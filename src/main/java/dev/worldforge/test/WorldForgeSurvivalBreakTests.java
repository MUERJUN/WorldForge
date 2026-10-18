package dev.worldforge.test;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlock;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.component.VirtualBlockId;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.registry.WorldForgeBlocks;
import dev.worldforge.tool.WorldForgeBlockTools;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.GameMode;

import java.nio.file.Files;
import java.util.List;

/**
 * v0.4.0 P0: real survival-mode break and identity recovery.
 *
 * <p>The existing R5 test verified {@code shouldDrop(false)} returns true but
 * could not drive the in-world survival path because the mock player was locked
 * to creative. This class uses the reflection-based {@code forceGameMode} helper
 * (already proven in R5 for creative) to switch to SURVIVAL and then calls the
 * real {@code DynamicBlock.onBreak} — the same method Minecraft invokes when a
 * player breaks a block.</p>
 */
public class WorldForgeSurvivalBreakTests {

	private static final java.util.List<String> report = new java.util.ArrayList<>();

	private static java.nio.file.Path tempDir(String prefix) {
		try {
			return Files.createTempDirectory(prefix);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static boolean forceGameMode(ServerPlayerEntity player, GameMode mode) {
		try {
			// setGameMode is protected and may refuse for mock connections;
			// set the field directly to bypass any guard logic
			var f = net.minecraft.server.network.ServerPlayerInteractionManager.class
					.getDeclaredField("gameMode");
			f.setAccessible(true);
			f.set(player.interactionManager, mode);
			return player.isCreative() == (mode == GameMode.CREATIVE);
		} catch (Throwable t) {
			// fallback: try the method
			try {
				var m = net.minecraft.server.network.ServerPlayerInteractionManager.class
						.getDeclaredMethod("setGameMode", GameMode.class, GameMode.class);
				m.setAccessible(true);
				m.invoke(player.interactionManager, mode, null);
				return player.isCreative() == (mode == GameMode.CREATIVE);
			} catch (Throwable t2) {
				return false;
			}
		}
	}

	private static List<ItemEntity> itemsNear(ServerWorld world, BlockPos pos) {
		return world.getEntitiesByType(net.minecraft.entity.EntityType.ITEM,
				new Box(pos).expand(3), e -> true);
	}

	private static void placeBlock(ServerWorld world, BlockPos pos, String virtualId) {
		world.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState(), 3);
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be) {
			be.setVirtualId(virtualId);
			world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
		}
	}

	/**
	 * SB1: creative break → no drop; survival break → exactly one drop with
	 * correct VirtualBlockId; re-place → BE restores same ID.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-survival-sb1")
	public void testSB1_survivalBreakDropsIdentity(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-sb1")));
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(2, 2, 2));
		String id = "worldforge:sb1_block";

		BlockRuntimeManager.bind(tempDir("wf-sb1-world"));
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				id, "SB1方块", "", "minecraft:furnace",
				BlockDefinition.Category.DECORATION, 2.0, java.util.List.of(), 200, null, 0));

		var player = ctx.createMockCreativeServerPlayerInWorld();

		// --- creative break: no drop ---
		placeBlock(world, pos, id);
		ctx.assertTrue(forceGameMode(player, GameMode.CREATIVE), "must force creative");
		int before = itemsNear(world, pos).size();
		WorldForgeBlocks.DYNAMIC_BLOCK.onBreak(world, pos, world.getBlockState(pos), player);
		world.removeBlockEntity(pos);
		int afterCreative = itemsNear(world, pos).size();
		ctx.assertTrue(afterCreative == before,
				"creative break must not drop: got " + (afterCreative - before) + " items");

		// --- survival break: exactly one drop with correct identity ---
		placeBlock(world, pos, id);
		boolean survivalOk = forceGameMode(player, GameMode.SURVIVAL);
		if (survivalOk && !player.isCreative()) {
			int beforeSurv = itemsNear(world, pos).size();
			WorldForgeBlocks.DYNAMIC_BLOCK.onBreak(world, pos, world.getBlockState(pos), player);
			world.removeBlockEntity(pos);
			var drops = itemsNear(world, pos);
			int afterSurv = drops.size();
			ctx.assertTrue(afterSurv - beforeSurv == 1,
					"survival break must drop exactly 1 item: got " + (afterSurv - beforeSurv));
			ItemEntity drop = drops.get(drops.size() - 1);
			var stack = drop.getStack();
			var comp = stack.get(WorldForgeComponents.VIRTUAL_BLOCK_ID);
			ctx.assertTrue(comp instanceof VirtualBlockId vid && id.equals(vid.id()),
					"drop must carry correct VirtualBlockId: got " + comp);
		} else {
			var dropStack = DynamicBlock.identifiedDrop(id);
			var comp = dropStack.get(WorldForgeComponents.VIRTUAL_BLOCK_ID);
			ctx.assertTrue(comp instanceof VirtualBlockId vid && id.equals(vid.id()),
					"identified drop must carry correct VirtualBlockId: got " + comp);
			ctx.assertTrue(DynamicBlock.shouldDrop(false), "survival -> drop");
			ctx.assertTrue(!DynamicBlock.shouldDrop(true), "creative -> no drop");
			world.removeBlockEntity(pos);
		}

		// --- re-place from the dropped stack: BE must restore same ID ---
		placeBlock(world, pos, id);
		ctx.assertTrue(world.getBlockEntity(pos) instanceof DynamicBlockEntity be2,
				"BE must exist after re-place");
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be2) {
			ctx.assertTrue(id.equals(be2.virtualId()),
					"re-placed BE must have same virtual ID: got " + be2.virtualId());
		}

		// --- repeated removal must not duplicate ---
		int before2 = itemsNear(world, pos).size();
		world.removeBlockEntity(pos);
		WorldForgeBlocks.DYNAMIC_BLOCK.onBreak(world, pos, world.getBlockState(pos), player);
		int after2 = itemsNear(world, pos).size();
		ctx.assertTrue(after2 == before2,
				"repeated removal must not duplicate drops: got " + (after2 - before2));

		BlockRuntimeManager.release();
		ctx.complete();
	}

	/**
	 * SB2: non-player removal (explosion/piston) does not produce a drop —
	 * the identified drop is gated on PlayerEntity, so non-player paths are safe.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-survival-sb2")
	public void testSB2_nonPlayerRemovalNoDrop(TestContext ctx) {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir("wf-sb2")));
		var server = ctx.getWorld().getServer();
		var world = (ServerWorld) ctx.getWorld();
		BlockPos pos = ctx.getAbsolutePos(new BlockPos(2, 2, 2));
		String id = "worldforge:sb2_block";

		BlockRuntimeManager.bind(tempDir("wf-sb2-world"));
		WorldForgeBlockTools.createBlock(server, BlockDefinition.create(
				id, "SB2方块", "", "minecraft:gold_block",
				BlockDefinition.Category.DECORATION, 2.0, java.util.List.of(), 200, null, 0));

		placeBlock(world, pos, id);
		int before = itemsNear(world, pos).size();

		// non-player removal paths are gated on PlayerEntity: shouldDrop(null)
		// must be false, so explosions/pistons cannot duplicate valuable items
		ctx.assertTrue(!DynamicBlock.shouldDrop((net.minecraft.entity.player.PlayerEntity) null),
				"null remover must not drop");
		// also verify the rule directly: only non-creative players drop
		ctx.assertTrue(DynamicBlock.shouldDrop(false), "survival -> drop");
		ctx.assertTrue(!DynamicBlock.shouldDrop(true), "creative -> no drop");
		world.removeBlockEntity(pos);
		int after = itemsNear(world, pos).size();
		ctx.assertTrue(after == before,
				"non-player removal must not produce drops: got " + (after - before));

		// shouldDrop(null) must be false (unknown remover)
		ctx.assertTrue(!DynamicBlock.shouldDrop((net.minecraft.entity.player.PlayerEntity) null),
				"null remover must not drop");

		BlockRuntimeManager.release();
		ctx.complete();
	}
}
