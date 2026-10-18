package dev.worldforge.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.worldforge.block.BlockCatalog;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.tool.WorldForgeBlockTools;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * {@code /wf block ...} - the virtual BLOCK command tree (v0.4.0).
 *
 * <p>Kept in its own class so the item tree stays readable. Every mutating
 * sub-command requires OP2 (the same threshold as the item commands) and routes
 * through {@link WorldForgeBlockTools}, the single block mutation entry point
 * shared with Studio and the AI agent - there is no second business system.</p>
 */
public final class BlockCommand {

	public static LiteralArgumentBuilder<ServerCommandSource> build() {
		return CommandManager.literal("block")
				.executes(ctx -> help(ctx.getSource()))
				.then(CommandManager.literal("help").executes(ctx -> help(ctx.getSource())))
				.then(CommandManager.literal("list").executes(ctx -> list(ctx.getSource())))
				.then(CommandManager.literal("info")
						.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
								.executes(ctx -> info(ctx.getSource(), idOf(ctx)))))
				.then(createCommand())
				.then(CommandManager.literal("delete")
						.requires(s -> s.hasPermissionLevel(2))
						.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
								.executes(ctx -> delete(ctx.getSource(), idOf(ctx)))))
				.then(CommandManager.literal("give")
						.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
								.executes(ctx -> give(ctx.getSource(), idOf(ctx), 1))
								.then(CommandManager.argument("count", IntegerArgumentType.integer(1, 64))
										.executes(ctx -> give(ctx.getSource(), idOf(ctx),
												IntegerArgumentType.getInteger(ctx, "count"))))));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> createCommand() {
		// /wf block create <id> <name> <appearance> [category] [interval] [output]
		var outputArg = CommandManager.argument("output_item", IdentifierArgumentType.identifier())
				.executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "category"),
						IntegerArgumentType.getInteger(ctx, "interval"),
						IdentifierArgumentType.getIdentifier(ctx, "output_item").toString()));

		var intervalArg = CommandManager.argument("interval", IntegerArgumentType.integer(20, 72000))
				.executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "category"),
						IntegerArgumentType.getInteger(ctx, "interval"), null))
				.then(outputArg);

		var categoryArg = CommandManager.argument("category", StringArgumentType.word())
				.executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "category"), 0, null))
				.then(intervalArg);

		var appearanceArg = CommandManager.argument("appearance", StringArgumentType.word())
				.executes(ctx -> create(ctx, "decoration", 0, null))
				.then(categoryArg);

		var nameArg = CommandManager.argument("name", StringArgumentType.string())
				.then(appearanceArg);

		return CommandManager.literal("create")
				.requires(s -> s.hasPermissionLevel(2))
				.then(CommandManager.argument("id", IdentifierArgumentType.identifier())
						.then(nameArg));
	}

	private static String idOf(CommandContext<ServerCommandSource> ctx) {
		return IdentifierArgumentType.getIdentifier(ctx, "id").toString();
	}

	// ---------------------------------------------------------------- handlers

	private static int help(ServerCommandSource src) {
		src.sendFeedback(() -> Text.literal("§b§lWorldForge 方块命令 §r§7(v0.4.0)"), false);
		src.sendFeedback(() -> Text.literal("§e/wf block list §7- 列出全部虚拟方块"), false);
		src.sendFeedback(() -> Text.literal("§e/wf block info <id> §7- 查看详情与运行时限制"), false);
		src.sendFeedback(() -> Text.literal("§e/wf block create <id> <名称> <外观> [类型] [间隔] [产出] §7(OP2)"), false);
		src.sendFeedback(() -> Text.literal("§7  外观：" + String.join("/", BlockCatalog.supportedKeys())), false);
		src.sendFeedback(() -> Text.literal("§7  类型：decoration / interactive / periodic（periodic 需间隔≥20）"), false);
		src.sendFeedback(() -> Text.literal("§e/wf block give <id> [数量] §7- 获得可放置的方块物品"), false);
		src.sendFeedback(() -> Text.literal("§e/wf block delete <id> §7- 删除定义（已放置方块安全失效）(OP2)"), false);
		src.sendFeedback(() -> Text.literal("§7流程：create → give → 右键地面放置 → 右键方块触发行为"), false);
		return 1;
	}

	private static int list(ServerCommandSource src) {
		List<BlockDefinition> defs = WorldForgeBlockTools.listBlocks();
		if (defs.isEmpty()) {
			src.sendFeedback(() -> Text.literal(
					"§7当前没有虚拟方块。用 §e/wf block create <id> <名称> <外观>§7 创建。"), false);
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§b虚拟方块：" + defs.size() + " 个"), false);
		for (BlockDefinition d : defs) {
			src.sendFeedback(() -> Text.literal("§f" + WorldForgeBlockTools.describe(d)), false);
		}
		return defs.size();
	}

	private static int info(ServerCommandSource src, String id) {
		BlockDefinition d = WorldForgeBlockTools.getBlock(id);
		if (d == null) {
			src.sendError(Text.literal("§c没有此方块定义: " + id
					+ (BlockRuntimeManager.tombstones().contains(id) ? "（已被删除）" : "")));
			return 0;
		}
		src.sendFeedback(() -> Text.literal("§b" + d.name() + " §7(" + d.id() + ")"), false);
		src.sendFeedback(() -> Text.literal("§7类型：" + d.category().name().toLowerCase(Locale.ROOT)
				+ " · 外观：" + d.appearance() + " · 硬度：" + d.hardness()), false);
		if (d.periodic()) {
			src.sendFeedback(() -> Text.literal("§7周期：每 " + d.tickIntervalTicks() + " tick → "
					+ (d.outputItemId() == null ? "无产出"
							: d.outputItemId() + " x" + d.outputCount())), false);
		}
		if (d.behaviors().isEmpty()) {
			src.sendFeedback(() -> Text.literal("§7行为：无"), false);
		}
		for (var b : d.behaviors()) {
			src.sendFeedback(() -> Text.literal("§7行为：" + b.trigger() + " → "
					+ String.join(",", b.actions()) + " 冷却 " + b.cooldownTicks() + "t"), false);
		}
		src.sendFeedback(() -> Text.literal("§8运行时限制："), false);
		for (String limit : BlockDefinition.RUNTIME_LIMITATIONS) {
			src.sendFeedback(() -> Text.literal("§8· " + limit), false);
		}
		return 1;
	}

	private static int create(CommandContext<ServerCommandSource> ctx, String category, int interval,
			String outputItem) {
		ServerCommandSource src = ctx.getSource();
		String id = idOf(ctx);
		String name = StringArgumentType.getString(ctx, "name");
		String appearance = StringArgumentType.getString(ctx, "appearance");

		BlockDefinition.Category cat;
		try {
			cat = BlockDefinition.Category.valueOf(category.toUpperCase(Locale.ROOT));
		} catch (Exception e) {
			src.sendError(Text.literal("§c未知类型: " + category
					+ "（可用 decoration / interactive / periodic）"));
			return 0;
		}
		if (cat == BlockDefinition.Category.PERIODIC && interval <= 0) {
			src.sendError(Text.literal("§c周期方块必须指定间隔（≥20 tick = 1 秒）"));
			return 0;
		}
		BlockDefinition def = BlockDefinition.create(id, name, "", appearance, cat, 2.0,
				List.of(), interval <= 0 ? 200 : interval, outputItem, 1);
		report(src, WorldForgeBlockTools.createBlock(src.getServer(), def));
		return 1;
	}

	private static int delete(ServerCommandSource src, String id) {
		report(src, WorldForgeBlockTools.deleteBlock(src.getServer(), id));
		return 1;
	}

	private static int give(ServerCommandSource src, String id, int count) {
		var player = src.getPlayer();
		if (player == null) {
			src.sendError(Text.literal("§c只有玩家可以获得方块物品"));
			return 0;
		}
		report(src, WorldForgeBlockTools.give(player, id, count));
		return 1;
	}

	/** shared three-phase reporting: persisted / applied / sync stay distinguishable */
	private static void report(ServerCommandSource src, WorldForgeBlockTools.BlockToolResult r) {
		if (r.ok()) {
			String suffix = r.sync() == WorldForgeBlockTools.SyncStatus.PARTIAL
					? " §e（部分客户端同步失败，稍后会自动重发）" : "";
			src.sendFeedback(() -> Text.literal("§a" + r.message() + suffix), true);
		} else if (r.persisted()) {
			src.sendError(Text.literal("§e已写入但应用失败：" + r.message() + "（可重试同步）"));
		} else {
			src.sendError(Text.literal("§c" + r.message()));
		}
	}

	private BlockCommand() {
	}
}