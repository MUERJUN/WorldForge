package dev.worldforge.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldforge.definition.AppearanceSlots;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.tool.WorldForgeTools;
import dev.worldforge.registry.WorldForgeItems;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Map;

/**
 * Developer commands (spec section 7). Mutating subcommands require
 * permission level 2 (OP); give/list/info are usable by any player so a
 * single-player can test without opening cheats to full level.
 *
 * <pre>
 * /wf help | list | info &lt;id&gt; | create &lt;id&gt; &lt;name&gt; | give &lt;id&gt; [count]
 * /wf setdamage &lt;id&gt; &lt;value&gt; | delete &lt;id&gt; | reload | demo
 * </pre>
 */
public final class WorldForgeCommand {

	private static IdentifierArgumentType id() {
		return IdentifierArgumentType.identifier();
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				CommandManager.literal("wf")
						.executes(ctx -> help(ctx.getSource()))
						.then(CommandManager.literal("help").executes(ctx -> help(ctx.getSource())))
						.then(CommandManager.literal("list").executes(ctx -> list(ctx.getSource())))
						.then(CommandManager.literal("info")
								.then(CommandManager.argument("id", id())
										.executes(ctx -> info(ctx.getSource(), cid(ctx)))))
						.then(CommandManager.literal("create")
								.requires(s -> s.hasPermissionLevel(2))
								.then(CommandManager.argument("id", id())
										.then(CommandManager.argument("name", StringArgumentType.string())
												.executes(ctx -> create(ctx.getSource(), cid(ctx),
														StringArgumentType.getString(ctx, "name"))))))
						.then(CommandManager.literal("give")
								.then(CommandManager.argument("id", id())
										.executes(ctx -> give(ctx.getSource(), cid(ctx), 1))
										.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 99))
												.executes(ctx -> give(ctx.getSource(), cid(ctx),
														IntegerArgumentType.getInteger(ctx, "count"))))))
						.then(CommandManager.literal("setdamage")
								.requires(s -> s.hasPermissionLevel(2))
								.then(CommandManager.argument("id", id())
										.then(CommandManager.argument("value", IntegerArgumentType.integer(0, 1000))
												.executes(ctx -> setDamage(ctx.getSource(), cid(ctx),
														IntegerArgumentType.getInteger(ctx, "value"))))))
						.then(CommandManager.literal("delete")
								.requires(s -> s.hasPermissionLevel(2))
								.then(CommandManager.argument("id", id())
										.executes(ctx -> delete(ctx.getSource(), cid(ctx)))))
						.then(CommandManager.literal("reload")
								.requires(s -> s.hasPermissionLevel(2))
								.executes(ctx -> reload(ctx.getSource())))
						.then(CommandManager.literal("demo")
								.requires(s -> s.hasPermissionLevel(2))
								.executes(ctx -> demo(ctx.getSource())))));
	}

	private static String cid(CommandContext<ServerCommandSource> ctx) {
		return IdentifierArgumentType.getIdentifier(ctx, "id").toString();
	}

	// ---------- handlers ----------

	private static int help(ServerCommandSource src) {
		src.sendFeedback(() -> Text.literal("§b§lWorldForge §r§7- 运行时动态物品引擎 (MVP 0.1)"), false);
		src.sendFeedback(() -> Text.literal("§e/wf list §7- 列出全部虚拟物品"), false);
		src.sendFeedback(() -> Text.literal("§e/wf info <id> §7- 查看定义详情"), false);
		src.sendFeedback(() -> Text.literal("§e/wf create <id> <name> §7- 运行时创建物品 (OP2)"), false);
		src.sendFeedback(() -> Text.literal("§e/wf give <id> [数量] §7- 获取物品"), false);
		src.sendFeedback(() -> Text.literal("§e/wf setdamage <id> <n> §7- 热修改伤害 (OP2)"), false);
		src.sendFeedback(() -> Text.literal("§e/wf delete <id> §7- 删除定义 (OP2)"), false);
		src.sendFeedback(() -> Text.literal("§e/wf reload §7- 从世界存档重新加载 (OP2)"), false);
		src.sendFeedback(() -> Text.literal("§e/wf demo §7- 一键创建 10 种测试物品 (OP2)"), false);
		return 1;
	}

	private static int list(ServerCommandSource src) {
		List<ItemDefinition> defs = WorldForgeRuntimeManager.list();
		src.sendFeedback(() -> Text.literal("§b虚拟物品（" + defs.size() + "）："), false);
		for (ItemDefinition d : defs) {
			src.sendFeedback(() -> Text.literal("  §f" + d.id() + " §7- " + d.name()
					+ " §edmg=" + trim(d.damage()) + " dur=" + d.maxDurability()), false);
		}
		return defs.size();
	}

	private static int info(ServerCommandSource src, String id) {
		ItemDefinition d = WorldForgeRuntimeManager.get(id);
		if (d == null) {
			src.sendError(Text.literal("没有此物品: " + id));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§b" + d.id()).formatted(Formatting.BOLD), false);
		src.sendFeedback(() -> Text.literal("  名称: §f" + d.name()), false);
		src.sendFeedback(() -> Text.literal("  描述: §f" + (d.description().isBlank() ? "-" : d.description())), false);
		src.sendFeedback(() -> Text.literal("  贴图: §f" + d.texture().source() + " -> " + d.texture().resource()
				+ " §7(外观槽位 " + d.appearanceSlotIndex() + ")"), false);
		src.sendFeedback(() -> Text.literal("  属性: §f伤害 " + trim(d.damage()) + " | 耐久 " + d.maxDurability()
				+ " | 攻速 " + trim(d.attackSpeed())), false);
		for (BehaviorDefinition b : d.behaviors()) {
			src.sendFeedback(() -> Text.literal("  行为: §e" + b.trigger() + " -> §a"
					+ String.join(",", b.actions()) + " §7冷却 " + b.cooldownTicks() + "t " + b.parameters()), false);
		}
		return 1;
	}

	private static int create(ServerCommandSource src, String id, String name) {
		String res = id.contains(":") ? id : "worldforge:" + id;
		ItemDefinition def = new ItemDefinition(
				ItemDefinition.CURRENT_VERSION, res, name, "由 /wf create 创建",
				TextureSource.vanilla(AppearanceSlots.guessVanillaTexture(res + " " + name)),
				Map.of("damage", 4.0, "max_durability", 100.0),
				List.of());
		var r = WorldForgeTools.createItem(src.getServer(), def);
		if (r.ok()) {
			src.sendFeedback(() -> Text.literal("§a" + r.message()), false);
			return 1;
		}
		src.sendError(Text.literal("§c" + r.message()));
		return 0;
	}

	private static int give(ServerCommandSource src, String id, int count) {
		String res = id.contains(":") ? id : "worldforge:" + id;
		ItemStack stack = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, res, count);
		if (stack == null) {
			src.sendError(Text.literal("§c没有此物品: " + res + "（可先 /wf create 或 /wf demo）"));
			return 0;
		}
		var player = src.getPlayer();
		if (player != null) {
			player.giveItemStack(stack);
			src.sendFeedback(() -> Text.literal("§a已获得 " + stack.getName().getString() + " x" + stack.getCount()), false);
			return 1;
		}
		src.sendError(Text.literal("give 需要玩家执行者"));
		return 0;
	}

	private static int setDamage(ServerCommandSource src, String id, int value) {
		String res = id.contains(":") ? id : "worldforge:" + id;
		ItemDefinition d = WorldForgeRuntimeManager.get(res);
		if (d == null) {
			src.sendError(Text.literal("§c没有此物品: " + res));
			return 0;
		}
		var r = WorldForgeTools.setAttribute(src.getServer(), res, "damage", value);
		if (r.ok()) {
			src.sendFeedback(() -> Text.literal("§a" + res + " 伤害已热改为 " + value
					+ "（玩家背包中的该物品立即生效，无需重新获取）"), false);
			return 1;
		}
		src.sendError(Text.literal("§c保存失败，修改已回滚: " + r.message()));
		return 0;
	}

	private static int delete(ServerCommandSource src, String id) {
		String res = id.contains(":") ? id : "worldforge:" + id;
		var r = WorldForgeTools.deleteItem(src.getServer(), res);
		if (r.ok()) {
			src.sendFeedback(() -> Text.literal("§e" + r.message()), false);
			return 1;
		}
		src.sendError(Text.literal("§c" + r.message()));
		return 0;
	}

	private static int reload(ServerCommandSource src) {
		var r = WorldForgeTools.reload(src.getServer());
		if (r.ok()) {
			src.sendFeedback(() -> Text.literal("§a" + r.message()), false);
			return 1;
		}
		src.sendError(Text.literal("§c" + r.message()));
		return 0;
	}

	private static int demo(ServerCommandSource src) {
		var rr = DemoDefinitions.createAllViaTools(src.getServer());
		if (!rr.ok()) {
			src.sendError(Text.literal("§cdemo 失败: " + rr.message()));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§a演示物品就绪: " + rr.message()
				+ "。用 /wf give <id> 获取，/wf list 查看"), false);
		return 1;
	}

	private static String trim(double d) {
		return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
	}

	private WorldForgeCommand() {
	}
}
