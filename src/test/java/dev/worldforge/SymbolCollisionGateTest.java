package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.exporter.ExportPlan;
import dev.worldforge.exporter.ModExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.7 P1-3: symbol-mapping unit tests for the reserved-name and
 * case-insensitive collision gates (the full three-project real-build
 * regression lives in StandaloneComplexExportRegressionTest which needs
 * Gradle; this test covers the pure decision logic fast).
 */
class SymbolCollisionGateTest {

	@TempDir
	Path tempDir;

	@BeforeEach
	void setup() {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir.resolve("bs")));
		dev.worldforge.runtime.WorldForgeRuntimeManager.attachStoreForTesting(
				new dev.worldforge.persistence.DefinitionStore(tempDir.resolve("is")));
	}

	@AfterEach
	void cleanup() {
		BlockRuntimeManager.release();
		dev.worldforge.runtime.WorldForgeRuntimeManager.onServerStopped();
	}

	private void item(String id, String action) {
		dev.worldforge.runtime.WorldForgeRuntimeManager.createItem(new ItemDefinition(1, id, "I", "",
				TextureSource.vanilla("minecraft:item/diamond_sword"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of(action), Map.of(), 50))));
	}

	@Test
	void hyphenUnderscoreFoldCollisionFlagged() {
		var m = dev.worldforge.exporter.ExportSymbolMapper.create();
		String a = m.registerClass("worldforge:magic-sword");
		String b = m.registerClass("worldforge:magic_sword");
		assertEquals("MagicSword", a, "first id keeps the folded name");
		assertEquals("MagicSword_2", b, "colliding id is disambiguated deterministically");
		assertTrue(m.hasConflict(), "fold collision must be flagged");
		assertFalse(m.conflicts().isEmpty());
	}

	@Test
	void dotUnderscoreFoldCollisionFlagged() {
		var m = dev.worldforge.exporter.ExportSymbolMapper.create();
		m.registerClass("worldforge:magic.core");
		m.registerClass("worldforge:magic_core");
		assertTrue(m.hasConflict());
	}

	@Test
	void disambiguatedNamesRemainDeterministic() {
		var m1 = dev.worldforge.exporter.ExportSymbolMapper.create();
		var m2 = dev.worldforge.exporter.ExportSymbolMapper.create();
		for (String id : List.of("worldforge:a-b", "worldforge:a_b", "worldforge:a.b")) {
			assertEquals(m1.registerClass(id), m2.registerClass(id));
		}
		// three-way fold: a-b, a_b, a.b -> A, A_2, A_3
		var m3 = dev.worldforge.exporter.ExportSymbolMapper.create();
		var n1 = m3.registerClass("worldforge:a-b");
		var n2 = m3.registerClass("worldforge:a_b");
		var n3 = m3.registerClass("worldforge:a.b");
		assertEquals(3, List.of(n1, n2, n3).stream().distinct().count(),
				"disambiguated names must be unique: " + n1 + " " + n2 + " " + n3);
	}

	@Test
	void keywordAndDigitEdgeCases() {
		// PascalCase of "class" is "Class" — legal, not a keyword
		assertEquals("Class", dev.worldforge.exporter.ExportSymbolMapper.className("worldforge:class"));
		// camelCase field "int" IS a keyword -> escaped with trailing _
		assertEquals("int_", dev.worldforge.exporter.ExportSymbolMapper.fieldName("worldforge:int"));
		assertTrue(dev.worldforge.exporter.ExportSymbolMapper.className("worldforge:2fast")
				.matches("[A-Za-z_][A-Za-z0-9_]*"), "digit-leading must be prefixed");
	}

	@Test
	void packageNamesAreLegal() {
		assertEquals("a", dev.worldforge.exporter.ExportSymbolMapper.packageName("a_"));
		assertEquals("a.b", dev.worldforge.exporter.ExportSymbolMapper.packageName("a__b"));
		assertEquals("my.mod", dev.worldforge.exporter.ExportSymbolMapper.packageName("my_mod"));
		assertThrows(IllegalArgumentException.class,
				() -> dev.worldforge.exporter.ExportSymbolMapper.packageName("___"));
	}
}
