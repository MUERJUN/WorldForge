package dev.worldforge.exporter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v0.5.6 P0-1: the single authority for turning WorldForge ids / mod ids into
 * every external symbol the export writes.
 *
 * <p>Six distinct mapping targets, never conflated:</p>
 * <ol>
 *   <li><b>Minecraft registry id</b> — namespace:path, path = safe segments</li>
 *   <li><b>Java package</b> — from mod id, legal package segments</li>
 *   <li><b>Java class name</b> — PascalCase, keyword-escaped, collision-checked</li>
 *   <li><b>Java field name</b> — camelCase with leading keyword/char rules</li>
 *   <li><b>Resource path</b> — assets/... segments</li>
 *   <li><b>File name</b> — on-disk source file names</li>
 * </ol>
 *
 * <p>Guarantees: deterministic (same input → same output, no locale tricks),
 * repeatable, and every name registration goes through {@link #registerClass}/
 * {@link #registerField} so collisions between DIFFERENT ids are detected
 * before any file is written. Inputs that cannot be mapped safely are
 * rejected with a clear Chinese error message.</p>
 */
public final class ExportSymbolMapper {

	/** Strict subset of Java reserved words that could collide with generated names. */
	private static final Set<String> JAVA_KEYWORDS = Set.of(
			"abstract","assert","boolean","break","byte","case","catch","char","class","const",
			"continue","default","do","double","else","enum","extends","final","finally","float",
			"for","goto","if","implements","import","instanceof","int","interface","long","native",
			"new","package","private","protected","public","return","short","static","strictfp",
			"super","switch","synchronized","this","throw","throws","transient","try","void",
			"volatile","while","var","record","sealed","permits","yield","null","true","false");

	/** Words that would collide with generated machinery or minecraft base classes. */
	private static final Set<String> RESERVED_SUFFIX_BASES = Set.of(
			"item","block","mod","items","blocks");

	private final Map<String, String> idToClass = new LinkedHashMap<>();
	private final Map<String, String> idToField = new LinkedHashMap<>();
	private final Map<String, String> classToId = new HashMap<>();
	private final Map<String, String> fieldToId = new HashMap<>();
	private boolean conflict = false;
	private final java.util.List<String> conflictDetails = new java.util.ArrayList<>();

	public static ExportSymbolMapper create() {
		return new ExportSymbolMapper();
	}

	// ------------------------------------------------------------ class names

	/**
	 * Register a unique class name for the given WorldForge id.
	 * Deterministic: non-alphanumeric separators (-, ., runs of _) are folded
	 * to single '_' then PascalCased per segment; collisions between distinct
	 * ids are disambiguated deterministically with _2, _3, ... and flagged.
	 */
	public String registerClass(String worldForgeId) {
		return register(worldForgeId, idToClass, classToId, true);
	}

	/** Register a unique field name for the given WorldForge id. */
	public String registerField(String worldForgeId) {
		return register(worldForgeId, idToField, fieldToId, false);
	}

	private String register(String id, Map<String, String> idMap,
			Map<String, String> reverse, boolean pascal) {
		var existing = idMap.get(id);
		if (existing != null) return existing;

		String base = identifierBase(nativeId(id), pascal);
		String candidate = base;
		int suffix = 2;
		// deterministic disambiguation for cross-separator collisions
		while (reverse.containsKey(candidate)) {
			conflict = true;
			conflictDetails.add("ID 冲突: '" + id + "' 与 '"
					+ reverse.get(candidate) + "' 映射到同一"
					+ (pascal ? "类名" : "字段名") + " '" + candidate + "'");
			candidate = base + "_" + suffix;
			suffix++;
		}
		idMap.put(id, candidate);
		reverse.put(candidate, id);
		return candidate;
	}

	/** True when two different ids were mapped to the same Java name. */
	public boolean hasConflict() {
		return conflict;
	}

	/** Human-readable collision report (empty when none). */
	public java.util.List<String> conflicts() {
		return java.util.List.copyOf(conflictDetails);
	}

	// ------------------------------------------------------- raw conversions

	/** Stateless convenience: deterministic class name (no collision check). */
	public static String className(String worldForgeId) {
		return identifierBase(nativeId(worldForgeId), true);
	}

	/** Stateless convenience: deterministic field name (no collision check). */
	public static String fieldName(String worldForgeId) {
		return identifierBase(nativeId(worldForgeId), false);
	}

	/** Minecraft native registry id (namespace stripped, path preserved). */
	public static String nativeId(String worldForgeId) {
		return worldForgeId.replace("worldforge:", "");
	}

	/**
	 * Build a legal Java identifier base from a native id path.
	 * - splits on every non-alphanumeric char (never just '_')
	 * - drops empty segments
	 * - PascalCase (class) or camelCase (field)
	 * - prefixes '_' when the result would start with a digit
	 * - escapes Java keywords with a trailing '_'
	 */
	static String identifierBase(String nativePath, boolean pascal) {
		if (nativePath == null || nativePath.isBlank()) {
			throw new IllegalArgumentException("ID 为空，无法生成 Java 标识符");
		}
		var parts = new java.util.ArrayList<String>();
		for (var seg : nativePath.split("[^a-zA-Z0-9]+")) {
			if (seg.isEmpty()) continue;
			parts.add(seg);
		}
		if (parts.isEmpty()) {
			throw new IllegalArgumentException(
					"ID '" + nativePath + "' 不含可映射字符（仅支持字母、数字、-、_、.）");
		}
		var sb = new StringBuilder();
		for (int i = 0; i < parts.size(); i++) {
			String p = parts.get(i);
			if (pascal || i > 0) {
				sb.append(Character.toUpperCase(p.charAt(0)));
				if (p.length() > 1) sb.append(p.substring(1));
			} else {
				sb.append(p);
			}
		}
		String result = sb.toString();
		if (Character.isDigit(result.charAt(0))) {
			result = "_" + result;
		}
		if (JAVA_KEYWORDS.contains(result)) {
			result = result + "_";
		}
		return result;
	}

	// ------------------------------------------------------------ package name

	/**
	 * Java package from mod id: split on '_' into package segments, empty
	 * segments dropped (so 'a_' -> 'a', 'a__b' -> 'a.b', never 'a.'/'a..b'),
	 * joined with '.'. 'my_mod' -> 'my.mod' — matches the historical layout.
	 */
	public static String packageName(String modId) {
		if (modId == null || modId.isBlank()) {
			throw new IllegalArgumentException("Mod ID 为空");
		}
		var segs = new java.util.ArrayList<String>();
		for (var seg : modId.split("_")) {
			if (seg.isEmpty()) continue;
			segs.add(seg);
		}
		if (segs.isEmpty()) {
			throw new IllegalArgumentException(
					"Mod ID '" + modId + "' 无法生成合法 Java 包名（仅含下划线）");
		}
		return String.join(".", segs);
	}

	// ------------------------------------------------------------ resource path

	/** Registry-safe resource path: '/'-separated [a-z0-9_.-] segments. */
	public static String resourcePath(String worldForgeId) {
		return nativeId(worldForgeId);
	}

	/** On-disk source file name (class + .java). */
	public static String sourceFile(String className) {
		return className + ".java";
	}

	// ------------------------------------------------------------ strict params

	/**
	 * v0.5.7 P1-1: the exact set of vanilla status effects in Minecraft
	 * 1.21.1 (extracted from StatusEffects of the 1.21.1 merged jar — not
	 * from memory). An effect outside this set cannot be honoured by the
	 * exported code (getEntry would return empty and the behavior would
	 * silently do nothing while still claiming SUCCESS) — reject at plan
	 * creation instead.
	 */
	public static final java.util.Set<String> VANILLA_EFFECTS_1_21_1 = java.util.Set.of(
			"speed", "slowness", "haste", "mining_fatigue", "strength",
			"instant_health", "instant_damage", "jump_boost", "nausea",
			"regeneration", "resistance", "fire_resistance", "water_breathing",
			"invisibility", "blindness", "night_vision", "hunger", "weakness",
			"poison", "wither", "health_boost", "absorption", "saturation",
			"glowing", "levitation", "luck", "unluck", "slow_falling",
			"conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village",
			"darkness", "trial_omen", "raid_omen", "wind_charged", "weaving",
			"oozing", "infested");

	/**
	 * v0.5.6 P0-2 / v0.5.7 P1-1: STRICT effect id validation — rejects
	 * instead of rewriting, and now also rejects syntactically-valid ids
	 * that are not real 1.21.1 vanilla effects.
	 */
	public static String strictEffectId(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("效果 ID 不能为空");
		}
		if (raw.contains(":")) {
			var ns = raw.substring(0, raw.indexOf(':'));
			throw new IllegalArgumentException(
					"状态效果使用了不支持的命名空间 '" + ns + ":'——导出模板仅支持 minecraft 原版效果，"
					+ "请改用如 speed / strength / fire_resistance 等原版 ID");
		}
		if (!raw.matches("[a-z0-9_]+")) {
			throw new IllegalArgumentException(
					"状态效果 ID '" + raw + "' 含不合法字符（仅允许小写字母、数字、下划线）");
		}
		if (!VANILLA_EFFECTS_1_21_1.contains(raw)) {
			throw new IllegalArgumentException(
					"状态效果 '" + raw + "' 不是 Minecraft 1.21.1 的原版效果，无法等价导出"
					+ "（原版效果示例: speed, strength, fire_resistance, poison）");
		}
		return raw;
	}

	/**
	 * v0.5.6 P0-2: precise integer conversion — fractional doubles are
	 * rejected (never silently truncated), NaN/Infinite rejected, values that
	 * do not fit in int rejected (Long.MAX_VALUE cannot wrap to legal),
	 * then range-checked.
	 */
	public static int preciseInt(String name, Object raw, int min, int max) {
		long value;
		if (raw instanceof Integer i) {
			value = i;
		} else if (raw instanceof Long l) {
			value = l;
			if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
				throw new IllegalArgumentException(
						"参数 " + name + " 超出 int 范围: " + l);
			}
		} else if (raw instanceof Double d) {
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				throw new IllegalArgumentException("参数 " + name + " 不是有限数值: " + d);
			}
			if (d != Math.rint(d)) {
				throw new IllegalArgumentException(
						"参数 " + name + " 必须是整数，收到小数 " + d + "（拒绝静默截断）");
			}
			value = d.longValue();
			if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
				throw new IllegalArgumentException(
						"参数 " + name + " 超出 int 范围: " + d);
			}
		} else if (raw instanceof Float f) {
			return preciseInt(name, (double) f.floatValue(), min, max);
		} else if (raw instanceof Number n) {
			// BigInteger / BigDecimal / other exotic JSON numbers
			var bd = new java.math.BigDecimal(n.toString());
			try {
				value = bd.longValueExact();
			} catch (ArithmeticException e) {
				throw new IllegalArgumentException(
						"参数 " + name + " 不是可精确表示的整数: " + n);
			}
			if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
				throw new IllegalArgumentException(
						"参数 " + name + " 超出 int 范围: " + n);
			}
		} else {
			throw new IllegalArgumentException(
					"参数 " + name + " 类型不支持: " + (raw == null ? "null" : raw.getClass().getSimpleName()));
		}
		if (value < min || value > max) {
			throw new IllegalArgumentException(
					"参数 " + name + " 超出范围 [" + min + ", " + max + "]: " + value);
		}
		return (int) value;
	}

	private ExportSymbolMapper() {}
}
