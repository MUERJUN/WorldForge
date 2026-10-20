package dev.worldforge.exporter;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.5.3 P0-1: Immutable export plan with deep-copied definitions.
 *
 * <p>A plan is a complete, self-contained statement of what will be exported:
 * mod metadata, deep-copied item/block definitions, the capability report the
 * user saw and (optionally) confirmed, and the confirmation flag.</p>
 *
 * <p>Creation rules:</p>
 * <ul>
 *   <li>{@link #create} must run on the game/client thread with valid world context —
 *       it resolves definitions through the live managers exactly once.</li>
 *   <li>After creation the plan holds NO live references: collections are copied,
 *       record fields are rebuilt into fresh immutable collections. Editing or
 *       deleting runtime definitions afterwards cannot affect an in-flight export.</li>
 *   <li>Background threads (executor, generator) read the plan via
 *       {@link DefinitionResolver#fromSnapshot} and never query runtime managers.</li>
 * </ul>
 */
public record ExportPlan(
		String modId,
		String modName,
		String version,
		String outputDir,
		List<ItemDefinition> items,
		List<BlockDefinition> blocks,
		ModExporter.CapabilityReport report,
		boolean degradedExportConfirmed,
		long createdAtMillis
) {

	/**
	 * Create a plan on the game thread. Resolves every selected ID once and
	 * deep-copies the definitions. Throws if a selected ID no longer resolves.
	 */
	public static ExportPlan create(String modId, String modName, String version, String outputDir,
			List<String> itemIds, List<String> blockIds) {
		var resolver = DefinitionResolver.fromRuntime();
		var items = new ArrayList<ItemDefinition>();
		for (String id : itemIds) {
			var def = resolver.getItem(id);
			if (def == null) {
				throw new IllegalStateException("物品定义不存在或已被删除: " + id);
			}
			items.add(deepCopy(def));
		}
		var blocks = new ArrayList<BlockDefinition>();
		for (String id : blockIds) {
			var def = resolver.getBlock(id);
			if (def == null) {
				throw new IllegalStateException("方块定义不存在或已被删除: " + id);
			}
			blocks.add(deepCopy(def));
		}
		// v0.5.4 P0: report derives from the SAME frozen copies that will be
		// exported — no second read of the live managers, so the report the
		// user sees can never diverge from the snapshot actually exported.
		var frozenItems = List.copyOf(items);
		var frozenBlocks = List.copyOf(blocks);
		var report = ModExporter.capabilityReportOf(frozenItems, frozenBlocks);
		return new ExportPlan(modId, modName, version, outputDir,
				frozenItems, frozenBlocks, report,
				false, System.currentTimeMillis());
	}

	/** Return a copy of this plan with degraded-export confirmation recorded. */
	public ExportPlan withDegradedConfirmation() {
		return new ExportPlan(modId, modName, version, outputDir, items, blocks, report,
				true, createdAtMillis);
	}

	/** The confirmed plan identity must match what the user saw. */
	public boolean sameContentAs(ExportPlan other) {
		return other != null
				&& modId.equals(other.modId)
				&& version.equals(other.version)
				&& java.util.Objects.equals(outputDir, other.outputDir)
				&& items.equals(other.items)
				&& blocks.equals(other.blocks);
	}

	// ------------------------------------------------------------- deep copy

	private static ItemDefinition deepCopy(ItemDefinition def) {
		List<BehaviorDefinition> behaviors = new ArrayList<>();
		for (var b : def.behaviors()) {
			behaviors.add(deepCopy(b));
		}
		// v0.5.5: attributes frozen via Map.copyOf (mutation rejected)
		Map<String, Double> attributes = def.attributes() == null
				? Map.of()
				: Map.copyOf(def.attributes());
		return new ItemDefinition(def.version(), def.id(), def.name(),
				def.description(), def.texture(), attributes, List.copyOf(behaviors));
	}

	private static BehaviorDefinition deepCopy(BehaviorDefinition b) {
		// v0.5.5: params are recursively frozen — Map.copyOf rejects mutation
		// AND strips any nested mutable collections by refusing them outright
		Map<String, Object> raw = b.parameters() == null ? Map.of() : b.parameters();
		Map<String, Object> frozen = new LinkedHashMap<>();
		for (var e : raw.entrySet()) {
			frozen.put(e.getKey(), freezeParamValue(e.getKey(), e.getValue()));
		}
		return new BehaviorDefinition(b.trigger(),
				List.copyOf(b.actions()), Map.copyOf(frozen), b.cooldownTicks());
	}

	/**
	 * v0.5.5: a parameter value is exportable only when it is a scalar
	 * (String / Number / Boolean) — those are immutable and safe to freeze.
	 * Anything else (List/Map/nested structures) cannot be guaranteed
	 * equivalent in generated code, so the plan creation rejects it loudly.
	 *
	 * <p>Known numeric parameters are validated HERE at plan creation —
	 * out-of-range/negative values fail fast instead of poisoning a build
	 * minutes later on a background thread.</p>
	 */
	private static Object freezeParamValue(String key, Object v) {
		if (v == null || v instanceof String || v instanceof Boolean) {
			return v;
		}
		if (v instanceof Number n) {
			// range-check known timing/level params at freeze time
			switch (key) {
				case "duration" -> {
					int i = n.intValue();
					if (i < 1 || i > 7_200_000) {
						throw new IllegalArgumentException("参数 duration 超出范围 [1, 7200000]: " + i);
					}
					return i;
				}
				case "amplifier" -> {
					int i = n.intValue();
					if (i < 0 || i > 255) {
						throw new IllegalArgumentException("参数 amplifier 超出范围 [0, 255]: " + i);
					}
					return i;
				}
				case "amount" -> {
					int i = n.intValue();
					if (i < 1 || i > 1000) {
						throw new IllegalArgumentException("参数 amount 超出范围 [1, 1000]: " + i);
					}
					return i;
				}
				default -> {
					return v;
				}
			}
		}
		throw new IllegalArgumentException(
				"行为参数包含不支持的可变/嵌套类型 " + v.getClass().getSimpleName()
						+ "（值: " + v + "）。导出仅支持字符串、数字和布尔值参数。");
	}

	// (BlockDefinition carries only immutable scalars + behavior list; the
	//  behavior list is deep-copied below so no mutable reference is shared.)
	private static BlockDefinition deepCopy(BlockDefinition def) {
		List<BehaviorDefinition> behaviors = new ArrayList<>();
		for (var b : def.behaviors()) {
			behaviors.add(deepCopy(b));
		}
		return new BlockDefinition(def.version(), def.id(), def.name(),
				def.description(), def.appearance(), def.category(), def.hardness(),
				List.copyOf(behaviors), def.tickIntervalTicks(),
				def.outputItemId(), def.outputCount());
	}
}
