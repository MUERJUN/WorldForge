package dev.worldforge.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.AgentOrchestrator;
import dev.worldforge.ai.ChatTurn;
import dev.worldforge.ai.MockToolProvider;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.ops.ToolEvent;
import dev.worldforge.ops.ToolEventStatus;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.registry.WorldForgeBlocks;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * v0.7.0 the two real player flows, end to end, as GameTests.
 *
 * <p><b>Flow A</b> — the player holds a WorldForge virtual weapon, asks the
 * AI to add a behaviour, confirms, and the behaviour REALLY works in the
 * world.</p>
 *
 * <p><b>Flow B</b> — the player looks at a WorldForge dynamic block, asks the
 * AI to change what happens when it is used, confirms, and the block REALLY
 * reacts (and keeps the change across a save/reload).</p>
 *
 * <p>The model is replaced by a script ({@link MockToolProvider}); what is
 * tested for real is the whole agent chain — preparation, confirmation,
 * permission re-check, execution, persistence and the resulting MINECRAFT
 * behaviour. The assertions check actual game state, never the chat text.</p>
 */
public class WorldForgeChatFlowTests {

	/** records every event the agent produced */
	private static final class Recorder implements AgentOrchestrator.AgentEventSink {
		final List<ToolEvent> events = new CopyOnWriteArrayList<>();
		final List<String> messages = new CopyOnWriteArrayList<>();
		final AtomicBoolean done = new AtomicBoolean(false);

		@Override
		public void onMessage(String conversationId, String role, String text) {
			messages.add(role + ": " + text);
			if ("assistant".equals(role)) {
				// the turn is finished only when the ASSISTANT produced its
				// final answer — a successful intermediate tool must not end
				// the wait, or the assertions would run mid-flight
				done.set(true);
			}
		}

		@Override
		public void onToolEvent(ToolEvent event) {
			events.add(event);
		}

		@Override
		public void onStatus(String conversationId, String status, String detail) {
			if ("done".equals(status) || "error".equals(status)) {
				done.set(true);
			}
		}

		/** a compact dump of every event, for failure diagnostics */
		String dump() {
			StringBuilder sb = new StringBuilder();
			for (ToolEvent e : events) {
				sb.append(" | ").append(e.status()).append(" ").append(e.toolName())
						.append(" -> ").append(e.summary());
				if (!e.errorCode().isBlank()) {
					sb.append(" (").append(e.errorCode()).append(')');
				}
			}
			for (String m : messages) {
				sb.append(" | ").append(m);
			}
			return sb.toString();
		}

		/** the confirmation that is currently waiting, or null */
		ToolEvent pending() {
			for (ToolEvent e : events) {
				if (e.status() == ToolEventStatus.AWAITING_CONFIRMATION) {
					return e;
				}
			}
			return null;
		}
	}

	private static ServerPlayerEntity opPlayer(TestContext ctx) {
		var p = ctx.createMockCreativeServerPlayerInWorld();
		p.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(p.getGameProfile(), 2, false));
		return p;
	}

	private static AgentConfig cfg() {
		return new AgentConfig(true, "http://mock", "mock", "sk-test", 30, 500, 0, 100, 12, 300);
	}

	/**
	 * Polls the recorder until the condition holds, then runs the action.
	 *
	 * <p>The server thread must never block: the agent loop runs on a worker
	 * thread and needs the server thread to execute its tools.</p>
	 */
	private static void await(TestContext ctx, int attempts, BooleanSupplier ready, Runnable then) {
		if (ready.getAsBoolean()) {
			then.run();
			return;
		}
		if (attempts <= 0) {
			ctx.assertTrue(false, "timed out waiting for the agent");
			return;
		}
		ctx.waitAndRun(2, () -> await(ctx, attempts - 1, ready, then));
	}

	// =============================================================== flow A

	/**
	 * Flow A: the held virtual weapon really gains the new behaviour.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-chat", tickLimit = 1200)
	public void flowA_heldWeaponGainsBehaviourAndReallyWorks(TestContext ctx) {
		var dir = TestTempDirs.create("wf-flow-a");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		String id = "worldforge:flow_a_sword";
		// the weapon starts WITHOUT any behaviour
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1, id, "测试剑", "",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 10.0, "max_durability", 500.0), List.of()));

		var server = ctx.getWorld().getServer();
		var player = opPlayer(ctx);

		// the player HOLDS the weapon
		ItemStack stack = DynamicItem.createStack(dev.worldforge.registry.WorldForgeItems.DYNAMIC_ITEM, id, 1);
		ctx.assertTrue(stack != null, "the virtual weapon must be creatable");
		player.getInventory().setStack(player.getInventory().selectedSlot, stack);

		// the scripted model: look at the context, then add a behaviour
		var provider = MockToolProvider.scripted(List.of(
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c1", "inspect_context", "{}"))),
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c2", "update_item",
						"{\"id\":\"" + id + "\",\"add_behaviors\":[{\"trigger\":\"right_click\","
								+ "\"action\":\"apply_effect\",\"cooldown_ticks\":100,"
								+ "\"parameters\":{\"effect\":\"minecraft:speed\",\"duration\":200,\"amplifier\":1}}]}"))),
				new ChatTurn.Turn.Final("已经给你的剑加上了右键加速效果。")));
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());

		var recorder = new Recorder();
		String conversationId = "flow-a";
		AgentOrchestrator.send(server, player, conversationId, "让它右键召唤加速", null, recorder);

		// wait for the confirmation, then approve it
		await(ctx, 200, () -> recorder.pending() != null, () -> {
			var pending = recorder.pending();
			ctx.assertTrue(pending != null, "the agent must ask for a confirmation");
			ctx.assertTrue(pending.summary().contains(id),
					"the confirmation must name the affected item: " + pending.summary());
			boolean ok = AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId());
			ctx.assertTrue(ok, "the confirmation must be accepted");
		});

		await(ctx, 200, recorder.done::get, () -> {
			// --- the DEFINITION really changed ---
			var def = WorldForgeRuntimeManager.get(id);
			ctx.assertTrue(def != null, "the definition must still exist");
			ctx.assertTrue(def.behaviors().size() == 1,
					"the behaviour must really be on the definition, got " + def.behaviors() + recorder.dump());
			var behavior = def.behaviors().get(0);
			ctx.assertTrue("right_click".equals(behavior.trigger()),
					"the trigger must be right_click, got " + behavior.trigger());
			ctx.assertTrue(behavior.actions().contains("apply_effect"),
					"the action must be apply_effect, got " + behavior.actions());

			// --- and it REALLY WORKS in the world ---
			player.removeStatusEffect(StatusEffects.SPEED);
			ItemStack held = player.getMainHandStack();
			ctx.assertTrue(held != null && !held.isEmpty(), "the player must still hold the weapon");
			var vid = DynamicItem.virtualIdOf(held);
			ctx.assertTrue(vid != null && vid.id().equals(id), "the held stack must be the virtual weapon");

			dev.worldforge.behavior.BehaviorEngine.onRightClick(
					ctx.getWorld(), player, held, Hand.MAIN_HAND);
			ctx.assertTrue(player.hasStatusEffect(StatusEffects.SPEED),
					"right-clicking the weapon must really apply the effect");

			ctx.complete();
		});
	}

	// =============================================================== flow B

	/**
	 * Flow B: the targeted dynamic block really changes, and keeps the change.
	 */
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-chat", tickLimit = 1200)
	public void flowB_targetedBlockChangesAndReallyWorks(TestContext ctx) {
		var dir = TestTempDirs.create("wf-flow-b");
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(dir));

		String id = "worldforge:flow_b_block";
		// the block starts WITHOUT any behaviour
		BlockRuntimeManager.create(BlockDefinition.create(id, "测试方块", "",
				"minecraft:stone", BlockDefinition.Category.INTERACTIVE, 2.0, List.of(), 200, null, 0));

		var server = ctx.getWorld().getServer();
		var player = opPlayer(ctx);

		// place the block and give it its identity
		BlockPos pos = new BlockPos(2, 1, 2);
		var abs = ctx.getAbsolutePos(pos);
		ctx.setBlockState(pos, WorldForgeBlocks.DYNAMIC_BLOCK.getDefaultState());
		var be = (DynamicBlockEntity) ctx.getWorld().getBlockEntity(abs);
		ctx.assertTrue(be != null, "the dynamic block must have a block entity");
		be.setVirtualId(id);

		// the player LOOKS at the block: stand 3 blocks away, at the block's
		// height, and aim at its centre (eye height 1.62 above the feet)
		java.util.function.Consumer<ServerPlayerEntity> aim = p -> {
			p.setPosition(abs.getX() + 3.5, abs.getY() + 0.5, abs.getZ() + 0.5);
			// the player stands EAST of the block, so it must look WEST (-X)
			// NB: the look ray uses headYaw (LivingEntity.getYaw(tickDelta)),
			// not the plain yaw field, so both have to be set.
			p.setYaw(90.0f);
			p.setHeadYaw(90.0f);
			p.prevYaw = 90.0f;
			p.prevHeadYaw = 90.0f;
			p.setPitch(28.0f); // look down at the block's centre
			p.prevPitch = 28.0f;
		};
		aim.accept(player);

		var provider = MockToolProvider.scripted(List.of(
				new ChatTurn.Turn.Calls(List.of(
						new ChatTurn.ModelToolCall("c1", "inspect_target_block", "{}"))),
				new ChatTurn.Turn.Calls(List.of(new ChatTurn.ModelToolCall("c2", "modify_target_block",
						"{\"add_behaviors\":[{\"trigger\":\"right_click\",\"action\":\"apply_effect\","
								+ "\"cooldown_ticks\":100,\"parameters\":{\"effect\":\"minecraft:speed\","
								+ "\"duration\":200,\"amplifier\":1}}]}"))),
				new ChatTurn.Turn.Final("方块右键现在会给你加速了。")));
		dev.worldforge.ai.AgentBridge.attachProviderForTesting(provider, cfg());

		var recorder = new Recorder();
		String conversationId = "flow-b";
		aim.accept(player);
		// DIAGNOSTIC: what does the raycast actually see right now?
		// sanity: the player must really be aiming at the block, otherwise the
		// flow would be testing "no target" instead of "modify the target"
		var probe = player.raycast(6.0, 1.0f, false);
		ctx.assertTrue(probe.getType() != net.minecraft.util.hit.HitResult.Type.MISS,
				"the player must be aiming at the block: pos=" + player.getPos()
						+ " eye=" + player.getEyePos() + " headYaw=" + player.getHeadYaw()
						+ " pitch=" + player.getPitch() + " block=" + abs);
		AgentOrchestrator.send(server, player, conversationId, "让这个方块右键时给我加速", null, recorder);

		await(ctx, 200, () -> {
			// the server may move a mock player between ticks, so keep aiming
			aim.accept(player);
			return recorder.pending() != null;
		}, () -> {
			var pending = recorder.pending();
			ctx.assertTrue(pending != null, "the agent must ask for a confirmation");
			ctx.assertTrue(pending.summary().contains(id),
					"the confirmation must name the affected block: " + pending.summary());
			ctx.assertTrue(pending.summary().contains("共享定义") || pending.summary().contains("影响"),
					"the confirmation must warn about the shared definition: " + pending.summary());
			boolean ok = AgentOrchestrator.confirm(player.getUuid(), conversationId,
					pending.confirmationId());
			ctx.assertTrue(ok, "the confirmation must be accepted");
		});

		await(ctx, 200, recorder.done::get, () -> {
			// --- the DEFINITION really changed ---
			var def = BlockRuntimeManager.get(id);
			ctx.assertTrue(def != null, "the block definition must still exist");
			ctx.assertTrue(def.behaviors().size() == 1,
					"the behaviour must really be on the definition, got " + def.behaviors() + recorder.dump());
			ctx.assertTrue(def.behaviors().get(0).actions().contains("apply_effect"),
					"the action must be apply_effect, got " + def.behaviors().get(0).actions());

			// --- and it REALLY WORKS in the world ---
			player.removeStatusEffect(StatusEffects.SPEED);
			// the same entry point the block's onUse delegates to
			var liveBe = (DynamicBlockEntity) ctx.getWorld().getBlockEntity(abs);
			var liveDef = BlockRuntimeManager.get(id);
			var result = dev.worldforge.block.BlockBehaviorEngine.onUse(ctx.getWorld(), abs,
					player, liveDef, liveBe);
			ctx.assertTrue(result != null, "using the block must return a result");
			ctx.assertTrue(player.hasStatusEffect(StatusEffects.SPEED),
					"using the block must really apply the effect");

			// --- and the change SURVIVES a save/reload ---
			var store = new BlockDefinitionStore(dir);
			var reloaded = store.tryLoadStrict();
			ctx.assertTrue(reloaded.ok(), "the block file must still load: " + reloaded.error());
			ctx.assertTrue(reloaded.definitions().size() == 1, "the block must still be there");
			ctx.assertTrue(reloaded.definitions().get(0).behaviors().size() == 1,
					"the behaviour must have been persisted");

			ctx.complete();
		});
	}
}
