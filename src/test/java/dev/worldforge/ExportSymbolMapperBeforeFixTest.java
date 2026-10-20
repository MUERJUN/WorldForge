package dev.worldforge;

import dev.worldforge.exporter.ExportSymbolMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.6 P0-1: symbol mapping contract — before-fix evidence.
 *
 * <p>The old generator built class names by splitting native IDs on '_'
 * and capitalizing, which maps 'magic-sword', 'magic.device', 'magic_core'
 * and 'magic__core' onto colliding/illegal Java identifiers. ExportSymbolMapper
 * must be a deterministic, conflict-detecting, 6-target mapping.</p>
 */
class ExportSymbolMapperBeforeFixTest {

	@Test
	void hyphenAndDotIdsProduceDistinctClassNames() {
		// old: nativeItemId collapses both to "magic-sword"/"magic.device"
		// then PascalCase-on-'_' produces "Magic-sword" / "Magic.device" —
		// ILLEGAL Java identifiers
		String c1 = ExportSymbolMapper.className("worldforge:magic-sword");
		String c2 = ExportSymbolMapper.className("worldforge:magic.device");
		assertTrue(c1.matches("[A-Za-z][A-Za-z0-9]*"), "legal identifier: " + c1);
		assertTrue(c2.matches("[A-Za-z][A-Za-z0-9]*"), "legal identifier: " + c2);
		assertNotEquals(c1, c2, "distinct ids must map to distinct classes");
	}

	@Test
	void underscoreRunsProduceLegalDistinctClassNames() {
		var m = ExportSymbolMapper.create();
		String c1 = m.registerClass("worldforge:magic_core");
		String c2 = m.registerClass("worldforge:magic__core");
		assertTrue(c1.matches("[A-Za-z_][A-Za-z0-9_]*"), c1);
		assertTrue(c2.matches("[A-Za-z_][A-Za-z0-9_]*"), c2);
		if (c1.equals(c2)) {
			// folding collision — must be flagged, not silent
			assertTrue(m.hasConflict(), "magic_core vs magic__core fold must be flagged");
		}
	}

	@Test
	void javaKeywordsAreEscaped() {
		// field names must not be Java keywords
		String f = ExportSymbolMapper.fieldName("worldforge:int");
		assertFalse(isJavaKeyword(f), "field must not be a keyword: " + f);
		assertTrue(f.matches("[A-Za-z_][A-Za-z0-9_]*"));
		String c2 = ExportSymbolMapper.className("worldforge:interface");
		assertFalse(isJavaKeyword(c2), "class must not be a keyword: " + c2);
	}

	@Test
	void digitLeadingIdIsPrefixed() {
		String c = ExportSymbolMapper.className("worldforge:2fast");
		assertTrue(c.matches("[A-Za-z_][A-Za-z0-9]*"), "cannot start with digit: " + c);
	}

	@Test
	void packageNameFromModIdIsLegal() {
		// mod ids 'a_' and 'a__b' must not yield packages 'a.' / 'a..b'
		String p1 = ExportSymbolMapper.packageName("a_");
		assertTrue(p1.matches("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*"),
				"legal package: " + p1);
		assertFalse(p1.contains(".."), "empty package segment");
		assertFalse(p1.endsWith("."), "trailing dot");

		String p2 = ExportSymbolMapper.packageName("a__b");
		assertTrue(p2.matches("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*"), "legal: " + p2);
		assertFalse(p2.contains(".."));
	}

	@Test
	void sameMapperCollisionDetected() {
		var m = ExportSymbolMapper.create();
		String idA = "worldforge:magic-sword";
		String idB = "worldforge:magic_sword";
		// these two DISTINCT ids map to the SAME PascalCase class — mapper must detect
		String ca = m.registerClass(idA);
		String cb = m.registerClass(idB);
		if (ca.equals(cb)) {
			assertTrue(m.hasConflict(),
					"colliding class names must be recorded as a conflict");
		}
	}

	@Test
	void mapperIsDeterministic() {
		var a = ExportSymbolMapper.create();
		var b = ExportSymbolMapper.create();
		for (String id : List.of("worldforge:x-y", "worldforge:z_w", "worldforge:q.r")) {
			assertEquals(a.registerClass(id), b.registerClass(id));
		}
	}

	@Test
	void mapperRejectsEmptyAfterSanitize() {
		var m = ExportSymbolMapper.create();
		assertThrows(Exception.class, () -> m.registerClass("worldforge:..."),
				"id with no usable characters must be rejected with a clear error");
	}

	// ---- P0-3: hardcoded machine paths must not enter the repo ----------

	@Test
	void repoHasNoMachineSpecificPaths() throws Exception {
		var offenders = List.of("gradle.properties", "build.gradle", "settings.gradle");
		// machine-specific path patterns — url schemes (https://) excluded by
		// checking the surrounding context for '://'
		var patterns = List.of(
				"[A-Za-z]:[\\\\/]",
				"/e/javas", "/c/Users",
				"E:/javas", "C:/Users");
		for (String f : offenders) {
			if (!Files.exists(Path.of(f))) continue;
			String content = Files.readString(Path.of(f));
			for (var p : patterns) {
				var m = java.util.regex.Pattern.compile(p).matcher(content);
				while (m.find()) {
					// url schemes (https:/, http:/) match [A-Za-z]:/ too —
					// a REAL drive path's ':' is preceded by a single letter
					// that is itself preceded by a non-letter (start, /, _,
					// space). In 'https:/' the 's' follows 'http'.
					int idx = m.start();
					if (idx >= 2) {
						char c1 = content.charAt(idx - 1); // the drive letter
						char c0 = content.charAt(idx - 2); // before the letter
						if (Character.isLetterOrDigit(c0)) continue; // url scheme
					}
					// '://' double slash is always a url
					if (content.regionMatches(m.end(), "/", 0, 1)
							&& content.regionMatches(m.end() - 1, "/", 0, 1)) continue;
					String ctx = content.substring(Math.max(0, m.start() - 20),
							Math.min(content.length(), m.end() + 20));
					fail("machine-specific path '" + p + "' found in " + f + " near: " + ctx);
				}
			}
		}
	}

	// ---- P0-2: strict effect id + precise numbers ------------------------

	@Test
	void strictEffectIdRejectsInsteadOfRewriting() {
		// old sanitizeEffectId rewrote 'speed!extra' -> 'speedextra' (semantic change)
		assertThrows(Exception.class,
				() -> ExportSymbolMapper.strictEffectId("speed!extra"),
				"illegal chars must be REJECTED, not rewritten");
		assertThrows(Exception.class,
				() -> ExportSymbolMapper.strictEffectId("speed extra"),
				"spaces rejected");
		assertEquals("speed", ExportSymbolMapper.strictEffectId("speed"));
		assertEquals("fire_resistance", ExportSymbolMapper.strictEffectId("fire_resistance"));
	}

	@Test
	void strictEffectIdRejectsForeignNamespace() {
		// namespaced values must be rejected with an explicit message — the
		// template hardcodes the minecraft: namespace
		var e = assertThrows(Exception.class,
				() -> ExportSymbolMapper.strictEffectId("modid:custom_effect"));
		assertTrue(String.valueOf(e.getMessage()).contains("命名空间"),
				"must explain the namespace restriction");
	}

	@Test
	void preciseNumberConversionRejectsFractional() {
		// 2.9 must NOT silently truncate to 2
		assertThrows(Exception.class,
				() -> ExportSymbolMapper.preciseInt("duration", 2.9, 1, 100),
				"fractional must be rejected, not truncated");
	}

	@Test
	void preciseNumberConversionRejectsNonFinite() {
		assertThrows(Exception.class, () -> ExportSymbolMapper.preciseInt("duration", Double.NaN, 1, 100));
		assertThrows(Exception.class, () -> ExportSymbolMapper.preciseInt("duration", Double.POSITIVE_INFINITY, 1, 100));
	}

	@Test
	void preciseNumberConversionRejectsOverflow() {
		assertThrows(Exception.class,
				() -> ExportSymbolMapper.preciseInt("duration", Long.MAX_VALUE, 1, 7_200_000),
				"Long.MAX_VALUE must not wrap around to a legal value");
		assertThrows(Exception.class,
				() -> ExportSymbolMapper.preciseInt("duration", -1, 1, 100));
		assertEquals(0, ExportSymbolMapper.preciseInt("amplifier", 0, 0, 255));
		assertEquals(255, ExportSymbolMapper.preciseInt("amplifier", 255, 0, 255));
	}

	private static boolean isJavaKeyword(String s) {
		return java.util.Set.of("int","class","interface","public","private","new","return",
				"void","static","final","if","else","for","while","do","switch","case",
				"break","continue","this","super","import","package","boolean","char",
				"byte","short","long","float","double","null","true","false","instanceof",
				"extends","implements","enum","assert","try","catch","finally","throw",
				"throws","default","native","transient","volatile","synchronized","strictfp",
				"const","goto","var","record","sealed","permits","yield").contains(s);
	}
}
