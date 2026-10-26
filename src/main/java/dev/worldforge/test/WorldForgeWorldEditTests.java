package dev.worldforge.test;

import dev.worldforge.ai.AgentBridge;
import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.AgentOrchestrator;
import dev.worldforge.ai.ChatTurn;
import dev.worldforge.ai.MockToolProvider;
import dev.worldforge.ai.SuiteReservedRoutes;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.ops.ToolEvent;
import dev.worldforge.ops.ToolEventStatus;
import dev.worldforge.ops.ToolTaskHandle;
import dev.worldforge.ops.worldedit.BlockTargetRules;
import dev.worldforge.ops.worldedit.WorldEditEntry;
import dev.worldforge.ops.worldedit.WorldEditExecutor;
import dev.worldforge.ops.worldedit.WorldEditHistory;
import dev.worldforge.ops.worldedit.WorldEditPlan;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v0.8.0 — the world edit vertical slice, verified on the real server world.
 *
 * <p>CRITICAL coordinate convention: blocks are BUILT with the relative
 * {@code ctx.setBlockState} (inside the loaded structure area), and the plan
 * uses the SAME cells translated to world coordinates via
 * {@code ctx.getAbsolutePos} — the executor validates and writes the world at
 * exactly those cells. Writing through the world at far-away absolute
 * coordinates would force a synchronous chunk load on the server thread and
 * deadlock the whole run.</p>
 */
public class WorldForgeWorldEditTests {

	private static final String SINGLE_MSG = "wfedit: replace the targeted stone with glass (single)";
	private static final String UNDO_MSG = "wfedit: undo my last world edit";

	static {
		SuiteReservedRoutes.registerExact(SINGLE_MSG,
				() -> calls("edit_world_blocks", "{\"block\":\"minecraft:glass\"}"));
		SuiteReservedRoutes.registerExact(UNDO_MSG,
				() -> calls("undo_world_edit", "{}"));
	}

	private static ChatTurn.Turn.Calls calls(String tool, String argsJson) {
		return new ChatTurn.Turn.Calls(List.of(
				new ChatTurn.ModelToolCall("wf_" + System.nanoTime(), tool, argsJson)));
	}

	private static AgentConfig cfg() {
		return new AgentConfig(true, "http://mock", "mock", "sk-test", 60, 500, 0, 100, 12, 300);
	}

	/**
	 * The absolute position for a RELATIVE template cell: relative writes and
	 * the plan's coordinates describe the SAME cell.
	 */
	private static BlockPos abs(TestContext ctx, BlockPos rel) {
		return ctx.getAbsolutePos(rel);
	}

	private static WorldEditPlan oneBlock(UUID player, BlockPos absPos, Instant now) {
		return WorldEditPlan.of("we-" + Math.abs(absPos.hashCode()), player,
				"minecraft:overworld",
				List.of(absPos),
				Map.of(absPos, WorldEditEntry.of("minecraft:stone")),
				Map.of(absPos, WorldEditEntry.of("minecraft:glass")),
				now, now.plusSeconds(600));
	}

	private static WorldEditPlan regionPlan(UUID player, BlockPos absOrigin,
			int width, int height, Instant now) {
		List<BlockPos> positions = new ArrayList<>();
		Map<BlockPos, WorldEditEntry> originals = new LinkedHashMap<>();
		Map<BlockPos, WorldEditEntry> targets = new LinkedHashMap<>();
		for (int w = 0; w < width; w++) {
			for (int h = 0; h < height; h++) {
				BlockPos pos = absOrigin.add(w, h, 0);
				positions.add(pos);
				originals.put(pos, WorldEditEntry.of("minecraft:stone"));
				targets.put(pos, WorldEditEntry.of("minecraft:glass"));
			}
		}
		return WorldEditPlan.of("we-region" + absOrigin, player, "minecraft:overworld",
				positions, originals, targets, now, now.plusSeconds(600));
	}

	private static final BlockPos BASE_REL = new BlockPos(3, 1, 3);

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void singleBlockReplaceSucceeds(TestContext ctx) {
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		BlockPos abs = abs(ctx, BASE_REL);
		var plan = oneBlock(UUID.randomUUID(), abs, Instant.now());
		var outcome = WorldEditExecutor.execute(ctx.getWorld(), plan, null);
		ctx.assertTrue(outcome.fullyApplied(), "expected full apply: " + outcome);
		ctx.assertEquals(1, outcome.applied(), "one block must be applied");
		ctx.assertTrue(ctx.getWorld().getBlockState(abs).getBlock() == Blocks.GLASS,
				"the world must now hold glass");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void regionPlanChangesExactlyFifteenTargets(TestContext ctx) {
		for (int w = 0; w < 5; w++) {
			for (int h = 0; h < 3; h++) {
				ctx.setBlockState(BASE_REL.add(w, h, 0), Blocks.STONE);
			}
		}
		BlockPos absOrigin = abs(ctx, BASE_REL);
		var plan = regionPlan(UUID.randomUUID(), absOrigin, 5, 3, Instant.now());
		ctx.assertEquals(15, plan.affectedCount(), "the region plan covers 15 positions");
		var outcome = WorldEditExecutor.execute(ctx.getWorld(), plan, null);
		ctx.assertTrue(outcome.fullyApplied(), "expected full apply: " + outcome);
		ctx.assertEquals(15, outcome.applied(), "exactly 15 blocks must be applied");
		for (int w = 0; w < 5; w++) {
			for (int h = 0; h < 3; h++) {
				ctx.assertTrue(ctx.getWorld().getBlockState(absOrigin.add(w, h, 0)).getBlock()
								== Blocks.GLASS,
						"every wall position must be glass at " + absOrigin.add(w, h, 0));
			}
		}
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void theWorldIsUntouchedBeforeConfirmation(TestContext ctx) {
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		BlockPos abs = abs(ctx, BASE_REL);
		oneBlock(UUID.randomUUID(), abs, Instant.now());
		ctx.assertTrue(ctx.getWorld().getBlockState(abs).getBlock() == Blocks.STONE,
				"building a plan must never touch the world");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void targetChangedDuringConfirmationRefusesWithZeroWrites(TestContext ctx) {
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		BlockPos abs = abs(ctx, BASE_REL);
		var plan = oneBlock(UUID.randomUUID(), abs, Instant.now());
		ctx.setBlockState(BASE_REL, Blocks.COBBLESTONE);
		var outcome = WorldEditExecutor.execute(ctx.getWorld(), plan, null);
		ctx.assertTrue(!outcome.fullyApplied() && outcome.applied() == 0,
				"the drifted plan must refuse with zero writes: " + outcome);
		ctx.assertTrue(outcome.failureReason().contains("目标状态已变化"),
				"the reason must say the target drifted: " + outcome.failureReason());
		ctx.assertTrue(ctx.getWorld().getBlockState(abs).getBlock() == Blocks.COBBLESTONE,
				"the drifted state must be untouched");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void aForbiddenTargetFailsTheWholePlan(TestContext ctx) {
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		ctx.setBlockState(BASE_REL.east(1), Blocks.STONE);
		ctx.setBlockState(BASE_REL.east(2), Blocks.COMMAND_BLOCK);
		BlockPos absOrigin = abs(ctx, BASE_REL);
		BlockPos forbiddenAbs = abs(ctx, BASE_REL.east(2));
		UUID player = UUID.randomUUID();
		List<BlockPos> positions = List.of(absOrigin, absOrigin.east(1), forbiddenAbs);
		Map<BlockPos, WorldEditEntry> originals = new LinkedHashMap<>();
		Map<BlockPos, WorldEditEntry> targets = new LinkedHashMap<>();
		for (BlockPos pos : positions) {
			originals.put(pos, BlockTargetRules.describe(ctx.getWorld().getBlockState(pos)));
			targets.put(pos, WorldEditEntry.of("minecraft:glass"));
		}
		WorldEditPlan plan = WorldEditPlan.of("we-forbidden", player, "minecraft:overworld",
				positions, originals, targets, Instant.now(), Instant.now().plusSeconds(600));
		var outcome = WorldEditExecutor.execute(ctx.getWorld(), plan, null);
		ctx.assertTrue(!outcome.fullyApplied() && outcome.applied() == 0,
				"the whole plan must fail before any write: " + outcome);
		ctx.assertTrue(ctx.getWorld().getBlockState(absOrigin).getBlock() == Blocks.STONE,
				"the legal positions must be untouched");
		ctx.assertTrue(ctx.getWorld().getBlockState(forbiddenAbs).getBlock() == Blocks.COMMAND_BLOCK,
				"the forbidden block untouched");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void aBlockEntityTargetIsRefusedAndUntouched(TestContext ctx) {
		ctx.setBlockState(BASE_REL, Blocks.CHEST);
		BlockPos abs = abs(ctx, BASE_REL);
		UUID player = UUID.randomUUID();
		WorldEditPlan plan = WorldEditPlan.of("we-chest", player, "minecraft:overworld",
				List.of(abs),
				Map.of(abs, WorldEditEntry.of("minecraft:chest")),
				Map.of(abs, WorldEditEntry.of("minecraft:glass")),
				Instant.now(), Instant.now().plusSeconds(600));
		var outcome = WorldEditExecutor.execute(ctx.getWorld(), plan, null);
		ctx.assertTrue(!outcome.fullyApplied() && outcome.applied() == 0,
				"a BlockEntity target must be refused: " + outcome);
		ctx.assertTrue(outcome.failureReason().contains("方块实体"),
				"the reason must name the block entity: " + outcome.failureReason());
		ctx.assertTrue(ctx.getWorld().getBlockState(abs).getBlock() == Blocks.CHEST,
				"the chest must be untouched");
		ctx.assertTrue(ctx.getWorld().getBlockEntity(abs) != null,
				"the chest block entity must survive");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void anUnloadedChunkIsNotForceLoaded(TestContext ctx) {
		BlockPos far = new BlockPos(abs(ctx, BASE_REL).getX() + 100_000, 64,
				abs(ctx, BASE_REL).getZ());
		ctx.assertTrue(!ctx.getWorld().isChunkLoaded(far),
				"precondition: the far chunk must not be loaded");
		UUID player = UUID.randomUUID();
		WorldEditPlan plan = WorldEditPlan.of("we-far", player, "minecraft:overworld",
				List.of(far),
				Map.of(far, WorldEditEntry.of("minecraft:stone")),
				Map.of(far, WorldEditEntry.of("minecraft:glass")),
				Instant.now(), Instant.now().plusSeconds(600));
		var outcome = WorldEditExecutor.execute(ctx.getWorld(), plan, null);
		ctx.assertTrue(!outcome.fullyApplied() && outcome.applied() == 0,
				"the far position must be refused: " + outcome);
		ctx.assertTrue(outcome.failureReason().contains("未加载"),
				"the reason must name the unloaded chunk: " + outcome.failureReason());
		ctx.assertTrue(!ctx.getWorld().isChunkLoaded(far),
				"the chunk must STILL not be loaded after the refusal");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void overTheBlockLimitIsRefused(TestContext ctx) {
		List<BlockPos> positions = new ArrayList<>();
		Map<BlockPos, WorldEditEntry> originals = new LinkedHashMap<>();
		Map<BlockPos, WorldEditEntry> targets = new LinkedHashMap<>();
		for (int i = 0; i < 257; i++) {
			BlockPos pos = new BlockPos(i % 16, 4, i / 16);
			positions.add(pos);
			originals.put(pos, WorldEditEntry.of("minecraft:stone"));
			targets.put(pos, WorldEditEntry.of("minecraft:glass"));
		}
		boolean threw = false;
		try {
			WorldEditPlan.of("we-huge", UUID.randomUUID(), "minecraft:overworld",
					positions, originals, targets, Instant.now(), Instant.now().plusSeconds(600));
		} catch (IllegalArgumentException ex) {
			threw = true;
		}
		ctx.assertTrue(threw, "a 257-block plan must be refused at construction");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit",
			tickLimit = 60_000)
	public void aCancelMidWayReportsTheRealNumbersAndRollsBack(TestContext ctx) {
		for (int w = 0; w < 16; w++) {
			for (int h = 0; h < 16; h++) {
				ctx.setBlockState(BASE_REL.add(w, h, 0), Blocks.STONE);
			}
		}
		BlockPos absOrigin = abs(ctx, BASE_REL);
		UUID player = UUID.randomUUID();
		List<BlockPos> positions = new ArrayList<>();
		Map<BlockPos, WorldEditEntry> originals = new LinkedHashMap<>();
		Map<BlockPos, WorldEditEntry> targets = new LinkedHashMap<>();
		for (int w = 0; w < 16; w++) {
			for (int h = 0; h < 16; h++) {
				BlockPos pos = absOrigin.add(w, h, 0);
				positions.add(pos);
				originals.put(pos, WorldEditEntry.of("minecraft:stone"));
				targets.put(pos, WorldEditEntry.of("minecraft:glass"));
			}
		}
		WorldEditPlan plan = WorldEditPlan.of("we-cancel", player, "minecraft:overworld",
				positions, originals, targets, Instant.now(), Instant.now().plusSeconds(600));

		final int stopAfter = 100;
		ToolTaskHandle cancel = new ToolTaskHandle() {
			private final AtomicInteger checks = new AtomicInteger();

			@Override
			public boolean isCancelled() {
				return checks.getAndIncrement() >= stopAfter;
			}

			@Override
			public void onCancel(Runnable action) {
			}

			@Override
			public String taskId() {
				return "we-cancel-test";
			}
		};

		var outcome = WorldEditExecutor.execute(ctx.getWorld(), plan, cancel);
		ctx.assertTrue(outcome.cancelled(), "the outcome must report the cancellation: " + outcome);
		ctx.assertEquals(stopAfter, outcome.applied(),
				"the applied count must be the real number written before the cancel");
		ctx.assertTrue(outcome.rolledBack() == stopAfter,
				"the rollback must have restored every written block: " + outcome);
		ctx.assertTrue(outcome.rollbackComplete(), "the rollback must be complete");
		for (int w = 0; w < 16; w++) {
			for (int h = 0; h < 16; h++) {
				ctx.assertTrue(ctx.getWorld().getBlockState(absOrigin.add(w, h, 0)).getBlock()
								== Blocks.STONE,
						"after the rollback the world must be exactly as before");
			}
		}
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void undoRestoresTheOriginalState(TestContext ctx) {
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		BlockPos abs = abs(ctx, BASE_REL);
		UUID player = UUID.randomUUID();
		var plan = oneBlock(player, abs, Instant.now());
		ctx.assertTrue(WorldEditExecutor.execute(ctx.getWorld(), plan, null).fullyApplied(),
				"the edit must apply first");
		var undo = WorldEditExecutor.undo(ctx.getWorld(), plan, null);
		ctx.assertTrue(undo.fullyApplied(), "the undo must apply: " + undo);
		ctx.assertTrue(ctx.getWorld().getBlockState(abs).getBlock() == Blocks.STONE,
				"the world must be back to stone");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void undoRefusesWhenTheTargetWasChangedBySomeoneElse(TestContext ctx) {
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		BlockPos abs = abs(ctx, BASE_REL);
		UUID player = UUID.randomUUID();
		var plan = oneBlock(player, abs, Instant.now());
		ctx.assertTrue(WorldEditExecutor.execute(ctx.getWorld(), plan, null).fullyApplied(),
				"the edit must apply first");
		ctx.setBlockState(BASE_REL, Blocks.DIAMOND_BLOCK);
		var undo = WorldEditExecutor.undo(ctx.getWorld(), plan, null);
		ctx.assertTrue(!undo.fullyApplied() && undo.applied() == 0,
				"the undo must refuse: " + undo);
		ctx.assertTrue(undo.failureReason().contains("无法安全撤销"),
				"the reason must say the undo is unsafe: " + undo.failureReason());
		ctx.assertTrue(ctx.getWorld().getBlockState(abs).getBlock() == Blocks.DIAMOND_BLOCK,
				"the other change must NOT be clobbered");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void historiesAreIsolatedPerPlayerAndWorld(TestContext ctx) {
		BlockPos abs = abs(ctx, BASE_REL);
		WorldEditHistory h = new WorldEditHistory(10);
		UUID alice = UUID.randomUUID();
		h.record(oneBlock(alice, abs, Instant.now()));
		ctx.assertTrue(h.latestUndoable(UUID.randomUUID(), "minecraft:overworld") == null,
				"another player must see nothing");
		ctx.assertTrue(h.latestUndoable(alice, "minecraft:the_nether") == null,
				"another world key must see nothing");
		var alicePlan = h.latestUndoable(alice, "minecraft:overworld");
		boolean refused = false;
		try {
			h.recordFor(alicePlan, UUID.randomUUID());
		} catch (IllegalArgumentException ex) {
			refused = true;
		}
		ctx.assertTrue(refused, "recording under a different player must be refused");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void worldInstanceEditingNeverBlursIntoDefinitionEditing(TestContext ctx) {
		var dir = TestTempDirs.create("wf-worldedit-13");
		BlockRuntimeManager.attachStoreForTesting(
				new dev.worldforge.block.BlockDefinitionStore(dir));
		BlockDefinition def = BlockDefinition.create("worldforge:we13", "WE13", "",
				"minecraft:stone", BlockDefinition.Category.DECORATION, 2.0, List.of(), 200, null, 0);
		ctx.assertTrue(BlockRuntimeManager.create(def).ok(), "the definition must be created");
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));
		BlockPos relTarget = BASE_REL.up(2);
		ctx.setBlockState(relTarget, dev.worldforge.registry.WorldForgeBlocks.DYNAMIC_BLOCK);
		if (ctx.getWorld().getBlockEntity(abs(ctx, relTarget))
				 instanceof dev.worldforge.block.DynamicBlockEntity be) {
			be.setVirtualId("worldforge:we13");
		}
		BlockPos abs = abs(ctx, relTarget);
		player.setPos(abs.getX() + 0.5, abs.getY() - 1.0, abs.getZ() + 0.5);
		player.setPitch(90.0f);
		var read = dev.worldforge.ops.tools.TargetBlock.read(new dev.worldforge.ops.ToolContext(
				player, player.getServer(), ctx.getWorld(), ctx.getWorld().getRegistryKey(),
				"conv-13", null, null));
		ctx.assertTrue(read.hit() && "worldforge:we13".equals(read.virtualId()),
				"the target must be recognised as a WF dynamic block: " + read.vanillaId());
		var outcome = WorldEditExecutor.execute(ctx.getWorld(),
				oneBlock(player.getUuid(), abs, Instant.now()), null);
		ctx.assertTrue(!outcome.fullyApplied() && outcome.applied() == 0,
				"a WF dynamic block must not be edited through the instance path: " + outcome);
		ctx.assertTrue(ctx.getWorld().getBlockEntity(abs) instanceof DynamicBlockEntity,
				"the dynamic block must be untouched");
		ctx.complete();
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void theEditSurvivesASave(TestContext ctx) {
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		BlockPos abs = abs(ctx, BASE_REL);
		var plan = oneBlock(UUID.randomUUID(), abs, Instant.now());
		ctx.assertTrue(WorldEditExecutor.execute(ctx.getWorld(), plan, null).fullyApplied(),
				"the edit must apply first");
		var server = ctx.getWorld().getServer();
		AtomicBoolean saved = new AtomicBoolean(false);
		server.execute(() -> saved.set(server.saveAll(true, true, false)));
		ctx.waitAndRun(20, () -> {
			ctx.assertTrue(saved.get(), "the save must complete without error");
			ctx.assertTrue(ctx.getWorld().getBlockState(abs).getBlock() == Blocks.GLASS,
					"the edit must persist across the save");
			ctx.complete();
		});
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void chatFlowEditThenUndoEndToEnd(TestContext ctx) {
		var dir = TestTempDirs.create("wf-worldedit-chat");
		WorldForgeRuntimeManager.attachStoreForTesting(new dev.worldforge.persistence.DefinitionStore(dir));
		BlockRuntimeManager.attachStoreForTesting(new dev.worldforge.block.BlockDefinitionStore(dir));
		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));
		player.setPos(abs(ctx, BASE_REL).getX() + 0.5,
				abs(ctx, BASE_REL).getY() - 1.0, abs(ctx, BASE_REL).getZ() - 2.0);
		player.setYaw(0.0f);
		player.setPitch(0.0f);
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		BlockPos targetAbs = abs(ctx, BASE_REL);

		AgentBridge.attachProviderForTesting(
				MockToolProvider.responding(m -> {
					if (m != null && m.contains(SINGLE_MSG)) {
						return calls("edit_world_blocks", "{\"block\":\"minecraft:glass\"}");
					}
					if (m != null && m.contains(UNDO_MSG)) {
						return calls("undo_world_edit", "{}");
					}
					return new ChatTurn.Turn.Final("好了。");
				}), cfg());

		var recorder = new AgentEventRecorder();
		String conversationId = "worldedit-chat";
		AgentOrchestrator.send(server, player, conversationId, SINGLE_MSG, null, recorder);

		dev.worldforge.WorldForge.LOGGER.info("[WE-DIAG] pre-confirm read: {}",
				dev.worldforge.ops.tools.TargetBlock.read(new dev.worldforge.ops.ToolContext(
						player, player.getServer(), ctx.getWorld(),
						ctx.getWorld().getRegistryKey(), "diag", null, null)));
		await(ctx, 30_000, () -> confirmationCount(recorder) > 0, () -> {
			ctx.assertTrue(ctx.getWorld().getBlockState(targetAbs).getBlock() == Blocks.STONE,
					"the world must be untouched while the confirmation is pending");
			var pending = pendingOf(recorder);
			ctx.assertTrue(pending.summary().contains("个方块"),
					"the preview must state the plan: " + pending.summary());
			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId()), "the confirmation must be accepted");

			await(ctx, 30_000, () -> finished(recorder, "edit_world_blocks") != null, () -> {
				var done = finished(recorder, "edit_world_blocks");
				ctx.assertEquals(ToolEventStatus.SUCCEEDED, done.status(),
						"the edit must succeed: " + done.summary());
				ctx.assertTrue(ctx.getWorld().getBlockState(targetAbs).getBlock() == Blocks.GLASS,
						"the world must now be glass");

				await(ctx, 30_000, () -> AgentOrchestrator.stateOf(
						player.getUuid(), conversationId) == null, () -> {
					AgentOrchestrator.send(server, player, conversationId, UNDO_MSG, null, recorder);
					// the undo asks for a confirmation of ITS OWN
					await(ctx, 30_000, () -> confirmationCount(recorder) >= 2, () -> {
						var undoPending = pendingOf(recorder);
						ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
								undoPending.confirmationId()), "the undo confirmation must be accepted");
						await(ctx, 30_000, () -> finished(recorder, "undo_world_edit") != null, () -> {
							var undo = finished(recorder, "undo_world_edit");
							ctx.assertEquals(ToolEventStatus.SUCCEEDED, undo.status(),
									"the undo must succeed: " + undo.summary());
							ctx.assertTrue(ctx.getWorld().getBlockState(targetAbs).getBlock() == Blocks.STONE,
									"the world must be back to stone");
							AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
							ctx.complete();
						});
					});
				});
			});
		});
	}

	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-worldedit", tickLimit = 60_000)
	public void chatFlowTargetChangedDuringConfirmationRefuses(TestContext ctx) {
		final String MSG2 = SINGLE_MSG + " (case 2)";
		var dir = TestTempDirs.create("wf-worldedit-chat-2");
		WorldForgeRuntimeManager.attachStoreForTesting(new dev.worldforge.persistence.DefinitionStore(dir));
		BlockRuntimeManager.attachStoreForTesting(new dev.worldforge.block.BlockDefinitionStore(dir));
		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));
		player.setPos(abs(ctx, BASE_REL).getX() + 0.5,
				abs(ctx, BASE_REL).getY() - 1.0, abs(ctx, BASE_REL).getZ() - 2.0);
		player.setYaw(0.0f);
		player.setPitch(0.0f);
		ctx.setBlockState(BASE_REL, Blocks.STONE);
		BlockPos targetAbs = abs(ctx, BASE_REL);

		SuiteReservedRoutes.registerExact(MSG2,
				() -> calls("edit_world_blocks", "{\"block\":\"minecraft:glass\"}"));
		AgentBridge.attachProviderForTesting(
				MockToolProvider.responding(m -> m != null && m.contains(MSG2)
						? calls("edit_world_blocks", "{\"block\":\"minecraft:glass\"}")
						: new ChatTurn.Turn.Final("好了。")), cfg());

		var recorder = new AgentEventRecorder();
		String conversationId = "worldedit-chat-2";
		AgentOrchestrator.send(server, player, conversationId, MSG2, null, recorder);

		await(ctx, 30_000, () -> confirmationCount(recorder) > 0, () -> {
			ctx.setBlockState(BASE_REL, Blocks.COBBLESTONE);
			var pending = pendingOf(recorder);
			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId()), "the confirmation must be accepted");
			await(ctx, 30_000, () -> finished(recorder, "edit_world_blocks") != null, () -> {
				var done = finished(recorder, "edit_world_blocks");
				ctx.assertEquals(ToolEventStatus.FAILED, done.status(),
						"the drifted plan must be refused: " + done.summary());
				ctx.assertTrue(ctx.getWorld().getBlockState(targetAbs).getBlock() == Blocks.COBBLESTONE,
						"zero writes: the drifted state must be untouched");
				AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
				ctx.complete();
			});
		});
	}

	// ------------------------------------------------------------- helpers

	private static int confirmationCount(AgentEventRecorder recorder) {
		int n = 0;
		for (ToolEvent e : recorder.events()) {
			if (e.status() == ToolEventStatus.AWAITING_CONFIRMATION) {
				n++;
			}
		}
		return n;
	}

	private static ToolEvent pendingOf(AgentEventRecorder recorder) {
		ToolEvent found = null;
		for (ToolEvent e : recorder.events()) {
			if (e.status() == ToolEventStatus.AWAITING_CONFIRMATION) {
				found = e;
			}
		}
		return found;
	}

	private static ToolEvent finished(AgentEventRecorder recorder, String tool) {
		ToolEvent found = null;
		for (ToolEvent e : recorder.events()) {
			if (tool.equals(e.toolName())
					&& (e.status() == ToolEventStatus.SUCCEEDED
							|| e.status() == ToolEventStatus.FAILED
							|| e.status() == ToolEventStatus.CANCELLED)) {
				found = e;
			}
		}
		return found;
	}

	private static void await(TestContext ctx, long budgetMillis,
			java.util.function.BooleanSupplier ready, Runnable then) {
		if (ready.getAsBoolean()) {
			then.run();
			return;
		}
		long deadline = System.currentTimeMillis() + budgetMillis;
		poll(ctx, deadline, ready, then, 0);
	}

	private static void poll(TestContext ctx, long deadline,
			java.util.function.BooleanSupplier ready, Runnable then, int level) {
		if (ready.getAsBoolean()) {
			then.run();
			return;
		}
		if (System.currentTimeMillis() > deadline) {
			ctx.assertTrue(false, "timed out waiting for the world edit");
			return;
		}
		ctx.waitAndRun(2, () -> poll(ctx, deadline, ready, then, level + 1));
	}
}
