package dev.worldforge.exporter;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.runtime.WorldForgeRuntimeManager;

import java.util.HashMap;
import java.util.Map;

/**
 * v0.5.3 P0-1: Abstraction for definition lookup during export.
 *
 * <p>Two implementations:</p>
 * <ul>
 *   <li>{@link #fromRuntime()} — queries live managers (plan creation, client thread only)</li>
 *   <li>{@link #fromSnapshot(ExportPlan)} — reads the plan's deep-copied definitions
 *       (background export threads MUST use this; never touches runtime managers)</li>
 * </ul>
 */
public interface DefinitionResolver {

	ItemDefinition getItem(String id);

	BlockDefinition getBlock(String id);

	/** Live resolver — only safe on the game thread while the world is running. */
	static DefinitionResolver fromRuntime() {
		return new DefinitionResolver() {
			@Override
			public ItemDefinition getItem(String id) {
				return WorldForgeRuntimeManager.get(id);
			}

			@Override
			public BlockDefinition getBlock(String id) {
				return BlockRuntimeManager.get(id);
			}
		};
	}

	/** Snapshot resolver — safe from any thread; reads only the immutable plan. */
	static DefinitionResolver fromSnapshot(ExportPlan plan) {
		// Index the plan's deep-copied definitions once; reads are then lock-free.
		Map<String, ItemDefinition> items = new HashMap<>();
		for (var def : plan.items()) {
			items.put(def.id(), def);
		}
		Map<String, BlockDefinition> blocks = new HashMap<>();
		for (var def : plan.blocks()) {
			blocks.put(def.id(), def);
		}
		return new DefinitionResolver() {
			@Override
			public ItemDefinition getItem(String id) {
				return items.get(id);
			}

			@Override
			public BlockDefinition getBlock(String id) {
				return blocks.get(id);
			}
		};
	}
}
