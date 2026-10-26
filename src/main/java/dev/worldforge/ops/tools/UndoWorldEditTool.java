package dev.worldforge.ops.tools;

import com.google.gson.JsonObject;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolEventSink;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolPreparer;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.ops.worldedit.WorldEditExecutor;
import dev.worldforge.ops.worldedit.WorldEditPlan;

import java.util.List;

/**
 * {@code undo_world_edit} — scenario C: undo the player's most recent still
 * valid world edit. The history is per-player, per-world, bounded and
 * in-memory (a restart clears it; the docs say so honestly). The undo is
 * refused unless EVERY position still matches what the previous edit wrote —
 * it never clobbers a change somebody else made afterwards.
 */
public final class UndoWorldEditTool {

	public static final String NAME = "undo_world_edit";

	private UndoWorldEditTool() {
	}

	public static ToolDescriptor descriptor() {
		JsonObject props = new JsonObject();
		JsonObject schema = JsonSchemaValidator.object(props, List.of());
		return ToolDescriptor.write(NAME,
				"撤销你最近一次仍然有效的世界方块修改。只有当所有相关位置的当前状态仍与上次修改后的状态一致时才会执行；"
						+ "否则会拒绝并说明原因。服务器重启后撤销历史会清空。",
				schema);
	}

	public static final ToolPreparer PREPARER = (ctx, request) -> {
		if (ctx.player() == null || ctx.world() == null) {
			return ToolPreparation.none();
		}
		WorldEditPlan plan = WorldEditTool.HISTORY.latestUndoable(
				ctx.player().getUuid(), ctx.world().getRegistryKey().getValue().toString());
		if (plan == null) {
			return ToolPreparation.none();
		}
		String summary = "将撤销修改 " + plan.planId() + "：恢复 " + plan.affectedCount()
				+ " 个方块到修改前的状态。若期间目标被其它操作改动，撤销会被拒绝。";
		return ToolPreparation.of(List.of(), List.of(), summary,
				plan.planId() + ":" + plan.fingerprint(),
				ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};

	public static final ToolExecutor EXECUTOR = (ctx, request, sink) -> {
		if (ctx.player() == null || ctx.world() == null) {
			return ToolResult.fail("no_player", "没有可用的服务端上下文");
		}
		WorldEditPlan plan = WorldEditTool.HISTORY.popLatest(
				ctx.player().getUuid(), ctx.world().getRegistryKey().getValue().toString());
		if (plan == null) {
			return ToolResult.fail("nothing_to_undo",
					"没有可撤销的世界修改（历史为空、已过期，或不在当前世界中）");
		}
		// v0.8.0: server-thread marshalling (see WorldEditTool)
		var ref = new java.util.concurrent.atomic.AtomicReference<WorldEditExecutor.Outcome>();
		var latch = new java.util.concurrent.CountDownLatch(1);
		Runnable job = () -> {
			try {
				ref.set(WorldEditExecutor.undo(ctx.world(), plan, ctx.task()));
			} finally {
				latch.countDown();
			}
		};
		boolean finished = true;
		if (ctx.server().isOnThread()) {
			// the confirm path already runs on the server thread: marshalling
			// to it again would deadlock on our own latch
			job.run();
		} else {
			ctx.server().execute(job);
			ctx.task().onCancel(latch::countDown);
			try {
				finished = latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return ToolResult.fail("server_error", "撤销等待被中断");
			}
			if (!finished) {
				return ToolResult.fail("server_error", "撤销执行超时（60 秒）");
			}
		}
		var outcome = ref.get();
		JsonObject o = new JsonObject();
		o.addProperty("plan_id", plan.planId());
		o.addProperty("requested", outcome.requested());
		o.addProperty("applied", outcome.applied());
		o.addProperty("rollback_complete", outcome.rollbackComplete());
		o.addProperty("cancelled", outcome.cancelled());

		if (!outcome.fullyApplied()) {
			String reason = outcome.failureReason() == null ? "未知原因" : outcome.failureReason();
			// the undo was refused or interrupted: the executed plan goes back
			// into the history so the player can try again after fixing the cause
			WorldEditTool.HISTORY.record(plan);
			if (outcome.applied() > 0) {
				return ToolResult.degraded("undo_partial",
						"撤销中断：" + reason + "。已恢复 " + outcome.applied() + "/"
								+ outcome.requested() + " 个方块" + (outcome.rollbackComplete()
								? "（中断部分已恢复）" : "（部分恢复未完成，请检查世界）"),
						true, false);
			}
			return ToolResult.fail("undo_refused", reason);
		}
		return new ToolResult(true, "",
				"已撤销：恢复 " + outcome.applied() + " 个方块到修改前的状态（计划 " + plan.planId() + "）",
				o.toString(), List.of(), false, false, "none");
	};
}
