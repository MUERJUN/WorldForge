package dev.worldforge.block;

import dev.worldforge.component.VirtualBlockId;
import dev.worldforge.component.WorldForgeComponents;
import net.minecraft.block.Block;
import dev.worldforge.registry.WorldForgeBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The single generic dynamic block (v0.4.0).
 *
 * <p>One native block type is registered at startup; every user-created
 * "virtual block" is an instance of it whose identity lives in
 * {@link DynamicBlockEntity}. Appearance is borrowed from a vanilla block for
 * rendering (see {@link BlockCatalog}); physics/light/redstone come from THIS
 * block and are therefore shared, which is documented as a runtime limitation
 * rather than faked per definition.</p>
 */
public class DynamicBlock extends Block implements net.minecraft.block.BlockEntityProvider {

	public DynamicBlock(Settings settings) {
		super(settings);
	}

	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new DynamicBlockEntity(pos, state);
	}

	/** placement copies the virtual identity from the item into the block entity */
	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.onPlaced(world, pos, state, placer, stack);
		if (world.isClient) {
			return;
		}
		String id = stack.get(WorldForgeComponents.VIRTUAL_BLOCK_ID) instanceof VirtualBlockId v ? v.id() : null;
		if (id == null) {
			// placed from a plain generic item (e.g. /setblock): no identity
			return;
		}
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be) {
			be.setVirtualId(id);
			world.updateListeners(pos, state, state, 3);
		}
	}

	/** server-side ticking for PERIODIC definitions (quota handled in the manager) */
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
			BlockEntityType<T> type) {
		if (world.isClient || type != WorldForgeBlocks.DYNAMIC_BLOCK_ENTITY) {
			return null;
		}
		return (w, pos, st, be) -> {
			if (be instanceof DynamicBlockEntity dbe && w instanceof ServerWorld) {
				dbe.serverTick(w, pos, st);
			}
		};
	}

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
			BlockHitResult hit) {
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		if (!(world.getBlockEntity(pos) instanceof DynamicBlockEntity be)) {
			return ActionResult.PASS;
		}
		String id = be.virtualId();
		if (!BlockRuntimeManager.isLive(id)) {
			// deleted definition: the block stays but is inert, and we say so
			// instead of pretending something happened
			player.sendMessage(net.minecraft.text.Text.literal(
					"§c该方块的定义已被删除（" + id + "），当前为失效状态"), true);
			return ActionResult.SUCCESS;
		}
		BlockDefinition def = BlockRuntimeManager.get(id);
		return BlockBehaviorEngine.onUse(world, pos, player, def, be);
	}

	/** breaking a dynamic block returns the SAME virtual identity as an item */
	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && !world.isClient
				&& world.getBlockEntity(pos) instanceof DynamicBlockEntity be
				&& !be.virtualId().isBlank()) {
			ItemStack drop = new ItemStack(WorldForgeBlocks.DYNAMIC_BLOCK_ITEM);
			drop.set(WorldForgeComponents.VIRTUAL_BLOCK_ID, new VirtualBlockId(be.virtualId()));
			Block.dropStack(world, pos, drop);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	@Override
	public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		// the identified drop is produced in onStateReplaced
		return super.onBreak(world, pos, state, player);
	}

	/** never used silently: exposes the shared-physics limitation in-game */
	@Override
	public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		super.onBlockAdded(state, world, pos, oldState, notify);
	}
}