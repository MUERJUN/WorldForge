package dev.worldforge.behavior;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.definition.BehaviorDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * v0.7.3: server-side policy that separates "can modify own item" from
 * "can execute a behaviour in this world".
 *
 * <p>The SELF permission on {@code graft_behavior} only answers the first
 * question: a player may graft a behaviour onto their own held stack. It
 * does NOT answer the second: whether the player may actually cause a
 * lightning strike, an explosion, or set another entity on fire.</p>
 *
 * <p>This policy is checked at EXECUTION time (when the behaviour fires),
 * not at graft time. If the server admin changes the policy, already-grafted
 * behaviours are re-checked against the new policy on their next fire.</p>
 *
 * <h2>Default policy</h2>
 *
 * <ul>
 *   <li><b>Always allowed</b> (safe, self-only): {@code heal}, {@code experience}</li>
 *   <li><b>Conditionally allowed</b>: {@code apply_effect} only when
 *       {@code target=self} (the default); {@code teleport} (self-only)</li>
 *   <li><b>Always restricted</b> (dangerous, world-changing or PvP):
 *       {@code summon_lightning}, {@code explosion}, {@code ignite},
 *       {@code apply_effect} with {@code target=target}</li>
 * </ul>
 *
 * <h2>Server configuration</h2>
 *
 * <p>Admins may override the defaults via
 * {@code config/worldforge/behavior-policy.json}:</p>
 *
 * <pre>{@code
 * {
 *   "allowedActions": ["heal", "experience", "teleport", "summon_lightning"],
 *   "restrictedActions": []
 * }
 * }</pre>
 *
 * <p>An action in {@code allowedActions} is always permitted. An action in
 * {@code restrictedActions} is always denied. If neither list contains an
 * action, the default policy applies. The default policy never grants an
 * action that the default policy would deny — an admin must explicitly
 * add it to {@code allowedActions}.</p>
 */
public final class BehaviorPolicy {

	/** The default set of actions that are always safe to execute. */
	private static final Set<String> SAFE_ACTIONS = Set.of(
			"heal", "experience", "teleport");

	/** The default set of actions that are always dangerous. */
	private static final Set<String> DANGEROUS_ACTIONS = Set.of(
			"summon_lightning", "explosion", "ignite");

	/** Actions that are conditionally safe based on parameters. */
	private static final Set<String> CONDITIONAL_ACTIONS = Set.of(
			"apply_effect");

	/** The built-in default: safe actions allowed, dangerous actions restricted. */
	public static final BehaviorPolicy DEFAULT = new BehaviorPolicy(
			Set.of(), Set.of());

	private static volatile BehaviorPolicy current = DEFAULT;

	private final Set<String> serverAllowed;
	private final Set<String> serverRestricted;

	private BehaviorPolicy(Set<String> serverAllowed, Set<String> serverRestricted) {
		this.serverAllowed = serverAllowed;
		this.serverRestricted = serverRestricted;
	}

	/** Creates a policy with explicit server-side allow/restrict lists. */
	public static BehaviorPolicy of(Set<String> serverAllowed, Set<String> serverRestricted) {
		return new BehaviorPolicy(serverAllowed, serverRestricted);
	}

	/**
	 * Returns the currently active policy.
	 *
	 * <p>This is read at EXECUTION time, so a server-side policy change takes
	 * effect on the next behaviour fire — already-grafted behaviours are
	 * re-checked against the new policy.</p>
	 */
	public static BehaviorPolicy get() {
		return current;
	}

	/** Replaces the current policy (for testing or server reload). */
	public static void set(BehaviorPolicy policy) {
		current = policy == null ? DEFAULT : policy;
	}

	/**
	 * v0.7.4: reloads the policy from the config file and swaps it in ONLY
	 * when the file parses and validates completely. Called from the
	 * {@code /wf policy reload} command (OP2) on the server thread.
	 *
	 * <p>On any failure the LAST VALID policy stays in force and {@code false}
	 * is returned, so a broken edit can never silently relax or break the
	 * active restrictions. A missing file falls back to the safe default —
	 * an explicit operator action, so that is treated as intentional.</p>
	 *
	 * @return true when a new policy is now active
	 */
	public static boolean reload(Path gameDir) {
		Path file = gameDir.resolve("config/worldforge/behavior-policy.json");
		if (!Files.exists(file)) {
			current = DEFAULT;
			dev.worldforge.WorldForge.LOGGER.info(
					"[WorldForge] policy file missing; the safe default policy is now active");
			return true;
		}
		try {
			String json = Files.readString(file);
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			Set<String> allowed = stringSet(o, "allowedActions");
			Set<String> restricted = stringSet(o, "restrictedActions");
			current = new BehaviorPolicy(allowed, restricted);
			dev.worldforge.WorldForge.LOGGER.info(
					"[WorldForge] policy reloaded: {} allowed, {} restricted by config",
					allowed.size(), restricted.size());
			return true;
		} catch (Exception e) {
			// keep the last valid policy — never fail open
			dev.worldforge.WorldForge.LOGGER.error(
					"[WorldForge] policy reload failed ({}); the last valid policy stays in force",
					e.getMessage());
			return false;
		}
	}

	/**
	 * Loads the policy from {@code config/worldforge/behavior-policy.json}.
	 *
	 * <p>v0.7.4 failure semantics: a file that does not exist gets a template
	 * and the DEFAULT (safe) policy. A file that exists but cannot be parsed
	 * or has an unexpected structure NEVER relaxes security — the LAST VALID
	 * policy stays in force (on first startup that is the safe default), the
	 * problem is logged, and the operator is expected to fix the file and
	 * reload.</p>
	 */
	public static BehaviorPolicy load(Path gameDir) {
		Path file = gameDir.resolve("config/worldforge/behavior-policy.json");
		if (!Files.exists(file)) {
			createTemplate(file);
			return DEFAULT;
		}
		try {
			String json = Files.readString(file);
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			Set<String> allowed = stringSet(o, "allowedActions");
			Set<String> restricted = stringSet(o, "restrictedActions");
			return new BehaviorPolicy(allowed, restricted);
		} catch (Exception e) {
			// NEVER fall back to a MORE permissive policy than the one that is
			// already in force. On first startup `current` is the safe default.
			dev.worldforge.WorldForge.LOGGER.error(
					"[WorldForge] behavior-policy.json is invalid ({}); keeping the LAST VALID policy",
					e.getMessage());
			return current;
		}
	}

	/** Creates a template config file with documented defaults. */
	private static void createTemplate(Path file) {
		try {
			Files.createDirectories(file.getParent());
			// distinct keys — duplicate JSON keys are invalid and break parsers
			String tpl = """
					{
					  "_doc": "WorldForge v0.7.4 行为执行策略：分离『能否修改自己物品』与『能否在世界中执行行为』。restrictedActions 优先于 allowedActions；两个列表都不含的动作按内置默认策略处理（安全动作允许、危险动作拒绝、未知动作拒绝）。",
					  "_actions": "可用动作: heal, experience, teleport, apply_effect, summon_lightning, explosion, ignite",
					  "_defaults": "默认允许: heal, experience, teleport, apply_effect(仅 target=self)。默认拒绝: summon_lightning, explosion, ignite, apply_effect(对其他实体)。",
					  "_reload": "修改本文件后运行 /wf policy reload (OP2) 生效；解析失败时保留上一次有效策略。",
					  "allowedActions": [],
					  "restrictedActions": []
					}
					""";
			Files.writeString(file, tpl, java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// best-effort: the default policy is safe without the file
		}
	}

	/**
	 * Reads a string-array field. A MISSING field is an empty set; a field
	 * that exists with the WRONG type is a config error and throws, so the
	 * caller keeps the last valid policy instead of silently loosening it.
	 */
	private static Set<String> stringSet(JsonObject o, String key) {
		Set<String> out = new LinkedHashSet<>();
		if (!o.has(key)) {
			return out;
		}
		JsonElement el = o.get(key);
		if (!el.isJsonArray()) {
			throw new IllegalArgumentException(key + " must be an array of strings");
		}
		for (JsonElement e : el.getAsJsonArray()) {
			if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
				throw new IllegalArgumentException(key + " must contain only strings");
			}
			out.add(e.getAsString().toLowerCase(Locale.ROOT));
		}
		return out;
	}

	/**
	 * True when the player may execute this action in the current world.
	 *
	 * <p>The check is done at EXECUTION time, so a server-side policy change
	 * takes effect immediately — even for behaviours that were grafted before
	 * the change.</p>
	 *
	 * @param action the action name (lowercase)
	 * @param b the behaviour definition (needed for parameter inspection)
	 * @return true if the action may execute
	 */
	public boolean canExecute(String action, BehaviorDefinition b) {
		action = action.toLowerCase(Locale.ROOT);

		// v0.7.4: RESTRICTED takes priority over ALLOWED. A denied action
		// stays denied even when a (mis-edited) config lists it in both
		// arrays — fail-closed beats fail-open.
		if (serverRestricted.contains(action)) {
			return false;
		}
		if (serverAllowed.contains(action)) {
			return true;
		}

		// default policy
		if (SAFE_ACTIONS.contains(action)) {
			return true;
		}
		if (DANGEROUS_ACTIONS.contains(action)) {
			return false;
		}
		if (CONDITIONAL_ACTIONS.contains(action)) {
			// apply_effect is safe only when targeting self
			return "self".equals(b.paramString("target", "self"));
		}
		// unknown actions are restricted by default
		return false;
	}

	/** The actions that are restricted by the current policy. */
	public Set<String> restrictedActions() {
		Set<String> out = new LinkedHashSet<>(DANGEROUS_ACTIONS);
		out.addAll(serverRestricted);
		return out;
	}

	/** The actions that are always allowed by the current policy. */
	public Set<String> allowedActions() {
		Set<String> out = new LinkedHashSet<>(SAFE_ACTIONS);
		out.addAll(serverAllowed);
		return out;
	}
}
