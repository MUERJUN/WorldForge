package dev.worldforge.test;

import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.AgentOrchestrator;
import dev.worldforge.ai.ChatTurn;
import dev.worldforge.ai.MockToolProvider;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.ops.ToolEvent;
import dev.worldforge.ops.ToolEventStatus;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.workspace.WorkspaceManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;

/**
 * v0.7.0 flow C: export a standalone mod FROM THE CHAT.
 *
 * <p>The player asks for the export in the chat, the agent asks for the
 * capability report, the player confirms, and a REAL Gradle build runs. The
 * acceptance is the JAR on disk — a real, readable, valid Fabric mod — not
 * the chat text.</p>
 *
 * <p>The same exporter, the same plan and the same executor as the old export
 * screen: the chat only adds the conversation around them.</p>
 */
public class WorldForgeChatExportTests {

	/** the item that gets exported */
	private static final String ITEM_ID = "worldforge:chat_export_sword";

	/**
	 * System property that OPTS IN to this test.
	 *
	 * <p>A real Gradle build takes minutes, so the test does nothing unless it
	 * is explicitly enabled: {@code ./gradlew runGameTest -PwfChatExport}. The
	 * Linux CI enables it on every run.</p>
	 */
	public static final String OPT_IN = "worldforge.chat.export.gametest";

	/**
	 * How long the build wait lasts (ticks; 20 ticks = 1 second).
	 *
	 * <p>A cold Gradle build of the exported project downloads the Gradle
	 * distribution and the Fabric dependencies, so it can take anywhere from
	 * one to several minutes. 12000 ticks = 10 minutes, which is generous
	 * enough for a cold CI runner and bounded enough to fail instead of hang.</p>
	 */
	private static final long BUILD_WAIT_TICKS = 12_000;

	/** how long the first wait lasts (the report + the confirmation) */
	private static final long FIRST_WAIT_TICKS = 120;

	private static final class Recorder implements AgentOrchestrator.AgentEventSink {
		final List<ToolEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void onMessage(String conversationId, String role, String text) {
		}

		@Override
		public void onToolEvent(ToolEvent event) {
			events.add(event);
		}

		@Override
		public void onStatus(String conversationId, String status, String detail) {
		}

		ToolEvent pending() {
			for (ToolEvent e : events) {
				if (e.status() == ToolEventStatus.AWAITING_CONFIRMATION) {
					return e;
				}
			}
			return null;
		}

		ToolEvent finished(String tool) {
			for (ToolEvent e : events) {
				if (tool.equals(e.toolName())
						&& (e.status() == ToolEventStatus.SUCCEEDED
								|| e.status() == ToolEventStatus.FAILED
								|| e.status() == ToolEventStatus.CANCELLED)) {
					return e;
				}
			}
			return null;
		}

		String dump() {
			StringBuilder sb = new StringBuilder();
			for (ToolEvent e : events) {
				sb.append(" | ").append(e.status()).append(" ").append(e.toolName())
						.append(" -> ").append(e.summary());
				if (!e.errorCode().isBlank()) {
					sb.append(" (").append(e.errorCode()).append(')');
				}
			}
			return sb.toString();
		}
	}


	/**
	 * Flow C: the chat really builds a standalone mod.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-chat-export",
			tickLimit = 2_000_000)
	public void flowC_chatExportProducesARealJar(TestContext ctx) {
		if (!Boolean.getBoolean(OPT_IN)) {
			// not opted in — the real build is verified separately (see the CI
			// workflow and docs/ACCEPTANCE-v0.7.0.md)
			ctx.complete();
			return;
		}
		var dir = TestTempDirs.create("wf-flow-c");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		// a real item to export
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1, ITEM_ID, "聊天导出剑", "",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 14.0, "max_durability", 900.0),
				List.of(new dev.worldforge.definition.BehaviorDefinition(
						"right_click", "summon_lightning", 100, Map.of()))));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));

		// the scripted model: first the capability report, then the export
		var provider = MockToolProvider.scripted(List.of(
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1",
						"check_export_capabilities",
						"{\"item_ids\":[\"" + ITEM_ID + "\"]}"))),
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c2", "export_mod",
						"{\"item_ids\":[\"" + ITEM_ID + "\"],\"mod_id\":\"chat_export_mod\","
								+ "\"mod_name\":\"Chat Export Mod\",\"version\":\"1.0.0\"}"))),
				new ChatTurn.Turn.Final("导出完成了。")));
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider,
				new AgentConfig(true, "http://mock", "mock", "sk-test", 300, 500, 0, 100, 12, 600));

		var recorder = new Recorder();
		String conversationId = "flow-c";
		AgentOrchestrator.send(server, player, conversationId, "把刚才的剑导出成一个独立 Mod", null, recorder);

		// --- 1) the capability report, then the confirmation ---
		ctx.waitAndRun(FIRST_WAIT_TICKS, () -> {
			var report = recorder.finished("check_export_capabilities");
			ctx.assertTrue(report != null, "the agent must ask for the capability report "
					+ recorder.dump());
			ctx.assertTrue(report != null && report.status() == ToolEventStatus.SUCCEEDED,
					"the capability report must succeed: " + report);

			var pending = recorder.pending();
			ctx.assertTrue(pending != null, "the export must ask for a confirmation " + recorder.dump());
			if (pending == null) {
				return;
			}
			// the confirmation must name what would be LOST
			ctx.assertTrue(pending.summary().contains("无法导出"),
					"the confirmation must name the capabilities that cannot be exported: "
							+ pending.summary());
			boolean ok = AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId());
			ctx.assertTrue(ok, "the confirmation must be accepted");

			// --- 2) the real build runs ---
			//
			// ONE wait, scheduled from inside the confirmation wait so that it
			// is measured from NOW. The GameTest tick budget is anchored at the
			// moment the test was QUEUED, so a wait scheduled from the test
			// method body can already be "in the past" when the test starts.
			ctx.waitAndRun(BUILD_WAIT_TICKS, () -> {
						var exportEvent = recorder.finished("export_mod");
						// the JAR on disk is the real acceptance; the event is the
						// conversation's view of it
						if (exportEvent == null && findJar() == null) {
							ctx.assertTrue(false, "the export must report a final state " + recorder.dump());
							return;
						}
						ctx.assertTrue(exportEvent != null
									&& exportEvent.status() == ToolEventStatus.SUCCEEDED,
								"the export must succeed: " + (exportEvent == null ? "(no event)"
										: exportEvent.summary() + " / " + exportEvent.errorCode())
										+ recorder.dump());

						// the JAR really exists, in the workspace
						Path jar = findJar();
						ctx.assertTrue(jar != null, "a JAR must exist in the workspace");
						if (jar == null) {
							return;
						}
						try {
							ctx.assertTrue(Files.size(jar) > 1024, "the JAR must not be empty");
						} catch (java.io.IOException e) {
							ctx.assertTrue(false, "the JAR must be readable: " + e);
						}
						// and it really is a Fabric mod
						try (ZipFile zip = new ZipFile(jar.toFile())) {
							var entry = zip.getEntry("fabric.mod.json");
							ctx.assertTrue(entry != null, "the JAR must contain fabric.mod.json");
							if (entry != null) {
								String json = new String(zip.getInputStream(entry).readAllBytes(),
										java.nio.charset.StandardCharsets.UTF_8);
								ctx.assertTrue(json.contains("chat_export_mod"),
										"the exported mod must be the one that was asked for");
							}
						} catch (Exception e) {
							ctx.assertTrue(false, "the JAR must be a readable zip: " + e);
						}
						ctx.complete();
				});
		});
	}

	/** Finds the exported JAR in the workspace. */
	private static Path findJar() {
		Path dir = WorkspaceManager.exportsDir();
		if (!Files.isDirectory(dir)) {
			return null;
		}
		try (var stream = Files.walk(dir, 5)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(p -> !p.getFileName().toString().contains("sources"))
					.findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}
}
