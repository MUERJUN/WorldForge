package dev.worldforge.command;

import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.runtime.WorldForgeRuntimeManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 10 demo virtual items (spec section 4). These are ordinary runtime
 * definitions - nothing about them is natively registered; they exist to
 * validate the create/give/behaviour/persistence pipeline.
 */
public final class DemoDefinitions {

	private static ItemDefinition d(String id, String name, String desc, String texture,
			double damage, double durability, double attackSpeed, List<BehaviorDefinition> behaviors) {
		Map<String, Double> attrs = new LinkedHashMap<>();
		attrs.put("damage", damage);
		attrs.put("max_durability", durability);
		attrs.put("attack_speed", attackSpeed);
		return new ItemDefinition(ItemDefinition.CURRENT_VERSION, "worldforge:" + id, name, desc,
				TextureSource.vanilla(texture), Map.copyOf(attrs), behaviors);
	}

	private static BehaviorDefinition right(String action, int cd, Map<String, Object> params) {
		return new BehaviorDefinition("right_click", action, cd, params);
	}

	private static BehaviorDefinition hit(String action, int cd, Map<String, Object> params) {
		return new BehaviorDefinition("hit_entity", action, cd, params);
	}

	public static List<ItemDefinition> all() {
		return List.of(
				// 1. 雷霆之剑：右键召唤闪电
				d("thunder_sword", "雷霆之剑", "能够召唤闪电的魔法武器",
						"minecraft:item/diamond_sword", 12, 500, -2.4,
						List.of(right("summon_lightning", 100, Map.of("range", 12)))),
				// 2. 烈焰之剑：攻击点燃目标
				d("fire_sword", "烈焰之剑", "被它击中的敌人会燃烧起来",
						"minecraft:item/golden_sword", 9, 300, -2.4,
						List.of(hit("ignite", 40, Map.of("seconds", 5)))),
				// 3. 治疗法杖：右键恢复生命
				d("healing_staff", "治疗法杖", "凝聚生命之力的法杖",
						"minecraft:item/blaze_rod", 2, 200, 0,
						List.of(right("heal", 100, Map.of("amount", 6)))),
				// 4. 速度护符：使用后获得速度效果
				d("speed_amulet", "速度护符", "佩戴者健步如飞",
						"minecraft:item/heart_of_the_sea", 0, 64, 0,
						List.of(right("apply_effect", 60, Map.of("effect", "minecraft:speed", "duration", 400, "amplifier", 1)))),
				// 5. 跳跃魔杖：使用后获得跳跃提升
				d("jump_wand", "跳跃魔杖", "轻轻一点，跃过房顶",
						"minecraft:item/ender_eye", 0, 128, 0,
						List.of(right("apply_effect", 60, Map.of("effect", "minecraft:jump_boost", "duration", 300, "amplifier", 2)))),
				// 6. 爆炸法杖：在安全限制下产生爆炸
				d("explosion_staff", "爆炸法杖", "向视线前方投掷受控爆裂（威力已被引擎限制）",
						"minecraft:item/gunpowder", 3, 128, 0,
						List.of(right("explosion", 120, Map.of("power", 2.0, "fire", false)))),
				// 7. 传送法杖：短距离传送
				d("teleport_staff", "传送法杖", "一步千里",
						"minecraft:item/ender_pearl", 0, 256, 0,
						List.of(right("teleport", 60, Map.of("distance", 16)))),
				// 8. 冰霜之剑：攻击附加缓慢
				d("frost_sword", "冰霜之剑", "寒气刺骨，令敌人步履维艰",
						"minecraft:item/iron_sword", 8, 400, -2.4,
						List.of(hit("apply_effect", 30, Map.of("effect", "minecraft:slowness", "duration", 100, "amplifier", 1, "target", "target")))),
				// 9. 经验法杖：使用后获得经验
				d("experience_staff", "经验法杖", "凝结知识的结晶",
						"minecraft:item/experience_bottle", 0, 128, 0,
						List.of(right("experience", 80, Map.of("amount", 30)))),
				// 10. 夜视护符：使用后获得夜视
				d("nightvision_amulet", "夜视护符", "黑暗如白昼",
						"minecraft:item/nether_star", 0, 64, 0,
						List.of(right("apply_effect", 60, Map.of("effect", "minecraft:night_vision", "duration", 600, "amplifier", 0)))));
	}

	/** Create all demo items; returns how many were newly created. Existing ids are kept untouched. */
	public static int createAll() {
		int n = 0;
		for (ItemDefinition def : all()) {
			var r = WorldForgeRuntimeManager.createItem(def);
			if (r.ok()) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Tool-routed demo: every creation goes through WorldForgeTools (rollback
	 * + broadcast once at the end). A persistence failure aborts cleanly with
	 * the error message instead of half-applied fake success.
	 */
	public static dev.worldforge.tool.WorldForgeTools.ToolResult createAllViaTools(
			net.minecraft.server.MinecraftServer server) {
		int created = 0, existed = 0;
		for (ItemDefinition def : all()) {
			if (WorldForgeRuntimeManager.get(def.id()) != null) {
				existed++;
				continue;
			}
			var r = dev.worldforge.tool.WorldForgeTools.createItem(server, def);
			if (!r.ok()) {
				return new dev.worldforge.tool.WorldForgeTools.ToolResult(false,
						"failed at " + def.id() + ": " + r.message());
			}
			created++;
		}
		dev.worldforge.tool.WorldForgeTools.broadcast(server);
		return new dev.worldforge.tool.WorldForgeTools.ToolResult(true,
				"新建 " + created + " 件（已存在 " + existed + " 件保持不变）");
	}

	private DemoDefinitions() {
	}
}
