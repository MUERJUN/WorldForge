package dev.worldforge.ai;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tool call that is waiting for the player's explicit approval.
 *
 * <p>The worker thread of the agent loop blocks on {@link #await(long)} while
 * the player decides. Resolving the confirmation is the ONLY way out — a
 * timeout, a disconnect or a world change all resolve it as "cancelled".</p>
 */
public final class PendingConfirmation {

	/** how long a confirmation stays valid */
	public static final long DEFAULT_TIMEOUT_SECONDS = 300;

	private final String confirmationId;
	private final String conversationId;
	private final String requestId;
	private final String toolCallId;
	private final String toolName;
	private final String summary;
	private final List<String> itemIds;
	private final List<String> blockIds;
	private final String snapshot;
	private final CountDownLatch latch = new CountDownLatch(1);
	private final AtomicBoolean confirmed = new AtomicBoolean(false);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final long createdAtMillis = System.currentTimeMillis();

	public PendingConfirmation(String confirmationId, String conversationId, String requestId,
			String toolCallId, String toolName, String summary, List<String> itemIds,
			List<String> blockIds, String snapshot) {
		this.confirmationId = confirmationId == null ? "" : confirmationId;
		this.conversationId = conversationId == null ? "" : conversationId;
		this.requestId = requestId == null ? "" : requestId;
		this.toolCallId = toolCallId == null ? "" : toolCallId;
		this.toolName = toolName == null ? "" : toolName;
		this.summary = summary == null ? "" : summary;
		this.itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
		this.blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
		this.snapshot = snapshot == null ? "" : snapshot;
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

	public long createdAtMillis() {
		return createdAtMillis;
	}

	/** The player approved the operation. */
	public void confirm() {
		if (confirmed.compareAndSet(false, true)) {
			latch.countDown();
		}
	}

	/** The player refused, or the operation was aborted. */
	public void cancel() {
		if (cancelled.compareAndSet(false, true)) {
			latch.countDown();
		}
	}

	public boolean isConfirmed() {
		return confirmed.get();
	}

	public boolean isCancelled() {
		return cancelled.get();
	}

	/**
	 * Waits for the player's decision.
	 *
	 * @return true when the player confirmed, false on timeout or cancellation
	 */
	public boolean await(long seconds) {
		try {
			return latch.await(seconds, TimeUnit.SECONDS) && confirmed.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			cancel();
			return false;
		}
	}
}
