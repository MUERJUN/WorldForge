package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.exporter.DefinitionResolver;
import dev.worldforge.exporter.ExportPlan;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.6 P1: per-action equivalence audit — a retained entry must mean the
 * exported code honours the definition's parameters. Hardcoded stand-ins
 * (fixed ignite seconds, fixed sound event, fixed particle type) are NOT
 * full equivalence and must be reported as converted, not retained.
 */
class ActionEquivalenceAuditTest {

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

	private ItemDefinition itemWith(String action, Map<String, Object> params) {
		return new ItemDefinition(1, "worldforge:eq_" + action, "Eq " + action, "",
				TextureSource.vanilla("minecraft:item/diamond_sword"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of(action), params, 20)));
	}

	@Test
	void soundWithCustomEventIsConvertedNotRetained() {
		// the generator always plays BLOCK_BELL_USE — a custom sound event
		// cannot be honoured, so it must be reported as converted
		WorldForgeRuntimeManager.createItem(itemWith("sound", Map.of("sound", "entity.dragon.roar")));
		var plan = ExportPlan.create("snd_mod", "Snd", "1.0.0", tempDir.toString(),
				List.of("worldforge:eq_sound"), List.of());
		assertTrue(plan.report().converted().stream().anyMatch(s -> s.contains("sound")),
				"custom sound must be 'converted': " + plan.report().converted());
	}

	@Test
	void particlesWithCustomTypeIsConvertedNotRetained() {
		WorldForgeRuntimeManager.createItem(itemWith("particles", Map.of("particle", "flame", "count", 30)));
		var plan = ExportPlan.create("par_mod", "Par", "1.0.0", tempDir.toString(),
				List.of("worldforge:eq_particles"), List.of());
		assertTrue(plan.report().converted().stream().anyMatch(s -> s.contains("particles")),
				"custom particle must be 'converted': " + plan.report().converted());
	}

	@Test
	void igniteIsConvertedWithFixedDuration() {
		WorldForgeRuntimeManager.createItem(itemWith("ignite", Map.of("seconds", 15)));
		var plan = ExportPlan.create("ign_mod", "Ign", "1.0.0", tempDir.toString(),
				List.of("worldforge:eq_ignite"), List.of());
		assertTrue(plan.report().converted().stream().anyMatch(s -> s.contains("ignite")),
				"ignite (fixed 5s in export) must be 'converted': " + plan.report().converted());
	}

	@Test
	void defaultParamsStayRetained() {
		// no custom params -> the hardcoded export exactly matches
		WorldForgeRuntimeManager.createItem(itemWith("sound", Map.of()));
		WorldForgeRuntimeManager.createItem(itemWith("particles", Map.of()));
		WorldForgeRuntimeManager.createItem(itemWith("ignite", Map.of()));
		var plan = ExportPlan.create("def_mod", "Def", "1.0.0", tempDir.toString(),
				List.of("worldforge:eq_sound", "worldforge:eq_particles", "worldforge:eq_ignite"),
				List.of());
		assertTrue(plan.report().lost().isEmpty(), "no loss expected: " + plan.report().lost());
		assertTrue(plan.report().converted().isEmpty(),
				"default params must be fully retained: " + plan.report().converted());
		assertEquals(3, plan.report().retained().size());
	}

	@Test
	void applyEffectAndLightningParamsFullyHonoured() throws Exception {
		WorldForgeRuntimeManager.createItem(itemWith("apply_effect",
				Map.of("effect", "strength", "duration", 400, "amplifier", 2)));
		WorldForgeRuntimeManager.createItem(itemWith("summon_lightning", Map.of()));
		var out = tempDir.resolve("out");
		Files.createDirectories(out);
		var plan = ExportPlan.create("ae_mod", "AE", "1.0.0", out.toString(),
				List.of("worldforge:eq_apply_effect", "worldforge:eq_summon_lightning"), List.of());
		assertTrue(plan.report().lost().isEmpty() && plan.report().converted().isEmpty());

		var projectDir = out.resolve("ae_mod");
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(plan), projectDir,
				DefinitionResolver.fromSnapshot(plan));
		gen.generate();
		var strength = Files.readString(projectDir.resolve("src/main/java/ae/mod/EqApplyEffectItem.java"));
		assertTrue(strength.contains("minecraft:strength"), "effect honoured");
		assertTrue(strength.contains("400"), "duration honoured");
		assertTrue(strength.contains("2"), "amplifier honoured");
		var bolt = Files.readString(projectDir.resolve("src/main/java/ae/mod/EqSummonLightningItem.java"));
		assertTrue(bolt.contains("LIGHTNING_BOLT"));
	}

	@Test
	void healAmountHonoured() throws Exception {
		WorldForgeRuntimeManager.createItem(itemWith("heal", Map.of("amount", 42)));
		var out = tempDir.resolve("outh");
		Files.createDirectories(out);
		var plan = ExportPlan.create("heal_mod", "Heal", "1.0.0", out.toString(),
				List.of("worldforge:eq_heal"), List.of());
		assertTrue(plan.report().converted().isEmpty() && plan.report().lost().isEmpty());
		var projectDir = out.resolve("heal_mod");
		var gen = new TemplateProjectGenerator(ModExporter.toRequest(plan), projectDir,
				DefinitionResolver.fromSnapshot(plan));
		gen.generate();
		var src = Files.readString(projectDir.resolve("src/main/java/heal/mod/EqHealItem.java"));
		assertTrue(src.contains("42"), "heal amount honoured: " + src.lines().filter(l -> l.contains("heal")).findFirst().orElse(""));
	}
}
