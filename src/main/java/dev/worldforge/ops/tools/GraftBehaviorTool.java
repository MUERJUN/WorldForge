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
		String snapshot = stackSnapshot(stack, ctx.server());
		return ToolPreparation.of(List.of(), List.of(), summary, snapshot,
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};

	// ------------------------------------------------------------ ungraft

	public static final String UNGRAFT = "ungraft_behavior";

	public static ToolDescriptor ungraftDescriptor() {
		JsonObject props = new JsonObject();
		// v0.7.4: single-behaviour undo. Omit both fields to remove the ONLY
		// behaviour when exactly one is present; specify behavior_index to
		// remove one of several; specify all=true to remove everything.
		props.add("behavior_index", JsonSchemaValidator.prop("integer",
				"要撤销的行为在当前列表中的序号（0 开始）"));
		props.add("all", JsonSchemaValidator.prop("boolean", "撤销全部行为"));
		JsonObject schema = JsonSchemaValidator.object(props, List.of());
		return ToolDescriptor.self(UNGRAFT,
				"撤销你手里物品上的 WorldForge 行为。默认撤销唯一的一个行为；"
						+ "有多个行为时必须指定 behavior_index（只撤销那一个）或 all=true（全部撤销）。"
						+ "不影响原版附魔、耐久、名称或其它模组的数据。",
				schema);
	}

	/** Resolves which behaviour index the request targets, or -1 when ambiguous. */
	private static int resolveUngraftIndex(ToolRequest request, List<BehaviorDefinition> behaviors) {
		if (wantsAll(request)) {
			return 0; // "all" is checked separately
		}
		Integer idx = request.argumentInt("behavior_index", -1);
		if (idx != null && idx >= 0) {
			return idx;
		}
		// no explicit choice: only unambiguous when exactly one behaviour exists
		return behaviors.size() == 1 ? 0 : -1;
	}

	private static boolean wantsAll(ToolRequest request) {
		Object allRaw = request.arguments().get("all");
		return allRaw instanceof Boolean b && b;
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
		// re-read the CURRENT behaviour set — the confirmation snapshot already
		// proved it did not change between confirm and execute, but the
		// executor still validates the index against what it actually finds
		List<BehaviorDefinition> behaviors =
				new ArrayList<>(BehaviorEngine.graftBehaviors(stack));

		boolean all = wantsAll(request);
		int idx = resolveUngraftIndex(request, behaviors);
		if (!all && idx < 0) {
			return ToolResult.fail("ambiguous_target",
					"这个物品有 " + behaviors.size() + " 个附加行为。请指定 behavior_index（只撤销一个）"
							+ "或 all=true（全部撤销）。当前列表：" + behaviorListLabel(behaviors));
		}
		if (!all && idx >= behaviors.size()) {
			return ToolResult.fail("invalid_index",
					"序号 " + idx + " 不存在，当前只有 " + behaviors.size() + " 个行为："
							+ behaviorListLabel(behaviors));
		}

		if (all) {
			String removedLabel = behaviorListLabel(behaviors);
			stack.remove(WorldForgeComponents.BEHAVIOR_GRAFT);
			JsonObject o = new JsonObject();
			o.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
			o.addProperty("removed", behaviors.size());
			o.addProperty("scope", "all");
			return new ToolResult(true, "",
					"已撤销全部 " + behaviors.size() + " 个附加行为（" + removedLabel + "），物品恢复原样。",
					o.toString(), List.of(), true, true, "none");
		}
		// single-behaviour undo: rebuild the list WITHOUT the target behaviour,
		// touching nothing else on the stack
		BehaviorDefinition removed = behaviors.remove(idx);
		if (behaviors.isEmpty()) {
			stack.remove(WorldForgeComponents.BEHAVIOR_GRAFT);
		} else {
			stack.set(WorldForgeComponents.BEHAVIOR_GRAFT, new BehaviorGraft(behaviors));
		}
		JsonObject o = new JsonObject();
		o.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
		o.addProperty("removed", 1);
		o.addProperty("scope", "single");
		o.addProperty("index", idx);
		return new ToolResult(true, "",
				"已撤销 1 个附加行为（" + behaviorLabel(removed) + "），其余 "
						+ behaviors.size() + " 个行为保留。物品其它数据完全不变。",
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
		List<BehaviorDefinition> behaviors =
				new ArrayList<>(BehaviorEngine.graftBehaviors(stack));
		boolean all = wantsAll(request);
		int idx = resolveUngraftIndex(request, behaviors);

		String summary;
		if (all) {
			summary = "将撤销手中「" + stack.getName().getString() + "」上的全部 "
					+ behaviors.size() + " 个附加行为（" + behaviorListLabel(behaviors) + "），物品恢复原样。";
		} else if (idx >= 0 && idx < behaviors.size()) {
			summary = "将仅撤销手中「" + stack.getName().getString() + "」的第 " + idx
					+ " 个行为（" + behaviorLabel(behaviors.get(idx)) + "），其余 "
					+ (behaviors.size() - 1) + " 个行为保留。";
		} else {
			// ambiguous (several behaviours, no index/all) or out of range:
			// refuse at preparation so the model can narrow the request
			return ToolPreparation.none();
		}
		String snapshot = stackSnapshot(stack, ctx.server());
		return ToolPreparation.of(List.of(), List.of(), summary, snapshot,
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};

	/** Human-readable one-line label for one behaviour. */
	private static String behaviorLabel(BehaviorDefinition b) {
		return triggerLabel(b.trigger()) + "·" + actionLabel(b)
				+ "（冷却 " + (b.cooldownTicks() / 20.0) + " 秒）";
	}

	/** Human-readable numbered list of behaviours. */
	private static String behaviorListLabel(List<BehaviorDefinition> behaviors) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < behaviors.size(); i++) {
			if (i > 0) {
				sb.append("、");
			}
			sb.append('[').append(i).append(']').append(behaviorLabel(behaviors.get(i)));
		}
		return sb.toString();
	}

	// ------------------------------------------------------------- labels

	/**
	 * Captures the identity of a held stack for the confirmation snapshot.
	 *
	 * <p>v0.7.4 redesign. The old fingerprint (item id + count + toString of
	 * every component except the graft) had two defects: a change to an
	 * EXISTING graft during the confirmation window was invisible (the graft
	 * component was excluded), and {@code toString()} is not a stable or
	 * collision-free serialisation. The new binding combines:</p>
	 *
	 * <ol>
	 *   <li><b>instance identity</b> — the identity hash of the server-side
	 *       ItemStack object the player is actually holding. A swap to a
	 *       DIFFERENT stack object is refused even when the content is
	 *       identical: "same behaviour effect" does not mean "same target";</li>
	 *   <li><b>slot</b> — the tool always binds to the main hand, so the slot
	 *       is implied by the preparer reading {@code getMainHandStack()};</li>
	 *   <li><b>content fingerprint</b> — the full component map serialised
	 *       with Minecraft's own NBT encoder ({@link ItemStack#encode}),
	 *       SHA-256 hashed. The GRAFT component is INCLUDED (a change to the
	 *       behaviour list during the confirmation window is caught); the
	 *       BEHAVIOR_STATE component is EXCLUDED so a normal cooldown tick
	 *       does not invalidate a legitimate confirmation.</li>
	 * </ol>
	 *
	 * <p>When NBT serialisation fails (a broken custom component), the method
	 * falls back to an ordered type+value listing — less canonical, but still
	 * deterministic within one server session, and it never throws.</p>
	 *
	 * <p>Any difference in ANY of the three parts changes the snapshot string,
	 * and the orchestrator then refuses the operation and asks the player to
	 * reconfirm. This is deliberately fail-closed.</p>
	 */
	private static String stackSnapshot(ItemStack stack, net.minecraft.server.MinecraftServer server) {
		// 1. instance identity: two stacks with identical content are still
		//    different targets
		int identity = System.identityHashCode(stack);

		// 2. content fingerprint over Minecraft's own serialisation,
		//    including the graft, excluding the cooldown state
		ItemStack copy = stack.copy();
		copy.remove(WorldForgeComponents.BEHAVIOR_STATE);
		String content;
		try {
			var registryLookup = server == null ? null : server.getRegistryManager();
			if (registryLookup != null) {
				net.minecraft.nbt.NbtElement nbt = copy.encode(registryLookup);
				content = sha256Hex(nbt.toString());
			} else {
				content = orderedListingHash(copy);
			}
		} catch (Exception e) {
			// a broken custom component must not break the confirmation gate
			content = orderedListingHash(copy);
		}
		return identity + ":" + content;
	}

	/** Deterministic fallback when the NBT encoder fails for a component. */
	private static String orderedListingHash(ItemStack stack) {
		List<String> parts = new ArrayList<>();
		var components = stack.getComponents();
		for (var type : components.getTypes()) {
			if (type == WorldForgeComponents.BEHAVIOR_STATE) {
				continue; // cooldown changes are normal, never a target change
			}
			Object value = components.get(type);
			parts.add(type.toString() + "=" + (value == null ? "null" : value.toString()));
		}
		java.util.Collections.sort(parts);
		return sha256Hex(String.join("|", parts));
	}

	/** SHA-256 hex of a UTF-8 string (lowercase, 64 chars). */
	private static String sha256Hex(String s) {
		try {
			var md = java.security.MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			// SHA-256 is mandated for every Java platform; this cannot happen
			return String.valueOf(s.hashCode());
		}
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
