package dev.worldforge.ops.worldedit;

import java.util.Map;

/**
 * One target position's block description in STRING form — the plan layer
 * never touches live {@code BlockState} objects. Resolution to a real state
 * happens in the executor via the registry, with strict validation (the
 * model's strings never reach the world unchecked).
 *
 * @param blockId    a namespaced block id, e.g. {@code minecraft:glass}
 * @param properties block state properties, e.g. {@code facing=north}
 */
public record WorldEditEntry(String blockId, Map<String, String> properties) {

	public WorldEditEntry {
		if (blockId == null || !blockId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
			throw new IllegalArgumentException(
					"block id must be a lowercase namespaced id: " + blockId);
		}
		if (properties == null) {
			properties = Map.of();
		}
		for (Map.Entry<String, String> e : properties.entrySet()) {
			if (e.getKey() == null || !e.getKey().matches("[a-z0-9_]+")) {
				throw new IllegalArgumentException(
						"property keys must be lowercase snake_case: " + e.getKey());
			}
			if (e.getValue() == null || e.getValue().isBlank()) {
				throw new IllegalArgumentException(
						"property values must be non-empty: " + e.getKey());
			}
		}
		properties = Map.copyOf(properties);
	}

	/** A default-state entry for the given block id. */
	public static WorldEditEntry of(String blockId) {
		return new WorldEditEntry(blockId, Map.of());
	}

	/** An entry with explicit state properties. */
	public static WorldEditEntry of(String blockId, Map<String, String> properties) {
		return new WorldEditEntry(blockId, properties);
	}

	/** A compact canonical text form, e.g. {@code minecraft:oak_stairs[facing=north]}. */
	public String toText() {
		if (properties.isEmpty()) {
			return blockId;
		}
		StringBuilder sb = new StringBuilder(blockId).append('[');
		boolean first = true;
		for (Map.Entry<String, String> e : properties.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(e.getKey()).append('=').append(e.getValue());
		}
		return sb.append(']').toString();
	}
}
