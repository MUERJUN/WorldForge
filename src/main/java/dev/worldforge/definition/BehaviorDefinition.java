package dev.worldforge.definition;

import java.util.List;
import java.util.Map;

/**
 * One declarative behaviour rule: when {@code trigger} fires, run each
 * {@code action} (parameterised), respecting {@code cooldownTicks}.
 *
 * <p>Triggers and actions are an explicit whitelist dispatched by the
 * behaviour engine - no arbitrary code execution (spec section 5).</p>
 */
public record BehaviorDefinition(
		String trigger,
		List<String> actions,
		Map<String, Object> parameters,
		int cooldownTicks) {

	public static final int DEFAULT_COOLDOWN_TICKS = 20;

	public BehaviorDefinition {
		if (actions == null || actions.isEmpty()) {
			actions = List.of();
		}
		if (parameters == null) {
			parameters = Map.of();
		}
		if (cooldownTicks < 0) {
			cooldownTicks = DEFAULT_COOLDOWN_TICKS;
		}
	}

	/** Convenience for single-action rules. */
	public BehaviorDefinition(String trigger, String action, int cooldownTicks, Map<String, Object> parameters) {
		this(trigger, List.of(action), parameters, cooldownTicks);
	}

	public String trigger() {
		return trigger == null ? "" : trigger.toLowerCase(java.util.Locale.ROOT);
	}

	public int cooldownTicks() {
		return cooldownTicks < 0 ? DEFAULT_COOLDOWN_TICKS : Math.min(cooldownTicks, 20 * 60 * 60);
	}

	// ---- parameter helpers ----

	public double paramDouble(String key, double def) {
		Object v = parameters.get(key);
		return v instanceof Number n ? n.doubleValue() : def;
	}

	public int paramInt(String key, int def) {
		Object v = parameters.get(key);
		return v instanceof Number n ? n.intValue() : def;
	}

	public String paramString(String key, String def) {
		Object v = parameters.get(key);
		return v instanceof String s ? s : def;
	}

	public boolean paramBool(String key, boolean def) {
		Object v = parameters.get(key);
		return v instanceof Boolean b ? b : def;
	}
}
