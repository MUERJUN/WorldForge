package dev.worldforge.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Definition validation (spec 3.2: 校验 + 合理默认值 + 错误处理).
 *
 * <p>Validation is deliberately texture-tolerant: an unusable texture
 * downgrades to the default slot instead of failing creation
 * ("贴图生成失败不等于物品创建失败").</p>
 */
public final class DefinitionValidator {

	public record Result(List<String> errors, List<String> warnings) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	private static final List<String> KNOWN_TRIGGERS = List.of("right_click", "hit_entity");
	private static final List<String> KNOWN_ACTIONS = List.of(
			"summon_lightning", "apply_effect", "heal", "teleport", "explosion",
			"experience", "ignite", "damage");

	public static Result validate(ItemDefinition def) {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		if (def.id() == null || def.id().isBlank()) {
			errors.add("id must not be blank");
		} else if (!def.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
			errors.add("id must be a valid namespace:path (lowercase), got: " + def.id());
		}

		if (def.name() == null || def.name().isBlank()) {
			warnings.add("name is blank; display will fall back to the id");
		}

		if (def.version() != ItemDefinition.CURRENT_VERSION) {
			warnings.add("definition version " + def.version() + " differs from current " + ItemDefinition.CURRENT_VERSION);
		}

		if (def.damage() < -1000 || def.damage() > 1000) {
			errors.add("damage out of sane range [-1000, 1000]: " + def.damage());
		}
		if (def.maxDurability() <= 0 || def.maxDurability() > 65536) {
			errors.add("max_durability out of sane range (0, 65536]: " + def.attributes().get("max_durability"));
		}

		if (def.texture() != null && !def.texture().isKnownSource()) {
			warnings.add("unknown texture source '" + def.texture().source() + "'; falling back to vanilla default");
		}

		if (def.behaviors().isEmpty()) {
			warnings.add("no behaviors defined; item is decorative only");
		}
		for (BehaviorDefinition b : def.behaviors()) {
			if (b.trigger().isEmpty()) {
				errors.add("behavior with empty trigger");
				continue;
			}
			if (!KNOWN_TRIGGERS.contains(b.trigger())) {
				warnings.add("unknown trigger '" + b.trigger() + "' will never fire");
			}
			if (b.actions().isEmpty()) {
				errors.add("behavior '" + b.trigger() + "' has no actions");
			}
			for (String a : b.actions()) {
				if (!KNOWN_ACTIONS.contains(a.toLowerCase(Locale.ROOT))) {
					warnings.add("unknown action '" + a + "' will be skipped at runtime");
				}
			}
		}
		return new Result(errors, warnings);
	}

	private DefinitionValidator() {
	}
}
