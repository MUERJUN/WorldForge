package dev.worldforge.ops.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolPermission;
import dev.worldforge.ops.ToolRegistry;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;

import java.util.List;

/**
 * {@code list_capabilities} — what this server can actually do right now.
 *
 * <p>The model is told the TRUTH here, including the things WorldForge
 * cannot do. An honest "no" is far more useful than a confident "yes" that
 * fails three turns later.</p>
 */
public final class ListCapabilitiesTool {

	public static final String NAME = "list_capabilities";

	private ListCapabilitiesTool() {
	}

	public static ToolDescriptor descriptor() {
		JsonObject props = new JsonObject();
		JsonObject schema = JsonSchemaValidator.object(props, List.of());
		return ToolDescriptor.read(NAME,
				"列出当前服务器支持的即时操作、每项操作的约束，以及明确不能实现的功能。"
						+ "在承诺任何做不到的事情之前先调用它。",
				schema);
	}

	public static final ToolExecutor EXECUTOR = (ctx, request, sink) -> {
		JsonObject o = new JsonObject();

		// ---- what the player may do right now ----
		JsonArray readable = new JsonArray();
		JsonArray writable = new JsonArray();
		for (ToolDescriptor d : ToolRegistry.availableTo(ctx.player())) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", d.name());
			entry.addProperty("purpose", d.description());
			entry.addProperty("mutates_world", d.mutatesWorld());
			entry.addProperty("requires_confirmation", d.requiresConfirmation());
			JsonArray args = new JsonArray();
			d.argumentNames().forEach(args::add);
			entry.add("arguments", args);
			(d.permission() == ToolPermission.READ ? readable : writable).add(entry);
		}
		o.add("read_only_tools", readable);
		o.add("mutating_tools", writable);

		// ---- the honest limits ----
		JsonArray limits = new JsonArray();
		limits.add("不能给原版物品单独附加 WorldForge 行为。若玩家手里是原版物品，"
				+ "可以创建一个外观相同的虚拟物品再交给玩家，但不能偷偷替换玩家原有武器。");
		limits.add("不能改造任意原版方块。改造只对 WorldForge 动态方块有效。");
		limits.add("不能执行任意 Java 代码、Shell 或 PowerShell 命令；只有上面列出的结构化操作可用。");
		limits.add("修改方块定义会影响世界里所有同 ID 的方块实例（共享定义），不是只改一个。");
		limits.add("复杂 Mod 需求需要后续的开发流程，不能在游戏内热加载任意 Java 代码。");
		limits.add("导出为独立 Mod 时，部分能力可能无法等价导出；导出前会给出保留/转换/丢失报告。");
		o.add("known_limitations", limits);

		// ---- the concrete behaviour vocabulary ----
		JsonObject actions = new JsonObject();
		actions.add("triggers", InspectDefinitionTool.jsonArray("right_click", "hit_entity"));
		actions.add("actions", InspectDefinitionTool.jsonArray("summon_lightning", "apply_effect", "heal",
				"experience", "teleport", "explosion", "ignite"));
		actions.addProperty("cooldown_ticks_range", "0-72000（20 tick = 1 秒）");
		o.add("behaviour_vocabulary", actions);

		o.addProperty("can_modify_world_now", ctx.player() != null && ctx.player().getServer() != null
				&& ctx.player().getServer().getPermissionLevel(ctx.player().getGameProfile()) >= 2);
		o.addProperty("note", "修改世界的操作需要玩家在聊天里明确确认，工具结果才是唯一的事实来源。");

		return ToolResult.ok("已列出当前能力", o.toString());
	};
}
