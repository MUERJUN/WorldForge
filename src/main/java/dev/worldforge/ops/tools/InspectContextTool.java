package dev.worldforge.ops.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolEventSink;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * {@code inspect_context} — a bounded, server-authoritative snapshot of the
 * player's immediate situation.
 *
 * <p>Deliberately LIMITED: the player's world/dimension, position, what they
 * are looking at, what they hold, and what the server currently allows. It
 * never scans the player's disk, never reads other players' data, and never
 * walks the world beyond the single block the player is aiming at.</p>
 */
public final class InspectContextTool {

	public static final String NAME = "inspect_context";

	private InspectContextTool() {
	}

	public static ToolDescriptor descriptor() {
		JsonObject props = new JsonObject();
		JsonObject schema = JsonSchemaValidator.object(props, List.of());
		return ToolDescriptor.read(NAME,
				"读取玩家当前的游戏上下文：所在世界与维度、位置、视线目标的方块与坐标、主手物品、"
						+ "以及当前服务器允许的 WorldForge 能力。修改任何东西之前应先调用它。",
				schema);
	}

	public static final ToolExecutor EXECUTOR = (ctx, request, sink) -> {
		if (ctx.player() == null) {
			return ToolResult.fail("no_player", "没有可用的玩家上下文");
		}
		var player = ctx.player();
		JsonObject o = new JsonObject();

		o.addProperty("player", player.getName().getString());
		o.addProperty("world", ctx.worldId());
		o.addProperty("dimension", ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
		o.addProperty("position", player.getBlockPos().getX() + ", "
				+ player.getBlockPos().getY() + ", " + player.getBlockPos().getZ());
		o.addProperty("has_permission_to_modify", hasModifyPermission(player));

		// ---- main hand item ----
		ItemStack main = player.getMainHandStack();
		JsonObject hand = new JsonObject();
		hand.addProperty("empty", main.isEmpty());
		if (!main.isEmpty()) {
			hand.addProperty("item_id", Registries.ITEM.getId(main.getItem()).toString());
			hand.addProperty("display_name", main.getName().getString());
			hand.addProperty("count", main.getCount());
			var vid = DynamicItem.virtualIdOf(main);
			if (vid != null && vid.id() != null && !vid.id().isBlank()) {
				hand.addProperty("worldforge_virtual_id", vid.id());
				ItemDefinition def = WorldForgeRuntimeManager.get(vid.id());
				hand.addProperty("worldforge_definition_exists", def != null);
				if (def != null) {
					hand.addProperty("name", def.name());
					hand.addProperty("behaviors", def.behaviors().size());
				}
			} else {
				hand.addProperty("worldforge_virtual_id", "");
				hand.addProperty("worldforge_definition_exists", false);
				hand.addProperty("note", "这是原版物品，WorldForge 目前不能给原版物品附加行为；"
						+ "可以创建外观相同的 WorldForge 虚拟物品再交给玩家。");
			}
		}
		o.add("main_hand", hand);

		// ---- targeted block ----
		JsonObject target = new JsonObject();
		// tickDelta 1.0 = the CURRENT frame: with 0.0 the ray would use the
		// interpolated (previous) yaw/pitch and could miss what the player sees
		HitResult hit = player.raycast(6.0, 1.0f, false);
		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockHitResult bhr = (BlockHitResult) hit;
			BlockPos pos = bhr.getBlockPos();
			BlockState state = ctx.world() == null ? null : ctx.world().getBlockState(pos);
			target.addProperty("hit", true);
			target.addProperty("block_id", state == null ? "?" : Registries.BLOCK.getId(state.getBlock()).toString());
			target.addProperty("x", pos.getX());
			target.addProperty("y", pos.getY());
			target.addProperty("z", pos.getZ());
			target.addProperty("face", bhr.getSide().getName());
			String virtualBlockId = null;
			if (ctx.world() != null) {
				var be = ctx.world().getBlockEntity(pos);
				virtualBlockId = be instanceof DynamicBlockEntity dbe ? dbe.virtualId() : null;
			}
			if (virtualBlockId != null && !virtualBlockId.isBlank()) {
				target.addProperty("worldforge_virtual_id", virtualBlockId);
				BlockDefinition def = BlockRuntimeManager.get(virtualBlockId);
				target.addProperty("worldforge_definition_exists", def != null);
				if (def != null) {
					target.addProperty("name", def.name());
					target.addProperty("category", def.category().name());
					target.addProperty("appearance", def.appearance());
				}
			} else {
				target.addProperty("worldforge_virtual_id", "");
				target.addProperty("worldforge_definition_exists", false);
				target.addProperty("note", "这是原版方块，WorldForge 目前不能改造原版方块；"
						+ "可以改用 WorldForge 动态方块实现同样的效果。");
			}
		} else {
			target.addProperty("hit", false);
		}
		o.add("target_block", target);

		// ---- what this server currently allows ----
		JsonArray items = new JsonArray();
		for (ItemDefinition d : WorldForgeRuntimeManager.list()) {
			items.add(d.id());
		}
		o.add("available_item_ids", items);
		JsonArray blocks = new JsonArray();
		for (BlockDefinition d : BlockRuntimeManager.list()) {
			blocks.add(d.id());
		}
		o.add("available_block_ids", blocks);

		return ToolResult.ok("已读取当前游戏上下文", o.toString());
	};

	private static boolean hasModifyPermission(net.minecraft.server.network.ServerPlayerEntity player) {
		return player.getServer() != null
				&& player.getServer().getPermissionLevel(player.getGameProfile()) >= 2;
	}
}
