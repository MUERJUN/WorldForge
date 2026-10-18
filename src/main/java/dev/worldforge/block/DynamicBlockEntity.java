package dev.worldforge.block;

import dev.worldforge.component.VirtualBlockId;
import dev.worldforge.registry.WorldForgeBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The block entity of a placed virtual block (v0.4.0).
 *
 * <p>This is where a dynamic block's identity and per-instance state live.
 * Because it is an ordinary block entity, it is written and read by the
 * vanilla chunk save/load path - {@link #writeNbt}/{@link #readNbt} ARE the
 * persistence mechanism, so "does the identity survive a chunk unload?" is a
 * question about these two methods and is verified by round-tripping real NBT
 * in the GameTests.</p>
 */
public class DynamicBlockEntity extends BlockEntity {

	private static final String NBT_ID = "VirtualBlockId";
	private static final String NBT_TICK = "TickCounter";

	private String virtualId = "";
	private int tickCounter = 0;

	public DynamicBlockEntity(BlockPos pos, BlockState state) {
		this(WorldForgeBlocks.DYNAMIC_BLOCK_ENTITY, pos, state);
	}

	public DynamicBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public String virtualId() {
		return virtualId;
	}

	public void setVirtualId(String id) {
		this.virtualId = id == null ? "" : id;
		markDirty();
	}

	public int tickCounter() {
		return tickCounter;
	}

	public BlockDefinition definition() {
		return BlockRuntimeManager.get(virtualId);
	}

	// ---------------------------------------------------------------- persistence

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		nbt.putString(NBT_ID, virtualId);
		nbt.putInt(NBT_TICK, tickCounter);
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		virtualId = nbt.contains(NBT_ID) ? nbt.getString(NBT_ID) : "";
		tickCounter = nbt.contains(NBT_TICK) ? nbt.getInt(NBT_TICK) : 0;
	}

	/**
	 * Public wrapper around {@link #readNbt}: the chunk loader uses the
	 * protected override directly, while tests (and any future data migration)
	 * need to feed real NBT through the SAME code path.
	 */
	public void loadFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		readNbt(nbt, registries);
	}

	/** identity helper used by placement and by the item form of the block */
	public static String idOf(BlockEntity be) {
		return be instanceof DynamicBlockEntity d ? d.virtualId() : null;
	}

	/** server-side tick: periodic emission with a hard quota */
	public void serverTick(World world, BlockPos pos, BlockState state) {
		BlockDefinition def = definition();
		if (def == null || !def.periodic()) {
			return;
		}
		tickCounter++;
		if (tickCounter < Math.max(1, def.tickIntervalTicks())) {
			return;
		}
		// v0.4.0 fix: only consume the pending tick when the emission actually
		// ran; a budget-starved block keeps its counter and retries next tick.
		if (BlockRuntimeManager.runPeriodic(world, pos, def)) {
			tickCounter = 0;
			markDirty();
		}
	}
}