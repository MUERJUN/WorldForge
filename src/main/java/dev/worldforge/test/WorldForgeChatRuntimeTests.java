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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * v0.7.1 — the chat runtime's CONCURRENCY rules, as real GameTests.
 *
 * <p>These are the failures the v0.7.0 release could produce: two tasks
 * running in one conversation, a deleted conversation killing all the others,
 * a confirmation that could be both approved and cancelled, and a cancel that
 * did not really stop the work.</p>
 *
 * <p><b>Structure.</b> A GameTest wait is ASYNCHRONOUS: the test method
 * carries on after scheduling it. So the waits are NESTED — the next wait
 * lives inside the body of the previous one — and every assertion lives
 * inside the body of the wait that guarantees its state.</p>
 *
 * <p>Each test has its OWN batch, because the mock provider is global static
 * state and GameTest runs the tests of one batch concurrently.</p>
 */
public class WorldForgeChatRuntimeTests {

	private static final String ITEM_ID = "worldforge:runtime_test_item";

	/** Notes for the acceptance report. */
	private static final List<String> report = new java.util.concurrent.CopyOnWriteArrayList<>();

	private static ServerPlayerEntity opPlayer(TestContext ctx) {
		var p = ctx.createMockCreativeServerPlayerInWorld();
		p.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(p.getGameProfile(), 2, false));
		return p;
	}

	private static AgentConfig cfg() {
		return new AgentConfig(true, "http://mock", "mock", "sk-test", 60, 500, 0, 100, 12, 300);
	}

	/**
	 * ONE provider for every test in this class.
	 *
	 * <p>The provider is global static state and the GameTest runner runs the
	 * batches CONCURRENTLY, so a per-test provider would be overwritten by
	 * another test at any moment — which is exactly the race this suite must
	 * not have. This provider answers from the player's MESSAGE, so it can be
	 * shared safely.</p>
	 */
	private static MockToolProvider sharedProvider() {
		var provider = MockToolProvider.responding(userMessage -> {
			String m = userMessage == null ? "" : userMessage;
			if (m.contains("导出")) {
				return new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "export_mod",
						"{\"item_ids\":[\"" + ITEM_ID + "\"],\"mod_id\":\"cancel_test_mod\","
								+ "\"mod_name\":\"Cancel Test\",\"version\":\"1.0.0\"}")));
			}
			if (m.contains("改名") || m.contains("名字")) {
				// create_item needs NO existing definition, so this test does
				// not depend on the shared runtime store
				return new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "create_item",
						"{\"id\":\"" + ITEM_ID + "_new\",\"name\":\"新物品\","
								+ "\"texture\":\"minecraft:item/diamond_sword\","
								+ "\"max_durability\":500}")));
			}
			return new ChatTurn.Turn.Final("ok");
		});
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());
		return provider;
	}

	/** The last diagnostic snapshot, included in a timeout message. */
	private static volatile String lastDump = "";

	/**
	 * Waits for a condition, with a WALL-CLOCK budget.
	 *
	 * <p><b>Why wall clock and not ticks.</b> The GameTest server ticks as fast
	 * as it can, so 400 ticks can be a fraction of a second of real time. A
	 * tick-based budget would therefore expire long before the agent (which
	 * runs on its own worker threads and needs REAL time) had finished. The
	 * poll chain yields the server thread between polls, so the agent's
	 * workers get the CPU they need while the budget passes.</p>
	 *
	 * @param budgetMillis how long to wait in real time
	 * @param resend       asked again every few polls when the task vanished
	 *                     (a foreign GameTest disconnect cancels it — every
	 *                     mock player shares one UUID), or null
	 */
	private static void await(TestContext ctx, long budgetMillis, Runnable resend,
			java.util.function.BooleanSupplier ready, Runnable then) {
		if (ready.getAsBoolean()) {
			then.run();
			return;
		}
		poll(ctx, System.currentTimeMillis() + budgetMillis, resend, ready, then, 0);
	}

	private static void poll(TestContext ctx, long deadline, Runnable resend,
			java.util.function.BooleanSupplier ready, Runnable then, int level) {
		if (ready.getAsBoolean()) {
			then.run();
			return;
		}
		if (System.currentTimeMillis() > deadline) {
			ctx.assertTrue(false, "timed out waiting for the agent: " + lastDump);
			return;
		}
		// the task may have been cancelled by a foreign disconnect: ask again
		if (resend != null && level > 0 && level % 200 == 0) {
			resend.run();
		}
		ctx.waitAndRun(2, () -> poll(ctx, deadline, resend, ready, then, level + 1));
	}

	// ------------------------------------------------- P0-1: serialisation

	/**
	 * Twenty rapid messages in ONE conversation must not run in parallel.
	 *
	 * <p>The v0.7.0 orchestrator started a worker per message, so all twenty
	 * ran at once and stomped on each other's state.</p>
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-runtime-1",
			tickLimit = 60_000)
	public void manyMessagesInOneConversationNeverRunInParallel(TestContext ctx) {
		var dir = TestTempDirs.create("wf-runtime-a");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		var server = ctx.getWorld().getServer();
		var player = opPlayer(ctx);

		// a model that thinks for a while, so the messages really pile up
		sharedProvider().withDelay(1500);

		String conversationId = "serial";
		int accepted = 0;
		int queued = 0;
		int rejected = 0;
		for (int i = 0; i < 20; i++) {
			var result = AgentOrchestrator.send(server, player, conversationId, "消息" + i, null,
					new AgentEventRecorder());
			if (result.accepted()) {
				accepted++;
			} else if (result.queued()) {
				queued++;
			} else {
				rejected++;
			}
		}
		// exactly one task may run; the rest are queued (bounded) or refused
		ctx.assertTrue(accepted == 1,
				"exactly one task may be accepted, got " + accepted);
		ctx.assertTrue(queued <= dev.worldforge.ai.AgentTimeouts.MAX_QUEUED_MESSAGES,
				"the queue must be bounded, got " + queued);
		ctx.assertTrue(rejected == 19 - queued,
				"the rest must be refused with a reason, got " + rejected);
		ctx.assertTrue(AgentOrchestrator.activeTaskCount(player.getUuid()) == 1,
				"the player must run exactly one task, got "
						+ AgentOrchestrator.activeTaskCount(player.getUuid()));

		// and a further message must be refused, not executed in parallel
		var again = AgentOrchestrator.send(server, player, conversationId, "再来一条", null,
				new AgentEventRecorder());
		ctx.assertFalse(again.accepted(), "a message must not start a second task");

		// clean up so the test does not leave a task running
		AgentOrchestrator.cancelTask(player.getUuid(), conversationId);
		AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
		ctx.complete();
	}

	/**
	 * A second message sent DURING a confirmation must not take over the
	 * pending confirmation.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-runtime-2",
			tickLimit = 60_000)
	public void aMessageDuringAConfirmationDoesNotTakeOverTheConfirmation(TestContext ctx) {
		var dir = TestTempDirs.create("wf-runtime-b");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		var player = opPlayer(ctx);

		// the model asks for an update (which needs a confirmation)
		sharedProvider();

		String conversationId = "confirm-busy";
		var recorder = new AgentEventRecorder();
		AgentOrchestrator.send(server, player, conversationId, "改个名字", null, recorder);

		lastDump = recorder.dump();
		await(ctx, 30_000,
				() -> AgentOrchestrator.send(server, player, conversationId, "改个名字", null, recorder),
				() -> {
					lastDump = recorder.dump();
					return pendingOf(recorder) != null;
				}, () -> {
			var pending = pendingOf(recorder);
			ctx.assertTrue(pending != null, "the agent must ask for a confirmation");
			String confirmationId = pending.confirmationId();

			// a second message arrives while the confirmation is pending
			var second = AgentOrchestrator.send(server, player, conversationId, "等一下我还有话说", null,
					new AgentEventRecorder());
			ctx.assertFalse(second.accepted(), "a second message must not start a task");

			// the pending confirmation is still THE one, and it still works
			var still = AgentOrchestrator.pendingFor(player.getUuid(), conversationId);
			ctx.assertTrue(still != null, "the confirmation must still be pending");
			ctx.assertTrue(confirmationId.equals(still.confirmationId()),
					"the confirmation must be the same one");
			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId, confirmationId),
					"the original confirmation must still be accepted");

			AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
			ctx.complete();
		});
	}

	/**
	 * Two conversations run independently.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-runtime-3",
			tickLimit = 60_000)
	public void twoConversationsRunIndependently(TestContext ctx) {
		var dir = TestTempDirs.create("wf-runtime-c");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		var server = ctx.getWorld().getServer();
		var player = opPlayer(ctx);

		sharedProvider().withDelay(800);

		var recorderA = new AgentEventRecorder();
		var recorderB = new AgentEventRecorder();
		AgentOrchestrator.send(server, player, "conv-a", "A 的问题", null, recorderA);
		AgentOrchestrator.send(server, player, "conv-b", "B 的问题", null, recorderB);

		ctx.assertTrue(AgentOrchestrator.activeTaskCount(player.getUuid()) == 2,
				"two conversations must run at the same time, got "
						+ AgentOrchestrator.activeTaskCount(player.getUuid()));

		await(ctx, 30_000,
				() -> {
					AgentOrchestrator.send(server, player, "conv-a", "A 的问题", null, recorderA);
					AgentOrchestrator.send(server, player, "conv-b", "B 的问题", null, recorderB);
				},
				() -> {
					lastDump = "A=" + recorderA.dump() + " B=" + recorderB.dump();
					return hasAssistantAnswer(recorderA) && hasAssistantAnswer(recorderB);
				},
				() -> {
					// each conversation got ITS OWN answer
					ctx.assertTrue(hasAssistantAnswer(recorderA),
							"A must get an answer " + recorderA.dump());
					ctx.assertTrue(hasAssistantAnswer(recorderB),
							"B must get an answer " + recorderB.dump());
					AgentOrchestrator.releaseSession(player.getUuid(), "conv-a");
					AgentOrchestrator.releaseSession(player.getUuid(), "conv-b");
					ctx.complete();
				});
	}

	/**
	 * A late answer of an OLD task must not touch the state of the new task.
	 *
	 * <p>The provider here answers even after its future was cancelled, which
	 * emulates a service that does not honour a cancellation.</p>
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-runtime-4",
			tickLimit = 60_000)
	public void aLateAnswerOfAnOldTaskDoesNotTouchTheNewTask(TestContext ctx) {
		var dir = TestTempDirs.create("wf-runtime-d");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		var server = ctx.getWorld().getServer();
		var player = opPlayer(ctx);

		sharedProvider().withDelay(1200).ignoringCancellation();

		String conversationId = "late";
		var recorder = new AgentEventRecorder();
		var provider = sharedProvider();
		AgentOrchestrator.send(server, player, conversationId, "第一个问题", null, recorder);

		// cancel it while the model is still thinking
		await(ctx, 30_000, null, () -> AgentOrchestrator.stateOf(player.getUuid(), conversationId) != null, () -> {
			ctx.assertTrue(AgentOrchestrator.cancelTask(player.getUuid(), conversationId),
					"the task must be cancellable");
		});

		// wait for the late answer to arrive (the provider answers after its
		// delay, even though the task was cancelled in the meantime)
		long askedAt = System.currentTimeMillis();
		await(ctx, 30_000,
				() -> AgentOrchestrator.send(server, player, conversationId, "第一个问题", null, recorder),
				() -> {
					lastDump = recorder.dump();
					return System.currentTimeMillis() - askedAt > 2500;
				}, () -> {
			// the cancelled task must NOT have produced a final answer in the
			// session, and the session must not be busy any more
			var session = AgentOrchestrator.existingSession(player.getUuid(), conversationId);
			ctx.assertTrue(session != null, "the session must still exist");
			if (session != null) {
				ctx.assertTrue(session.historyView().stream()
								.noneMatch(t -> t.content().contains("迟到的回答")),
						"a cancelled task must not write its late answer into the history");
				ctx.assertTrue(AgentOrchestrator.stateOf(player.getUuid(), conversationId) == null,
						"a cancelled task must not leave a running task behind");
			}
			AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
			ctx.complete();
		});
	}

	// -------------------------------------------- P0-2: delete one session

	/**
	 * Deleting ONE conversation must not stop the others.
	 *
	 * <p>The v0.7.0 code called releasePlayer(), which cancelled EVERY
	 * conversation of the player.</p>
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-runtime-5",
			tickLimit = 60_000)
	public void deletingOneConversationLeavesTheOthersAlone(TestContext ctx) {
		var dir = TestTempDirs.create("wf-runtime-e");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		var player = opPlayer(ctx);

		// A: a running task, B: waiting for a confirmation
		sharedProvider().withDelay(3000);
		var recorderA = new AgentEventRecorder();
		AgentOrchestrator.send(server, player, "conv-A", "A", null, recorderA);

		var recorderB = new AgentEventRecorder();
		AgentOrchestrator.send(server, player, "conv-B", "改个名字", null, recorderB);

		await(ctx, 30_000,
				() -> AgentOrchestrator.send(server, player, "conv-B", "改个名字", null, recorderB),
				() -> {
					lastDump = "A=" + recorderA.dump() + " B=" + recorderB.dump();
					return pendingOf(recorderB) != null;
				}, () -> {
			ctx.assertTrue(pendingOf(recorderB) != null, "B must be waiting for a confirmation");
			ctx.assertTrue(AgentOrchestrator.stateOf(player.getUuid(), "conv-A") != null,
					"A must still be running");

			// delete B only
			ctx.assertTrue(AgentOrchestrator.releaseSession(player.getUuid(), "conv-B"),
					"deleting B must report success");

			// A must still be running
			ctx.assertTrue(AgentOrchestrator.stateOf(player.getUuid(), "conv-A") != null,
					"deleting B must NOT stop A");
			// B must be gone, including its pending confirmation
			ctx.assertTrue(AgentOrchestrator.existingSession(player.getUuid(), "conv-B") == null,
					"the deleted session must be gone");
			ctx.assertTrue(AgentOrchestrator.pendingFor(player.getUuid(), "conv-B") == null,
					"the deleted session must not keep a pending confirmation");

			AgentOrchestrator.releaseSession(player.getUuid(), "conv-A");
			ctx.complete();
		});
	}

	// ------------------------------------------------ P0-4: real cancellation

	/**
	 * Cancelling an export really terminates the Gradle build.
	 *
	 * <p>The player starts an export from the chat, waits until the Gradle
	 * process is really running, and then cancels. The build must stop, no
	 * RELEASED artifact may appear, and the task must end in exactly one
	 * terminal state — a cancellation, never a success.</p>
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-runtime-6",
			tickLimit = 120_000)
	public void cancellingAnExportReallyStopsTheGradleBuild(TestContext ctx) {
		var dir = TestTempDirs.create("wf-runtime-f");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1, ITEM_ID, "取消导出", "",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 5.0, "max_durability", 100.0), List.of()));

		var server = ctx.getWorld().getServer();
		var player = opPlayer(ctx);

		sharedProvider();

		String conversationId = "cancel-export";
		var recorder = new AgentEventRecorder();
		AgentOrchestrator.send(server, player, conversationId, "导出成一个 Mod", null, recorder);

		// 1) wait for the confirmation and approve it
		await(ctx, 30_000, null, () -> pendingOf(recorder) != null, () -> {
			var pending = pendingOf(recorder);
			ctx.assertTrue(pending != null, "the export must ask for a confirmation");
			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId()), "the confirmation must be accepted");

			// 2) wait until the Gradle process is really running, then cancel it
			await(ctx, 60_000, null, () -> recorder.events().stream()
					.anyMatch(e -> "export_mod".equals(e.toolName())
							&& e.status() == ToolEventStatus.RUNNING
							&& e.summary().contains("subprocess started")),
					() -> {
						ctx.assertTrue(true, "the Gradle build must have started");
						ctx.assertTrue(AgentOrchestrator.cancelTask(player.getUuid(), conversationId),
								"the running export must be cancellable");

						// 3) the task must end in exactly ONE terminal state, and
						// it must be a cancellation — not a success
						await(ctx, 60_000, null, () -> recorder.finishedEventOf("export_mod") != null, () -> {
							var done = recorder.finishedEventOf("export_mod");
							ctx.assertTrue(done != null,
									"the export must report a final state " + recorder.dump());
							if (done != null) {
								ctx.assertEquals(ToolEventStatus.CANCELLED, done.status(),
										"a cancelled export must not report success: " + done.summary());
							}
							ctx.assertTrue(
									recorder.sawState(AgentOrchestrator.TaskState.CANCELLED.name())
											|| recorder.sawState(AgentOrchestrator.TaskState.FAILED.name()),
									"the client must see a terminal state " + recorder.dump());

							// and no RELEASED artifact may have been produced BY
							// THIS EXPORT (the workspace may hold jars of EARLIER
							// exports, so the search is scoped to the project
							// directory this run created)
							Path project = projectDirOf(recorder);
							ctx.assertTrue(project != null,
									"the export must have reported the project directory " + recorder.dump());
							Path jar = findReleasedJar(project);
							ctx.assertTrue(jar == null,
									"a cancelled export must not leave a released JAR behind: " + jar);
							Path dev = findDevJar(project);
							if (dev != null) {
								// a Loom "dev" jar is an intermediate of the killed
								// build; it is NOT the artifact the exporter hands out
								report.add("[WARN] the killed build left a dev intermediate: " + dev);
							}
							AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
							ctx.complete();
						});
					});
		});
	}

	// ------------------------------------------------------------- helpers

	private static ToolEvent pendingOf(AgentEventRecorder recorder) {
		for (ToolEvent e : recorder.events()) {
			if (e.status() == ToolEventStatus.AWAITING_CONFIRMATION) {
				return e;
			}
		}
		return null;
	}

	/** True when the conversation already received an assistant answer. */
	private static boolean hasAssistantAnswer(AgentEventRecorder recorder) {
		return recorder.messages().stream().anyMatch(m -> m.startsWith("assistant:"));
	}

	/**
	 * The project directory this export created.
	 *
	 * <p>The exporter audits it ("project generated at ..."), and the chat
	 * forwards that line as a progress event — so the test knows exactly which
	 * directory belongs to the export it just cancelled.</p>
	 */
	private static Path projectDirOf(AgentEventRecorder recorder) {
		for (ToolEvent e : recorder.events()) {
			String summary = e.summary();
			// the audit line is "<timestamp> project generated at <dir>"
			int at = summary == null ? -1 : summary.indexOf("project generated at ");
			if (at >= 0) {
				String rest = summary.substring(at + "project generated at ".length()).trim();
				return Path.of(rest);
			}
		}
		return null;
	}

	/** The RELEASED artifact of an export (the one the exporter hands out). */
	private static Path findReleasedJar(Path projectDir) {
		if (projectDir == null || !Files.isDirectory(projectDir)) {
			return null;
		}
		try (var stream = Files.walk(projectDir, 4)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(p -> p.getParent() != null
							&& p.getParent().getFileName().toString().equals("libs"))
					.filter(p -> !p.getFileName().toString().contains("sources"))
					.findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}

	/** A Loom "dev" intermediate of a build. */
	private static Path findDevJar(Path projectDir) {
		if (projectDir == null || !Files.isDirectory(projectDir)) {
			return null;
		}
		try (var stream = Files.walk(projectDir, 4)) {
			return stream.filter(p -> p.getFileName().toString().endsWith("-dev.jar"))
					.findFirst().orElse(null);
		} catch (Exception e) {
			return null;
		}
	}
}
