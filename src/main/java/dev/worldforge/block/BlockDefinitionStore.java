package dev.worldforge.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.WorldForge;
import dev.worldforge.definition.BehaviorDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Persistence for virtual block definitions ({@code worldforge/blocks.json}),
 * holding the same data-safety discipline as the item store (v0.2.0 stage 1):
 *
 * <ul>
 *   <li>{@link #tryLoadStrict()} rejects the WHOLE file on any anomaly, so a
 *       single damaged record can never silently drop the other definitions
 *       on the next save;</li>
 *   <li>{@link #tryLoadLenient()} salvages what it can and reports every
 *       skipped entry by name (used only for first-start recovery);</li>
 *   <li>{@link #save} writes to a temp file and moves it atomically;</li>
 *   <li>{@link #quarantine()} moves a broken file aside instead of
 *       overwriting it.</li>
 * </ul>
 */
public final class BlockDefinitionStore {

	public static final int FILE_VERSION = 1;
	private static final String FILE_NAME = "blocks.json";
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]{1,128}");

	private final Path dir;
	private volatile String lastError = "";

	public BlockDefinitionStore(Path worldforgeDir) {
		this.dir = worldforgeDir;
	}

	public String lastError() {
		return lastError;
	}

	public Path file() {
		return dir.resolve(FILE_NAME);
	}

	public boolean exists() {
		return Files.isRegularFile(file());
	}

	public record LoadResult(boolean ok, List<BlockDefinition> definitions, String error) {
		public static LoadResult ok(List<BlockDefinition> defs) {
			return new LoadResult(true, defs, "");
		}

		public static LoadResult failed(String error) {
			return new LoadResult(false, List.of(), error);
		}
	}

	/** strict: any anomaly rejects the entire file (never a silent partial load) */
	public LoadResult tryLoadStrict() {
		if (!exists()) {
			return LoadResult.ok(List.of());
		}
		JsonObject root;
		try {
			JsonElement el = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
			if (!el.isJsonObject()) {
				return LoadResult.failed("blocks file is not a JSON object");
			}
			root = el.getAsJsonObject();
		} catch (Exception e) {
			return LoadResult.failed("unreadable blocks file: " + e);
		}
		if (!root.has("version")) {
			return LoadResult.failed("missing version field");
		}
		int version = root.get("version").getAsInt();
		if (version > FILE_VERSION) {
			return LoadResult.failed("file version " + version + " is newer than supported " + FILE_VERSION);
		}
		if (!root.has("blocks") || !root.get("blocks").isJsonArray()) {
			return LoadResult.failed("missing blocks array");
		}
		List<BlockDefinition> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		JsonArray arr = root.getAsJsonArray("blocks");
		for (int i = 0; i < arr.size(); i++) {
			JsonElement e = arr.get(i);
			if (!e.isJsonObject()) {
				return LoadResult.failed("entry #" + i + " is not an object");
			}
			BlockDefinition def;
			try {
				def = fromJson(e.getAsJsonObject());
			} catch (Exception ex) {
				return LoadResult.failed("entry #" + i + " is invalid: " + ex);
			}
			if (def.id() == null || !ID.matcher(def.id()).matches()) {
				return LoadResult.failed("entry #" + i + " has an illegal id");
			}
			if (!seen.add(def.id())) {
				return LoadResult.failed("duplicate id: " + def.id());
			}
			out.add(def);
		}
		return LoadResult.ok(out);
	}

	public record LenientLoad(List<BlockDefinition> definitions, List<String> skipped) {
		public boolean hadDamage() {
			return !skipped.isEmpty();
		}
	}

	/** lenient: skip broken entries but report each one (first-start recovery only) */
	public LenientLoad tryLoadLenient() {
		List<BlockDefinition> out = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		if (!exists()) {
			return new LenientLoad(out, skipped);
		}
		try {
			JsonElement el = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
			if (!el.isJsonObject() || !el.getAsJsonObject().has("blocks")
					|| !el.getAsJsonObject().get("blocks").isJsonArray()) {
				skipped.add("missing blocks array");
				return new LenientLoad(out, skipped);
			}
			Set<String> seen = new LinkedHashSet<>();
			JsonArray arr = el.getAsJsonObject().getAsJsonArray("blocks");
			for (int i = 0; i < arr.size(); i++) {
				try {
					BlockDefinition d = fromJson(arr.get(i).getAsJsonObject());
					if (d.id() == null || !ID.matcher(d.id()).matches()) {
						skipped.add("entry #" + i + ": illegal id");
						continue;
					}
					if (!seen.add(d.id())) {
						skipped.add("entry #" + i + ": duplicate id " + d.id());
						continue;
					}
					out.add(d);
				} catch (Exception ex) {
					skipped.add("entry #" + i + ": " + ex.getClass().getSimpleName());
				}
			}
		} catch (Exception e) {
			skipped.add("unreadable file: " + e.getClass().getSimpleName());
		}
		return new LenientLoad(out, skipped);
	}

	/** move a broken file aside (never delete player data) */
	public boolean quarantine() {
		Path f = file();
		if (!Files.isRegularFile(f)) {
			return false;
		}
		Path target = dir.resolve(FILE_NAME + ".broken-" + Instant.now().toEpochMilli());
		try {
			Files.move(f, target, StandardCopyOption.REPLACE_EXISTING);
			WorldForge.LOGGER.error("[WorldForge] quarantined damaged block file -> {}", target.getFileName());
			return true;
		} catch (IOException e) {
			lastError = "quarantine failed: " + e;
			return false;
		}
	}

	/** atomic save: temp file + move, keeping the previous file on failure */
	public boolean save(List<BlockDefinition> definitions) {
		JsonObject root = new JsonObject();
		root.addProperty("version", FILE_VERSION);
		JsonArray arr = new JsonArray();
		for (BlockDefinition d : definitions) {
			arr.add(toJson(d));
		}
		root.add("blocks", arr);
		Path target = file();
		Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
		try {
			Files.createDirectories(dir);
			Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException moveFailure) {
				// v0.5.7: rename refused (Windows temp junctions) — copy+delete
				Files.copy(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				try {
					Files.deleteIfExists(tmp);
				} catch (IOException ignored) {
				}
			}
			lastError = "";
			return true;
		} catch (IOException e) {
			lastError = e.toString();
			WorldForge.LOGGER.error("[WorldForge] FAILED to save block definitions (previous file kept): {}", e);
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
			}
			return false;
		}
	}

	// ---------------------------------------------------------------- json

	/** serialize a list of definitions to a JSON string (for network sync) */
	public static String toJsonString(java.util.List<BlockDefinition> defs) {
		JsonObject root = new JsonObject();
		root.addProperty("version", FILE_VERSION);
		JsonArray arr = new JsonArray();
		for (BlockDefinition d : defs) {
			arr.add(toJson(d));
		}
		root.add("blocks", arr);
		return root.toString();
	}

	public static JsonObject toJson(BlockDefinition d) {
		JsonObject o = new JsonObject();
		o.addProperty("version", d.version());
		o.addProperty("id", d.id());
		o.addProperty("name", d.name());
		o.addProperty("description", d.description());
		o.addProperty("appearance", d.appearance());
		o.addProperty("category", d.category().name());
		o.addProperty("hardness", d.hardness());
		o.addProperty("tick_interval_ticks", d.tickIntervalTicks());
		if (d.outputItemId() != null) {
			o.addProperty("output_item", d.outputItemId());
			o.addProperty("output_count", d.outputCount());
		}
		JsonArray bs = new JsonArray();
		for (BehaviorDefinition b : d.behaviors()) {
			JsonObject bo = new JsonObject();
			bo.addProperty("trigger", b.trigger());
			JsonArray a = new JsonArray();
			b.actions().forEach(a::add);
			bo.add("actions", a);
			bo.addProperty("cooldown_ticks", b.cooldownTicks());
			if (!b.parameters().isEmpty()) {
				bo.add("parameters", WorldForge.GSON.toJsonTree(b.parameters()));
			}
			bs.add(bo);
		}
		o.add("behaviors", bs);
		return o;
	}

	public static BlockDefinition fromJson(JsonObject o) {
		String id = o.has("id") && o.get("id").isJsonPrimitive() ? o.get("id").getAsString() : null;
		String name = o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : "";
		String desc = o.has("description") && o.get("description").isJsonPrimitive()
				? o.get("description").getAsString() : "";
		String appearance = o.has("appearance") && o.get("appearance").isJsonPrimitive()
				? o.get("appearance").getAsString() : BlockDefinition.Category.DECORATION.name();
		BlockDefinition.Category cat;
		try {
			cat = BlockDefinition.Category.valueOf(o.has("category") && o.get("category").isJsonPrimitive()
					? o.get("category").getAsString().toUpperCase(java.util.Locale.ROOT)
					: "DECORATION");
		} catch (Exception e) {
			cat = BlockDefinition.Category.DECORATION;
		}
		double hardness = o.has("hardness") && o.get("hardness").isJsonPrimitive()
				? o.get("hardness").getAsDouble() : 1.5;
		int interval = o.has("tick_interval_ticks") && o.get("tick_interval_ticks").isJsonPrimitive()
				? o.get("tick_interval_ticks").getAsInt() : 200;
		String outItem = o.has("output_item") && o.get("output_item").isJsonPrimitive()
				? o.get("output_item").getAsString() : null;
		int outCount = o.has("output_count") && o.get("output_count").isJsonPrimitive()
				? o.get("output_count").getAsInt() : 1;
		List<BehaviorDefinition> behaviors = new ArrayList<>();
		if (o.has("behaviors") && o.get("behaviors").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("behaviors")) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject b = e.getAsJsonObject();
				String trigger = b.has("trigger") ? b.get("trigger").getAsString() : "right_click";
				List<String> actions = new ArrayList<>();
				if (b.has("actions") && b.get("actions").isJsonArray()) {
					b.getAsJsonArray("actions").forEach(a -> actions.add(a.getAsString()));
				}
				int cd = b.has("cooldown_ticks") ? b.get("cooldown_ticks").getAsInt() : 100;
				Map<String, Object> params = new LinkedHashMap<>();
				if (b.has("parameters") && b.get("parameters").isJsonObject()) {
					for (var en : b.getAsJsonObject("parameters").entrySet()) {
						JsonElement v = en.getValue();
						if (!v.isJsonPrimitive()) {
							continue;
						}
						if (v.getAsJsonPrimitive().isBoolean()) {
							params.put(en.getKey(), v.getAsBoolean());
						} else if (v.getAsJsonPrimitive().isNumber()) {
							params.put(en.getKey(), v.getAsDouble());
						} else {
							params.put(en.getKey(), v.getAsString());
						}
					}
				}
				behaviors.add(new BehaviorDefinition(trigger, List.copyOf(actions), Map.copyOf(params), cd));
			}
		}
		return new BlockDefinition(
				o.has("version") ? o.get("version").getAsInt() : BlockDefinition.CURRENT_VERSION,
				id, name, desc, appearance, cat, hardness, List.copyOf(behaviors), interval, outItem, outCount);
	}
}