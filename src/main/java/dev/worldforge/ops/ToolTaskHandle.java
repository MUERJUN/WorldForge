package dev.worldforge.ops;

/**
 * The task handle a tool may see.
 *
 * <p>Kept in the {@code ops} package on purpose: the tool layer must not
 * depend on the agent implementation, only on this small contract.</p>
 *
 * <p>A tool that starts something expensive registers how to stop it
 * ({@link #onCancel}); a tool that only reads can ignore the handle
 * completely.</p>
 */
public interface ToolTaskHandle {

	/** True once the task was cancelled; the tool must stop and report it. */
	boolean isCancelled();

	/**
	 * Registers how to stop the work this tool started.
	 *
	 * <p>Registered hooks run exactly once, when the cancellation is
	 * requested. A hook registered after the cancellation already happened is
	 * run immediately, so a tool that starts late is still stopped.</p>
	 */
	void onCancel(Runnable action);

	/** The id of the task, for correlation in logs and events. */
	String taskId();
}
