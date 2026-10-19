package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.exporter.ModExporter;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.1 Section 二: Registration ID conflict detection.
 * BlockItem also occupies the ITEM registry, so a standalone item
 * and a block item with the same native ID must be rejected.
 */
class ExportIdConflictTest {

	@TempDir
	Path tempDir;

	@Test
	void rejectsItemAndBlockWithSameNativeId() throws Exception {
		setupStore();
		// Item "worldforge:shared_name" and Block "worldforge:shared_name"
		// both produce native ID "shared_name" in the ITEM registry
		var itemDef = new ItemDefinition(1, "worldforge:shared_name", "Shared Item",
				"", TextureSource.vanilla("minecraft:item/stick"),
				Map.of(), List.of());
		WorldForgeRuntimeManager.createItem(itemDef);

		var blockDef = BlockDefinition.create("worldforge:shared_name", "Shared Block",
				"", "minecraft:furnace", BlockDefinition.Category.DECORATION, 2.0,
				List.of(), 0, null, 0);
		BlockRuntimeManager.create(blockDef);

		var req = new ModExporter.ExportRequest(
				"conflict_mod", "Conflict Mod", "1.0.0", tempDir,
				List.of("worldforge:shared_name"),
				List.of("worldforge:shared_name"));

		var errors = ModExporter.validate(req);
		assertTrue(errors.stream().anyMatch(e -> e.contains("冲突") && e.contains("shared_name")),
				"Must detect ID conflict between item and block item: " + errors);

		cleanup();
	}

	@Test
	void allowsDistinctNativeIds() throws Exception {
		setupStore();
		var itemDef = new ItemDefinition(1, "worldforge:my_sword", "Sword",
				"", TextureSource.vanilla("minecraft:item/stick"),
				Map.of(), List.of());
		WorldForgeRuntimeManager.createItem(itemDef);

		var blockDef = BlockDefinition.create("worldforge:my_block", "Block",
				"", "minecraft:furnace", BlockDefinition.Category.DECORATION, 2.0,
				List.of(), 0, null, 0);
		BlockRuntimeManager.create(blockDef);

		var req = new ModExporter.ExportRequest(
				"ok_mod", "OK Mod", "1.0.0", tempDir,
				List.of("worldforge:my_sword"),
				List.of("worldforge:my_block"));

		var errors = ModExporter.validate(req);
		assertFalse(errors.stream().anyMatch(e -> e.contains("冲突")),
				"Distinct IDs must not conflict: " + errors);

		cleanup();
	}

	@Test
	void rejectsDuplicateItemIds() throws Exception {
		setupStore();
		var itemDef = new ItemDefinition(1, "worldforge:dup_item", "Dup",
				"", TextureSource.vanilla("minecraft:item/stick"),
				Map.of(), List.of());
		WorldForgeRuntimeManager.createItem(itemDef);

		var req = new ModExporter.ExportRequest(
				"dup_mod", "Dup Mod", "1.0.0", tempDir,
				List.of("worldforge:dup_item", "worldforge:dup_item"),
				List.of());

		var errors = ModExporter.validate(req);
		assertTrue(errors.stream().anyMatch(e -> e.contains("冲突") && e.contains("dup_item")),
				"Must detect duplicate item ID: " + errors);

		cleanup();
	}

	private void setupStore() {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir.resolve("bs")));
		WorldForgeRuntimeManager.attachStoreForTesting(
				new dev.worldforge.persistence.DefinitionStore(tempDir.resolve("is")));
	}

	private void cleanup() {
		BlockRuntimeManager.release();
		WorldForgeRuntimeManager.onServerStopped();
	}
}
