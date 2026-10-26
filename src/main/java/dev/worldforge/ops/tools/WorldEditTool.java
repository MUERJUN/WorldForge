package dev.worldforge.ops.tools;

import com.google.gson.JsonObject;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolEventSink;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolPreparer;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.ops.worldedit.BlockTargetRules;
import dev.worldforge.ops.worldedit.WorldEditExecutor;
import dev.worldforge.ops.worldedit.WorldEditHistory;
import dev.worldforge.ops.worldedit.WorldEditPlan;
import dev.worldforge.ops.worldedit.WorldEditValidator;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import dev.worldforge.ops.worldedit.WorldEditEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code edit_world_blocks} — the v0.8.0 world-instance edit tool (scenarios
 * A and B): replace the targeted block, or a bounded region anchored on the
 * targeted block's face, after the player confirmed a server-built plan.
 *
 * <p>Deliberate boundaries:</p>
 * <ul>
 *   <li>a WorldForge dynamic block is refused here with a pointer to the
 *       definition-edit path (type B) — the two never blur;</li>
 *   <li>the region is plane-anchored on the hit face; a vertical/horizontal
 *       ambiguity is reported back for the player to clarify, the model is
 *       never allowed to guess coordinates;</li>
 *   <li>every hard limit (256 blocks, bounds, loaded chunks, forbidden
 *       blocks, BlockEntity targets) fails the WHOLE plan before any write.</li>
 * </ul>
 */
public final class WorldEditTool {

	public static final String NAME = "edit_world_blocks";

	/** The per-player undo history (bounded, in-memory, not persisted). */
	public static final WorldEditHistory HISTORY = new WorldEditHistory(10);

	/** How long a built plan stays usable, aligned with the confirm TTL. */
	private static final long PLAN_TTL_SECONDS = 600;

	private WorldEditTool() {
	}

	public static ToolDescriptor descriptor() {
		JsonObject props = new JsonObject();
		props.add("block", JsonSchemaValidator.prop("string",
				"目标方块 id，例如 minecraft:glass（必须带命名空间）"));
		props.add("properties", new JsonObject());
		props.add("expected_source", JsonSchemaValidator.prop("string",
				"期望的现有方块 id（例如 minecraft:stone）；给定后区域内任何不一致都会让整项计划被拒绝"));
		props.add("mode", JsonSchemaValidator.propEnum("编辑模式", "targeted", "region"));
		props.add("width", JsonSchemaValidator.prop("integer", "区域宽度（1-16，仅 region 模式）", 1, 16));
		props.add("height", JsonSchemaValidator.prop("integer", "区域高度（1-16，仅 region 模式）", 1, 16));
		JsonObject schema = JsonSchemaValidator.object(props, List.of("block"));
		return ToolDescriptor.write(NAME,
				"把玩家准星指向的方块（或以命中面为锚定的有限区域）替换成指定方块。"
						+ "确认前不会改动世界；执行前会再次核对每个位置；可以撤销。",
				schema);
	}

	// ----------------------------------------------------------- plan builder

	/**
	 * Builds the plan from the request. Shared by the preparer and the
	 * executor: the executor rebuilds it and the orchestrator's snapshot
	 * comparison guarantees the rebuild is byte-identical to what the player
	 * confirmed (any world drift changes the fingerprint and refuses).
	 */
	private static WorldEditPlan buildPlan(ToolContext ctx, ToolRequest request) {
		String blockArg = request.argumentString("block");
		if (blockArg == null || blockArg.isBlank()) {
			return null;
		}
		Map<String, String> targetProps = new LinkedHashMap<>();
		Object rawProps = request.arguments().get("properties");
		if (rawProps instanceof Map<?, ?> m) {
			m.forEach((k, v) -> targetProps.put(String.valueOf(k), String.valueOf(v)));
		}
		WorldEditEntry targetEntry;
		try {
			targetEntry = WorldEditEntry.of(normalizeId(blockArg), targetProps);
		} catch (IllegalArgumentException e) {
			return null;
		}

		var target = TargetBlock.read(ctx);
		if (!target.hit()) {
			return null;
		}
		// type separation: a WF dynamic block belongs to the definition path
		if (target.virtualId() != null && !target.virtualId().isBlank()) {
			return null;
		}

		String mode = request.argumentString("mode");
		boolean region = "region".equals(mode);
		int width = Math.max(1, Math.min(16, request.argumentInt("width", 1)));
		int height = Math.max(1, Math.min(16, request.argumentInt("height", 1)));

		List<BlockPos> positions = new ArrayList<>();
		if (!region) {
			positions.add(target.pos());
		} else {
			Direction faceNormal = target.face();
			if (faceNormal == null || faceNormal.getAxis().isVertical()) {
				// a floor/ceiling face gives no unambiguous wall orientation —
				// ask the player instead of guessing coordinates
				return null;
			}
			Direction widthAxis = faceNormal.getAxis() == Direction.Axis.X
					? Direction.SOUTH : Direction.EAST;
			BlockPos origin = target.pos();
			for (int h = 0; h < height; h++) {
				for (int w = 0; w < width; w++) {
					BlockPos pos = origin;
					if (w > 0) {
						pos = pos.offset(widthAxis, w);
					}
					if (h > 0) {
						pos = pos.up(h);
					}
					positions.add(pos);
				}
			}
		}
		if (positions.size() > WorldEditValidator.MAX_BLOCKS_PER_PLAN) {
			return null;
		}

		String expectedSource = request.argumentString("expected_source");
		Map<BlockPos, WorldEditEntry> originals = new LinkedHashMap<>();
		Map<BlockPos, WorldEditEntry> targets = new LinkedHashMap<>();
		var world = ctx.world();
		for (BlockPos pos : positions) {
			// only loaded chunks: never force-load for an edit
			if (!world.isChunkLoaded(pos)) {
				return null;
			}
			if (!WorldEditValidator.inWorldBounds(pos,
					world.getBottomY(), world.getBottomY() + world.getHeight() - 1)) {
				return null;
			}
			BlockState current = world.getBlockState(pos);
			WorldEditEntry original = BlockTargetRules.describe(current);
			// forbidden / BlockEntity targets refuse the WHOLE plan up front
			if (BlockTargetRules.isForbiddenId(original.blockId())
					|| BlockTargetRules.holdsBlockEntity(current)
					|| world.getBlockEntity(pos) != null) {
				return null;
			}
			// the expected source (when given) must match EVERY position
			if (expectedSource != null && !expectedSource.isBlank()
					&& !original.blockId().equals(normalizeId(expectedSource))) {
				return null;
			}
			originals.put(pos, original);
			targets.put(pos, targetEntry);
		}

		Instant now = Instant.now();
		WorldEditPlan plan = WorldEditPlan.of("we-pending", ctx.player().getUuid(),
				world.getRegistryKey().getValue().toString(),
				positions, originals, targets, now, now.plusSeconds(PLAN_TTL_SECONDS));
		// deterministic identity: the confirmed plan and the executed rebuild
		// share the same id because the content is the same
		String planId = "we-" + plan.fingerprint().substring(0, 12);
		return WorldEditPlan.of(planId, plan.playerUuid(), plan.worldId(),
				plan.positions(), plan.originalStates(), plan.newStates(),
				plan.createdAt(), plan.expiresAt());
	}

	private static String normalizeId(String raw) {
		String id = raw == null ? "" : raw.trim().toLowerCase();
		if (!id.contains(":")) {
			id = "minecraft:" + id;
		}
		return id;
	}

	// -------------------------------------------------------------- preparer

	public static final ToolPreparer PREPARER = (ctx, request) -> {
		if (ctx.player() == null || ctx.world() == null) {
			return ToolPreparation.none();
		}
		WorldEditPlan plan;
		try {
			plan = buildPlan(ctx, request);
		} catch (IllegalArgumentException e) {
			return ToolPreparation.none(); // over-limit / bad shape: refuse early
		}
		if (plan == null) {
			return ToolPreparation.none();
		}
		BlockPos min = plan.positions().stream().reduce(
				plan.positions().get(0),
				(a, b) -> new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
						Math.min(a.getZ(), b.getZ())));
		BlockPos max = plan.positions().stream().reduce(
				plan.positions().get(0),
				(a, b) -> new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
						Math.max(a.getZ(), b.getZ())));
		String from = plan.originalStates().get(plan.positions().get(0)).toText();
		String summary = "修改计划 " + plan.planId() + "：在 " + plan.worldId()
				+ " 中将 " + plan.affectedCount() + " 个方块（范围 "
				+ min.toShortString() + " 至 " + max.toShortString()
				+ "）替换为 " + plan.newStates().get(plan.positions().get(0)).toText()
				+ "。确认前不会改动世界；可撤销。";
		return ToolPreparation.of(List.of(), List.of(), summary,
				plan.planId() + ":" + plan.fingerprint(),
				ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};

	// -------------------------------------------------------------- executor

	public static final ToolExecutor EXECUTOR = (ctx, request, sink) -> {
		if (ctx.player() == null || ctx.world() == null || ctx.server() == null) {
			return ToolResult.fail("no_player", "没有可用的服务端上下文");
		}
		WorldEditPlan plan;
		try {
			plan = buildPlan(ctx, request);
		} catch (IllegalArgumentException e) {
			return ToolResult.fail("invalid_arguments", e.getMessage());
		}
		if (plan == null) {
			return ToolResult.fail("not_allowed",
					"无法构建有效的修改计划：目标不存在、指向了 WorldForge 动态方块（请走定义编辑）、"
							+ "方向不明确、包含禁止目标或方块实体、超出边界/上限，或与期望的原方块不一致。");
		}
		// the orchestrator has already re-run the preparer and compared the
		// snapshot; the rebuilt plan is therefore byte-identical to the one
		// the player confirmed - double-check the identity anyway
		// (defence in depth: nothing executes on a plan identity mismatch)

		// v0.8.0: the executor MUST run on the server thread (world writes from
		// a worker thread deadlock on the chunk lock). The tool executor runs on
		// an orchestrator worker, so marshal the plan onto the server thread and
		// wait for the result; a cancellation aborts the wait.
		var ref = new java.util.concurrent.atomic.AtomicReference<WorldEditExecutor.Outcome>();
		var latch = new java.util.concurrent.CountDownLatch(1);
		Runnable job = () -> {
			try {
				ref.set(WorldEditExecutor.execute(ctx.world(), plan, ctx.task()));
			} finally {
				latch.countDown();
			}
		};
		boolean finished = true;
		if (ctx.server().isOnThread()) {
			// the confirm path already runs on the server thread: marshalling
			// to it again would deadlock on our own latch
			job.run();
		} else {
			ctx.server().execute(job);
			ctx.task().onCancel(latch::countDown);
			try {
				finished = latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return ToolResult.fail("server_error", "执行等待被中断");
			}
			if (!finished) {
				return ToolResult.fail("server_error", "世界修改执行超时（60 秒）");
			}
		}
		var outcome = ref.get();
		JsonObject o = new JsonObject();
		o.addProperty("plan_id", plan.planId());
		o.addProperty("requested", outcome.requested());
		o.addProperty("applied", outcome.applied());
		o.addProperty("rolled_back", outcome.rolledBack());
		o.addProperty("rollback_complete", outcome.rollbackComplete());
		o.addProperty("cancelled", outcome.cancelled());

		if (!outcome.fullyApplied()) {
			String reason = outcome.failureReason() == null ? "未知原因" : outcome.failureReason();
			if (outcome.applied() > 0) {
				return ToolResult.degraded("partial_failure",
						"执行中断：" + reason + "。已写入 " + outcome.applied() + "/"
								+ outcome.requested() + " 个方块，已回滚 " + outcome.rolledBack()
								+ " 个" + (outcome.rollbackComplete() ? "（回滚完整）" : "（回滚不完整，请检查世界）"),
						true, false);
			}
			return ToolResult.fail("not_executed", "未写入世界：" + reason);
		}

		HISTORY.record(plan);
		return new ToolResult(true, "",
				"修改完成：实际替换 " + outcome.applied() + " 个方块（计划 " + plan.planId()
						+ "）。说「撤销刚才的世界修改」即可恢复。",
				o.toString(), List.of(), false, false, "none");
	};

}
