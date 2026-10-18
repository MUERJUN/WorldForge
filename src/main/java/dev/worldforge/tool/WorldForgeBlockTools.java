package dev.worldforge.tool;

import dev.worldforge.WorldForge;
import dev.worldforge.block.BlockBehaviorEngine;
import dev.worldforge.block.BlockCatalog;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.component.VirtualBlockId;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.registry.WorldForgeBlocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The ONE mutation entry point for virtual blocks (v0.4.0), mirroring
 * {@link WorldForgeTools} for items.
 *
 * <p>Commands, Studio and AgentBridge all call these methods, so there is no
 * second business system and no path that can bypass validation, persistence or
 * the permission model. Every write:</p>
 * <ol>
 *   <li>validates the id, capability category, appearance and behaviour
 *       parameters server-side;</li>
 *   <li>executes through {@link BlockRuntimeManager}, which persists first and
 *       rolls back definitions AND tombstones on failure;</li>
 *   <li>reports the three phases separately ({@code persisted} / {@code applied}
 *       / {@code sync}) so a caller can tell "written to disk but not shown to
 *       clients" apart from "nothing happened";</li>
 *   <li>never claims success for an unpersisted change.</li>
 * </ol>
 */
public final class WorldForgeBlockTools {

	public enum SyncStatus { NONE, OK, PARTIAL, FAILED }

	/** mirrors the item-side result so callers handle both uniformly */
	public record BlockToolResult(boolean persisted, boolean applied, SyncStatus sync, String message) {
		public boolean ok() {
			return persisted && applied;
		}

		public boolean needsRetry() {
			return persisted && (!applied || sync == SyncStatus.FAILED || sync == SyncStatus.PARTIAL);
		}
	}

	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-/]{1,128}");
	private static final List<String> TRIGGERS = List.of("right_click", "hit_entity");
	/** actions the generic block can genuinely perform (see BlockBehaviorEngine) */
	private static final List<String> ACTIONS = List.of(
			"apply_effect", "heal", "experience", "ignite", "give_item", "sound", "particles");

	// ---------------------------------------------------------------- reads

	public static List<BlockDefinition> listBlocks() {
		return BlockRuntimeManager.list();
	}

	public static BlockDefinition getBlock(String id) {
		return BlockRuntimeManager.get(WorldForgeTools.normalizeId(id));
	}

	// ---------------------------------------------------------------- validation

	public record Validation(List<String> errors, List<String> warnings) {
		public boolean ok() {
			return errors.isEmpty();
		}

		public String joined() {
			return String.join("; ", errors);
		}
	}

	/** strict server-side validation of a candidate definition */
	public static Validation validate(BlockDefinition def) {
		List<String> errs = new ArrayList<>();
		List<String> warns = new ArrayList<>();
		if (def.id() == null || !ID.matcher(def.id()).matches()) {
			errs.add("ID 非法（需 namespace:path 小写）");
		}
		if (def.name() == null || def.name().isBlank() || def.name().length() > 128) {
			errs.add("名称必须为 1-128 字符");
		}
		if (def.description() != null && def.description().length() > 1024) {
			errs.add("描述过长（>1024）");
		}
		if (!BlockCatalog.isSupported(def.appearance())) {
			errs.add("外观不在支持目录内：" + def.appearance()
					+ "（可用：" + String.join("/", BlockCatalog.supportedKeys()) + "）");
		}
		if (def.hardness() < 0 || def.hardness() > 50) {
			errs.add("硬度必须在 0-50 之间");
		}
		if (def.category() == BlockDefinition.Category.PERIODIC) {
			if (def.tickIntervalTicks() < 20) {
				errs.add("周期方块间隔不得低于 20 tick（1 秒），避免刷物品");
			}
			if (def.tickIntervalTicks() > 72000) {
				errs.add("周期方块间隔不得超过 72000 tick");
			}
			if (def.outputItemId() != null && !def.outputItemId().isBlank()) {
				var id = net.minecraft.util.Identifier.tryParse(def.outputItemId());
				if (id == null || !net.minecraft.registry.Registries.ITEM.containsId(id)) {
					errs.add("产出物品不存在：" + def.outputItemId());
				}
			} else {
				warns.add("周期方块未设置产出物品，将只做空转");
			}
			if (def.outputCount() < 1 || def.outputCount() > 8) {
				errs.add("单次产出数量必须在 1-8 之间");
			}
		}
		if (def.behaviors().size() > 8) {
			errs.add("行为数量过多（最多 8 条）");
		}
		for (BehaviorDefinition b : def.behaviors()) {
			if (b.trigger() == null || !TRIGGERS.contains(b.trigger().toLowerCase(Locale.ROOT))) {
				errs.add("未知触发器：" + b.trigger());
			}
			if (b.cooldownTicks() < 0 || b.cooldownTicks() > 72000) {
				errs.add("冷却必须在 0-72000 tick");
			}
			for (String action : b.actions()) {
				if (action == null || !ACTIONS.contains(action.toLowerCase(Locale.ROOT))) {
					errs.add("方块不支持该动作：" + action + "（支持：" + String.join("/", ACTIONS) + "）");
				}
			}
			for (var e : b.parameters().entrySet()) {
				if (!(e.getValue() instanceof Number) && !(e.getValue() instanceof String)
						&& !(e.getValue() instanceof Boolean)) {
					errs.add("参数类型非法：" + e.getKey());
				}
			}
		}
		return new Validation(errs, warns);
	}

	/**
	 * Store the appearance as a canonical full id ("furnace" -> "minecraft:furnace")
	 * so persisted data, comparisons and client rendering all agree.
	 */
	public static BlockDefinition canonical(BlockDefinition def) {
		String canonicalAppearance = BlockCatalog.strictAppearance(def.appearance());
		if (canonicalAppearance == null) {
			return def; // leave invalid input untouched so validation can reject it
		}
		if (canonicalAppearance.equals(def.appearance())) {
			return def;
		}
		return new BlockDefinition(def.version(), def.id(), def.name(), def.description(),
				canonicalAppearance, def.category(), def.hardness(), def.behaviors(),
				def.tickIntervalTicks(), def.outputItemId(), def.outputCount());
	}

	// ---------------------------------------------------------------- mutations

	public static BlockToolResult createBlock(MinecraftServer server, BlockDefinition def) {
		def = canonical(def);
		Validation v = validate(def);
		if (!v.ok()) {
			return new BlockToolResult(false, false, SyncStatus.NONE, "校验失败：" + v.joined());
		}
		if (BlockRuntimeManager.get(def.id()) != null) {
			return new BlockToolResult(false, false, SyncStatus.NONE,
					"已存在同名方块定义：" + def.id() + "（用 /wf block edit 修改）");
		}
		var r = BlockRuntimeManager.create(def);
		if (!r.ok()) {
			return new BlockToolResult(false, false, SyncStatus.NONE, r.message());
		}
		return finish(server, "created " + def.id());
	}

	public static BlockToolResult updateBlock(MinecraftServer server, BlockDefinition def) {
		def = canonical(def);
		Validation v = validate(def);
		if (!v.ok()) {
			return new BlockToolResult(false, false, SyncStatus.NONE, "校验失败：" + v.joined());
		}
		if (BlockRuntimeManager.get(def.id()) == null) {
			return new BlockToolResult(false, false, SyncStatus.NONE, "方块定义不存在：" + def.id());
		}
		var r = BlockRuntimeManager.update(def);
		if (!r.ok()) {
			return new BlockToolResult(false, false, SyncStatus.NONE, r.message());
		}
		return finish(server, "updated " + def.id());
	}

	public static BlockToolResult deleteBlock(MinecraftServer server, String id) {
		String nid = WorldForgeTools.normalizeId(id);
		var r = BlockRuntimeManager.delete(nid);
		if (!r.ok()) {
			return new BlockToolResult(false, false, SyncStatus.NONE, r.message());
		}
		return finish(server, "deleted " + nid);
	}

	/**
	 * Give a real generic block item carrying the virtual identity.
	 *
	 * <p>The stack is a genuine {@code worldforge:dynamic_block} BlockItem with
	 * the {@code worldforge:virtual_block_id} data component, so placing it runs
	 * the vanilla placement path and the block entity picks the identity up.</p>
	 */
	public static BlockToolResult give(ServerPlayerEntity player, String id, int count) {
		String nid = WorldForgeTools.normalizeId(id);
		BlockDefinition def = BlockRuntimeManager.get(nid);
		if (def == null) {
			return new BlockToolResult(false, false, SyncStatus.NONE,
					"方块定义不存在：" + nid + (BlockRuntimeManager.tombstones().contains(nid)
							? "（已删除，可重新创建同名定义恢复）" : ""));
		}
		int n = Math.max(1, Math.min(64, count));
		ItemStack stack = new ItemStack(WorldForgeBlocks.DYNAMIC_BLOCK_ITEM, n);
		stack.set(WorldForgeComponents.VIRTUAL_BLOCK_ID, new VirtualBlockId(nid));
		stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
				Text.literal(def.name()).styled(s -> s.withItalic(false)));
		if (!player.getInventory().insertStack(stack)) {
			player.dropItem(stack, false);
			return new BlockToolResult(true, true, SyncStatus.NONE,
					"背包已满，方块物品已掉落在脚下：" + def.name() + " x" + n);
		}
		return new BlockToolResult(true, true, SyncStatus.NONE,
				"已获得方块 " + def.name() + " x" + n + "（放置后即绑定虚拟身份）");
	}

	// ---------------------------------------------------------------- plumbing

	private static BlockToolResult finish(MinecraftServer server, String message) {
		boolean applied = true;
		SyncStatus sync = SyncStatus.NONE;
		if (server != null) {
			try {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					// refresh held block items so a hot-edited name/identity shows up
					for (int i = 0; i < p.getInventory().size(); i++) {
						ItemStack st = p.getInventory().getStack(i);
						if (st.getItem() == WorldForgeBlocks.DYNAMIC_BLOCK_ITEM) {
							var v = st.get(WorldForgeComponents.VIRTUAL_BLOCK_ID);
							if (v instanceof VirtualBlockId vb) {
								BlockDefinition d = BlockRuntimeManager.get(vb.id());
								if (d != null) {
									st.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
											Text.literal(d.name()).styled(s -> s.withItalic(false)));
								}
							}
						}
					}
				});
			} catch (Exception e) {
				applied = false;
				WorldForge.LOGGER.error("[WorldForge] block apply phase failed: {}", e.toString());
			}
			try {
				var itemSync = dev.worldforge.tool.WorldForgeTools.syncToAll(server);
				sync = itemSync == dev.worldforge.tool.WorldForgeTools.ToolResult.SyncStatus.OK
						? SyncStatus.OK : SyncStatus.PARTIAL;
			} catch (Exception e) {
				sync = SyncStatus.FAILED;
			}
		}
		return new BlockToolResult(true, applied, sync, message);
	}

	/** one-line summary used by commands and the Studio list */
	public static String describe(BlockDefinition d) {
		return d.id() + " | " + d.name() + " | " + d.category().name().toLowerCase(Locale.ROOT)
				+ " | " + d.appearance()
				+ (d.periodic() ? " | 每 " + d.tickIntervalTicks() + "t → "
						+ (d.outputItemId() == null ? "无产出" : d.outputItemId() + " x" + d.outputCount()) : "");
	}

	private WorldForgeBlockTools() {
	}
}