package dev.worldforge.test;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.component.VirtualBlockId;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.registry.WorldForgeBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.util.List;

/**
 * v0.4.0 virtual block identity + persistence tests.
 *
 * <p>The task requires proving Block Entity data persistence BEFORE any upper
 * layer is built, so these tests drive the real vanilla mechanisms: a placed
 * block's identity lives in the block entity, and {@code writeNbt}/{@code readNbt}
 * are exactly what chunk save/load calls.</p>
 */
public class WorldForgeBlockTests {

	private static java.nio.file.Path tempDir(String prefix) {
		try {
			return Files.createTempDirectory(prefix);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// ---- B1: placement writes the virtual identity into the block entity ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block")
	public void testB1_placementStoresIdentity(TestContext ctx) {
		var dir = tempDir("wf-b1");
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(dir));
		var id = "worldforge:magic_furnace";
		BlockRuntimeManager.create(BlockDefinition.create(id, "魔法熔炉", "", "minecraft:furnace",
				BlockDefinition.Category.PERIODIC, 3.0, List.of(), 200, "minecraft:iron_ingot", 1));

		BlockPos pos = new BlockPos(2, 1, 2);
		ctx.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		var be = ctx.getWorld().getBlockEntity(ctx.getAbsolutePos(pos));
		ctx.assertTrue(be instanceof DynamicBlockEntity, "placed dynamic block must create its block entity");

		// emulate a player placing the identified item
		ItemStack stack = new ItemStack(WorldForgeBlocks.DYNAMIC_BLOCK_ITEM);
		stack.set(WorldForgeComponents.VIRTUAL_BLOCK_ID, new VirtualBlockId(id));
		ctx.getWorld().setBlockState(ctx.getAbsolutePos(pos),
				WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState(), 3);
		WorldForgeBlocks.DYNAMIC_BLOCK.onPlaced(ctx.getWorld(), ctx.getAbsolutePos(pos),
				WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState(), null, stack);

		var placed = (DynamicBlockEntity) ctx.getWorld().getBlockEntity(ctx.getAbsolutePos(pos));
		ctx.assertTrue(id.equals(placed.virtualId()),
				"the block entity must carry the virtual id from the placed stack, got '" + placed.virtualId() + "'");
		ctx.assertTrue(placed.definition() != null, "identity must resolve to the definition");
		ctx.assertTrue(placed.definition().periodic(), "category must survive");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- B2: the identity survives a real NBT round trip (chunk save/load path) ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block")
	public void testB2_identitySurvivesNbtRoundTrip(TestContext ctx) {
		var dir = tempDir("wf-b2");
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(dir));
		var id = "worldforge:storage_crystal";
		BlockRuntimeManager.create(BlockDefinition.create(id, "储物水晶", "", "minecraft:amethyst_block",
				BlockDefinition.Category.INTERACTIVE, 2.0, List.of(), 200, null, 0));

		BlockPos pos = new BlockPos(3, 1, 3);
		var abs = ctx.getAbsolutePos(pos);
		ctx.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		var be = (DynamicBlockEntity) ctx.getWorld().getBlockEntity(abs);
		be.setVirtualId(id);

		// serialize exactly like the chunk writer does, then read it back into a
		// FRESH block entity: this is the persistence contract
		RegistryWrapper.WrapperLookup registries = ctx.getWorld().getRegistryManager();
		NbtCompound nbt = be.createNbt(registries);
		ctx.assertTrue(nbt.contains("VirtualBlockId"), "the identity must be written to NBT");

		DynamicBlockEntity restored = new DynamicBlockEntity(abs,
				WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		restored.loadFromNbt(nbt, registries);
		ctx.assertTrue(id.equals(restored.virtualId()),
				"identity must survive save/load, got '" + restored.virtualId() + "'");
		ctx.assertTrue(restored.definition() != null, "the restored block must resolve its definition again");

		// and a block entity without the tag must come back empty, not stale
		NbtCompound empty = new NbtCompound();
		DynamicBlockEntity blank = new DynamicBlockEntity(abs, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		blank.loadFromNbt(empty, registries);
		ctx.assertTrue(blank.virtualId().isEmpty(), "a fresh block entity must have no identity");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- B3: deleting a definition makes placed blocks inert, not broken ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block")
	public void testB3_deletedDefinitionLeavesBlockInert(TestContext ctx) {
		var dir = tempDir("wf-b3");
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(dir));
		var id = "worldforge:gone";
		BlockRuntimeManager.create(BlockDefinition.create(id, "将被删除", "", "minecraft:stone",
				BlockDefinition.Category.DECORATION, 1.0, List.of(), 200, null, 0));
		ctx.assertTrue(BlockRuntimeManager.isLive(id), "a created definition must be live");

		var del = BlockRuntimeManager.delete(id);
		ctx.assertTrue(del.ok(), del.message());
		ctx.assertTrue(!BlockRuntimeManager.isLive(id), "a deleted definition must not be live");
		ctx.assertTrue(BlockRuntimeManager.tombstones().contains(id),
				"deletion must leave a tombstone so placed blocks can report the失效 state");

		BlockPos pos = new BlockPos(1, 1, 1);
		ctx.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		var be = (DynamicBlockEntity) ctx.getWorld().getBlockEntity(ctx.getAbsolutePos(pos));
		be.setVirtualId(id);
		ctx.assertTrue(be.definition() == null, "an inert block must not resolve a deleted definition");

		// the identity itself is NOT erased: re-creating the id restores the block
		BlockRuntimeManager.create(BlockDefinition.create(id, "重建", "", "minecraft:stone",
				BlockDefinition.Category.DECORATION, 1.0, List.of(), 200, null, 0));
		ctx.assertTrue(be.definition() != null, "re-creating the same id must revive placed blocks");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- B4: definitions persist to disk and survive a reload (world restart) ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block")
	public void testB4_definitionsSurviveReload(TestContext ctx) {
		var dir = tempDir("wf-b4");
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(dir));
		var id = "worldforge:persistent_block";
		var created = BlockRuntimeManager.create(BlockDefinition.create(id, "持久方块", "", "minecraft:gold_block",
				BlockDefinition.Category.PERIODIC, 4.0, List.of(), 400, "minecraft:gold_nugget", 2));
		ctx.assertTrue(created.ok(), "creating the definition must succeed: " + created.message());
		ctx.assertTrue(Files.exists(dir.resolve("blocks.json")), "the definition must be on disk");

		// simulate a world restart on the same directory
		BlockRuntimeManager.release();
		BlockRuntimeManager.bind(dir);
		var loaded = BlockRuntimeManager.get(id);
		ctx.assertTrue(loaded != null, "the definition must be restored after a restart");
		ctx.assertTrue(loaded.category() == BlockDefinition.Category.PERIODIC, "category must survive");
		ctx.assertTrue(loaded.tickIntervalTicks() == 400, "tick interval must survive");
		ctx.assertTrue("minecraft:gold_nugget".equals(loaded.outputItemId()), "output item must survive");
		ctx.assertTrue(loaded.hardness() == 4.0, "hardness must survive");

		// and a damaged file must not wipe the live world state
		BlockRuntimeManager.release();
		try {
			Files.writeString(dir.resolve("blocks.json"), "{\"version\":1,\"blocks\":[{\"broken\":true}]}");
		} catch (Exception e) {
			ctx.assertTrue(false, "io: " + e);
		}
		BlockRuntimeManager.bind(dir);
		ctx.assertTrue(BlockRuntimeManager.get(id) == null,
				"a damaged file must load as empty rather than inventing definitions");
		boolean quarantined = false;
		try (var stream = Files.list(dir)) {
			quarantined = stream.anyMatch(p -> p.getFileName().toString().startsWith("blocks.json.broken-"));
		} catch (Exception e) {
			ctx.assertTrue(false, "io while checking quarantine: " + e);
		}
		ctx.assertTrue(quarantined, "the damaged file must be quarantined, never overwritten");

		BlockRuntimeManager.release();
		ctx.complete();
	}
}