package dev.worldforge;

import dev.worldforge.chat.ChatEventRouter;
import dev.worldforge.chat.ChatSession;
import dev.worldforge.chat.ChatTaskState;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolPermission;
import dev.worldforge.ops.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.7.1 — the chat client's view of the server state.
 *
 * <p>These rules are the reason a player can trust what the chat shows: a
 * message appears once, an old task cannot overwrite the current one, and a
 * deleted conversation stays deleted.</p>
 */
class ChatClientStateTest {

	private ChatEventRouter router;

	@BeforeEach
	void setUp() {
		router = new ChatEventRouter();
	}

	// ------------------------------------------------------------ dedup

	@Test
	void aUserMessageIsShownOnlyOnce() {
		assertFalse(router.isDuplicateUserMessage("req-1"),
				"the first time must not be a duplicate");
		assertTrue(router.isDuplicateUserMessage("req-1"),
				"the server's echo of the same request must be a duplicate");
	}

	@Test
	void differentRequestsAreNotDuplicates() {
		assertFalse(router.isDuplicateUserMessage("req-1"));
		assertFalse(router.isDuplicateUserMessage("req-2"));
		assertTrue(router.isDuplicateUserMessage("req-1"));
	}

	@Test
	void aMessageWithoutARequestIdIsNeverADuplicate() {
		assertFalse(router.isDuplicateUserMessage(""));
		assertFalse(router.isDuplicateUserMessage(null));
	}

	@Test
	void theSeenRequestIdsStayBounded() {
		for (int i = 0; i < 2000; i++) {
			router.isDuplicateUserMessage("req-" + i);
		}
		// the router must not grow without bound
		for (int i = 1500; i < 2000; i++) {
			assertTrue(router.isDuplicateUserMessage("req-" + i),
					"recent requests must still be remembered");
		}
	}

	// ------------------------------------------------------- stale events

	@Test
	void anEventOfTheCurrentTaskIsAccepted() {
		var session = new ChatSession("c1");
		session.setTaskId("t1");
		assertTrue(router.appliesTo(session, "t1"));
	}

	@Test
	void anEventOfAnOldTaskIsDropped() {
		// the slow task t1 is still running, the new task t2 took over
		var session = new ChatSession("c1");
		session.setTaskId("t2");
		assertFalse(router.appliesTo(session, "t1"),
				"an event of an old task must not overwrite the new state");
		assertTrue(router.appliesTo(session, "t2"));
	}

	@Test
	void anEventWithoutATaskIdIsAccepted() {
		var session = new ChatSession("c1");
		session.setTaskId("t2");
		assertTrue(router.appliesTo(session, ""),
				"an event without a task id cannot be judged and must not be dropped");
	}

	// -------------------------------------------------- deleted conversations

	@Test
	void aDeletedConversationStaysDeleted() {
		assertFalse(router.isDeleted("c1"));
		router.markDeleted("c1");
		assertTrue(router.isDeleted("c1"));
	}

	@Test
	void aConversationThatIsUsedAgainIsNotDeleted() {
		router.markDeleted("c1");
		router.markUsed("c1");
		assertFalse(router.isDeleted("c1"));
	}

	// ------------------------------------------------------------- states

	@Test
	void onlyATerminalStateEndsTheBusyState() {
		for (ChatTaskState state : List.of(ChatTaskState.THINKING, ChatTaskState.AWAITING_CONFIRMATION,
				ChatTaskState.EXECUTING, ChatTaskState.CANCELLING, ChatTaskState.QUEUED)) {
			assertTrue(state.isBusy(), state + " must count as busy");
			assertFalse(state.isTerminal(), state + " must not be terminal");
		}
		for (ChatTaskState state : List.of(ChatTaskState.SUCCEEDED, ChatTaskState.FAILED,
				ChatTaskState.CANCELLED)) {
			assertTrue(state.isTerminal(), state + " must be terminal");
			assertFalse(state.isBusy(), state + " must not be busy");
		}
		assertFalse(ChatTaskState.IDLE.isBusy());
	}

	@Test
	void anUnknownServerStateIsIgnored() {
		// an unknown state must never be mistaken for a terminal one
		assertNull(ChatTaskState.fromServer("SOMETHING_NEW"));
		assertNull(ChatTaskState.fromServer(""));
		assertNull(ChatTaskState.fromServer(null));
		assertEquals(ChatTaskState.EXECUTING, ChatTaskState.fromServer("executing"));
		assertEquals(ChatTaskState.AWAITING_CONFIRMATION,
				ChatTaskState.fromServer("AWAITING_CONFIRMATION"));
	}

	@Test
	void aSessionKeepsItsOwnState() {
		var a = new ChatSession("a");
		var b = new ChatSession("b");
		a.setTaskState(ChatTaskState.EXECUTING);
		b.setTaskState(ChatTaskState.IDLE);
		assertEquals(ChatTaskState.EXECUTING, a.taskState());
		assertEquals(ChatTaskState.IDLE, b.taskState());
		assertTrue(a.busy());
		assertFalse(b.busy());
	}

	// ------------------------------------------- P1-3: the tool permissions

	@Test
	void everyToolDeclaresItsPermissionLevel() {
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		List<ToolDescriptor> tools = ToolRegistry.all();
		assertEquals(16, tools.size(), "the catalogue must have exactly the 16 documented tools (v0.8.0 adds edit_world_blocks and undo_world_edit)");
		for (ToolDescriptor d : tools) {
			assertNotNull(d.permission(), d.name() + " has no permission level");
			assertNotNull(d.description(), d.name() + " has no description");
			assertFalse(d.description().isBlank(), d.name() + " has an empty description");
			assertNotNull(d.parameters(), d.name() + " has no schema");
		}
	}

	@Test
	void theReadOnlyToolsAreExactlyTheOnesThatOnlyRead() {
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		Set<String> expectedReadOnly = Set.of("inspect_context", "inspect_definition",
				"list_capabilities", "check_export_capabilities", "inspect_target_block");
		for (ToolDescriptor d : ToolRegistry.all()) {
			boolean readOnly = d.permission() == ToolPermission.READ;
			assertEquals(expectedReadOnly.contains(d.name()), readOnly,
					d.name() + " has the wrong permission level");
			// a READ tool changes nothing at all — not the world, and not
			// the player's own items either
			if (readOnly) {
				assertFalse(d.mutatesWorld(),
						d.name() + " is read-only and must not change the world");
				assertFalse(d.requiresConfirmation(),
						d.name() + " is read-only and must not ask for a confirmation");
			}
		}
	}

	@Test
	void everyMutatingToolNeedsAConfirmation() {
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		for (ToolDescriptor d : ToolRegistry.all()) {
			if (d.mutatesWorld()) {
				assertTrue(d.requiresConfirmation(),
						d.name() + " changes the world and must be confirmed");
			}
		}
	}

	@Test
	void noToolOffersArbitraryCodeExecution() {
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		for (ToolDescriptor d : ToolRegistry.all()) {
			String n = d.name().toLowerCase(java.util.Locale.ROOT);
			for (String forbidden : List.of("shell", "powershell", "bash", "cmd", "exec", "eval",
					"script", "code", "command")) {
				assertFalse(n.contains(forbidden),
						"a tool named '" + d.name() + "' looks like arbitrary execution");
			}
		}
	}

	@Test
	void theExportDegradationProtectionIsStillThere() {
		// the export must still refuse a degraded plan without the player's
		// consent — the chat does not weaken it
		var plan = dev.worldforge.exporter.ExportPlan.create("wf_probe", "Probe", "1.0.0",
				System.getProperty("java.io.tmpdir"),
				List.of(), List.of());
		assertFalse(plan.degradedExportConfirmed(),
				"a fresh plan must not be pre-confirmed");
		assertNotNull(plan.withDegradedConfirmation());
	}
}
