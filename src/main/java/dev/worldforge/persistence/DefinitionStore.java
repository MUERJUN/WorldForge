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
import java.util.Set;

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

	/** Result of a non-destructive load attempt (v0.1.2 issue #3). */
	public record LoadResult(boolean ok, List<ItemDefinition> definitions, String error) {
		public static LoadResult ok(List<ItemDefinition> defs) {
			return new LoadResult(true, defs, null);
		}

		public static LoadResult failed(String error) {
			return new LoadResult(false, List.of(), error);
		}
	}

	/**
	 * Parse WITHOUT side effects: never moves the file aside and never
	 * reports a corrupt file as "empty project". Callers must keep their
	 * current runtime state when {@code ok == false} (issue #3: /wf reload
	 * against a damaged file used to wipe live definitions).
	 */
	public LoadResult tryLoad() {
		if (!exists()) {
			// no file yet: a legitimately empty project (first run) is OK
			return LoadResult.ok(List.of());
		}
		JsonElement root;
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			root = JsonParser.parseString(text);
		} catch (Exception e) {
			return LoadResult.failed("unreadable definitions file: " + e);
		}
		if (!root.isJsonObject()) {
			return LoadResult.failed("definitions file is not a JSON object");
		}
		JsonObject obj = root.getAsJsonObject();
		int version = obj.has("version") ? obj.get("version").getAsInt() : 0;
		if (version > FILE_VERSION) {
			WorldForge.LOGGER.warn("[WorldForge] definitions file version {} newer than supported {}; attempting best-effort load", version, FILE_VERSION);
		}
		List<ItemDefinition> out = new ArrayList<>();
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
		return LoadResult.ok(out);
	}

	/**
	 * STRICT parse (v0.2.0 stage-1, closes v0.1.2 P1 silent partial loss):
	 * ANY anomaly aborts the WHOLE load instead of skipping entries -
	 * missing "items" field, unsupported newer version, non-object entries,
	 * broken records, duplicate ids, wrong field types. Callers must keep
	 * live runtime state on failure and must NOT rewrite the player's file.
	 * Distinguishes: no file (valid empty) / empty items (valid empty) /
	 * everything else that is not exactly well-formed => failed with cause.
	 */
	public LoadResult tryLoadStrict() {
		if (!exists()) {
			return LoadResult.ok(List.of()); // fresh world: legit empty project
		}
		JsonElement root;
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			root = JsonParser.parseString(text);
		} catch (Exception e) {
			return LoadResult.failed("unreadable definitions file: " + e);
		}
		if (!root.isJsonObject()) {
			return LoadResult.failed("definitions file is not a JSON object");
		}
		JsonObject obj = root.getAsJsonObject();
		int version = obj.has("version") && obj.get("version").isJsonPrimitive()
				? obj.get("version").getAsInt() : -1;
		if (version < 0) {
			return LoadResult.failed("missing or invalid \"version\" field");
		}
		if (version > FILE_VERSION) {
			return LoadResult.failed("file version " + version + " is newer than supported " + FILE_VERSION);
		}
		if (!obj.has("items")) {
			return LoadResult.failed("missing \"items\" field (refusing to treat a possibly damaged file as an empty project)");
		}
		if (!obj.get("items").isJsonArray()) {
			return LoadResult.failed("\"items\" is not an array");
		}
		List<ItemDefinition> out = new ArrayList<>();
		Set<String> seen = new java.util.HashSet<>();
		int idx = 0;
		for (JsonElement el : obj.getAsJsonArray("items")) {
			idx++;
			if (!el.isJsonObject()) {
				return LoadResult.failed("entry #" + idx + " is not an object");
			}
			ItemDefinition def;
			try {
				def = fromJson(el.getAsJsonObject());
			} catch (Exception e) {
				return LoadResult.failed("entry #" + idx + " is invalid: " + e);
			}
			if (!seen.add(def.id())) {
				return LoadResult.failed("duplicate id \"" + def.id() + "\" at entry #" + idx);
			}
			// strict also rejects values the tolerant path silently clamped away
			if (!def.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				return LoadResult.failed("entry #" + idx + " has an illegal id: " + def.id());
			}
			// strict raw-shape check: types the lenient path would silently drop
			JsonObject raw = el.getAsJsonObject();
			if (raw.has("attributes")) {
				if (!raw.get("attributes").isJsonObject()) {
					return LoadResult.failed("entry #" + idx + ": \"attributes\" must be an object");
				}
				for (var e2 : raw.getAsJsonObject("attributes").entrySet()) {
					if (!e2.getValue().isJsonPrimitive() || !e2.getValue().getAsJsonPrimitive().isNumber()) {
						return LoadResult.failed("entry #" + idx + ": attribute \"" + e2.getKey()
								+ "\" must be a number, got " + e2.getValue());
					}
				}
			}
			for (String key : new String[]{"name", "description"}) {
				if (raw.has(key) && (!raw.get(key).isJsonPrimitive() || !raw.get(key).getAsJsonPrimitive().isString())) {
					return LoadResult.failed("entry #" + idx + ": field \"" + key + "\" must be a string");
				}
			}
			out.add(def);
		}
		return LoadResult.ok(out);
	}

	/**
	 * Lenient best-effort parse with an EXPLICIT skip report (used only for
	 * world first-start recovery). Returns the definitions that could be
	 * salvaged AND the reasons for every skipped entry so the caller can
	 * warn the player loudly instead of losing records silently.
	 */
	public record LenientLoad(List<ItemDefinition> definitions, List<String> skipped) {
		public boolean hadDamage() {
			return !skipped.isEmpty();
		}
	}

	public LenientLoad tryLoadLenient() {
		List<String> skipped = new ArrayList<>();
		List<ItemDefinition> out = new ArrayList<>();
		if (!exists()) {
			return new LenientLoad(out, skipped);
		}
		JsonElement root;
		try {
			root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
		} catch (Exception e) {
			skipped.add("file unreadable: " + e);
			return new LenientLoad(out, skipped);
		}
		if (!root.isJsonObject()) {
			skipped.add("file is not a JSON object");
			return new LenientLoad(out, skipped);
		}
		JsonObject obj = root.getAsJsonObject();
		JsonArray items = obj.has("items") && obj.get("items").isJsonArray()
				? obj.getAsJsonArray("items") : new JsonArray();
		if (!obj.has("items")) {
			skipped.add("missing items array");
		}
		int idx = 0;
		Set<String> seen = new java.util.HashSet<>();
		for (JsonElement el : items) {
			idx++;
			if (!el.isJsonObject()) {
				skipped.add("entry #" + idx + ": not an object");
				continue;
			}
			try {
				ItemDefinition def = fromJson(el.getAsJsonObject());
				if (!seen.add(def.id())) {
					skipped.add("entry #" + idx + ": duplicate id " + def.id());
					continue;
				}
				out.add(def);
			} catch (Exception e) {
				skipped.add("entry #" + idx + ": " + e);
			}
		}
		return new LenientLoad(out, skipped);
	}

	/** Load all definitions for WORLD STARTUP; corrupt file is quarantined so
	 *  the fresh session can save cleanly. (Reload must use {@link #tryLoadStrict()}.) */
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
				try {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException nonAtomicFailure) {
					degradeCopy(tmp, file, nonAtomicFailure);
				}
			} catch (IOException moveFailure) {
				degradeCopy(tmp, file, moveFailure);
			}
			lastError = null;
			return true;
		} catch (IOException | RuntimeException e) {
			lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
			WorldForge.LOGGER.error("[WorldForge] FAILED to save definitions (previous file kept): {}", e.toString());
			return false;
		}
	}

	/**
	 * v0.5.7: rename() can be refused on some Windows locations (TEMP
	 * junctions, antivirus) while the directory itself IS writable — in that
	 * case copy+delete still preserves the atomic-ish save. But when the
	 * directory is genuinely unwritable (the disk-failure tests delete the
	 * target or lock the file), copy fails too and we keep the failure signal.
	 */
	private static void degradeCopy(Path tmp, Path file, IOException cause) throws IOException {
		// writability probe: can we open the target for append/create?
		boolean dirWritable;
		try (var ch = Files.newByteChannel(file,
				java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.WRITE)) {
			dirWritable = true;
		} catch (IOException probeFailure) {
			dirWritable = false;
		}
		if (!dirWritable) {
			throw cause; // genuine disk failure — keep the error signal
		}
		Files.copy(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		try {
			Files.deleteIfExists(tmp);
		} catch (IOException ignored) {
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

	public static JsonObject toJson(ItemDefinition def) {
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

	public static ItemDefinition fromJson(JsonObject o) {
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

	/** Public quarantine (v0.2.0 first-start salvage path): the damaged file
	 *  is preserved under a backup name so a later save cannot overwrite it. */
	public void quarantine() {
		moveAside();
	}
}
