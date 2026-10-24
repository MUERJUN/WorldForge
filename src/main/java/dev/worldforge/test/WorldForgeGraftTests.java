package dev.worldforge.test;

import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.AgentOrchestrator;
import dev.worldforge.ai.ChatTurn;
import dev.worldforge.ai.MockToolProvider;
import dev.worldforge.behavior.BehaviorEngine;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.ops.ToolEvent;
import dev.worldforge.ops.ToolEventStatus;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;

import java.util.List;
import java.util.Set;

/**
 * v0.7.2 — the world interaction loop.
 *
 * <p>Until now the behaviour engine only ever ran for a WorldForge
 * <em>virtual</em> item, so "give the sword in my hand lightning" was
 * impossible for a vanilla sword. This test proves the new loop end to end on a
 * VANILLA item:</p>
 *
 * <ol>
 *   <li>the player holds a plain {@code minecraft:diamond_sword};</li>
 *   <li>the AI grafts a behaviour onto it, after the player confirmed;</li>
 *   <li>the graft really fires — through the real Fabric use hook, not
 *       through a shortcut;</li>
 *   <li>the AI can undo it, and the stack is then <em>exactly</em> as it was:
 *       same id, same texture, same components, no trace left.</li>
 * </ol>
 */
public class WorldForgeGraftTests {

	/** the item the player holds: a completely ordinary vanilla sword */
	private static final String VANILLA_SWORD = "minecraft:diamond_sword";

	private static AgentConfig cfg() {
		return new AgentConfig(true, "http://mock", "mock", "sk-test", 60, 500, 0, 100, 12, 300);
	}

	/**
	 * The whole loop: graft -&gt; it really fires -&gt; undo -&gt; exactly as before.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-graft",
			tickLimit = 60_000)
	public void graftingAVanillaItemWorksAndIsUndoable(TestContext ctx) {
		var dir = TestTempDirs.create("wf-graft");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));

		// the player holds an ordinary vanilla sword
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		player.getInventory().setStack(player.getInventory().selectedSlot, sword);
		ctx.assertTrue(player.getMainHandStack().getItem() == Items.DIAMOND_SWORD,
				"the player must hold a vanilla sword");

		// what the stack looked like BEFORE anything happened
		Set<net.minecraft.component.ComponentType<?>> before = sword.getComponents().getTypes();
		ctx.assertFalse(sword.getComponents().contains(WorldForgeComponents.BEHAVIOR_GRAFT),
				"a fresh item has no graft");

		// The scripted model: graft once, then answer; the undo asks for the
		// ungraft. The counter matters — without it the model would keep
		// asking for another graft and the task would never end.
		var grafted = new java.util.concurrent.atomic.AtomicInteger();
		var provider = MockToolProvider.responding(userMessage -> {
			if (userMessage != null && userMessage.contains("撤销")) {
				return new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c2", "ungraft_behavior", "{}")));
			}
			if (grafted.getAndIncrement() == 0) {
				return new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "graft_behavior",
						"{\"action\":\"apply_effect\",\"trigger\":\"right_click\","
								+ "\"cooldown_ticks\":40,\"parameters\":"
								+ "{\"effect\":\"minecraft:speed\",\"duration\":200,\"amplifier\":1}}")));
			}
			return new ChatTurn.Turn.Final("好了，剑现在会给你加速。");
		});
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());

		var recorder = new AgentEventRecorder();
		// how many confirmations have ALREADY been answered
		var confirmationsSeen = new java.util.concurrent.atomic.AtomicInteger();
		String conversationId = "graft";
		AgentOrchestrator.send(server, player, conversationId, "给我手里的剑加上加速", null, recorder);

		// wait for the confirmation, then approve it
		await(ctx, 30_000, () -> confirmationCount(recorder) > 0, () -> {
			var pending = pendingOf(recorder);
			ctx.assertTrue(pending != null, "the graft must ask for a confirmation");
			ctx.assertTrue(pending.summary().contains(VANILLA_SWORD),
					"the confirmation must name the item it touches: " + pending.summary());
			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId()), "the confirmation must be accepted");
			confirmationsSeen.set(confirmationCount(recorder));

			// the tool runs on the server thread AFTER the confirmation, so the
			// result arrives asynchronously — wait for it
			await(ctx, 30_000, () -> finished(recorder, "graft_behavior") != null, () -> {
				var done = finished(recorder, "graft_behavior");
				ctx.assertTrue(done != null && done.status() == ToolEventStatus.SUCCEEDED,
						"the graft must succeed " + recorder.dump());

				// the graft must be ON the stack now
				ItemStack held = player.getMainHandStack();
				ctx.assertTrue(held.getComponents().contains(WorldForgeComponents.BEHAVIOR_GRAFT),
						"the held sword must carry the graft " + recorder.dump());
				ctx.assertTrue(BehaviorEngine.graftBehaviors(held).size() == 1,
						"the graft must carry exactly one behaviour");

				// ... and NOTHING else about the item may have changed
				ctx.assertTrue(held.getItem() == Items.DIAMOND_SWORD,
						"the item must still be the very same vanilla sword");
				Set<net.minecraft.component.ComponentType<?>> after = held.getComponents().getTypes();
				after.remove(WorldForgeComponents.BEHAVIOR_GRAFT);
				after.remove(WorldForgeComponents.BEHAVIOR_STATE);
				Set<net.minecraft.component.ComponentType<?>> expectedBefore =
						new java.util.HashSet<>(before);
				expectedBefore.remove(WorldForgeComponents.BEHAVIOR_GRAFT);
				expectedBefore.remove(WorldForgeComponents.BEHAVIOR_STATE);
				ctx.assertEquals(expectedBefore, after,
						"the graft must be the ONLY change to the stack");

				// the behaviour must REALLY fire — through the real Fabric use hook
				player.removeStatusEffect(StatusEffects.SPEED);
				ctx.assertFalse(player.hasStatusEffect(StatusEffects.SPEED),
						"the effect must not be there before the use");
				UseItemCallback.EVENT.invoker().interact(player, ctx.getWorld(), Hand.MAIN_HAND);
				ctx.assertTrue(player.hasStatusEffect(StatusEffects.SPEED),
						"right-clicking the grafted vanilla sword must really apply the effect");

			// ---- the undo ----
			// the first task finishes its model round trip after the tool ran;
			// the undo must wait for it, or it would only be QUEUED
			await(ctx, 30_000, () -> AgentOrchestrator.stateOf(player.getUuid(), conversationId) == null, () -> {
			AgentOrchestrator.send(server, player, conversationId, "撤销它", null, recorder);
			// the undo asks for a confirmation of its OWN
			await(ctx, 30_000, () -> confirmationCount(recorder) > confirmationsSeen.get(), () -> {
				var undo = pendingOf(recorder);
				ctx.assertTrue(undo != null, "the undo must ask for a confirmation");
				ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
						undo.confirmationId()),
						"the undo must be accepted; undoId=" + undo.confirmationId()
								+ " pending=" + AgentOrchestrator.pendingFor(player.getUuid(), conversationId)
								+ " dump=" + recorder.dump());

				// wait for the undo to actually run
				await(ctx, 30_000, () -> finished(recorder, "ungraft_behavior") != null, () -> {
				ItemStack afterUndo = player.getMainHandStack();
				ctx.assertFalse(afterUndo.getComponents().contains(WorldForgeComponents.BEHAVIOR_GRAFT),
						"the graft must be gone after the undo");
				// The item must be the same item again. The only component that
				// may differ is the behaviour COOLDOWN state, which the use
				// hook itself creates when the grafted behaviour fired.
				Set<net.minecraft.component.ComponentType<?>> afterUndoTypes =
						afterUndo.getComponents().getTypes();
				afterUndoTypes.remove(WorldForgeComponents.BEHAVIOR_GRAFT);
				afterUndoTypes.remove(WorldForgeComponents.BEHAVIOR_STATE);
				Set<net.minecraft.component.ComponentType<?>> expected = new java.util.HashSet<>(before);
				expected.remove(WorldForgeComponents.BEHAVIOR_GRAFT);
				expected.remove(WorldForgeComponents.BEHAVIOR_STATE);
				ctx.assertEquals(expected, afterUndoTypes,
						"the stack must be as it was before the graft (apart from the cooldown state)");
				ctx.assertTrue(afterUndo.getItem() == Items.DIAMOND_SWORD,
						"the item must still be the very same vanilla sword");

				// and the behaviour must really be gone
				player.removeStatusEffect(StatusEffects.SPEED);
				UseItemCallback.EVENT.invoker().interact(player, ctx.getWorld(), Hand.MAIN_HAND);
				ctx.assertFalse(player.hasStatusEffect(StatusEffects.SPEED),
						"after the undo the behaviour must not fire any more");

				AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
				ctx.complete();
				});
				});
			});
			});
		});
	}

	/**
	 * The whitelist is not a way around the rules: an action that is not
	 * allowed must be refused, and the item must be left untouched.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-graft-2",
			tickLimit = 60_000)
	public void aForbiddenBehaviourIsRefusedAndTheItemIsUntouched(TestContext ctx) {
		var dir = TestTempDirs.create("wf-graft-2");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));
		player.getInventory().setStack(player.getInventory().selectedSlot, new ItemStack(Items.DIAMOND_SWORD));
		Set<net.minecraft.component.ComponentType<?>> before = player.getMainHandStack().getComponents().getTypes();

		// the model asks for an action that does not exist
		var provider = MockToolProvider.responding(userMessage ->
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "graft_behavior",
						"{\"action\":\"launch_missiles\"}"))));
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());

		var recorder = new AgentEventRecorder();
		String conversationId = "graft-bad";
		AgentOrchestrator.send(server, player, conversationId, "给我的剑加个导弹", null, recorder);

		await(ctx, 30_000, () -> finished(recorder, "graft_behavior") != null, () -> {
			var done = finished(recorder, "graft_behavior");
			ctx.assertTrue(done != null, "the graft must report a final state");
			ctx.assertEquals(ToolEventStatus.FAILED, done.status(),
					"an unknown action must be refused: " + done.summary());
			ctx.assertTrue(!player.getMainHandStack().getComponents().contains(WorldForgeComponents.BEHAVIOR_GRAFT),
					"the item must be untouched");
			ctx.assertEquals(before, player.getMainHandStack().getComponents().getTypes(),
					"the stack must be untouched");
			AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
			ctx.complete();
		});
	}

	/**
	 * The confirmation target must not change: if the player swaps to a
	 * different item between the confirmation and the execution, the graft
	 * must be refused.
	 *
	 * <p>The snapshot captures the held stack's id and count at preparation
	 * time; the orchestrator re-runs the preparer at execution time and
	 * compares. A swap changes the id, so the comparison fails and the
	 * tool is refused with {@code target_changed}.</p>
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-graft-3",
			tickLimit = 60_000)
	public void swappingTheHeldItemAfterConfirmingRefusesTheGraft(TestContext ctx) {
		var dir = TestTempDirs.create("wf-graft-3");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));
		player.getInventory().setStack(player.getInventory().selectedSlot, new ItemStack(Items.DIAMOND_SWORD));

		var provider = MockToolProvider.responding(userMessage ->
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "graft_behavior",
						"{\"action\":\"apply_effect\",\"trigger\":\"right_click\","
								+ "\"cooldown_ticks\":40,\"parameters\":"
								+ "{\"effect\":\"minecraft:speed\",\"duration\":200,\"amplifier\":1}}"))));
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());

		var recorder = new AgentEventRecorder();
		String conversationId = "graft-swap";
		AgentOrchestrator.send(server, player, conversationId, "给我手里的剑加上加速", null, recorder);

		await(ctx, 30_000, () -> confirmationCount(recorder) > 0, () -> {
			var pending = pendingOf(recorder);
			ctx.assertTrue(pending != null, "the graft must ask for a confirmation");

			// the player swaps to a DIFFERENT item while the confirmation is
			// pending
			player.getInventory().setStack(player.getInventory().selectedSlot, new ItemStack(Items.DIAMOND_PICKAXE));
			ctx.assertTrue(player.getMainHandStack().getItem() == Items.DIAMOND_PICKAXE,
					"the player must now hold a different item");

			// ... and confirms
			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId()), "the confirmation must be accepted");

			// the graft must be REFUSED because the target changed
			await(ctx, 30_000, () -> finished(recorder, "graft_behavior") != null, () -> {
				var done = finished(recorder, "graft_behavior");
				ctx.assertTrue(done != null, "the graft must report a final state");
				ctx.assertEquals(ToolEventStatus.FAILED, done.status(),
						"the graft must fail when the target changed: " + done.summary());
				ctx.assertTrue(done.summary().contains("target_changed") || done.summary().contains("变化"),
						"the failure must name the target change: " + done.summary());

				// and the item must be untouched
				ctx.assertFalse(player.getMainHandStack().getComponents().contains(WorldForgeComponents.BEHAVIOR_GRAFT),
						"the graft must NOT be applied to the swapped item");

				AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
				ctx.complete();
			});
		});
	}

	// ------------------------------------------------------------- helpers

	/**
	 * P0-2: the fingerprint must distinguish items with the same id and
	 * count but different durability. Two diamond swords with different
	 * damage levels are different targets.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-graft-p0-2",
			tickLimit = 60_000)
	public void swappingToSameItemTypeWithDifferentDurabilityRefusesTheGraft(TestContext ctx) {
		var dir = TestTempDirs.create("wf-graft-p0-2-dur");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));

		// player holds a fresh diamond sword
		player.getInventory().setStack(player.getInventory().selectedSlot, new ItemStack(Items.DIAMOND_SWORD));

		var provider = MockToolProvider.responding(userMessage ->
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "graft_behavior",
						"{\"action\":\"apply_effect\",\"trigger\":\"right_click\","
								+ "\"cooldown_ticks\":40,\"parameters\":"
								+ "{\"effect\":\"minecraft:speed\",\"duration\":200,\"amplifier\":1}}"))));
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());

		var recorder = new AgentEventRecorder();
		String conversationId = "graft-dur-swap";
		AgentOrchestrator.send(server, player, conversationId, "给我手里的剑加上加速", null, recorder);

		await(ctx, 30_000, () -> confirmationCount(recorder) > 0, () -> {
			var pending = pendingOf(recorder);
			ctx.assertTrue(pending != null, "the graft must ask for a confirmation");

			// player swaps to a DAMAGED diamond sword (same id, same count, different durability)
			var damagedSword = new ItemStack(Items.DIAMOND_SWORD);
			damagedSword.set(net.minecraft.component.DataComponentTypes.DAMAGE, 10);
			player.getInventory().setStack(player.getInventory().selectedSlot, damagedSword);
			ctx.assertTrue(player.getMainHandStack().getItem() == Items.DIAMOND_SWORD,
					"the player must still hold a diamond sword");

			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId()), "the confirmation must be accepted");

			await(ctx, 30_000, () -> finished(recorder, "graft_behavior") != null, () -> {
				var done = finished(recorder, "graft_behavior");
				ctx.assertTrue(done != null, "the graft must report a final state");
				ctx.assertEquals(ToolEventStatus.FAILED, done.status(),
						"the graft must fail when durability changed: " + done.summary());
				ctx.assertFalse(player.getMainHandStack().getComponents().contains(WorldForgeComponents.BEHAVIOR_GRAFT),
						"the graft must NOT be applied to the swapped item");

				AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
				ctx.complete();
			});
		});
	}

	/**
	 * P0-2: the fingerprint must distinguish items with the same id and
	 * count but different custom names.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-graft-p0-2-name",
			tickLimit = 60_000)
	public void swappingToSameItemTypeWithDifferentNameRefusesTheGraft(TestContext ctx) {
		var dir = TestTempDirs.create("wf-graft-p0-2-name");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));

		// player holds a diamond sword with a custom name
		var namedSword = new ItemStack(Items.DIAMOND_SWORD);
		namedSword.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
				net.minecraft.text.Text.literal("我的剑"));
		player.getInventory().setStack(player.getInventory().selectedSlot, namedSword);

		var provider = MockToolProvider.responding(userMessage ->
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "graft_behavior",
						"{\"action\":\"apply_effect\",\"trigger\":\"right_click\","
								+ "\"cooldown_ticks\":40,\"parameters\":"
								+ "{\"effect\":\"minecraft:speed\",\"duration\":200,\"amplifier\":1}}"))));
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());

		var recorder = new AgentEventRecorder();
		String conversationId = "graft-name-swap";
		AgentOrchestrator.send(server, player, conversationId, "给我手里的剑加上加速", null, recorder);

		await(ctx, 30_000, () -> confirmationCount(recorder) > 0, () -> {
			var pending = pendingOf(recorder);
			ctx.assertTrue(pending != null, "the graft must ask for a confirmation");

			// player swaps to an UNNAMED diamond sword
			var plainSword = new ItemStack(Items.DIAMOND_SWORD);
			player.getInventory().setStack(player.getInventory().selectedSlot, plainSword);

			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId()), "the confirmation must be accepted");

			await(ctx, 30_000, () -> finished(recorder, "graft_behavior") != null, () -> {
				var done = finished(recorder, "graft_behavior");
				ctx.assertTrue(done != null, "the graft must report a final state");
				ctx.assertEquals(ToolEventStatus.FAILED, done.status(),
						"the graft must fail when the name changed: " + done.summary());
				ctx.assertFalse(player.getMainHandStack().getComponents().contains(WorldForgeComponents.BEHAVIOR_GRAFT),
						"the graft must NOT be applied to the swapped item");

				AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
				ctx.complete();
			});
		});
	}

	/**
	 * P0-2: ungraft must also verify the target fingerprint. If the player
	 * swaps items between confirmation and execution, the ungraft must be
	 * refused.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-graft-p0-2-ungraft",
			tickLimit = 60_000)
	public void swappingAfterConfirmingUngraftRefusesTheUndo(TestContext ctx) {
		var dir = TestTempDirs.create("wf-graft-p0-2-ungraft");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));

		// player holds a grafted sword
		var sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.set(WorldForgeComponents.BEHAVIOR_GRAFT,
				new dev.worldforge.component.BehaviorGraft(List.of(
						new dev.worldforge.definition.BehaviorDefinition(
								"right_click", List.of("summon_lightning"),
								java.util.Map.of(), 40))));
		player.getInventory().setStack(player.getInventory().selectedSlot, sword);

		// the model asks for ungraft
		var provider = MockToolProvider.responding(userMessage ->
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "ungraft_behavior", "{}"))));
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());

		var recorder = new AgentEventRecorder();
		String conversationId = "ungraft-swap";
		AgentOrchestrator.send(server, player, conversationId, "撤销它", null, recorder);

		await(ctx, 30_000, () -> confirmationCount(recorder) > 0, () -> {
			var pending = pendingOf(recorder);
			ctx.assertTrue(pending != null, "the ungraft must ask for a confirmation");

			// player swaps to a DIFFERENT grafted sword (different durability)
			var otherSword = new ItemStack(Items.DIAMOND_SWORD);
			otherSword.set(net.minecraft.component.DataComponentTypes.DAMAGE, 5);
			otherSword.set(WorldForgeComponents.BEHAVIOR_GRAFT,
					new dev.worldforge.component.BehaviorGraft(List.of(
							new dev.worldforge.definition.BehaviorDefinition(
									"right_click", List.of("summon_lightning"),
									java.util.Map.of(), 40))));
			player.getInventory().setStack(player.getInventory().selectedSlot, otherSword);

			ctx.assertTrue(AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId()), "the confirmation must be accepted");

			await(ctx, 30_000, () -> finished(recorder, "ungraft_behavior") != null, () -> {
				var done = finished(recorder, "ungraft_behavior");
				ctx.assertTrue(done != null, "the ungraft must report a final state");
				ctx.assertEquals(ToolEventStatus.FAILED, done.status(),
						"the ungraft must fail when the target changed: " + done.summary());
				// the new sword must STILL have its graft (not removed)
				ctx.assertTrue(player.getMainHandStack().getComponents().contains(WorldForgeComponents.BEHAVIOR_GRAFT),
						"the ungraft must NOT remove the graft from the swapped item");

				AgentOrchestrator.releaseSession(player.getUuid(), conversationId);
				ctx.complete();
			});
		});
	}

	/**
	 * P1: the server-side behaviour policy must block dangerous actions at
	 * EXECUTION time, even when the behaviour was grafted successfully.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-graft-p1-policy",
			tickLimit = 60_000)
	public void dangerousBehaviourIsBlockedByServerPolicyAtExecutionTime(TestContext ctx) {
		var dir = TestTempDirs.create("wf-graft-p1-policy");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));

		// the player holds a grafted sword with explosion behaviour
		var sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.set(WorldForgeComponents.BEHAVIOR_GRAFT,
				new dev.worldforge.component.BehaviorGraft(List.of(
						new dev.worldforge.definition.BehaviorDefinition(
								"right_click", List.of("explosion"),
								java.util.Map.of("power", 1.5), 40))));
		player.getInventory().setStack(player.getInventory().selectedSlot, sword);

		// the default policy blocks explosion
		dev.worldforge.behavior.BehaviorPolicy.set(dev.worldforge.behavior.BehaviorPolicy.DEFAULT);

		// right-click the grafted sword
		UseItemCallback.EVENT.invoker().interact(player, ctx.getWorld(), Hand.MAIN_HAND);

		// the explosion must NOT have fired — the policy blocked it
		ctx.assertTrue(player.getHealth() == player.getMaxHealth(),
				"the player must not be damaged by the blocked explosion");
		var state = player.getMainHandStack().get(WorldForgeComponents.BEHAVIOR_STATE);
		ctx.assertTrue(state == null || state.cooldownUntilGameTime() == 0,
				"the cooldown must NOT be set when the action is blocked by policy");

		// now the server allows explosion
		dev.worldforge.behavior.BehaviorPolicy.set(
				dev.worldforge.behavior.BehaviorPolicy.of(
						java.util.Set.of("explosion"), java.util.Set.of()));

		// right-click again — now the explosion fires
		UseItemCallback.EVENT.invoker().interact(player, ctx.getWorld(), Hand.MAIN_HAND);

		var stateAfter = player.getMainHandStack().get(WorldForgeComponents.BEHAVIOR_STATE);
		ctx.assertTrue(stateAfter != null,
				"the cooldown must be set when the action fires after the policy change");
		ctx.assertTrue(stateAfter.cooldownUntilGameTime() > 0,
				"the cooldown must be positive when the action fires");

		dev.worldforge.behavior.BehaviorPolicy.set(null);
		ctx.complete();
	}

	/**
	 * P1: safe behaviours must work even without any server config.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-graft-p1-safe",
			tickLimit = 60_000)
	public void safeBehaviourWorksWithoutServerConfig(TestContext ctx) {
		var dir = TestTempDirs.create("wf-graft-p1-safe");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		var server = ctx.getWorld().getServer();
		ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		player.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(player.getGameProfile(), 2, false));

		var sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.set(WorldForgeComponents.BEHAVIOR_GRAFT,
				new dev.worldforge.component.BehaviorGraft(List.of(
						new dev.worldforge.definition.BehaviorDefinition(
								"right_click", List.of("heal"),
								java.util.Map.of("amount", 4.0), 40))));
		player.getInventory().setStack(player.getInventory().selectedSlot, sword);

		dev.worldforge.behavior.BehaviorPolicy.set(dev.worldforge.behavior.BehaviorPolicy.DEFAULT);
		UseItemCallback.EVENT.invoker().interact(player, ctx.getWorld(), Hand.MAIN_HAND);

		var healState = player.getMainHandStack().get(WorldForgeComponents.BEHAVIOR_STATE);
		ctx.assertTrue(healState != null && healState.cooldownUntilGameTime() > 0,
				"the heal must fire and set a cooldown under the default policy");

		dev.worldforge.behavior.BehaviorPolicy.set(null);
		ctx.complete();
	}

	private static void await(TestContext ctx, long budgetMillis, java.util.function.BooleanSupplier ready,
			Runnable then) {
		if (ready.getAsBoolean()) {
			then.run();
			return;
		}
		long deadline = System.currentTimeMillis() + budgetMillis;
		poll(ctx, deadline, ready, then, 0);
	}

	private static void poll(TestContext ctx, long deadline, java.util.function.BooleanSupplier ready,
			Runnable then, int level) {
		if (ready.getAsBoolean()) {
			then.run();
			return;
		}
		if (System.currentTimeMillis() > deadline) {
			ctx.assertTrue(false, "timed out waiting for the agent");
			return;
		}
		ctx.waitAndRun(2, () -> poll(ctx, deadline, ready, then, level + 1));
	}

	/**
	 * How many confirmations the agent has asked for so far.
	 *
	 * <p>A test must wait for a NEW confirmation, not merely for "a
	 * confirmation": the earlier one has already been answered, and answering
	 * it again correctly does nothing.</p>
	 */
	private static int confirmationCount(AgentEventRecorder recorder) {
		int n = 0;
		for (ToolEvent e : recorder.events()) {
			if (e.status() == ToolEventStatus.AWAITING_CONFIRMATION) {
				n++;
			}
		}
		return n;
	}

	/** The confirmation the player has to answer NOW. */
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
}
