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
	 *
	 * <p>NOTE: this variant resolves definitions through the live runtime
	 * managers — only safe on the game thread. Background export threads must
	 * use {@link #validatePlanShape(ExportPlan)} which reads frozen plan data.</p>
	 */
	public static List<String> validate(ExportRequest req) {
		List<String> errors = validateMetadata(req.modId(), req.modName(), req.modVersion(), req.outputDir());
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
		// Check for registration ID conflicts
		// BlockItem also occupies the ITEM registry, so a standalone item
		// and a block item with the same native ID would conflict.
		var itemNativeIds = new java.util.HashSet<String>();
		for (String id : req.itemIds()) {
			if (isValidWorldForgeId(id) && WorldForgeRuntimeManager.get(id) != null) {
				var nativeId = nativeItemId(id);
				if (!itemNativeIds.add(nativeId)) {
					errors.add("物品 ID 冲突: " + nativeId + " 被多个物品使用");
				}
			}
		}
		for (String id : req.blockIds()) {
			if (isValidWorldForgeId(id) && BlockRuntimeManager.get(id) != null) {
				var nativeId = nativeBlockId(id);
				// Block occupies BLOCK registry
				// BlockItem occupies ITEM registry with the same native ID
				if (!itemNativeIds.add(nativeId)) {
					errors.add("注册 ID 冲突: 方块物品 " + nativeId
							+ " 与某个独立物品产生相同的 Item 注册 ID");
				}
			}
		}
		return errors;
	}

	/** Pure metadata/output-dir checks — no runtime lookups, thread-safe. */
	private static List<String> validateMetadata(String modId, String modName,
			String modVersion, Path outputDir) {
		List<String> errors = new ArrayList<>();
		if (modId == null || !modId.matches("[a-z][a-z0-9_]{1,31}")) {
			errors.add("Mod ID 必须以小写字母开头，仅含小写字母/数字/下划线，2-32 字符");
		}
		if (modName == null || modName.isBlank() || modName.length() > 64) {
			errors.add("Mod 名称不能为空且不超过 64 字符");
		} else if (modName.chars().anyMatch(c -> c < 0x20 && c != '\t' || c == ';' || c == '|' || c == '`' || c == '$' || c == '\n' || c == '\r')) {
			errors.add("Mod 名称包含不允许的特殊字符");
		}
		if (modVersion == null || !modVersion.matches("\\d+\\.\\d+\\.\\d+")) {
			errors.add("版本号格式必须为 X.Y.Z");
		}
		if (outputDir == null) {
			errors.add("输出目录不能为空");
		} else {
			// Reject symbolic links to prevent path traversal
			try {
				var resolved = outputDir.toAbsolutePath().normalize();
				if (Files.isSymbolicLink(resolved)) {
					errors.add("输出目录不能是符号链接");
				}
				// Check parent directories for symlinks too
				var parent = resolved.getParent();
				while (parent != null && !parent.equals(parent.getRoot())) {
					if (Files.isSymbolicLink(parent)) {
						errors.add("输出目录的父路径包含符号链接: " + parent);
						break;
					}
					parent = parent.getParent();
				}
			} catch (Exception e) {
				errors.add("无法检查输出目录路径: " + e.getMessage());
			}
			if (Files.exists(outputDir) && Files.isDirectory(outputDir)) {
				try (var stream = Files.list(outputDir)) {
					if (stream.findAny().isPresent()) {
						errors.add("输出目录必须为空（避免覆盖已有文件）");
					}
				} catch (Exception e) {
					errors.add("无法检查输出目录: " + e.getMessage());
				}
			}
		}
		return errors;
	}

	/**
	 * Extract native ID from WorldForge ID: "worldforge:my_sword" -> "my_sword".
	 * v0.5.5: the result is guaranteed to be a safe resource path — any id
	 * whose path segment would escape the assets directory ('..', '.', empty,
	 * absolute, or path-breaking characters) is normalized to a safe token
	 * derived from its safe characters; validateIdSafety reports the original
	 * as an error so plans containing such ids never export.
	 */
	static String nativeItemId(String worldForgeId) {
		var raw = worldForgeId.replace("worldforge:", "").replace(":", "_");
		// defensive normalization: keep only safe path characters per segment
		var sb = new StringBuilder();
		for (var seg : raw.split("/")) {
			var clean = seg.chars()
					.filter(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
							|| c == '_' || c == '-' || c == '.')
					.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
					.toString();
			if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) continue;
			if (sb.length() > 0) sb.append('_');
			sb.append(clean);
		}
		var safe = sb.toString();
		return safe.isBlank() ? "unnamed" : safe;
	}

	static String nativeBlockId(String worldForgeId) {
		return nativeItemId(worldForgeId);
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

	/**
	 * v0.5.5 capability contract: ONLY actions with a real code-generation
	 * implementation in TemplateProjectGenerator are "supported". Anything
	 * else (experience, give_item, ...) is reported unsupported and lost —
	 * never silently skipped while claiming support.
	 */
	private static boolean isSupportedItemAction(String action) {
		return switch (action) {
			case "apply_effect", "heal", "ignite",
				 "sound", "particles", "summon_lightning" -> true;
			default -> false;
		};
	}

	private static boolean isSupportedBlockAction(String action) {
		return switch (action) {
			case "apply_effect", "heal", "ignite",
				 "sound", "particles", "summon_lightning" -> true;
			default -> false;
		};
	}

	/** Capability report: what is retained, converted, and lost in the export. */
	public record CapabilityReport(
			List<String> retained,   // capabilities fully implemented in exported mod
			List<String> converted,   // capabilities with a different but equivalent implementation
			List<String> lost         // capabilities that cannot be exported
	) {
		public static CapabilityReport empty() {
			return new CapabilityReport(List.of(), List.of(), List.of());
		}
	}

	/**
	 * Build a capability report for the export request.
	 * Lists every behavior action and whether it is retained, converted, or lost.
	 */
	public static CapabilityReport buildCapabilityReport(ExportRequest req) {
		List<String> retained = new java.util.ArrayList<>();
		List<String> converted = new java.util.ArrayList<>();
		List<String> lost = new java.util.ArrayList<>();
		for (String id : req.itemIds()) {
			ItemDefinition def = WorldForgeRuntimeManager.get(id);
			if (def == null) continue;
			for (var b : def.behaviors()) {
				for (String action : b.actions()) {
					String desc = "item " + id + ": " + action + " (trigger=" + b.trigger() + ")";
					if (isSupportedItemAction(action)) {
						retained.add(desc);
					} else {
						lost.add(desc);
					}
				}
			}
		}
		for (String id : req.blockIds()) {
			BlockDefinition def = BlockRuntimeManager.get(id);
			if (def == null) continue;
			for (var b : def.behaviors()) {
				for (String action : b.actions()) {
					String desc = "block " + id + ": " + action + " (trigger=" + b.trigger() + ")";
					if (isSupportedBlockAction(action)) {
						retained.add(desc);
					} else {
						lost.add(desc);
					}
				}
			}
		}
		return new CapabilityReport(
				java.util.List.copyOf(retained),
				java.util.List.copyOf(converted),
				java.util.List.copyOf(lost));
	}

	// ------------------------------------------------------- plan-based API

	/**
	 * v0.5.3 P0-1: Convert a plan to its equivalent request (no runtime lookups).
	 */
	public static ExportRequest toRequest(ExportPlan plan) {
		return new ExportRequest(plan.modId(), plan.modName(), plan.version(),
				plan.outputDir() == null ? null : java.nio.file.Path.of(plan.outputDir()),
				plan.items().stream().map(ItemDefinition::id).toList(),
				plan.blocks().stream().map(BlockDefinition::id).toList());
	}

	/**
	 * v0.5.4 P0: Validate a plan purely from its FROZEN data — metadata,
	 * output dir, and registration ID conflicts computed from the plan's own
	 * deep-copied definitions. Never queries runtime managers; safe on any
	 * thread and immune to live definition edits/deletes/world switches.
	 */
	public static List<String> validatePlanShape(ExportPlan plan) {
		var errors = validateMetadata(plan.modId(), plan.modName(), plan.version(),
				plan.outputDir() == null ? null : java.nio.file.Path.of(plan.outputDir()));
		if (plan.items() == null || plan.blocks() == null) {
			errors.add("方案缺少物品或方块定义");
			return errors;
		}
		// Definitions in a plan exist by construction (create() rejected
		// missing ones), so only format + conflict checks remain.
		var usedNativeIds = new java.util.HashSet<String>();
		for (var def : plan.items()) {
			if (def == null || !isValidWorldForgeId(def.id())) {
				errors.add("方案包含无效物品定义");
				continue;
			}
			var nativeId = nativeItemId(def.id());
			if (!usedNativeIds.add(nativeId)) {
				errors.add("物品 ID 冲突: " + nativeId + " 被多个物品使用");
			}
		}
		for (var def : plan.blocks()) {
			if (def == null || !isValidWorldForgeId(def.id())) {
				errors.add("方案包含无效方块定义");
				continue;
			}
			var nativeId = nativeBlockId(def.id());
			// Block occupies BLOCK registry; its BlockItem occupies ITEM
			if (!usedNativeIds.add(nativeId)) {
				errors.add("注册 ID 冲突: 方块物品 " + nativeId
						+ " 与某个独立物品产生相同的 Item 注册 ID");
			}
		}
		return errors;
	}

	/**
	 * v0.5.4 P0: Capability report computed from the given (frozen) definitions
	 * — no runtime lookups. Used by ExportPlan.create so the report the user
	 * confirms is derived from exactly the snapshot that will be exported.
	 */
	public static CapabilityReport capabilityReportOf(List<ItemDefinition> items,
			List<BlockDefinition> blocks) {
		List<String> retained = new java.util.ArrayList<>();
		List<String> converted = new java.util.ArrayList<>();
		List<String> lost = new java.util.ArrayList<>();
		for (var def : items) {
			if (def == null) continue;
			// v0.5.5: item attributes (attack/armor/etc.) have no exported
			// equivalent — they must surface in the report, never vanish.
			if (def.attributes() != null && !def.attributes().isEmpty()) {
				lost.add("item " + def.id() + ": 属性 " + def.attributes().keySet()
						+ " 在导出 Mod 中没有等价实现");
			}
			for (var b : def.behaviors()) {
				boolean triggerOk = isTriggerExportable(b.trigger());
				for (String action : b.actions()) {
					String desc = "item " + def.id() + ": " + action + " (trigger=" + b.trigger() + ")";
					if (!isSupportedItemAction(action)) {
						lost.add(desc + " — 无代码生成实现");
					} else if (!triggerOk) {
						// v0.5.5: hit_entity etc. must NOT be folded into right-click
						lost.add(desc + " — 触发器 '" + b.trigger() + "' 在导出 Mod 中未实现（不会并入右键）");
					} else {
						retained.add(desc);
					}
				}
			}
		}
		for (var def : blocks) {
			if (def == null) continue;
			// v0.5.5: periodic (tick-driven) blocks have no exported equivalent —
			// an equal export is impossible and must be reported as lost.
			if (def.category() == BlockDefinition.Category.PERIODIC) {
				lost.add("block " + def.id() + ": 周期行为（每 " + def.tickIntervalTicks()
						+ " ticks 产出 " + def.outputItemId() + "）在导出 Mod 中没有等价实现");
			}
			for (var b : def.behaviors()) {
				boolean triggerOk = isTriggerExportable(b.trigger());
				for (String action : b.actions()) {
					String desc = "block " + def.id() + ": " + action + " (trigger=" + b.trigger() + ")";
					if (!isSupportedBlockAction(action)) {
						lost.add(desc + " — 无代码生成实现");
					} else if (!triggerOk) {
						lost.add(desc + " — 触发器 '" + b.trigger() + "' 在导出 Mod 中未实现（不会并入右键）");
					} else {
						retained.add(desc);
					}
				}
			}
		}
		return new CapabilityReport(
				java.util.List.copyOf(retained),
				java.util.List.copyOf(converted),
				java.util.List.copyOf(lost));
	}

	/** The plan's resolved output directory. */
	public static java.nio.file.Path planOutputDir(ExportPlan plan) {
		return java.nio.file.Path.of(plan.outputDir());
	}

	// ------------------------------------------------ v0.5.5 audit contract

	/** Public read-only view of item action support (audit/test use). */
	public static boolean isSupportedItemActionPublic(String action) {
		return isSupportedItemAction(action);
	}

	/**
	 * v0.5.5: a trigger is exportable only when the generator implements it.
	 * Right-click ("on_use"/"use") is the one implemented item trigger;
	 * hit_entity and any other trigger have no code generation and must be
	 * reported as unsupported instead of being silently folded into right-click.
	 */
	public static boolean isTriggerExportable(String trigger) {
		var t = trigger == null ? "" : trigger.trim().toLowerCase(java.util.Locale.ROOT);
		return t.equals("on_use") || t.equals("use") || t.equals("right_click");
	}

	/**
	 * v0.5.5: validate pure ID safety from frozen strings — path traversal
	 * segments, absolute escapes, and empty paths are rejected. Safe IDs pass.
	 */
	public static List<String> validateIdSafety(List<String> itemIds, List<String> blockIds) {
		List<String> errors = new ArrayList<>();
		var all = new ArrayList<String>();
		if (itemIds != null) all.addAll(itemIds);
		if (blockIds != null) all.addAll(blockIds);
		for (String id : all) {
			// check the RAW id — nativeItemId normalizes, but a plan containing
			// a traversal id must be rejected outright, not silently renamed
			var raw = id.replace("worldforge:", "").replace(":", "_");
			if (!isSafeResourcePath(raw)) {
				errors.add("ID 存在不安全的资源路径段: " + id + " -> " + raw);
			}
		}
		return errors;
	}

	/**
	 * v0.5.5: a resource path is safe when every segment is a plain
	 * [a-z0-9_] token — no '..', no '.', no '/'-led tricks, non-empty.
	 */
	static boolean isSafeResourcePath(String path) {
		if (path == null || path.isBlank()) return false;
		if (path.startsWith("/") || path.contains("//")) return false;
		for (var seg : path.split("/")) {
			if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return false;
			// letters/digits/_/-/dot are safe INSIDE a segment; the danger is
			// a whole segment being '.' or '..', or path-breaking characters
			if (!seg.matches("[a-z0-9_.-]+")) return false;
		}
		return true;
	}

	/**
	 * v0.5.5: sanitize a Minecraft effect identifier parameter — only
	 * [a-z0-9_] survives; everything that would break out of the generated
	 * Java string is stripped.
	 */
	public static String sanitizeEffectId(String raw) {
		if (raw == null) return "";
		var filtered = raw.chars()
				.filter(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
				.toString();
		return filtered.isBlank() ? "speed" : filtered;
	}

	/**
	 * v0.5.5: sanitize a numeric behavior parameter — must parse as an int
	 * within [min, max]; anything else (text injection, absurd bounds,
	 * negatives) is rejected loudly instead of inlined raw.
	 */
	public static int sanitizePositiveInt(String name, Object raw, int min, int max) {
		int value;
		try {
			value = raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
		} catch (Exception e) {
			throw new IllegalArgumentException("参数 " + name + " 不是有效整数: " + raw);
		}
		if (value < min || value > max) {
			throw new IllegalArgumentException(
					"参数 " + name + " 超出范围 [" + min + ", " + max + "]: " + value);
		}
		return value;
	}

	/**
	 * v0.5.5: degraded-export guard for the EXECUTOR (not GUI-only).
	 * Returns an error message when the plan has lost capabilities but was
	 * not explicitly confirmed; null when export may proceed.
	 */
	public static String degradedGuardError(CapabilityReport report, boolean confirmed) {
		if (report.lost().isEmpty()) return null;
		return confirmed ? null
				: "检测到 " + report.lost().size() + " 项能力无法导出，且未经用户确认降级。"
				+ "已阻止导出（能力丢失: " + String.join("; ", report.lost()) + "）";
	}

	/**
	 * v0.5.5: pure decision for which wrapper script to use — must follow the
	 * OS, never file existence order (a Linux run must not pick gradlew.bat
	 * just because copyGradleWrapper laid both files down).
	 */
	public static String gradleCommandFor(boolean windows, boolean batExists, boolean shExists) {
		if (windows && batExists) return "bat";
		if (!windows && shExists) return "sh";
		if (batExists) return "bat";
		if (shExists) return "sh";
		return "system";
	}

	private ModExporter() {}
}
