package dev.worldforge.exporter;

import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.block.BlockDefinition;

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
	/** v0.5.3 P0-1: where definitions come from — runtime (client thread) or plan snapshot (background). */
	private final DefinitionResolver resolver;

	public TemplateProjectGenerator(ModExporter.ExportRequest req, Path root) {
		this(req, root, DefinitionResolver.fromRuntime());
	}

	/** Plan-driven constructor: background export threads pass {@link DefinitionResolver#fromSnapshot}. */
	public TemplateProjectGenerator(ModExporter.ExportRequest req, Path root, DefinitionResolver resolver) {
		this.req = req;
		this.root = root;
		this.resolver = resolver;
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
				ItemDefinition def = resolver.getItem(id);
				if (def != null) writeCustomItem(src, def);
			}
		}
		if (!req.blockIds().isEmpty()) {
			writeModBlocks(src);
			for (String id : req.blockIds()) {
				BlockDefinition def = resolver.getBlock(id);
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
			ItemDefinition def = resolver.getItem(id);
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

				// P1-2: Test that lightning entity is actually spawned in the world
				testNum++;
				sb.append("\t@GameTest(templateName = \"fabric-gametest-api-v1:empty\", batchId = \"exported\")\n");
				sb.append("\tpublic static void itemLightningSpawnsEntity").append(testNum)
						.append("(TestContext ctx) {\n");
				sb.append("\t\tServerWorld world = ctx.getWorld();\n");
				sb.append("\t\tServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();\n");
				sb.append("\t\tvar item = Registries.ITEM.get(Identifier.of(\"")
						.append(req.modId()).append("\", \"").append(nativeId).append("\"));\n");
				sb.append("\t\tItemStack stack = new ItemStack(item);\n");
				sb.append("\t\tplayer.setStackInHand(Hand.MAIN_HAND, stack);\n");
				// Count lightning before
				sb.append("\t\tint before = countLightning(world);\n");
				sb.append("\t\titem.use(world, player, Hand.MAIN_HAND);\n");
				// Wait for entity to be processed
				sb.append("\t\tctx.waitAndRun(3, () -> {\n");
				sb.append("\t\t\tint after = countLightning(world);\n");
				sb.append("\t\t\tif (after <= before) {\n");
				sb.append("\t\t\t\tthrow new AssertionError(\"No lightning entity spawned — behavior did not execute. before=\" + before + \" after=\" + after);\n");
				sb.append("\t\t\t}\n");
				sb.append("\t\t\tctx.complete();\n");
				sb.append("\t\t});\n");
				sb.append("\t}\n\n");

				// P1-2: Test cooldown expiry — after cooldown, use succeeds again
				testNum++;
				int waitTicks = cooldown + 5;
				int expiryTimeout = Math.max(200, waitTicks + 20);
				sb.append("\t@GameTest(templateName = \"fabric-gametest-api-v1:empty\", batchId = \"exported\", tickLimit = ").append(expiryTimeout).append(")\n");
				sb.append("\tpublic static void itemLightningCooldownExpiry").append(testNum)
						.append("(TestContext ctx) {\n");
				sb.append("\t\tServerWorld world = ctx.getWorld();\n");
				sb.append("\t\tServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();\n");
				sb.append("\t\tvar item = Registries.ITEM.get(Identifier.of(\"")
						.append(req.modId()).append("\", \"").append(nativeId).append("\"));\n");
				sb.append("\t\tItemStack stack = new ItemStack(item);\n");
				sb.append("\t\tplayer.setStackInHand(Hand.MAIN_HAND, stack);\n");
				sb.append("\t\titem.use(world, player, Hand.MAIN_HAND);\n");
				// Wait for cooldown to expire + buffer
				sb.append("\t\tctx.waitAndRun(").append(waitTicks).append(", () -> {\n");
				sb.append("\t\t\tvar result = item.use(world, player, Hand.MAIN_HAND);\n");
				sb.append("\t\t\tif (result.getResult() != ActionResult.SUCCESS) {\n");
				sb.append("\t\t\t\tthrow new AssertionError(\"Cooldown expiry failed: use should succeed after ").append(cooldown).append(" ticks\");\n");
				sb.append("\t\t\t}\n");
				sb.append("\t\t\tctx.complete();\n");
				sb.append("\t\t});\n");
				sb.append("\t}\n\n");
			}
		}
		for (String id : req.blockIds()) {
			BlockDefinition def = resolver.getBlock(id);
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
				sb.append("\t\tBlockPos pos = ctx.getAbsolutePos(new BlockPos(0, 2, 0));\n");
				sb.append("\t\tvar state = block.getDefaultState();\n");
				sb.append("\t\t// fresh placement: destroy any stale BlockEntity first\n");
				sb.append("\t\tworld.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());\n");
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

				// P1-2: Test cooldown expiry — after cooldown, block use succeeds again
				int blockCooldown = def.behaviors().stream()
						.filter(b -> b.actions().contains("apply_effect"))
						.mapToInt(dev.worldforge.definition.BehaviorDefinition::cooldownTicks)
						.max().orElse(0);
				testNum++;
				int blockWaitTicks = blockCooldown + 5;
				int blockExpiryTimeout = Math.max(200, blockWaitTicks + 20);
				sb.append("\t@GameTest(templateName = \"fabric-gametest-api-v1:empty\", batchId = \"exported\", tickLimit = ").append(blockExpiryTimeout).append(")\n");
				sb.append("\tpublic static void blockEffectCooldownExpiry").append(testNum)
						.append("(TestContext ctx) {\n");
				sb.append("\t\tServerWorld world = ctx.getWorld();\n");
				sb.append("\t\tServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();\n");
				sb.append("\t\tvar block = Registries.BLOCK.get(Identifier.of(\"")
						.append(req.modId()).append("\", \"").append(nativeId).append("\"));\n");
				sb.append("\t\tBlockPos pos = ctx.getAbsolutePos(new BlockPos(0, 2, 0));\n");
				sb.append("\t\tvar state = block.getDefaultState();\n");
				sb.append("\t\tworld.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());\n");
				sb.append("\t\tworld.setBlockState(pos, state);\n");
				sb.append("\t\tvar hit = new BlockHitResult(new Vec3d(0.5, 1.0, 0.5), Direction.NORTH, pos, false);\n");
				sb.append("\t\tvar customBlock = (").append(blockClassName).append(") block;\n");
				sb.append("\t\tcustomBlock.onUse(state, world, pos, player, hit);\n");
				sb.append("\t\tctx.waitAndRun(").append(blockWaitTicks).append(", () -> {\n");
				sb.append("\t\t\tvar result = customBlock.onUse(state, world, pos, player, hit);\n");
				sb.append("\t\t\tif (result != ActionResult.SUCCESS) {\n");
				sb.append("\t\t\t\tthrow new AssertionError(\"Cooldown expiry failed: block use should succeed after ").append(blockCooldown).append(" ticks\");\n");
				sb.append("\t\t\t}\n");
				sb.append("\t\t\tvar effect = Registries.STATUS_EFFECT.getEntry(Identifier.of(\"minecraft:")
						.append(effectName).append("\")).orElse(null);\n");
				sb.append("\t\t\tif (effect == null || !player.hasStatusEffect(effect)) {\n");
				sb.append("\t\t\t\tthrow new AssertionError(\"Effect not re-applied after cooldown expiry\");\n");
				sb.append("\t\t\t}\n");
				sb.append("\t\t\tctx.complete();\n");
				sb.append("\t\t});\n");
				sb.append("\t}\n\n");

				// v0.5.3 Phase 4: BlockEntity cooldown state must survive an NBT
				// round-trip (chunk save -> world reload semantics)
				testNum++;
				sb.append("\t@GameTest(templateName = \"fabric-gametest-api-v1:empty\", batchId = \"exported\")\n");
				sb.append("\tpublic static void blockEntityPersistence").append(testNum)
						.append("(TestContext ctx) {\n");
				sb.append("\t\tServerWorld world = ctx.getWorld();\n");
				sb.append("\t\tvar block = Registries.BLOCK.get(Identifier.of(\"")
						.append(req.modId()).append("\", \"").append(nativeId).append("\"));\n");
				sb.append("\t\tvar state = block.getDefaultState();\n");
				sb.append("\t\t// unique per-test absolute positions — other tests share raw coords\n");
				sb.append("\t\tBlockPos pos = ctx.getAbsolutePos(new BlockPos(0, 2, 0));\n");
				sb.append("\t\t// destroy any stale block entity left by earlier tests at this spot\n");
				sb.append("\t\tworld.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());\n");
				sb.append("\t\tworld.setBlockState(pos, state);\n");
				sb.append("\t\tvar be = world.getBlockEntity(pos);\n");
				sb.append("\t\tif (!(be instanceof ").append(blockClassName).append("Entity entity)) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"BlockEntity missing after placement — per-instance cooldown unavailable\");\n");
				sb.append("\t\t}\n");
				sb.append("\t\tentity.markUsed(world.getTime());\n");
				sb.append("\t\tlong savedTick = entity.lastUseTick();\n");
				sb.append("\t\tvar nbt = entity.createNbt(world.getRegistryManager());\n");
				sb.append("\t\tif (!nbt.getBoolean(\"HasBeenUsed\")\n");
				sb.append("\t\t\t\t|| nbt.getLong(\"LastUseTick\") != savedTick) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"NBT must capture the cooldown state on save\");\n");
				sb.append("\t\t}\n");
				sb.append("\t\t// simulate chunk reload: a fresh entity restores the saved NBT\n");
				sb.append("\t\tBlockPos pos2 = ctx.getAbsolutePos(new BlockPos(2, 2, 0));\n");
				sb.append("\t\tworld.setBlockState(pos2, net.minecraft.block.Blocks.AIR.getDefaultState());\n");
				sb.append("\t\tworld.setBlockState(pos2, state);\n");
				sb.append("\t\tvar be2 = world.getBlockEntity(pos2);\n");
				sb.append("\t\tif (!(be2 instanceof ").append(blockClassName).append("Entity restored)) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"second BlockEntity missing\");\n");
				sb.append("\t\t}\n");
				sb.append("\t\tif (restored.hasBeenUsed()) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"fresh entity must start unused\");\n");
				sb.append("\t\t}\n");
				sb.append("\t\trestored.read(nbt, world.getRegistryManager());\n");
				sb.append("\t\tif (!restored.hasBeenUsed() || restored.lastUseTick() != savedTick) {\n");
				sb.append("\t\t\tthrow new AssertionError(\"cooldown state must survive NBT round-trip (world reload)\");\n");
				sb.append("\t\t}\n");
				sb.append("\t\tctx.complete();\n");
				sb.append("\t}\n\n");
			}
		}
		sb.append("}\n");

		// P1-2: Helper method to count lightning entities
		sb.insert(sb.length() - 2, "\tprivate static int countLightning(ServerWorld world) {\n"
				+ "\t\tint count = 0;\n"
				+ "\t\tfor (var e : world.iterateEntities()) {\n"
				+ "\t\t\tif (e.getType() == EntityType.LIGHTNING_BOLT) count++;\n"
				+ "\t\t}\n"
				+ "\t\treturn count;\n"
				+ "\t}\n\n");

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
			ItemDefinition def = resolver.getItem(id);
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
			ItemDefinition def = resolver.getItem(id);
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
			BlockDefinition def = resolver.getBlock(id);
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
			if (hasBehaviors) {
				// v0.5.3 Phase 3: per-instance cooldown state via BlockEntity
				sb.append("\t\t").append(blockClassName(def)).append("Entity.registerType(").append(field).append(");\n");
			}
		}
		sb.append("\t}\n");
		sb.append("}\n");
		Files.writeString(src.resolve("ModBlocks.java"), sb.toString());

		// Generate custom Block subclasses for blocks with behaviors
		for (String id : req.blockIds()) {
			BlockDefinition def = resolver.getBlock(id);
			if (def != null && !def.behaviors().isEmpty()) {
				writeCustomBlock(src, def);
			}
		}
	}

	private void writeCustomBlock(Path src, BlockDefinition def) throws IOException {
		var pkg = req.modId().replace("_", ".");
		var className = blockClassName(def);
		var entityName = className + "Entity";
		writeBlockEntity(src, def);

		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.minecraft.block.Block;\n");
		sb.append("import net.minecraft.block.BlockEntityProvider;\n");
		sb.append("import net.minecraft.block.BlockState;\n");
		sb.append("import net.minecraft.block.entity.BlockEntity;\n");
		sb.append("import net.minecraft.entity.player.PlayerEntity;\n");
		sb.append("import net.minecraft.server.world.ServerWorld;\n");
		sb.append("import net.minecraft.util.ActionResult;\n");
		sb.append("import net.minecraft.util.hit.BlockHitResult;\n");
		sb.append("import net.minecraft.util.math.BlockPos;\n");
		sb.append("import net.minecraft.world.World;\n\n");
		sb.append("/**\n * Custom block with exported behaviors for ").append(escapeJava(def.name())).append(".\n");
		sb.append(" *\n * v0.5.3: cooldown state lives in a per-instance {@link ").append(entityName).append("}\n");
		sb.append(" * (persisted via NBT) instead of a static map — isolated per world save,\n");
		sb.append(" * cleared automatically when the block is removed, and bound to real\n");
		sb.append(" * block instances rather than coordinates.\n */\n");
		sb.append("public final class ").append(className).append(" extends Block implements BlockEntityProvider {\n\n");

		int cooldownTicks = def.behaviors().stream()
				.mapToInt(dev.worldforge.definition.BehaviorDefinition::cooldownTicks)
				.max().orElse(0);
		sb.append("\tprivate static final int COOLDOWN_TICKS = ").append(cooldownTicks).append(";\n\n");

		sb.append("\tpublic ").append(className).append("(Settings settings) {\n");
		sb.append("\t\tsuper(settings);\n");
		sb.append("\t}\n\n");

		// v0.5.3: world-created per-instance state
		sb.append("\t@Override\n");
		sb.append("\tpublic BlockEntity createBlockEntity(BlockPos pos, BlockState state) {\n");
		sb.append("\t\treturn new ").append(entityName).append("(pos, state);\n");
		sb.append("\t}\n\n");

		// Override onUse() — right click on block (public for testability).
		// Cooldown is read from / written to the block's own BlockEntity, so it
		// survives chunk unload/reload and world restarts, is isolated per world
		// save, and dies with the block when the block is broken.
		sb.append("\t@Override\n");
		sb.append("\tpublic ActionResult onUse(BlockState state, World world, BlockPos pos,\n");
		sb.append("\t\t\tPlayerEntity player, BlockHitResult hit) {\n");
		sb.append("\t\tif (world.isClient()) return ActionResult.PASS;\n");
		sb.append("\t\tif (!(world.getBlockEntity(pos) instanceof ").append(entityName).append(" be)) {\n");
		sb.append("\t\t\treturn ActionResult.PASS;\n");
		sb.append("\t\t}\n");
		sb.append("\t\tvar sw = (ServerWorld) world;\n");
		sb.append("\t\tlong now = sw.getTime();\n");
		sb.append("\t\t// time rollback (e.g. /time set backwards) invalidates the cooldown\n");
		sb.append("\t\tif (be.hasBeenUsed() && COOLDOWN_TICKS > 0 && now >= be.lastUseTick()\n");
		sb.append("\t\t\t\t&& now - be.lastUseTick() < COOLDOWN_TICKS) {\n");
		sb.append("\t\t\treturn ActionResult.PASS;\n");
		sb.append("\t\t}\n");

		// Generate action code for each behavior
		for (var behavior : def.behaviors()) {
			for (String action : behavior.actions()) {
				generateBlockActionCode(sb, action, behavior);
			}
		}

		sb.append("\t\tbe.markUsed(now);\n");
		sb.append("\t\treturn ActionResult.SUCCESS;\n");
		sb.append("\t}\n");

		sb.append("}\n");
		Files.writeString(src.resolve(className + ".java"), sb.toString());
	}

	/**
	 * v0.5.3 Phase 3: generate a dedicated BlockEntity that stores the block's
	 * cooldown state with full NBT persistence.
	 */
	private void writeBlockEntity(Path src, BlockDefinition def) throws IOException {
		var pkg = req.modId().replace("_", ".");
		var className = blockClassName(def);
		var entityName = className + "Entity";
		var beId = nativeBlockId(def.id()) + "_entity";

		var sb = new StringBuilder();
		sb.append("package ").append(pkg).append(";\n\n");
		sb.append("import net.minecraft.block.Block;\n");
		sb.append("import net.minecraft.block.BlockState;\n");
		sb.append("import net.minecraft.block.entity.BlockEntity;\n");
		sb.append("import net.minecraft.block.entity.BlockEntityType;\n");
		sb.append("import net.minecraft.nbt.NbtCompound;\n");
		sb.append("import net.minecraft.registry.Registries;\n");
		sb.append("import net.minecraft.registry.Registry;\n");
		sb.append("import net.minecraft.util.Identifier;\n");
		sb.append("import net.minecraft.util.math.BlockPos;\n");
		sb.append("import net.minecraft.registry.RegistryWrapper;\n\n");
		sb.append("/**\n * Per-instance state for ").append(escapeJava(def.name())).append(".\n");
		sb.append(" *\n * The cooldown timestamp is persisted in the chunk NBT, so it survives\n");
		sb.append(" * chunk unload/reload and world restart, is scoped to the current world\n");
		sb.append(" * save, and is destroyed together with the block.\n */\n");
		sb.append("public final class ").append(entityName).append(" extends BlockEntity {\n\n");
		sb.append("\tprivate static final String NBT_USED = \"HasBeenUsed\";\n");
		sb.append("\tprivate static final String NBT_LAST_USE = \"LastUseTick\";\n\n");
		sb.append("\t/** Assigned once during mod init (after block registration). */\n");
		sb.append("\tpublic static BlockEntityType<").append(entityName).append("> TYPE;\n\n");
		sb.append("\tprivate boolean hasBeenUsed = false;\n");
		sb.append("\tprivate long lastUseTick = 0L;\n\n");
		sb.append("\tpublic ").append(entityName).append("(BlockPos pos, BlockState state) {\n");
		sb.append("\t\tsuper(TYPE, pos, state);\n");
		sb.append("\t}\n\n");
		sb.append("\tpublic boolean hasBeenUsed() {\n");
		sb.append("\t\treturn hasBeenUsed;\n");
		sb.append("\t}\n\n");
		sb.append("\tpublic long lastUseTick() {\n");
		sb.append("\t\treturn lastUseTick;\n");
		sb.append("\t}\n\n");
		sb.append("\tpublic void markUsed(long tick) {\n");
		sb.append("\t\tthis.hasBeenUsed = true;\n");
		sb.append("\t\tthis.lastUseTick = tick;\n");
		sb.append("\t\tmarkDirty();\n");
		sb.append("\t}\n\n");
		sb.append("\t/** Register the block entity type. Call AFTER the block is registered. */\n");
		sb.append("\tpublic static void registerType(Block... blocks) {\n");
		sb.append("\t\tTYPE = Registry.register(Registries.BLOCK_ENTITY_TYPE,\n");
		sb.append("\t\t\t\tIdentifier.of(\"").append(req.modId()).append("\", \"").append(beId).append("\"),\n");
		sb.append("\t\t\t\tBlockEntityType.Builder.create(").append(entityName).append("::new, blocks).build());\n");
		sb.append("\t}\n\n");
		sb.append("\t@Override\n");
		sb.append("\tprotected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {\n");
		sb.append("\t\tsuper.writeNbt(nbt, registries);\n");
		sb.append("\t\tnbt.putBoolean(NBT_USED, hasBeenUsed);\n");
		sb.append("\t\tnbt.putLong(NBT_LAST_USE, lastUseTick);\n");
		sb.append("\t}\n\n");
		sb.append("\t@Override\n");
		sb.append("\tprotected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {\n");
		sb.append("\t\tsuper.readNbt(nbt, registries);\n");
		sb.append("\t\thasBeenUsed = nbt.contains(NBT_USED) && nbt.getBoolean(NBT_USED);\n");
		sb.append("\t\tlastUseTick = nbt.contains(NBT_LAST_USE) ? nbt.getLong(NBT_LAST_USE) : 0L;\n");
		sb.append("\t}\n");
		sb.append("}\n");
		Files.writeString(src.resolve(entityName + ".java"), sb.toString());
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
			ItemDefinition def = resolver.getItem(id);
			if (def == null) continue;
			if (!first) sb.append(",\n");
			first = false;
			sb.append("\t\"item.").append(req.modId()).append(".").append(nativeItemId(id))
					.append("\": \"").append(escapeJson(def.name())).append("\"");
		}
		for (String id : req.blockIds()) {
			BlockDefinition def = resolver.getBlock(id);
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
			ItemDefinition def = resolver.getItem(id);
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
			BlockDefinition def = resolver.getBlock(id);
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
