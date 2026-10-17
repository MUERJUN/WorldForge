package dev.worldforge.definition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The finite catalog of pre-baked appearance slots shipped inside the JAR.
 *
 * <p>How appearance works on Minecraft 1.21.1 (verified against the vanilla
 * model override predicate code): the item model of
 * {@code worldforge:dynamic_item} declares one override per slot,
 * {@code {"predicate": {"custom_model_data": N}, "model": "worldforge:item/wf_slot_N"}}.
 * Each {@code wf_slot_N.json} references a texture - here always a
 * <em>vanilla</em> texture id such as {@code minecraft:item/diamond_sword},
 * so vanilla resources are reused by id and no PNG is copied into the mod.</p>
 *
 * <p>The stack carries the slot index in the vanilla
 * {@code minecraft:custom_model_data} data component, which is both networked
 * and persistent, so appearance changes propagate to clients automatically.</p>
 *
 * <p><b>Honest limitation:</b> this is a fixed catalog of slots, NOT
 * unlimited dynamic texture generation. Several virtual items may share one
 * slot; identity, name, stats and behaviour stay independent regardless.
 * AI-generated textures ({@code generated}) and user imports ({@code custom})
 * are AssetManager work for a later milestone.</p>
 */
public final class AppearanceSlots {

	/** Slot index used when nothing is configured. */
	public static final int DEFAULT_INDEX = 15;
	public static final String DEFAULT_TEXTURE = "minecraft:item/stick";

	private static final List<AppearanceSlot> SLOTS = new ArrayList<>();
	private static final Map<String, AppearanceSlot> BY_TEXTURE = new LinkedHashMap<>();

	public record AppearanceSlot(int index, String name, String texture) {
	}

	static {
		add("sword_light",  "minecraft:item/diamond_sword");
		add("sword_fire",   "minecraft:item/golden_sword");
		add("sword_frost",  "minecraft:item/iron_sword");
		add("sword_plain",  "minecraft:item/stone_sword");
		add("staff_rod",    "minecraft:item/blaze_rod");
		add("staff_eye",    "minecraft:item/ender_eye");
		add("staff_tnt",    "minecraft:item/gunpowder");
		add("amulet_star",  "minecraft:item/nether_star");
		add("amulet_heart", "minecraft:item/heart_of_the_sea");
		add("gem_emerald",  "minecraft:item/emerald");
		add("gem_diamond",  "minecraft:item/diamond");
		add("orb_exp",      "minecraft:item/experience_bottle");
		add("speed_sugar",  "minecraft:item/sugar");
		add("jump_foot",    "minecraft:item/rabbit_foot");
		add("default_stick","minecraft:item/stick");
	}

	private static void add(String name, String texture) {
		AppearanceSlot slot = new AppearanceSlot(SLOTS.size() + 1, name, texture);
		SLOTS.add(slot);
		BY_TEXTURE.put(texture, slot);
	}

	/** Number of slots; matches the generated model JSONs exactly. */
	public static int count() {
		return SLOTS.size();
	}

	public static List<AppearanceSlot> slots() {
		return List.copyOf(SLOTS);
	}

	public static AppearanceSlot byIndex(int index) {
		if (index < 1 || index > SLOTS.size()) {
			return SLOTS.get(DEFAULT_INDEX - 1);
		}
		return SLOTS.get(index - 1);
	}

	/** Resolve a texture id to a slot; unknown textures degrade to the default slot. */
	public static AppearanceSlot resolve(String textureId) {
		AppearanceSlot slot = BY_TEXTURE.get(normalizeTexture(textureId));
		return slot != null ? slot : SLOTS.get(DEFAULT_INDEX - 1);
	}

	/** Resolve a texture reference from a definition, honouring its source. */
	public static AppearanceSlot resolveTexture(TextureSource tex) {
		if (tex == null) {
			return byIndex(DEFAULT_INDEX);
		}
		return switch (tex.source()) {
			// Future sources: parsed, but resolved through the same fallback
			// chain so creation never fails because of a texture problem.
			case TextureSource.SOURCE_VANILLA, TextureSource.SOURCE_MOD,
				 TextureSource.SOURCE_GENERATED, TextureSource.SOURCE_CUSTOM -> resolve(tex.resource());
			default -> byIndex(DEFAULT_INDEX);
		};
	}

	/** Normalize a texture reference (accepts "diamond_sword", "item/diamond_sword", full ids). */
	public static String normalizeTexture(String textureId) {
		if (textureId == null || textureId.isBlank()) {
			return DEFAULT_TEXTURE;
		}
		String t = textureId.trim().toLowerCase(Locale.ROOT);
		if (!t.contains(":")) {
			t = t.startsWith("item/") || t.startsWith("block/") ? "minecraft:" + t : "minecraft:item/" + t;
		}
		return t;
	}

	/**
	 * Heuristic vanilla-texture matching from an item id / name / description
	 * (spec 13.1.3). Only ever returns textures that exist in the catalog.
	 */
	public static String guessVanillaTexture(String haystack) {
		String s = haystack == null ? "" : haystack.toLowerCase(Locale.ROOT);
		if (has(s, "sword", "剑", "blade", "刃")) {
			if (has(s, "fire", "flame", "烈焰", "火焰", "火")) return "minecraft:item/golden_sword";
			if (has(s, "ice", "frost", "霜", "冰")) return "minecraft:item/iron_sword";
			if (has(s, "thunder", "lightning", "雷", "闪电")) return "minecraft:item/diamond_sword";
			return "minecraft:item/stone_sword";
		}
		if (has(s, "heal", "治疗", "生命")) return "minecraft:item/blaze_rod";
		if (has(s, "exp", "经验")) return "minecraft:item/experience_bottle";
		if (has(s, "bomb", "explos", "爆炸")) return "minecraft:item/gunpowder";
		if (has(s, "teleport", "传送")) return "minecraft:item/ender_eye";
		if (has(s, "speed", "速度")) return "minecraft:item/sugar";
		if (has(s, "jump", "跳跃")) return "minecraft:item/rabbit_foot";
		if (has(s, "night", "vision", "夜视")) return "minecraft:item/stick";
		if (has(s, "amulet", "charm", "护符")) return "minecraft:item/heart_of_the_sea";
		if (has(s, "gem", "宝石")) return "minecraft:item/emerald";
		if (has(s, "staff", "wand", "法杖", "魔杖")) return "minecraft:item/blaze_rod";
		return DEFAULT_TEXTURE;
	}

	private static boolean has(String s, String... needles) {
		for (String n : needles) {
			if (s.contains(n)) return true;
		}
		return false;
	}

	private AppearanceSlots() {
	}
}
