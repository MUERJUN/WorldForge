package dev.worldforge.client;

import com.google.gson.JsonObject;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.persistence.DefinitionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side view of studio data, fed ONLY by server messages (S2C sync and
 * studio response snapshots). The GUI reads exclusively from here - no
 * hard-coded client test data (spec 四), no reads of the server-authoritative
 * manager (spec v0.1.2 #1: display lives in the client mirror only).
 * Cleared on disconnect so nothing leaks between worlds/servers (spec 十三).
 */
public final class ClientStudioState {

	private static final Map<String, ItemDefinition> DEFINITIONS = new ConcurrentHashMap<>();
	private static final Set<String> TOMBSTONES = ConcurrentHashMap.newKeySet();
	private static volatile boolean serverReady = false;

	public static void applySnapshot(String snapshotJson) {
		if (snapshotJson == null || snapshotJson.isBlank()) {
			return;
		}
		try {
			JsonObject o = com.google.gson.JsonParser.parseString(snapshotJson).getAsJsonObject();
			List<ItemDefinition> defs = new ArrayList<>();
			if (o.has("items") && o.get("items").isJsonArray()) {
				for (var el : o.getAsJsonArray("items")) {
					if (el.isJsonObject()) {
						defs.add(DefinitionStore.fromJson(el.getAsJsonObject()));
					}
				}
			}
			Set<String> tombs = ConcurrentHashMap.newKeySet();
			if (o.has("tombstones") && o.get("tombstones").isJsonArray()) {
				o.getAsJsonArray("tombstones").forEach(el -> tombs.add(el.getAsString()));
			}
			DEFINITIONS.clear();
			TOMBSTONES.clear();
			for (ItemDefinition d : defs) {
				DEFINITIONS.put(d.id(), d);
			}
			TOMBSTONES.addAll(tombs);
			serverReady = true;
		} catch (Exception ignored) {
			// malformed snapshot: keep previous view; the GUI shows its own error
		}
	}

	public static boolean serverReady() {
		return serverReady;
	}

	public static ItemDefinition get(String id) {
		return id == null ? null : DEFINITIONS.get(id);
	}

	public static boolean isMissing(String id) {
		return TOMBSTONES.contains(id);
	}

	public static List<ItemDefinition> list() {
		var out = new ArrayList<>(DEFINITIONS.values());
		out.sort((a, b) -> a.id().compareTo(b.id()));
		return out;
	}

	public static List<String> tombstones() {
		return List.copyOf(TOMBSTONES);
	}

	public static void clear() {
		DEFINITIONS.clear();
		TOMBSTONES.clear();
		serverReady = false;
	}

	private ClientStudioState() {
	}
}
