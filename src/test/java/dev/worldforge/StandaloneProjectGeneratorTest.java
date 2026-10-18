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
 * Generates a standalone test project to a known path for manual runtime verification.
 * The project is generated at /tmp/wf-standalone/export/standalone_mod (or Windows equivalent).
 * Run `gradlew build` and `gradlew runGameTest` in that directory to verify the mod
 * loads in a Minecraft instance without WorldForge.
 */
class StandaloneProjectGeneratorTest {

	@Test
	void generateStandaloneProject() throws Exception {
		var exportDir = Path.of("build/standalone-export");
		Files.createDirectories(exportDir);
		// clean any previous generation
		try (var stream = Files.walk(exportDir)) {
			stream.sorted(java.util.Comparator.reverseOrder())
					.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
		}

		// setup stores with temp dirs
		var storeDir = Path.of("build/standalone-stores");
		Files.createDirectories(storeDir);
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(storeDir.resolve("bs")));
		WorldForgeRuntimeManager.attachStoreForTesting(
				new dev.worldforge.persistence.DefinitionStore(storeDir.resolve("is")));

		// create definitions
		var itemDef = new ItemDefinition(1, "worldforge:standalone_sword", "独立之剑",
				"从 WorldForge 导出的独立剑",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 7.0), List.of());
		WorldForgeRuntimeManager.createItem(itemDef);

		var blockDef = BlockDefinition.create("worldforge:standalone_block", "独立方块",
				"从 WorldForge 导出的独立方块",
				"minecraft:furnace", BlockDefinition.Category.DECORATION, 2.0,
				List.of(), 200, null, 0);
		BlockRuntimeManager.create(blockDef);

		var req = new ModExporter.ExportRequest(
				"standalone_mod", "Standalone Mod", "1.0.0", exportDir,
				List.of("worldforge:standalone_sword"),
				List.of("worldforge:standalone_block"));

		var errors = ModExporter.validate(req);
		assertTrue(errors.isEmpty(), "validation must pass: " + errors);

		var projectDir = exportDir.resolve("standalone_mod");
		var gen = new TemplateProjectGenerator(req, projectDir);
		gen.generate();
		gen.copyGradleWrapper(Path.of(".").toAbsolutePath());

		assertTrue(Files.exists(projectDir.resolve("build.gradle")), "build.gradle must exist");
		assertTrue(Files.exists(projectDir.resolve("settings.gradle")), "settings.gradle must exist");
		assertTrue(Files.exists(projectDir.resolve("gradlew.bat")), "gradlew.bat must exist");
		assertTrue(Files.exists(projectDir.resolve("src/main/java/standalone/mod/StandaloneModMod.java")),
				"main class must exist");
		assertTrue(Files.exists(projectDir.resolve("src/main/java/standalone/mod/ExportedModTest.java")),
				"GameTest class must exist");

		System.out.println("=== Standalone project generated at: " + projectDir.toAbsolutePath() + " ===");
		System.out.println("To verify: cd " + projectDir.toAbsolutePath() + " && gradlew.bat build && gradlew.bat runGameTest");

		BlockRuntimeManager.release();
		WorldForgeRuntimeManager.onServerStopped();
	}
}
