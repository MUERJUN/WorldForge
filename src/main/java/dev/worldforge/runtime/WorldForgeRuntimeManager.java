package dev.worldforge.runtime;

import dev.worldforge.WorldForge;
import dev.worldforge.definition.DefinitionValidator;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.persistence.DefinitionStore;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SERVER-authoritative runtime owner of every virtual item definition.
 *
 * <p>v0.1.1 changes (GitHub issue triage):</p>
 * <ul>
 *   <li>#1 Persistence is part of the mutation contract: every create/update/
 *       delete first writes to disk; if the write fails the in-memory change
 *       is ROLLED BACK and the failure (with cause) is returned. No more
 *       "fake success" where memory and disk diverge. The previous on-disk
 *       file is untouched on failure (atomic temp+move), so the save is
 *       protected.</li>
 *   <li>#2 This class holds ONLY server state. The client mirror lives in
 *       {@link ClientDefinitionMirror} - a separate store - so in a single-
 *       player / integrated server (same JVM, both sides) a client-side sync
 *       write can never wipe server truth. Display code resolves through
 *       DynamicItem, which consults the server manager first and the mirror
 *       second.</li>
 *   <li>#3 Prefer {@link dev.worldforge.tool.WorldForgeTools} as the single
 *       mutation entry point (validation + rollback + broadcast); commands
 *       and the future AI AgentBridge both call the tools.</li>
 * </ul>
 *
 * <p>Deletion policy: a definition is removed but stacks keep their
 * {@code worldforge:virtual_id} component and a tombstone marks the id.
 * Those stacks render as invalid entries and are combat-stripped by
 * {@link dev.worldforge.item.DynamicItem#refreshStack} - never silently
 * destroyed (spec 3.3).</p>
 */
public final class WorldForgeRuntimeManager {

	private static final Map<String, ItemDefinition> DEFINITIONS = new ConcurrentHashMap<>();

	/** ids whose definitions were deleted while stacks still referenced them. */
	private static final Set<String> TOMBSTONES = ConcurrentHashMap.newKeySet();

	private static volatile DefinitionStore store;
	private static volatile String loadedWorldKey = null;

	// ---- query (server truth) ----

	public static ItemDefinition get(String virtualId) {
		return virtualId == null ? null : DEFINITIONS.get(virtualId);
	}

	public static boolean isMissing(String virtualId) {
		return virtualId != null && TOMBSTONES.contains(virtualId);
	}

	public static List<ItemDefinition> list() {
		List<ItemDefinition> out = new ArrayList<>(DEFINITIONS.values());
		out.sort((a, b) -> a.id().compareTo(b.id()));
		return out;
	}

	public static Set<String> tombstones() {
		return Set.copyOf(TOMBSTONES);
	}

	/** True when this JVM currently runs the authoritative side (server). */
	public static boolean hasServerStore() {
		return store != null;
	}

	// ---- mutations: apply -> persist -> rollback on persistence failure ----

	public record MutationResult(boolean ok, String message) {}

	public static synchronized MutationResult createItem(ItemDefinition def) {
		if (def == null) {
			return new MutationResult(false, "definition is null");
		}
		DefinitionValidator.Result v = DefinitionValidator.validate(def);
		if (!v.ok()) {
			return new MutationResult(false, String.join("; ", v.errors()));
		}
		if (DEFINITIONS.containsKey(def.id())) {
			return new MutationResult(false, "already exists: " + def.id() + " (use update)");
		}
		v.warnings().forEach(w -> WorldForge.LOGGER.warn("[WorldForge] create {}: {}", def.id(), w));
		boolean wasTomb = TOMBSTONES.remove(def.id());
		DEFINITIONS.put(def.id(), def);
		if (!saveOrRollback("create " + def.id(), () -> {
			DEFINITIONS.remove(def.id());
			if (wasTomb) {
				TOMBSTONES.add(def.id());
			}
		})) {
			return failure("create", def.id());
		}
		return new MutationResult(true, "created " + def.id());
	}

	public static synchronized MutationResult updateItem(ItemDefinition def) {
		ItemDefinition previous = def == null ? null : DEFINITIONS.get(def.id());
		if (def == null || previous == null) {
			return new MutationResult(false, def == null ? "definition is null" : "no such item: " + def.id());
		}
		DefinitionValidator.Result v = DefinitionValidator.validate(def);
		if (!v.ok()) {
			return new MutationResult(false, String.join("; ", v.errors()));
		}
		DEFINITIONS.put(def.id(), def);
		if (!saveOrRollback("update " + def.id(), () -> DEFINITIONS.put(def.id(), previous))) {
			return failure("update", def.id());
		}
		return new MutationResult(true, "updated " + def.id());
	}

	public static synchronized MutationResult deleteItem(String id) {
		ItemDefinition previous = DEFINITIONS.get(id);
		if (previous == null) {
			return new MutationResult(false, "no such item: " + id);
		}
		DEFINITIONS.remove(id);
		TOMBSTONES.add(id);
		if (!saveOrRollback("delete " + id, () -> {
			DEFINITIONS.put(id, previous);
			TOMBSTONES.remove(id);
		})) {
			return failure("delete", id);
		}
		return new MutationResult(true, "deleted " + id
				+ " (existing stacks are marked invalid and stripped, not destroyed)");
	}

	private static MutationResult failure(String op, String id) {
		DefinitionStore s = store;
		String cause = s == null ? "unknown" : String.valueOf(s.lastError());
		return new MutationResult(false, op + " " + id + " rolled back: definitions could NOT be saved to disk (" + cause + ")");
	}

	/** Persist current state; on failure run the rollback and report false. */
	private static boolean saveOrRollback(String what, Runnable rollback) {
		if (save()) {
			return true;
		}
		rollback.run();
		WorldForge.LOGGER.error("[WorldForge] mutation '{}' rolled back because persistence failed", what);
		return false;
	}

	/**
	 * Write the authoritative state to disk.
	 * A null store means "in-memory mode" (unit/game tests) - treated as ok.
	 */
	public static boolean save() {
		DefinitionStore s = store;
		if (s == null) {
			return true;
		}
		return s.save(list());
	}

	/** Replace the definition ignoring existence checks; used by load/reload paths. */
	static void putQuietly(ItemDefinition def) {
		DEFINITIONS.put(def.id(), def);
		TOMBSTONES.remove(def.id());
	}

	// ---- world lifecycle (server side) ----

	/**
	 * Bind the manager to the running server's world directory and load
	 * definitions. Safe on every SERVER_STARTED; state is rebuilt from disk so
	 * repeated world entries never duplicate anything.
	 */
	public static synchronized void onServerStarted(MinecraftServer server) {
		Path dir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("worldforge");
		String worldKey = dir.toAbsolutePath().toString();
		if (store != null && worldKey.equals(loadedWorldKey)) {
			// Same world restarted (integrated server re-entry): pick up disk truth.
			reloadDefinitions();
			return;
		}
		DEFINITIONS.clear();
		TOMBSTONES.clear();
		store = new DefinitionStore(dir);
		loadedWorldKey = worldKey;
		int n = 0;
		for (ItemDefinition def : store.load()) {
			DefinitionValidator.Result v = DefinitionValidator.validate(def);
			if (!v.ok()) {
				WorldForge.LOGGER.warn("[WorldForge] skipping invalid stored definition {}: {}", def.id(), v.errors());
				continue;
			}
			putQuietly(def);
			n++;
		}
		WorldForge.LOGGER.info("[WorldForge] loaded {} definitions for world {}", n, worldKey);
	}

	public static synchronized void onServerStopping(MinecraftServer server) {
		if (!save()) {
			WorldForge.LOGGER.error("[WorldForge] SERVER STOPPING WITH UNSAVED CHANGES - "
					+ "definitions edited this session were NOT persisted ({})",
					store == null ? "?" : store.lastError());
		}
	}

	/** Re-read definitions from disk, replacing the runtime map. */
	public static synchronized int reloadDefinitions() {
		DefinitionStore s = store;
		if (s == null) {
			return -1;
		}
		Map<String, ItemDefinition> previous = Map.copyOf(DEFINITIONS);
		DEFINITIONS.clear();
		int n = 0;
		for (ItemDefinition def : s.load()) {
			DefinitionValidator.Result v = DefinitionValidator.validate(def);
			if (!v.ok()) {
				WorldForge.LOGGER.warn("[WorldForge] reload skipped invalid {}: {}", def.id(), v.errors());
				continue;
			}
			putQuietly(def);
			n++;
		}
		// Anything that disappeared on disk becomes a tombstone so stacks show as invalid.
		for (String id : previous.keySet()) {
			if (!DEFINITIONS.containsKey(id)) {
				TOMBSTONES.add(id);
			}
		}
		WorldForge.LOGGER.info("[WorldForge] reloaded {} definitions", n);
		return n;
	}

	/** Test hook: bind a store (null = in-memory mode) and clear state. */
	public static void attachStoreForTesting(DefinitionStore s) {
		store = s;
		loadedWorldKey = s == null ? null : "test:" + System.nanoTime();
		DEFINITIONS.clear();
		TOMBSTONES.clear();
	}

	private WorldForgeRuntimeManager() {
	}
}
