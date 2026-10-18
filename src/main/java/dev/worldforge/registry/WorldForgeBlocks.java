package dev.worldforge.registry;

import dev.worldforge.WorldForge;
import dev.worldforge.block.DynamicBlock;
import dev.worldforge.block.DynamicBlockEntity;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * The generic native blocks WorldForge registers at startup (v0.4.0).
 *
 * <p>Everything is registered HERE, during mod init, before the registry
 * freeze - never later. A user-created "virtual block" is not a registry
 * entry: it is this one block plus a {@link DynamicBlockEntity} carrying a
 * {@code worldforge:<name>} identity, which is why the number of native blocks
 * stays constant no matter how many blocks a player creates.</p>
 */
public final class WorldForgeBlocks {

	/** the one generic dynamic block; physics/light/redstone are shared by design */
	public static final DynamicBlock DYNAMIC_BLOCK = new DynamicBlock(
			AbstractBlock.Settings.create().strength(2.0f).requiresTool());

	public static final Item DYNAMIC_BLOCK_ITEM = new BlockItem(DYNAMIC_BLOCK, new Item.Settings());

	public static BlockEntityType<DynamicBlockEntity> DYNAMIC_BLOCK_ENTITY;

	public static void register() {
		Registry.register(Registries.BLOCK, WorldForge.id("dynamic_block"), DYNAMIC_BLOCK);
		DYNAMIC_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE,
				WorldForge.id("dynamic_block"), BlockEntityType.Builder
						.create(DynamicBlockEntity::new, DYNAMIC_BLOCK)
						.build());
		Registry.register(Registries.ITEM, WorldForge.id("dynamic_block"), DYNAMIC_BLOCK_ITEM);
		WorldForge.LOGGER.info("[WorldForge] registered one native dynamic block + block entity type");
	}

	private WorldForgeBlocks() {
	}
}