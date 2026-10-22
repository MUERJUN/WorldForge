package dev.worldforge.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.component.ComponentType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import dev.worldforge.WorldForge;
import dev.worldforge.definition.BehaviorDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The behaviours GRAFTED onto a stack.
 *
 * <p>v0.7.2: until now only a WorldForge <em>virtual</em> item could carry
 * behaviours, because the behaviour engine was only ever reached through
 * {@code DynamicItem#use}. A vanilla item had no way to take part.</p>
 *
 * <p>A graft is a small, self-contained list of behaviours that lives on the
 * stack itself. The item keeps its identity (same id, same texture, same
 * enchantments, same durability); it only <em>gains</em> the ability to do
 * something. Removing the graft restores the stack completely, which is what
 * makes the feature undoable.</p>
 *
 * <p>The behaviour list uses exactly the JSON shape the definition file uses,
 * so there is one format to learn and one to validate.</p>
 */
public record BehaviorGraft(List<BehaviorDefinition> behaviors) {

	public static final Codec<BehaviorGraft> CODEC = Codec.STRING.xmap(
			BehaviorGraft::fromJson, g -> toJson(g.behaviors()));

	public static final PacketCodec<? super RegistryByteBuf, BehaviorGraft> PACKET_CODEC =
			PacketCodecs.STRING.xmap(BehaviorGraft::fromJson, g -> toJson(g.behaviors()));

	public BehaviorGraft {
		behaviors = behaviors == null ? List.of() : List.copyOf(behaviors);
	}

	public boolean isEmpty() {
		return behaviors.isEmpty();
	}

	/** Serialises the grafted behaviours. */
	public static String toJson(List<BehaviorDefinition> behaviors) {
		JsonArray arr = new JsonArray();
		for (BehaviorDefinition b : behaviors) {
			JsonObject o = new JsonObject();
			o.addProperty("trigger", b.trigger());
			JsonArray actions = new JsonArray();
			b.actions().forEach(actions::add);
			o.add("actions", actions);
			o.addProperty("cooldown_ticks", b.cooldownTicks());
			JsonObject params = new JsonObject();
			b.parameters().forEach((k, v) -> {
				if (v instanceof Number n) {
					params.addProperty(k, n);
				} else if (v instanceof Boolean bo) {
					params.addProperty(k, bo);
				} else if (v != null) {
					params.addProperty(k, String.valueOf(v));
				}
			});
			o.add("parameters", params);
			arr.add(o);
		}
		JsonObject root = new JsonObject();
		root.add("behaviors", arr);
		return root.toString();
	}

	/** Parses grafted behaviours; a malformed graft is simply empty. */
	public static BehaviorGraft fromJson(String json) {
		List<BehaviorDefinition> out = new ArrayList<>();
		if (json == null || json.isBlank()) {
			return new BehaviorGraft(out);
		}
		try {
			JsonElement el = JsonParser.parseString(json);
			JsonArray arr = el.isJsonObject() && el.getAsJsonObject().has("behaviors")
					? el.getAsJsonObject().getAsJsonArray("behaviors")
					: (el.isJsonArray() ? el.getAsJsonArray() : null);
			if (arr == null) {
				return new BehaviorGraft(out);
			}
			for (JsonElement item : arr) {
				if (!item.isJsonObject()) {
					continue;
				}
				JsonObject b = item.getAsJsonObject();
				String trigger = b.has("trigger") ? b.get("trigger").getAsString() : "right_click";
				List<String> actions = new ArrayList<>();
				if (b.has("actions") && b.get("actions").isJsonArray()) {
					b.getAsJsonArray("actions").forEach(a -> actions.add(a.getAsString()));
				} else if (b.has("action") && b.get("action").isJsonPrimitive()) {
					actions.add(b.get("action").getAsString());
				}
				int cooldown = b.has("cooldown_ticks") ? b.get("cooldown_ticks").getAsInt()
						: BehaviorDefinition.DEFAULT_COOLDOWN_TICKS;
				Map<String, Object> params = new LinkedHashMap<>();
				if (b.has("parameters") && b.get("parameters").isJsonObject()) {
					for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("parameters").entrySet()) {
						params.put(e.getKey(), primitive(e.getValue()));
					}
				}
				if (!actions.isEmpty()) {
					out.add(new BehaviorDefinition(trigger, actions, params, cooldown));
				}
			}
		} catch (Exception ignored) {
			// a malformed graft must never break the item
		}
		return new BehaviorGraft(out);
	}

	private static Object primitive(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonPrimitive()) {
			var p = e.getAsJsonPrimitive();
			if (p.isBoolean()) {
				return p.getAsBoolean();
			}
			if (p.isNumber()) {
				return p.getAsDouble();
			}
			return p.getAsString();
		}
		return e.toString();
	}
}
