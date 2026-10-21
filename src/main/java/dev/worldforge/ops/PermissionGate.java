package dev.worldforge.ops;

import dev.worldforge.network.WorldForgeStudioProtocol;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * The authority check for tool calls.
 *
 * <p>Three independent gates, all of which must pass:</p>
 * <ol>
 *   <li><b>Authority</b> — {@link ToolPermission#WRITE} and {@link
 *       ToolPermission#ADMIN} need the same OP level as the {@code /wf}
 *       mutating commands. A tool can never grant more authority than the
 *       command layer, and a prompt can never substitute for this check.</li>
 *   <li><b>Scope</b> — a mutating call may only touch the item/block ids that
 *       the player confirmed. The scope is built from the confirmed operation,
 *       not from the model's free text.</li>
 *   <li><b>Target</b> — the concrete target must still exist and still match
 *       the snapshot the player saw when confirming.</li>
 * </ol>
 */
public final class PermissionGate {

	/** Why a call was refused. Stable codes the model can react to. */
	public static final String ERR_NO_PERMISSION = "no_permission";
	public static final String ERR_OUT_OF_SCOPE = "out_of_scope";
	public static final String ERR_TARGET_CHANGED = "target_changed";
	public static final String ERR_NEEDS_CONFIRMATION = "needs_confirmation";
	public static final String ERR_UNKNOWN_TOOL = "unknown_tool";
	public static final String ERR_INVALID_ARGUMENTS = "invalid_arguments";
	public static final String ERR_CANCELLED = "cancelled";
	public static final String ERR_SERVER_ERROR = "server_error";
	public static final String ERR_NOT_ALLOWED = "not_allowed";

	private PermissionGate() {
	}

	/**
	 * Checks that the player may invoke a tool at all.
	 *
	 * @return {@code null} when allowed, otherwise an error code
	 */
	public static String checkAuthority(ToolDescriptor descriptor, ServerPlayerEntity player) {
		if (descriptor == null) {
			return ERR_UNKNOWN_TOOL;
		}
		if (descriptor.permission() == ToolPermission.READ) {
			return null; // every player may look at their own context
		}
		if (player == null) {
			return ERR_NO_PERMISSION;
		}
		if (!WorldForgeStudioProtocol.hasEditPermission(player)) {
			return ERR_NO_PERMISSION;
		}
		return null;
	}

	/**
	 * Checks that a mutating call stays inside the confirmed scope.
	 *
	 * @param itemIds ids the operation will touch
	 * @param blockIds ids the operation will touch
	 * @return {@code null} when allowed, otherwise an error code
	 */
	public static String checkScope(ToolContext ctx, java.util.Collection<String> itemIds,
			java.util.Collection<String> blockIds) {
		if (ctx == null || ctx.scope() == null) {
			return ERR_OUT_OF_SCOPE;
		}
		if (!ctx.scope().worldMutationAllowed()) {
			return ERR_OUT_OF_SCOPE;
		}
		for (String id : itemIds) {
			if (id != null && !id.isBlank() && !ctx.scope().allowsItem(id)) {
				return ERR_OUT_OF_SCOPE;
			}
		}
		for (String id : blockIds) {
			if (id != null && !id.isBlank() && !ctx.scope().allowsBlock(id)) {
				return ERR_OUT_OF_SCOPE;
			}
		}
		return null;
	}

	/**
	 * Checks that the target still matches the snapshot the player confirmed.
	 *
	 * <p>This is the guard against "the item I was looking at was swapped
	 * while the AI was thinking" and against two players editing the same
	 * definition concurrently.</p>
	 *
	 * @param expectedSnapshot the snapshot captured at confirmation time
	 * @param currentSnapshot  the snapshot taken now
	 * @return {@code null} when unchanged, otherwise an error code
	 */
	public static String checkSnapshot(String expectedSnapshot, String currentSnapshot) {
		String e = expectedSnapshot == null ? "" : expectedSnapshot;
		String c = currentSnapshot == null ? "" : currentSnapshot;
		if (e.equals(c)) {
			return null;
		}
		return ERR_TARGET_CHANGED;
	}
}
