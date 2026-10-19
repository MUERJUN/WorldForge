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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.3 P0-1: ExportPlan snapshot must be truly immutable.
 *
 * <p>Editing or deleting definitions AFTER plan creation must not affect an
 * export driven by that plan; concurrent plan creation must be safe; and the
 * snapshot resolver must serve the captured definitions even when the live
 * managers no longer contain them.</p>
 */
class ExportPlanSnapshotTest {

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

	private ItemDefinition newItem(String id, String action, int cooldown) {
		var behavior = new BehaviorDefinition("on_use", List.of(action), Map.of(), cooldown);
		return new ItemDefinition(1, id, "Item " + id, "",
				TextureSource.vanilla("minecraft:item/diamond_sword"), Map.of(), List.of(behavior));
	}

	@Test
	void planSurvivesDefinitionDeletionAfterCreation() throws Exception {
		WorldForgeRuntimeManager.createItem(newItem("worldforge:snap_item", "summon_lightning", 100));

		var plan = ExportPlan.create("snap_mod", "Snap", "1.0.0",
				tempDir.toString(), List.of("worldforge:snap_item"), List.of());

		// Delete AFTER plan creation — the plan must be unaffected
		WorldForgeRuntimeManager.deleteItem("worldforge:snap_item");
		assertNull(WorldForgeRuntimeManager.get("worldforge:snap_item"));

		// Snapshot resolver still serves the captured definition
		var resolver = DefinitionResolver.fromSnapshot(plan);
		assertNotNull(resolver.getItem("worldforge:snap_item"),
				"snapshot must retain the definition even after runtime deletion");

		// Generation from the snapshot must produce the custom item class
		var projectDir = tempDir.resolve("snap_mod");
		var req = ModExporter.toRequest(plan);
		var gen = new TemplateProjectGenerator(req, projectDir, resolver);
		gen.generate();
		var itemClass = Files.readString(projectDir.resolve("src/main/java/snap/mod/SnapItemItem.java"));
		assertTrue(itemClass.contains("LIGHTNING_BOLT"),
				"generated code must reflect the plan snapshot, not live state");
	}

	@Test
	void planSurvivesDefinitionEditAfterCreation() throws Exception {
		WorldForgeRuntimeManager.createItem(newItem("worldforge:edit_item", "summon_lightning", 100));

		var plan = ExportPlan.create("edit_mod", "Edit", "1.0.0",
				tempDir.toString(), List.of("worldforge:edit_item"), List.of());

		// Edit the live definition AFTER plan creation — change action + cooldown
		var edited = newItem("worldforge:edit_item", "heal", 999);
		WorldForgeRuntimeManager.updateItem(edited);

		// The plan still carries the ORIGINAL behavior
		assertEquals(1, plan.items().size());
		var captured = plan.items().get(0);
		assertEquals("summon_lightning", captured.behaviors().get(0).actions().get(0),
				"plan must keep the original action despite live edits");
		assertEquals(100, captured.behaviors().get(0).cooldownTicks(),
				"plan must keep the original cooldown despite live edits");

		// Deep copy check: mutating the plan's returned collections must fail
		// (List.copyOf produces immutable lists) or not affect the plan
		assertThrows(UnsupportedOperationException.class,
				() -> plan.items().add(newItem("worldforge:x", "heal", 1)),
				"plan item list must be immutable");
		assertThrows(UnsupportedOperationException.class,
				() -> plan.blocks().add(null),
				"plan block list must be immutable");
	}

	@Test
	void concurrentPlanCreationIsSafe() throws Exception {
		for (int i = 0; i < 8; i++) {
			WorldForgeRuntimeManager.createItem(newItem("worldforge:conc_item_" + i, "summon_lightning", 10 + i));
		}

		int threads = 8;
		var pool = Executors.newFixedThreadPool(threads);
		var done = new CountDownLatch(threads);
		var failures = new AtomicInteger();
		for (int t = 0; t < threads; t++) {
			final int idx = t;
			pool.submit(() -> {
				try {
					var plan = ExportPlan.create("conc_mod_" + idx, "Conc " + idx, "1.0.0",
							tempDir.toString(),
							List.of("worldforge:conc_item_" + idx), List.of());
					if (plan.items().size() != 1) failures.incrementAndGet();
					if (!plan.items().get(0).id().equals("worldforge:conc_item_" + idx)) {
						failures.incrementAndGet();
					}
				} catch (Exception e) {
					failures.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}
		assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent plan creation must finish");
		pool.shutdownNow();
		assertEquals(0, failures.get(), "no failures allowed in concurrent plan creation");
	}

	@Test
	void resolverDoesNotTouchRuntimeManagers() throws Exception {
		WorldForgeRuntimeManager.createItem(newItem("worldforge:iso_item", "heal", 5));
		BlockRuntimeManager.create(new BlockDefinition(1, "worldforge:iso_block", "Iso", "",
				"stone", BlockDefinition.Category.INTERACTIVE, 2.0,
				List.of(new BehaviorDefinition("on_use", List.of("apply_effect"), Map.of(), 20)),
				0, null, 0));

		var plan = ExportPlan.create("iso_mod", "Iso", "1.0.0", tempDir.toString(),
				List.of("worldforge:iso_item"), List.of("worldforge:iso_block"));

		// Detach ALL live state — snapshot resolution must not depend on it
		BlockRuntimeManager.release();
		WorldForgeRuntimeManager.onServerStopped();

		var resolver = DefinitionResolver.fromSnapshot(plan);
		assertNotNull(resolver.getItem("worldforge:iso_item"),
				"snapshot item resolution must work with runtime detached");
		assertNotNull(resolver.getBlock("worldforge:iso_block"),
				"snapshot block resolution must work with runtime detached");
		assertNull(resolver.getItem("worldforge:missing"),
				"unknown IDs must resolve to null from the snapshot");
	}

	@Test
	void degradedConfirmationCopiesContent() {
		WorldForgeRuntimeManager.createItem(newItem("worldforge:deg_item", "summon_lightning", 10));
		var plan = ExportPlan.create("deg_mod", "Deg", "1.0.0", tempDir.toString(),
				List.of("worldforge:deg_item"), List.of());
		assertFalse(plan.degradedExportConfirmed());

		var confirmed = plan.withDegradedConfirmation();
		assertTrue(confirmed.degradedExportConfirmed());
		assertTrue(plan.sameContentAs(confirmed), "confirmation copy keeps content identity");
		assertFalse(plan.degradedExportConfirmed(), "original plan stays unconfirmed");
	}
}
