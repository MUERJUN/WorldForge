package dev.worldforge.ai;

import java.util.List;
import java.util.Map;

/**
 * A structured change proposal produced by the model (or any future agent).
 *
 * <p>Proposals are INTENTS, never effects: nothing here touches world state.
 * A proposal must pass {@link ProposalValidator} (whitelist + ranges + shape),
 * then be explicitly confirmed by the player, then the server re-checks
 * permission and the base version before executing via WorldForgeTools
 * (spec 五: two-phase generate/confirm).</p>
 */
public sealed interface Proposal {

	/** create a brand-new virtual item */
	record Create(String id, String name, String description, String texture,
			Double damage, Integer maxDurability, Double attackSpeed,
			List<BehaviorSpec> behaviors) implements Proposal {
		@Override
		public String kind() {
			return "create";
		}
	}

	/**
	 * Delta-modify an existing item. Null fields mean "keep current value";
	 * behaviors REPLACE only when {@code setBehaviors} is true (an explicit
	 * full re-specification), otherwise {@code addBehaviors}/{@code removeTriggers}
	 * patch the existing set (spec 四.2: 不得无故覆盖其他属性和技能).
	 */
	record Update(String id, String name, String description, String texture,
			Double damage, Integer maxDurability,
			List<BehaviorSpec> setBehaviors, List<BehaviorSpec> addBehaviors,
			List<String> removeTriggers) implements Proposal {
		@Override
		public String kind() {
			return "update";
		}
	}

	/** delete a definition (player confirmation mandatory; tombstone semantics preserved) */
	record Delete(String id) implements Proposal {
		@Override
		public String kind() {
			return "delete";
		}
	}

	/**
	 * v0.4.0: block proposals are a SEPARATE type from item proposals, so a
	 * virtual id that exists as both an item and a block can never be edited by
	 * the wrong pipeline (spec 阶段三: 不得因虚拟 ID 相同而修改错误类型).
	 */
	record CreateBlock(String id, String name, String description, String appearance,
			String category, Double hardness, Integer tickIntervalTicks,
			String outputItemId, Integer outputCount, List<BehaviorSpec> behaviors) implements Proposal {
		@Override
		public String kind() {
			return "create_block";
		}
	}

	record UpdateBlock(String id, String name, String description, String appearance,
			String category, Double hardness, Integer tickIntervalTicks,
			String outputItemId, Integer outputCount) implements Proposal {
		@Override
		public String kind() {
			return "update_block";
		}
	}

	record DeleteBlock(String id) implements Proposal {
		@Override
		public String kind() {
			return "delete_block";
		}
	}

	/** read-only query answered without world mutation */
	record Query(String what) implements Proposal {
		@Override
		public String kind() {
			return "query";
		}
	}

	/** one behavior rule inside a proposal (model-facing shape, validated before use) */
	record BehaviorSpec(String trigger, String action, Integer cooldownTicks, Map<String, Object> parameters) {
	}

	String kind();
}
