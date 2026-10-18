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
 * v0.5.0: Test that the template generator produces a valid Fabric project.
 */
class TemplateGenerationTest {

	@TempDir
	Path tempDir;

	private void setupStore() {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir.resolve("blockstore")));
		WorldForgeRuntimeManager.attachStoreForTesting(
				new dev.worldforge.persistence.DefinitionStore(tempDir.resolve("itemstore")));
	}

	@Test
	void generatesCompleteProject() throws Exception {
		setupStore();
		// create a test item definition
		var itemDef = new ItemDefinition(1, "worldforge:test_sword", "测试剑",
				"一把测试剑", TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 7.0), List.of());
		WorldForgeRuntimeManager.createItem(itemDef);

		// create a test block definition
		var blockDef = BlockDefinition.create("worldforge:test_block", "测试方块", "",
				"minecraft:furnace", BlockDefinition.Category.DECORATION, 2.0,
				List.of(), 200, null, 0);
		BlockRuntimeManager.create(blockDef);

		var req = new ModExporter.ExportRequest(
				"test_mod", "Test Mod", "1.0.0", tempDir.resolve("export"),
				List.of("worldforge:test_sword"),
				List.of("worldforge:test_block"));

		var errors = ModExporter.validate(req);
		assertTrue(errors.isEmpty(), "validation must pass: " + errors);

		var projectDir = tempDir.resolve("export").resolve("test_mod");
		var gen = new TemplateProjectGenerator(req, projectDir);
		gen.generate();

		// verify project structure
		assertTrue(Files.exists(projectDir.resolve("build.gradle")), "build.gradle must exist");
		assertTrue(Files.exists(projectDir.resolve("settings.gradle")), "settings.gradle must exist");
		assertTrue(Files.exists(projectDir.resolve("gradle.properties")), "gradle.properties must exist");

		var res = projectDir.resolve("src/main/resources");
		assertTrue(Files.exists(res.resolve("fabric.mod.json")), "fabric.mod.json must exist");

		var src = projectDir.resolve("src/main/java/test/mod");
		assertTrue(Files.exists(src.resolve("TestModMod.java")), "main class must exist");
		assertTrue(Files.exists(src.resolve("ModItems.java")), "ModItems must exist");
		assertTrue(Files.exists(src.resolve("ModBlocks.java")), "ModBlocks must exist");

		// verify lang file
		var lang = res.resolve("assets/test_mod/lang/en_us.json");
		assertTrue(Files.exists(lang), "lang file must exist");
		var langContent = Files.readString(lang);
		assertTrue(langContent.contains("test_sword"), "lang must contain item name");
		assertTrue(langContent.contains("test_block"), "lang must contain block name");

		// verify fabric.mod.json
		var modJson = Files.readString(res.resolve("fabric.mod.json"));
		assertTrue(modJson.contains("\"test_mod\""), "fabric.mod.json must contain mod ID");
		assertTrue(modJson.contains("Test Mod"), "fabric.mod.json must contain mod name");

		// verify build.gradle
		var buildGradle = Files.readString(projectDir.resolve("build.gradle"));
		assertTrue(buildGradle.contains("fabric-loom"), "build.gradle must use fabric-loom");
		assertTrue(buildGradle.contains("minecraft_version"), "build.gradle must reference MC version");

		// verify gradle.properties
		var props = Files.readString(projectDir.resolve("gradle.properties"));
		assertTrue(props.contains("minecraft_version=1.21.1"), "must target MC 1.21.1");
		assertTrue(props.contains("mod_version=1.0.0"), "must have correct version");

		// verify model files
		var itemModel = res.resolve("assets/test_mod/models/item/test_sword.json");
		assertTrue(Files.exists(itemModel), "item model must exist");
		var modelContent = Files.readString(itemModel);
		assertTrue(modelContent.contains("diamond_sword"), "item model must reference vanilla texture");

		var blockModel = res.resolve("assets/test_mod/models/block/test_block.json");
		assertTrue(Files.exists(blockModel), "block model must exist");
		var blockstate = res.resolve("assets/test_mod/blockstates/test_block.json");
		assertTrue(Files.exists(blockstate), "blockstate must exist");

		// verify no secrets leaked
		var allFiles = Files.walk(projectDir).filter(Files::isRegularFile).toList();
		for (var f : allFiles) {
			var content = Files.readString(f);
			assertFalse(content.contains("apiKey"), "no API keys in generated files: " + f);
			assertFalse(content.contains("agent.json"), "no agent.json reference: " + f);
		}

		BlockRuntimeManager.release();
		WorldForgeRuntimeManager.onServerStopped();
	}

	@Test
	void generatedJavaIsSyntacticallyValid() throws Exception {
		setupStore();
		var itemDef = new ItemDefinition(1, "worldforge:my_item", "我的物品",
				"", TextureSource.vanilla("minecraft:item/iron_ingot"),
				Map.of(), List.of());
		WorldForgeRuntimeManager.createItem(itemDef);

		var req = new ModExporter.ExportRequest(
				"my_export", "My Export", "2.1.0", tempDir.resolve("export"),
				List.of("worldforge:my_item"), List.of());

		var projectDir = tempDir.resolve("export").resolve("my_export");
		var gen = new TemplateProjectGenerator(req, projectDir);
		gen.generate();

		// verify the main class has proper package and class declaration
		var mainClass = Files.readString(projectDir.resolve("src/main/java/my/export/MyExportMod.java"));
		assertTrue(mainClass.startsWith("package my.export;"), "must have correct package");
		assertTrue(mainClass.contains("implements ModInitializer"), "must implement ModInitializer");
		assertTrue(mainClass.contains("onInitialize()"), "must have onInitialize");

		// verify ModItems has registry calls
		var modItems = Files.readString(projectDir.resolve("src/main/java/my/export/ModItems.java"));
		assertTrue(modItems.contains("Registry.register"), "must register items");
		assertTrue(modItems.contains("Identifier.of"), "must use Identifier.of");
		assertTrue(modItems.contains("\"my_export\""), "must use mod ID namespace");
		assertTrue(modItems.contains("\"my_item\""), "must use item's native ID");

		BlockRuntimeManager.release();
		WorldForgeRuntimeManager.onServerStopped();
	}
}
