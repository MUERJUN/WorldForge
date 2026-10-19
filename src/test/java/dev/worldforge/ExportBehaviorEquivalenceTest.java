package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.exporter.ModExporter;
import dev.worldforge.exporter.TemplateProjectGenerator;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.1 P0-1: Regression test proving that v0.5.0 exporter marks actions
 * as supported but the generated code does NOT implement them.
 *
 * <p>This test must pass before and after the fix. Before the fix, it
 * demonstrates the "silent feature loss" problem. After the fix, the
 * generated code must contain real behavior implementations.</p>
 */
class ExportBehaviorEquivalenceTest {

	@TempDir
	Path tempDir;

	@Test
	void generatedItemWithLightningActionContainsBehaviorCode() throws Exception {
		setupStore();
		// Create an item with "summon_lightning" action — claimed supported by v0.5.0
		var behavior = new dev.worldforge.definition.BehaviorDefinition(
				"on_use", List.of("summon_lightning"), Map.of(), 100);
		var itemDef = new ItemDefinition(1, "worldforge:thunder_sword", "雷霆之剑",
				"一把能召唤闪电的剑",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 7.0), List.of(behavior));
		WorldForgeRuntimeManager.createItem(itemDef);

		var req = new ModExporter.ExportRequest(
				"thunder_mod", "Thunder Mod", "1.0.0", tempDir,
				List.of("worldforge:thunder_sword"), List.of());

		// v0.5.0 marks summon_lightning as supported
		var warnings = ModExporter.checkUnsupported(req);
		assertFalse(warnings.stream().anyMatch(w -> w.contains("summon_lightning")),
				"summon_lightning should be supported, not warned about");

		// Generate the project
		var projectDir = tempDir.resolve("thunder_mod");
		var gen = new TemplateProjectGenerator(req, projectDir);
		gen.generate();

		// Read the generated custom item class — it must contain behavior code
		var itemClassFile = projectDir.resolve("src/main/java/thunder/mod/ThunderSwordItem.java");
		assertTrue(Files.exists(itemClassFile), "Custom item class must be generated for items with behaviors");
		var itemClass = Files.readString(itemClassFile);

		// P0-1: The generated code MUST contain actual behavior implementation
		assertTrue(itemClass.contains("LIGHTNING_BOLT") || itemClass.contains("lightning"),
				"Generated item code must summon lightning. "
				+ "Generated ThunderSwordItem.java:\n" + itemClass);
		assertTrue(itemClass.contains("COOLDOWN_TICKS") || itemClass.contains("cooldown"),
				"Generated item code must contain cooldown logic. "
				+ "Generated ThunderSwordItem.java:\n" + itemClass);

		cleanup();
	}

	@Test
	void generatedBlockWithCooldownContainsCooldownCode() throws Exception {
		setupStore();
		var behavior = new dev.worldforge.definition.BehaviorDefinition(
				"on_use", List.of("apply_effect"), Map.of("effect", "speed"), 200);
		var blockDef = BlockDefinition.create("worldforge:magic_device", "魔法装置",
				"一个有冷却的魔法装置",
				"minecraft:furnace", BlockDefinition.Category.DECORATION, 2.0,
				List.of(behavior), 200, null, 0);
		BlockRuntimeManager.create(blockDef);

		var req = new ModExporter.ExportRequest(
				"magic_mod", "Magic Mod", "1.0.0", tempDir,
				List.of(), List.of("worldforge:magic_device"));

		var warnings = ModExporter.checkUnsupported(req);
		assertFalse(warnings.stream().anyMatch(w -> w.contains("apply_effect")),
				"apply_effect should be supported");

		var projectDir = tempDir.resolve("magic_mod");
		var gen = new TemplateProjectGenerator(req, projectDir);
		gen.generate();

		var blockClassFile = projectDir.resolve("src/main/java/magic/mod/MagicDeviceBlock.java");
		assertTrue(Files.exists(blockClassFile), "Custom block class must be generated for blocks with behaviors");
		var blockClass = Files.readString(blockClassFile);

		// P0-1: Generated block code MUST contain cooldown logic
		assertTrue(blockClass.contains("COOLDOWN_TICKS") || blockClass.contains("cooldown"),
				"Generated block code must contain cooldown implementation. "
				+ "Generated MagicDeviceBlock.java:\n" + blockClass);
		assertTrue(blockClass.contains("apply_effect") || blockClass.contains("STATUS_EFFECT")
						|| blockClass.contains("addStatusEffect"),
				"Generated block code must contain apply_effect implementation. "
				+ "Generated MagicDeviceBlock.java:\n" + blockClass);

		cleanup();
	}

	@Test
	void exportReportListsRetainedAndLostCapabilities() throws Exception {
		setupStore();
		var behavior = new dev.worldforge.definition.BehaviorDefinition(
				"on_use", List.of("summon_lightning"), Map.of(), 100);
		var itemDef = new ItemDefinition(1, "worldforge:thunder_sword", "雷霆之剑",
				"", TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of(), List.of(behavior));
		WorldForgeRuntimeManager.createItem(itemDef);

		var req = new ModExporter.ExportRequest(
				"thunder_mod", "Thunder Mod", "1.0.0", tempDir,
				List.of("worldforge:thunder_sword"), List.of());

		// The export result must include a capability report
		var report = ModExporter.buildCapabilityReport(req);
		assertNotNull(report, "capability report must not be null");
		assertFalse(report.retained().isEmpty(),
				"retained capabilities must list summon_lightning");
		assertTrue(report.retained().stream().anyMatch(c -> c.contains("summon_lightning")),
				"summon_lightning must be in retained list");

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
