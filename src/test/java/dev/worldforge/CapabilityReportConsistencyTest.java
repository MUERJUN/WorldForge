package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.exporter.ExportPlan;
import dev.worldforge.exporter.ExportExecutor;
import dev.worldforge.exporter.ModExporter;
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
 * v0.5.7 P1-2: ONE capability core. A complex object carrying retained,
 * converted AND lost capabilities at once must produce the IDENTICAL frozen
 * report through every consumer: ExportPlan, the deprecated per-request API
 * (delegation), the executor guard, and the GUI.
 */
class CapabilityReportConsistencyTest {

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

	private ModExporter.CapabilityReport complexPlanReport() throws Exception {
		// retained: summon_lightning on_use with no custom params
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1,
				"worldforge:c_retain", "R", "",
				TextureSource.vanilla("minecraft:item/diamond_sword"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of("summon_lightning"), Map.of(), 100))));
		// converted: sound with a custom event the template cannot honour
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1,
				"worldforge:c_convert", "C", "",
				TextureSource.vanilla("minecraft:item/stick"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of("sound"),
						Map.of("sound", "entity.wither.shoot"), 20))));
		// lost: unsupported action + bad trigger + attributes
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1,
				"worldforge:c_lose", "L", "",
				TextureSource.vanilla("minecraft:item/stick"), Map.of("attack_damage", 9.0),
				List.of(new BehaviorDefinition("hit_entity", List.of("fly"), Map.of(), 20))));
		// periodic block -> lost
		BlockRuntimeManager.create(new BlockDefinition(1, "worldforge:c_periodic", "P", "",
				"furnace", BlockDefinition.Category.PERIODIC, 2.0, List.of(),
				100, "worldforge:iron_ingot", 1));

		var out = tempDir.resolve("out");
		Files.createDirectories(out);
		return ExportPlan.create("cons_mod", "Cons", "1.0.0", out.toString(),
				List.of("worldforge:c_retain", "worldforge:c_convert", "worldforge:c_lose"),
				List.of("worldforge:c_periodic")).report();
	}

	@Test
	void complexObjectSplitsAcrossAllThreeBuckets() throws Exception {
		var report = complexPlanReport();
		assertTrue(report.retained().stream().anyMatch(s -> s.contains("summon_lightning")));
		assertTrue(report.converted().stream().anyMatch(s -> s.contains("sound")));
		assertTrue(report.lost().stream().anyMatch(s -> s.contains("fly")));
		assertTrue(report.lost().stream().anyMatch(s -> s.contains("hit_entity")));
		assertTrue(report.lost().stream().anyMatch(s -> s.contains("attack_damage")));
		assertTrue(report.lost().stream().anyMatch(s -> s.contains("周期")));
	}

	@Test
	void legacyApiDelegatesToSameCore() throws Exception {
		// deprecated buildCapabilityReport must now AGREE with the plan report
		var planReport = complexPlanReport();
		var legacy = ModExporter.buildCapabilityReport(new ModExporter.ExportRequest(
				"cons_mod", "Cons", "1.0.0", tempDir.resolve("out"),
				List.of("worldforge:c_retain", "worldforge:c_convert", "worldforge:c_lose"),
				List.of("worldforge:c_periodic")));
		assertEquals(planReport.retained(), legacy.retained(),
				"legacy retained must equal plan retained");
		assertEquals(planReport.converted(), legacy.converted(),
				"legacy converted must equal plan converted (old core had none!)");
		assertEquals(planReport.lost(), legacy.lost(),
				"legacy lost must equal plan lost");
	}

	@Test
	void executorGuardUsesTheFrozenPlanReport() throws Exception {
		// unconfirmed degraded plan → executor refuses with the plan's own lost list
		var out = tempDir.resolve("outg");
		Files.createDirectories(out);
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1,
				"worldforge:g_lose", "G", "",
				TextureSource.vanilla("minecraft:item/stick"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of("fly"), Map.of(), 10))));
		var plan = ExportPlan.create("guard_mod", "Guard", "1.0.0", out.toString(),
				List.of("worldforge:g_lose"), List.of());
		assertFalse(plan.report().lost().isEmpty());

		var exec = new ExportExecutor(plan);
		var result = exec.doExport();
		assertFalse(result.ok(), "executor must refuse unconfirmed degraded export");
		assertTrue(result.errorMessage().contains("fly"),
				"error must cite the plan's lost capability: " + result.errorMessage());
		assertEquals(ExportExecutor.TerminalState.FAILED, exec.state());
		// no project dir was created (guard fires before generation)
		assertFalse(Files.exists(out.resolve("guard_mod")));
	}

	@Test
	void guiAndExecutorSeeIdenticalReport() throws Exception {
		// the GUI reads plan.report() and the executor reads plan.report() —
		// same object identity through the frozen plan
		var out = tempDir.resolve("outgui");
		Files.createDirectories(out);
		WorldForgeRuntimeManager.createItem(new ItemDefinition(1,
				"worldforge:u_item", "U", "",
				TextureSource.vanilla("minecraft:item/stick"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of("summon_lightning"), Map.of(), 10))));
		var plan = ExportPlan.create("u_mod", "U", "1.0.0", out.toString(),
				List.of("worldforge:u_item"), List.of());
		var report = plan.report();
		// identity: both consumers receive the same frozen instance
		assertSame(report, plan.report(), "plan.report() must be a stable frozen instance");
		// a withConfirmation copy keeps the SAME report object
		assertSame(report, plan.withDegradedConfirmation().report());
	}
}
