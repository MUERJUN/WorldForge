package dev.worldforge.ops.worldedit;

import dev.worldforge.ops.ToolTaskHandle;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.8.0: executes a VALIDATED plan on the server thread.
 *
 * <p>Execution contract:</p>
 * <ul>
 *   <li>every position is re-checked against the plan's original state right
 *       before its own write — a mismatch aborts the WHOLE plan before any
 *       write (the pre-check runs once for the entire plan up front);</li>
 *   <li>writes go through {@code world.setBlockState(pos, state, NOTIFY_ALL)}
 *       and the written state is read back and verified;</li>
 *   <li>the cancel token is checked between positions — a cancel stops the
 *       plan, rolls back the already-written positions, and reports
 *       honestly;</li>
 *   <li>a write/verify failure stops the plan, rolls back what was written
 *       (best effort) and reports the real numbers — never a fake atomic
 *       success.</li>
 * </ul>
 */
public final class WorldEditExecutor {

	private WorldEditExecutor() {
	}

	/** The honest outcome of an execution attempt. */
	public record Outcome(
			int requested,
			int applied,
			int rolledBack,
			boolean rollbackComplete,
			boolean cancelled,
			String failureReason) {

		public boolean fullyApplied() {
			return failureReason == null && applied == requested && !cancelled;
		}
	}

	/**
	 * Executes the plan. The caller has already confirmed it; the executor
	 * still re-validates EVERYTHING (state, bounds, targets) and refuses
	 * before the first write when anything drifted.
	 */
	public static Outcome execute(ServerWorld world, WorldEditPlan plan, ToolTaskHandle cancel) {
		// 0) the world must be the one the plan was built for
		if (!world.getRegistryKey().getValue().toString().equals(plan.worldId())) {
			return new Outcome(plan.affectedCount(), 0, 0, true, false,
					"世界不匹配: 计划属于 " + plan.worldId());
		}
		if (plan.isExpiredAt(java.time.Instant.now())) {
			return new Outcome(plan.affectedCount(), 0, 0, true, false, "计划已过期，请重新发起");
		}

		// 1) resolve every entry to a live state — one bad entry refuses all
		Map<BlockPos, net.minecraft.block.BlockState> originals = new LinkedHashMap<>();
		Map<BlockPos, net.minecraft.block.BlockState> targets = new LinkedHashMap<>();
		try {
			for (BlockPos pos : plan.positions()) {
				originals.put(pos, BlockTargetRules.resolve(plan.originalStates().get(pos)));
				targets.put(pos, BlockTargetRules.resolve(plan.newStates().get(pos)));
			}
		} catch (IllegalArgumentException e) {
			return new Outcome(plan.affectedCount(), 0, 0, true, false, e.getMessage());
		}

		// 2) the whole-plan pre-check: bounds, chunk, BlockEntity, forbidden,
		//    and the target must STILL match the original the player approved
		for (BlockPos pos : plan.positions()) {
			if (!WorldEditValidator.inWorldBounds(pos, world.getBottomY(), world.getBottomY() + world.getHeight() - 1)) {
				return new Outcome(plan.affectedCount(), 0, 0, true, false,
						"目标越出世界边界: " + pos.toShortString());
			}
			if (!world.isChunkLoaded(pos)) {
				return new Outcome(plan.affectedCount(), 0, 0, true, false,
						"目标所在区块未加载，不会为编辑强制加载区块: " + pos.toShortString());
			}
			net.minecraft.block.BlockState current = world.getBlockState(pos);
			if (BlockTargetRules.holdsBlockEntity(current)
					|| world.getBlockEntity(pos) != null) {
				return new Outcome(plan.affectedCount(), 0, 0, true, false,
						"目标位置包含方块实体（箱子/熔炉/其它模组数据），第一阶段拒绝修改: "
								+ pos.toShortString());
			}
			if (BlockTargetRules.isForbiddenId(BlockTargetRules.describe(current).blockId())) {
				return new Outcome(plan.affectedCount(), 0, 0, true, false,
						"目标包含禁止修改的世界控制方块: " + pos.toShortString());
			}
			if (!current.equals(originals.get(pos))) {
				return new Outcome(plan.affectedCount(), 0, 0, true, false,
						"目标状态已变化（" + pos.toShortString() + " 现在是 "
								+ BlockTargetRules.describe(current).toText() + "，计划基于 "
								+ BlockTargetRules.describe(originals.get(pos)).toText()
								+ "），未写入世界");
			}
		}

		// 3) execute with the cancel check between positions
		List<BlockPos> written = new ArrayList<>();
		for (BlockPos pos : plan.positions()) {
			if (cancel != null && cancel.isCancelled()) {
				Outcome rolled = rollback(world, written, originals);
				return new Outcome(plan.affectedCount(), written.size(),
						rolled.rolledBack(), rolled.rollbackComplete(), true,
						"任务已取消，已回滚已写入的方块");
			}
			net.minecraft.block.BlockState target = targets.get(pos);
			try {
				world.setBlockState(pos, target, net.minecraft.block.Block.NOTIFY_ALL);
			} catch (Exception e) {
				Outcome rolled = rollback(world, written, originals);
				return new Outcome(plan.affectedCount(), written.size(),
						rolled.rolledBack(), rolled.rollbackComplete(), false,
						"写入失败于 " + pos.toShortString() + ": " + e.getMessage());
			}
			// verify the write really landed
			if (!world.getBlockState(pos).equals(target)) {
				Outcome rolled = rollback(world, written, originals);
				return new Outcome(plan.affectedCount(), written.size(),
						rolled.rolledBack(), rolled.rollbackComplete(), false,
						"写入后读回校验失败于 " + pos.toShortString());
			}
			written.add(pos);
		}
		return new Outcome(plan.affectedCount(), written.size(), 0, true, false, null);
	}

	/**
	 * Undoes a previously APPLIED plan: every position must currently match
	 * the plan's TARGET state, otherwise the undo is refused (it must never
	 * clobber a change somebody else made afterwards).
	 */
	public static Outcome undo(ServerWorld world, WorldEditPlan plan, ToolTaskHandle cancel) {
		if (!world.getRegistryKey().getValue().toString().equals(plan.worldId())) {
			return new Outcome(plan.affectedCount(), 0, 0, true, false,
					"世界不匹配: 计划属于 " + plan.worldId());
		}
		Map<BlockPos, net.minecraft.block.BlockState> originals = new LinkedHashMap<>();
		Map<BlockPos, net.minecraft.block.BlockState> targets = new LinkedHashMap<>();
		try {
			for (BlockPos pos : plan.positions()) {
				originals.put(pos, BlockTargetRules.resolve(plan.originalStates().get(pos)));
				targets.put(pos, BlockTargetRules.resolve(plan.newStates().get(pos)));
			}
		} catch (IllegalArgumentException e) {
			return new Outcome(plan.affectedCount(), 0, 0, true, false, e.getMessage());
		}
		// whole-plan pre-check for the undo
		for (BlockPos pos : plan.positions()) {
			if (!world.isChunkLoaded(pos)) {
				return new Outcome(plan.affectedCount(), 0, 0, true, false,
						"撤销目标所在区块未加载: " + pos.toShortString());
			}
			net.minecraft.block.BlockState current = world.getBlockState(pos);
			if (world.getBlockEntity(pos) != null) {
				return new Outcome(plan.affectedCount(), 0, 0, true, false,
						"撤销目标位置现在包含方块实体，拒绝覆盖: " + pos.toShortString());
			}
			if (!current.equals(targets.get(pos))) {
				return new Outcome(plan.affectedCount(), 0, 0, true, false,
						"目标已被其它操作修改（" + pos.toShortString() + " 现在是 "
								+ BlockTargetRules.describe(current).toText()
								+ "），无法安全撤销");
			}
		}
		List<BlockPos> written = new ArrayList<>();
		for (BlockPos pos : plan.positions()) {
			if (cancel != null && cancel.isCancelled()) {
				Outcome rolled = rollbackTo(world, written, targets);
				return new Outcome(plan.affectedCount(), written.size(),
						rolled.rolledBack(), rolled.rollbackComplete(), true,
						"撤销任务已取消，已恢复 " + rolled.rolledBack() + " 个方块");
			}
			net.minecraft.block.BlockState original = originals.get(pos);
			try {
				world.setBlockState(pos, original, net.minecraft.block.Block.NOTIFY_ALL);
			} catch (Exception e) {
				Outcome rolled = rollbackTo(world, written, targets);
				return new Outcome(plan.affectedCount(), written.size(),
						rolled.rolledBack(), rolled.rollbackComplete(), false,
						"撤销写入失败于 " + pos.toShortString() + ": " + e.getMessage());
			}
			if (!world.getBlockState(pos).equals(original)) {
				Outcome rolled = rollbackTo(world, written, targets);
				return new Outcome(plan.affectedCount(), written.size(),
						rolled.rolledBack(), rolled.rollbackComplete(), false,
						"撤销读回校验失败于 " + pos.toShortString());
			}
			written.add(pos);
		}
		return new Outcome(plan.affectedCount(), written.size(), 0, true, false, null);
	}

	/** Restores the ORIGINAL states for the positions written so far. */
	private static Outcome rollback(ServerWorld world, List<BlockPos> written,
			Map<BlockPos, net.minecraft.block.BlockState> originals) {
		int ok = 0;
		boolean complete = true;
		for (BlockPos pos : written) {
			try {
				world.setBlockState(pos, originals.get(pos), net.minecraft.block.Block.NOTIFY_ALL);
				if (world.getBlockState(pos).equals(originals.get(pos))) {
					ok++;
				} else {
					complete = false;
				}
			} catch (Exception e) {
				complete = false;
			}
		}
		return new Outcome(written.size(), written.size(), ok, complete, false,
				complete ? null : "回滚未完全成功，请检查世界状态");
	}

	/** Restores the TARGET states (used when an undo aborts mid-way). */
	private static Outcome rollbackTo(ServerWorld world, List<BlockPos> written,
			Map<BlockPos, net.minecraft.block.BlockState> targets) {
		int ok = 0;
		boolean complete = true;
		for (BlockPos pos : written) {
			try {
				world.setBlockState(pos, targets.get(pos), net.minecraft.block.Block.NOTIFY_ALL);
				if (world.getBlockState(pos).equals(targets.get(pos))) {
					ok++;
				} else {
					complete = false;
				}
			} catch (Exception e) {
				complete = false;
			}
		}
		return new Outcome(written.size(), written.size(), ok, complete, false,
				complete ? null : "撤销中断后的恢复未完全成功，请检查世界状态");
	}
}
