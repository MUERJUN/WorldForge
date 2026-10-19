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
		Map<String, Double> attributes = def.attributes() == null
				? Map.of()
				: new LinkedHashMap<>(def.attributes());
		return new ItemDefinition(def.version(), def.id(), def.name(),
				def.description(), def.texture(), attributes, List.copyOf(behaviors));
	}

	private static BehaviorDefinition deepCopy(BehaviorDefinition b) {
		Map<String, Object> params = b.parameters() == null
				? Map.of()
				: new LinkedHashMap<>(b.parameters());
		return new BehaviorDefinition(b.trigger(),
				List.copyOf(b.actions()), params, b.cooldownTicks());
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
