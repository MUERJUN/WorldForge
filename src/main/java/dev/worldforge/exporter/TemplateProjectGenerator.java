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
		var pkg = req.modId().replace("_", ".");
		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.minecraft.entity.Entity;\n");
		sb.append("import net.minecraft.entity.EntityType;\n");
		sb.append("import net.minecraft.item.ItemStack;\n");
		sb.append("import net.minecraft.item.ItemUsageContext;\n");
		sb.append("import net.minecraft.registry.Registries;\n");
		sb.append("import net.minecraft.server.network.ServerPlayerEntity;\n");
		sb.append("import net.minecraft.server.world.ServerWorld;\n");
		sb.append("import net.minecraft.test.GameTest;\n");
		sb.append("import net.minecraft.test.TestContext;\n");
		sb.append("import net.minecraft.util.ActionResult;\n");
		sb.append("import net.minecraft.util.Hand;\n");
		sb.append("import net.minecraft.util.Identifier;\n");
		sb.append("import net.minecraft.util.TypedActionResult;\n");
		sb.append("import net.minecraft.util.hit.BlockHitResult;\n");
		sb.append("import net.minecraft.util.math.BlockPos;\n");
		sb.append("import net.minecraft.util.math.Direction;\n");
		sb.append("import net.minecraft.util.math.Vec3d;\n\n");
		sb.append("public class ExportedModTest {\n");
		int testNum = 0;
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) continue;
			var nativeId = nativeItemId(id);
			testNum++;
			sb.append("\t@GameTest(templateName = \"fabric-gametest-api-v1:empty\", batchId = \"exported\")\n");
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

			// Add behavior test if item has summon_lightning
			boolean hasLightning = def.behaviors().stream()
					.anyMatch(b -> b.actions().contains("summon_lightning"));
			if (hasLightning) {
				int cooldown = def.behaviors().stream()
						.filter(b -> b.actions().contains("summon_lightning"))
						.mapToInt(dev.worldforge.definition.BehaviorDefinition::cooldownTicks)
						.max().orElse(0);
				testNum++;
				sb.append("\t@GameTest(templateName = \"fabric-gametest-api-v1:empty\", batchId = \"exported\")\n");
				sb.append("\tpublic static void itemLightningBehavior").append(testNum)
						.append("(TestContext ctx) {\n");
				sb.append("\t\tServerWorld world = ctx.getWorld();\n");
				sb.append("\t\tServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();\n");
				sb.append("\t\tvar item = Registries.ITEM.get(Identifier.of(\"")
						.append(req.modId()).append("\", \"").append(nativeId).append("\"));\n");
				sb.append("\t\tItemStack stack = new ItemStack(item);\n");
				sb.append("\t\tplayer.setStackInHand(Hand.MAIN_HAND, stack);\n");
				// Use the item — should succeed (behavior executed)
				sb.append("\t\tvar result1 = item.use(world, player, Hand.MAIN_HAND);\n");
				sb.append("\t\tif (result1.getResult() != ActionResult.SUCCESS) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"Item use did not succeed — lightning behavior not triggered\");\n");
				sb.append("\t\t}\n");
				// Use again immediately — should pass (cooldown blocks it)
				sb.append("\t\tvar result2 = item.use(world, player, Hand.MAIN_HAND);\n");
				sb.append("\t\tif (result2.getResult() == ActionResult.SUCCESS) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"Cooldown failed: second use succeeded within cooldown period\");\n");
				sb.append("\t\t}\n");
				sb.append("\t\tctx.complete();\n");
				sb.append("\t}\n\n");
			}
		}
		for (String id : req.blockIds()) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) continue;
			var nativeId = nativeBlockId(id);
			testNum++;
			sb.append("\t@GameTest(templateName = \"fabric-gametest-api-v1:empty\", batchId = \"exported\")\n");
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

			// Add behavior test if block has apply_effect
			boolean hasEffect = def.behaviors().stream()
					.anyMatch(b -> b.actions().contains("apply_effect"));
			if (hasEffect) {
				var effectName = "speed";
				for (var b : def.behaviors()) {
					if (b.actions().contains("apply_effect")) {
						effectName = b.parameters().getOrDefault("effect", "speed").toString();
						break;
					}
				}
				testNum++;
				sb.append("\t@GameTest(templateName = \"fabric-gametest-api-v1:empty\", batchId = \"exported\")\n");
				sb.append("\tpublic static void blockEffectBehavior").append(testNum)
						.append("(TestContext ctx) {\n");
				sb.append("\t\tServerWorld world = ctx.getWorld();\n");
				sb.append("\t\tServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();\n");
				sb.append("\t\tvar block = Registries.BLOCK.get(Identifier.of(\"")
						.append(req.modId()).append("\", \"").append(nativeId).append("\"));\n");
				// Place the block
				sb.append("\t\tBlockPos pos = new BlockPos(0, 1, 0);\n");
				sb.append("\t\tvar state = block.getDefaultState();\n");
				sb.append("\t\tworld.setBlockState(pos, state);\n");
				// Right-click the block via custom block's onUse (public)
				sb.append("\t\tvar hit = new BlockHitResult(new Vec3d(0.5, 1.0, 0.5), Direction.NORTH, pos, false);\n");
				var blockClassName = blockClassName(def);
				sb.append("\t\tvar customBlock = (").append(blockClassName).append(") block;\n");
				sb.append("\t\tvar result1 = customBlock.onUse(state, world, pos, player, hit);\n");
				sb.append("\t\tif (result1 != ActionResult.SUCCESS) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"Block onUse did not succeed — effect behavior not triggered\");\n");
				sb.append("\t\t}\n");
				// Check the player has the effect
				sb.append("\t\tvar effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(\"minecraft:")
						.append(effectName).append("\")).orElse(null);\n");
				sb.append("\t\tif (effect == null || !player.hasStatusEffect(effect)) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"Effect ").append(effectName)
						.append(" was not applied by block use\");\n");
				sb.append("\t\t}\n");
				// Test cooldown: remove effect, use again immediately, should NOT apply
				sb.append("\t\tplayer.removeStatusEffect(effect);\n");
				sb.append("\t\tvar result2 = customBlock.onUse(state, world, pos, player, hit);\n");
				sb.append("\t\tif (result2 == ActionResult.SUCCESS) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"Cooldown failed: second block use succeeded within cooldown\");\n");
				sb.append("\t\t}\n");
				sb.append("\t\tif (player.hasStatusEffect(effect)) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"Cooldown failed: effect re-applied within cooldown period\");\n");
				sb.append("\t\t}\n");
				sb.append("\t\tctx.complete();\n");
				sb.append("\t}\n\n");
			}
		}
		sb.append("}\n");
		Files.writeString(src.resolve("ExportedModTest.java"), sb.toString());

		// Register the test entrypoint in fabric.mod.json
		var modJsonPath = res.resolve("fabric.mod.json");
		var modJson = Files.readString(modJsonPath);
		if (!modJson.contains("fabric-gametest")) {
			modJson = modJson.replace(
					"\"main\": [\"" + req.modId().replace("_", ".") + "." + mainClassName() + "\"]",
					"\"main\": [\"" + req.modId().replace("_", ".") + "." + mainClassName() + "\"],\n"
					+ "        \"fabric-gametest\": [\"" + req.modId().replace("_", ".") + ".ExportedModTest\"]");
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

				fabricApi {
				    configureTests {
				        modId = "%s"
				        enableGameTests = true
				        enableClientGameTests = false
				        clearRunDirectory = true
				    }
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
				""".formatted(req.modId()));
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
	/**
	 * Copy Gradle wrapper from bundled resources (no source repo dependency).
	 * Falls back to sourceProjectRoot if resources aren't available (IDE dev mode).
	 */
	public void copyGradleWrapper(Path sourceProjectRoot) throws IOException {
		var wrapperDir = root.resolve("gradle/wrapper");
		Files.createDirectories(wrapperDir);

		// Try classpath resources first (works in packaged JAR)
		boolean fromResources = copyResourceIfPresent("export-template/gradle/wrapper/gradle-wrapper.properties",
				wrapperDir.resolve("gradle-wrapper.properties"));
		copyResourceIfPresent("export-template/gradle/wrapper/gradle-wrapper.jar",
				wrapperDir.resolve("gradle-wrapper.jar"));
		copyResourceIfPresent("export-template/gradlew", root.resolve("gradlew"));
		copyResourceIfPresent("export-template/gradlew.bat", root.resolve("gradlew.bat"));

		if (fromResources) return; // success — all from bundled resources

		// Fallback: copy from source project root (IDE dev mode only)
		var propsSrc = sourceProjectRoot.resolve("gradle/wrapper/gradle-wrapper.properties");
		if (Files.exists(propsSrc)) {
			Files.copy(propsSrc, wrapperDir.resolve("gradle-wrapper.properties"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} else {
			Files.writeString(wrapperDir.resolve("gradle-wrapper.properties"),
					"distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\n"
					+ "distributionType=bin\n");
		}
		var jarSrc = sourceProjectRoot.resolve("gradle/wrapper/gradle-wrapper.jar");
		if (Files.exists(jarSrc)) {
			Files.copy(jarSrc, wrapperDir.resolve("gradle-wrapper.jar"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		for (String name : new String[]{"gradlew", "gradlew.bat"}) {
			var src = sourceProjectRoot.resolve(name);
			if (Files.exists(src)) {
				Files.copy(src, root.resolve(name),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/** Copy a classpath resource to a file. Returns true if the resource was found. */
	private boolean copyResourceIfPresent(String resourcePath, Path target) throws IOException {
		var is = getClass().getClassLoader().getResourceAsStream(resourcePath);
		if (is == null) return false;
		try (is) {
			Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		return true;
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
		sb.append("import net.minecraft.entity.player.PlayerEntity;\n");
		sb.append("import net.minecraft.item.Item;\n");
		sb.append("import net.minecraft.item.ItemStack;\n");
		sb.append("import net.minecraft.item.ItemUsageContext;\n");
		sb.append("import net.minecraft.registry.Registries;\n");
		sb.append("import net.minecraft.registry.Registry;\n");
		sb.append("import net.minecraft.server.world.ServerWorld;\n");
		sb.append("import net.minecraft.util.ActionResult;\n");
		sb.append("import net.minecraft.util.Hand;\n");
		sb.append("import net.minecraft.util.Identifier;\n");
		sb.append("import net.minecraft.util.math.BlockPos;\n");
		sb.append("import net.minecraft.world.World;\n\n");
		sb.append("public final class ModItems {\n");
		sb.append("\tprivate ModItems() {}\n\n");
		sb.append("\tstatic void initialize() {\n");
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) continue;
			var field = itemFieldName(def);
			var nativeId = nativeItemId(id);
			boolean hasBehaviors = !def.behaviors().isEmpty();
			String itemClass = hasBehaviors ? itemClassName(def) : "Item";
			String constructor = hasBehaviors
					? "new " + itemClassName(def) + "(new Item.Settings())"
					: "new Item(new Item.Settings())";
			sb.append("\t\tvar ").append(field).append(" = Registry.register(Registries.ITEM,\n");
			sb.append("\t\t\t\tIdentifier.of(\"").append(req.modId()).append("\", \"").append(nativeId).append("\"),\n");
			sb.append("\t\t\t\t").append(constructor).append(");\n");
		}
		sb.append("\t}\n");
		sb.append("}\n");
		Files.writeString(src.resolve("ModItems.java"), sb.toString());

		// Generate custom Item subclasses for items with behaviors
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def != null && !def.behaviors().isEmpty()) {
				writeCustomItem(src, def);
			}
		}
	}

	private void writeCustomItem(Path src, ItemDefinition def) throws IOException {
		var pkg = req.modId().replace("_", ".");
		var className = itemClassName(def);
		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.minecraft.entity.player.PlayerEntity;\n");
		sb.append("import net.minecraft.item.Item;\n");
		sb.append("import net.minecraft.item.ItemStack;\n");
		sb.append("import net.minecraft.item.ItemUsageContext;\n");
		sb.append("import net.minecraft.server.world.ServerWorld;\n");
		sb.append("import net.minecraft.util.ActionResult;\n");
		sb.append("import net.minecraft.util.Hand;\n");
		sb.append("import net.minecraft.util.TypedActionResult;\n");
		sb.append("import net.minecraft.util.math.BlockPos;\n");
		sb.append("import net.minecraft.world.World;\n");
		sb.append("import java.util.HashMap;\n");
		sb.append("import java.util.Map;\n\n");
		sb.append("/**\n * Custom item with exported behaviors for ").append(escapeJava(def.name())).append(".\n */\n");
		sb.append("public final class ").append(className).append(" extends Item {\n\n");

		// Cooldown tracking: per-player last use tick
		int cooldownTicks = def.behaviors().stream()
				.mapToInt(dev.worldforge.definition.BehaviorDefinition::cooldownTicks)
				.max().orElse(0);
		sb.append("\tprivate static final int COOLDOWN_TICKS = ").append(cooldownTicks).append(";\n");
		sb.append("\tprivate static final Map<java.util.UUID, Long> LAST_USE = new HashMap<>();\n\n");

		sb.append("\tpublic ").append(className).append("(Settings settings) {\n");
		sb.append("\t\tsuper(settings);\n");
		sb.append("\t}\n\n");

		// Override use() — right click in air
		sb.append("\t@Override\n");
		sb.append("\tpublic TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {\n");
		sb.append("\t\tItemStack stack = player.getStackInHand(hand);\n");
		sb.append("\t\tif (world.isClient()) return TypedActionResult.pass(stack);\n");
		sb.append("\t\tvar sw = (ServerWorld) world;\n");
		sb.append("\t\tlong now = sw.getTime();\n");
		sb.append("\t\tvar last = LAST_USE.get(player.getUuid());\n");
		sb.append("\t\tif (last != null && COOLDOWN_TICKS > 0 && now - last < COOLDOWN_TICKS) {\n");
		sb.append("\t\t\treturn TypedActionResult.pass(stack);\n");
		sb.append("\t\t}\n");

		// Generate action code for each behavior
		for (var behavior : def.behaviors()) {
			for (String action : behavior.actions()) {
				generateItemActionCode(sb, action, behavior);
			}
		}

		sb.append("\t\tLAST_USE.put(player.getUuid(), now);\n");
		sb.append("\t\treturn TypedActionResult.success(stack);\n");
		sb.append("\t}\n");

		// Override useOnBlock() — right click on block
		sb.append("\n\t@Override\n");
		sb.append("\tpublic ActionResult useOnBlock(ItemUsageContext context) {\n");
		sb.append("\t\tif (context.getWorld().isClient()) return ActionResult.PASS;\n");
		sb.append("\t\tvar sw = (ServerWorld) context.getWorld();\n");
		sb.append("\t\tlong now = sw.getTime();\n");
		sb.append("\t\tvar player = context.getPlayer();\n");
		sb.append("\t\tif (player == null) return ActionResult.PASS;\n");
		sb.append("\t\tvar last = LAST_USE.get(player.getUuid());\n");
		sb.append("\t\tif (last != null && COOLDOWN_TICKS > 0 && now - last < COOLDOWN_TICKS) {\n");
		sb.append("\t\t\treturn ActionResult.PASS;\n");
		sb.append("\t\t}\n");
		for (var behavior : def.behaviors()) {
			for (String action : behavior.actions()) {
				generateItemActionCode(sb, action, behavior);
			}
		}
		sb.append("\t\tLAST_USE.put(player.getUuid(), now);\n");
		sb.append("\t\treturn ActionResult.SUCCESS;\n");
		sb.append("\t}\n");

		sb.append("}\n");
		Files.writeString(src.resolve(className + ".java"), sb.toString());
	}

	private void generateItemActionCode(StringBuilder sb, String action,
			dev.worldforge.definition.BehaviorDefinition behavior) {
		switch (action) {
			case "summon_lightning" -> {
				sb.append("\t\t{\n");
				sb.append("\t\t\tvar pos = player.getBlockPos();\n");
				sb.append("\t\t\tvar entity = net.minecraft.entity.EntityType.LIGHTNING_BOLT.create(sw);\n");
				sb.append("\t\t\tif (entity != null) {\n");
				sb.append("\t\t\t\tentity.refreshPositionAfterTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);\n");
				sb.append("\t\t\t\tsw.spawnEntity(entity);\n");
				sb.append("\t\t\t}\n");
				sb.append("\t\t}\n");
			}
			case "apply_effect" -> {
				var effectName = behavior.parameters().getOrDefault("effect", "speed").toString();
				var duration = behavior.parameters().getOrDefault("duration", 200);
				var amplifier = behavior.parameters().getOrDefault("amplifier", 0);
				sb.append("\t\t{\n");
				sb.append("\t\t\tvar effect = net.minecraft.registry.Registries.STATUS_EFFECT\n");
				sb.append("\t\t\t\t\t.getEntry(net.minecraft.util.Identifier.of(\"minecraft:").append(effectName).append("\")).orElse(null);\n");
				sb.append("\t\t\tif (effect != null) {\n");
				sb.append("\t\t\t\tplayer.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(\n");
				sb.append("\t\t\t\t\t\teffect, ").append(duration).append(", ").append(amplifier).append("));\n");
				sb.append("\t\t\t}\n");
				sb.append("\t\t}\n");
			}
			case "heal" -> {
				var amount = behavior.parameters().getOrDefault("amount", 10);
				sb.append("\t\tplayer.heal(").append(amount).append("f);\n");
			}
			case "ignite" -> {
				sb.append("\t\tplayer.setOnFireFor(5);\n");
			}
			case "sound" -> {
				sb.append("\t\tsw.playSound(null, player.getBlockPos(),\n");
				sb.append("\t\t\t\tnet.minecraft.sound.SoundEvents.BLOCK_BELL_USE,\n");
				sb.append("\t\t\t\tnet.minecraft.sound.SoundCategory.PLAYERS, 1f, 1f);\n");
			}
			case "particles" -> {
				sb.append("\t\tsw.spawnParticles(net.minecraft.particle.ParticleTypes.ELECTRIC_SPARK,\n");
				sb.append("\t\t\t\tplayer.getX(), player.getY() + 1, player.getZ(), 10, 0.5, 0.5, 0.5, 0.1);\n");
			}
			default -> {
				// unsupported action — skip (should have been caught by validation)
			}
		}
	}

	private void writeModBlocks(Path src) throws IOException {
		var pkg = req.modId().replace("_", ".");
		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.minecraft.block.Block;\n");
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
			boolean hasBehaviors = !def.behaviors().isEmpty();
			String blockClass = hasBehaviors ? blockClassName(def) : "Block";
			String constructor = hasBehaviors
					? "new " + blockClassName(def) + "(Block.Settings.create().strength(2.0f))"
					: "new Block(Block.Settings.create().strength(2.0f))";
			sb.append("\t\tvar ").append(field).append(" = Registry.register(Registries.BLOCK,\n");
			sb.append("\t\t\t\tIdentifier.of(\"").append(req.modId()).append("\", \"").append(nativeId).append("\"),\n");
			sb.append("\t\t\t\t").append(constructor).append(");\n");
			sb.append("\t\tRegistry.register(Registries.ITEM,\n");
			sb.append("\t\t\t\tIdentifier.of(\"").append(req.modId()).append("\", \"").append(nativeId).append("\"),\n");
			sb.append("\t\t\t\tnew BlockItem(").append(field).append(", new Item.Settings()));\n");
		}
		sb.append("\t}\n");
		sb.append("}\n");
		Files.writeString(src.resolve("ModBlocks.java"), sb.toString());

		// Generate custom Block subclasses for blocks with behaviors
		for (String id : req.blockIds()) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def != null && !def.behaviors().isEmpty()) {
				writeCustomBlock(src, def);
			}
		}
	}

	private void writeCustomBlock(Path src, BlockDefinition def) throws IOException {
		var pkg = req.modId().replace("_", ".");
		var className = blockClassName(def);
		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.minecraft.block.Block;\n");
		sb.append("import net.minecraft.block.BlockState;\n");
		sb.append("import net.minecraft.entity.player.PlayerEntity;\n");
		sb.append("import net.minecraft.server.world.ServerWorld;\n");
		sb.append("import net.minecraft.util.ActionResult;\n");
		sb.append("import net.minecraft.util.hit.BlockHitResult;\n");
		sb.append("import net.minecraft.util.math.BlockPos;\n");
		sb.append("import net.minecraft.world.World;\n");
		sb.append("import java.util.HashMap;\n");
		sb.append("import java.util.Map;\n\n");
		sb.append("/**\n * Custom block with exported behaviors for ").append(escapeJava(def.name())).append(".\n */\n");
		sb.append("public final class ").append(className).append(" extends Block {\n\n");

		// Cooldown tracking: per-position last use tick
		int cooldownTicks = def.behaviors().stream()
				.mapToInt(dev.worldforge.definition.BehaviorDefinition::cooldownTicks)
				.max().orElse(0);
		sb.append("\tprivate static final int COOLDOWN_TICKS = ").append(cooldownTicks).append(";\n");
		sb.append("\tprivate static final Map<BlockPos, Long> LAST_USE = new HashMap<>();\n\n");

		sb.append("\tpublic ").append(className).append("(Settings settings) {\n");
		sb.append("\t\tsuper(settings);\n");
		sb.append("\t}\n\n");

		// Override onUse() — right click on block (public for testability)
		sb.append("\t@Override\n");
		sb.append("\tpublic ActionResult onUse(BlockState state, World world, BlockPos pos,\n");
		sb.append("\t\t\tPlayerEntity player, BlockHitResult hit) {\n");
		sb.append("\t\tif (world.isClient()) return ActionResult.PASS;\n");
		sb.append("\t\tvar sw = (ServerWorld) world;\n");
		sb.append("\t\tlong now = sw.getTime();\n");
		sb.append("\t\tvar last = LAST_USE.get(pos);\n");
		sb.append("\t\tif (last != null && COOLDOWN_TICKS > 0 && now - last < COOLDOWN_TICKS) {\n");
		sb.append("\t\t\treturn ActionResult.PASS;\n");
		sb.append("\t\t}\n");

		// Generate action code for each behavior
		for (var behavior : def.behaviors()) {
			for (String action : behavior.actions()) {
				generateBlockActionCode(sb, action, behavior);
			}
		}

		sb.append("\t\tLAST_USE.put(pos, now);\n");
		sb.append("\t\treturn ActionResult.SUCCESS;\n");
		sb.append("\t}\n");

		sb.append("}\n");
		Files.writeString(src.resolve(className + ".java"), sb.toString());
	}

	private void generateBlockActionCode(StringBuilder sb, String action,
			dev.worldforge.definition.BehaviorDefinition behavior) {
		switch (action) {
			case "summon_lightning" -> {
				sb.append("\t\t{\n");
				sb.append("\t\t\tvar entity = net.minecraft.entity.EntityType.LIGHTNING_BOLT.create(sw);\n");
				sb.append("\t\t\tif (entity != null) {\n");
				sb.append("\t\t\t\tentity.refreshPositionAfterTeleport(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);\n");
				sb.append("\t\t\t\tsw.spawnEntity(entity);\n");
				sb.append("\t\t\t}\n");
				sb.append("\t\t}\n");
			}
			case "apply_effect" -> {
				var effectName = behavior.parameters().getOrDefault("effect", "speed").toString();
				var duration = behavior.parameters().getOrDefault("duration", 200);
				var amplifier = behavior.parameters().getOrDefault("amplifier", 0);
				sb.append("\t\tif (player != null) {\n");
				sb.append("\t\t\tvar effect = net.minecraft.registry.Registries.STATUS_EFFECT\n");
				sb.append("\t\t\t\t\t.getEntry(net.minecraft.util.Identifier.of(\"minecraft:").append(effectName).append("\")).orElse(null);\n");
				sb.append("\t\t\tif (effect != null) {\n");
				sb.append("\t\t\t\tplayer.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(\n");
				sb.append("\t\t\t\t\t\teffect, ").append(duration).append(", ").append(amplifier).append("));\n");
				sb.append("\t\t\t}\n");
				sb.append("\t\t}\n");
			}
			case "heal" -> {
				var amount = behavior.parameters().getOrDefault("amount", 10);
				sb.append("\t\tif (player != null) player.heal(").append(amount).append("f);\n");
			}
			case "ignite" -> {
				sb.append("\t\tif (player != null) player.setOnFireFor(5);\n");
			}
			case "sound" -> {
				sb.append("\t\tsw.playSound(null, pos,\n");
				sb.append("\t\t\t\tnet.minecraft.sound.SoundEvents.BLOCK_BELL_USE,\n");
				sb.append("\t\t\t\tnet.minecraft.sound.SoundCategory.BLOCKS, 1f, 1f);\n");
			}
			case "particles" -> {
				sb.append("\t\tsw.spawnParticles(net.minecraft.particle.ParticleTypes.ELECTRIC_SPARK,\n");
				sb.append("\t\t\t\tpos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 10, 0.5, 0.5, 0.5, 0.1);\n");
			}
			default -> {
				// unsupported action — skip
			}
		}
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

	private String itemClassName(ItemDefinition def) {
		var nativeId = nativeItemId(def.id());
		var sb = new StringBuilder();
		for (var p : nativeId.split("_")) {
			if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
		}
		return sb.toString() + "Item";
	}

	private String blockFieldName(BlockDefinition def) {
		return "BLOCK_" + nativeBlockId(def.id()).toUpperCase().replace("-", "_");
	}

	private String blockClassName(BlockDefinition def) {
		var nativeId = nativeBlockId(def.id());
		var sb = new StringBuilder();
		for (var p : nativeId.split("_")) {
			if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
		}
		return sb.toString() + "Block";
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
