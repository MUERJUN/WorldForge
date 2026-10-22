package dev.worldforge.ai;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A tool call that is waiting for the player's explicit approval.
 *
 * <p>v0.7.1: the two independent booleans are replaced by ONE atomic state.
 * The old pair could be in a contradictory state (approved AND cancelled),
 * and the client could then be told "cancelled" for an operation that was
 * already approved and executed.</p>
 *
 * <p>The state machine is:</p>
 * <pre>
 *   PENDING ──approve──&gt; APPROVED      (terminal)
 *          ──deny─────&gt; DENIED         (terminal)
 *          ──timeout──&gt; TIMED_OUT      (terminal)
 *          ──cancel───&gt; CANCELLED      (terminal)
 * </pre>
 *
 * <p>Terminal means terminal: once the state left PENDING it never changes
 * again, so a late "deny" cannot undo an approval and a late "approve"
 * cannot execute a denied or timed-out operation.</p>
 */
public final class PendingConfirmation {

	/** The mutually exclusive states of a confirmation. */
	public enum State {
		/** waiting for the player */
		PENDING,
		/** the player approved it; the operation may run */
		APPROVED,
		/** the player refused it */
		DENIED,
		/** nobody answered in time; the operation must NOT run */
		TIMED_OUT,
		/** it was cancelled (disconnect, world change, task cancel) */
		CANCELLED
	}

	private final String confirmationId;
	private final String conversationId;
	private final String requestId;
	private final String toolCallId;
	private final String toolName;
	private final String summary;
	private final List<String> itemIds;
	private final List<String> blockIds;
	private final String snapshot;
	private final long generation;
	private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
	private final CountDownLatch latch = new CountDownLatch(1);
	private final long createdAtMillis = System.currentTimeMillis();

	public PendingConfirmation(String confirmationId, String conversationId, String requestId,
			String toolCallId, String toolName, String summary, List<String> itemIds,
			List<String> blockIds, String snapshot, long generation) {
		this.confirmationId = confirmationId == null ? "" : confirmationId;
		this.conversationId = conversationId == null ? "" : conversationId;
		this.requestId = requestId == null ? "" : requestId;
		this.toolCallId = toolCallId == null ? "" : toolCallId;
		this.toolName = toolName == null ? "" : toolName;
		this.summary = summary == null ? "" : summary;
		this.itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
		this.blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
		this.snapshot = snapshot == null ? "" : snapshot;
		this.generation = generation;
	}

	public String confirmationId() {
		return confirmationId;
	}

	public String conversationId() {
		return conversationId;
	}

	public String requestId() {
		return requestId;
	}

	public String toolCallId() {
		return toolCallId;
	}

	public String toolName() {
		return toolName;
	}

	public String summary() {
		return summary;
	}

	public List<String> itemIds() {
		return itemIds;
	}

	public List<String> blockIds() {
		return blockIds;
	}

	public String snapshot() {
		return snapshot;
	}

	/** The generation of the task that owns this confirmation. */
	public long generation() {
		return generation;
	}

	public long createdAtMillis() {
		return createdAtMillis;
	}

	public State state() {
		return state.get();
	}

	/** True while the player still has to answer. */
	public boolean isPending() {
		return state.get() == State.PENDING;
	}

	/**
	 * The player approved the operation.
	 *
	 * @param id the confirmation id the player acted on; it must match
	 *            EXACTLY — a blank id approves nothing
	 * @return true when this call performed the transition
	 */
	public boolean approve(String id) {
		if (!matches(id)) {
			return false;
		}
		return state.compareAndSet(State.PENDING, State.APPROVED) && release();
	}

	/**
	 * The player refused the operation.
	 *
	 * @param id the confirmation id; must match EXACTLY
	 * @return true when this call performed the transition
	 */
	public boolean deny(String id) {
		if (!matches(id)) {
			return false;
		}
		return state.compareAndSet(State.PENDING, State.DENIED) && release();
	}

	/** The wait expired: the operation must NOT run. */
	public boolean timeout() {
		return state.compareAndSet(State.PENDING, State.TIMED_OUT) && release();
	}

	/** The operation was abandoned (cancel, disconnect, world change). */
	public boolean cancel() {
		return state.compareAndSet(State.PENDING, State.CANCELLED) && release();
	}

	/** True when the given id is exactly this confirmation's id. */
	private boolean matches(String id) {
		return id != null && !id.isBlank() && confirmationId.equals(id);
	}

	private boolean release() {
		latch.countDown();
		return true;
	}

	/**
	 * Waits for the player's decision.
	 *
	 * @return true ONLY when the operation was approved
	 */
	public boolean await(long seconds) {
		try {
			if (!latch.await(seconds, TimeUnit.SECONDS)) {
				timeout();
				return false;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			cancel();
			return false;
		}
		return state.get() == State.APPROVED;
	}

	/** A human-readable reason for the chat. */
	public String reason() {
		return switch (state.get()) {
			case APPROVED -> "已确认";
			case DENIED -> "玩家拒绝了这次操作";
			case TIMED_OUT -> "确认超时，操作未执行";
			case CANCELLED -> "操作已取消";
			case PENDING -> "等待确认";
		};
	}
}
