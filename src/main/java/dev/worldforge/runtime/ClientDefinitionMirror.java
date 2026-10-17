package dev.worldforge.runtime;

import dev.worldforge.WorldForge;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.persistence.DefinitionStore;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLIENT-side mirror of the authoritative definition set (issue #2).
 *
 * <p>In single player / integrated server the client and server live in ONE
 * JVM. Sharing a single static map (v0.1.0) meant a client-side sync payload
 * could clear server truth and tombstones mid-session. This mirror is a
 * separate store fed only by S2C sync and cleared only on disconnect;
 * DynamicItem resolves definitions as: server manager first (authoritative,
 * present on dedicated/integrated server), then this mirror (pure client on
 * remote servers).</p>
 */
public final class ClientDefinitionMirror {

	private static final Map<String, ItemDefinition> MIRROR = new ConcurrentHashMap<>();
	private static final Set<String> SERVER_TOMBSTONES = ConcurrentHashMap.newKeySet();

	public static ItemDefinition get(String virtualId) {
		return virtualId == null ? null : MIRROR.get(virtualId);
	}

	public static boolean isMissing(String virtualId) {
		return virtualId != null && SERVER_TOMBSTONES.contains(virtualId);
	}

	/** Apply a full snapshot from the server (join / after every mutation). */
	public static void replaceAllFromSync(String json, java.util.Collection<String> tombstones) {
		MIRROR.clear();
		SERVER_TOMBSTONES.clear();
		for (ItemDefinition def : DefinitionStore.fromJsonString(json)) {
			MIRROR.put(def.id(), def);
		}
		if (tombstones != null) {
			SERVER_TOMBSTONES.addAll(tombstones);
		}
		WorldForge.LOGGER.debug("[WorldForge] client mirror updated: {} defs", MIRROR.size());
	}

	/** Disconnect: drop everything so a later server cannot inherit stale names. */
	public static void clear() {
		MIRROR.clear();
		SERVER_TOMBSTONES.clear();
	}

	private ClientDefinitionMirror() {
	}
}
