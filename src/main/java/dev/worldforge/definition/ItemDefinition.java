package dev.worldforge.definition;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One virtual item definition, fully data driven.
 *
 * <p>JSON shape (persisted form):</p>
 * <pre>{@code
 * {
 *   "version": 1,
 *   "id": "worldforge:thunder_sword",
 *   "name": "雷霆之剑",
 *   "description": "能够召唤闪电的魔法武器",
 *   "texture": { "source": "vanilla", "resource": "minecraft:item/diamond_sword" },
 *   "attributes": { "damage": 12, "max_durability": 500, "max_stack": 1, "attack_speed": -2.4 },
 *   "behaviors": [
 *     { "trigger": "right_click", "action": "summon_lightning", "cooldown_ticks": 100, "parameters": {} }
 *   ]
 * }
 * }</pre>
 *
 * <p>Instances are immutable. Editing a definition means replacing it in the
 * runtime manager; stacks in inventories re-resolve everything (name, lore,
 * damage, appearance) from the manager on every use, so hot edits apply
 * without rebuilding the stacks.</p>
 */
public record ItemDefinition(
		int version,
		String id,
		String name,
		String description,
		TextureSource texture,
		Map<String, Double> attributes,
		List<BehaviorDefinition> behaviors) {

	public static final int CURRENT_VERSION = 1;

	public ItemDefinition {
		if (version <= 0) {
			version = CURRENT_VERSION;
		}
		if (behaviors == null) {
			behaviors = List.of();
		}
		if (attributes == null) {
			attributes = Map.of();
		}
		if (texture == null) {
			texture = TextureSource.default_();
		}
	}

	// ---- attribute accessors with sane defaults ----

	public double damage() {
		return attrOr("damage", 0.0);
	}

	public int maxDurability() {
		return (int) Math.max(1, attrOr("max_durability", 1.0));
	}

	public int maxStack() {
		return (int) Math.min(99, Math.max(1, attrOr("max_stack", maxDurability() > 1 ? 1 : 64)));
	}

	/** Vanilla baseline is 4.0; negative values slow swings down (sword = -2.4). */
	public double attackSpeed() {
		return attrOr("attack_speed", 0.0);
	}

	private double attrOr(String key, double def) {
		Double d = attributes.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
		return d == null || d.isNaN() || d.isInfinite() ? def : d;
	}

	/** The appearance slot this definition maps to (never fails). */
	public int appearanceSlotIndex() {
		return AppearanceSlots.resolveTexture(texture).index();
	}

	/** Return a copy with one attribute changed. */
	public ItemDefinition withAttribute(String key, double value) {
		var copy = new java.util.LinkedHashMap<>(attributes);
		copy.put(key.toLowerCase(Locale.ROOT), value);
		return new ItemDefinition(version, id, name, description, texture, Map.copyOf(copy), behaviors);
	}

	public ItemDefinition withTexture(TextureSource tex) {
		return new ItemDefinition(version, id, name, description, tex, attributes, behaviors);
	}

	public ItemDefinition withName(String newName) {
		return new ItemDefinition(version, id, newName, description, texture, attributes, behaviors);
	}

	/** Extract the bare path of the virtual id, e.g. "thunder_sword". */
	public String shortId() {
		int i = id.indexOf(':');
		return i >= 0 ? id.substring(i + 1) : id;
	}
}
