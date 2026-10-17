package dev.worldforge;

import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.DefinitionValidator;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.persistence.DefinitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-JVM tests for the definition/persistence layer (tests executed here
 * are labelled "自动化测试通过" in the README; Minecraft-side behaviour is
 * verified separately on the real server).
 */
class DefinitionStoreTest {

	private static ItemDefinition sample(String id) {
		return new ItemDefinition(ItemDefinition.CURRENT_VERSION, id, "雷霆之剑", "召唤闪电",
				TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 12.0, "max_durability", 500.0),
				List.of(new BehaviorDefinition("right_click", "summon_lightning", 100, Map.of("range", 12))));
	}

	@Test
	void roundTripPreservesEveryField(@TempDir Path dir) {
		DefinitionStore store = new DefinitionStore(dir);
		ItemDefinition def = sample("worldforge:thunder_sword");
		assertTrue(store.save(List.of(def)), "save must succeed");
		assertTrue(store.exists(), "file must exist after save");

		List<ItemDefinition> loaded = store.load();
		assertEquals(1, loaded.size());
		ItemDefinition back = loaded.get(0);
		assertEquals(def.id(), back.id());
		assertEquals(def.name(), back.name());
		assertEquals(def.description(), back.description());
		assertEquals(def.texture(), back.texture());
		assertEquals(12.0, back.damage());
		assertEquals(500, back.maxDurability());
		assertEquals(1, back.behaviors().size());
		assertEquals("right_click", back.behaviors().get(0).trigger());
		assertEquals("summon_lightning", back.behaviors().get(0).actions().get(0));
		assertEquals(100, back.behaviors().get(0).cooldownTicks());
		assertEquals(12.0, ((Number) back.behaviors().get(0).parameters().get("range")).doubleValue(), 0.001);
	}

	@Test
	void missingFileLoadsEmpty(@TempDir Path dir) {
		DefinitionStore store = new DefinitionStore(dir);
		assertTrue(store.load().isEmpty());
	}

	@Test
	void corruptFileIsMovedAsideAndNextSaveWorks(@TempDir Path dir) throws IOException {
		Files.createDirectories(dir);
		Path f = dir.resolve("items.json");
		Files.writeString(f, "{ this is ]]] not json");
		DefinitionStore store = new DefinitionStore(dir);

		List<ItemDefinition> loaded = store.load();
		assertTrue(loaded.isEmpty(), "corrupt file yields empty list, no crash");
		// original moved aside
		try (var s = Files.list(dir)) {
			assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("items.json.broken-")),
					"damaged file must be preserved for evidence");
		}
		// next save must succeed
		assertTrue(store.save(List.of(sample("worldforge:x"))));
		assertEquals(1, store.load().size());
	}

	@Test
	void invalidEntriesSkippedIndividually(@TempDir Path dir) {
		// one valid, one missing id, one non-object
		String json = """
				{"version":1,"items":[
				  {"id":"worldforge:a","name":"A"},
				  {"name":"no-id"},
				  42
				]}""";
		List<ItemDefinition> defs = DefinitionStore.fromJsonString(json);
		assertEquals(1, defs.size());
		assertEquals("worldforge:a", defs.get(0).id());
	}

	@Test
	void jsonToStringSyncMatchesStoreFormat(@TempDir Path dir) throws IOException {
		DefinitionStore store = new DefinitionStore(dir);
		List<ItemDefinition> defs = List.of(sample("worldforge:a"), sample("worldforge:b"));
		store.save(defs);
		String a = DefinitionStore.toJsonString(defs);
		String b = Files.readString(dir.resolve("items.json"));
		// same data => semantically same (both pretty printed by the same gson)
		assertEquals(b.replaceAll("\\s", ""), a.replaceAll("\\s", ""),
				"S2C sync payload must equal the persisted file content");
	}

	@Test
	void validatorAcceptsDemoAndRejectsBadIds() {
		DefinitionValidator.Result ok = DefinitionValidator.validate(sample("worldforge:thunder_sword"));
		assertTrue(ok.ok(), () -> "sample must validate, got " + ok.errors());

		assertFalse(DefinitionValidator.validate(sample("UPPER CASE!!")).ok());
		assertFalse(DefinitionValidator.validate(sample("no-namespace")).ok());
		assertFalse(DefinitionValidator.validate(
				new ItemDefinition(1, "worldforge:x", "x", "", TextureSource.default_(),
						Map.of("damage", 999999.0), List.of())).ok(), "insane damage must fail");
	}

	@Test
	void attributeDefaultsAreSane() {
		ItemDefinition bare = new ItemDefinition(ItemDefinition.CURRENT_VERSION, "worldforge:bare", "b", "",
				null, Map.of(), null);
		assertEquals(0.0, bare.damage());
		assertEquals(1, bare.maxDurability());
		assertEquals(List.of(), bare.behaviors());
		assertNotNull(bare.texture());
		assertTrue(bare.appearanceSlotIndex() >= 1);
	}

	@Test
	void appearanceSlotResolutionNeverFails() {
		ItemDefinition weird = sample("worldforge:weird")
				.withTexture(new TextureSource("vanilla", "minecraft:item/does_not_exist_anywhere"));
		assertTrue(weird.appearanceSlotIndex() >= 1, "unknown texture degrades to a default slot, never fails");

		ItemDefinition gen = sample("worldforge:gen")
				.withTexture(new TextureSource("generated", null));
		assertTrue(gen.appearanceSlotIndex() >= 1, "unimplemented texture source must not fail creation");
	}

	@Test
	void withAttributeProducesUpdatedCopy() {
		ItemDefinition def = sample("worldforge:thunder_sword");
		ItemDefinition upd = def.withAttribute("damage", 20);
		assertEquals(12.0, def.damage(), "original immutable");
		assertEquals(20.0, upd.damage());
	}
}
