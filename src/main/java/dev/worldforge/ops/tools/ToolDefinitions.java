package dev.worldforge.ops.tools;

import dev.worldforge.ai.Proposal;
import dev.worldforge.ai.ProposalJson;
import dev.worldforge.ai.ProposalValidator;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.ops.ToolRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts WF-Ops tool arguments into validated WorldForge definitions.
 *
 * <p>Deliberately reuses {@link ProposalJson} and {@link ProposalValidator}:
 * the AI path must have EXACTLY the same whitelist, ranges and shape checks as
 * the legacy proposal path. A second, "AI-only" validation would inevitably
 * drift and become a hole.</p>
 */
public final class ToolDefinitions {

	private ToolDefinitions() {
	}

	/** Reads a string argument, or null. */
	public static String str(ToolRequest req, String key) {
		String v = req.argumentString(key);
		if (v == null) {
			return null;
		}
		v = v.trim();
		return v.isEmpty() ? null : v;
	}

	/** Reads a number argument, or null. */
	public static Double num(ToolRequest req, String key) {
		Object v = req.arguments().get(key);
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		if (v instanceof String s) {
			try {
				return Double.parseDouble(s.trim());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	/** Reads an integer argument, or null. */
	public static Integer intg(ToolRequest req, String key) {
		Double d = num(req, key);
		return d == null ? null : (int) Math.round(d);
	}

	/** Builds the behaviour list from the tool arguments. */
	public static List<Proposal.BehaviorSpec> behaviors(ToolRequest req, String key) {
		Object v = req.arguments().get(key);
		List<Proposal.BehaviorSpec> out = new ArrayList<>();
		if (!(v instanceof List<?> list)) {
			return out;
		}
		for (Object o : list) {
			if (!(o instanceof Map<?, ?> m)) {
				continue;
			}
			String trigger = strOf(m, "trigger");
			String action = strOf(m, "action");
			Object cd = m.get("cooldown_ticks");
			Integer cooldown = cd instanceof Number n ? n.intValue() : null;
			Map<String, Object> params = new java.util.LinkedHashMap<>();
			Object p = m.get("parameters");
			if (p instanceof Map<?, ?> pm) {
				pm.forEach((k, val) -> {
					if (val instanceof Number num) {
						params.put(String.valueOf(k), num.doubleValue());
					} else if (val instanceof Boolean b) {
						params.put(String.valueOf(k), b);
					} else if (val != null) {
						params.put(String.valueOf(k), String.valueOf(val));
					}
				});
			}
			out.add(new Proposal.BehaviorSpec(trigger, action, cooldown, params));
		}
		return out;
	}

	private static String strOf(Map<?, ?> m, String key) {
		Object v = m.get(key);
		return v == null ? null : String.valueOf(v).trim();
	}

	/**
	 * Builds a validated item definition for a create operation.
	 *
	 * @return the definition, or an error message prefixed with {@code !}
	 */
	public static Outcome<ItemDefinition> createItem(ToolRequest req) {
		Proposal.Create c = new Proposal.Create(
				str(req, "id"),
				str(req, "name"),
				str(req, "description"),
				str(req, "texture"),
				num(req, "damage"),
				intg(req, "max_durability"),
				num(req, "attack_speed"),
				behaviors(req, "behaviors"));
		var errors = ProposalValidator.validate(c);
		if (!errors.ok()) {
			return Outcome.fail("参数校验失败: " + errors.joined());
		}
		return Outcome.ok(ProposalJson.toDefinition(c));
	}

	/**
	 * Builds a validated, merged item definition for an update operation.
	 *
	 * @param current the definition as it exists NOW
	 */
	public static Outcome<ItemDefinition> updateItem(ToolRequest req, ItemDefinition current) {
		List<Proposal.BehaviorSpec> setBehaviors = req.arguments().containsKey("set_behaviors")
				? behaviors(req, "set_behaviors") : null;
		List<Proposal.BehaviorSpec> addBehaviors = req.arguments().containsKey("add_behaviors")
				? behaviors(req, "add_behaviors") : null;
		List<String> removeTriggers = null;
		Object rt = req.arguments().get("remove_triggers");
		if (rt instanceof List<?> list) {
			removeTriggers = new ArrayList<>();
			for (Object o : list) {
				if (o != null) {
					removeTriggers.add(String.valueOf(o));
				}
			}
		}
		Proposal.Update u = new Proposal.Update(
				str(req, "id"),
				str(req, "name"),
				str(req, "description"),
				str(req, "texture"),
				num(req, "damage"),
				intg(req, "max_durability"),
				setBehaviors, addBehaviors, removeTriggers);
		var errors = ProposalValidator.validate(u);
		if (!errors.ok()) {
			return Outcome.fail("参数校验失败: " + errors.joined());
		}
		return Outcome.ok(ProposalJson.mergeUpdate(current, u));
	}

	/**
	 * Builds a validated block definition for a create operation.
	 */
	public static Outcome<BlockDefinition> createBlock(ToolRequest req) {
		Proposal.CreateBlock cb = new Proposal.CreateBlock(
				str(req, "id"),
				str(req, "name"),
				str(req, "description"),
				str(req, "appearance"),
				str(req, "category"),
				num(req, "hardness"),
				intg(req, "tick_interval_ticks"),
				str(req, "output_item_id"),
				intg(req, "output_count"),
				behaviors(req, "behaviors"));
		var errors = ProposalValidator.validate(cb);
		if (!errors.ok()) {
			return Outcome.fail("参数校验失败: " + errors.joined());
		}
		return Outcome.ok(toBlockDefinition(cb));
	}

	/**
	 * Builds a validated, merged block definition for an update operation.
	 */
	public static Outcome<BlockDefinition> updateBlock(ToolRequest req, BlockDefinition current) {
		Proposal.UpdateBlock ub = new Proposal.UpdateBlock(
				str(req, "id"),
				str(req, "name"),
				str(req, "description"),
				str(req, "appearance"),
				str(req, "category"),
				num(req, "hardness"),
				intg(req, "tick_interval_ticks"),
				str(req, "output_item_id"),
				intg(req, "output_count"));
		var errors = ProposalValidator.validate(ub);
		if (!errors.ok()) {
			return Outcome.fail("参数校验失败: " + errors.joined());
		}
		return Outcome.ok(new BlockDefinition(
				current.version(), current.id(),
				ub.name() == null ? current.name() : ub.name(),
				ub.description() == null ? current.description() : ub.description(),
				ub.appearance() == null ? current.appearance() : ub.appearance(),
				ub.category() == null ? current.category() : parseCategory(ub.category()),
				ub.hardness() == null ? current.hardness() : ub.hardness(),
				current.behaviors(),
				ub.tickIntervalTicks() == null ? current.tickIntervalTicks() : ub.tickIntervalTicks(),
				ub.outputItemId() == null ? current.outputItemId() : ub.outputItemId(),
				ub.outputCount() == null ? current.outputCount() : ub.outputCount()));
	}

	/** Mirrors {@code AgentBridge.executeBlockProposal}'s category parsing. */
	public static BlockDefinition.Category parseCategory(String category) {
		try {
			return BlockDefinition.Category.valueOf(
					category == null ? "DECORATION" : category.toUpperCase(java.util.Locale.ROOT));
		} catch (Exception e) {
			return BlockDefinition.Category.DECORATION;
		}
	}

	/** Builds a block definition record from a validated create proposal. */
	public static BlockDefinition toBlockDefinition(Proposal.CreateBlock cb) {
		return new BlockDefinition(
				BlockDefinition.CURRENT_VERSION, cb.id(), cb.name(),
				cb.description() == null ? "" : cb.description(),
				cb.appearance() == null ? dev.worldforge.block.BlockCatalog.DEFAULT_APPEARANCE : cb.appearance(),
				parseCategory(cb.category()),
				cb.hardness() == null ? 2.0 : cb.hardness(),
				cb.behaviors() == null ? List.of()
						: cb.behaviors().stream().map(ProposalValidator::toBehavior).toList(),
				cb.tickIntervalTicks() == null ? 200 : cb.tickIntervalTicks(),
				cb.outputItemId(), cb.outputCount() == null ? 1 : cb.outputCount());
	}

	/** Snapshot used for the "did the target change?" check. */
	public static String snapshotOf(ItemDefinition def) {
		return def == null ? "" : dev.worldforge.persistence.DefinitionStore.toJson(def).toString();
	}

	/** Snapshot used for the "did the target change?" check. */
	public static String snapshotOf(BlockDefinition def) {
		return def == null ? "" : BlockDefinitionStore.toJson(def).toString();
	}

	/** A value that is either a result or an error message. */
	public record Outcome<T>(boolean ok, T value, String error) {

		public static <T> Outcome<T> ok(T value) {
			return new Outcome<>(true, value, "");
		}

		public static <T> Outcome<T> fail(String error) {
			return new Outcome<>(false, null, error);
		}
	}
}
