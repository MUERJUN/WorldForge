package dev.worldforge.test;

import dev.worldforge.block.BlockCatalog;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.component.VirtualBlockId;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.registry.WorldForgeBlocks;
import dev.worldforge.tool.WorldForgeBlockTools;
import net.minecraft.item.ItemStack;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * v0.4.0: the unified block tool layer (validation, persistence, rollback,
 * three-phase results, permission-safe give).
 */
public class WorldForgeBlockToolTests {

	private static Path tempDir(String prefix) {
		return TestTempDirs.create(prefix);
	}

	private static BlockDefinition def(String id, String appearance, BlockDefinition.Category cat) {
		return BlockDefinition.create(id, "工具测试方块", "", appearance, cat, 2.0, List.of(), 200, null, 0);
	}

	// ---- T1: validation rejects bad input instead of storing it ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-tools")
	public void testT1_validationRejectsBadInput(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-t1")));
		var server = ctx.getWorld().getServer();

		// unsupported appearance (the BlockCatalog strict check must catch this)
		var r1 = WorldForgeBlockTools.createBlock(server,
				def("worldforge:bad_look", "minecraft:diamond_block", BlockDefinition.Category.DECORATION));
		ctx.assertTrue(!r1.ok(), "an appearance outside the catalog must be rejected");
		ctx.assertTrue(r1.message().contains("外观"), r1.message());

		// illegal id
		var r2 = WorldForgeBlockTools.createBlock(server,
				def("BAD ID", "stone", BlockDefinition.Category.DECORATION));
		ctx.assertTrue(!r2.ok(), "an illegal id must be rejected");

		// periodic interval below 1s (anti-farming guard)
		var r3 = WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				"worldforge:too_fast", "太快", "", "stone", BlockDefinition.Category.PERIODIC,
				2.0, List.of(), 1, "minecraft:iron_ingot", 1));
		ctx.assertTrue(!r3.ok(), "a periodic interval under 20 ticks must be rejected");
		ctx.assertTrue(r3.message().contains("20"), r3.message());

		// none of the rejected definitions may be stored
		ctx.assertTrue(BlockRuntimeManager.list().isEmpty(),
				"rejected definitions must not be persisted, got " + BlockRuntimeManager.list().size());

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- T2: create persists, duplicates are refused, delete leaves a tombstone ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-tools")
	public void testT2_createPersistDelete(TestContext ctx) {
		Path dir = tempDir("wf-t2");
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(dir));
		var server = ctx.getWorld().getServer();

		var ok = WorldForgeBlockTools.createBlock(server,
				def("worldforge:tool_block", "furnace", BlockDefinition.Category.DECORATION));
		ctx.assertTrue(ok.ok(), "a valid definition must be created: " + ok.message());
		ctx.assertTrue(ok.persisted(), "the result must report persistence separately");
		ctx.assertTrue(Files.exists(dir.resolve("blocks.json")), "the definition must be on disk");
		ctx.assertTrue(ok.sync() != WorldForgeBlockTools.SyncStatus.FAILED,
				"sync must be reported, not silently skipped");

		var dup = WorldForgeBlockTools.createBlock(server,
				def("worldforge:tool_block", "furnace", BlockDefinition.Category.DECORATION));
		ctx.assertTrue(!dup.ok(), "a duplicate id must be refused");
		ctx.assertTrue(BlockRuntimeManager.list().size() == 1, "no duplicate may be stored");

		var del = WorldForgeBlockTools.deleteBlock(server, "worldforge:tool_block");
		ctx.assertTrue(del.ok(), "delete must succeed: " + del.message());
		ctx.assertTrue(BlockRuntimeManager.tombstones().contains("worldforge:tool_block"),
				"delete must leave a tombstone so placed blocks report the失效 state");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- T3: give produces a REAL block item carrying the virtual identity ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-tools")
	public void testT3_giveProducesIdentifiedBlockItem(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-t3")));
		var server = ctx.getWorld().getServer();
		WorldForgeBlockTools.createBlock(server,
				def("worldforge:give_me", "emerald_block", BlockDefinition.Category.DECORATION));

		var player = ctx.createMockCreativeServerPlayerInWorld();
		var r = WorldForgeBlockTools.give(player, "worldforge:give_me", 3);
		ctx.assertTrue(r.ok(), "give must succeed: " + r.message());

		ItemStack found = null;
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack st = player.getInventory().getStack(i);
			if (st.getItem() == WorldForgeBlocks.DYNAMIC_BLOCK_ITEM) {
				found = st;
				break;
			}
		}
		ctx.assertTrue(found != null, "the inventory must hold a real dynamic block item");
		ctx.assertTrue(found.getCount() == 3, "the requested count must be honoured");
		var v = found.get(WorldForgeComponents.VIRTUAL_BLOCK_ID);
		ctx.assertTrue(v instanceof VirtualBlockId vb && vb.id().equals("worldforge:give_me"),
				"the given item must carry the virtual identity");

		// giving an unknown id must fail with guidance, not silently
		var bad = WorldForgeBlockTools.give(player, "worldforge:nope", 1);
		ctx.assertTrue(!bad.ok() && bad.message().contains("不存在"), bad.message());

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- T4: a failed persist rolls back definitions AND tombstones ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-tools")
	public void testT4_failedPersistRollsBack(TestContext ctx) {
		Path dir = tempDir("wf-t4");
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(dir));
		var server = ctx.getWorld().getServer();

		ctx.assertTrue(WorldForgeBlockTools.createBlock(server,
				def("worldforge:rb", "stone", BlockDefinition.Category.DECORATION)).ok(), "initial create must succeed");
		ctx.assertTrue(WorldForgeBlockTools.deleteBlock(server, "worldforge:rb").ok(), "delete must succeed");
		ctx.assertTrue(BlockRuntimeManager.tombstones().contains("worldforge:rb"), "tombstone expected");

		// sabotage the store, then try to re-create
		try {
			Files.delete(dir.resolve("blocks.json"));
			Files.createDirectory(dir.resolve("blocks.json"));
			Files.writeString(dir.resolve("blocks.json").resolve("occupied"), "x");
		} catch (Exception e) {
			ctx.assertTrue(false, "io: " + e);
		}

		var r = WorldForgeBlockTools.createBlock(server,
				def("worldforge:rb", "stone", BlockDefinition.Category.DECORATION));
		ctx.assertTrue(!r.ok(), "an unpersistable create must fail");
		ctx.assertTrue(!r.persisted(), "the result must NOT claim persistence");
		ctx.assertTrue(BlockRuntimeManager.get("worldforge:rb") == null,
				"the rolled-back definition must not be live");
		ctx.assertTrue(BlockRuntimeManager.tombstones().contains("worldforge:rb"),
				"the tombstone must be restored by the rollback");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- T5: unbound store is an error, never a fake success ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-tools")
	public void testT5_unboundStoreFails(TestContext ctx) {
		BlockRuntimeManager.release();
		var r = WorldForgeBlockTools.createBlock(ctx.getWorld().getServer(),
				def("worldforge:nostore", "stone", BlockDefinition.Category.DECORATION));
		ctx.assertTrue(!r.ok() && !r.persisted(),
				"without a bound store nothing may be reported as persisted");
		ctx.assertTrue(!BlockRuntimeManager.isBound(), "the manager must report itself unbound");

		BlockRuntimeManager.release();
		ctx.complete();
	}

	// ---- T6: the catalog strict check used by validation ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-block-tools")
	public void testT6_catalogStrictness(TestContext ctx) {
		ctx.assertTrue(BlockCatalog.isSupported("furnace"), "a catalog key must be supported");
		ctx.assertTrue(BlockCatalog.isSupported("minecraft:furnace"), "the full id must be supported");
		ctx.assertTrue(!BlockCatalog.isSupported("minecraft:diamond_block"),
				"an unlisted vanilla block must NOT be accepted");
		ctx.assertTrue(!BlockCatalog.isSupported("not_a_block"), "garbage must be rejected");
		ctx.assertTrue(BlockCatalog.normalize("garbage").equals("minecraft:stone"),
				"display fallback still yields a usable appearance");
		BlockRuntimeManager.release();
		ctx.complete();
	}
}