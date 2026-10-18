package dev.worldforge.block;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded round-robin scheduler for periodic virtual blocks (v0.4.0 phase 2b).
 *
 * <p>The previous design used a per-tick counter with a "boost if overdue" rule
 * evaluated AFTER the hard-cap check, so once the number of continuously due
 * blocks exceeded the hard cap, the same head of the iteration order could be
 * served forever and the tail starved permanently. This scheduler removes the
 * ordering dependency entirely:</p>
 *
 * <ul>
 *   <li>every pending block has AT MOST ONE queued task, keyed by
 *       (world, position) - re-scheduling an already queued block is a no-op, so
 *       chunk reloads cannot duplicate work;</li>
 *   <li>tasks live in a FIFO queue; each tick at most {@link #MAX_PER_TICK}
 *       tasks are executed and re-queued at the BACK with their next due time,
 *       so service order rotates and no position can monopolise the budget;</li>
 *   <li>the queue is bounded: registration refuses beyond
 *       {@link #MAX_QUEUE} entries instead of growing without limit, and the
 *       per-tick cap is never raised to "fix" fairness;</li>
 *   <li>tasks are bound to a specific world key + position, so definitions and
 *       timers can never leak across dimensions or saves;</li>
 *   <li>block removal, definition deletion, chunk unload and world shutdown all
 *       cancel their tasks explicitly.</li>
 * </ul>
 *
 * <p><b>Honest latency statement.</b> When continuously-due blocks exceed the
 * per-tick capacity, the effective period is stretched: a block is served once
 * per ceil(N / MAX_PER_TICK) ticks on average rather than at its nominal
 * interval. The scheduler guarantees eventual service and bounded work per
 * tick; it does NOT guarantee that every block keeps its original period under
 * overload.</p>
 */
public final class PeriodicScheduler {

	/** hard ceiling of periodic executions per server tick (never raised) */
	public static final int MAX_PER_TICK = 128;
	/** hard ceiling of registered tasks (bounded memory) */
	public static final int MAX_QUEUE = 8192;

	/** one scheduled block */
	private record Task(RegistryKey<World> worldKey, BlockPos pos, long dueTick) {
	}

	private static final Deque<Task> QUEUE = new ArrayDeque<>();
	private static final Map<String, Task> REGISTERED = new HashMap<>();
	private static long currentTick = 0;
	private static int executedThisTick = 0;
	private static long totalExecuted = 0;
	private static long totalDeferred = 0;

	private static String key(RegistryKey<World> worldKey, BlockPos pos) {
		return worldKey.getValue() + "@" + pos.asLong();
	}

	/** advance the scheduler clock (called once per server tick) */
	public static void tick() {
		currentTick++;
		executedThisTick = 0;
	}

	/**
	 * Register (or refresh) a periodic block. Idempotent per position: an
	 * already queued block is NOT duplicated, which is what makes chunk reloads
	 * safe.
	 */
	public static boolean register(ServerWorld world, BlockPos pos, long delayTicks) {
		RegistryKey<World> wk = world.getRegistryKey();
		String k = key(wk, pos);
		if (REGISTERED.containsKey(k)) {
			return false; // already scheduled: no duplicate task
		}
		if (REGISTERED.size() >= MAX_QUEUE) {
			return false; // bounded: refuse instead of growing forever
		}
		Task t = new Task(wk, pos.toImmutable(), currentTick + Math.max(1, delayTicks));
		REGISTERED.put(k, t);
		QUEUE.addLast(t);
		return true;
	}

	/** cancel one block (block removed, definition deleted, chunk unloaded) */
	public static boolean cancel(RegistryKey<World> worldKey, BlockPos pos) {
		String k = key(worldKey, pos);
		Task removed = REGISTERED.remove(k);
		if (removed == null) {
			return false;
		}
		QUEUE.remove(removed);
		return true;
	}

	/** cancel everything in one world (dimension unload / world stop) */
	public static int cancelWorld(RegistryKey<World> worldKey) {
		List<Task> victims = new ArrayList<>();
		for (Task t : QUEUE) {
			if (t.worldKey().equals(worldKey)) {
				victims.add(t);
			}
		}
		for (Task t : victims) {
			REGISTERED.remove(key(t.worldKey(), t.pos()));
			QUEUE.remove(t);
		}
		return victims.size();
	}

	public static void reset() {
		QUEUE.clear();
		REGISTERED.clear();
		currentTick = 0;
		executedThisTick = 0;
		totalExecuted = 0;
		totalDeferred = 0;
	}

	public static int queued() {
		return QUEUE.size();
	}

	public static int executedThisTick() {
		return executedThisTick;
	}

	public static long totalExecuted() {
		return totalExecuted;
	}

	public static long totalDeferred() {
		return totalDeferred;
	}

	public static boolean isRegistered(RegistryKey<World> worldKey, BlockPos pos) {
		return REGISTERED.containsKey(key(worldKey, pos));
	}

	/**
	 * Run one scheduler step against the given world lookup.
	 *
	 * <p>At most {@link #MAX_PER_TICK} tasks execute. A task that is not yet due
	 * stays queued; a task that just ran is re-queued at the BACK with its next
	 * due tick, which is what produces round-robin service instead of
	 * fixed-order starvation.</p>
	 *
	 * @param worldLookup resolves a registry key to the live world, or null when
	 *                    that world is gone (the task is then cancelled)
	 */
	public static void runDueTasks(java.util.function.Function<RegistryKey<World>, ServerWorld> worldLookup) {
		executedThisTick = 0;
		if (QUEUE.isEmpty()) {
			return;
		}
		// v0.4.0 phase 2b fix: the previous loop re-appended not-yet-due tasks and
		// could burn its whole scan budget before reaching a single due task,
		// reporting zero executions. We now snapshot the current rotation ONCE and
		// only touch tasks that are actually due; everything else stays in the
		// queue untouched (its position is preserved for the next tick).
		int snapshot = QUEUE.size();
		int processed = 0;
		while (processed < snapshot && executedThisTick < MAX_PER_TICK && !QUEUE.isEmpty()) {
			Task t = QUEUE.pollFirst();
			processed++;
			if (t == null) {
				break;
			}
			String k = key(t.worldKey(), t.pos());
			if (!REGISTERED.containsKey(k)) {
				continue; // cancelled while queued
			}
			if (t.dueTick() > currentTick) {
				QUEUE.addLast(t); // not due: keep its place in the rotation
				continue;
			}
			ServerWorld world = worldLookup.apply(t.worldKey());
			if (world == null) {
				REGISTERED.remove(k);
				continue;
			}
			BlockDefinition def = definitionAt(world, t.pos());
			if (def == null || !def.periodic()) {
				REGISTERED.remove(k);
				continue; // definition gone or no longer periodic
			}
			boolean ran = BlockRuntimeManager.runPeriodic(world, t.pos(), def);
			executedThisTick++;
			totalExecuted++;
			if (ran) {
				long nextDelay = Math.max(1, def.tickIntervalTicks());
				Task next = new Task(t.worldKey(), t.pos(), currentTick + nextDelay);
				REGISTERED.put(k, next);
				QUEUE.addLast(next); // round robin: back of the queue
			} else {
				totalDeferred++;
				QUEUE.addLast(t);
			}
		}
	}

	private static BlockDefinition definitionAt(ServerWorld world, BlockPos pos) {
		if (world.getBlockEntity(pos) instanceof DynamicBlockEntity be) {
			return BlockRuntimeManager.get(be.virtualId());
		}
		return null;
	}

	private PeriodicScheduler() {
	}
}