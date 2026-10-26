package dev.worldforge.ops.worldedit;

import net.minecraft.util.math.BlockPos;

/**
 * v0.8.0: the string/syntax validation layer of the world edit core —
 * everything checkable WITHOUT a live registry (plan shapes, hard limits,
 * world bounds math). Registry-dependent state resolution lives in the
 * executor, on the server thread.
 */
public final class WorldEditValidator {

	/** The hard per-plan limit (spec: 256). */
	public static final int MAX_BLOCKS_PER_PLAN = 256;

	/** The vanilla world border's hard horizontal limit. */
	public static final int MAX_HORIZONTAL = 30_000_000;

	public WorldEditValidator() {
	}

	/** True when the position is inside the world's Y bounds and border. */
	public static boolean inWorldBounds(BlockPos pos, int bottomY, int topYInclusive) {
		if (pos.getY() < bottomY || pos.getY() > topYInclusive) {
			return false;
		}
		return Math.abs(pos.getX()) <= MAX_HORIZONTAL && Math.abs(pos.getZ()) <= MAX_HORIZONTAL;
	}
}
