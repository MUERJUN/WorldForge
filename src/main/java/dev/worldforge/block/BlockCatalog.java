package dev.worldforge.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The closed catalog of vanilla appearances a virtual block may borrow
 * (v0.4.0, spec 七).
 *
 * <p>Important distinction: a virtual block has its OWN identity
 * ({@code worldforge:xxx}) which is NOT a registry entry. Only its appearance
 * points at a real registered vanilla block, whose default state is used for
 * rendering by the block entity renderer. Nothing is registered after the
 * registry freeze, and no item CustomModelData trick is reused for blocks.</p>
 */
public final class BlockCatalog {

	/** appearance key -> the vanilla block that supplies model + texture */
	private static final Map<String, String> CATALOG = new LinkedHashMap<>();

	static {
		put("stone", "minecraft:stone");
		put("oak_planks", "minecraft:oak_planks");
		put("furnace", "minecraft:furnace");
		put("crafting_table", "minecraft:crafting_table");
		put("iron_block", "minecraft:iron_block");
		put("gold_block", "minecraft:gold_block");
		put("emerald_block", "minecraft:emerald_block");
		put("amethyst_block", "minecraft:amethyst_block");
		put("redstone_lamp", "minecraft:redstone_lamp");
		put("bookshelf", "minecraft:bookshelf");
		put("barrel", "minecraft:barrel");
		put("deepslate_tiles", "minecraft:deepslate_tiles");
	}

	private static void put(String key, String vanillaId) {
		CATALOG.put(key, vanillaId);
	}

	public static final String DEFAULT_APPEARANCE = "minecraft:stone";

	/** every supported appearance, as vanilla block ids */
	public static List<String> supportedAppearances() {
		return List.copyOf(CATALOG.values());
	}

	public static List<String> supportedKeys() {
		return List.copyOf(CATALOG.keySet());
	}

	/**
	 * Strict resolution: returns the canonical vanilla block id ONLY when the
	 * requested appearance is genuinely in the catalog, otherwise {@code null}.
	 *
	 * <p>v0.4.0 fix: the previous single {@code normalize()} folded "unknown"
	 * into the stone fallback, so {@code isSupported("minecraft:diamond_block")}
	 * returned TRUE - a validation hole that would have let an unsupported
	 * appearance through the proposal validator. Strict resolution and display
	 * fallback are now separate operations.</p>
	 */
	public static String strictAppearance(String appearance) {
		if (appearance == null || appearance.isBlank()) {
			return null;
		}
		String a = appearance.trim();
		if (a.contains(":")) {
			return CATALOG.containsValue(a) ? a : null;
		}
		return CATALOG.get(a); // null when not a catalog key
	}

	public static boolean isSupported(String appearance) {
		return strictAppearance(appearance) != null;
	}

	/** display/rendering resolution: unsupported or empty values fall back to stone */
	public static String normalize(String appearance) {
		String strict = strictAppearance(appearance);
		return strict != null ? strict : DEFAULT_APPEARANCE;
	}

	/** resolve to the vanilla block, or the fallback when unknown */
	public static Block resolveBlock(String appearance) {
		Identifier id = Identifier.tryParse(normalize(appearance));
		Block b = id == null ? null : Registries.BLOCK.get(id);
		if (b == null || b == net.minecraft.block.Blocks.AIR) {
			b = Registries.BLOCK.get(Identifier.tryParse(DEFAULT_APPEARANCE));
		}
		return b;
	}

	public static BlockState resolveState(String appearance) {
		return resolveBlock(appearance).getDefaultState();
	}

	private BlockCatalog() {
	}
}