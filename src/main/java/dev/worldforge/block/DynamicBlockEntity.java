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
	private static final String NBT_LAST_USE = "LastUseTick";

	private String virtualId = "";
	private int tickCounter = 0;
	private long lastUseTick = 0;

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

	public long lastUseTick() {
		return lastUseTick;
	}

	public void setLastUseTick(long tick) {
		this.lastUseTick = tick;
		markDirty();
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
		nbt.putLong(NBT_LAST_USE, lastUseTick);
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		virtualId = nbt.contains(NBT_ID) ? nbt.getString(NBT_ID) : "";
		tickCounter = nbt.contains(NBT_TICK) ? nbt.getInt(NBT_TICK) : 0;
		lastUseTick = nbt.contains(NBT_LAST_USE) ? nbt.getLong(NBT_LAST_USE) : 0;
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

	/**
	 * Server-side tick.
	 *
	 * <p>v0.4.0 phase 2b: the block entity no longer keeps its own countdown and
	 * budget. It only REGISTERS itself with {@link PeriodicScheduler}, which is
	 * idempotent per (world, position) and therefore safe across chunk reloads -
	 * a reloaded chunk re-registers the block but cannot duplicate the task.</p>
	 */
	public void serverTick(World world, BlockPos pos, BlockState state) {
		BlockDefinition def = definition();
		if (def == null || !def.periodic()) {
			return;
		}
		if (!(world instanceof net.minecraft.server.world.ServerWorld sw)) {
			return;
		}
		PeriodicScheduler.register(sw, pos, def.tickIntervalTicks());
	}

	/** drop the scheduled task for this position (block removed / definition gone) */
	public static void unschedule(World world, BlockPos pos) {
		if (world instanceof net.minecraft.server.world.ServerWorld sw) {
			PeriodicScheduler.cancel(sw.getRegistryKey(), pos);
		}
	}
}