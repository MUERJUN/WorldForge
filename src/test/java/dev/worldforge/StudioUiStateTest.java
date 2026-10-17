package dev.worldforge;

import dev.worldforge.studio.StudioLayout;
import dev.worldforge.studio.StudioUiState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.3.2 GUI regression tests: the three reported defects plus layout
 * hit-testing, verified deterministically without a graphics context
 * (desktop input injection is not available in every environment, so the
 * rules were extracted into testable components instead of being asserted
 * through fragile screen coordinates).
 */
class StudioUiStateTest {

	// ================================================================
	// 1. AI request state must never lock the player in THINKING
	// ================================================================

	@Test
	void aiSendEntersThinkingOnlyWhenTransportAccepted() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);

		assertEquals(StudioUiState.AiSend.EMPTY, s.beginAiSend("   ", true), "blank input never sends");
		assertEquals(StudioUiState.AiPhase.IDLE, s.aiPhase(), "blank input must not enter THINKING");

		assertEquals(StudioUiState.AiSend.NO_TRANSPORT, s.beginAiSend("创建雷霆之剑", false),
				"without a WorldForge server the request is refused locally");
		assertEquals(StudioUiState.AiPhase.IDLE, s.aiPhase(),
				"a refused send must NOT leave the panel in THINKING (the reported freeze)");
		assertTrue(s.sendActive(), "send button must be usable again immediately");

		assertEquals(StudioUiState.AiSend.SENT, s.beginAiSend("创建雷霆之剑", true));
		assertEquals(StudioUiState.AiPhase.THINKING, s.aiPhase());
		assertFalse(s.sendActive(), "send is disabled while a request is in flight");

		assertEquals(StudioUiState.AiSend.BUSY, s.beginAiSend("再来一次", true), "no double submit");
	}

	@Test
	void aiFailureRestoresInputImmediately() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);
		s.beginAiSend("测试", true);
		s.aiFailed();
		assertEquals(StudioUiState.AiPhase.IDLE, s.aiPhase());
		assertTrue(s.sendActive(), "input restored after a transport failure");
		assertTrue(s.sendVisible());
	}

	@Test
	void aiRejectedAckRestoresInput() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);
		s.beginAiSend("测试", true);
		s.aiAcknowledged(false); // server refused (no config / rate limit)
		assertEquals(StudioUiState.AiPhase.IDLE, s.aiPhase(), "a rejected ack must not keep THINKING");
	}

	@Test
	void aiTimeoutWatchdogReleasesThinking() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);
		s.beginAiSend("卡住", true);
		assertEquals(StudioUiState.AiPhase.THINKING, s.aiPhase());

		long now = s.thinkingSince();
		assertFalse(s.tick(now + 1_000), "not yet timed out");
		assertTrue(s.tick(now + StudioUiState.AI_TIMEOUT_MS + 1), "watchdog must fire");
		assertEquals(StudioUiState.AiPhase.IDLE, s.aiPhase());
		assertTrue(s.sendActive(), "input usable again after the watchdog");
	}

	@Test
	void disconnectClearsEverythingInFlight() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);
		s.beginAiSend("断线前", true);
		s.aiOutcome(true, true, "pid-1", "diff", "create", "worldforge:x");
		assertTrue(s.hasPendingProposal());

		s.onDisconnected();
		assertEquals(StudioUiState.AiPhase.IDLE, s.aiPhase());
		assertFalse(s.hasPendingProposal(), "no ghost proposal survives a disconnect");
		assertTrue(s.sendActive());
	}

	@Test
	void proposalArrivalShowsConfirmAndCancelButtons() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);
		s.beginAiSend("创建冰霜剑", true);
		s.aiOutcome(true, true, "pid-42", "创建新物品...", "create", "worldforge:frost_sword");

		assertEquals(StudioUiState.AiPhase.PROPOSAL, s.aiPhase());
		assertTrue(s.confirmVisible(), "确认 button must appear when a proposal arrives");
		assertTrue(s.cancelVisible(), "取消 button must appear when a proposal arrives");
		assertFalse(s.sendVisible(), "send is replaced by confirm/cancel");
		assertEquals("pid-42", s.pendingProposalId());
	}

	@Test
	void plainAnswerReturnsToIdleNotProposal() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);
		s.beginAiSend("你好", true);
		s.aiOutcome(true, false, null, "", "", "");
		assertEquals(StudioUiState.AiPhase.IDLE, s.aiPhase());
		assertTrue(s.sendActive());
		assertFalse(s.confirmVisible());
	}

	// ================================================================
	// 2. Unsaved draft protection on EVERY navigation entry point
	// ================================================================

	@Test
	void leavingEditorWithDraftRequiresSecondPress() {
		var s = new StudioUiState();
		s.beginCreate();
		s.markDirty();

		assertEquals(StudioUiState.Nav.NEEDS_CONFIRM, s.requestNavigation(StudioUiState.Mode.LIST));
		assertTrue(s.dirty(), "draft still intact after the first press");
		assertEquals(StudioUiState.Nav.MOVED, s.requestNavigation(StudioUiState.Mode.LIST));
		assertFalse(s.dirty(), "second press discards and leaves");
		assertEquals(StudioUiState.Mode.LIST, s.mode());
	}

	@Test
	void enteringAiPanelIsAlsoDraftGuarded() {
		var s = new StudioUiState();
		s.beginEdit();
		s.markDirty();

		// the AI panel button is a navigation entry point like any other
		assertEquals(StudioUiState.Nav.NEEDS_CONFIRM, s.requestNavigation(StudioUiState.Mode.AI),
				"entering the AI panel with unsaved edits must warn first");
		assertEquals(StudioUiState.Mode.EDIT, s.mode(), "and must not switch yet");
		assertTrue(s.dirty());

		assertEquals(StudioUiState.Nav.MOVED, s.requestNavigation(StudioUiState.Mode.AI));
		assertEquals(StudioUiState.Mode.AI, s.mode());
		assertFalse(s.dirty());
	}

	@Test
	void cleanEditorNavigatesWithoutExtraPress() {
		var s = new StudioUiState();
		s.beginCreate();
		assertEquals(StudioUiState.Nav.MOVED, s.requestNavigation(StudioUiState.Mode.AI),
				"no unsaved changes -> no pointless confirmation");
	}

	@Test
	void guardStateIsExplicitAndResetOnSave() {
		var s = new StudioUiState();
		s.beginCreate();
		s.markDirty();
		assertEquals(StudioUiState.Nav.NEEDS_CONFIRM, s.requestNavigation(StudioUiState.Mode.LIST));
		assertTrue(s.discardArmed());
		// a successful save clears both the draft and the arm
		s.markSaved();
		assertFalse(s.dirty());
		assertEquals(StudioUiState.Nav.MOVED, s.requestNavigation(StudioUiState.Mode.LIST),
				"after saving there is nothing to confirm");
	}

	@Test
	void guardDoesNotDependOnAnyDisplayString() {
		// the old implementation inferred "already warned" from the status bar
		// text, so any other message silently disarmed it and trapped the
		// player in the editor. The state machine has no string input at all:
		// the only way to leave with a draft is the explicit second request.
		var s = new StudioUiState();
		s.beginCreate();
		s.markDirty();
		assertEquals(StudioUiState.Nav.NEEDS_CONFIRM, s.requestNavigation(StudioUiState.Mode.LIST));
		assertEquals(StudioUiState.Nav.MOVED, s.requestNavigation(StudioUiState.Mode.LIST));
		assertEquals(StudioUiState.Mode.LIST, s.mode());
	}

	@Test
	void modalCloseReturnsToTheModeItCameFrom() {
		var s = new StudioUiState();
		s.beginEdit();
		assertEquals(StudioUiState.Nav.MOVED, s.openModal(StudioUiState.Mode.APPEAR_PICK));
		assertEquals(StudioUiState.Mode.APPEAR_PICK, s.mode());
		assertEquals(StudioUiState.Nav.MOVED, s.closeModal());
		assertEquals(StudioUiState.Mode.EDIT, s.mode(), "picker started from EDIT returns to EDIT");

		s.beginCreate();
		s.openModal(StudioUiState.Mode.DELETE_CONFIRM);
		s.closeModal();
		assertEquals(StudioUiState.Mode.CREATE, s.mode(), "confirm started from CREATE returns to CREATE");
	}

	// ================================================================
	// 3. Cancel vs. exit decoupling (works with a dead server)
	// ================================================================

	@Test
	void escCancelsProposalFirstAndNeedsNoServer() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);
		s.beginAiSend("创建", true);
		s.aiOutcome(true, true, "pid-9", "diff", "create", "worldforge:x");

		assertEquals(StudioUiState.Esc.CANCEL_PROPOSAL, s.pressEsc(),
				"first ESC drops the proposal locally");
		assertFalse(s.hasPendingProposal(), "local cancel must not wait for the server");
		assertEquals(StudioUiState.Mode.AI, s.mode(), "still in the AI panel");

		assertEquals(StudioUiState.Esc.MOVED, s.pressEsc(), "second ESC backs out to the list");
		assertEquals(StudioUiState.Mode.LIST, s.mode());
		assertEquals(StudioUiState.Esc.CLOSE, s.pressEsc(), "third ESC closes");
	}

	@Test
	void cancelProposalIsAlwaysPossibleEvenWhileThinking() {
		var s = new StudioUiState();
		s.requestNavigation(StudioUiState.Mode.AI);
		s.beginAiSend("慢请求", true);
		// the model is still thinking: the player must still be able to leave
		assertEquals(StudioUiState.Esc.MOVED, s.pressEsc());
		assertEquals(StudioUiState.Mode.LIST, s.mode());
		assertEquals(StudioUiState.Esc.CLOSE, s.pressEsc());
	}

	@Test
	void closeButtonAlwaysEndsInClose() {
		var s = new StudioUiState();
		s.beginEdit();
		s.markDirty();
		assertEquals(StudioUiState.Esc.NEEDS_CONFIRM, s.requestClose(), "warns once");
		assertTrue(s.closeArmed());
		assertEquals(StudioUiState.Esc.CLOSE, s.requestClose(), "second press closes - never traps the player");
	}

	@Test
	void closeFromEveryModeIsPossible() {
		for (StudioUiState.Mode target : new StudioUiState.Mode[]{
				StudioUiState.Mode.AI, StudioUiState.Mode.LIST, StudioUiState.Mode.CREATE}) {
			var s = new StudioUiState();
			s.requestNavigation(target);
			assertEquals(StudioUiState.Esc.CLOSE, s.requestClose(), "close must work from " + target);
		}
	}

	@Test
	void escIsLayeredWithoutOscillation() {
		var s = new StudioUiState();
		s.beginEdit();
		assertEquals(StudioUiState.Esc.MOVED, s.pressEsc(), "clean editor -> list");
		assertEquals(StudioUiState.Esc.CLOSE, s.pressEsc(), "list -> close");

		s.beginCreate();
		s.markDirty();
		assertEquals(StudioUiState.Esc.NEEDS_CONFIRM, s.pressEsc(), "dirty -> warn");
		assertEquals(StudioUiState.Esc.MOVED, s.pressEsc(), "second -> leave");
		assertEquals(StudioUiState.Mode.LIST, s.mode());
	}

	// ================================================================
	// visibility invariants
	// ================================================================

	@Test
	void visibilityIsMutuallyExclusive() {
		var s = new StudioUiState();
		for (StudioUiState.Mode m : StudioUiState.Mode.values()) {
			s.requestNavigation(m);
			boolean ai = s.inputVisible();
			if (m == StudioUiState.Mode.AI) {
				assertTrue(ai);
			} else {
				assertFalse(ai, "chat input only exists in the AI panel");
			}
			assertFalse(s.sendVisible() && s.confirmVisible(), "send and confirm never overlap");
			assertFalse(s.editorFieldsVisible() && s.aiVisible(), "editor and AI panel never overlap");
		}
	}

	@Test
	void editorWidgetsFollowMode() {
		var s = new StudioUiState();
		s.beginEdit();
		assertTrue(s.editorFieldsVisible());
		assertTrue(s.giveVisible());
		assertTrue(s.deleteVisible());
		s.beginCreate();
		assertTrue(s.editorFieldsVisible());
		assertFalse(s.giveVisible(), "cannot give an item that does not exist yet");
		assertFalse(s.deleteVisible());
	}

	// ================================================================
	// layout geometry / hit-testing
	// ================================================================

	@Test
	void layoutFitsSmallWindow() {
		var l = StudioLayout.of(854, 480);
		assertTrue(l.panel().w() <= 854 && l.panel().h() <= 480);
		assertTrue(l.panel().x() >= 0 && l.panel().y() >= 0);
		assertTrue(l.panel().right() <= 854, "panel stays inside the window");
		assertTrue(l.panel().bottom() <= 480);
		assertTrue(l.listMaxRows() >= 1, "at least one row is always usable");
		assertTrue(l.contentW() > 0);
	}

	@Test
	void layoutDegradesGracefullyOnTinyWindows() {
		for (int[] size : new int[][]{{320, 240}, {200, 150}, {854, 480}, {1920, 1080}, {1280, 720}}) {
			var l = StudioLayout.of(size[0], size[1]);
			assertTrue(l.panel().w() > 0 && l.panel().h() > 0, "panel must have a positive size at " + size[0] + "x" + size[1]);
			assertTrue(l.list().w() > 0, "list must remain usable");
			assertTrue(l.listMaxRows() >= 1);
			assertTrue(l.statusBarY() > l.panel().y());
		}
	}

	@Test
	void rowHitTestMatchesDrawnRowsExactly() {
		var l = StudioLayout.of(854, 480);
		for (int i = 0; i < l.listMaxRows(); i++) {
			var r = l.rowRect(i);
			// centre of a drawn row must map back to the same row
			assertEquals(i, l.rowIndexAt(r.x() + r.w() / 2.0, r.y() + r.h() / 2.0, 0),
					"row " + i + " centre must hit-test to row " + i);
			// its first pixel is inside, one pixel above the next row is not this row
			assertEquals(i, l.rowIndexAt(r.x() + 1, r.y(), 0));
		}
		assertEquals(-1, l.rowIndexAt(l.list().x() - 5, l.list().y() + 5, 0), "left of the list is not a row");
		assertEquals(-1, l.rowIndexAt(l.list().right() + 5, l.list().y() + 5, 0), "right of the list is not a row");
		assertEquals(-1, l.rowIndexAt(l.list().x() + 5, l.list().bottom() + 5, 0), "below the list is not a row");
	}

	@Test
	void rowHitTestHonoursScrollOffset() {
		var l = StudioLayout.of(854, 480);
		var first = l.rowRect(0);
		assertEquals(7, l.rowIndexAt(first.x() + 2, first.y() + 2, 7),
				"scroll offset is added to the visible row index");
	}

	@Test
	void scrollIsClampedToContent() {
		var l = StudioLayout.of(854, 480);
		int rows = l.listMaxRows();
		assertEquals(0, l.clampScroll(-5, rows * 3));
		assertEquals(0, l.clampScroll(0, 2), "content shorter than the viewport cannot scroll");
		assertEquals(rows * 3 - l.listMaxRows(), l.clampScroll(9999, rows * 3));
		assertEquals(1, l.scrollBy(0, 100, -1), "wheel down advances one row");
		assertEquals(0, l.scrollBy(1, 100, 1), "wheel up goes back");
		assertEquals(0, l.scrollBy(0, 1, -1), "cannot scroll past the end");
	}

	@Test
	void bottomButtonsStayInsideTheListColumn() {
		var l = StudioLayout.of(854, 480);
		for (int slot = 0; slot < 3; slot++) {
			var r = l.bottomButton(slot, 3);
			assertTrue(r.w() >= 24);
			assertTrue(r.x() >= l.list().x(), "button " + slot + " starts inside the column");
			assertTrue(r.right() <= l.list().right() + 1, "button " + slot + " does not overflow");
		}
	}

	@Test
	void aiInputRowKeepsWidgetsApart() {
		var l = StudioLayout.of(854, 480);
		var input = l.aiInputRow(l.contentX(), l.panel().y() + 24, l.contentW(), l.panel().h() - 56, 0);
		var send = l.aiInputRow(l.contentX(), l.panel().y() + 24, l.contentW(), l.panel().h() - 56, 1);
		var confirm = l.aiInputRow(l.contentX(), l.panel().y() + 24, l.contentW(), l.panel().h() - 56, 2);
		assertTrue(input.w() > 0 && send.w() > 0 && confirm.w() > 0);
		assertFalse(input.contains(send.x() + 1, send.y() + 1), "input and send must not overlap");
		assertFalse(send.contains(confirm.x() + 1, confirm.y() + 1), "send and confirm must not overlap");
		assertTrue(confirm.right() <= l.panel().right(), "confirm stays inside the panel");
	}
}