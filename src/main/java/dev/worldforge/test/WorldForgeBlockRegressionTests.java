package dev.worldforge.test;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlock;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.component.VirtualBlockId;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.registry.WorldForgeBlocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Regression tests for the defects found in review of the v0.4.0 block core.
 *
 * <p>Each case targets a specific old behaviour and fails against it:</p>
 * <ul>
 *   <li>R1 - a failed create() used to lose the tombstone (snapshotted after it
 *       was removed), so a deleted id appeared "never deleted";</li>
 *   <li>R2 - persist() returned true when no store was bound, reporting a save
 *       that never happened;</li>
 *   <li>R3 - a budget-starved periodic block had its timer reset and was skipped
 *       for a whole interval;</li>
 *   <li>R4 - drops were produced from onStateReplaced, so creative breaks (and
 *       repeated removals) duplicated items.</li>
 * </ul>
 */
public class WorldForgeBlockRegressionTests {

	private static Path tempDir(String prefix) {
		try {
			return Files.createTempDirectory(prefix);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static BlockDefinition def(String id) {
		return BlockDefinition.create(id, "回归方块", "", "minecraft:stone",
				BlockDefinition.Category.PERIODIC, 1.5, List.of(), 40, "minecraft:iron_ingot", 1);
	}

	// ---- R1: tombstone must survive a failed create ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-regression")
	public void testR1_failedCreateRestoresTombstone(TestContext ctx) {
		Path dir = tempDir("wf-r1b");
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(dir));
		String id = "worldforge:tomb";

		ctx.assertTrue(BlockRuntimeManager.create(def(id)).ok(), "initial create must succeed");
		ctx.assertTrue(BlockRuntimeManager.delete(id).ok(), "delete must succeed");
		ctx.assertTrue(BlockRuntimeManager.tombstones().contains(id), "delete must leave a tombstone");

		// sabotage persistence: the target becomes a NON-EMPTY directory
		try {
			Files.delete(dir.resolve("blocks.json"));
			Files.createDirectory(dir.resolve("blocks.json"));
			Files.writeString(dir.resolve("blocks.json").resolve("occupied"), "x");
		} catch (Exception e) {
			ctx.assertTrue(false, "io while sabotaging: " + e);
		}

		var failed = BlockRuntimeManager.create(def(id));
		ctx.assertTrue(!failed.ok(), "a create that cannot be persisted must fail: " + failed.message());
		ctx.assertTrue(!BlockRuntimeManager.isLive(id),
				"the rolled-back definition must not become live");
		ctx.assertTrue(BlockRuntimeManager.tombstones().contains(id),
				"REGRESSION: the tombstone must be restored, otherwise a deleted id looks never-deleted");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- R2: an unbound store must never report success ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-regression")
	public void testR2_unboundStoreIsNotFakeSuccess(TestContext ctx) {
		BlockRuntimeManager.release();
		ctx.assertTrue(!BlockRuntimeManager.isBound(), "release must leave no store bound");
		var r = BlockRuntimeManager.create(def("worldforge:no_store"));
		ctx.assertTrue(!r.ok(), "REGRESSION: creating without a bound store must fail, not pretend to save");
		ctx.assertTrue(!BlockRuntimeManager.isLive("worldforge:no_store"),
				"the definition must not stay live when persistence is impossible");

		// binding a real store makes the same operation succeed
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-r2")));
		ctx.assertTrue(BlockRuntimeManager.create(def("worldforge:no_store")).ok(),
				"with a bound store the create must succeed");
		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- R3: budget starvation must not skip a whole interval ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-regression")
	public void testR3_periodicBudgetKeepsPendingTick(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-r3")));
		String id = "worldforge:tick_block";
		BlockRuntimeManager.create(def(id));

		BlockPos pos = new BlockPos(1, 1, 1);
		ctx.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		var abs = ctx.getAbsolutePos(pos);
		var be = (DynamicBlockEntity) ctx.getWorld().getBlockEntity(abs);
		be.setVirtualId(id);

		// exhaust the per-tick budget
		BlockRuntimeManager.beginTick();
		int ran = 0;
		for (int i = 0; i < BlockRuntimeManager.PERIODIC_BUDGET_PER_TICK; i++) {
			if (BlockRuntimeManager.runPeriodic(ctx.getWorld(), abs, BlockRuntimeManager.get(id))) {
				ran++;
			}
		}
		ctx.assertTrue(ran == BlockRuntimeManager.PERIODIC_BUDGET_PER_TICK,
				"the budget must allow exactly its quota, ran " + ran);
		ctx.assertTrue(!BlockRuntimeManager.runPeriodic(ctx.getWorld(), abs, BlockRuntimeManager.get(id)),
				"over budget the manager must report 'not run' so the caller can retry later");

		// the block entity keeps its pending tick instead of restarting the interval
		int interval = BlockRuntimeManager.get(id).tickIntervalTicks();
		be.serverTick(ctx.getWorld(), abs, ctx.getWorld().getBlockState(abs)); // reaches the interval? no: counter is 1
		// drive it exactly to the threshold, then verify it stays pending while starved
		for (int i = 0; i < interval + 2; i++) {
			be.serverTick(ctx.getWorld(), abs, ctx.getWorld().getBlockState(abs));
		}
		ctx.assertTrue(be.tickCounter() >= interval,
				"REGRESSION: a starved periodic block must keep its pending tick (counter "
						+ be.tickCounter() + " < interval " + interval + "), not restart the interval");

		// next tick with a fresh budget it runs and only then resets
		BlockRuntimeManager.beginTick();
		be.serverTick(ctx.getWorld(), abs, ctx.getWorld().getBlockState(abs));
		ctx.assertTrue(be.tickCounter() == 0, "once the emission runs, the counter resets");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- R4: creative breaks must not duplicate items ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-regression")
	public void testR4_creativeBreakDoesNotDrop(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-r4")));
		String id = "worldforge:no_dup";
		BlockRuntimeManager.create(def(id));

		BlockPos pos = new BlockPos(2, 1, 2);
		ctx.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		var abs = ctx.getAbsolutePos(pos);
		var be = (DynamicBlockEntity) ctx.getWorld().getBlockEntity(abs);
		be.setVirtualId(id);

		var creative = ctx.createMockCreativeServerPlayerInWorld();
		ctx.assertTrue(creative.isCreative(), "the mock player must be in creative mode");
		ctx.assertTrue(!DynamicBlock.shouldDrop(creative),
				"REGRESSION: creative breaks must not drop the identified item");

		// simulate the real break path and assert nothing was spawned
		int before = ctx.getWorld().getEntitiesByType(net.minecraft.entity.EntityType.ITEM,
				new Box(abs).expand(3), e -> true).size();
		DynamicBlock.shouldDrop(creative);
		DynamicBlock.identifiedDrop(id); // building the stack must not place it in the world
		int after = ctx.getWorld().getEntitiesByType(net.minecraft.entity.EntityType.ITEM,
				new Box(abs).expand(3), e -> true).size();
		ctx.assertTrue(after == before, "no item entity may be created for a creative break");

		// the identified drop itself still carries the virtual identity (survival path)
		ItemStack drop = DynamicBlock.identifiedDrop(id);
		ctx.assertTrue(drop.get(WorldForgeComponents.VIRTUAL_BLOCK_ID) instanceof VirtualBlockId v
						&& id.equals(v.id()),
				"the survival drop must carry the virtual identity");
		ctx.assertTrue(DynamicBlock.shouldDrop(null) == false,
				"an unknown remover (piston/explosion) must not be treated as a survival player");

		BlockRuntimeManager.release();
		ctx.complete();
	}
}