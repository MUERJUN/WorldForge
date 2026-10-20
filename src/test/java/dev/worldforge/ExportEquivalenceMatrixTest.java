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
import dev.worldforge.exporter.DefinitionResolver;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.5 P0: equivalence contract for multi-item / multi-block /
 * mixed-trigger exports. Everything the report says is RETAINED must have a
 * real code-generation implementation in the produced sources; everything
 * unsupported must appear in lost() — silence is a bug.
 */
class ExportEquivalenceMatrixTest {

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

	private ItemDefinition item(String id, String trigger, String action, int cd, Map<String, Object> params) {
		return new ItemDefinition(1, id, "I " + id, "",
				TextureSource.vanilla("minecraft:item/diamond_sword"), Map.of(),
				List.of(new BehaviorDefinition(trigger, List.of(action), params, cd)));
	}

	@Test
	void mixedTriggerExportOnlyImplementsOnUse() throws Exception {
		WorldForgeRuntimeManager.createItem(item("worldforge:a_right", "on_use", "summon_lightning", 50, Map.of()));
		WorldForgeRuntimeManager.createItem(item("worldforge:b_hit", "hit_entity", "heal", 50, Map.of()));
		WorldForgeRuntimeManager.createItem(item("worldforge:c_weird", "left_click", "ignite", 50, Map.of()));

		var out = tempDir.resolve("out");
		Files.createDirectories(out);
		var plan = ExportPlan.create("mix_mod", "Mix", "1.0.0", out.toString(),
				List.of("worldforge:a_right", "worldforge:b_hit", "worldforge:c_weird"), List.of());

		// report contract: right-click retained, others lost with explicit reason
		assertTrue(plan.report().retained().stream().anyMatch(s -> s.contains("a_right") && s.contains("summon_lightning")));
		assertTrue(plan.report().lost().stream().anyMatch(s -> s.contains("b_hit") && s.contains("hit_entity")));
		assertTrue(plan.report().lost().stream().anyMatch(s -> s.contains("c_weird") && s.contains("left_click")));

		var projectDir = out.resolve("mix_mod");
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(plan), projectDir,
				DefinitionResolver.fromSnapshot(plan));
		gen.generate();

		// right-click item: real behavior code present
		var right = Files.readString(projectDir.resolve("src/main/java/mix/mod/ARightItem.java"));
		assertTrue(right.contains("LIGHTNING_BOLT"), "on_use action implemented");
		// hit_entity item: class exists (registration) but NO heal behavior in use()
		var hit = Files.readString(projectDir.resolve("src/main/java/mix/mod/BHitItem.java"));
		assertFalse(hit.contains("heal(") || hit.contains("setHealth"),
				"hit_entity behavior must NOT be folded into use()");
		// weird trigger: same
		var weird = Files.readString(projectDir.resolve("src/main/java/mix/mod/CWeirdItem.java"));
		assertFalse(weird.contains("setOnFireFor"),
				"left_click behavior must NOT be folded into use()");
	}

	@Test
	void unsupportedActionsReportedLostNotSilent() {
		WorldForgeRuntimeManager.createItem(item("worldforge:exp_item", "on_use", "experience", 10, Map.of()));
		WorldForgeRuntimeManager.createItem(item("worldforge:give_item", "on_use", "give_item", 10, Map.of()));

		var plan = ExportPlan.create("uns_mod", "Uns", "1.0.0", tempDir.toString(),
				List.of("worldforge:exp_item", "worldforge:give_item"), List.of());

		assertTrue(plan.report().lost().stream().anyMatch(s -> s.contains("experience")),
				"experience must be explicitly lost");
		assertTrue(plan.report().lost().stream().anyMatch(s -> s.contains("give_item")),
				"give_item must be explicitly lost");
		assertTrue(plan.report().retained().isEmpty());
	}

	@Test
	void periodicBlockCannotExportEquivalently() {
		var periodic = new BlockDefinition(1, "worldforge:periodic_gen", "Gen", "",
				"furnace", BlockDefinition.Category.PERIODIC, 2.0,
				List.of(), 200, "worldforge:iron_ingot", 1);
		BlockRuntimeManager.create(periodic);

		var plan = ExportPlan.create("per_mod", "Per", "1.0.0", tempDir.toString(),
				List.of(), List.of("worldforge:periodic_gen"));

		assertTrue(plan.report().lost().stream()
						.anyMatch(s -> s.contains("periodic_gen") && s.contains("周期")),
				"periodic behavior must be explicitly lost: " + plan.report().lost());
	}

	@Test
	void itemAttributesSurfaceInReport() {
		var att = new ItemDefinition(1, "worldforge:att_sword", "Att", "",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("attack_damage", 12.0), List.of());
		WorldForgeRuntimeManager.createItem(att);

		var plan = ExportPlan.create("att_mod", "Att", "1.0.0", tempDir.toString(),
				List.of("worldforge:att_sword"), List.of());
		assertTrue(plan.report().lost().stream().anyMatch(s -> s.contains("attack_damage")),
				"attributes must surface as lost, not silently dropped");
	}

	@Test
	void multiItemMultiBlockFullExport() throws Exception {
		WorldForgeRuntimeManager.createItem(item("worldforge:s1", "on_use", "summon_lightning", 100, Map.of()));
		WorldForgeRuntimeManager.createItem(item("worldforge:s2", "on_use", "apply_effect",
				100, Map.of("effect", "speed", "duration", 200, "amplifier", 1)));
		BlockRuntimeManager.create(new BlockDefinition(1, "worldforge:m1", "M1", "",
				"furnace", BlockDefinition.Category.INTERACTIVE, 2.0,
				List.of(new BehaviorDefinition("on_use", List.of("apply_effect"),
						Map.of("effect", "strength", "duration", 100, "amplifier", 0), 200)),
				0, null, 0));
		BlockRuntimeManager.create(new BlockDefinition(1, "worldforge:m2", "M2", "",
				"stone", BlockDefinition.Category.DECORATION, 2.0,
				List.of(new BehaviorDefinition("on_use", List.of("sound"), Map.of(), 20)),
				0, null, 0));

		var out = tempDir.resolve("outm");
		Files.createDirectories(out);
		var plan = ExportPlan.create("full_mod", "Full", "1.0.0", out.toString(),
				List.of("worldforge:s1", "worldforge:s2"), List.of("worldforge:m1", "worldforge:m2"));
		assertTrue(plan.report().lost().isEmpty(), "all supported+on_use: " + plan.report().lost());

		var projectDir = out.resolve("full_mod");
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(plan), projectDir,
				DefinitionResolver.fromSnapshot(plan));
		gen.generate();

		// every retained item/block has a real behavior implementation
		assertTrue(Files.readString(projectDir.resolve("src/main/java/full/mod/S1Item.java"))
				.contains("LIGHTNING_BOLT"));
		assertTrue(Files.readString(projectDir.resolve("src/main/java/full/mod/S2Item.java"))
				.contains("addStatusEffect"));
		assertTrue(Files.readString(projectDir.resolve("src/main/java/full/mod/M1Block.java"))
				.contains("addStatusEffect"));
		assertTrue(Files.readString(projectDir.resolve("src/main/java/full/mod/M2Block.java"))
				.contains("playSound"));
	}

	@Test
	void maliciousParamsCannotBreakGeneratedCode() throws Exception {
		// hostile parameter values from AI/player: the plan CREATION rejects
		// out-of-range numbers loudly (fail-fast, fail-safe) — the user sees
		// the error before any export work starts
		WorldForgeRuntimeManager.createItem(item("worldforge:evil", "on_use", "apply_effect", 10,
				Map.of("effect", "speed\"); player.kill(); //", "duration", 999999999, "amplifier", -5)));

		assertThrows(Exception.class,
				() -> ExportPlan.create("evil_mod", "Evil", "1.0.0", tempDir.toString(),
						List.of("worldforge:evil"), List.of()),
				"out-of-range numeric params must be rejected at plan creation");

		// v0.5.6: hostile effect text is now REJECTED at plan creation —
		// strict validation, not silent rewriting
		assertThrows(Exception.class,
				() -> ExportPlan.create("evil2_mod", "Evil2", "1.0.0", tempDir.toString(),
						List.of("worldforge:evil2"), List.of()),
				"illegal effect id must be rejected at plan creation, not rewritten");

		// a LEGAL effect flows through unchanged
		WorldForgeRuntimeManager.createItem(item("worldforge:good", "on_use", "apply_effect", 10,
				Map.of("effect", "speed", "duration", 200, "amplifier", 0)));
		var plan3 = ExportPlan.create("good_mod", "Good", "1.0.0", tempDir.toString(),
				List.of("worldforge:good"), List.of());
		var projectDir3 = tempDir.resolve("good_mod");
		var gen3 = new TemplateProjectGenerator(ModExporter.toRequest(plan3), projectDir3,
				DefinitionResolver.fromSnapshot(plan3));
		var generated = assertDoesNotThrow(() -> {
			gen3.generate();
			return Files.readString(projectDir3.resolve("src/main/java/good/mod/GoodItem.java"));
		});
		assertTrue(generated.contains("minecraft:speed"),
				"legal effect id reaches generated code verbatim");
		assertFalse(generated.contains("player.kill()"),
				"no injected Java anywhere");
	}
}
