package dev.worldforge.ops.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolEventSink;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolPreparer;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.tool.WorldForgeTools;

import java.util.List;

/**
 * {@code create_item} and {@code update_item} — the write path for virtual
 * items.
 *
 * <p>Both reuse {@link WorldForgeTools}, the single mutation entry point that
 * the {@code /wf} commands and the legacy Studio already use. The transaction
 * (persist with rollback, re-bake held stacks, broadcast snapshot) is therefore
 * identical no matter who asked.</p>
 */
public final class ItemMutationTools {

	private ItemMutationTools() {
	}

	// ---------------------------------------------------------------- create

	public static final String CREATE = "create_item";

	public static ToolDescriptor createDescriptor() {
		JsonObject props = new JsonObject();
		props.add("id", JsonSchemaValidator.prop("string", "新物品 id，例如 worldforge:thunder_sword"));
		props.add("name", JsonSchemaValidator.prop("string", "显示名称"));
		props.add("description", JsonSchemaValidator.prop("string", "描述，可选"));
		props.add("texture", JsonSchemaValidator.prop("string", "外观槽位，例如 minecraft:item/diamond_sword"));
		props.add("damage", JsonSchemaValidator.prop("number", "伤害值", -1000, 1000));
		props.add("max_durability", JsonSchemaValidator.prop("integer", "耐久度", 1, 65536));
		props.add("attack_speed", JsonSchemaValidator.prop("number", "攻击速度", -10, 10));
		props.add("behaviors", behaviourArraySchema());
		JsonObject schema = JsonSchemaValidator.object(props, List.of("id", "name", "texture"));
		return ToolDescriptor.write(CREATE,
				"创建一个新的 WorldForge 虚拟物品。外观只能用受支持的原版槽位，行为只能用白名单动作。"
						+ "创建后可以用 give_virtual_item 把它交给玩家。",
				schema);
	}

	/** The behaviour array schema, shared by item and block tools. */
	public static JsonObject behaviourArraySchema() {
		JsonObject behaviour = new JsonObject();
		behaviour.addProperty("type", "object");
		JsonObject bprops = new JsonObject();
		bprops.add("trigger", JsonSchemaValidator.propEnum("触发时机", "right_click", "hit_entity"));
		bprops.add("action", JsonSchemaValidator.propEnum("行为动作", "summon_lightning", "apply_effect",
				"heal", "experience", "teleport", "explosion", "ignite"));
		bprops.add("cooldown_ticks", JsonSchemaValidator.prop("integer", "冷却 tick（20 = 1 秒）", 0, 72000));
		bprops.add("parameters", new JsonObject());
		behaviour.add("properties", bprops);
		behaviour.addProperty("additionalProperties", false);
		JsonObject arr = new JsonObject();
		arr.addProperty("type", "array");
		arr.add("items", behaviour);
		arr.addProperty("maxItems", 8);
		return arr;
	}

	public static final ToolExecutor CREATE_EXEC = (ctx, request, sink) -> {
		var outcome = ToolDefinitions.createItem(request);
		if (!outcome.ok()) {
			return ToolResult.fail("invalid_arguments", outcome.error());
		}
		ItemDefinition def = outcome.value();
		if (!ctx.scope().mayWriteItem(def.id())) {
			return ToolResult.fail("out_of_scope",
					"这个物品不在你确认的范围内：" + def.id());
		}
		var r = WorldForgeTools.createItem(ctx.server(), def);
		JsonObject o = new JsonObject();
		o.addProperty("id", def.id());
		o.addProperty("name", def.name());
		o.addProperty("persisted", r.persisted());
		o.addProperty("applied", r.applied());
		o.addProperty("sync", r.sync().name());
		if (!r.ok()) {
			return ToolResult.degraded(failureCode(r.message()),
					"创建物品失败（未写入）: " + r.message(), r.persisted(), r.applied());
		}
		return new ToolResult(true, "", "已创建物品 " + def.name() + "（" + def.id() + "）",
				o.toString(), List.of(def.id()), r.persisted(), r.applied(), r.sync().name());
	};

	/** Prepare step: the id does not exist yet, so there is no snapshot. */
	/**
	 * The right error code for a failed write.
	 *
	 * <p>v0.7.1: everything used to be reported as "persistence-failed", so a
	 * validation failure ("this id already exists") told the model the disk
	 * had failed. That is simply not true.</p>
	 */
	static String failureCode(String message) {
		String m = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
		if (m.contains("已存在") || m.contains("already exists") || m.contains("no such item")) {
			return "already_exists";
		}
		if (m.contains("save") || m.contains("persist") || m.contains("disk") || m.contains("磁盘")) {
			return "persistence-failed";
		}
		return "invalid_arguments";
	}

	public static final ToolPreparer CREATE_PREPARER = (ctx, request) -> {
		String id = dev.worldforge.tool.WorldForgeTools.normalizeId(ToolDefinitions.str(request, "id"));
		if (id == null) {
			return ToolPreparation.none();
		}
		return ToolPreparation.of(List.of(id), List.of(),
				"将创建新物品 " + id, "",
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};

	// ---------------------------------------------------------------- update

	public static final String UPDATE = "update_item";

	public static ToolDescriptor updateDescriptor() {
		JsonObject props = new JsonObject();
		props.add("id", JsonSchemaValidator.prop("string", "要修改的物品 id"));
		props.add("name", JsonSchemaValidator.prop("string", "新的显示名称"));
		props.add("description", JsonSchemaValidator.prop("string", "新的描述"));
		props.add("texture", JsonSchemaValidator.prop("string", "新的外观槽位"));
		props.add("damage", JsonSchemaValidator.prop("number", "新的伤害值", -1000, 1000));
		props.add("max_durability", JsonSchemaValidator.prop("integer", "新的耐久度", 1, 65536));
		props.add("add_behaviors", behaviourArraySchema());
		props.add("set_behaviors", behaviourArraySchema());
		props.add("remove_triggers", new JsonObject());
		JsonObject schema = JsonSchemaValidator.object(props, List.of("id"));
		return ToolDescriptor.write(UPDATE,
				"修改一个已存在的 WorldForge 虚拟物品。add_behaviors 追加行为，set_behaviors 整体替换行为列表，"
						+ "remove_triggers 移除指定触发器的行为；未写的字段保持不变。",
				schema);
	}

	public static final ToolExecutor UPDATE_EXEC = (ctx, request, sink) -> {
		String rawId = ToolDefinitions.str(request, "id");
		if (rawId == null) {
			return ToolResult.fail("invalid_arguments", "缺少 id");
		}
		String id = WorldForgeTools.normalizeId(rawId);
		ItemDefinition current = WorldForgeRuntimeManager.get(id);
		if (current == null) {
			return ToolResult.fail("not_found", "物品定义不存在: " + id);
		}
		var outcome = ToolDefinitions.updateItem(request, current);
		if (!outcome.ok()) {
			return ToolResult.fail("invalid_arguments", outcome.error());
		}
		ItemDefinition merged = outcome.value();
		if (!ctx.scope().mayWriteItem(id)) {
			return ToolResult.fail("out_of_scope", "这个物品不在你确认的范围内：" + id);
		}
		var r = WorldForgeTools.updateItem(ctx.server(), merged);
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("persisted", r.persisted());
		o.addProperty("applied", r.applied());
		o.addProperty("sync", r.sync().name());
		if (!r.ok()) {
			return ToolResult.degraded(failureCode(r.message()),
					"修改物品失败（未写入）: " + r.message(), r.persisted(), r.applied());
		}
		return new ToolResult(true, "", "已修改物品 " + id + "（背包中的存量物品立即生效）",
				o.toString(), List.of(id), r.persisted(), r.applied(), r.sync().name());
	};

	/**
	 * Prepare step: capture the CURRENT definition so the confirmation can be
	 * re-validated at execution time (someone else may have edited it in the
	 * meantime).
	 */
	public static final ToolPreparer UPDATE_PREPARER = (ctx, request) -> {
		String rawId = ToolDefinitions.str(request, "id");
		if (rawId == null) {
			return ToolPreparation.none();
		}
		String id = WorldForgeTools.normalizeId(rawId);
		ItemDefinition current = WorldForgeRuntimeManager.get(id);
		if (current == null) {
			return ToolPreparation.none();
		}
		return ToolPreparation.of(List.of(id), List.of(),
				"将修改物品 " + id + " 的定义（当前名称: " + current.name() + "，已有行为 "
						+ current.behaviors().size() + " 条）。背包中该物品的存量实例会立即生效。",
				ToolDefinitions.snapshotOf(current),
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	};
}
