package dev.worldforge.ops.tools;

import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.ops.ToolContext;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

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
		String virtualId) {

	/** Reads the current target from the player's context. */
	public static TargetBlock read(ToolContext ctx) {
		if (ctx == null || ctx.player() == null || ctx.world() == null) {
			return new TargetBlock(false, BlockPos.ORIGIN, "", "");
		}
		HitResult hit = ctx.player().raycast(6.0f, 0.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return new TargetBlock(false, BlockPos.ORIGIN, "", "");
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
		return new TargetBlock(true, pos, vanillaId, virtualId);
	}
}
