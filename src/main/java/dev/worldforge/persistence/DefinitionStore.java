package dev.worldforge.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.WorldForge;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-world JSON file store for item definitions.
 *
 * <p>Layout inside the world save: {@code worldforge/items.json}. Isolation
 * between saves is automatic because the path is derived from the running
 * server's save directory.</p>
 *
 * <p>Write safety: serialise to a temp file in the same directory, then an
 * atomic move over the target. A crash mid-write can never leave a truncated
 * definitions file. Reads validate every entry; a corrupt or unreadable file
 * is moved aside to {@code items.json.broken-<timestamp>} so the next save
 * succeeds and evidence is preserved. Entries that fail validation are
 * skipped individually with a logged reason (spec 6: graceful handling of
 * damaged files).</p>
 */
public final class DefinitionStore {

	public static final int FILE_VERSION = 1;
	private static final String FILE_NAME = "items.json";

	private final Path file;
	/** Reason of the most recent failed save (null if the last save succeeded). */
	private volatile String lastError = null;

	public DefinitionStore(Path worldforgeDir) {
		this.file = worldforgeDir.resolve(FILE_NAME);
	}

	public String lastError() {
		return lastError;
	}

	public boolean exists() {
		return Files.isRegularFile(file);
	}

	/** Load all definitions; never throws - failures are logged and skipped. */
	public List<ItemDefinition> load() {
		List<ItemDefinition> out = new ArrayList<>();
		if (!exists()) {
			return out;
		}
		JsonElement root;
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			root = JsonParser.parseString(text);
		} catch (Exception e) {
			WorldForge.LOGGER.error("[WorldForge] definitions file unreadable: {}", e.toString());
			moveAside();
			return out;
		}
		if (!root.isJsonObject()) {
			WorldForge.LOGGER.error("[WorldForge] definitions file is not a JSON object; moving aside");
			moveAside();
			return out;
		}
		JsonObject obj = root.getAsJsonObject();
		int version = obj.has("version") ? obj.get("version").getAsInt() : 0;
		if (version > FILE_VERSION) {
			WorldForge.LOGGER.warn("[WorldForge] definitions file version {} newer than supported {}; attempting best-effort load", version, FILE_VERSION);
		}
		JsonArray items = obj.has("items") && obj.get("items").isJsonArray() ? obj.getAsJsonArray("items") : new JsonArray();
		for (JsonElement el : items) {
			if (!el.isJsonObject()) {
				WorldForge.LOGGER.warn("[WorldForge] skipping non-object item entry");
				continue;
			}
			try {
				out.add(fromJson(el.getAsJsonObject()));
			} catch (Exception e) {
				WorldForge.LOGGER.warn("[WorldForge] skipping invalid item definition: {}", e.toString());
			}
		}
		return out;
	}

	/**
	 * Atomic write of all definitions. On any failure the previous file is
	 * left untouched (temp-file + atomic move) and {@link #lastError()}
	 * carries the reason; returns false so callers must NOT report success.
	 */
	public boolean save(List<ItemDefinition> definitions) {
		JsonObject root = new JsonObject();
		root.addProperty("version", FILE_VERSION);
		JsonArray arr = new JsonArray();
		for (ItemDefinition def : definitions) {
			arr.add(toJson(def));
		}
		root.add("items", arr);

		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
			Files.writeString(tmp, WorldForge.GSON.toJson(root), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			lastError = null;
			return true;
		} catch (IOException | RuntimeException e) {
			lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
			WorldForge.LOGGER.error("[WorldForge] FAILED to save definitions (previous file kept): {}", e.toString());
			return false;
		}
	}

	// ---- (de)serialisation: pure JSON handling, unit-testable without MC ----

	/** Serialise all definitions to the file format (used for S2C sync too). */
	public static String toJsonString(List<ItemDefinition> definitions) {
		JsonObject root = new JsonObject();
		root.addProperty("version", FILE_VERSION);
		JsonArray arr = new JsonArray();
		for (ItemDefinition def : definitions) {
			arr.add(toJson(def));
		}
		root.add("items", arr);
		return WorldForge.GSON.toJson(root);
	}

	/** Parse definitions from the file format; invalid entries skipped. */
	public static List<ItemDefinition> fromJsonString(String json) {
		List<ItemDefinition> out = new ArrayList<>();
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return out;
			}
			JsonArray items = root.getAsJsonObject().has("items")
					? root.getAsJsonObject().getAsJsonArray("items") : new JsonArray();
			for (JsonElement el : items) {
				if (el.isJsonObject()) {
					try {
						out.add(fromJson(el.getAsJsonObject()));
					} catch (Exception ignored) {
					}
				}
			}
		} catch (Exception ignored) {
		}
		return out;
	}

	static JsonObject toJson(ItemDefinition def) {
		JsonObject o = new JsonObject();
		o.addProperty("version", def.version());
		o.addProperty("id", def.id());
		if (def.name() != null) o.addProperty("name", def.name());
		if (def.description() != null) o.addProperty("description", def.description());

		JsonObject tex = new JsonObject();
		tex.addProperty("source", def.texture() == null ? TextureSource.SOURCE_VANILLA : def.texture().source());
		tex.addProperty("resource", def.texture() == null ? TextureSource.default_().resource() : def.texture().resource());
		o.add("texture", tex);

		JsonObject attrs = new JsonObject();
		for (Map.Entry<String, Double> e : def.attributes().entrySet()) {
			attrs.addProperty(e.getKey(), e.getValue());
		}
		o.add("attributes", attrs);

		JsonArray behArr = new JsonArray();
		for (BehaviorDefinition b : def.behaviors()) {
			JsonObject bo = new JsonObject();
			bo.addProperty("trigger", b.trigger());
			JsonArray actions = new JsonArray();
			b.actions().forEach(actions::add);
			bo.add("actions", actions);
			if (b.cooldownTicks() > 0) {
				bo.addProperty("cooldown_ticks", b.cooldownTicks());
			}
			if (!b.parameters().isEmpty()) {
				bo.add("parameters", WorldForge.GSON.toJsonTree(b.parameters()));
			}
			behArr.add(bo);
		}
		o.add("behaviors", behArr);
		return o;
	}

	static ItemDefinition fromJson(JsonObject o) {
		String id = requireString(o, "id");
		String name = o.has("name") ? o.get("name").getAsString() : id;
		String desc = o.has("description") ? o.get("description").getAsString() : "";
		int version = o.has("version") ? o.get("version").getAsInt() : ItemDefinition.CURRENT_VERSION;

		TextureSource tex = TextureSource.default_();
		if (o.has("texture") && o.get("texture").isJsonObject()) {
			JsonObject t = o.getAsJsonObject("texture");
			tex = TextureSource.of(
					t.has("source") ? t.get("source").getAsString() : TextureSource.SOURCE_VANILLA,
					t.has("resource") ? t.get("resource").getAsString() : TextureSource.default_().resource());
		}

		Map<String, Double> attrs = new LinkedHashMap<>();
		if (o.has("attributes") && o.get("attributes").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("attributes").entrySet()) {
				if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
					attrs.put(e.getKey(), e.getValue().getAsDouble());
				}
			}
		}

		List<BehaviorDefinition> behaviors = new ArrayList<>();
		if (o.has("behaviors") && o.get("behaviors").isJsonArray()) {
			for (JsonElement el : o.getAsJsonArray("behaviors")) {
				if (!el.isJsonObject()) continue;
				JsonObject b = el.getAsJsonObject();
				String trigger = requireString(b, "trigger");
				List<String> actions = new ArrayList<>();
				if (b.has("actions") && b.get("actions").isJsonArray()) {
					b.getAsJsonArray("actions").forEach(a -> actions.add(a.getAsString()));
				} else if (b.has("action")) {
					// tolerate the single-string reference format
					actions.add(b.get("action").getAsString());
				}
				int cooldown = b.has("cooldown_ticks") ? b.get("cooldown_ticks").getAsInt() : BehaviorDefinition.DEFAULT_COOLDOWN_TICKS;
				Map<String, Object> params = new LinkedHashMap<>();
				if (b.has("parameters") && b.get("parameters").isJsonObject()) {
					for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("parameters").entrySet()) {
						params.put(e.getKey(), primitiveOrString(e.getValue()));
					}
				}
				behaviors.add(new BehaviorDefinition(trigger, actions, params, cooldown));
			}
		}

		return new ItemDefinition(version, id, name, desc, tex, attrs, behaviors);
	}

	private static Object primitiveOrString(JsonElement el) {
		if (el.isJsonNull()) return null;
		if (el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
		if (el.getAsJsonPrimitive().isNumber()) return el.getAsDouble();
		return el.getAsString();
	}

	private static String requireString(JsonObject o, String key) {
		if (!o.has(key) || !o.get(key).isJsonPrimitive()) {
			throw new IllegalArgumentException("missing required field '" + key + "'");
		}
		String s = o.get(key).getAsString();
		if (s.isBlank()) {
			throw new IllegalArgumentException("blank field '" + key + "'");
		}
		return s;
	}

	private void moveAside() {
		try {
			Path bak = file.resolveSibling(FILE_NAME + ".broken-" + System.currentTimeMillis());
			Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
			WorldForge.LOGGER.warn("[WorldForge] damaged definitions file preserved at {}", bak);
		} catch (IOException e) {
			WorldForge.LOGGER.warn("[WorldForge] could not move aside damaged definitions file: {}", e.toString());
		}
	}
}
