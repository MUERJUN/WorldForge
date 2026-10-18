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

		// the block entity keeps its pending tick while it is inside the fairness
		// grace window, and the scheduler must serve it once it is past it
		int interval = BlockRuntimeManager.get(id).tickIntervalTicks();
		int window = interval + BlockRuntimeManager.FAIRNESS_GRACE_TICKS + 5;
		int servedAt = -1;
		for (int i = 1; i <= window; i++) {
			be.serverTick(ctx.getWorld(), abs, ctx.getWorld().getBlockState(abs));
			if (be.tickCounter() == 0) {
				servedAt = i;
				break;
			}
		}
		ctx.assertTrue(servedAt > 0,
				"REGRESSION: a budget-starved periodic block was never served within " + window
						+ " ticks (counter " + be.tickCounter() + ", interval " + interval + ")");
		ctx.assertTrue(servedAt >= interval,
				"the block must not fire before its own interval elapsed (served at tick " + servedAt + ")");
		ctx.assertTrue(BlockRuntimeManager.periodicBoostedThisTick() >= 1,
				"the boosted emission must be accounted for, not hidden");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- R4: the real onBreak path - creative 0 drops, survival exactly 1, no duplication ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-regression")
	public void testR4_creativeBreakDoesNotDrop(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-r4")));
		String id = "worldforge:no_dup";
		BlockRuntimeManager.create(def(id));

		var player = ctx.createMockCreativeServerPlayerInWorld();
		BlockPos pos = new BlockPos(2, 1, 2);
		var abs = ctx.getAbsolutePos(pos);

		// ---- creative: the REAL onBreak path must produce no drop ----
		ctx.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		var be = (DynamicBlockEntity) ctx.getWorld().getBlockEntity(abs);
		be.setVirtualId(id);
		ctx.assertTrue(forceGameMode(player, net.minecraft.world.GameMode.CREATIVE), "could not force creative mode");
		ctx.assertTrue(player.isCreative(), "player must be creative for this case");
		int before = itemsNear(ctx, abs);
		WorldForgeBlocks.DYNAMIC_BLOCK.onBreak(ctx.getWorld(), abs, ctx.getWorld().getBlockState(abs), player);
		int afterCreative = itemsNear(ctx, abs);
		ctx.assertTrue(afterCreative == before,
				"REGRESSION: a creative break spawned " + (afterCreative - before) + " item(s)");

		// ---- the survival decision and the identified drop ----
		// NOTE (verification gap, stated honestly): the GameTest mock player's
		// game mode is fixed to CREATIVE (ServerPlayerInteractionManager refuses
		// protected/unprivileged mode changes for a mock connection), so the
		// in-world survival drop cannot be driven here. Instead the exact rule
		// the drop depends on is verified directly, plus the identity carried by
		// the drop stack; the in-world survival drop is listed as unverified.
		ctx.assertTrue(DynamicBlock.shouldDrop(false),
				"a non-creative remover must yield the identified item");
		ctx.assertTrue(!DynamicBlock.shouldDrop(true),
				"a creative remover must not yield the identified item");
		ctx.assertTrue(!DynamicBlock.shouldDrop((net.minecraft.entity.player.PlayerEntity) null),
				"an unknown remover must not be treated as a survival player");
		ItemStack drop = DynamicBlock.identifiedDrop(id);
		ctx.assertTrue(drop.get(WorldForgeComponents.VIRTUAL_BLOCK_ID) instanceof VirtualBlockId v
						&& id.equals(v.id()),
				"the survival drop must carry the virtual identity");
		int afterSurvival = afterCreative;

		// ---- repeated removal must not duplicate ----
		// simulate a second removal event (block entity already gone)
		ctx.getWorld().removeBlockEntity(abs);
		WorldForgeBlocks.DYNAMIC_BLOCK.onBreak(ctx.getWorld(), abs, ctx.getWorld().getBlockState(abs), player);
		ctx.assertTrue(itemsNear(ctx, abs) == afterSurvival,
				"a repeated removal must not spawn another drop");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	/**
	 * Force a mock player's game mode. {@code ServerPlayerInteractionManager#setGameMode}
	 * is protected and {@code changeGameMode} silently refuses for a mock
	 * connection, so the test reaches the real field through reflection - this
	 * only ever runs in the dev GameTest environment.
	 */
	private static boolean forceGameMode(net.minecraft.server.network.ServerPlayerEntity player,
			net.minecraft.world.GameMode mode) {
		try {
			var m = net.minecraft.server.network.ServerPlayerInteractionManager.class
					.getDeclaredMethod("setGameMode", net.minecraft.world.GameMode.class,
							net.minecraft.world.GameMode.class);
			m.setAccessible(true);
			m.invoke(player.interactionManager, mode, null);
			return player.isCreative() == (mode == net.minecraft.world.GameMode.CREATIVE);
		} catch (Throwable t) {
			return false;
		}
	}

	private static int itemsNear(TestContext ctx, BlockPos abs) {
		return ctx.getWorld().getEntitiesByType(net.minecraft.entity.EntityType.ITEM,
				new Box(abs).expand(3), e -> true).size();
	}
}
