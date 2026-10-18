package dev.worldforge.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockDefinitionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of virtual BLOCK definitions (v0.4.0).
 *
 * <p>Fed exclusively by server messages, never by the server-authoritative
 * manager - the same rule the item mirror follows (v0.1.2 issue #1). The block
 * entity renderer resolves appearances from here, so a client can never render
 * data the server did not send, and a deleted definition shows the documented
 * fallback instead of a stale appearance.</p>
 */
public final class ClientBlockState {

	private static final Map<String, BlockDefinition> DEFINITIONS = new ConcurrentHashMap<>();
	private static volatile boolean ready = false;

	/** apply a full snapshot (definitions + tombstones) from the server */
	public static boolean applySnapshot(String json) {
		if (json == null || json.isBlank()) {
			return true;
		}
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			Map<String, BlockDefinition> next = new ConcurrentHashMap<>();
			if (root.has("blocks") && root.get("blocks").isJsonArray()) {
				for (var el : root.getAsJsonArray("blocks")) {
					if (!el.isJsonObject()) {
						continue;
					}
					BlockDefinition d = BlockDefinitionStore.fromJson(el.getAsJsonObject());
					if (d.id() != null) {
						next.put(d.id(), d);
					}
				}
			}
			DEFINITIONS.clear();
			DEFINITIONS.putAll(next);
			ready = true;
			return true;
		} catch (Exception e) {
			// keep the previous view; the caller shows the error
			ready = false;
			return false;
		}
	}

	public static boolean ready() {
		return ready;
	}

	public static BlockDefinition get(String id) {
		return id == null ? null : DEFINITIONS.get(id);
	}

	public static List<BlockDefinition> list() {
		List<BlockDefinition> out = new ArrayList<>(DEFINITIONS.values());
		out.sort((a, b) -> a.id().compareTo(b.id()));
		return out;
	}

	public static void clear() {
		DEFINITIONS.clear();
		ready = false;
	}

	private ClientBlockState() {
	}
}