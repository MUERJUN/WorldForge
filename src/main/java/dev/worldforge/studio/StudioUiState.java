package dev.worldforge.studio;

import java.util.ArrayList;
import java.util.List;

/**
 * Presentation-agnostic state machine for WorldForge Studio (v0.3.2).
 *
 * <p>Deliberately free of any Minecraft import so it can be unit-tested
 * without a GL context or a running game - which is exactly why the GUI
 * regression rules now live here instead of inside widget callbacks:
 * desktop input injection is not reliable in every environment, but these
 * rules are verifiable deterministically.</p>
 *
 * <p>It owns the three behaviours that were reported broken:</p>
 * <ol>
 *   <li><b>AI request state</b>: a request only enters THINKING when the
 *       transport actually accepted it; rejection, failure, timeout,
 *       cancellation or disconnect always return to IDLE, so the player can
 *       never be locked in a loading state.</li>
 *   <li><b>Unsaved draft protection</b>: every navigation entry point
 *       (list click, AI panel, appearance picker, close button, ESC) goes
 *       through {@link #requestNavigation} / {@link #pressEsc} /
 *       {@link #requestClose} - no path silently drops a draft, and no
 *       decision is ever derived from display strings.</li>
 *   <li><b>Cancel vs. exit decoupling</b>: cancelling an AI proposal is a
 *       purely local state change (always possible, even with an
 *       unresponsive server); closing the editor is always possible too.</li>
 * </ol>
 */
public final class StudioUiState {

	public enum Mode { LIST, EDIT, CREATE, APPEAR_PICK, DELETE_CONFIRM, AI, AI_SETTINGS }

	public enum AiPhase { IDLE, THINKING, PROPOSAL }

	/** outcome of a navigation request */
	public enum Nav {
		/** navigation happened */
		MOVED,
		/** the draft guard armed itself: ask the player to trigger again */
		NEEDS_CONFIRM,
		/** nothing to do (already there / invalid target) */
		IGNORED
	}

	/** outcome of an ESC press */
	public enum Esc {
		/** a pending AI proposal was dropped locally (caller may notify server best-effort) */
		CANCEL_PROPOSAL,
		/** a modal/editor was closed one level; another ESC may follow */
		MOVED,
		/** draft guard armed: player must press ESC again to discard */
		NEEDS_CONFIRM,
		/** the screen should close now */
		CLOSE
	}

	/** outcome of an AI send attempt */
	public enum AiSend {
		/** transport accepted the request: now THINKING */
		SENT,
		/** input was blank */
		EMPTY,
		/** a request is already in flight */
		BUSY,
		/** transport unavailable (no WorldForge server / not connected): never enters THINKING */
		NO_TRANSPORT
	}

	/** default AI response watchdog (ms) */
	public static final long AI_TIMEOUT_MS = 75_000L;

	private Mode mode = Mode.LIST;
	private boolean dirty = false;
	private boolean discardArmed = false;
	private boolean closeArmed = false;
	private boolean editorWasEditing = false;

	private boolean settingsDirty = false;
	private AiPhase aiPhase = AiPhase.IDLE;
	private String pendingProposalId = null;
	private String pendingDiff = "";
	private String pendingKind = "";
	private String pendingItemId = "";
	private long thinkingSince = 0L;
	private int aiRequestSeq = 0;
	private int aiOutstanding = 0;

	// ---------------------------------------------------------------- mode

	public Mode mode() {
		return mode;
	}

	/** raw mode switch used internally after a guard has been satisfied */
	private void setMode(Mode m) {
		mode = m;
		discardArmed = false;
		closeArmed = false;
	}

	public boolean editorVisible() {
		return mode == Mode.EDIT || mode == Mode.CREATE;
	}

	public boolean listSelectable() {
		return mode == Mode.LIST || mode == Mode.EDIT;
	}

	public boolean modalVisible() {
		return mode == Mode.APPEAR_PICK || mode == Mode.DELETE_CONFIRM;
	}

	public boolean aiVisible() {
		return mode == Mode.AI;
	}

	public boolean settingsVisible() {
		return mode == Mode.AI_SETTINGS;
	}

	// ---------------------------------------------------------- settings draft

	public boolean settingsDirty() {
		return settingsDirty;
	}

	public void markSettingsDirty() {
		settingsDirty = true;
	}

	public void markSettingsSaved() {
		settingsDirty = false;
		discardArmed = false;
	}

	// ---------------------------------------------------------------- draft

	public boolean dirty() {
		return dirty;
	}

	/** the player edited something in the editor form */
	public void markDirty() {
		dirty = true;
	}

	/** a successful save/persist round-trip: the draft matches the server again */
	public void markSaved() {
		dirty = false;
		discardArmed = false;
		closeArmed = false;
	}

	public boolean discardArmed() {
		return discardArmed;
	}

	/** begin editing an existing definition (or creating one) */
	public void beginEdit() {
		dirty = false;
		discardArmed = false;
		closeArmed = false;
		editorWasEditing = true;
		setMode(Mode.EDIT);
	}

	public void beginCreate() {
		dirty = false;
		discardArmed = false;
		closeArmed = false;
		editorWasEditing = false;
		setMode(Mode.CREATE);
	}

	public boolean editorWasEditing() {
		return editorWasEditing;
	}

	/**
	 * Navigate to another mode through the draft guard.
	 *
	 * <p>Leaving EDIT/CREATE with unsaved edits returns NEEDS_CONFIRM on the
	 * first call and MOVED on the second (the guard is armed in between).
	 * Every caller - buttons, ESC, list clicks, the AI panel button - must use
	 * this, so no entry point can silently drop a draft.</p>
	 */
	public Nav requestNavigation(Mode target) {
		if (target == mode) {
			return Nav.IGNORED;
		}
		boolean leavingEditor = editorVisible() && target != Mode.EDIT && target != Mode.CREATE;
		boolean leavingSettings = mode == Mode.AI_SETTINGS && target != Mode.AI_SETTINGS;
		if (leavingEditor && dirty) {
			if (!discardArmed) {
				discardArmed = true;
				return Nav.NEEDS_CONFIRM;
			}
			dirty = false;
		} else if (leavingSettings && settingsDirty) {
			if (!discardArmed) {
				discardArmed = true;
				return Nav.NEEDS_CONFIRM;
			}
			settingsDirty = false;
		}
		setMode(target);
		return Nav.MOVED;
	}

	/** open the appearance picker / delete confirm modal from the editor */
	public Nav openModal(Mode modal) {
		if (!editorVisible()) {
			return Nav.IGNORED;
		}
		setMode(modal);
		return Nav.MOVED;
	}

	/** leave a modal back to the editor it came from */
	public Nav closeModal() {
		if (!modalVisible()) {
			return Nav.IGNORED;
		}
		setMode(editorWasEditing ? Mode.EDIT : Mode.CREATE);
		return Nav.MOVED;
	}

	// ---------------------------------------------------------------- ESC / close

	/**
	 * One ESC press, resolved into an explicit action.
	 *
	 * <p>Never returns something that leaves the player unable to continue:
	 * each press moves exactly one level, and from LIST it closes.</p>
	 */
	public Esc pressEsc() {
		switch (mode) {
			case AI_SETTINGS -> {
				Nav nav = requestNavigation(Mode.LIST);
				return nav == Nav.NEEDS_CONFIRM ? Esc.NEEDS_CONFIRM : Esc.MOVED;
			}
			case AI -> {
				if (aiPhase == AiPhase.PROPOSAL || pendingProposalId != null) {
					cancelProposalLocal();
					return Esc.CANCEL_PROPOSAL;
				}
				setMode(Mode.LIST);
				return Esc.MOVED;
			}
			case APPEAR_PICK, DELETE_CONFIRM -> {
				setMode(editorWasEditing ? Mode.EDIT : Mode.CREATE);
				return Esc.MOVED;
			}
			case EDIT, CREATE -> {
				Nav nav = requestNavigation(Mode.LIST);
				return nav == Nav.NEEDS_CONFIRM ? Esc.NEEDS_CONFIRM : Esc.MOVED;
			}
			case LIST -> {
				return Esc.CLOSE;
			}
		}
		return Esc.MOVED;
	}

	/**
	 * The close button. Always ends in a close: with unsaved edits the first
	 * press arms the guard and the second closes, but there is no state in
	 * which closing is impossible (the reported "cannot exit" bug).
	 */
	public Esc requestClose() {
		if ((editorVisible() && dirty || settingsVisible() && settingsDirty) && !closeArmed) {
			closeArmed = true;
			return Esc.NEEDS_CONFIRM;
		}
		closeArmed = false;
		return Esc.CLOSE;
	}

	public boolean closeArmed() {
		return closeArmed;
	}

	// ---------------------------------------------------------------- AI lifecycle

	public AiPhase aiPhase() {
		return aiPhase;
	}

	public boolean hasPendingProposal() {
		return pendingProposalId != null;
	}

	public String pendingProposalId() {
		return pendingProposalId;
	}

	public String pendingDiff() {
		return pendingDiff;
	}

	public String pendingKind() {
		return pendingKind;
	}

	public String pendingItemId() {
		return pendingItemId;
	}

	public long thinkingSince() {
		return thinkingSince;
	}

	public int aiOutstanding() {
		return aiOutstanding;
	}

	/** next correlation id for an AI request */
	public int nextAiRequestId() {
		return ++aiRequestSeq;
	}

	/**
	 * Attempt to send a chat turn. THINKING is entered ONLY for {@link AiSend#SENT};
	 * a blank input, an in-flight request or an unavailable transport leave the
	 * panel interactive (no permanent loading state).
	 */
	public AiSend beginAiSend(String text, boolean transportAvailable) {
		if (text == null || text.isBlank()) {
			return AiSend.EMPTY;
		}
		if (aiPhase == AiPhase.THINKING || aiOutstanding > 0) {
			return AiSend.BUSY;
		}
		if (!transportAvailable) {
			return AiSend.NO_TRANSPORT;
		}
		aiOutstanding++;
		aiPhase = AiPhase.THINKING;
		thinkingSince = System.currentTimeMillis();
		return AiSend.SENT;
	}

	/** the transport refused after all (send threw / channel closed): restore immediately */
	public void aiTransportRejected() {
		aiOutstanding = Math.max(0, aiOutstanding - 1);
		if (aiOutstanding == 0) {
			aiPhase = AiPhase.IDLE;
		}
		thinkingSince = 0L;
	}

	/** the ack for our chat request arrived (server accepted it) */
	public void aiAcknowledged(boolean ok) {
		if (!ok) {
			aiTransportRejected();
		}
	}

	/** the async outcome arrived: either a plain answer or a proposal */
	public void aiOutcome(boolean ok, boolean hasProposal, String proposalId, String diff,
			String kind, String itemId) {
		aiOutstanding = Math.max(0, aiOutstanding - 1);
		thinkingSince = 0L;
		if (ok && hasProposal && proposalId != null && !proposalId.isBlank()) {
			pendingProposalId = proposalId;
			pendingDiff = diff == null ? "" : diff;
			pendingKind = kind == null ? "" : kind;
			pendingItemId = itemId == null ? "" : itemId;
			aiPhase = AiPhase.PROPOSAL;
		} else {
			aiPhase = AiPhase.IDLE;
		}
	}

	/** provider/transport failure surfaced as an error outcome */
	public void aiFailed() {
		aiTransportRejected();
	}

	/**
	 * Watchdog: releases a stuck THINKING state. Returns true when the state
	 * changed (so the caller can refresh widget visibility).
	 */
	public boolean tick(long nowMillis) {
		if (aiPhase == AiPhase.THINKING && thinkingSince > 0
				&& nowMillis - thinkingSince > AI_TIMEOUT_MS) {
			aiPhase = AiPhase.IDLE;
			aiOutstanding = 0;
			thinkingSince = 0L;
			return true;
		}
		return false;
	}

	/** the connection dropped: everything in flight is gone, keep the player interactive */
	public void onDisconnected() {
		aiPhase = AiPhase.IDLE;
		aiOutstanding = 0;
		thinkingSince = 0L;
		pendingProposalId = null;
		pendingDiff = "";
		pendingKind = "";
		pendingItemId = "";
	}

	/**
	 * Drop a pending proposal locally. This NEVER needs the server: the player
	 * can always cancel, even when the network is dead. The caller may send a
	 * best-effort notification afterwards.
	 */
	public void cancelProposalLocal() {
		pendingProposalId = null;
		pendingDiff = "";
		pendingKind = "";
		pendingItemId = "";
		if (aiPhase == AiPhase.PROPOSAL) {
			aiPhase = AiPhase.IDLE;
		}
	}

	// ---------------------------------------------------------------- visibility

	public boolean inputVisible() {
		return mode == Mode.AI;
	}

	public boolean sendVisible() {
		return mode == Mode.AI && aiPhase != AiPhase.PROPOSAL;
	}

	public boolean sendActive() {
		return mode == Mode.AI && aiPhase == AiPhase.IDLE;
	}

	public boolean confirmVisible() {
		return mode == Mode.AI && aiPhase == AiPhase.PROPOSAL;
	}

	public boolean cancelVisible() {
		return mode == Mode.AI && aiPhase == AiPhase.PROPOSAL;
	}

	/** the shapes/damage/etc. form is only editable while in the editor */
	public boolean editorFieldsVisible() {
		return editorVisible();
	}

	public boolean giveVisible() {
		return mode == Mode.EDIT;
	}

	public boolean deleteVisible() {
		return mode == Mode.EDIT;
	}

	/** status hint shown above the input while waiting for the model */
	public boolean thinkingHintVisible() {
		return mode == Mode.AI && aiPhase == AiPhase.THINKING;
	}

	/** snapshot of the AI conversation state, for tests and diagnostics */
	public List<String> describe() {
		List<String> out = new ArrayList<>();
		out.add("mode=" + mode);
		out.add("dirty=" + dirty);
		out.add("discardArmed=" + discardArmed);
		out.add("closeArmed=" + closeArmed);
		out.add("aiPhase=" + aiPhase);
		out.add("settingsDirty=" + settingsDirty);
		out.add("outstanding=" + aiOutstanding);
		out.add("pending=" + (pendingProposalId == null ? "-" : pendingProposalId));
		return out;
	}
}