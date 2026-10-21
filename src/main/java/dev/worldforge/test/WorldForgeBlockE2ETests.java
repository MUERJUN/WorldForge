package dev.worldforge.test;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockBehaviorEngine;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.registry.WorldForgeBlocks;
import dev.worldforge.tool.WorldForgeBlockTools;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * v0.4.0 P1: end-to-end block lifecycle + behavior engine safety tests.
 */
public class WorldForgeBlockE2ETests {

	private static java.nio.file.Path tempDir(String prefix) {
		return TestTempDirs.create(prefix);
	}

	private static void setup() {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-e2e")));
	}

	private static BlockPos placeBlock(ServerWorld world, BlockPos pos, String id) {
		world.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState(), 3);
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be) {
			be.setVirtualId(id);
			world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
		}
		return pos;
	}

	/**
	 * E2E: full lifecycle — create → give → place → info → hot-update → delete → verify inert.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-e2e")
	public void testE2E_fullLifecycle(TestContext ctx) {
		setup();
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(2, 2, 2));
		String id = "worldforge:e2e_block";

		// 1) create
		var r1 = WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				id, "E2E方块", "", "minecraft:furnace",
				BlockDefinition.Category.INTERACTIVE, 2.0, List.of(), 200, null, 0));
		ctx.assertTrue(r1.ok(), "create must succeed: " + r1.message());
		ctx.assertTrue(BlockRuntimeManager.get(id) != null, "definition must exist");

		// 2) give
		var player = ctx.createMockCreativeServerPlayerInWorld();
		var r2 = WorldForgeBlockTools.give(player, id, 1);
		ctx.assertTrue(r2.ok(), "give must succeed: " + r2.message());

		// 3) place
		placeBlock(world, pos, id);
		ctx.assertTrue(world.getBlockEntity(pos) instanceof DynamicBlockEntity be
				&& id.equals(be.virtualId()), "placed BE must have correct ID");

		// 4) info
		var be = (DynamicBlockEntity) world.getBlockEntity(pos);
		var def = BlockRuntimeManager.get(be.virtualId());
		ctx.assertTrue(def != null && "E2E方块".equals(def.name()), "info must return correct definition");

		// 5) hot-update
		var r3 = WorldForgeBlockTools.updateBlock(server, BlockDefinition.create(
				id, "更新名称", "", "minecraft:gold_block",
				BlockDefinition.Category.INTERACTIVE, 3.0, List.of(), 200, null, 0));
		ctx.assertTrue(r3.ok(), "update must succeed: " + r3.message());
		var updated = BlockRuntimeManager.get(id);
		ctx.assertTrue(updated != null && "更新名称".equals(updated.name()), "name must be updated");
		ctx.assertTrue(updated != null && "minecraft:gold_block".equals(updated.appearance()),
				"appearance must be updated");

		// 6) delete
		var r4 = WorldForgeBlockTools.deleteBlock(server, id);
		ctx.assertTrue(r4.ok(), "delete must succeed: " + r4.message());
		ctx.assertTrue(BlockRuntimeManager.get(id) == null, "definition must be gone");

		// 7) block still present but inert
		ctx.assertTrue(world.getBlockState(pos).getBlock() == WorldForgeBlocks.DYNAMIC_BLOCK,
				"block must still be present after delete");
		ctx.assertTrue(BlockRuntimeManager.get(be.virtualId()) == null, "BE's definition must be null");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	/**
	 * BEH1: cooldown prevents rapid re-activation.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-beh-beh1")
	public void testBEH1_cooldownPreventsRapidActivation(TestContext ctx) {
		setup();
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(2, 2, 2));
		String id = "worldforge:beh1_block";

		var beh = new BehaviorDefinition("right_click", List.of("heal"), Map.of("amount", 4), 100);
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				id, "BEH1", "", "minecraft:furnace",
				BlockDefinition.Category.INTERACTIVE, 2.0, List.of(beh), 200, null, 0));
		placeBlock(world, pos, id);

		var player = ctx.createMockCreativeServerPlayerInWorld();
		var be = (DynamicBlockEntity) world.getBlockEntity(pos);
		var def = BlockRuntimeManager.get(id);
		long tick1 = world.getTime();

		// first use: should trigger
		var r1 = BlockBehaviorEngine.onUse(world, pos, player, def, be);
		ctx.assertTrue(r1 == ActionResult.SUCCESS, "first use must succeed: " + r1);
		ctx.assertTrue(be.lastUseTick() == tick1, "first use must set lastUseTick");

		// second use immediately: should be on cooldown (no tick advance)
		var r2 = BlockBehaviorEngine.onUse(world, pos, player, def, be);
		ctx.assertTrue(r2 == ActionResult.PASS, "second use within cooldown must PASS: " + r2);
		ctx.assertTrue(be.lastUseTick() == tick1, "cooldown must not update lastUseTick");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	/**
	 * BEH2: failed behavior (invalid effect) returns PASS, never silently succeeds.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-beh-beh2")
	public void testBEH2_failedBehaviorDoesNotSucceed(TestContext ctx) {
		setup();
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(2, 2, 2));
		String id = "worldforge:beh2_block";

		var beh = new BehaviorDefinition("right_click", List.of("apply_effect"),
				Map.of("effect", "minecraft:nonexistent_effect"), 0);
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				id, "BEH2", "", "minecraft:stone",
				BlockDefinition.Category.INTERACTIVE, 2.0, List.of(beh), 200, null, 0));
		placeBlock(world, pos, id);

		var player = ctx.createMockCreativeServerPlayerInWorld();
		var be = (DynamicBlockEntity) world.getBlockEntity(pos);
		var def = BlockRuntimeManager.get(id);

		var r = BlockBehaviorEngine.onUse(world, pos, player, def, be);
		ctx.assertTrue(r == ActionResult.PASS, "invalid effect must not succeed: " + r);
		ctx.assertTrue(be.lastUseTick() == 0, "failed behavior must not update lastUseTick");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	/**
	 * BEH3: unsupported action returns false, never silently succeeds.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-beh-beh3")
	public void testBEH3_unsupportedActionFails(TestContext ctx) {
		setup();
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(2, 2, 2));
		String id = "worldforge:beh3_block";

		var beh = new BehaviorDefinition("right_click", List.of("fly"), Map.of(), 0);
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				id, "BEH3", "", "minecraft:stone",
				BlockDefinition.Category.INTERACTIVE, 2.0, List.of(beh), 200, null, 0));
		placeBlock(world, pos, id);

		var player = ctx.createMockCreativeServerPlayerInWorld();
		var be = (DynamicBlockEntity) world.getBlockEntity(pos);
		var def = BlockRuntimeManager.get(id);

		var r = BlockBehaviorEngine.onUse(world, pos, player, def, be);
		ctx.assertTrue(r == ActionResult.PASS, "unsupported 'fly' must not succeed: " + r);
		ctx.assertTrue(be.lastUseTick() == 0, "unsupported action must not update lastUseTick");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	/**
	 * BEH4: delete definition → behavior immediately stops (no stale behavior).
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-beh-beh4")
	public void testBEH4_deleteStopsBehavior(TestContext ctx) {
		setup();
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(2, 2, 2));
		String id = "worldforge:beh4_block";

		var beh = new BehaviorDefinition("right_click", List.of("heal"), Map.of("amount", 4), 0);
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				id, "BEH4", "", "minecraft:furnace",
				BlockDefinition.Category.INTERACTIVE, 2.0, List.of(beh), 200, null, 0));
		placeBlock(world, pos, id);

		var player = ctx.createMockCreativeServerPlayerInWorld();
		var be = (DynamicBlockEntity) world.getBlockEntity(pos);

		// before delete: behavior works
		var def = BlockRuntimeManager.get(id);
		var r1 = BlockBehaviorEngine.onUse(world, pos, player, def, be);
		ctx.assertTrue(r1 == ActionResult.SUCCESS, "behavior must work before delete: " + r1);

		// delete
		WorldForgeBlockTools.deleteBlock(server, id);
		ctx.assertTrue(BlockRuntimeManager.get(id) == null, "definition must be deleted");

		// after delete: behavior is inert (def is null)
		var def2 = BlockRuntimeManager.get(id);
		var r2 = BlockBehaviorEngine.onUse(world, pos, player, def2, be);
		ctx.assertTrue(r2 == ActionResult.PASS, "behavior must stop after delete: " + r2);

		BlockRuntimeManager.release();
		ctx.complete();
	}
}
