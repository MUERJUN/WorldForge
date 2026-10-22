package dev.worldforge;

import dev.worldforge.ai.AgentTimeouts;
import dev.worldforge.ai.PendingConfirmation;
import dev.worldforge.ai.TaskToken;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.7.1 — the chat runtime's concurrency and confirmation contracts.
 *
 * <p>These are the rules the v0.7.0 release did not have, and each of them
 * is checked here directly (including with REAL racing threads, not with
 * sequential calls that pretend to be concurrent).</p>
 */
class ChatRuntimeConcurrencyTest {

	// ------------------------------------------------------- confirmation

	private static PendingConfirmation pending(String id) {
		return new PendingConfirmation(id, "c1", "r1", "t1", "update_item",
				"将修改 worldforge:x", List.of("worldforge:x"), List.of(), "snap", 1);
	}

	@Test
	void aFreshConfirmationIsPending() {
		var pc = pending("cid");
		assertEquals(PendingConfirmation.State.PENDING, pc.state());
		assertTrue(pc.isPending());
	}

	@Test
	void approvingMovesToTheApprovedTerminalState() {
		var pc = pending("cid");
		assertTrue(pc.approve("cid"));
		assertEquals(PendingConfirmation.State.APPROVED, pc.state());
		assertFalse(pc.isPending());
	}

	@Test
	void aRepeatedApprovalDoesNotExecuteTwice() {
		var pc = pending("cid");
		assertTrue(pc.approve("cid"));
		assertFalse(pc.approve("cid"), "a second approval must not perform the transition again");
		assertEquals(PendingConfirmation.State.APPROVED, pc.state());
	}

	@Test
	void denyingAfterApprovingDoesNotChangeTheState() {
		// the v0.7.0 bug: two independent booleans could be approved AND
		// cancelled, and the client was then told "cancelled" for an
		// operation that had already been approved
		var pc = pending("cid");
		assertTrue(pc.approve("cid"));
		assertFalse(pc.deny("cid"), "a late denial must not undo an approval");
		assertEquals(PendingConfirmation.State.APPROVED, pc.state());
	}

	@Test
	void approvingAfterDenyingDoesNotExecute() {
		var pc = pending("cid");
		assertTrue(pc.deny("cid"));
		assertFalse(pc.approve("cid"), "a late approval must not execute a denied operation");
		assertEquals(PendingConfirmation.State.DENIED, pc.state());
	}

	@Test
	void aBlankConfirmationIdApprovesNothing() {
		// the v0.7.0 bug: a blank id was accepted and then approved whatever
		// happened to be pending
		var pc = pending("cid");
		assertFalse(pc.approve(""));
		assertFalse(pc.approve(null));
		assertFalse(pc.deny(""));
		assertEquals(PendingConfirmation.State.PENDING, pc.state());
	}

	@Test
	void aWrongConfirmationIdApprovesNothing() {
		var pc = pending("cid");
		assertFalse(pc.approve("other"));
		assertEquals(PendingConfirmation.State.PENDING, pc.state());
	}

	@Test
	void aTimeoutPreventsTheExecution() {
		var pc = pending("cid");
		assertTrue(pc.timeout());
		assertFalse(pc.approve("cid"), "a timed out confirmation must not run");
		assertEquals(PendingConfirmation.State.TIMED_OUT, pc.state());
	}

	@Test
	void aCancellationPreventsTheExecution() {
		var pc = pending("cid");
		assertTrue(pc.cancel());
		assertFalse(pc.approve("cid"));
		assertEquals(PendingConfirmation.State.CANCELLED, pc.state());
	}

	@Test
	void awaitReturnsTrueOnlyForAnApproval() throws Exception {
		var approved = pending("a");
		Thread t = new Thread(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			}
			approved.approve("a");
		});
		t.start();
		assertTrue(approved.await(5));
		t.join();

		var denied = pending("d");
		Thread t2 = new Thread(() -> {
			try {
				Thread.sleep(50);
			} catch (InterruptedException ignored) {
			}
			denied.deny("d");
		});
		t2.start();
		assertFalse(denied.await(5), "a denied operation must not report success");
		t2.join();
	}

	@Test
	void anApprovalAndADenialRacingProduceExactlyOneWinner() throws Exception {
		// a REAL race: two threads try to resolve the same confirmation
		for (int round = 0; round < 200; round++) {
			var pc = pending("cid");
			var barrier = new CountDownLatch(1);
			var executor = Executors.newFixedThreadPool(2);
			AtomicReference<String> winner = new AtomicReference<>();
			var a = executor.submit(() -> {
				barrier.await();
				if (pc.approve("cid")) {
					winner.compareAndSet(null, "approve");
				}
				return null;
			});
			var b = executor.submit(() -> {
				barrier.await();
				if (pc.deny("cid")) {
					winner.compareAndSet(null, "deny");
				}
				return null;
			});
			barrier.countDown();
			a.get(5, TimeUnit.SECONDS);
			b.get(5, TimeUnit.SECONDS);
			executor.shutdown();
			assertNotNull(winner.get(), "exactly one side must win the race");
			// and the state must agree with the winner — never both, never neither
			if ("approve".equals(winner.get())) {
				assertEquals(PendingConfirmation.State.APPROVED, pc.state());
			} else {
				assertEquals(PendingConfirmation.State.DENIED, pc.state());
			}
		}
	}

	@Test
	void manyThreadsRacingOnOneConfirmationStillProduceOneTerminalState() throws Exception {
		var pc = pending("cid");
		var executor = Executors.newFixedThreadPool(8);
		var barrier = new CountDownLatch(1);
		var winners = new AtomicInteger();
		for (int i = 0; i < 8; i++) {
			executor.submit(() -> {
				barrier.await();
				if (pc.approve("cid") || pc.deny("cid")) {
					winners.incrementAndGet();
				}
				return null;
			});
		}
		barrier.countDown();
		executor.shutdown();
		executor.awaitTermination(10, TimeUnit.SECONDS);
		assertEquals(1, winners.get(), "exactly one thread may resolve the confirmation");
		assertFalse(pc.isPending());
	}

	// ------------------------------------------------------------- task token

	@Test
	void aTaskStartsRunning() {
		var token = new TaskToken("t1", "c1", java.util.UUID.randomUUID(), 1);
		assertEquals(TaskToken.State.RUNNING, token.state());
		assertFalse(token.isCancelled());
	}

	@Test
	void cancellingRunsTheCancelHooksExactlyOnce() {
		var token = new TaskToken("t1", "c1", java.util.UUID.randomUUID(), 1);
		var calls = new AtomicInteger();
		token.onCancel(calls::incrementAndGet);
		assertTrue(token.requestCancel());
		assertEquals(1, calls.get());
		// a second cancellation must not run the hooks again
		assertFalse(token.requestCancel());
		assertEquals(1, calls.get());
	}

	@Test
	void aHookRegisteredAfterTheCancellationRunsImmediately() {
		// a tool that starts its work AFTER the cancellation must still be
		// stopped, otherwise it would run to completion in the background
		var token = new TaskToken("t1", "c1", java.util.UUID.randomUUID(), 1);
		assertTrue(token.requestCancel());
		var calls = new AtomicInteger();
		token.onCancel(calls::incrementAndGet);
		assertEquals(1, calls.get());
	}

	@Test
	void aCancelledTaskIsCancelled() {
		var token = new TaskToken("t1", "c1", java.util.UUID.randomUUID(), 1);
		token.requestCancel();
		assertTrue(token.isCancelled());
	}

	@Test
	void aFinishedTaskCannotBeCancelled() {
		// an action that already happened cannot be taken back — saying
		// otherwise would be a lie
		var token = new TaskToken("t1", "c1", java.util.UUID.randomUUID(), 1);
		assertTrue(token.finish());
		assertEquals(TaskToken.State.FINISHED, token.state());
		assertFalse(token.requestCancel(), "a finished task must not become cancelled");
		assertEquals(TaskToken.State.FINISHED, token.state());
	}

	@Test
	void aCancelRacingAFinishProducesOneFinalState() throws Exception {
		for (int round = 0; round < 200; round++) {
			var token = new TaskToken("t1", "c1", java.util.UUID.randomUUID(), 1);
			var barrier = new CountDownLatch(1);
			var executor = Executors.newFixedThreadPool(2);
			executor.submit(() -> {
				barrier.await();
				token.requestCancel();
				return null;
			});
			executor.submit(() -> {
				barrier.await();
				token.finish();
				return null;
			});
			barrier.countDown();
			executor.shutdown();
			executor.awaitTermination(5, TimeUnit.SECONDS);
			// exactly one of CANCELLED / FINISHED, never a mixed state
			TaskToken.State s = token.state();
			assertTrue(s == TaskToken.State.CANCELLED || s == TaskToken.State.FINISHED,
					"unexpected final state: " + s);
		}
	}

	// -------------------------------------------------------------- budgets

	@Test
	void theExportToolNeverDeclaresAShorterBudgetThanTheBuildItWaitsFor() {
		// the v0.7.0 bug: the orchestrator waited 12 minutes for a tool whose
		// build takes 20 — a healthy but slow export was reported as failed
		assertTrue(AgentTimeouts.EXPORT_TOOL_SECONDS
				> AgentTimeouts.GRADLE_BUILD_MINUTES * 60,
				"the export tool's budget must exceed the Gradle build time");
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		var exportTool = ToolRegistry.descriptor("export_mod");
		assertTrue(exportTool.isPresent());
		assertTrue(exportTool.get().timeoutSeconds() >= AgentTimeouts.GRADLE_BUILD_MINUTES * 60,
				"the registered export tool must declare at least the build time: "
						+ exportTool.get().timeoutSeconds());
	}

	@Test
	void everyToolDeclaresABudget() {
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		for (ToolDescriptor d : ToolRegistry.all()) {
			assertTrue(d.timeoutSeconds() > 0, d.name() + " has no budget");
			assertTrue(d.timeoutSeconds() >= 10, d.name() + " has a nonsensical budget");
		}
	}

	@Test
	void theTimeoutsAreDistinctAndOrdered() {
		// the model request, the confirmation wait, the tool preparation and
		// the Gradle build are four different things with four different
		// budgets
		assertTrue(AgentTimeouts.TOOL_PREPARE_SECONDS
				< AgentTimeouts.MODEL_REQUEST_SECONDS,
				"preparing an operation must not wait longer than a model call");
		assertTrue(AgentTimeouts.MODEL_REQUEST_SECONDS
				< AgentTimeouts.CONFIRMATION_WAIT_SECONDS,
				"the player may take longer to answer than the model needs to think");
		assertTrue(AgentTimeouts.CONFIRMATION_WAIT_SECONDS
				< AgentTimeouts.GRADLE_BUILD_MINUTES * 60,
				"a Gradle build takes longer than a confirmation wait");
	}

	@Test
	void theWorkerAndTaskCapsAreBounded() {
		assertTrue(AgentTimeouts.MAX_WORKERS > 0 && AgentTimeouts.MAX_WORKERS <= 32,
				"the worker pool must be bounded");
		assertTrue(AgentTimeouts.MAX_ACTIVE_TASKS_PER_PLAYER > 0
				&& AgentTimeouts.MAX_ACTIVE_TASKS_PER_PLAYER <= AgentTimeouts.MAX_WORKERS,
				"a player may not run more tasks than there are workers");
		assertTrue(AgentTimeouts.MAX_QUEUED_MESSAGES > 0);
		assertTrue(AgentTimeouts.MAX_TOOL_ROUNDS_PER_TASK > 0);
		assertTrue(AgentTimeouts.MAX_TOOL_CALLS_PER_TASK >= AgentTimeouts.MAX_TOOL_ROUNDS_PER_TASK,
				"the call budget must not be smaller than the round budget");
	}

	@Test
	void theToolBudgetsAccumulateOverTheWholeTask() {
		// the budgets are per TASK, not per round: a task may use all of
		// them across its rounds
		assertTrue(AgentTimeouts.MAX_TOOL_CALLS_PER_TASK
				> AgentTimeouts.MAX_TOOL_ROUNDS_PER_TASK,
				"a task may call several tools per round, so the call budget must be larger");
	}
}
