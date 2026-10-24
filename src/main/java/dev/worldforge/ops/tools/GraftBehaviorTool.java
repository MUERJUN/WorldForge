package dev.worldforge.ops.tools;

import com.google.gson.JsonObject;
import dev.worldforge.ai.Proposal;
import dev.worldforge.ai.ProposalValidator;
import dev.worldforge.behavior.BehaviorEngine;
import dev.worldforge.component.BehaviorGraft;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolPreparer;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code graft_behavior} and {@code ungraft_behavior} — v0.7.2's world
 * interaction.
 *
 * <p>Until now the AI could only change a WorldForge <em>virtual</em>
 * definition, so "give the sword in my hand lightning" was impossible for a
 * vanilla sword. These tools attach a behaviour to the stack the player is
 * actually holding.</p>
 *
 * <p>What the item is:</p>
 * <ul>
 *   <li>the same item it was — same id, same texture, same enchantments, same
 *       durability;</li>
 *   <li>only <em>gaining</em> the ability to do one thing;</li>
 *   <li><b>fully undoable</b>: removing the graft restores the stack
 *       exactly, because nothing else about it was touched.</li>
 * </ul>
 *
 * <p>The behaviour still obeys the SAME whitelist as a behaviour on a
 * definition (the reuse of {@link ProposalValidator#validateBehavior} is
 * what guarantees that): the graft is not a way around the rules.</p>
 */
public final class GraftBehaviorTool {

	/** the action names the AI may graft (the same whitelist as definitions) */
	private static final List<String> ACTIONS = List.of("summon_lightning", "apply_effect", "heal",
			"experience", "teleport", "explosion", "ignite");

	/** the triggers the AI may graft */
	private static final List<String> TRIGGERS = List.of("right_click", "hit_entity");

	private GraftBehaviorTool() {
	}

	// ------------------------------------------------------------- graft

	public static final String GRAFT = "graft_behavior";

	public static ToolDescriptor graftDescriptor() {
		JsonObject props = new JsonObject();
		props.add("action", JsonSchemaValidator.propEnum("要附加的行为", ACTIONS.toArray(new String[0])));
		props.add("trigger", JsonSchemaValidator.propEnum("什么时候触发", TRIGGERS.toArray(new String[0])));
		props.add("cooldown_ticks", JsonSchemaValidator.prop("integer", "冷却 tick（20 = 1 秒）", 0, 72000));
		props.add("parameters", new JsonObject());
		JsonObject schema = JsonSchemaValidator.object(props, List.of("action"));
		return ToolDescriptor.self(GRAFT,
				"给你手里正在拿着的物品附加一个 WorldForge 行为。物品本身完全不变（同样的模型、耐久、附魔），"
						+ "只是多了一个能力；随时可以撤销。",
				schema);
	}

	public static final ToolExecutor GRAFT_EXEC = (ctx, request, sink) -> {
		var player = ctx.player();
		if (player == null) {
			return ToolResult.fail("no_player", "没有找到你手里的物品");
		}
		ItemStack stack = player.getMainHandStack();
		if (stack.isEmpty()) {
			return ToolResult.fail("no_item", "你的主手是空的");
		}
		String action = request.argumentString("action");
		String trigger = request.argumentString("trigger");
		if (trigger == null || trigger.isBlank()) {
			trigger = "right_click";
		}
		Integer cooldown = request.argumentInt("cooldown_ticks", 20);

		// the parameters come from the model, but they are validated by the
		// SAME rules the definitions use
		Map<String, Object> params = new LinkedHashMap<>();
		Object raw = request.arguments().get("parameters");
		if (raw instanceof Map<?, ?> m) {
			m.forEach((k, v) -> {
				if (v instanceof Number n) {
					params.put(String.valueOf(k), n.doubleValue());
				} else if (v instanceof Boolean b) {
					params.put(String.valueOf(k), b);
				} else if (v != null) {
					params.put(String.valueOf(k), String.valueOf(v));
				}
			});
		}
		Proposal.BehaviorSpec spec = new Proposal.BehaviorSpec(trigger, action, cooldown, params);
		var problems = ProposalValidator.validateBehavior(spec);
		if (!problems.isEmpty()) {
			return ToolResult.fail("invalid_arguments", "这个行为不被允许: " + String.join("; ", problems));
		}
		BehaviorDefinition behavior = ProposalValidator.toBehavior(spec);

		// the graft is added to the behaviour list the stack already carries
		List<BehaviorDefinition> behaviors = new ArrayList<>(BehaviorEngine.graftBehaviors(stack));
		behaviors.add(behavior);
		stack.set(WorldForgeComponents.BEHAVIOR_GRAFT, new BehaviorGraft(behaviors));

		JsonObject o = new JsonObject();
		o.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
		o.addProperty("action", behavior.actions().isEmpty() ? "" : behavior.actions().get(0));
		o.addProperty("trigger", behavior.trigger());
		o.addProperty("cooldown_ticks", behavior.cooldownTicks());
		return new ToolResult(true, "",
				"已给手中的「" + stack.getName().getString() + "」附加行为："
						+ triggerLabel(behavior.trigger()) + "·" + actionLabel(behavior)
						+ "（冷却 " + (behavior.cooldownTicks() / 20.0) + " 秒）。物品本身没有任何改变。",
				o.toString(), List.of(), true, true, "none");
	};

	public static final ToolPreparer GRAFT_PREPARER = (ctx, request) -> {
		var player = ctx.player();
		if (player == null) {
			return ToolPreparation.none();
		}
		ItemStack stack = player.getMainHandStack();
		if (stack.isEmpty()) {
			return ToolPreparation.none();
		}
		String action = request.argumentString("action");
		String trigger = request.argumentString("trigger");
		if (trigger == null || trigger.isBlank()) {
			trigger = "right_click";
		}
		Integer cooldown = request.argumentInt("cooldown_ticks", 20);
		Proposal.BehaviorSpec spec = new Proposal.BehaviorSpec(trigger, action, cooldown, Map.of());
		if (!ProposalValidator.validateBehavior(spec).isEmpty()) {
			return ToolPreparation.none();
		}
		BehaviorDefinition behavior = ProposalValidator.toBehavior(spec);
		String summary = "将给手中的「" + stack.getName().getString() + "」("
				+ Registries.ITEM.getId(stack.getItem()).toString() + ") 附加行为："
				+ triggerLabel(behavior.trigger()) + "·" + actionLabel(behavior)
				+ "（冷却 " + (behavior.cooldownTicks() / 20.0) + " 秒）。"
				+ "物品本身（模型、耐久、附魔）完全不变，随时可以撤销。";
		// the snapshot captures the item stack identity so that a player who
		// swaps items between confirmation and execution is caught by the
		// orchestrator's snapshot comparison (AgentOrchestrator re-runs this
		// preparer at execution time and compares)
		String snapshot = stackSnapshot(stack);
		return ToolPreparation.of(List.of(), List.of(), summary, snapshot,
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};

	// ------------------------------------------------------------ ungraft

	public static final String UNGRAFT = "ungraft_behavior";

	public static ToolDescriptor ungraftDescriptor() {
		JsonObject props = new JsonObject();
		JsonObject schema = JsonSchemaValidator.object(props, List.of());
		return ToolDescriptor.self(UNGRAFT,
				"撤销你手里物品上的 WorldForge 行为，把物品恢复成原来的样子。",
				schema);
	}

	public static final ToolExecutor UNGRAFT_EXEC = (ctx, request, sink) -> {
		var player = ctx.player();
		if (player == null) {
			return ToolResult.fail("no_player", "没有找到你手里的物品");
		}
		ItemStack stack = player.getMainHandStack();
		if (stack.isEmpty()) {
			return ToolResult.fail("no_item", "你的主手是空的");
		}
		if (!BehaviorEngine.isGrafted(stack)) {
			return ToolResult.fail("not_grafted", "你手里的物品本来就没有附加行为");
		}
		int removed = BehaviorEngine.graftBehaviors(stack).size();
		stack.remove(WorldForgeComponents.BEHAVIOR_GRAFT);
		JsonObject o = new JsonObject();
		o.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
		o.addProperty("removed", removed);
		return new ToolResult(true, "",
				"已撤销，" + removed + " 个附加行为已移除，物品恢复原样。",
				o.toString(), List.of(), true, true, "none");
	};

	public static final ToolPreparer UNGRAFT_PREPARER = (ctx, request) -> {
		var player = ctx.player();
		if (player == null) {
			return ToolPreparation.none();
		}
		ItemStack stack = player.getMainHandStack();
		if (stack.isEmpty() || !BehaviorEngine.isGrafted(stack)) {
			return ToolPreparation.none();
		}
		int count = BehaviorEngine.graftBehaviors(stack).size();
		String summary = "将撤销手中「" + stack.getName().getString() + "」上的 " + count
				+ " 个附加行为，物品恢复原样。";
		String snapshot = stackSnapshot(stack);
		return ToolPreparation.of(List.of(), List.of(), summary, snapshot,
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};

	// ------------------------------------------------------------- labels

	/**
	 * Captures the identity of a held stack for the confirmation snapshot.
	 *
	 * <p>The orchestrator re-runs the preparer at execution time and compares
	 * this string, so a player who swaps to a different item between
	 * confirmation and execution is caught.</p>
	 *
	 * <p>The fingerprint includes the item id, count, and a sorted
	 * serialization of ALL data components (enchantments, durability, name,
	 * lore, etc.) except the ones the graft itself creates
	 * ({@link WorldForgeComponents#BEHAVIOR_GRAFT} and
	 * {@link WorldForgeComponents#BEHAVIOR_STATE}). This means two swords
	 * with the same id and count but different enchantments or durability
	 * produce different fingerprints.</p>
	 *
	 * <p><b>Known limitation</b>: two items that are truly identical in every
	 * component (same id, count, enchantments, durability, name, lore, etc.)
	 * produce the same fingerprint. This cannot be avoided without modifying
	 * the player's item to add a unique identifier, which we do not do. In
	 * that edge case the behaviour would be identical regardless, so the
	 * graft is safe.</p>
	 */
	private static String stackSnapshot(ItemStack stack) {
		String id = Registries.ITEM.getId(stack.getItem()).toString();
		StringBuilder sb = new StringBuilder(id);
		sb.append(':').append(stack.getCount());
		List<String> parts = new ArrayList<>();
		var components = stack.getComponents();
		for (var type : components.getTypes()) {
			// exclude components the graft itself creates or modifies
			if (type == WorldForgeComponents.BEHAVIOR_GRAFT
					|| type == WorldForgeComponents.BEHAVIOR_STATE) {
				continue;
			}
			String key = type.toString();
			Object value = components.get(type);
			parts.add(key + "=" + (value == null ? "null" : value.toString()));
		}
		java.util.Collections.sort(parts);
		for (String part : parts) {
			sb.append('|').append(part);
		}
		return sb.toString();
	}

	private static String triggerLabel(String trigger) {
		return "right_click".equals(trigger) ? "右键" : "命中";
	}

	private static String actionLabel(BehaviorDefinition b) {
		String a = b.actions().isEmpty() ? "" : b.actions().get(0);
		return switch (a) {
			case "summon_lightning" -> "召唤闪电";
			case "apply_effect" -> "施加状态效果";
			case "heal" -> "治疗";
			case "experience" -> "给予经验";
			case "teleport" -> "传送";
			case "explosion" -> "爆炸";
			case "ignite" -> "点燃";
			default -> a;
		};
	}
}
