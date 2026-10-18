package dev.worldforge;

import dev.worldforge.exporter.ModExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.0: Mod exporter validation and safety tests.
 */
class ModExporterTest {

	@TempDir
	Path tempDir;

	@Test
	void validateRejectsBadModId() {
		var req = new ModExporter.ExportRequest(
				"BadID", "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "uppercase mod ID must be rejected");
		assertTrue(errors.stream().anyMatch(e -> e.contains("Mod ID")));
	}

	@Test
	void validateRejectsNumericStartModId() {
		var req = new ModExporter.ExportRequest(
				"123mod", "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "mod ID starting with number must be rejected");
	}

	@Test
	void validateRejectsBadVersion() {
		var req = new ModExporter.ExportRequest(
				"testmod", "Test", "v1.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "non-X.Y.Z version must be rejected");
	}

	@Test
	void validateRejectsEmptyModName() {
		var req = new ModExporter.ExportRequest(
				"testmod", "", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "empty mod name must be rejected");
	}

	@Test
	void validateRejectsNonEmptyOutputDir() throws Exception {
		Files.writeString(tempDir.resolve("existing.txt"), "data");
		var req = new ModExporter.ExportRequest(
				"testmod", "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "non-empty output dir must be rejected");
		assertTrue(errors.stream().anyMatch(e -> e.contains("空")));
	}

	@Test
	void validateAcceptsGoodRequest() {
		var req = new ModExporter.ExportRequest(
				"my_awesome_mod", "My Mod", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertTrue(errors.isEmpty(), "good request must pass: " + errors);
	}

	@Test
	void validateRejectsNonExistentItem() {
		var req = new ModExporter.ExportRequest(
				"testmod", "Test", "1.0.0", tempDir,
				List.of("worldforge:nonexistent"), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "non-existent item must be rejected");
	}

	@Test
	void validateRejectsPathTraversalModId() {
		// path traversal attempt in mod ID
		var req = new ModExporter.ExportRequest(
				"test/../../../etc", "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "path traversal in mod ID must be rejected");
	}

	@Test
	void checkUnsupportedReturnsEmptyForNoBehaviors() {
		var req = new ModExporter.ExportRequest(
				"testmod", "Test", "1.0.0", tempDir, List.of(), List.of());
		var warnings = ModExporter.checkUnsupported(req);
		assertTrue(warnings.isEmpty(), "no definitions = no warnings");
	}

	@Test
	void modIdWithOnlyUnderscoresRejected() {
		var req = new ModExporter.ExportRequest(
				"__", "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "mod ID of only underscores must be rejected");
	}

	@Test
	void modIdTooLongRejected() {
		var req = new ModExporter.ExportRequest(
				"a".repeat(33), "Test", "1.0.0", tempDir, List.of(), List.of());
		var errors = ModExporter.validate(req);
		assertFalse(errors.isEmpty(), "mod ID > 32 chars must be rejected");
	}
}
