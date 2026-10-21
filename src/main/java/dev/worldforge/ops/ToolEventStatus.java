package dev.worldforge.ops;

/**
 * Lifecycle of a single tool call, as reported to the chat stream.
 *
 * <p>The chat renders these; the model never sees its own chain of thought —
 * only the observable facts of what the tool layer actually did.</p>
 */
public enum ToolEventStatus {

	/** The call was accepted and is about to run. */
	STARTED,

	/** The call is running; may be reported more than once. */
	RUNNING,

	/** The call needs the player's explicit approval before it can proceed. */
	AWAITING_CONFIRMATION,

	/** The call finished and the intended effect really happened. */
	SUCCEEDED,

	/** The call finished with a failure; the effect did NOT happen. */
	FAILED,

	/** The call was cancelled by the player or by a world change. */
	CANCELLED
}
