package dev.worldforge.client;

import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.definition.ItemDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of the definition registry, populated by the S2C sync
 * payload. Used by the (server-authoritative) DynamicItem display path when
 * running on the client, so names/tooltips render correctly in GUIs.
 */
public final class ClientDefinitionCache {

	private static final Map<String, ItemDefinition> CACHE = new ConcurrentHashMap<>();

	public static void putAll(List<ItemDefinition> defs) {
		CACHE.clear();
		for (ItemDefinition d : defs) {
			CACHE.put(d.id(), d);
		}
	}

	public static ItemDefinition get(String id) {
		return id == null ? null : CACHE.get(id);
	}

	public static List<ItemDefinition> list() {
		return List.copyOf(CACHE.values());
	}

	public static void onPayload(String json) {
		putAll(DefinitionStore.fromJsonString(json));
	}

	private ClientDefinitionCache() {
	}
}
