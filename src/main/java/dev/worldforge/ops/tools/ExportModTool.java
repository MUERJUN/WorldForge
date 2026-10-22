package dev.worldforge.ops.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.worldforge.WorldForge;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.exporter.ExportExecutor;
import dev.worldforge.exporter.ExportPlan;
import dev.worldforge.exporter.ModExporter;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolEvent;
import dev.worldforge.ops.ToolEventSink;
import dev.worldforge.ops.ToolEventStatus;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolPreparer;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.workspace.WorkspaceManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code export_mod} — build a standalone Fabric mod from the current
 * definitions, driven from the chat.
 *
 * <p>This tool does NOT reimplement the exporter. It creates the same
 * {@link ExportPlan} the export screen creates, runs the same
 * {@link ExportExecutor}, and therefore inherits the same validation, the same
 * degradation guard and the same failure rollback (a failed build deletes its
 * JARs and keeps the source project for inspection).</p>
 *
 * <p>It is a LONG RUNNING tool: the Gradle build must not block the server
 * thread, so the orchestrator runs it on a worker thread. Only the plan
 * creation hops onto the server thread, because it needs the live
 * definitions.</p>
 */
public final class ExportModTool {

	public static final String NAME = "export_mod";

	/** hard cap on how long the chat waits for a build */
	public static final long MAX_WAIT_MINUTES = 12;

	private ExportModTool() {
	}

	public static ToolDescriptor descriptor() {
		JsonObject props = new JsonObject();
		props.add("item_ids", CheckExportCapabilitiesTool.stringArraySchema("要导出的物品 id 列表"));
		props.add("block_ids", CheckExportCapabilitiesTool.stringArraySchema("要导出的方块 id 列表"));
		props.add("mod_id", JsonSchemaValidator.prop("string", "导出 mod 的 id，例如 my_thunder_mod"));
		props.add("mod_name", JsonSchemaValidator.prop("string", "导出 mod 的显示名称"));
		props.add("version", JsonSchemaValidator.prop("string", "mod 版本号，例如 1.0.0"));
		// only the mod id is REQUIRED: exporting items only, or blocks only, must
		// both be possible (the tool checks that at least one id is given)
		JsonObject schema = JsonSchemaValidator.object(props, List.of("mod_id"));
		return ToolDescriptor.longRunningAdmin(NAME,
				"把选定的物品和方块构建成一个独立的 Fabric Mod JAR。会真的运行 Gradle 构建，"
						+ "过程中会在聊天里显示真实进度；完成后给出 JAR 名称和工作区中的相对位置。",
				schema,
				// the budget FOLLOWS the build: it must never be shorter than
				// the Gradle build the tool actually waits for
				dev.worldforge.ai.AgentTimeouts.EXPORT_TOOL_SECONDS);
	}

	public static final ToolExecutor EXECUTOR = (ctx, request, sink) -> {
		List<String> itemIds = normalize(CheckExportCapabilitiesTool.stringList(request, "item_ids"));
		List<String> blockIds = normalize(CheckExportCapabilitiesTool.stringList(request, "block_ids"));
		if (itemIds.isEmpty() && blockIds.isEmpty()) {
			return ToolResult.fail("invalid_arguments", "至少需要一个物品或方块 id");
		}
		String modId = ToolDefinitions.str(request, "mod_id");
		if (modId == null) {
			return ToolResult.fail("invalid_arguments", "缺少 mod_id");
		}
		String rawModName = ToolDefinitions.str(request, "mod_name");
		final String modName = rawModName == null ? modId : rawModName;
		String rawVersion = ToolDefinitions.str(request, "version");
		final String version = rawVersion == null ? "1.0.0" : rawVersion;

		// --- resolve the definitions FIRST, ON THE SERVER THREAD ---
		//
		// v0.7.1: this used to read BlockRuntimeManager from the worker
		// thread. Its map is a plain LinkedHashMap that the server thread
		// mutates, so an off-thread read can observe a half-updated state.
		var missing = onServerThread(ctx, () -> {
			for (String id : itemIds) {
				if (WorldForgeRuntimeManager.get(id) == null) {
					return "物品定义不存在: " + id;
				}
			}
			for (String id : blockIds) {
				if (BlockRuntimeManager.get(id) == null) {
					return "方块定义不存在: " + id;
				}
			}
			return "";
		});
		if (missing == null) {
			return ToolResult.fail("server_error", "无法在服务端线程上校验定义");
		}
		if (!missing.isBlank()) {
			return ToolResult.fail("not_found", missing);
		}

		// the exporter refuses to write into a directory that is not empty, so
		// every export gets its OWN fresh directory under the workspace
		Path outDir = freeExportDir(modId);
		String outDirText = outDir.toString();

		// --- create the immutable plan ON THE SERVER THREAD ---
		// ExportPlan.create resolves the ids against the LIVE runtime managers
		// and freezes the definitions + capability report in one atomic step.
		AtomicReference<ExportPlan> planRef = new AtomicReference<>();
		AtomicReference<Exception> planError = new AtomicReference<>();
		CountDownLatch planLatch = new CountDownLatch(1);
		ctx.server().execute(() -> {
			try {
				planRef.set(ExportPlan.create(modId, modName, version, outDirText,
						List.copyOf(itemIds), List.copyOf(blockIds)));
			} catch (Exception e) {
				planError.set(e);
			} finally {
				planLatch.countDown();
			}
		});
		try {
			if (!planLatch.await(30, TimeUnit.SECONDS)) {
				return ToolResult.fail("server_error", "创建导出方案超时");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return ToolResult.fail("cancelled", "导出已取消");
		}
		if (planError.get() != null) {
			return ToolResult.fail("invalid_arguments",
					"无法创建导出方案: " + planError.get().getMessage());
		}
		ExportPlan plan = planRef.get();
		if (plan == null) {
			return ToolResult.fail("server_error", "无法创建导出方案");
		}

		// --- the exporter's own guard: never export a degraded plan silently ---
		// The consent must come from the summary the player ACTUALLY approved:
		// every lost capability has to be named in it. A model that says
		// "the user accepted the degradation" cannot unlock this.
		ExportPlan exportPlan = plan;
		if (!plan.report().lost().isEmpty()) {
			String approved = ctx.confirmedSummary() == null ? "" : ctx.confirmedSummary();
			boolean allNamed = plan.report().lost().stream().allMatch(approved::contains);
			if (!allNamed) {
				return ToolResult.fail("degraded_not_confirmed",
						"有 " + plan.report().lost().size() + " 项能力无法导出，"
								+ "但玩家批准的摘要里没有说明这些损失，因此已阻止导出。");
			}
			exportPlan = plan.withDegradedConfirmation();
		}
		String degraded = ModExporter.degradedGuardError(exportPlan.report(),
				exportPlan.degradedExportConfirmed());
		if (degraded != null) {
			return ToolResult.fail("degraded_not_confirmed", degraded);
		}

		// --- run the build on THIS (worker) thread, never the server thread ---
		ExportExecutor executor = new ExportExecutor(exportPlan);
		// v0.7.1: a cancellation really stops the build — the Gradle process
		// AND its process tree are destroyed, and the produced JARs are
		// cleaned up by the exporter itself.
		if (ctx.task() != null) {
			ctx.task().onCancel(executor::cancel);
		}
		Thread progress = startProgressReporter(executor, sink, request);
		ModExporter.ExportResult result;
		try {
			result = executor.doExport();
		} catch (Exception e) {
			WorldForge.LOGGER.warn("[WorldForge] export tool failed", e);
			return ToolResult.fail("export_failed", "导出过程异常: " + e.getClass().getSimpleName());
		} finally {
			progress.interrupt();
		}

		JsonObject o = new JsonObject();
		o.addProperty("mod_id", plan.modId());
		o.addProperty("mod_name", plan.modName());
		o.addProperty("version", plan.version());
		boolean cancelled = ctx.task() != null && ctx.task().isCancelled();
		if (cancelled) {
			// the build was cancelled. If it had ALREADY finished before the
			// cancellation arrived, that must be said honestly: an artifact
			// that exists cannot be un-made.
			if (result.ok()) {
				// the build was already finished when the cancellation arrived:
				// the artifact EXISTS and cannot be un-made. Say so honestly —
				// it is not a failure, and it is not a cancellation either.
				o.addProperty("jar_name", result.jarPath() == null ? ""
						: result.jarPath().getFileName().toString());
				o.addProperty("jar_path", result.jarPath() == null ? "" : result.jarPath().toString());
				o.addProperty("sha256", result.sha256() == null ? "" : result.sha256());
				o.addProperty("already_completed", true);
				return new ToolResult(true, "", "导出在取消到达之前已经完成，产物已保留："
						+ (result.jarPath() == null ? "" : result.jarPath()),
						o.toString(), List.of(), true, true, "none");
			}
			o.addProperty("error", result.errorMessage() == null ? "已取消" : result.errorMessage());
			return ToolResult.cancelled("导出已取消，构建进程已终止，产物已清理。", o.toString());
		}
		if (result.ok()) {
			Path jar = result.jarPath();
			o.addProperty("jar_name", jar == null ? "" : jar.getFileName().toString());
			o.addProperty("jar_path", jar == null ? "" : jar.toString());
			o.addProperty("relative_path", relativeToWorkspace(jar));
			o.addProperty("sha256", result.sha256() == null ? "" : result.sha256());
			o.addProperty("project_dir", result.projectDir() == null ? "" : result.projectDir().toString());
			JsonArray warnings = new JsonArray();
			if (result.warnings() != null) {
				result.warnings().forEach(warnings::add);
			}
			o.add("warnings", warnings);
			return ToolResult.ok("导出成功：" + (jar == null ? "" : jar.getFileName())
					+ "（" + relativeToWorkspace(jar) + "）", o.toString());
		}
		o.addProperty("error", result.errorMessage() == null ? "未知错误" : result.errorMessage());
		o.addProperty("project_dir", result.projectDir() == null ? "" : result.projectDir().toString());
		return ToolResult.fail("export_failed", "导出失败：" + result.errorMessage());
	};

	/**
	 * Prepare step: compute the degradation report so the confirmation the
	 * player reads names the exact capabilities that will be lost.
	 */
	public static final ToolPreparer PREPARER = (ctx, request) -> {
		List<String> itemIds = CheckExportCapabilitiesTool.stringList(request, "item_ids");
		List<String> blockIds = CheckExportCapabilitiesTool.stringList(request, "block_ids");
		if (itemIds.isEmpty() && blockIds.isEmpty()) {
			return ToolPreparation.none();
		}
		String modId = ToolDefinitions.str(request, "mod_id");
		if (modId == null) {
			return ToolPreparation.none();
		}
		var items = new java.util.ArrayList<ItemDefinition>();
		for (String id : itemIds) {
			ItemDefinition d = WorldForgeRuntimeManager.get(id);
			if (d == null) {
				return ToolPreparation.none();
			}
			items.add(d);
		}
		var blocks = new java.util.ArrayList<BlockDefinition>();
		for (String id : blockIds) {
			BlockDefinition d = BlockRuntimeManager.get(id);
			if (d == null) {
				return ToolPreparation.none();
			}
			blocks.add(d);
		}
		var report = ModExporter.capabilityReportOf(items, blocks);
		StringBuilder sb = new StringBuilder();
		sb.append("将把 ").append(items.size()).append(" 个物品、").append(blocks.size())
				.append(" 个方块构建成独立 Mod「").append(modId).append("」。");
		if (report.lost().isEmpty()) {
			sb.append("所有能力都能完整导出。");
		} else {
			sb.append("注意：有 ").append(report.lost().size())
					.append(" 项能力无法导出（确认后将以降级方式导出）：");
			report.lost().forEach(l -> sb.append(" · ").append(l));
		}
		if (!report.converted().isEmpty()) {
			sb.append("另有 ").append(report.converted().size()).append(" 项会以等价但不同的方式实现。");
		}
		return ToolPreparation.of(itemIds, blockIds, sb.toString(), "",
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};

	/**
	 * Watches the executor's audit trail and turns new lines into real chat
	 * events, so the player sees the build actually progressing.
	 */
	private static Thread startProgressReporter(ExportExecutor executor, ToolEventSink sink, ToolRequest request) {
		Thread t = new Thread(() -> {
			int seen = 0;
			long last = System.currentTimeMillis();
			while (!Thread.currentThread().isInterrupted()) {
				List<String> trail = executor.auditTrail();
				if (trail.size() > seen) {
					for (int i = seen; i < trail.size(); i++) {
						String line = trail.get(i);
						if (line == null || line.isBlank()) {
							continue;
						}
						sink.emit(new ToolEvent(request.conversationId(), request.requestId(),
								request.toolCallId(), 0, System.currentTimeMillis(),
								NAME, ToolEventStatus.RUNNING, line, "", List.of()));
					}
					seen = trail.size();
					last = System.currentTimeMillis();
				} else if (System.currentTimeMillis() - last > 20_000) {
					// a heartbeat so the chat never looks frozen during a long build
					sink.emit(new ToolEvent(request.conversationId(), request.requestId(),
							request.toolCallId(), 0, System.currentTimeMillis(),
							NAME, ToolEventStatus.RUNNING, "Gradle 构建进行中…", "", List.of()));
					last = System.currentTimeMillis();
				}
				try {
					Thread.sleep(400);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}, "wf-export-progress");
		t.setDaemon(true);
		t.start();
		return t;
	}

	/** Normalises the ids the way every other tool does. */
	private static List<String> normalize(List<String> ids) {
		List<String> out = new java.util.ArrayList<>();
		for (String id : ids) {
			String n = dev.worldforge.tool.WorldForgeTools.normalizeId(id);
			if (n != null && !n.isBlank()) {
				out.add(n);
			}
		}
		return out;
	}

	/** Runs a piece of work on the server thread and waits for its result. */
	private static <T> T onServerThread(dev.worldforge.ops.ToolContext ctx,
			java.util.function.Supplier<T> work) {
		if (ctx.server() == null) {
			return work.get();
		}
		var ref = new java.util.concurrent.atomic.AtomicReference<T>();
		var err = new java.util.concurrent.atomic.AtomicReference<Exception>();
		var latch = new java.util.concurrent.CountDownLatch(1);
		ctx.server().execute(() -> {
			try {
				ref.set(work.get());
			} catch (Exception e) {
				err.set(e);
			} finally {
				latch.countDown();
			}
		});
		try {
			if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
				return null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		return err.get() == null ? ref.get() : null;
	}

	/**
	 * Finds a free directory for one export.
	 *
	 * <p>The exporter refuses a non-empty output directory (so it can never
	 * overwrite an earlier export), which is why each run gets its own
	 * {@code <modId>}, {@code <modId>-2}, ... slot under the workspace.</p>
	 */
	private static Path freeExportDir(String modId) {
		Path base = WorkspaceManager.exportsDir();
		String safe = modId == null || modId.isBlank() ? "export" : modId.replaceAll("[^a-zA-Z0-9_-]", "_");
		Path candidate = base.resolve(safe);
		int n = 2;
		while (Files.exists(candidate)) {
			candidate = base.resolve(safe + "-" + n);
			n++;
			if (n > 1000) {
				break;
			}
		}
		return candidate;
	}

	/** The artifact path relative to the WorldForge workspace, for display. */
	private static String relativeToWorkspace(Path jar) {
		if (jar == null) {
			return "";
		}
		try {
			return WorkspaceManager.root().toAbsolutePath().normalize()
					.relativize(jar.toAbsolutePath().normalize()).toString();
		} catch (Exception e) {
			return jar.getFileName().toString();
		}
	}
}
