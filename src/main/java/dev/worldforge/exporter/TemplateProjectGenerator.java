package dev.worldforge.exporter;

import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates a complete Fabric 1.21.1 mod project from WorldForge definitions.
 *
 * <p>All generated code comes from controlled templates — no user input is
 * ever inserted as raw Java. IDs, names, and parameters are validated and
 * escaped before insertion.</p>
 */
public final class TemplateProjectGenerator {

	private final ModExporter.ExportRequest req;
	private final Path root;

	public TemplateProjectGenerator(ModExporter.ExportRequest req, Path root) {
		this.req = req;
		this.root = root;
	}

	public void generate() throws IOException {
		// directory structure
		var src = root.resolve("src/main/java/" + req.modId().replace("_", "/"));
		var res = root.resolve("src/main/resources");
		Files.createDirectories(src);
		Files.createDirectories(res);

		// build files
		writeBuildGradle();
		writeSettingsGradle();
		writeGradleProperties();
		writeLicense();
		writeFabricModJson(res);

		// Java source
		writeMainClass(src);
		if (!req.itemIds().isEmpty()) {
			writeModItems(src);
			for (String id : req.itemIds()) {
				ItemDefinition def = WorldForgeRuntimeManager.get(id);
				if (def != null) writeCustomItem(src, def);
			}
		}
		if (!req.blockIds().isEmpty()) {
			writeModBlocks(src);
			for (String id : req.blockIds()) {
				BlockDefinition def = BlockRuntimeManager.get(id);
				if (def != null) writeCustomBlock(src, def);
			}
		}

		// resources
		writeLangFile(res);
		writeModelFiles(res);

		// GameTest that verifies items/blocks are registered at runtime
		writeGameTest(src, res);
	}

	private void writeGameTest(Path src, Path res) throws IOException {
		// Generate a GameTest class that verifies registration
		var sb = new StringBuilder();
		sb.append("package ").append(req.modId().replace("_", ".")).append(";\n\n");
		sb.append("import net.minecraft.registry.Registries;\n");
		sb.append("import net.minecraft.test.GameTest;\n");
		sb.append("import net.minecraft.test.TestContext;\n");
		sb.append("import net.minecraft.util.Identifier;\n\n");
		sb.append("public class ExportedModTest {\n");
		int testNum = 0;
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) continue;
			var nativeId = nativeItemId(id);
			testNum++;
			sb.append("\t@GameTest(batchId = \"exported\")\n");
			sb.append("\tpublic static void itemRegistered").append(testNum)
					.append("(TestContext ctx) {\n");
			sb.append("\t\tvar item = Registries.ITEM.get(Identifier.of(\"")
					.append(req.modId()).append("\", \"").append(nativeId).append("\"));\n");
			sb.append("\t\tif (item == null || item == net.minecraft.item.Items.AIR) {\n");
			sb.append("\t\t\tthrow new AssertionError(\"Item ").append(nativeId)
					.append(" not registered\");\n");
			sb.append("\t\t}\n");
			sb.append("\t\tctx.complete();\n");
			sb.append("\t}\n\n");
		}
		for (String id : req.blockIds()) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) continue;
			var nativeId = nativeBlockId(id);
			testNum++;
			sb.append("\t@GameTest(batchId = \"exported\")\n");
			sb.append("\tpublic static void blockRegistered").append(testNum)
					.append("(TestContext ctx) {\n");
			sb.append("\t\tvar block = Registries.BLOCK.get(Identifier.of(\"")
					.append(req.modId()).append("\", \"").append(nativeId).append("\"));\n");
			sb.append("\t\tif (block == null || block == net.minecraft.block.Blocks.AIR) {\n");
			sb.append("\t\t\tthrow new AssertionError(\"Block ").append(nativeId)
					.append(" not registered\");\n");
			sb.append("\t\t}\n");
			sb.append("\t\tctx.complete();\n");
			sb.append("\t}\n\n");
		}
		sb.append("}\n");
		Files.writeString(src.resolve("ExportedModTest.java"), sb.toString());

		// Register the test entrypoint in fabric.mod.json
		// (we need to update the existing fabric.mod.json to add the test entrypoint)
		var modJsonPath = res.resolve("fabric.mod.json");
		var modJson = Files.readString(modJsonPath);
		if (!modJson.contains("test")) {
			modJson = modJson.replace(
					"\"main\": [\"" + req.modId().replace("_", ".") + "." + mainClassName() + "\"]",
					"\"main\": [\"" + req.modId().replace("_", ".") + "." + mainClassName() + "\"],\n"
					+ "        \"test\": [\"" + req.modId().replace("_", ".") + ".ExportedModTest\"]");
			Files.writeString(modJsonPath, modJson);
		}
	}

	private void writeBuildGradle() throws IOException {
		Files.writeString(root.resolve("build.gradle"), """
				plugins {
				    id 'fabric-loom' version '1.12.7'
				    id 'java'
				}

				version = project.mod_version
				group = project.maven_group

				base {
				    archivesName = project.archives_base_name
				}

				repositories {
				    mavenCentral()
				    maven { url = 'https://maven.fabricmc.net/' }
				}

				dependencies {
				    minecraft "com.mojang:minecraft:${project.minecraft_version}"
				    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
				    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
				    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
				}

				processResources {
				    inputs.property "version", project.version
				    filesMatching("fabric.mod.json") {
				        expand "version": project.version
				    }
				}

				tasks.withType(JavaCompile).configureEach {
				    it.options.release = 21
				}

				java {
				    withSourcesJar()
				    sourceCompatibility = JavaVersion.VERSION_21
				    targetCompatibility = JavaVersion.VERSION_21
				}

				jar {
				    from("LICENSE") {
				        rename { "${it}" }
				    }
				}
				""");
	}

	private void writeLicense() throws IOException {
		Files.writeString(root.resolve("LICENSE"), """
				MIT License

				Copyright (c) 2024 WorldForge Export

				Permission is hereby granted, free of charge, to any person obtaining a copy
				of this software and associated documentation files (the "Software"), to deal
				in the Software without restriction, including without limitation the rights
				to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
				copies of the Software, and to permit persons to whom the Software is
				furnished to do so, subject to the following conditions:

				The above copyright notice and this permission notice shall be included in all
				copies or substantial portions of the Software.

				THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
				""");
	}

	/**
	 * Copy the Gradle wrapper from the WorldForge project so the generated
	 * project can build standalone without a system Gradle install.
	 */
	public void copyGradleWrapper(Path sourceProjectRoot) throws IOException {
		var wrapperDir = root.resolve("gradle/wrapper");
		Files.createDirectories(wrapperDir);
		// gradle-wrapper.properties
		var propsSrc = sourceProjectRoot.resolve("gradle/wrapper/gradle-wrapper.properties");
		if (Files.exists(propsSrc)) {
			Files.copy(propsSrc, wrapperDir.resolve("gradle-wrapper.properties"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.writeString(wrapperDir.resolve("gradle-wrapper.properties"),
					"distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\n"
					+ "distributionType=bin\n");
		}
		// gradle-wrapper.jar
		var jarSrc = sourceProjectRoot.resolve("gradle/wrapper/gradle-wrapper.jar");
		if (Files.exists(jarSrc)) {
			Files.copy(jarSrc, wrapperDir.resolve("gradle-wrapper.jar"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		// gradlew / gradlew.bat
		for (String name : new String[]{"gradlew", "gradlew.bat"}) {
			var src = sourceProjectRoot.resolve(name);
			if (Files.exists(src)) {
				Files.copy(src, root.resolve(name),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private void writeSettingsGradle() throws IOException {
		Files.writeString(root.resolve("settings.gradle"),
				"pluginManagement {\n"
				+ "    repositories {\n"
				+ "        maven {\n"
				+ "            name = 'FabricMC'\n"
				+ "            url = 'https://maven.fabricmc.net/'\n"
				+ "        }\n"
				+ "        mavenCentral()\n"
				+ "        gradlePluginPortal()\n"
				+ "    }\n"
				+ "}\n"
				+ "\n"
				+ "rootProject.name = \"" + req.modId() + "\"\n");
	}

	private void writeGradleProperties() throws IOException {
		Files.writeString(root.resolve("gradle.properties"), """
				org.gradle.jvmargs=-Xmx2G
				org.gradle.parallel=true

				minecraft_version=1.21.1
				yarn_mappings=1.21.1+build.3
				loader_version=0.16.9
				fabric_version=0.116.5+1.21.1

				mod_version=%s
				maven_group=%s
				archives_base_name=%s
				""".formatted(req.modVersion(), req.modId(), req.modId()));
	}

	private void writeFabricModJson(Path res) throws IOException {
		var json = """
				{
				    "schemaVersion": 1,
				    "id": "%s",
				    "version": "${version}",
				    "name": "%s",
				    "description": "Exported by WorldForge v0.5.0",
				    "authors": ["WorldForge Export"],
				    "license": "MIT",
				    "environment": "*",
				    "entrypoints": {
				        "main": ["%s.%s"]
				    },
				    "depends": {
				        "fabricloader": ">=0.16.0",
				        "minecraft": "~1.21.1",
				        "java": ">=21",
				        "fabric-api": "*"
				    }
				}
				""".formatted(
				req.modId(),
				escapeJson(req.modName()),
				req.modId().replace("_", "."),
				mainClassName());
		Files.createDirectories(res);
		Files.writeString(res.resolve("fabric.mod.json"), json);
	}

	private void writeMainClass(Path src) throws IOException {
		var pkg = req.modId().replace("_", ".");
		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.fabricmc.api.ModInitializer;\n");
		sb.append("import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;\n");
		sb.append("import net.minecraft.item.ItemGroup;\n");
		sb.append("import net.minecraft.item.ItemStack;\n");
		sb.append("import net.minecraft.text.Text;\n");
		sb.append("import net.minecraft.util.Identifier;\n");
		sb.append("import org.slf4j.Logger;\n");
		sb.append("import org.slf4j.LoggerFactory;\n\n");
		sb.append("public class ").append(mainClassName()).append(" implements ModInitializer {\n");
		sb.append("\tpublic static final Logger LOGGER = LoggerFactory.getLogger(\"").append(req.modId()).append("\");\n\n");
		sb.append("\t@Override\n");
		sb.append("\tpublic void onInitialize() {\n");
		sb.append("\t\tLOGGER.info(\"Initializing ").append(escapeJava(req.modName())).append("\");\n");
		if (!req.itemIds().isEmpty()) {
			sb.append("\t\tModItems.initialize();\n");
		}
		if (!req.blockIds().isEmpty()) {
			sb.append("\t\tModBlocks.initialize();\n");
		}
		sb.append("\t}\n");
		sb.append("}\n");
		Files.writeString(src.resolve(mainClassName() + ".java"), sb.toString());
	}

	private void writeModItems(Path src) throws IOException {
		var pkg = req.modId().replace("_", ".");
		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.minecraft.item.Item;\n");
		sb.append("import net.minecraft.registry.Registries;\n");
		sb.append("import net.minecraft.registry.Registry;\n");
		sb.append("import net.minecraft.util.Identifier;\n\n");
		sb.append("public final class ModItems {\n");
		sb.append("\tprivate ModItems() {}\n\n");
		sb.append("\tstatic void initialize() {\n");
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) continue;
			var field = itemFieldName(def);
			var nativeId = nativeItemId(id);
			sb.append("\t\tvar ").append(field).append(" = Registry.register(Registries.ITEM,\n");
			sb.append("\t\t\t\tIdentifier.of(\"").append(req.modId()).append("\", \"").append(nativeId).append("\"),\n");
			sb.append("\t\t\t\tnew Item(new Item.Settings()));\n");
		}
		sb.append("\t}\n");
		sb.append("}\n");
		Files.writeString(src.resolve("ModItems.java"), sb.toString());
	}

	private void writeCustomItem(Path src, ItemDefinition def) throws IOException {
		// Items are registered in ModItems; no separate class needed for v0.5.0
		// Future: generate custom Item subclasses for behaviors
	}

	private void writeModBlocks(Path src) throws IOException {
		var pkg = req.modId().replace("_", ".");
		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.minecraft.block.Block;\n");
		sb.append("import net.minecraft.block.Blocks;\n");
		sb.append("import net.minecraft.item.BlockItem;\n");
		sb.append("import net.minecraft.item.Item;\n");
		sb.append("import net.minecraft.registry.Registries;\n");
		sb.append("import net.minecraft.registry.Registry;\n");
		sb.append("import net.minecraft.util.Identifier;\n\n");
		sb.append("public final class ModBlocks {\n");
		sb.append("\tprivate ModBlocks() {}\n\n");
		sb.append("\tstatic void initialize() {\n");
		for (String id : req.blockIds()) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) continue;
			var field = blockFieldName(def);
			var nativeId = nativeBlockId(id);
			sb.append("\t\tvar ").append(field).append(" = Registry.register(Registries.BLOCK,\n");
			sb.append("\t\t\t\tIdentifier.of(\"").append(req.modId()).append("\", \"").append(nativeId).append("\"),\n");
			sb.append("\t\t\t\tnew Block(Block.Settings.copy(Blocks.").append(vanillaBaseBlock(def)).append(")));\n");
			sb.append("\t\tRegistry.register(Registries.ITEM,\n");
			sb.append("\t\t\t\tIdentifier.of(\"").append(req.modId()).append("\", \"").append(nativeId).append("\"),\n");
			sb.append("\t\t\t\tnew BlockItem(").append(field).append(", new Item.Settings()));\n");
		}
		sb.append("\t}\n");
		sb.append("}\n");
		Files.writeString(src.resolve("ModBlocks.java"), sb.toString());
	}

	private void writeCustomBlock(Path src, BlockDefinition def) throws IOException {
		// Blocks are registered in ModBlocks; no separate class needed for v0.5.0
	}

	private void writeLangFile(Path res) throws IOException {
		var sb = new StringBuilder();
		sb.append("{\n");
		boolean first = true;
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) continue;
			if (!first) sb.append(",\n");
			first = false;
			sb.append("\t\"item.").append(req.modId()).append(".").append(nativeItemId(id))
					.append("\": \"").append(escapeJson(def.name())).append("\"");
		}
		for (String id : req.blockIds()) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) continue;
			if (!first) sb.append(",\n");
			first = false;
			sb.append("\t\"block.").append(req.modId()).append(".").append(nativeBlockId(id))
					.append("\": \"").append(escapeJson(def.name())).append("\"");
		}
		sb.append("\n}\n");
		Files.createDirectories(res.resolve("assets/" + req.modId() + "/lang"));
		Files.writeString(res.resolve("assets/" + req.modId() + "/lang/en_us.json"), sb.toString());
	}

	private void writeModelFiles(Path res) throws IOException {
		var itemModels = res.resolve("assets/" + req.modId() + "/models/item");
		Files.createDirectories(itemModels);
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) continue;
			var nativeId = nativeItemId(id);
			var appearance = def.texture().resource().replace("item/", "");
			var vanillaPath = appearance.replace("minecraft:", "");
			Files.writeString(itemModels.resolve(nativeId + ".json"),
					"{\"parent\": \"minecraft:item/" + vanillaPath + "\"}");
		}
		var blockModels = res.resolve("assets/" + req.modId() + "/models/block");
		Files.createDirectories(blockModels);
		for (String id : req.blockIds()) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) continue;
			var nativeId = nativeBlockId(id);
			var appearance = def.appearance().replace("minecraft:", "");
			Files.writeString(blockModels.resolve(nativeId + ".json"),
					"{\"parent\": \"minecraft:block/" + appearance + "\"}");
			// blockstate
			var blockstates = res.resolve("assets/" + req.modId() + "/blockstates");
			Files.createDirectories(blockstates);
			Files.writeString(blockstates.resolve(nativeId + ".json"),
					"{\"variants\": {\"\": {\"model\": \"" + req.modId() + ":block/" + nativeId + "\"}}}");
		}
	}

	// ---- helpers ----

	private String mainClassName() {
		var parts = req.modId().split("_");
		var sb = new StringBuilder();
		for (var p : parts) {
			if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
		}
		return sb.toString() + "Mod";
	}

	private String nativeItemId(String worldForgeId) {
		// "worldforge:my_sword" -> "my_sword"
		return worldForgeId.replace("worldforge:", "").replace(":", "_");
	}

	private String nativeBlockId(String worldForgeId) {
		return nativeItemId(worldForgeId);
	}

	private String itemFieldName(ItemDefinition def) {
		return "ITEM_" + nativeItemId(def.id()).toUpperCase().replace("-", "_");
	}

	private String blockFieldName(BlockDefinition def) {
		return "BLOCK_" + nativeBlockId(def.id()).toUpperCase().replace("-", "_");
	}

	private String vanillaBaseBlock(BlockDefinition def) {
		var app = def.appearance().replace("minecraft:", "");
		// map appearance to a vanilla Blocks field name
		return switch (app) {
			case "stone" -> "STONE";
			case "oak_planks" -> "OAK_PLANKS";
			case "furnace" -> "FURNACE";
			case "crafting_table" -> "CRAFTING_TABLE";
			case "iron_block" -> "IRON_BLOCK";
			case "gold_block" -> "GOLD_BLOCK";
			case "emerald_block" -> "EMERALD_BLOCK";
			case "amethyst_block" -> "AMETHYST_BLOCK";
			case "redstone_lamp" -> "REDSTONE_LAMP";
			case "bookshelf" -> "BOOKSHELF";
			case "barrel" -> "BARREL";
			case "deepslate_tiles" -> "DEEPSLATE_TILES";
			default -> "STONE";
		};
	}

	private static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}

	private static String escapeJava(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r");
	}
}
