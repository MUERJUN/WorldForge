package dev.worldforge.definition;

/**
 * Texture reference of a virtual item.
 *
 * <p>Texture priority (spec section 13):</p>
 * <ol>
 *   <li>{@code generated} / {@code custom} - reserved for future AI / imported
 *       textures. Parsed today but resolved to the best available fallback.</li>
 *   <li>{@code vanilla} - reuse a vanilla texture by resource id (the main MVP
 *       path; nothing is copied into the JAR).</li>
 *   <li>{@code mod} - a texture shipped by WorldForge itself.</li>
 * </ol>
 *
 * <p>A texture problem must never make item creation fail: resolution to a
 * concrete appearance slot always succeeds, degrading to the default slot
 * when nothing better can be found.</p>
 */
public record TextureSource(String source, String resource) {

	public static final String SOURCE_VANILLA = "vanilla";
	public static final String SOURCE_MOD = "mod";
	public static final String SOURCE_GENERATED = "generated";
	public static final String SOURCE_CUSTOM = "custom";

	public static TextureSource of(String source, String resource) {
		if (source == null || source.isBlank()) {
			source = SOURCE_VANILLA;
		}
		if (resource == null || resource.isBlank()) {
			return default_();
		}
		return new TextureSource(source.toLowerCase(java.util.Locale.ROOT), resource.toLowerCase(java.util.Locale.ROOT));
	}

	public static TextureSource vanilla(String resource) {
		return new TextureSource(SOURCE_VANILLA, resource);
	}

	public static TextureSource default_() {
		return new TextureSource(SOURCE_VANILLA, AppearanceSlots.DEFAULT_TEXTURE);
	}

	public boolean isKnownSource() {
		return switch (source) {
			case SOURCE_VANILLA, SOURCE_MOD, SOURCE_GENERATED, SOURCE_CUSTOM -> true;
			default -> false;
		};
	}
}
