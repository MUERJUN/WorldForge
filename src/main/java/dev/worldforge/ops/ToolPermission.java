package dev.worldforge.ops;

/**
 * Permission level required to invoke a {@link ToolDescriptor}.
 *
 * <p>The level is checked TWICE: once by {@link PermissionGate} before the
 * tool is dispatched, and again inside the tool implementation itself before
 * it touches world state. A prompt is never a substitute for this check.</p>
 */
public enum ToolPermission {

	/**
	 * Read-only inspection of the player's own immediate context. Every player
	 * may use these; they never mutate the world.
	 */
	READ,

	/**
	 * Mutates WorldForge definitions or gives items. Requires the same
	 * authority as {@code /wf} mutating commands (OP2), AND explicit player
	 * confirmation of the concrete operation summary.
	 */
	WRITE,

	/**
	 * Heavier or irreversible operations (building an export artifact, deleting
	 * data). Requires {@link #WRITE} authority plus its own confirmation.
	 */
	ADMIN
}
