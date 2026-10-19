package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.exporter.ExportPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.5.4 Phase 1 P1: ExportPlan must be DEEP immutable — nested parameters,
 * behavior maps, and definition lists can never be mutated from outside, and
 * mutating the ORIGINAL objects after plan creation cannot leak into the plan.
 */
class ExportPlanDeepImmutabilityTest {

	@TempDir
	Path tempDir;

	@Test
	void nestedParameterMutationDoesNotLeakIntoPlan() {
		// build the behavior with a MUTABLE map we keep a handle on
		Map<String, Object> params = new HashMap<>();
		params.put("effect", "speed");
		params.put("duration", 200);
		var behavior = new BehaviorDefinition("on_use", List.of("apply_effect"), params, 100);
		var item = new ItemDefinition(1, "worldforge:deep_item", "Deep", "",
				TextureSource.vanilla("minecraft:item/stick"), new HashMap<>(), List.of(behavior));

		// register through the shared helper so create() can resolve it
		WorldForgeRuntimeManagerAccess.create(item, null, tempDir);

		var plan = ExportPlan.create("deep_mod", "Deep", "1.0.0", tempDir.toString(),
				List.of("worldforge:deep_item"), List.of());

		// mutate the ORIGINAL parameter map AFTER plan creation
		params.put("duration", 99999);
		params.put("poison", "injected");

		var captured = plan.items().get(0).behaviors().get(0).parameters();
		assertEquals(200, captured.get("duration"), "plan params frozen at create()");
		assertFalse(captured.containsKey("poison"), "post-create mutation must not leak");
	}

	@Test
	void planListsRejectAllMutation() {
		Map<String, Object> params = new HashMap<>();
		params.put("effect", "speed");
		var behavior = new BehaviorDefinition("on_use", List.of("apply_effect"), params, 50);
		var item = new ItemDefinition(1, "worldforge:frz_item", "Frz", "",
				TextureSource.vanilla("minecraft:item/stick"), Map.of(), List.of(behavior));
		var block = new BlockDefinition(1, "worldforge:frz_block", "FrzB", "",
				"stone", BlockDefinition.Category.DECORATION, 2.0,
				List.of(new BehaviorDefinition("on_use", List.of("heal"), Map.of(), 5)),
				0, null, 0);
		WorldForgeRuntimeManagerAccess.create(item, block, tempDir);

		var plan = ExportPlan.create("frz_mod", "Frz", "1.0.0", tempDir.toString(),
				List.of("worldforge:frz_item"), List.of("worldforge:frz_block"));

		// top-level lists
		assertThrows(UnsupportedOperationException.class, () -> plan.items().add(item));
		assertThrows(UnsupportedOperationException.class, () -> plan.blocks().add(block));
		assertThrows(UnsupportedOperationException.class, () -> plan.items().clear());
		assertThrows(UnsupportedOperationException.class,
				() -> plan.report().retained().add("hack"),
				"CapabilityReport must be immutable too");
		assertThrows(UnsupportedOperationException.class,
				() -> plan.report().lost().clear());

		// nested behavior list inside a captured definition
		var capturedItem = plan.items().get(0);
		assertThrows(UnsupportedOperationException.class,
				() -> capturedItem.behaviors().add(behavior),
				"captured behavior list must be frozen");
		var capturedBehavior = capturedItem.behaviors().get(0);
		assertThrows(UnsupportedOperationException.class,
				() -> capturedBehavior.actions().add("ignite"),
				"captured action list must be frozen");
	}

	@Test
	void originalCollectionEditsAfterCreateDoNotAffectPlan() {
		// hand create() a MUTABLE list we mutate afterwards
		var itemIds = new ArrayList<String>();
		itemIds.add("worldforge:orig_item");
		Map<String, Object> params = new HashMap<>();
		params.put("effect", "speed");
		var item = new ItemDefinition(1, "worldforge:orig_item", "Orig", "",
				TextureSource.vanilla("minecraft:item/stick"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of("apply_effect"), params, 10)));
		WorldForgeRuntimeManagerAccess.create(item, null, tempDir);

		var plan = ExportPlan.create("orig_mod", "Orig", "1.0.0", tempDir.toString(),
				itemIds, new ArrayList<>());

		itemIds.add("worldforge:sneaky_extra");
		assertEquals(1, plan.items().size(), "post-create id-list mutation must not leak");
	}

	@Test
	void concurrentReadersSeeConsistentPlan() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("effect", "speed");
		var item = new ItemDefinition(1, "worldforge:conc_read", "CR", "",
				TextureSource.vanilla("minecraft:item/stick"), Map.of(),
				List.of(new BehaviorDefinition("on_use", List.of("apply_effect"), params, 7)));
		WorldForgeRuntimeManagerAccess.create(item, null, tempDir);
		var plan = ExportPlan.create("cr_mod", "CR", "1.0.0", tempDir.toString(),
				List.of("worldforge:conc_read"), List.of());

		int readers = 12;
		var pool = Executors.newFixedThreadPool(readers);
		var done = new CountDownLatch(readers);
		var failures = new AtomicInteger();
		// hammer the original param map WHILE readers walk the plan
		Thread mutator = new Thread(() -> {
			for (int i = 0; i < 10_000; i++) {
				params.put("duration", i);
				params.put("junk" + i, i);
			}
		});
		mutator.start();
		for (int r = 0; r < readers; r++) {
			pool.submit(() -> {
				try {
					for (int i = 0; i < 1_000; i++) {
						var p = plan.items().get(0).behaviors().get(0).parameters();
						if (!"speed".equals(p.get("effect"))) failures.incrementAndGet();
						if (p.containsKey("junk0")) failures.incrementAndGet();
						if (plan.items().size() != 1) failures.incrementAndGet();
					}
				} catch (Throwable t) {
					failures.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}
		assertTrue(done.await(30, TimeUnit.SECONDS));
		mutator.join(5000);
		pool.shutdownNow();
		assertEquals(0, failures.get(), "plan reads must be stable under concurrent mutation of originals");
	}

	/** Tiny bridge so tests can register definitions with a shared tempDir. */
	private static final class WorldForgeRuntimeManagerAccess {
		static void create(ItemDefinition item, BlockDefinition block, Path tempDir) {
			dev.worldforge.runtime.WorldForgeRuntimeManager.attachStoreForTesting(
					new dev.worldforge.persistence.DefinitionStore(tempDir.resolve("cr-bs")));
			dev.worldforge.runtime.WorldForgeRuntimeManager.createItem(item);
			if (block != null) {
				dev.worldforge.block.BlockRuntimeManager.attachStoreForTesting(
						new dev.worldforge.block.BlockDefinitionStore(tempDir.resolve("cr-is")));
				dev.worldforge.block.BlockRuntimeManager.create(block);
			}
		}
	}
}
