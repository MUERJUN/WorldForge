package dev.worldforge.exporter;

import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.5.0: Export WorldForge definitions as an independent Fabric Mod JAR.
 *
 * <p>The exported mod registers real Item and Block entries at startup (not
 * virtual proxies), so it runs without WorldForge installed. The first version
 * uses a template project strategy: generate a complete Fabric 1.21.1 project
 * with Java source, resources, and a Gradle wrapper, then build it.</p>
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>All IDs, class names, and resource paths are strictly validated.</li>
 *   <li>No API keys, agent.json, world saves, or user config are included.</li>
 *   <li>Generated code comes only from controlled templates, not user input.</li>
 *   <li>The build runs in a background thread with a separate process.</li>
 * </ul>
 */
public final class ModExporter {

	/** Export request: what to export and where. */
	public record ExportRequest(
			String modId,        // e.g. "myawesome_mod" (validated: [a-z0-9_]{2,32})
			String modName,      // human-readable name
			String modVersion,   // e.g. "1.0.0"
			Path outputDir,      // where to generate the project
			List<String> itemIds, // WorldForge item IDs to export
			List<String> blockIds  // WorldForge block IDs to export
	) {}

	/** Export result. */
	public record ExportResult(
			boolean ok,
			Path projectDir,     // generated project root
			Path jarPath,         // built JAR (null if build failed)
			String sha256,        // JAR checksum (null if no JAR)
			String errorMessage,  // null on success
			List<String> warnings // unsupported features, etc.
	) {}

	/**
	 * Validate an export request before doing any work.
	 * Returns a list of error messages (empty = valid).
	 */
	public static List<String> validate(ExportRequest req) {
		List<String> errors = new ArrayList<>();
		if (req.modId() == null || !req.modId().matches("[a-z][a-z0-9_]{1,31}")) {
			errors.add("Mod ID 必须以小写字母开头，仅含小写字母/数字/下划线，2-32 字符");
		}
		if (req.modName() == null || req.modName().isBlank() || req.modName().length() > 64) {
			errors.add("Mod 名称不能为空且不超过 64 字符");
		} else if (req.modName().chars().anyMatch(c -> c < 0x20 && c != '\t' || c == ';' || c == '|' || c == '`' || c == '$' || c == '\n' || c == '\r')) {
			errors.add("Mod 名称包含不允许的特殊字符");
		}
		if (req.modVersion() == null || !req.modVersion().matches("\\d+\\.\\d+\\.\\d+")) {
			errors.add("版本号格式必须为 X.Y.Z");
		}
		if (req.outputDir() == null) {
			errors.add("输出目录不能为空");
		} else if (Files.exists(req.outputDir()) && Files.isDirectory(req.outputDir())) {
			try (var stream = Files.list(req.outputDir())) {
				if (stream.findAny().isPresent()) {
					errors.add("输出目录必须为空（避免覆盖已有文件）");
				}
			} catch (Exception e) {
				errors.add("无法检查输出目录: " + e.getMessage());
			}
		}
		if (req.itemIds() == null || req.blockIds() == null) {
			errors.add("物品和方块 ID 列表不能为 null");
			return errors; // can't continue validation with null lists
		}
		// validate each item ID exists and is exportable
		for (String id : req.itemIds()) {
			if (!isValidWorldForgeId(id)) {
				errors.add("无效的物品 ID: " + id);
				continue;
			}
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) {
				errors.add("物品定义不存在: " + id);
			}
		}
		for (String id : req.blockIds()) {
			if (!isValidWorldForgeId(id)) {
				errors.add("无效的方块 ID: " + id);
				continue;
			}
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) {
				errors.add("方块定义不存在: " + id);
			}
		}
		return errors;
	}

	/**
	 * Check for unsupported features and return warnings.
	 * The exporter never silently drops features — it warns.
	 */
	public static List<String> checkUnsupported(ExportRequest req) {
		List<String> warnings = new ArrayList<>();
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) continue;
			// check behaviors for unsupported actions
			for (var b : def.behaviors()) {
				for (String action : b.actions()) {
					if (!isSupportedItemAction(action)) {
						warnings.add("物品 " + id + " 的行为 '" + action
								+ "' 在导出 Mod 中不支持，将被跳过");
					}
				}
			}
		}
		for (String id : req.blockIds()) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) continue;
			for (var b : def.behaviors()) {
				for (String action : b.actions()) {
					if (!isSupportedBlockAction(action)) {
						warnings.add("方块 " + id + " 的行为 '" + action
								+ "' 在导出 Mod 中不支持，将被跳过");
					}
				}
			}
		}
		return warnings;
	}

	private static boolean isValidWorldForgeId(String id) {
		return id != null && id.matches("[a-z][a-z0-9_:./-]{2,127}");
	}

	private static boolean isSupportedItemAction(String action) {
		return switch (action) {
			case "apply_effect", "heal", "experience", "ignite",
				 "give_item", "sound", "particles", "summon_lightning" -> true;
			default -> false;
		};
	}

	private static boolean isSupportedBlockAction(String action) {
		return switch (action) {
			case "apply_effect", "heal", "experience", "ignite",
				 "give_item", "sound", "particles" -> true;
			default -> false;
		};
	}

	private ModExporter() {}
}
