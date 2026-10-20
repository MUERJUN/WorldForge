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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.0: Real end-to-end build test — generates a project and builds it
 * with Gradle to produce a standalone JAR.
 *
 * <p>This test is slow (downloads Fabric dependencies) but proves the
 * exported project compiles and produces a real mod JAR.</p>
 */
class ExportBuildIntegrationTest {

	Path tempDir;

	@org.junit.jupiter.api.BeforeEach
	void setup() throws Exception {
		tempDir = java.nio.file.Files.createTempDirectory("wf-export-test");
	}

	@org.junit.jupiter.api.AfterEach
	void cleanup() {
		// best-effort cleanup; Gradle may lock files
		try {
			deleteRecursively(tempDir);
		} catch (Exception ignored) {}
	}

	private static void deleteRecursively(Path p) throws java.io.IOException {
		if (java.nio.file.Files.exists(p)) {
			try (var stream = java.nio.file.Files.walk(p)) {
				stream.sorted(java.util.Comparator.reverseOrder())
					.forEach(path -> { try { java.nio.file.Files.deleteIfExists(path); } catch (Exception ignored) {} });
			}
		}
	}

	@Test
	void generatedProjectBuildsAndProducesJar() throws Exception {
		// setup stores
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir.resolve("bs")));
		WorldForgeRuntimeManager.attachStoreForTesting(
				new dev.worldforge.persistence.DefinitionStore(tempDir.resolve("is")));

		// create definitions
		var itemDef = new ItemDefinition(1, "worldforge:exported_sword", "导出之剑",
				"一把从 WorldForge 导出的剑",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 7.0), List.of());
		WorldForgeRuntimeManager.createItem(itemDef);

		var blockDef = BlockDefinition.create("worldforge:exported_block", "导出方块",
				"从 WorldForge 导出的方块",
				"minecraft:furnace", BlockDefinition.Category.DECORATION, 2.0,
				List.of(), 200, null, 0);
		BlockRuntimeManager.create(blockDef);

		// generate project
		var exportDir = tempDir.resolve("export");
		Files.createDirectories(exportDir);
		var req = new ModExporter.ExportRequest(
				"exported_mod", "Exported Mod", "1.0.0", exportDir,
				List.of("worldforge:exported_sword"),
				List.of("worldforge:exported_block"));

		var errors = ModExporter.validate(req);
		assertTrue(errors.isEmpty(), "validation must pass: " + errors);

		var projectDir = exportDir.resolve("exported_mod");
		var gen = new TemplateProjectGenerator(req, projectDir);
		gen.generate();

		// copy Gradle wrapper from WorldForge project
		var wfRoot = Path.of(".").toAbsolutePath();
		gen.copyGradleWrapper(wfRoot);

		// verify wrapper exists
		assertTrue(Files.exists(projectDir.resolve("gradlew")), "gradlew must exist");
		assertTrue(Files.exists(projectDir.resolve("gradle/wrapper/gradle-wrapper.jar")),
				"gradle-wrapper.jar must exist");

		// build the project (same command as ExportExecutor: skip runGameTest —
		// a nested Minecraft inside the test JVM exhausts the commit charge)
		var isWin = System.getProperty("os.name").toLowerCase().contains("win");
		var gradlew = projectDir.resolve(isWin ? "gradlew.bat" : "gradlew").toAbsolutePath().toString();
		var pb = new ProcessBuilder(gradlew, "build", "-x", "runGameTest",
				"--no-daemon", "--console=plain");
		pb.directory(projectDir.toFile());
		pb.redirectErrorStream(true);
		pb.environment().put("JAVA_HOME", System.getenv("JAVA_HOME"));
		var proc = pb.start();
		var output = new StringBuilder();
		try (var reader = proc.inputReader()) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append('\n');
			}
		}
		int exit = proc.waitFor();

		if (exit != 0) {
			fail("Gradle build failed (exit " + exit + "):\n"
					+ output.substring(0, Math.min(output.length(), 3000)));
		}

		// find the JAR
		var jarDir = projectDir.resolve("build/libs");
		assertTrue(Files.exists(jarDir), "build/libs must exist");
		Path jarPath = null;
		try (var stream = Files.list(jarDir)) {
			jarPath = stream
					.filter(p -> p.toString().endsWith(".jar")
							&& !p.toString().endsWith("-sources.jar"))
					.findFirst()
					.orElse(null);
		}
		assertNotNull(jarPath, "JAR must be produced by build");
		assertTrue(Files.size(jarPath) > 1000, "JAR must be non-trivial");

		// verify JAR contains fabric.mod.json
		var jarCheck = new ProcessBuilder("jar", "tf", jarPath.toAbsolutePath().toString());
		jarCheck.redirectErrorStream(true);
		var cp = jarCheck.start();
		var jarContents = new StringBuilder();
		try (var reader = cp.inputReader()) {
			String line;
			while ((line = reader.readLine()) != null) {
				jarContents.append(line).append('\n');
			}
		}
		cp.waitFor();
		var contents = jarContents.toString();
		assertTrue(contents.contains("fabric.mod.json"), "JAR must contain fabric.mod.json");
		assertTrue(contents.contains("ExportedModMod.class"), "JAR must contain main class");
		assertTrue(contents.contains("ModItems.class"), "JAR must contain ModItems");
		assertTrue(contents.contains("ModBlocks.class"), "JAR must contain ModBlocks");
		assertTrue(contents.contains("en_us.json"), "JAR must contain lang file");

		// verify no secrets in JAR
		assertFalse(contents.contains("agent.json"), "JAR must not contain agent.json");
		assertFalse(contents.contains("apiKey"), "JAR must not contain API keys");

		BlockRuntimeManager.release();
		WorldForgeRuntimeManager.onServerStopped();
	}
}
