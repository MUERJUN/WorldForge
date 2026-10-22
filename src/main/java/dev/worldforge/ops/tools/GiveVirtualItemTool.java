package dev.worldforge.ops.tools;

import com.google.gson.JsonObject;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolPreparer;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.registry.WorldForgeItems;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.tool.WorldForgeTools;

import java.util.List;

/**
 * {@code give_virtual_item} — hands a created virtual item to the player.
 *
 * <p>Giving an item does not change any global definition, so it needs no
 * definition-write authority (it matches the authority of {@code /wf give}).
 * It is still a WRITE tool because it changes what the player holds.</p>
 */
public final class GiveVirtualItemTool {

	public static final String NAME = "give_virtual_item";

	private GiveVirtualItemTool() {
	}

	public static ToolDescriptor descriptor() {
		JsonObject props = new JsonObject();
		props.add("id", JsonSchemaValidator.prop("string", "要给予的虚拟物品 id，例如 worldforge:thunder_sword"));
		props.add("count", JsonSchemaValidator.prop("integer", "给予的数量", 1, 64));
		JsonObject schema = JsonSchemaValidator.object(props, List.of("id"));
		return ToolDescriptor.of(NAME,
				"把一个已创建的 WorldForge 虚拟物品交给玩家自己（放入背包；背包满时掉落在脚下）。",
				schema, dev.worldforge.ops.ToolPermission.WRITE, true, true);
	}

	public static final ToolExecutor EXECUTOR = (ctx, request, sink) -> {
		if (ctx.player() == null) {
			return ToolResult.fail("no_player", "没有可用的玩家上下文");
		}
		String rawId = ToolDefinitions.str(request, "id");
		if (rawId == null) {
			return ToolResult.fail("invalid_arguments", "缺少 id");
		}
		String id = WorldForgeTools.normalizeId(rawId);
		ItemDefinition def = WorldForgeRuntimeManager.get(id);
		if (def == null) {
			return ToolResult.fail("not_found", "物品定义不存在: " + id);
		}
		int count = Math.max(1, Math.min(64, request.argumentInt("count", 1)));
		var stack = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, id, count);
		if (stack == null) {
			return ToolResult.fail("not_found", "无法创建该虚拟物品（定义可能已失效）: " + id);
		}
		boolean dropped = false;
		boolean lost = false;
		if (!ctx.player().getInventory().insertStack(stack)) {
			// v0.7.1: dropItem returns null when the item could NOT be
			// dropped — the old code ignored that and reported success while
			// the item was simply destroyed.
			lost = ctx.player().dropItem(stack, false) == null;
			dropped = !lost;
		}
		if (!ctx.scope().mayWriteItem(id)) {
			return ToolResult.fail("out_of_scope", "这个物品不在你确认的范围内：" + id);
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("count", count);
		o.addProperty("dropped_at_feet", dropped);
		if (lost) {
			o.addProperty("lost", true);
			return ToolResult.fail("give_failed",
					"背包已满，而且物品无法掉落在地上——物品没有送达。", o.toString());
		}
		return new ToolResult(true, "",
				dropped ? "背包已满，" + def.name() + " x" + count + " 已掉落在脚下"
						: "已获得 " + def.name() + " x" + count,
				o.toString(), List.of(id), true, true, "none");
	};

	/** Prepare: the item must exist, and the target is that one item id. */
	public static final ToolPreparer PREPARER = (ctx, request) -> {
		String rawId = ToolDefinitions.str(request, "id");
		if (rawId == null) {
			return ToolPreparation.none();
		}
		String id = WorldForgeTools.normalizeId(rawId);
		ItemDefinition def = WorldForgeRuntimeManager.get(id);
		if (def == null) {
			return ToolPreparation.none();
		}
		int count = Math.max(1, Math.min(64, request.argumentInt("count", 1)));
		return ToolPreparation.of(List.of(id), List.of(),
				"将把「" + def.name() + "」(" + id + ") x" + count + " 交给你自己。", "",
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};
}
