package dev.worldforge.test;

import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.ClientDefinitionMirror;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.tool.WorldForgeTools;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * v0.1.2 regression suite for the three newly triaged issues. These use the
 * v0.1.2 APIs (three-phase ToolResult, mirror-only client display,
 * non-destructive tryLoad), so they compile only against the fixed code.
 * Issue #3 additionally has testR8 in WorldForgeGameTests, written against
 * the shared v0.1.1/v0.1.2 API and EXECUTED on v0.1.1 first: it FAILED there
 * ("corrupt-file reload must NOT wipe live definitions (count was 0)") and
 * passes now - evidence kept in docs/evidence-r8-failed-on-v0.1.1.log.
 */
public class WorldForgeV012Tests {

	// ---- Test R7 (issue #1): world A server state must not leak into a
	// new session; client display resolves ONLY from the mirror ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-v012")
	public void testR7_noCrossWorldLeakage(TestContext ctx) {
		java.nio.file.Path dir;
		try {
			dir = Files.createTempDirectory("wf-r7-a");
		} catch (Exception e) {
			throw new RuntimeException("setup", e);
		}
		// "world A": server manager owns magic_sword with a unique name
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		WorldForgeRuntimeManager.createItem(new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:magic_sword", "A世界之剑", "", TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 9.0, "max_durability", 100.0), List.of()));
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:magic_sword") != null, "world A has the id");

		// simulate SERVER_STOPPED lifecycle release (issue #1 core fix)
		WorldForgeRuntimeManager.onServerStopped();
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:magic_sword") == null,
				"server static state must be released when the world fully stops");

		// "server B" view on a client: mirror fed ONLY with B's definitions.
		ClientDefinitionMirror.clear();
		ClientDefinitionMirror.replaceAllFromSync(
				"{\"version\":1,\"items\":[{\"id\":\"worldforge:magic_sword\",\"name\":\"B世界之剑\",\"version\":1,"
						+ "\"texture\":{\"source\":\"vanilla\",\"resource\":\"minecraft:item/iron_sword\"}}]}",
				List.of());
		var shown = dev.worldforge.item.DynamicItem.resolveForClientDisplay("worldforge:magic_sword");
		ctx.assertTrue(shown != null && "B世界之剑".equals(shown.name()),
				"client display must come from the mirror snapshot (B), got: " + shown);

		// the authoritative logic path on this JVM (now empty server state)
		// must NOT resurrect A's or B's display data
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:magic_sword") == null,
				"logic lookup must stay server-authoritative and empty");

		ClientDefinitionMirror.clear();
		ctx.complete();
	}

	// ---- Test R9 (issue #2): three-phase result separation ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-v012")
	public void testR9_threePhaseResults(TestContext ctx) {
		java.nio.file.Path dir;
		try {
			dir = Files.createTempDirectory("wf-r9");
		} catch (Exception e) {
			throw new RuntimeException("setup", e);
		}
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		ItemDefinition def = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:r9_item", "R9", "", TextureSource.vanilla("minecraft:item/diamond"),
				Map.of("damage", 1.0, "max_durability", 10.0), List.of());

		// healthy pipeline: persisted + applied; sync NONE (no WF-capable
		// players connected in this headless batch)
		var ok = WorldForgeTools.createItem(ctx.getWorld().getServer(), def);
		ctx.assertTrue(ok.persisted(), "disk phase");
		ctx.assertTrue(ok.applied(), "apply phase");
		ctx.assertTrue(ok.sync() == WorldForgeTools.ToolResult.SyncStatus.NONE,
				"no WF client connected -> NONE, got " + ok.sync());

		// disk failure: everything downstream skipped and reported honestly
		try {
			Files.delete(dir.resolve("items.json"));
			Files.createDirectories(dir.resolve("items.json")); // unmovable target
		} catch (Exception e) {
			throw new RuntimeException("break disk", e);
		}
		var fail = WorldForgeTools.updateItem(ctx.getWorld().getServer(), def.withAttribute("damage", 2.0));
		ctx.assertTrue(!fail.persisted(), "disk failure must be surfaced, not swallowed");
		ctx.assertTrue(!fail.applied() && fail.sync() == WorldForgeTools.ToolResult.SyncStatus.NONE,
				"failed disk phase must not pretend apply/sync happened");
		ctx.assertTrue(!fail.ok(), "overall result is failure");
		// and the old value is still authoritative (manager rolled back)
		ctx.assertTrue(Math.abs(WorldForgeRuntimeManager.get("worldforge:r9_item").damage() - 1.0) < 0.001,
				"rolled-back damage must still read 1.0");

		// retry hook exists and reports fresh status once disk is healthy again
		try {
			Files.delete(dir.resolve("items.json"));
		} catch (Exception e) {
			throw new RuntimeException("restore", e);
		}
		var retry = WorldForgeTools.resync(ctx.getWorld().getServer());
		ctx.assertTrue(retry.persisted() && retry.applied(), "resync re-applies cleanly");
		ctx.complete();
	}


	// ---- Test R11 (v0.2.0 stage-1 P1): reload must reject PARTIALLY damaged
	// files. A file where one entry parses and one is broken currently passes
	// tryLoad() silently -> reload replaces runtime with the good subset ->
	// next save permanently drops the damaged record (silent data loss).
	// Strict reload must refuse to overwrite in that case and keep live state.
	// (Written before the fix: FAILS on v0.1.2 code.)
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-v020")
	public void testR11_strictReloadRejectsPartialDamage(TestContext ctx) {
		java.nio.file.Path dir;
		try {
			dir = Files.createTempDirectory("wf-r11");
		} catch (Exception e) {
			throw new RuntimeException("setup", e);
		}
		DefinitionStore store = new DefinitionStore(dir);
		WorldForgeRuntimeManager.attachStoreForTesting(store);
		// live runtime has an item; the create below saves it to disk first...
		ctx.assertTrue(WorldForgeRuntimeManager.createItem(new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:r11_live_only", "仅活动", "", TextureSource.vanilla("minecraft:item/diamond"),
				Map.of("damage", 3.0, "max_durability", 10.0), List.of())).ok(), "live create");
		// ...THEN the file is externally damaged: one GOOD entry that does NOT
		// include the live item + one BROKEN entry (missing id).
		try {
			Files.writeString(dir.resolve("items.json"), """
					{"version":1,"items":[
					  {"version":1,"id":"worldforge:r11_ok","name":"好条目"},
					  {"no_id_field":true}
					]}""");
		} catch (Exception e) {
			throw new RuntimeException("damage", e);
		}

		int n = WorldForgeRuntimeManager.reloadDefinitions();
		// STRICT reload (v0.2.0): any malformed entry aborts the whole replace
		ctx.assertTrue(n < 0, "reload with a partially damaged file must FAIL, got count " + n);
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:r11_live_only") != null,
				"live-only definition must survive an aborted strict reload");
		// the player file must be untouched
		try {
			String after = Files.readString(dir.resolve("items.json"));
			ctx.assertTrue(after.contains("no_id_field"),
					"strict reload must not rewrite the player's file");
			// and any later save must not silently drop the damaged record either:
			// (state preserved => save persists the preserved state, file still on disk untouched until then)
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		ctx.complete();
	}

	// ---- Test R12: missing "items" field is NOT a legit empty project on the
	// strict path; version newer than supported is rejected ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-v020")
	public void testR12_strictLoadRejectsShapes(TestContext ctx) {
		try {
			java.nio.file.Path d1 = Files.createTempDirectory("wf-r12a");
			Files.writeString(d1.resolve("items.json"), "{\"version\":1}");
			var r1 = new DefinitionStore(d1).tryLoadStrict();
			ctx.assertTrue(!r1.ok(), "missing items array must be rejected strictly");

			java.nio.file.Path d2 = Files.createTempDirectory("wf-r12b");
			Files.writeString(d2.resolve("items.json"), "{\"version\":99,\"items\":[]}");
			var r2 = new DefinitionStore(d2).tryLoadStrict();
			ctx.assertTrue(!r2.ok(), "unsupported newer file version must be rejected strictly");

			java.nio.file.Path d3 = Files.createTempDirectory("wf-r12c");
			Files.writeString(d3.resolve("items.json"), """
					{"version":1,"items":[
					  {"version":1,"id":"worldforge:dup","name":"A"},
					  {"version":1,"id":"worldforge:dup","name":"B"}
					]}""");
			var r3 = new DefinitionStore(d3).tryLoadStrict();
			ctx.assertTrue(!r3.ok(), "duplicate ids must be rejected strictly");

			java.nio.file.Path d4 = Files.createTempDirectory("wf-r12d");
			Files.writeString(d4.resolve("items.json"), "{\"version\":1,\"items\":[]}");
			var r4 = new DefinitionStore(d4).tryLoadStrict();
			ctx.assertTrue(r4.ok() && r4.definitions().isEmpty(),
					"a genuinely empty project stays valid");

			java.nio.file.Path d5 = Files.createTempDirectory("wf-r12e");
			Files.writeString(d5.resolve("items.json"), """
					{"version":1,"items":[
					  {"version":1,"id":"worldforge:badtype","attributes":{"damage":"NaN-string"}}
					]}""");
			var r5 = new DefinitionStore(d5).tryLoadStrict();
			ctx.assertTrue(!r5.ok(), "wrong field types must be rejected strictly");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		ctx.complete();
	}

	// ---- Test R10 (issue #3): store-level non-destructive load ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-v012")
	public void testR10_tryLoadNeverQuarantines(TestContext ctx) {
		java.nio.file.Path dir;
		try {
			dir = Files.createTempDirectory("wf-r10");
			Files.writeString(dir.resolve("items.json"), "{oops");
		} catch (Exception e) {
			throw new RuntimeException("setup", e);
		}
		DefinitionStore store = new DefinitionStore(dir);
		var res = store.tryLoad();
		ctx.assertTrue(!res.ok(), "corrupt file -> failed result");
		ctx.assertTrue(Files.exists(dir.resolve("items.json")),
				"tryLoad must NOT move the damaged file aside (player decides)");
		try (var s = Files.list(dir)) {
			ctx.assertTrue(s.noneMatch(p -> p.getFileName().toString().contains(".broken-")),
					"no quarantine side effects on reload path");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		ctx.complete();
	}
}
