package dev.worldforge.ops.worldedit;

import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable world edit plan (v0.8.0): exactly what the player approved,
 * bound to the exact positions, original states and target states. The AI's
 * prose is never an execution input — only this structure, validated
 * server-side, executes.
 *
 * <p>The plan layer is deliberately registry-free: entries are STRINGS. The
 * executor resolves them through the live registry with strict validation,
 * and refuses the whole plan when any single entry cannot resolve.</p>
 */
public record WorldEditPlan(
		String planId,
		UUID playerUuid,
		String worldId,
		List<BlockPos> positions,
		Map<BlockPos, WorldEditEntry> originalStates,
		Map<BlockPos, WorldEditEntry> newStates,
		Instant createdAt,
		Instant expiresAt) {

	public WorldEditPlan {
		if (planId == null || planId.isBlank()) {
			throw new IllegalArgumentException("planId is required");
		}
		if (playerUuid == null) {
			throw new IllegalArgumentException("playerUuid is required");
		}
		if (worldId == null || worldId.isBlank()) {
			throw new IllegalArgumentException("worldId is required");
		}
		if (positions == null || positions.isEmpty()) {
			throw new IllegalArgumentException("a plan needs at least one position");
		}
		if (positions.size() > WorldEditValidator.MAX_BLOCKS_PER_PLAN) {
			throw new IllegalArgumentException("a plan is capped at "
					+ WorldEditValidator.MAX_BLOCKS_PER_PLAN + " blocks, got " + positions.size());
		}
		if (originalStates == null || newStates == null) {
			throw new IllegalArgumentException("original and target states are required");
		}
		for (BlockPos pos : positions) {
			if (pos == null) {
				throw new IllegalArgumentException("positions must not contain null");
			}
			if (!originalStates.containsKey(pos) || !newStates.containsKey(pos)) {
				throw new IllegalArgumentException(
						"every position needs an original AND a target entry: " + pos);
			}
			if (originalStates.get(pos) == null || newStates.get(pos) == null) {
				throw new IllegalArgumentException("entries must not be null: " + pos);
			}
		}
		if (originalStates.size() != positions.size() || newStates.size() != positions.size()) {
			throw new IllegalArgumentException(
					"state maps must cover exactly the plan's positions");
		}
		if (createdAt == null || expiresAt == null) {
			throw new IllegalArgumentException("timestamps are required");
		}
		if (!expiresAt.isAfter(createdAt)) {
			throw new IllegalArgumentException("the plan must expire AFTER it was created");
		}
		positions = List.copyOf(positions);
		originalStates = Map.copyOf(originalStates);
		newStates = Map.copyOf(newStates);
	}

	public static WorldEditPlan of(String planId, UUID playerUuid, String worldId,
			List<BlockPos> positions, Map<BlockPos, WorldEditEntry> originalStates,
			Map<BlockPos, WorldEditEntry> newStates, Instant createdAt, Instant expiresAt) {
		return new WorldEditPlan(planId, playerUuid, worldId, positions,
				originalStates, newStates, createdAt, expiresAt);
	}

	/**
	 * SHA-256 over the sorted position/entry content — the plan's content
	 * identity. Two plans with the same positions and entries share it; any
	 * change to ANY original or target entry changes it.
	 */
	public String fingerprint() {
		List<String> lines = new ArrayList<>();
		for (BlockPos pos : positions) {
			lines.add(pos.getX() + "," + pos.getY() + "," + pos.getZ()
					+ "|" + originalStates.get(pos).toText()
					+ "|" + newStates.get(pos).toText());
		}
		lines.sort(Comparator.naturalOrder());
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(String.join("\n", lines)
					.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			// SHA-256 is guaranteed on every Java platform
			throw new IllegalStateException("fingerprint failed", e);
		}
	}

	/** True when {@code now} is at or past the plan's expiry deadline. */
	public boolean isExpiredAt(Instant now) {
		return !now.isBefore(expiresAt);
	}

	public int affectedCount() {
		return positions.size();
	}
}
