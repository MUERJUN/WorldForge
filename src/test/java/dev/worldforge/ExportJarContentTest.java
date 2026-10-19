package dev.worldforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.1 Section 四: Verify exported JAR content is clean.
 * No API keys, world saves, or private configuration should be present.
 */
class ExportJarContentTest {

	@TempDir
	Path tempDir;

	@Test
	void exportedJarDoesNotContainApiKeyFiles() throws Exception {
		// Find the standalone mod JAR if it exists
		var jarPath = Path.of("build/standalone-export/thunder_mod/build/libs/thunder_mod-1.0.0.jar");
		if (!Files.exists(jarPath)) {
			// Try alternate name
			jarPath = Path.of("build/standalone-export/thunder_mod/build/libs");
			if (Files.exists(jarPath)) {
				try (var stream = Files.list(jarPath)) {
					var jar = stream.filter(p -> p.toString().endsWith(".jar"))
							.filter(p -> !p.toString().endsWith("-sources.jar"))
							.findFirst().orElse(null);
					if (jar != null) jarPath = jar;
				}
			}
		}
		assumeTrue(Files.exists(jarPath), "Standalone JAR must exist for content check");

		try (var jar = new JarFile(jarPath.toFile())) {
			var entries = jar.entries();
			while (entries.hasMoreElements()) {
				var entry = entries.nextElement();
				var name = entry.getName().toLowerCase();

				// Must NOT contain API keys or credentials
				assertFalse(name.contains("api_key") || name.contains("apikey"),
						"JAR must not contain API key files: " + name);
				assertFalse(name.contains("deepseek") || name.contains("openai"),
						"JAR must not contain AI provider config: " + name);
				assertFalse(name.contains("world_save") || name.contains("level.dat"),
						"JAR must not contain world saves: " + name);
				assertFalse(name.contains("agent.json") || name.contains("config.json"),
						"JAR must not contain WorldForge config: " + name);
			}
		}
	}

	@Test
	void exportedJarContainsModClasses() throws Exception {
		var jarDir = Path.of("build/standalone-export/thunder_mod/build/libs");
		if (!Files.exists(jarDir)) {
			assumeTrue(false, "Standalone build output not found");
			return;
		}

		Path jarPath = null;
		try (var stream = Files.list(jarDir)) {
			jarPath = stream.filter(p -> p.toString().endsWith(".jar"))
					.filter(p -> !p.toString().endsWith("-sources.jar"))
					.findFirst().orElse(null);
		}
		assumeTrue(jarPath != null, "JAR must exist");

		try (var jar = new JarFile(jarPath.toFile())) {
			// Must contain the mod's main class
			assertNotNull(jar.getEntry("thunder/mod/ThunderModMod.class"),
					"JAR must contain ThunderModMod.class");
			// Must contain the custom item class with behavior
			assertNotNull(jar.getEntry("thunder/mod/ThunderSwordItem.class"),
					"JAR must contain ThunderSwordItem.class (behavior implementation)");
			// Must contain the custom block class with behavior
			assertNotNull(jar.getEntry("thunder/mod/MagicDeviceBlock.class"),
					"JAR must contain MagicDeviceBlock.class (behavior implementation)");
			// Must contain fabric.mod.json
			assertNotNull(jar.getEntry("fabric.mod.json"),
					"JAR must contain fabric.mod.json");
		}
	}

	@Test
	void exportedJarDoesNotDependOnWorldForge() throws Exception {
		var jarDir = Path.of("build/standalone-export/thunder_mod/build/libs");
		if (!Files.exists(jarDir)) {
			assumeTrue(false, "Standalone build output not found");
			return;
		}

		Path jarPath = null;
		try (var stream = Files.list(jarDir)) {
			jarPath = stream.filter(p -> p.toString().endsWith(".jar"))
					.filter(p -> !p.toString().endsWith("-sources.jar"))
					.findFirst().orElse(null);
		}
		assumeTrue(jarPath != null, "JAR must exist");

		try (var jar = new JarFile(jarPath.toFile())) {
			var modJson = jar.getEntry("fabric.mod.json");
			assertNotNull(modJson, "fabric.mod.json must exist");
			var content = new String(jar.getInputStream(modJson).readAllBytes());
			// Must NOT declare WorldForge as a dependency
			assertFalse(content.contains("worldforge"),
					"fabric.mod.json must not depend on WorldForge:\n" + content);
		}
	}

	private static void assumeTrue(boolean condition, String message) {
		if (!condition) {
			System.out.println("[SKIP] " + message);
		}
		org.junit.jupiter.api.Assumptions.assumeTrue(condition, message);
	}
}
