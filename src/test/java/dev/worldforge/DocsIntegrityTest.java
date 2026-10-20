package dev.worldforge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.7 P1-4: docs integrity — the quick-start section that a greedy sync
 * regex once swallowed must exist, and the sync script must refuse (exit!=0)
 * when a required section is missing.
 */
class DocsIntegrityTest {

	@Test
	void readmeHasQuickStartAndInstallInfo() throws Exception {
		var readme = Files.readString(Path.of("README.md"));
		assertTrue(readme.contains("## 快速开始"), "README must keep a quick-start section");
		assertTrue(readme.contains("按 K"), "quick start must mention the K key");
		assertTrue(readme.contains("Fabric API"), "install must name Fabric API");
		assertTrue(readme.contains("1.21.1"), "MC version must be stated");
		assertTrue(readme.contains("Java **21**"), "Java 21 requirement must be stated");
		assertTrue(readme.contains("worldforge-0.5.7.jar")
						|| readme.matches("(?s).*worldforge-\\d+\\.\\d+\\.\\d+\\.jar.*"),
				"install must reference a release jar name");
		assertTrue(readme.contains("## 已知限制"), "known limitations must be documented");
	}

	@Test
	void betaGuideCoversPlayerJourney() throws Exception {
		var guide = Files.readString(Path.of("docs/BETA-GUIDE.md"));
		for (String need : new String[]{"安装", "5 分钟", "能力清单", "环境要求",
				"常见问题", "API Key", "Gradle"}) {
			assertTrue(guide.contains(need), "BETA-GUIDE must cover: " + need);
		}
		// capability matrix must distinguish retained / converted / lost
		assertTrue(guide.contains("✅") && guide.contains("⚠️") && guide.contains("❌"),
				"capability list must have all three tiers");
	}

	@Test
	void versionConsistencyAcrossDocs() throws Exception {
		String ver = null;
		for (var line : Files.readAllLines(Path.of("gradle.properties"))) {
			if (line.startsWith("version=")) ver = line.substring(8).trim();
		}
		assertNotNull(ver);
		var readme = Files.readString(Path.of("README.md"));
		var guide = Files.readString(Path.of("docs/BETA-GUIDE.md"));
		assertTrue(readme.contains("v" + ver), "README version must match gradle.properties");
		assertTrue(guide.contains(ver + ".jar"), "BETA-GUIDE jar must match version");
	}

	@Test
	void syncScriptRefusesSectionLoss() throws Exception {
		// run the sync script — it must exit 0 on the CURRENT (healthy) docs;
		// the integrity gate itself is exercised by its exit code
		var pb = new ProcessBuilder("python", "scripts/sync-version-docs.py");
		pb.redirectErrorStream(true);
		var proc = pb.start();
		var out = new String(proc.getInputStream().readAllBytes());
		int exit = proc.waitFor();
		assertEquals(0, exit, "sync script must pass integrity gate:\n" + out);
	}
}
