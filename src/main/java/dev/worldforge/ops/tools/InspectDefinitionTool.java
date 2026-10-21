package dev.worldforge.ops.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.runtime.WorldForgeRuntimeManager;

import java.util.List;

/**
 * {@code inspect_definition} — reads one WorldForge definition and what can
 * currently be changed about it.
 */
public final class InspectDefinitionTool {

	public static final String NAME = "inspect_definition";

	private InspectDefinitionTool() {
	}

	public static ToolDescriptor descriptor() {
		JsonObject props = new JsonObject();
		props.add("id", JsonSchemaValidator.prop("string", "the id of the item or block, e.g. worldforge:thunder_sword"));
		props.add("type", JsonSchemaValidator.propEnum("which definition kind to read", "item", "block"));
		JsonObject schema = JsonSchemaValidator.object(props, List.of("id", "type"));
		return ToolDescriptor.read(NAME,
				"Read the current definition and editable capabilities of a WorldForge item or block "
						+ "(attributes, existing behaviours, appearance, cooldowns). Read it before you "
						+ "modify anything so you know the exact current state.",
				schema);
	}

	public static final ToolExecutor EXECUTOR = (ctx, request, sink) -> {
		String rawId = request.argumentString("id");
		if (rawId == null || rawId.isBlank()) {
			return ToolResult.fail("invalid_arguments", "missing id");
		}
		String id = dev.worldforge.tool.WorldForgeTools.normalizeId(rawId);
		String type = request.argumentString("type");
		if (type == null) {
			type = "item";
		}

		if ("block".equals(type)) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) {
				return ToolResult.fail("not_found", "no such block definition: " + id);
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", def.id());
			o.addProperty("type", "block");
			o.addProperty("name", def.name());
			o.addProperty("description", def.description());
			o.addProperty("appearance", def.appearance());
			o.addProperty("category", def.category().name());
			o.addProperty("hardness", def.hardness());
			o.addProperty("tick_interval_ticks", def.tickIntervalTicks());
			o.addProperty("output_item_id", def.outputItemId() == null ? "" : def.outputItemId());
			o.addProperty("output_count", def.outputCount());
			JsonArray behaviours = new JsonArray();
			for (BehaviorDefinition b : def.behaviors()) {
				behaviours.add(behaviourJson(b));
			}
			o.add("behaviors", behaviours);
			o.add("editable_fields", jsonArray(
					"name", "description", "appearance", "category",
					"hardness", "tick_interval_ticks", "output_item_id", "output_count", "behaviors"));
			return ToolResult.ok("read block definition " + id, o.toString(), List.of(id));
		}

		ItemDefinition def = WorldForgeRuntimeManager.get(id);
		if (def == null) {
			return ToolResult.fail("not_found", "no such item definition: " + id);
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", def.id());
		o.addProperty("type", "item");
		o.addProperty("name", def.name());
		o.addProperty("description", def.description());
		o.addProperty("texture", def.texture().resource());
		o.addProperty("damage", def.damage());
		o.addProperty("max_durability", def.maxDurability());
		o.addProperty("attack_speed", def.attackSpeed());
		JsonArray behaviours = new JsonArray();
		for (BehaviorDefinition b : def.behaviors()) {
			behaviours.add(behaviourJson(b));
		}
		o.add("behaviors", behaviours);
		o.add("editable_fields", jsonArray(
				"name", "description", "texture", "damage",
				"max_durability", "attack_speed", "behaviors"));
		return ToolResult.ok("read item definition " + id, o.toString(), List.of(id));
	};

	/** Builds a JSON array of strings. */
	public static JsonArray jsonArray(String... values) {
		JsonArray arr = new JsonArray();
		for (String v : values) {
			arr.add(v);
		}
		return arr;
	}

	/** Shared behaviour rendering so the model sees one consistent shape. */
	public static JsonObject behaviourJson(BehaviorDefinition b) {
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
			} else if (v != null) {
				params.addProperty(k, String.valueOf(v));
			}
		});
		o.add("parameters", params);
		return o;
	}
}
