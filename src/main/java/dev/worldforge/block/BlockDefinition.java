package dev.worldforge.block;

import dev.worldforge.definition.BehaviorDefinition;

import java.util.List;

/**
 * A runtime-created virtual BLOCK definition (v0.4.0).
 *
 * <p>Minecraft's block registry is frozen after init, so a "dynamic block" is
 * a virtual identity carried by {@link DynamicBlockEntity} on ONE of the few
 * generic block types registered at startup. This record is the definition
 * half (shared by all placed instances); the per-instance half lives in the
 * block entity.</p>
 *
 * <p>Capabilities are explicit, not implied: {@link Category} declares what a
 * definition may do, and {@link BlockCatalog} decides which vanilla appearance
 * it may borrow. Anything the generic block cannot express (per-block light
 * level, unique collision shape, redstone behaviour) is NOT silently faked -
 * it is listed as a runtime limitation.</p>
 */
public record BlockDefinition(
		int version,
		String id,
		String name,
		String description,
		String appearance,
		Category category,
		double hardness,
		List<BehaviorDefinition> behaviors,
		int tickIntervalTicks,
		String outputItemId,
		int outputCount) {

	public static final int CURRENT_VERSION = 1;

	/** what a definition is allowed to do at runtime */
	public enum Category {
		/** place/break only */
		DECORATION,
		/** right-click interaction (effects, sound, particles, controlled item give) */
		INTERACTIVE,
		/** block-entity tick that emits controlled items on an interval */
		PERIODIC
	}

	/** runtime restrictions that are inherent to the single-generic-block design */
	public static final List<String> RUNTIME_LIMITATIONS = List.of(
			"所有虚拟方块共用一个原生方块类型：碰撞形状/硬度/光照/红石行为来自通用方块本身，无法逐定义改变",
			"无法逐定义自定义光照等级（luminance）",
			"外观通过方块实体渲染复用原版方块模型，不作为独立方块注册",
			"周期方块每 tick 产出受全局与单方块配额限制");

	public static BlockDefinition create(String id, String name, String description, String appearance,
			Category category, double hardness, List<BehaviorDefinition> behaviors,
			int tickIntervalTicks, String outputItemId, int outputCount) {
		return new BlockDefinition(CURRENT_VERSION, id, name, description, appearance, category,
				hardness, behaviors == null ? List.of() : List.copyOf(behaviors),
				tickIntervalTicks, outputItemId, outputCount);
	}

	public boolean periodic() {
		return category == Category.PERIODIC;
	}

	public boolean interactive() {
		return category == Category.INTERACTIVE || category == Category.PERIODIC;
	}

	public String shortId() {
		int i = id.indexOf(':');
		return i >= 0 ? id.substring(i + 1) : id;
	}
}