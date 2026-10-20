package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.exporter.ExportPlan;
import dev.worldforge.exporter.DefinitionResolver;
import dev.worldforge.exporter.ExportSymbolMapper;
import dev.worldforge.exporter.ModExporter;
import dev.worldforge.exporter.TemplateProjectGenerator;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.7 P0-1: the WHOLE generator must read package/entry symbols from one
 * frozen ExportSymbolTable — before-fix evidence that 'a_' / 'a__b' mod ids
 * and odd item ids still produce illegal packages / mismatched entries via
 * raw replace("_", ".").
 */
class SymbolTableWiringTest {

	@TempDir
	Path tempDir;

	@BeforeEach
	void setup() {
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(tempDir.resolve("bs")));
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(tempDir.resolve("is")));
	}

	@AfterEach
	void cleanup() {
		BlockRuntimeManager.release();
		WorldForgeRuntimeManager.onServerStopped();
	}

	private void makeItem(String id, String action) {
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1, id, "I", "",
				TextureSource.vanilla("minecraft:item/diamond_sword"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of(action), Map.of(), 50))));
	}

	/** Extract the declared package from every generated .java file; all must match. */
	private List<String> declaredPackages(Path projectDir) throws Exception {
		var pkgs = new java.util.ArrayList<String>();
		try (var walk = Files.walk(projectDir.resolve("src"))) {
			walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
				try {
					var m = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE)
							.matcher(Files.readString(p));
					if (m.find()) pkgs.add(m.group(1));
				} catch (Exception ignored) {
				}
			});
		}
		return pkgs;
	}

	/** fabric.mod.json entry_package + entry class must match the real files. */
	private String[] fabricEntry(Path projectDir) throws Exception {
		var json = Files.readString(projectDir.resolve("src/main/resources/fabric.mod.json"));
		var mp = Pattern.compile("\"main\":\\s*\\[\\s*\"([\\w.]+)\"").matcher(json);
		assertTrue(mp.find(), "fabric.mod.json main entry missing");
		return mp.group(1).split("\\.");
	}

	private void assertPackageConsistent(String modId, String itemId) throws Exception {
		makeItem(itemId, "summon_lightning");
		var out = tempDir.resolve("out-" + modId + "-" + itemId.replace(":", "_"));
		Files.createDirectories(out);
		var plan = ExportPlan.create(modId, "M", "1.0.0", out.toString(),
				List.of(itemId), List.of());
		var projectDir = out.resolve(modId);
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(plan), projectDir,
				DefinitionResolver.fromSnapshot(plan));
		gen.generate();

		// 1) every java file lives under src/main/java/<package path>
		var pkgs = declaredPackages(projectDir);
		assertFalse(pkgs.isEmpty(), "no generated java files");
		for (var pkg : pkgs) {
			assertTrue(pkg.matches("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*"),
					"illegal package segment in '" + pkg + "' for modId=" + modId);
			assertFalse(pkg.contains(".."), "empty package segment: " + pkg);
			assertFalse(pkg.endsWith("."), "trailing dot: " + pkg);
		}
		assertEquals(1, pkgs.stream().distinct().count(),
				"all files must share ONE package for modId=" + modId + " pkgs=" + pkgs);

		// 2) the package path on disk matches the declared package exactly
		String pkg = pkgs.get(0);
		Path expect = projectDir.resolve("src/main/java");
		for (var seg : pkg.split("\\.")) expect = expect.resolve(seg);
		assertTrue(Files.isDirectory(expect),
				"package dir mismatch: declared " + pkg);

		// 3) fabric.mod.json entry == real class file location
		var entry = fabricEntry(projectDir);
		String entryPkg = String.join(".", java.util.Arrays.copyOfRange(entry, 0, entry.length - 1));
		String entryClass = entry[entry.length - 1];
		assertEquals(pkg, entryPkg, "fabric entry package != generated package");
		assertTrue(Files.exists(expect.resolve(entryClass + ".java")),
				"entry class " + entryClass + " does not exist in " + expect);
	}

	@Test
	void modIdAUnderscoreProducesLegalConsistentPackages() throws Exception {
		assertPackageConsistent("a_", "worldforge:sword");
	}

	@Test
	void modIdDoubleUnderscoreProducesLegalConsistentPackages() throws Exception {
		assertPackageConsistent("a__b", "worldforge:sword");
	}

	@Test
	void modIdMyModBaseline() throws Exception {
		assertPackageConsistent("my_mod", "worldforge:sword");
	}

	@Test
	void complexItemIdsProduceConsistentSymbols() throws Exception {
		// hyphen, dot, double-underscore item ids + a mod id with underscore
		makeItem("worldforge:magic-sword", "summon_lightning");
		makeItem("worldforge:magic.device", "heal");
		makeItem("worldforge:magic__core", "ignite");
		BlockRuntimeManager.create(new BlockDefinition(1, "worldforge:magic.block", "MB", "",
				"stone", BlockDefinition.Category.INTERACTIVE, 2.0,
				List.of(new BehaviorDefinition("on_use", List.of("apply_effect"),
						Map.of("effect", "speed", "duration", 100, "amplifier", 0), 100)),
				0, null, 0));

		var out = tempDir.resolve("out-cplx");
		Files.createDirectories(out);
		var plan = ExportPlan.create("my_mod", "M", "1.0.0", out.toString(),
				List.of("worldforge:magic-sword", "worldforge:magic.device", "worldforge:magic__core"),
				List.of("worldforge:magic.block"));
		var projectDir = out.resolve("my_mod");
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(plan), projectDir,
				DefinitionResolver.fromSnapshot(plan));
		gen.generate();

		// all class files compile-able names & unique
		try (var walk = Files.walk(projectDir.resolve("src/main/java"))) {
			var names = new java.util.HashSet<String>();
			walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
				String f = p.getFileName().toString();
				assertTrue(f.matches("[A-Za-z_][A-Za-z0-9_]*\\.java"), "bad file name: " + f);
				assertTrue(names.add(f), "duplicate file name: " + f);
			});
		}
		// the generated ModItems/ModBlocks must register all ids with legal registry paths
		var modItems = Files.readString(projectDir.resolve("src/main/java/my/mod/ModItems.java"));
		var modBlocks = Files.readString(projectDir.resolve("src/main/java/my/mod/ModBlocks.java"));
		for (String registryId : new String[]{"magic-sword", "magic.device", "magic__core"}) {
			assertTrue(modItems.contains("\"" + registryId + "\""),
					"item registry id must stay verbatim for minecraft: " + registryId);
		}
		assertTrue(modBlocks.contains("\"magic.block\""),
				"block registry id must stay verbatim for minecraft: magic.block");
		// fabric.mod.json entry must match an existing file
		var entry = fabricEntry(projectDir);
		Path expect = projectDir.resolve("src/main/java");
		for (int i = 0; i < entry.length - 1; i++) expect = expect.resolve(entry[i]);
		expect = expect.resolve(entry[entry.length - 1] + ".java");
		assertTrue(Files.exists(expect), "entry class missing: " + expect);
	}

	@Test
	void keywordItemIdsDoNotBreakGeneration() throws Exception {
		makeItem("worldforge:class", "summon_lightning");
		makeItem("worldforge:int", "heal");
		var out = tempDir.resolve("out-kw");
		Files.createDirectories(out);
		var plan = ExportPlan.create("kw_mod", "K", "1.0.0", out.toString(),
				List.of("worldforge:class", "worldforge:int"), List.of());
		var projectDir = out.resolve("kw_mod");
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(plan), projectDir,
				DefinitionResolver.fromSnapshot(plan));
		assertDoesNotThrow(gen::generate);
		// generated classes must not be raw keywords
		try (var walk = Files.walk(projectDir.resolve("src/main/java"))) {
			walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(p -> {
				String base = p.getFileName().toString().replace(".java", "");
				assertFalse(base.equals("class") || base.equals("int") || base.equals("interface"),
						"raw keyword file name: " + base);
			});
		}
	}
}
