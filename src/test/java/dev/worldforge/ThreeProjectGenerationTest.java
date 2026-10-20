package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.exporter.ExportPlan;
import dev.worldforge.exporter.ModExporter;
import dev.worldforge.exporter.TemplateProjectGenerator;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.7 P1-3: generate THREE distinct standalone Fabric mods exercising the
 * full symbol matrix. Structure + fabric.mod.json entry consistency are
 * asserted here (fast); the real Gradle builds run in
 * scripts/build-three-projects.sh as the heavyweight evidence step.
 *
 *   A. standard: thunder sword + interactive block (baseline)
 *   B. complex ids: hyphen/dot/double-underscore item+block ids, non-default
 *      cooldowns and effect params, multiple items and multiple blocks
 *   C. degraded: an item whose action has no implementation — the plan report
 *      must mark it lost and require confirmation
 */
class ThreeProjectGenerationTest {

	@BeforeEach
	void setup() throws Exception {
		var storeDir = Path.of("build/three-projects-stores");
		Files.createDirectories(storeDir);
		BlockRuntimeManager.attachStoreForTesting(new BlockDefinitionStore(storeDir.resolve("bs")));
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(storeDir.resolve("is")));
	}

	private Path exportBase() throws Exception {
		var base = Path.of("build/three-projects");
		Files.createDirectories(base);
		return base;
	}

	private void item(String id, String action, Map<String, Object> params, int cd) {
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1, id, "I " + id, "",
				TextureSource.vanilla("minecraft:item/diamond_sword"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of(action), params, cd))));
	}

	private void block(String id, String action, Map<String, Object> params, int cd,
			BlockDefinition.Category cat) {
		BlockRuntimeManager.create(new BlockDefinition(1, id, "B " + id, "",
				"furnace", cat, 2.0,
				List.of(new BehaviorDefinition("on_use", List.of(action), params, cd)),
				cat == BlockDefinition.Category.PERIODIC ? 100 : 0,
				cat == BlockDefinition.Category.PERIODIC ? "worldforge:iron_ingot" : null, 0));
	}

	private Path generate(String modId, List<String> itemIds, List<String> blockIds) throws Exception {
		var base = exportBase();
		var out = base.resolve(modId);
		// fresh output dir per run (the exporter requires an empty dir)
		if (Files.exists(out)) {
			try (var walk = Files.walk(out)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
					try { Files.deleteIfExists(p); } catch (Exception ignored) {}
				});
			}
		}
		Files.createDirectories(out);
		var plan = ExportPlan.create(modId, "M " + modId, "1.0.0", out.toString(), itemIds, blockIds);
		var projectDir = out.resolve(modId);
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(plan), projectDir,
				dev.worldforge.exporter.DefinitionResolver.fromSnapshot(plan));
		gen.generate();
		gen.copyGradleWrapper(Path.of(".").toAbsolutePath());
		return projectDir;
	}

	/** fabric.mod.json "main" entry package must equal the real java package. */
	private void assertEntryMatchesFiles(Path projectDir) throws Exception {
		var json = Files.readString(projectDir.resolve("src/main/resources/fabric.mod.json"));
		var m = Pattern.compile("\"main\":\\s*\\[\\s*\"([\\w.]+)\"").matcher(json);
		assertTrue(m.find(), "main entry present");
		String fqcn = m.group(1);
		String pkg = fqcn.substring(0, fqcn.lastIndexOf('.'));
		String cls = fqcn.substring(fqcn.lastIndexOf('.') + 1);
		// package must be legal
		assertTrue(pkg.matches("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*"), "legal pkg " + pkg);
		// the class file must exist at pkg path
		Path expect = projectDir.resolve("src/main/java");
		for (var seg : pkg.split("\\.")) expect = expect.resolve(seg);
		assertTrue(Files.exists(expect.resolve(cls + ".java")),
				"entry class file must exist: " + expect.resolve(cls + ".java"));
	}

	@Test
	void projectA_standardSwordAndBlock() throws Exception {
		item("worldforge:thunder_sword", "summon_lightning", Map.of(), 100);
		block("worldforge:magic_device", "apply_effect",
				Map.of("effect", "speed", "duration", 200, "amplifier", 1), 200,
				BlockDefinition.Category.INTERACTIVE);
		var dir = generate("alpha_mod", List.of("worldforge:thunder_sword"),
				List.of("worldforge:magic_device"));
		assertEntryMatchesFiles(dir);
		assertTrue(Files.exists(dir.resolve("src/main/java/alpha/mod/ThunderSwordItem.java")));
		assertTrue(Files.exists(dir.resolve("src/main/java/alpha/mod/MagicDeviceBlockEntity.java")));
	}

	@Test
	void projectB_complexIdsAndMultipleEntries() throws Exception {
		item("worldforge:magic-sword", "summon_lightning", Map.of(), 80);
		item("worldforge:magic.device", "apply_effect",
				Map.of("effect", "strength", "duration", 300, "amplifier", 2), 150);
		item("worldforge:magic__core", "heal", Map.of("amount", 8), 60);
		block("worldforge:power.block", "apply_effect",
				Map.of("effect", "regeneration", "duration", 100, "amplifier", 0), 120,
				BlockDefinition.Category.INTERACTIVE);
		block("worldforge:spark_stone", "particles", Map.of(), 30,
				BlockDefinition.Category.INTERACTIVE);
		var dir = generate("beta_mod",
				List.of("worldforge:magic-sword", "worldforge:magic.device", "worldforge:magic__core"),
				List.of("worldforge:power.block", "worldforge:spark_stone"));
		assertEntryMatchesFiles(dir);
		// all five custom classes exist with legal, distinct names
		try (var walk = Files.walk(dir.resolve("src/main/java/beta/mod"))) {
			var classes = walk.filter(p -> p.toString().endsWith(".java"))
					.map(p -> p.getFileName().toString()).toList();
			assertTrue(classes.stream().anyMatch(c -> c.equals("MagicSwordItem.java")),
					"hyphen id -> MagicSwordItem: " + classes);
			// magic.device folds to MagicDevice, magic__core to MagicCore —
			// both distinct and legal
			assertTrue(classes.stream().anyMatch(c -> c.equals("MagicDeviceItem.java")),
					"dot id -> MagicDeviceItem: " + classes);
			assertTrue(classes.stream().anyMatch(c -> c.equals("MagicCoreItem.java")),
					"double-underscore id -> MagicCoreItem: " + classes);
			// no illegal identifier characters in any file name
			for (var c : classes) {
				assertTrue(c.matches("[A-Za-z_][A-Za-z0-9_]*\\.java"), "illegal name: " + c);
			}
		}
	}

	@Test
	void projectC_degradedRequiresConfirmation() throws Exception {
		item("worldforge:good_sword", "summon_lightning", Map.of(), 100);
		item("worldforge:broken_wand", "fly", Map.of(), 50); // fly: unsupported
		var base = exportBase();
		var out = base.resolve("gamma_mod");
		Files.createDirectories(out);
		var plan = ExportPlan.create("gamma_mod", "G", "1.0.0", out.toString(),
				List.of("worldforge:good_sword", "worldforge:broken_wand"), List.of());
		// the report must mark fly as lost, good_sword retained
		assertTrue(plan.report().lost().stream().anyMatch(s -> s.contains("fly")),
				"fly must be reported lost: " + plan.report().lost());
		assertTrue(plan.report().retained().stream().anyMatch(s -> s.contains("summon_lightning")),
				"good_sword must be retained: " + plan.report().retained());
		// the EXECUTOR GUARD refuses the unconfirmed plan (pure decision, no build)
		var guardError = ModExporter.degradedGuardError(plan.report(),
				plan.degradedExportConfirmed());
		assertNotNull(guardError, "unconfirmed degraded plan must be refused");
		assertTrue(guardError.contains("fly"), "guard cites the lost capability");
		// after confirmation the guard passes (same frozen report)
		var confirmed = plan.withDegradedConfirmation();
		assertNull(ModExporter.degradedGuardError(confirmed.report(),
				confirmed.degradedExportConfirmed()),
				"confirmed plan passes the guard");
		// and the confirmed plan generates with good_sword kept, fly dropped
		var projectDir = out.resolve("gamma_mod");
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(confirmed), projectDir,
				dev.worldforge.exporter.DefinitionResolver.fromSnapshot(confirmed));
		gen.generate();
		gen.copyGradleWrapper(Path.of(".").toAbsolutePath());
		var good = Files.readString(projectDir.resolve("src/main/java/gamma/mod/GoodSwordItem.java"));
		assertTrue(good.contains("LIGHTNING_BOLT"), "retained action generated");
		var wand = Files.readString(projectDir.resolve("src/main/java/gamma/mod/BrokenWandItem.java"));
		assertFalse(wand.contains("setHealth") || wand.contains("fly"),
				"unsupported fly action must NOT be generated into use()");
		assertEntryMatchesFiles(projectDir);
	}
}
