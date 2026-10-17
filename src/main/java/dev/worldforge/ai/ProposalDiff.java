package dev.worldforge.ai;

import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.runtime.WorldForgeRuntimeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable diff summary for the confirmation preview (spec 七 预览要求:
 * 玩家必须能知道 AI 将会修改什么). Pure text, no world access.
 */
public final class ProposalDiff {

	public static String describe(Proposal p) {
		List<String> lines = new ArrayList<>();
		switch (p) {
			case Proposal.Create c -> {
				lines.add("操作: 创建新物品");
				lines.add("ID: " + c.id());
				lines.add("名称: " + c.name());
				if (c.description() != null && !c.description().isBlank()) {
					lines.add("描述: " + c.description());
				}
				lines.add("外观: " + (c.texture() == null ? "默认(stick)" : c.texture()));
				lines.add("伤害: " + (c.damage() == null ? 0 : c.damage())
						+ " | 耐久: " + (c.maxDurability() == null ? 100 : c.maxDurability()));
				describeBehaviors(c.behaviors(), lines);
			}
			case Proposal.Update u -> {
				ItemDefinition cur = WorldForgeRuntimeManager.get(u.id());
				lines.add("操作: 修改已有物品 " + u.id());
				if (cur == null) {
					lines.add("§c(当前定义不可见)");
					return String.join("\n", lines);
				}
				if (u.name() != null) {
					lines.add("名称: " + cur.name() + " → " + u.name());
				}
				if (u.description() != null) {
					lines.add("描述: → " + u.description());
				}
				if (u.texture() != null) {
					lines.add("外观: " + (cur.texture() == null ? "?" : cur.texture().resource()) + " → " + u.texture());
				}
				if (u.damage() != null) {
					lines.add("伤害: " + cur.damage() + " → " + u.damage());
				}
				if (u.maxDurability() != null) {
					lines.add("耐久: " + cur.maxDurability() + " → " + u.maxDurability());
				}
				if (u.setBehaviors() != null) {
					lines.add("技能: 全量替换为：");
					describeBehaviors(u.setBehaviors(), lines);
				}
				if (u.addBehaviors() != null && !u.addBehaviors().isEmpty()) {
					lines.add("技能: 新增：");
					describeBehaviors(u.addBehaviors(), lines);
				}
				if (u.removeTriggers() != null && !u.removeTriggers().isEmpty()) {
					lines.add("技能: 移除触发器 " + String.join("/", u.removeTriggers()));
				}
				if (lines.size() == 1) {
					lines.add("（无任何字段变化）");
				}
				lines.add("未提及的属性与技能保持不变");
			}
			case Proposal.Delete d -> {
				ItemDefinition cur = WorldForgeRuntimeManager.get(d.id());
				lines.add("操作: 删除定义 " + d.id() + (cur == null ? "" : "（" + cur.name() + "）"));
				lines.add("背包中的存量物品不会消失，将显示[已失效]标记；重建同名 ID 自动恢复");
			}
			case Proposal.Query q -> lines.add("查询: " + q.what());
		}
		return String.join("\n", lines);
	}

	private static void describeBehaviors(List<Proposal.BehaviorSpec> bs, List<String> lines) {
		if (bs == null || bs.isEmpty()) {
			return;
		}
		for (Proposal.BehaviorSpec b : bs) {
			StringBuilder sb = new StringBuilder("技能: ");
			sb.append(b.trigger()).append(" → ").append(b.action());
			if (b.cooldownTicks() != null) {
				sb.append(" 冷却 ").append(b.cooldownTicks()).append("t");
			}
			if (b.parameters() != null && !b.parameters().isEmpty()) {
				sb.append(" ").append(b.parameters());
			}
			lines.add(sb.toString());
		}
	}

	/** compact single-line summary for chat fallbacks */
	public static String oneLine(Proposal p) {
		String d = describe(p);
		return d.replace("\n", " · ");
	}

	private ProposalDiff() {
	}
}
