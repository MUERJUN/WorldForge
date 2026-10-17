package dev.worldforge;

import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.definition.BehaviorDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.4.0 virtual-block definition persistence: the same data-safety contract
 * as the item store, verified without a running game.
 */
class BlockDefinitionStoreTest {

	private static BlockDefinition def(String id) {
		return BlockDefinition.create(id, "魔法熔炉", "周期性生成铁锭", "minecraft:furnace",
				BlockDefinition.Category.PERIODIC, 3.5,
				List.of(new BehaviorDefinition("right_click", List.of("apply_effect"),
						Map.of("effect", "minecraft:regeneration", "duration", 100.0), 60)),
				200, "minecraft:iron_ingot", 1);
	}

	@Test
	void roundTripPreservesEveryField(@TempDir Path dir) {
		var store = new BlockDefinitionStore(dir);
		assertTrue(store.save(List.of(def("worldforge:magic_furnace"))));
		assertTrue(store.exists());

		var loaded = store.tryLoadStrict();
		assertTrue(loaded.ok(), loaded.error());
		assertEquals(1, loaded.definitions().size());
		var d = loaded.definitions().get(0);
		assertEquals("worldforge:magic_furnace", d.id());
		assertEquals("魔法熔炉", d.name());
		assertEquals("minecraft:furnace", d.appearance());
		assertEquals(BlockDefinition.Category.PERIODIC, d.category());
		assertEquals(3.5, d.hardness());
		assertEquals(200, d.tickIntervalTicks());
		assertEquals("minecraft:iron_ingot", d.outputItemId());
		assertEquals(1, d.outputCount());
		assertEquals(1, d.behaviors().size());
		assertEquals("minecraft:regeneration", d.behaviors().get(0).parameters().get("effect"));
	}

	@Test
	void missingFileIsAnEmptyProject(@TempDir Path dir) {
		var r = new BlockDefinitionStore(dir).tryLoadStrict();
		assertTrue(r.ok());
		assertTrue(r.definitions().isEmpty());
	}

	@Test
	void strictLoadRejectsPartialDamage(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("blocks.json"),
				"{\"version\":1,\"blocks\":[{\"version\":1,\"id\":\"worldforge:ok\",\"name\":\"A\"},{\"no_id\":true}]}");
		var r = new BlockDefinitionStore(dir).tryLoadStrict();
		assertFalse(r.ok(), "one damaged record must reject the whole file, not silently drop it");
		// and the live data must still be recoverable leniently, with a report
		var lenient = new BlockDefinitionStore(dir).tryLoadLenient();
		assertTrue(lenient.hadDamage());
		assertFalse(lenient.skipped().isEmpty());
	}

	@Test
	void strictLoadRejectsDuplicatesAndBadIds(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("blocks.json"),
				"{\"version\":1,\"blocks\":[{\"id\":\"worldforge:a\"},{\"id\":\"worldforge:a\"}]}");
		assertFalse(new BlockDefinitionStore(dir).tryLoadStrict().ok(), "duplicate ids must be rejected");

		Files.writeString(dir.resolve("blocks.json"),
				"{\"version\":1,\"blocks\":[{\"id\":\"BAD ID!\"}]}");
		assertFalse(new BlockDefinitionStore(dir).tryLoadStrict().ok(), "illegal ids must be rejected");
	}

	@Test
	void newerFileVersionIsRejectedNotGuessed(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("blocks.json"), "{\"version\":99,\"blocks\":[]}");
		var r = new BlockDefinitionStore(dir).tryLoadStrict();
		assertFalse(r.ok());
		assertTrue(r.error().contains("newer"), r.error());
	}

	@Test
	void brokenFileIsQuarantinedNotOverwritten(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("blocks.json"), "{not json at all");
		var store = new BlockDefinitionStore(dir);
		assertTrue(store.quarantine(), "damaged file must be moved aside");
		assertFalse(store.exists());
		assertTrue(Files.list(dir).anyMatch(p -> p.getFileName().toString().startsWith("blocks.json.broken-")),
				"the damaged content must be preserved for the player");
	}

	@Test
	void saveKeepsPreviousFileWhenItFails(@TempDir Path dir) throws Exception {
		var store = new BlockDefinitionStore(dir);
		assertTrue(store.save(List.of(def("worldforge:first"))));
		// force a genuine write failure: the target path becomes a NON-EMPTY
		// directory, so the atomic move cannot replace it
		Files.delete(dir.resolve("blocks.json"));
		Files.createDirectory(dir.resolve("blocks.json"));
		Files.writeString(dir.resolve("blocks.json").resolve("occupied"), "x");
		assertFalse(store.save(List.of(def("worldforge:second"))), "save into a directory must fail loudly");
		assertFalse(store.lastError().isEmpty(), "failure must be reported, never silent");
	}

	@Test
	void optionalOutputFieldsSurviveNull(@TempDir Path dir) {
		var store = new BlockDefinitionStore(dir);
		assertTrue(store.save(List.of(BlockDefinition.create("worldforge:deco", "装饰石板", "",
				"minecraft:stone", BlockDefinition.Category.DECORATION, 1.5, List.of(), 200, null, 0))));
		var d = store.tryLoadStrict().definitions().get(0);
		assertNull(d.outputItemId());
		assertEquals(BlockDefinition.Category.DECORATION, d.category());
		assertFalse(d.periodic());
	}
}