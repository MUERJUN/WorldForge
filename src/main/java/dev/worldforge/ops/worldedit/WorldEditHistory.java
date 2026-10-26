package dev.worldforge.ops.worldedit;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * v0.8.0: the bounded per-player undo history. In-memory ONLY — the history
 * does NOT survive a server restart, and the docs say so honestly.
 *
 * <p>Every entry is bound to the plan's owning player (enforced) and looked
 * up under a world key, so an edit made in one dimension/world can never be
 * undone from another.</p>
 */
public final class WorldEditHistory {

	private final int capPerPlayer;
	private final Map<UUID, Deque<WorldEditPlan>> byPlayer = new HashMap<>();

	public WorldEditHistory(int capPerPlayer) {
		if (capPerPlayer < 1) {
			throw new IllegalArgumentException("the cap must be at least 1");
		}
		this.capPerPlayer = capPerPlayer;
	}

	/** Records a plan under its OWN player. */
	public synchronized void record(WorldEditPlan plan) {
		if (plan == null) {
			throw new NullPointerException("plan");
		}
		recordFor(plan, plan.playerUuid());
	}

	/** Records a plan, asserting it really belongs to the given player. */
	public synchronized void recordFor(WorldEditPlan plan, UUID player) {
		if (plan == null || player == null) {
			throw new NullPointerException("plan/player");
		}
		if (!plan.playerUuid().equals(player)) {
			throw new IllegalArgumentException(
					"the plan belongs to " + plan.playerUuid() + ", not " + player);
		}
		Deque<WorldEditPlan> stack = byPlayer.computeIfAbsent(player,
				k -> new ArrayDeque<>());
		stack.push(plan);
		while (stack.size() > capPerPlayer) {
			stack.removeLast();
		}
	}

	/**
	 * The newest entry for the player + world that is still within its expiry
	 * window, or {@code null}. Expired entries are dropped lazily here.
	 */
	public synchronized WorldEditPlan latestUndoable(UUID player, String worldId) {
		Deque<WorldEditPlan> stack = byPlayer.get(player);
		if (stack == null) {
			return null;
		}
		Iterator<WorldEditPlan> it = stack.iterator();
		while (it.hasNext()) {
			WorldEditPlan p = it.next();
			if (p.isExpiredAt(Instant.now())) {
				it.remove(); // lazy expiry: an old entry never comes back
				continue;
			}
			if (p.worldId().equals(worldId)) {
				return p;
			}
		}
		return null;
	}

	/**
	 * Removes and returns the newest undoable entry for the player + world,
	 * or {@code null} when there is none.
	 */
	public synchronized WorldEditPlan popLatest(UUID player, String worldId) {
		Deque<WorldEditPlan> stack = byPlayer.get(player);
		if (stack == null) {
			return null;
		}
		Iterator<WorldEditPlan> it = stack.iterator();
		while (it.hasNext()) {
			WorldEditPlan p = it.next();
			if (p.isExpiredAt(Instant.now())) {
				it.remove();
				continue;
			}
			if (p.worldId().equals(worldId)) {
				it.remove();
				return p;
			}
		}
		return null;
	}
}
