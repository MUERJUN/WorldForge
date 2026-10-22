package dev.worldforge.chat;

/**
 * The task states the chat can show.
 *
 * <p>v0.7.1: the client used a single {@code busy} flag, so a message like
 * "已确认，正在执行" turned the busy indicator off while the task was still
 * running — and a "cancel requested" message turned it off before the task
 * had actually stopped. Each state is now explicit, and only a TERMINAL
 * state ends the busy state.</p>
 */
public enum ChatTaskState {

	/** nothing is running */
	IDLE,

	/** the model is being asked */
	THINKING,

	/** a tool waits for the player's approval */
	AWAITING_CONFIRMATION,

	/** an approved tool is running */
	EXECUTING,

	/** a cancellation was requested and is being carried out */
	CANCELLING,

	/** the task finished successfully (terminal) */
	SUCCEEDED,

	/** the task failed (terminal) */
	FAILED,

	/** the task was cancelled (terminal) */
	CANCELLED,

	/** the message was queued behind another task */
	QUEUED;

	/** True when the state means "the task is over". */
	public boolean isTerminal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED;
	}

	/** True while the task is still doing something. */
	public boolean isBusy() {
		return this == THINKING || this == AWAITING_CONFIRMATION
				|| this == EXECUTING || this == CANCELLING || this == QUEUED;
	}

	/**
	 * Maps a state name from the server.
	 *
	 * @return the state, or {@code null} when the name is unknown (an
	 *         unknown state must never clear a busy state)
	 */
	public static ChatTaskState fromServer(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		try {
			return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
