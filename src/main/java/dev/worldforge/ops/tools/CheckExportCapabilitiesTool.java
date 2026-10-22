package dev.worldforge.ops.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.exporter.ExportPlan;
import dev.worldforge.exporter.ModExporter;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolExecutor;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolPreparer;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import dev.worldforge.runtime.WorldForgeRuntimeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code check_export_capabilities} — what survives an export to a standalone
 * mod, BEFORE anything is built.
 *
 * <p>Pure analysis: no Gradle process, no files written. The player sees the
 * retained / converted / lost breakdown and decides.</p>
 */
public final class CheckExportCapabilitiesTool {

	public static final String NAME = "check_export_capabilities";

	private CheckExportCapabilitiesTool() {
	}

	public static ToolDescriptor descriptor() {
		JsonObject props = new JsonObject();
		props.add("item_ids", stringArraySchema("要导出的物品 id 列表"));
		props.add("block_ids", stringArraySchema("要导出的方块 id 列表"));
		JsonObject schema = JsonSchemaValidator.object(props, List.of());
		return ToolDescriptor.read(NAME,
				"分析把哪些物品和方块导出成独立 Mod 时会怎样：哪些能力被完整保留、哪些被等价转换、"
						+ "哪些无法导出。不会开始构建，先给出报告。",
				schema);
	}

	static JsonObject stringArraySchema(String description) {
		JsonObject arr = new JsonObject();
		arr.addProperty("type", "array");
		JsonObject items = new JsonObject();
		items.addProperty("type", "string");
		items.addProperty("description", description);
		arr.add("items", items);
		arr.addProperty("maxItems", 64);
		return arr;
	}

	public static final ToolExecutor EXECUTOR = (ctx, request, sink) -> {
		List<String> itemIds = normalize(stringList(request, "item_ids"));
		List<String> blockIds = normalize(stringList(request, "block_ids"));
		if (itemIds.isEmpty() && blockIds.isEmpty()) {
			return ToolResult.fail("invalid_arguments", "至少需要一个物品或方块 id");
		}
		List<ItemDefinition> items = new ArrayList<>();
		for (String id : itemIds) {
			ItemDefinition d = WorldForgeRuntimeManager.get(id);
			if (d == null) {
				return ToolResult.fail("not_found", "物品定义不存在: " + id);
			}
			items.add(d);
		}
		List<BlockDefinition> blocks = new ArrayList<>();
		for (String id : blockIds) {
			BlockDefinition d = BlockRuntimeManager.get(id);
			if (d == null) {
				return ToolResult.fail("not_found", "方块定义不存在: " + id);
			}
			blocks.add(d);
		}
		var report = ModExporter.capabilityReportOf(items, blocks);
		JsonObject o = new JsonObject();
		o.add("retained", jsonArray(report.retained()));
		o.add("converted", jsonArray(report.converted()));
		o.add("lost", jsonArray(report.lost()));
		o.addProperty("needs_degraded_confirmation", !report.lost().isEmpty());
		o.addProperty("summary", "保留 " + report.retained().size() + " 项，转换 "
				+ report.converted().size() + " 项，丢失 " + report.lost().size() + " 项");
		return ToolResult.ok("已生成导出能力报告", o.toString());
	};

	/** Normalises the ids the way every other tool does. */
	private static List<String> normalize(List<String> ids) {
		List<String> out = new java.util.ArrayList<>();
		for (String id : ids) {
			String n = dev.worldforge.tool.WorldForgeTools.normalizeId(id);
			if (n != null && !n.isBlank()) {
				out.add(n);
			}
		}
		return out;
	}

	/** Reads a list-of-strings argument. */
	public static List<String> stringList(ToolRequest request, String key) {
		List<String> out = new ArrayList<>();
		Object v = request.arguments().get(key);
		if (v instanceof List<?> list) {
			for (Object o : list) {
				if (o != null) {
					String s = String.valueOf(o).trim();
					if (!s.isEmpty()) {
						out.add(s);
					}
				}
			}
		}
		return out;
	}

	/** Builds a JSON array of strings. */
	public static JsonArray jsonArray(List<String> values) {
		JsonArray arr = new JsonArray();
		values.forEach(arr::add);
		return arr;
	}

	/** Shared helper for the export tool's preparation step. */
	public static ToolPreparation prepareExport(dev.worldforge.ops.ToolContext ctx, ToolRequest request,
			String summary) {
		List<String> itemIds = stringList(request, "item_ids");
		List<String> blockIds = stringList(request, "block_ids");
		return ToolPreparation.of(itemIds, blockIds, summary, "",
				ctx.world() == null ? "" : ctx.worldId(),
				ctx.dimensionId() == null ? "" : ctx.dimensionId().toString());
	}
}
