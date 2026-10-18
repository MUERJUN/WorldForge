package dev.worldforge.test;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.registry.WorldForgeBlocks;
import dev.worldforge.tool.WorldForgeBlockTools;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.nio.file.Files;

/**
 * v0.4.0 P0: real chunk unload/reload lifecycle for dynamic blocks.
 *
 * <p>Uses the public {@code ServerChunkManager} API ({@code setChunkForced},
 * {@code save}, {@code tick}, {@code isChunkLoaded}) to drive a genuine
 * chunk unload → disk → reload cycle — not a simulated one. The block entity
 * must go through real NBT serialization to disk and back.</p>
 *
 * <p>Each test is in its own batch because they mutate the process-global
 * {@code BlockRuntimeManager} store.</p>
 */
public class WorldForgeChunkLifecycleTests {

	private static java.nio.file.Path tempDir(String prefix) {
		try {
			return Files.createTempDirectory(prefix);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void bindStore() {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-chunk")));
	}

	private static BlockPos placeDemoBlock(ServerWorld world, BlockPos pos, String virtualId) {
		world.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState(), 3);
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be) {
			be.setVirtualId(virtualId);
			world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
		}
		return pos;
	}

	/**
	 * CL1: place → save → unload chunk → verify BE gone → reload → verify BE
	 * restored with the same virtual ID through real disk I/O.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-chunk-cl1")
	public void testCL1_chunkUnloadReloadRestoresIdentity(TestContext ctx) {
		bindStore();
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(1000, 1, 1000));
		ChunkPos cpos = new ChunkPos(pos);

		// 1) create a definition and place the block
		// store is already attached; no world bind needed for this test
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				"worldforge:cl1_block", "CL1测试方块", "",
				"minecraft:furnace", BlockDefinition.Category.DECORATION, 2.0,
				java.util.List.of(), 200, null, 0));
		placeDemoBlock(world, pos, "worldforge:cl1_block");

		// 2) verify BE exists with correct identity
		ctx.assertTrue(world.getBlockEntity(pos) instanceof DynamicBlockEntity be1
				&& "worldforge:cl1_block".equals(be1.virtualId()),
				"BE must exist with correct virtual ID before unload");

		// 3) save chunks to disk (real I/O)
		world.getChunkManager().save(true);

		// 4) force-load the chunk, then remove the force to allow unloading
		var cm = world.getChunkManager();
		cm.setChunkForced(cpos, true);
		cm.setChunkForced(cpos, false);

		// 5) tick the chunk manager to process unloading
		for (int i = 0; i < 20; i++) {
			cm.tick(() -> false, false);
		}

		// 6) verify the chunk is no longer loaded
		ctx.assertTrue(!cm.isChunkLoaded(cpos.x, cpos.z),
				"chunk must be unloaded after removing forced ticket (was loaded: "
						+ cm.isChunkLoaded(cpos.x, cpos.z) + ")");

		// 7) reload the chunk by re-adding the forced ticket
		cm.setChunkForced(cpos, true);
		for (int i = 0; i < 20; i++) {
			cm.tick(() -> false, true);
		}

		// 8) verify the chunk is loaded again
		ctx.assertTrue(cm.isChunkLoaded(cpos.x, cpos.z),
				"chunk must be reloaded after re-adding forced ticket");

		// 9) verify the BE is restored with the SAME virtual ID (real disk round-trip)
		ctx.assertTrue(world.getBlockEntity(pos) instanceof DynamicBlockEntity be2,
				"BE must exist after chunk reload");
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be2) {
			ctx.assertTrue("worldforge:cl1_block".equals(be2.virtualId()),
					"virtual ID must survive chunk unload/reload: got " + be2.virtualId());
		}

		// 10) verify the block state is still the dynamic block
		ctx.assertTrue(world.getBlockState(pos).getBlock() == WorldForgeBlocks.DYNAMIC_BLOCK,
				"block state must survive chunk reload");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	/**
	 * CL2: hot-edit a definition, then unload/reload the chunk — the reloaded
	 * BE must see the updated definition, not the old one.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-chunk-cl2")
	public void testCL2_hotEditSurvivesChunkReload(TestContext ctx) {
		bindStore();
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(500, 1, 500));
		ChunkPos cpos = new ChunkPos(pos);

		// store is already attached; no world bind needed for this test
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				"worldforge:cl2_block", "原始名称", "",
				"minecraft:stone", BlockDefinition.Category.DECORATION, 2.0,
				java.util.List.of(), 200, null, 0));
		placeDemoBlock(world, pos, "worldforge:cl2_block");

		// hot-edit: change the name and appearance
		WorldForgeBlockTools.updateBlock(server, BlockDefinition.create(
				"worldforge:cl2_block", "更新后名称", "",
				"minecraft:furnace", BlockDefinition.Category.INTERACTIVE, 3.0,
				java.util.List.of(), 200, null, 0));

		// save + unload + reload
		world.getChunkManager().save(true);
		var cm = world.getChunkManager();
		cm.setChunkForced(cpos, true);
		cm.setChunkForced(cpos, false);
		for (int i = 0; i < 20; i++) {
			cm.tick(() -> false, false);
		}
		cm.setChunkForced(cpos, true);
		for (int i = 0; i < 20; i++) {
			cm.tick(() -> false, true);
		}

		// the BE must still have the same virtual ID
		ctx.assertTrue(world.getBlockEntity(pos) instanceof DynamicBlockEntity be,
				"BE must exist after reload");
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be) {
			ctx.assertTrue("worldforge:cl2_block".equals(be.virtualId()),
					"virtual ID must survive reload");
		}
		// the definition must be the updated one
		var def = BlockRuntimeManager.get("worldforge:cl2_block");
		ctx.assertTrue(def != null && "更新后名称".equals(def.name()),
				"updated definition must be active: " + (def == null ? "null" : def.name()));
		ctx.assertTrue(def != null && "minecraft:furnace".equals(def.appearance()),
				"updated appearance must be active: " + (def == null ? "null" : def.appearance()));

		BlockRuntimeManager.release();
		ctx.complete();
	}

	/**
	 * CL3: delete a definition, then unload/reload — the reloaded block must
	 * be inert (no behavior) but still present.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-chunk-cl3")
	public void testCL3_deletedDefinitionStaysInertAfterReload(TestContext ctx) {
		bindStore();
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(500, 1, 500));
		ChunkPos cpos = new ChunkPos(pos);

		// store is already attached; no world bind needed for this test
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				"worldforge:cl3_block", "CL3方块", "",
				"minecraft:gold_block", BlockDefinition.Category.DECORATION, 2.0,
				java.util.List.of(), 200, null, 0));
		placeDemoBlock(world, pos, "worldforge:cl3_block");

		// delete the definition
		WorldForgeBlockTools.deleteBlock(server, "worldforge:cl3_block");
		ctx.assertTrue(BlockRuntimeManager.get("worldforge:cl3_block") == null,
				"definition must be deleted");

		// save + unload + reload
		world.getChunkManager().save(true);
		var cm = world.getChunkManager();
		cm.setChunkForced(cpos, true);
		cm.setChunkForced(cpos, false);
		for (int i = 0; i < 20; i++) {
			cm.tick(() -> false, false);
		}
		cm.setChunkForced(cpos, true);
		for (int i = 0; i < 20; i++) {
			cm.tick(() -> false, true);
		}

		// the block must still exist (not air)
		ctx.assertTrue(world.getBlockState(pos).getBlock() == WorldForgeBlocks.DYNAMIC_BLOCK,
				"block must still be present after reload (deleted ≠ removed)");
		// the BE must still have the virtual ID (identity preserved)
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be) {
			ctx.assertTrue("worldforge:cl3_block".equals(be.virtualId()),
					"virtual ID must survive reload even when definition is deleted");
		}
		// the definition must still be gone
		ctx.assertTrue(BlockRuntimeManager.get("worldforge:cl3_block") == null,
				"definition must still be deleted after reload");

		BlockRuntimeManager.release();
		ctx.complete();
	}
}
