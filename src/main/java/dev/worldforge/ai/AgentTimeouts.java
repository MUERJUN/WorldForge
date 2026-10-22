package dev.worldforge.ai;

/**
 * Every effective timeout and budget of the chat runtime, in ONE place.
 *
 * <p>v0.7.1: the constants used to live in three different classes with
 * different meanings, and one of them (the orchestrator's wait for a tool
 * call) was SHORTER than the Gradle build the tool actually runs — so a slow
 * but perfectly healthy export was reported as a failure. Every limit is now
 * named, justified and asserted by a test.</p>
 */
public final class AgentTimeouts {

	// ---- model ----

	/**
	 * How long ONE model request may take.
	 *
	 * <p>This is per request, not per task: a task may make several
	 * requests, and each one gets the full budget.</p>
	 */
	public static final int MODEL_REQUEST_SECONDS = 120;

	/**
	 * How long the player may take to answer a confirmation.
	 *
	 * <p>When it expires the pending operation is TIMED OUT — it is not
	 * executed, and the task is told why.</p>
	 */
	public static final int CONFIRMATION_WAIT_SECONDS = 300;

	// ---- tools ----

	/**
	 * How long a tool's PREPARATION may take.
	 *
	 * <p>Preparation computes the real impact of an operation (for the export
	 * tool that means creating the plan). It never runs the operation
	 * itself.</p>
	 */
	public static final int TOOL_PREPARE_SECONDS = 60;

	/**
	 * The default budget for a tool call that is not long running.
	 *
	 * <p>A tool that runs something slower declares its own budget in its
	 * {@link dev.worldforge.ops.ToolDescriptor} — and that budget is never
	 * shorter than the work it waits for.</p>
	 */
	public static final int DEFAULT_TOOL_SECONDS = 120;

	/**
	 * How long a tool may wait for the server thread to run it.
	 *
	 * <p>This is a SAFETY NET, not the tool's budget: the tool's own timeout
	 * is authoritative and always longer than the work it does.</p>
	 */
	public static final int TOOL_DISPATCH_GRACE_SECONDS = 30;

	/**
	 * How long the Gradle build of an export may run.
	 *
	 * <p>Owned by the exporter itself ({@code ExportExecutor}); mirrored here
	 * so the chat can wait long enough for it. A first export downloads the
	 * Gradle distribution and the Fabric dependencies, which is slow on a
	 * cold machine.</p>
	 */
	public static final int GRADLE_BUILD_MINUTES = 20;

	/**
	 * The budget of the export tool: the build time plus a margin for the
	 * project generation and the final packaging.
	 *
	 * <p>v0.7.1: this used to be a hard-coded 12 minutes in the orchestrator,
	 * which is SHORTER than the 20-minute build — so a healthy but slow
	 * export was killed and reported as a failure. Now the tool's budget
	 * follows the build it waits for.</p>
	 */
	public static final int EXPORT_TOOL_SECONDS = GRADLE_BUILD_MINUTES * 60 + 180;

	// ---- budgets ----

	/** How many tool ROUNDS one task may take (cumulative over the task). */
	public static final int MAX_TOOL_ROUNDS_PER_TASK = 6;

	/** How many tool CALLS one task may make (cumulative over the task). */
	public static final int MAX_TOOL_CALLS_PER_TASK = 12;

	/** How many tasks one player may run at the same time. */
	public static final int MAX_ACTIVE_TASKS_PER_PLAYER = 4;

	/** How many worker threads exist in total. */
	public static final int MAX_WORKERS = 8;

	/** How many messages may wait in one conversation's queue. */
	public static final int MAX_QUEUED_MESSAGES = 4;

	private AgentTimeouts() {
	}
}
