package dev.worldforge.ops;

/**
 * The implementation of a tool.
 *
 * <p>Executors are invoked on the SERVER THREAD. A tool that needs to do
 * slow work (a Gradle build) must not block that thread — see
 * {@link ToolExecutor} implementations that hand the work to a background
 * executor and report progress through the {@link ToolEventSink}.</p>
 */
@FunctionalInterface
public interface ToolExecutor {

	/**
	 * Executes the tool.
	 *
	 * @param ctx    the authoritative context
	 * @param request the validated request
	 * @param sink   progress reporter
	 * @return the outcome; must reflect what REALLY happened
	 */
	ToolResult execute(ToolContext ctx, ToolRequest request, ToolEventSink sink);
}
