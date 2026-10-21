package dev.worldforge.ops.tools;

import com.google.gson.JsonObject;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolPreparer;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.tool.WorldForgeBlockTools;
import dev.worldforge.tool.WorldForgeTools;

import java.util.List;

/**
 * The block-side write path: {@code create_block}, {@code update_block},
 * {@code inspect_target_block} and {@code modify_target_block}.
 *
 * <p>All four reuse {@link WorldForgeBlockTools}, the single block mutation
 * entry point shared with the {@code /wf} commands and the legacy Studio.</p>
 */
public final class BlockMutationTools {

	private BlockMutationTools() {
	}

	// --------------------------------------------------------- create_block

	public static final String CREATE = "create_block";

	public static ToolDescriptor createDescriptor() {
		JsonObject props = new JsonObject();
		props.add("id", JsonSchemaValidator.prop("string", "新方块 id，例如 worldforge:healing_statue"));
		props.add("name", JsonSchemaValidator.prop("string", "显示名称"));
		props.add("description", JsonSchemaValidator.prop("string", "描述，可选"));
		props.add("appearance", JsonSchemaValidator.prop("string", "外观原版方块，例如 stone"));
		props.add("category", JsonSchemaValidator.propEnum("方块类型", "decoration", "interactive", "periodic"));
		props.add("hardness", JsonSchemaValidator.prop("number", "硬度", 0, 50));
		props.add("tick_interval_ticks", JsonSchemaValidator.prop("integer", "周期方块的执行间隔（≥20）", 20, 72000));
		props.add("output_item_id", JsonSchemaValidator.prop("string", "周期方块产出的物品 id"));
		props.add("output_count", JsonSchemaValidator.prop("integer", "周期方块产出数量", 1, 8));
		props.add("behaviors", ItemMutationTools.behaviourArraySchema());
		JsonObject schema = JsonSchemaValidator.object(props, List.of("id", "name", "appearance"));
		return ToolDescriptor.write(CREATE,
				"创建一个新的 WorldForge 动态方块。外观只能用受支持的原版方块目录，"
						+ "类型支持 decoration / interactive / periodic。",
				schema);
	}

	public static final ToolExecutor CREATE_EXEC = (ctx, request, sink) -> {
		var outcome = ToolDefinitions.createBlock(request);
		if (!outcome.ok()) {
			return ToolResult.fail("invalid_arguments", outcome.error());
		}
		BlockDefinition def = outcome.value();
		var r = WorldForgeBlockTools.createBlock(ctx.server(), def);
		JsonObject o = new JsonObject();
		o.addProperty("id", def.id());
		o.addProperty("persisted", r.persisted());
		o.addProperty("applied", r.applied());
		o.addProperty("sync", r.sync().name());
		if (!r.ok()) {
			return ToolResult.degraded("persistence-failed",
					"创建方块失败（未写入）: " + r.message(), r.persisted(), r.applied());
		}
		return new ToolResult(true, "", "已创建方块 " + def.name() + "（" + def.id() + "）",
				o.toString(), List.of(def.id()), r.persisted(), r.applied(), r.sync().name());
	};

	public static final ToolPreparer CREATE_PREPARER = (ctx, request) -> {
		String id = WorldForgeTools.normalizeId(ToolDefinitions.str(request, "id"));
		if (id == null) {
			return ToolPreparation.none();
		}
		return new ToolPreparation(List.of(), List.of(), "将创建新方块 " + id, "");
	};

	// --------------------------------------------------------- update_block

	public static final String UPDATE = "update_block";

	public static ToolDescriptor updateDescriptor() {
		JsonObject props = new JsonObject();
		props.add("id", JsonSchemaValidator.prop("string", "要修改的方块 id"));
		props.add("name", JsonSchemaValidator.prop("string", "新的显示名称"));
		props.add("description", JsonSchemaValidator.prop("string", "新的描述"));
		props.add("appearance", JsonSchemaValidator.prop("string", "新的外观原版方块"));
		props.add("category", JsonSchemaValidator.propEnum("方块类型", "decoration", "interactive", "periodic"));
		props.add("hardness", JsonSchemaValidator.prop("number", "新的硬度", 0, 50));
		props.add("tick_interval_ticks", JsonSchemaValidator.prop("integer", "新的周期间隔", 20, 72000));
		props.add("output_item_id", JsonSchemaValidator.prop("string", "新的产出物品 id"));
		props.add("output_count", JsonSchemaValidator.prop("integer", "新的产出数量", 1, 8));
		JsonObject schema = JsonSchemaValidator.object(props, List.of("id"));
		return ToolDescriptor.write(UPDATE,
				"修改一个已存在的 WorldForge 动态方块定义。未写的字段保持不变。",
				schema);
	}

	public static final ToolExecutor UPDATE_EXEC = (ctx, request, sink) -> {
		String rawId = ToolDefinitions.str(request, "id");
		if (rawId == null) {
			return ToolResult.fail("invalid_arguments", "缺少 id");
		}
		String id = WorldForgeTools.normalizeId(rawId);
		BlockDefinition current = BlockRuntimeManager.get(id);
		if (current == null) {
			return ToolResult.fail("not_found", "方块定义不存在: " + id);
		}
		var outcome = ToolDefinitions.updateBlock(request, current);
		if (!outcome.ok()) {
			return ToolResult.fail("invalid_arguments", outcome.error());
		}
		var r = WorldForgeBlockTools.updateBlock(ctx.server(), outcome.value());
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("persisted", r.persisted());
		o.addProperty("applied", r.applied());
		o.addProperty("sync", r.sync().name());
		if (!r.ok()) {
			return ToolResult.degraded("persistence-failed",
					"修改方块失败（未写入）: " + r.message(), r.persisted(), r.applied());
		}
		return new ToolResult(true, "", "已修改方块 " + id + "（已放置的方块立即使用新配置）",
				o.toString(), List.of(id), r.persisted(), r.applied(), r.sync().name());
	};

	public static final ToolPreparer UPDATE_PREPARER = (ctx, request) -> {
		String rawId = ToolDefinitions.str(request, "id");
		if (rawId == null) {
			return ToolPreparation.none();
		}
		String id = WorldForgeTools.normalizeId(rawId);
		BlockDefinition current = BlockRuntimeManager.get(id);
		if (current == null) {
			return ToolPreparation.none();
		}
		var count = PlacedBlockCounter.count(ctx, id);
		String summary = "将修改方块 " + id + " 的共享定义。" + PlacedBlockCounter.impactStatement(count.count(), count.truncated());
		return new ToolPreparation(List.of(), List.of(id), summary, ToolDefinitions.snapshotOf(current));
	};

	// -------------------------------------------------- inspect_target_block

	public static final String INSPECT_TARGET = "inspect_target_block";

	public static ToolDescriptor inspectTargetDescriptor() {
		JsonObject props = new JsonObject();
		JsonObject schema = JsonSchemaValidator.object(props, List.of());
		return ToolDescriptor.read(INSPECT_TARGET,
				"检查玩家当前视线所指的那个方块：坐标、原版方块类型，以及它是否是 WorldForge 动态方块"
						+ "（是的话给出关联的定义 id 和当前行为）。原版方块无法直接改造。",
				schema);
	}

	public static final ToolExecutor INSPECT_TARGET_EXEC = (ctx, request, sink) -> {
		var target = TargetBlock.read(ctx);
		if (!target.hit()) {
			return ToolResult.fail("no_target", "你没有看向任何方块");
		}
		JsonObject o = new JsonObject();
		o.addProperty("block_id", target.vanillaId());
		o.addProperty("x", target.pos().getX());
		o.addProperty("y", target.pos().getY());
		o.addProperty("z", target.pos().getZ());
		o.addProperty("is_worldforge_block", target.virtualId() != null && !target.virtualId().isBlank());
		if (target.virtualId() != null && !target.virtualId().isBlank()) {
			o.addProperty("virtual_id", target.virtualId());
			BlockDefinition def = BlockRuntimeManager.get(target.virtualId());
			o.addProperty("definition_exists", def != null);
			if (def != null) {
				o.add("definition", PlacedBlockCounter.describe(def));
			}
			var count = PlacedBlockCounter.count(ctx, target.virtualId());
			o.addProperty("placed_instances", count.count());
			o.addProperty("placed_instances_truncated", count.truncated());
		} else {
			o.addProperty("note", "这是原版方块。WorldForge 目前不能改造原版方块；"
					+ "如果想实现类似效果，请改用一个 WorldForge 动态方块。");
		}
		return ToolResult.ok("已读取目标方块", o.toString());
	};

	// --------------------------------------------------- modify_target_block

	public static final String MODIFY_TARGET = "modify_target_block";

	public static ToolDescriptor modifyTargetDescriptor() {
		JsonObject props = new JsonObject();
		props.add("name", JsonSchemaValidator.prop("string", "新的显示名称"));
		props.add("description", JsonSchemaValidator.prop("string", "新的描述"));
		props.add("appearance", JsonSchemaValidator.prop("string", "新的外观原版方块"));
		props.add("category", JsonSchemaValidator.propEnum("方块类型", "decoration", "interactive", "periodic"));
		props.add("hardness", JsonSchemaValidator.prop("number", "新的硬度", 0, 50));
		props.add("tick_interval_ticks", JsonSchemaValidator.prop("integer", "新的周期间隔", 20, 72000));
		JsonObject schema = JsonSchemaValidator.object(props, List.of());
		return ToolDescriptor.write(MODIFY_TARGET,
				"修改玩家当前视线所指的 WorldForge 动态方块。修改的是它的共享定义，"
						+ "因此会同时影响世界里所有同 ID 的方块实例。原版方块无法这样修改。",
				schema);
	}

	public static final ToolExecutor MODIFY_TARGET_EXEC = (ctx, request, sink) -> {
		var target = TargetBlock.read(ctx);
		if (!target.hit()) {
			return ToolResult.fail("no_target", "你没有看向任何方块");
		}
		if (target.virtualId() == null || target.virtualId().isBlank()) {
			return ToolResult.fail("not_a_worldforge_block",
					"你看向的是原版方块，无法直接修改。可以创建一个 WorldForge 动态方块来实现同样的效果。");
		}
		String id = target.virtualId();
		BlockDefinition current = BlockRuntimeManager.get(id);
		if (current == null) {
			return ToolResult.fail("not_found", "该方块的定义已不存在: " + id);
		}
		// route through update_block with the target's id
		ToolRequest scoped = new ToolRequest(request.requestId(), request.conversationId(),
				request.toolCallId(), UPDATE, withId(request.arguments(), id));
		var outcome = ToolDefinitions.updateBlock(scoped, current);
		if (!outcome.ok()) {
			return ToolResult.fail("invalid_arguments", outcome.error());
		}
		var r = WorldForgeBlockTools.updateBlock(ctx.server(), outcome.value());
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("persisted", r.persisted());
		o.addProperty("applied", r.applied());
		o.addProperty("sync", r.sync().name());
		if (!r.ok()) {
			return ToolResult.degraded("persistence-failed",
					"修改方块失败（未写入）: " + r.message(), r.persisted(), r.applied());
		}
		return new ToolResult(true, "",
				"已修改方块 " + id + "，当前世界里所有同 ID 的方块立即使用新行为",
				o.toString(), List.of(id), r.persisted(), r.applied(), r.sync().name());
	};

	/**
	 * Prepare step for {@code modify_target_block}: resolves the target and
	 * states the shared-definition impact explicitly.
	 */
	public static final ToolPreparer MODIFY_TARGET_PREPARER = (ctx, request) -> {
		var target = TargetBlock.read(ctx);
		if (!target.hit() || target.virtualId() == null || target.virtualId().isBlank()) {
			return ToolPreparation.none();
		}
		String id = target.virtualId();
		BlockDefinition current = BlockRuntimeManager.get(id);
		if (current == null) {
			return ToolPreparation.none();
		}
		var count = PlacedBlockCounter.count(ctx, id);
		String summary = "将修改你正在看的方块 " + id + " 的共享定义。"
				+ PlacedBlockCounter.impactStatement(count.count(), count.truncated());
		return new ToolPreparation(List.of(), List.of(id), summary, ToolDefinitions.snapshotOf(current));
	};

	private static java.util.Map<String, Object> withId(java.util.Map<String, Object> args, String id) {
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<>(args);
		out.put("id", id);
		return out;
	}
}
