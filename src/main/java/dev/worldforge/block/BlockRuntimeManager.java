package dev.worldforge.block;

import dev.worldforge.WorldForge;
import dev.worldforge.persistence.DefinitionStore;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-authoritative registry of virtual block definitions (v0.4.0).
 *
 * <p>Mirrors the item side: definitions live in the world's
 * {@code worldforge/blocks.json}, are loaded strictly on world start, and a
 * placed block resolves its identity through {@link DynamicBlockEntity}.</p>
 *
 * <p>Periodic behaviour is quota-limited: a per-tick budget caps how many
 * periodic blocks may run world-wide, so a large field of periodic blocks
 * degrades into "some blocks idle this tick" instead of a server stall. This
 * is a deliberate, documented runtime limit rather than a hidden failure.</p>
 */
public final class BlockRuntimeManager {

	/** soft budget of periodic emissions per server tick (fairness target) */
	public static final int PERIODIC_BUDGET_PER_TICK = 32;
	/** absolute ceiling per tick, even under starvation pressure */
	public static final int PERIODIC_HARD_CAP_PER_TICK = 128;
	/** how many ticks a block may wait past its interval before it is boosted */
	public static final int FAIRNESS_GRACE_TICKS = 2;
	/** diagnostics: boosted emissions during the current tick */
	private static int periodicBoostedThisTick;

	private static final Map<String, BlockDefinition> DEFINITIONS = new LinkedHashMap<>();
	private static final Set<String> TOMBSTONES = new LinkedHashSet<>();
	private static volatile BlockDefinitionStore store;
	private static volatile String loadedWorldKey;
	private static int periodicUsedThisTick;

	public static synchronized void attachStoreForTesting(BlockDefinitionStore s) {
		store = s;
		loadedWorldKey = s == null ? null : "test:" + System.nanoTime();
		DEFINITIONS.clear();
		TOMBSTONES.clear();
	}

	public static synchronized void bind(Path worldforgeDir) {
		BlockDefinitionStore s = new BlockDefinitionStore(worldforgeDir);
		var strict = s.tryLoadStrict();
		if (strict.ok()) {
			DEFINITIONS.clear();
			TOMBSTONES.clear();
			strict.definitions().forEach(d -> DEFINITIONS.put(d.id(), d));
			store = s;
			loadedWorldKey = worldforgeDir.toString();
			WorldForge.LOGGER.info("[WorldForge] loaded {} virtual block definitions", DEFINITIONS.size());
			return;
		}
		// damaged file: salvage what we can, quarantine, and say so loudly
		WorldForge.LOGGER.error("[WorldForge] block definitions could NOT be loaded strictly: {}", strict.error());
		var lenient = s.tryLoadLenient();
		lenient.skipped().forEach(skip -> WorldForge.LOGGER.error("[WorldForge] LOST BLOCK ENTRY: {}", skip));
		s.quarantine();
		DEFINITIONS.clear();
		TOMBSTONES.clear();
		lenient.definitions().forEach(d -> DEFINITIONS.put(d.id(), d));
		store = s;
		loadedWorldKey = worldforgeDir.toString();
	}

	public static synchronized void release() {
		DEFINITIONS.clear();
		TOMBSTONES.clear();
		store = null;
		loadedWorldKey = null;
		periodicUsedThisTick = 0;
	}

	public static String loadedWorldKey() {
		return loadedWorldKey;
	}

	public static BlockDefinition get(String id) {
		return id == null ? null : DEFINITIONS.get(id);
	}

	public static List<BlockDefinition> list() {
		return List.copyOf(DEFINITIONS.values());
	}

	public static Set<String> tombstones() {
		return Set.copyOf(TOMBSTONES);
	}

	// ---------------------------------------------------------------- mutations

	public record MutationResult(boolean ok, String message) {
	}

	public static synchronized MutationResult create(BlockDefinition def) {
		if (def.id() == null || def.id().isBlank()) {
			return new MutationResult(false, "id 不能为空");
		}
		if (DEFINITIONS.containsKey(def.id())) {
			return new MutationResult(false, "已存在同名方块定义：" + def.id());
		}
		// v0.4.0 fix: snapshot BOTH maps BEFORE touching either of them. The
		// previous order removed the tombstone first, so a failed save restored
		// the definition map but silently lost the tombstone - leaving a deleted
		// id looking "never existed" (placed blocks would then resolve as null
		// without the失效 marker being tracked).
		Map<String, BlockDefinition> defSnapshot = new LinkedHashMap<>(DEFINITIONS);
		Set<String> tombSnapshot = new LinkedHashSet<>(TOMBSTONES);

		DEFINITIONS.put(def.id(), def);
		TOMBSTONES.remove(def.id());
		if (!persist()) {
			restore(defSnapshot, tombSnapshot);
			return new MutationResult(false, "方块定义未能写入磁盘，已回滚");
		}
		return new MutationResult(true, "created " + def.id());
	}

	public static synchronized MutationResult update(BlockDefinition def) {
		if (!DEFINITIONS.containsKey(def.id())) {
			return new MutationResult(false, "方块定义不存在：" + def.id());
		}
		Map<String, BlockDefinition> defSnapshot = new LinkedHashMap<>(DEFINITIONS);
		Set<String> tombSnapshot = new LinkedHashSet<>(TOMBSTONES);
		DEFINITIONS.put(def.id(), def);
		if (!persist()) {
			restore(defSnapshot, tombSnapshot);
			return new MutationResult(false, "方块定义未能写入磁盘，已回滚");
		}
		return new MutationResult(true, "updated " + def.id());
	}

	public static synchronized MutationResult delete(String id) {
		if (!DEFINITIONS.containsKey(id)) {
			return new MutationResult(false, "方块定义不存在：" + id);
		}
		Map<String, BlockDefinition> defSnapshot = new LinkedHashMap<>(DEFINITIONS);
		Set<String> tombSnapshot = new LinkedHashSet<>(TOMBSTONES);
		DEFINITIONS.remove(id);
		TOMBSTONES.add(id); // placed blocks keep their identity and become inert
		if (!persist()) {
			restore(defSnapshot, tombSnapshot);
			return new MutationResult(false, "方块定义未能写入磁盘，已回滚");
		}
		return new MutationResult(true, "deleted " + id);
	}

	private static void restore(Map<String, BlockDefinition> defs, Set<String> tombs) {
		DEFINITIONS.clear();
		DEFINITIONS.putAll(defs);
		TOMBSTONES.clear();
		TOMBSTONES.addAll(tombs);
	}

	/**
	 * v0.4.0 fix: an unbound store is an ERROR, not a success. Reporting
	 * "persisted" with nowhere to persist to is exactly the kind of fake
	 * success that hides data loss; callers must bind a store (the lifecycle
	 * does it on server start, tests use {@link #attachStoreForTesting}).
	 */
	private static boolean persist() {
		BlockDefinitionStore s = store;
		if (s == null) {
			WorldForge.LOGGER.error("[WorldForge] block definition change rejected: no block store is bound");
			return false;
		}
		return s.save(new ArrayList<>(DEFINITIONS.values()));
	}

	/** true when a store is bound (diagnostics and tests) */
	public static boolean isBound() {
		return store != null;
	}

	// ---------------------------------------------------------------- runtime

	/** true when the definition still exists and is not tombstoned */
	public static boolean isLive(String id) {
		return id != null && DEFINITIONS.containsKey(id) && !TOMBSTONES.contains(id);
	}

	/**
	 * Periodic emission for one block entity. Quota-limited per tick and
	 * guarded against unbounded output (count is clamped, item must resolve).
	 */
	public static boolean runPeriodic(World world, BlockPos pos, BlockDefinition def) {
		return runPeriodic(world, pos, def, 0);
	}

	/**
	 * Periodic emission for one block entity.
	 *
	 * <p><b>Bounded fair scheduling (v0.4.0 review fix).</b> A pure per-tick
	 * counter served blocks in a fixed iteration order, so with more periodic
	 * blocks than budget the same tail could be skipped forever. Now:</p>
	 * <ul>
	 *   <li>while the SOFT budget lasts, blocks are served in encounter order;</li>
	 *   <li>a block that has been pending for more than {@link #FAIRNESS_GRACE_TICKS}
	 *       ticks beyond its own interval is boosted even after the soft budget,
	 *       so no block can starve permanently;</li>
	 *   <li>the HARD cap still bounds total work per tick, so a huge field of
	 *       periodic blocks cannot stall the server - latency is bounded, never
	 *       unbounded.</li>
	 * </ul>
	 *
	 * @param overdueTicks how long this block has been waiting past its interval
	 * @return true when the emission actually ran (the caller may then reset its timer)
	 */
	public static boolean runPeriodic(World world, BlockPos pos, BlockDefinition def, int overdueTicks) {
		if (!(world instanceof ServerWorld sw)) {
			return false;
		}
		if (periodicUsedThisTick >= PERIODIC_HARD_CAP_PER_TICK) {
			return false; // hard ceiling: the block keeps its pending tick
		}
		boolean softBudgetExhausted = periodicUsedThisTick >= PERIODIC_BUDGET_PER_TICK;
		if (softBudgetExhausted) {
			if (overdueTicks < FAIRNESS_GRACE_TICKS) {
				return false; // let the caller retry next tick
			}
			periodicBoostedThisTick++;
		}
		periodicUsedThisTick++;
		if (def.outputItemId() == null || def.outputItemId().isBlank()) {
			return true; // the turn was spent, nothing to emit
		}
		Identifier itemId = Identifier.tryParse(def.outputItemId());
		if (itemId == null) {
			return true;
		}
		Item item = Registries.ITEM.get(itemId);
		if (item == null || item == net.minecraft.item.Items.AIR) {
			return true;
		}
		int count = Math.max(1, Math.min(8, def.outputCount()));
		BlockState state = sw.getBlockState(pos);
		net.minecraft.block.Block.dropStack(sw, pos, new ItemStack(item, count));
		sw.updateListeners(pos, state, state, 3);
		return true;
	}

	/** emissions processed during the current tick (diagnostics/tests) */
	public static int periodicUsedThisTick() {
		return periodicUsedThisTick;
	}

	/** boosted emissions during the current tick (diagnostics/tests) */
	public static int periodicBoostedThisTick() {
		return periodicBoostedThisTick;
	}

	/** reset the per-tick budget (called from the server tick event) */
	public static void beginTick() {
		periodicUsedThisTick = 0;
		periodicBoostedThisTick = 0;
	}

	private BlockRuntimeManager() {
	}
}