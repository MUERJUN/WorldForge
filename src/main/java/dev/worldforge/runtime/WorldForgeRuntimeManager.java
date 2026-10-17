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
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single runtime owner of every virtual item definition.
 *
 * <p>Lifecycle: created empty at mod init; on server/world start it loads the
 * per-world {@code worldforge/items.json}; on shutdown (and on every mutation)
 * it saves back. All game-thread reads go through {@link #get(String)}.
 * Definitions may be mutated while the game runs - stacks resolve their name,
 * damage, lore and appearance through this manager on use, so edits are
 * effective immediately without re-creating stacks.</p>
 *
 * <p>Deletion policy: a definition is removed but stacks keep their
 * {@code worldforge:virtual_id} component. Those stacks render as "missing
 * item" entries with an invalid marker resolved at display time
 * ({@link #isMissing}) - they are never silently destroyed (spec 3.3).</p>
 */
public final class WorldForgeRuntimeManager {

	private static final Map<String, ItemDefinition> DEFINITIONS = new ConcurrentHashMap<>();

	/** ids whose definitions were deleted while stacks still referenced them (best-effort UX marker). */
	private static final java.util.Set<String> TOMBSTONES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static volatile DefinitionStore store;
	private static volatile String loadedWorldKey = null;

	// ---- query ----

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

	// ---- mutations ----

	public record MutationResult(boolean ok, String message) {}

	public static MutationResult createItem(ItemDefinition def) {
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
		TOMBSTONES.remove(def.id());
		DEFINITIONS.put(def.id(), def);
		save();
		return new MutationResult(true, "created " + def.id());
	}

	public static MutationResult updateItem(ItemDefinition def) {
		if (def == null || !DEFINITIONS.containsKey(def.id())) {
			return new MutationResult(false, def == null ? "definition is null" : "no such item: " + def.id());
		}
		DefinitionValidator.Result v = DefinitionValidator.validate(def);
		if (!v.ok()) {
			return new MutationResult(false, String.join("; ", v.errors()));
		}
		DEFINITIONS.put(def.id(), def);
		save();
		return new MutationResult(true, "updated " + def.id());
	}

	public static MutationResult deleteItem(String id) {
		ItemDefinition removed = DEFINITIONS.remove(id);
		if (removed == null) {
			return new MutationResult(false, "no such item: " + id);
		}
		TOMBSTONES.add(id);
		save();
		return new MutationResult(true, "deleted " + id
				+ " (existing stacks are marked invalid, not destroyed)");
	}

	/** Replace the definition ignoring existence checks; used by reload/load paths. */
	static void putQuietly(ItemDefinition def) {
		DEFINITIONS.put(def.id(), def);
		TOMBSTONES.remove(def.id());
	}

	// ---- persistence hooks ----

	public static void save() {
		DefinitionStore s = store;
		if (s != null) {
			s.save(list());
		}
	}

	/**
	 * Bind the manager to the running server's world directory and load
	 * definitions. Safe to call on every SERVER_STARTED; state is rebuilt from
	 * disk each time so repeated world entries do not duplicate anything.
	 */
	public static void onServerStarted(MinecraftServer server) {
		Path dir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("worldforge");
		String worldKey = dir.toAbsolutePath().toString();
		if (store != null && worldKey.equals(loadedWorldKey)) {
			// Same world restarted without stopping (integrated server reuse) - reload anyway to pick up disk truth.
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

	public static void onServerStopping(MinecraftServer server) {
		save();
	}

	/** Re-read definitions from disk, replacing the runtime map. */
	public static int reloadDefinitions() {
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

	/** Client mirror: replace the whole definition map from an S2C sync payload. */
	public static void replaceAllFromSync(String json) {
		DEFINITIONS.clear();
		TOMBSTONES.clear();
		for (ItemDefinition def : DefinitionStore.fromJsonString(json)) {
			putQuietly(def);
		}
	}

	/** Client mirror: cleared on disconnect so stale names never leak between servers. */
	public static void clearClientMirror() {
		DEFINITIONS.clear();
		TOMBSTONES.clear();
	}

	/** Test hook: bind an in-memory store and clear state. */
	public static void attachStoreForTesting(DefinitionStore s) {
		store = s;
		loadedWorldKey = s == null ? null : "test";
		DEFINITIONS.clear();
		TOMBSTONES.clear();
	}

	private WorldForgeRuntimeManager() {
	}
}
