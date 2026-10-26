package dev.worldforge.ops.tools;

import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.ops.ToolContext;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Resolves the block the player is currently aiming at.
 *
 * <p>One shared implementation for {@code inspect_target_block} and
 * {@code modify_target_block} so both agree on what "the target" means.</p>
 */
public record TargetBlock(
		boolean hit,
		BlockPos pos,
		String vanillaId,
		String virtualId,
		Direction face,
		String diagnostic) {

	/** A compact description of where the player was looking, for the log. */
	public String diagnostic() {
		return diagnostic == null ? "" : diagnostic;
	}

	/** Reads the current target from the player's context. */
	public static TargetBlock read(ToolContext ctx) {
		if (ctx == null || ctx.player() == null || ctx.world() == null) {
			return new TargetBlock(false, BlockPos.ORIGIN, "", "", null, "no player context");
		}
		// tickDelta 1.0 = the CURRENT frame: with 0.0 the ray would use the
		// interpolated (previous) yaw/pitch and could miss what the player sees
		HitResult hit = ctx.player().raycast(6.0, 1.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return new TargetBlock(false, BlockPos.ORIGIN, "", "", null,
					"pos=" + ctx.player().getPos() + " eye=" + ctx.player().getEyePos()
							+ " yaw=" + ctx.player().getYaw() + " pitch=" + ctx.player().getPitch()
							+ " world=" + ctx.world().getRegistryKey().getValue());
		}
		BlockHitResult bhr = (BlockHitResult) hit;
		BlockPos pos = bhr.getBlockPos();
		BlockState state = ctx.world().getBlockState(pos);
		String vanillaId = Registries.BLOCK.getId(state.getBlock()).toString();
		String virtualId = null;
		var be = ctx.world().getBlockEntity(pos);
		if (be instanceof DynamicBlockEntity dbe) {
			virtualId = dbe.virtualId();
		}
		return new TargetBlock(true, pos, vanillaId, virtualId, bhr.getSide(), "");
	}
}
