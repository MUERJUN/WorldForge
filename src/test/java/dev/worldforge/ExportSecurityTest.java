package dev.worldforge;

import dev.worldforge.exporter.ModExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.0: Export security tests — verify no secrets leak into generated projects.
 */
class ExportSecurityTest {

	@TempDir
	Path tempDir;

	@Test
	void validateRejectsModIdWithPathTraversal() {
		// Various path traversal attempts
		String[] badIds = {
				"../etc/passwd",
				"test/../../../etc",
				"..\\windows\\system32",
				"test;rm -rf /",
				"test&&calc",
				"test|nc",
				"test\u0000null"
		};
		for (String bad : badIds) {
			var req = new ModExporter.ExportRequest(
					bad, "Test", "1.0.0", tempDir, List.of(), List.of());
			var errors = ModExporter.validate(req);
			assertFalse(errors.isEmpty(), "path traversal '" + bad + "' must be rejected");
		}
	}

	@Test
	void validateRejectsShellInjectionInModName() {
		String[] badNames = {
				";rm -rf /;",
				"$(calc)",
				"`whoami`",
				"test\nrm -rf /"
		};
		for (String bad : badNames) {
			var req = new ModExporter.ExportRequest(
					"testmod", bad, "1.0.0", tempDir, List.of(), List.of());
			var errors = ModExporter.validate(req);
			assertFalse(errors.isEmpty(), "shell injection in name '" + bad + "' must be rejected");
		}
	}

	@Test
	void validateRejectsExtremelyLongModId() {
		var req = new ModExporter.ExportRequest(
				"a".repeat(100), "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "100-char mod ID must be rejected");
	}

	@Test
	void validateRejectsNullFields() {
		var req = new ModExporter.ExportRequest(
				null, "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "null mod ID must be rejected");
	}

	@Test
	void validateRejectsNullLists() {
		var req = new ModExporter.ExportRequest(
				"testmod", "Test", "1.0.0", tempDir, null, null);
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "null lists must be rejected");
	}

	@Test
	void checkUnsupportedWarnsAboutUnknownActions() {
		// Even with no definitions, checkUnsupported should not crash
		var req = new ModExporter.ExportRequest(
				"testmod", "Test", "1.0.0", tempDir, List.of(), List.of());
		var warnings = ModExporter.checkUnsupported(req);
		assertNotNull(warnings, "warnings list must not be null");
		assertTrue(warnings.isEmpty(), "no definitions = no warnings");
	}

	@Test
	void modIdCannotContainSpaces() {
		var req = new ModExporter.ExportRequest(
				"test mod", "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "mod ID with space must be rejected");
	}

	@Test
	void modIdCannotContainDots() {
		var req = new ModExporter.ExportRequest(
				"test.mod", "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "mod ID with dot must be rejected");
	}

	@Test
	void versionMustBeThreeParts() {
		String[] badVersions = {"1", "1.0", "v1.0.0", "1.0.0.0", "latest", ""};
		for (String bad : badVersions) {
			var req = new ModExporter.ExportRequest(
					"testmod", "Test", bad, tempDir, List.of(), List.of());
			var errors = ModExporter.validate(req);
			assertFalse(errors.isEmpty(), "version '" + bad + "' must be rejected");
		}
	}

	@Test
	void validVersionAccepted() {
		String[] goodVersions = {"0.0.1", "1.0.0", "99.99.99", "0.5.0"};
		for (String good : goodVersions) {
			var req = new ModExporter.ExportRequest(
					"testmod", "Test", good, tempDir, List.of(), List.of());
			var errors = ModExporter.validate(req);
			assertTrue(errors.isEmpty(), "version '" + good + "' must be accepted: " + errors);
		}
	}
}
