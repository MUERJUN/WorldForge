package dev.worldforge.ai;

import dev.worldforge.definition.AppearanceSlots;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.runtime.WorldForgeRuntimeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict, server-side validation of model proposals (spec 九).
 *
 * <p>Everything the model can say is checked against the SAME capability
 * surface the /wf commands and Studio GUI expose: known triggers, whitelisted
 * actions, numeric ranges identical to BehaviorEngine's runtime clamps,
 * identifier syntax, and the 15-slot vanilla texture catalog. Unknown
 * capabilities are reported as capability limits, never silently accepted.
 * A model claiming something unsupported must tell the player (spec 六).</p>
 */
public final class ProposalValidator {

	public record Validated(Proposal proposal, List<String> warnings, List<String> capabilityGaps) {
		public boolean ok() {
			return capabilityGaps.isEmpty() && warnings.stream().noneMatch(w -> w.startsWith("ERROR"));
		}
	}

	public record Errors(List<String> errors) {
		public boolean ok() {
			return errors.isEmpty();
		}

		public String joined() {
			return String.join("; ", errors);
		}
	}

	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-/]{1,128}");
	private static final List<String> TRIGGERS = List.of("right_click", "hit_entity");
	private static final List<String> ACTIONS = List.of(
			"summon_lightning", "apply_effect", "heal", "experience", "teleport", "explosion", "ignite");

	public static Errors validate(Proposal p) {
		List<String> errs = new ArrayList<>();
		switch (p) {
			case Proposal.Create c -> {
				checkId(c.id(), errs);
				checkName(c.name(), errs);
				checkTexture(c.texture(), errs);
				checkRange("damage", c.damage(), -1000, 1000, errs, true);
				checkRange("max_durability", c.maxDurability() == null ? null : c.maxDurability().doubleValue(),
						1, 65536, errs, false);
				checkRange("attack_speed", c.attackSpeed(), -10, 10, errs, true);
				checkBehaviors(c.behaviors(), errs);
				if (errs.isEmpty() && WorldForgeRuntimeManager.get(c.id()) != null) {
					errs.add("ERROR id already exists, use update instead of create: " + c.id());
				}
			}
			case Proposal.Update u -> {
				checkId(u.id(), errs);
				if (u.name() != null) {
					checkName(u.name(), errs);
				}
				if (u.texture() != null) {
					checkTexture(u.texture(), errs);
				}
				// v0.3.0 fix: on UPDATE every field is optional - null means
				// "keep the current value" (delta semantics). The old code
				// wrongly required max_durability on updates.
				checkRange("damage", u.damage(), -1000, 1000, errs, true);
				checkRange("max_durability", u.maxDurability() == null ? null : u.maxDurability().doubleValue(),
						1, 65536, errs, true);
				checkBehaviors(u.setBehaviors(), errs);
				checkBehaviors(u.addBehaviors(), errs);
				if (u.removeTriggers() != null) {
					for (String t : u.removeTriggers()) {
						if (t == null || !TRIGGERS.contains(t.toLowerCase(Locale.ROOT))) {
							errs.add("unknown remove trigger: " + t);
						}
					}
				}
				if (errs.isEmpty() && WorldForgeRuntimeManager.get(u.id()) == null) {
					errs.add("ERROR no such item to update: " + u.id());
				}
			}
			case Proposal.Delete d -> checkId(d.id(), errs);
			case Proposal.Query q -> {
				// read-only, nothing to validate
			}
		}
		return new Errors(errs);
	}

	/** detect requests the model mapped onto capabilities we do not have (e.g. block placement) */
	public static List<String> capabilityGaps(Proposal p) {
		List<String> gaps = new ArrayList<>();
		collectGaps(p, gaps);
		return gaps;
	}

	private static void collectGaps(Proposal p, List<String> gaps) {
		List<Proposal.BehaviorSpec> bs = switch (p) {
			case Proposal.Create c -> c.behaviors();
			case Proposal.Update u -> {
				var all = new ArrayList<Proposal.BehaviorSpec>();
				if (u.setBehaviors() != null) all.addAll(u.setBehaviors());
				if (u.addBehaviors() != null) all.addAll(u.addBehaviors());
				yield all;
			}
			default -> List.of();
		};
		if (bs == null) {
			return;
		}
		for (Proposal.BehaviorSpec b : bs) {
			if (b.action() != null && !ACTIONS.contains(b.action().toLowerCase(Locale.ROOT))) {
				gaps.add("动作 '" + b.action() + "' 暂不支持（当前支持: " + String.join("/", ACTIONS) + "）");
			}
			if (b.trigger() != null && !TRIGGERS.contains(b.trigger().toLowerCase(Locale.ROOT))) {
				gaps.add("触发器 '" + b.trigger() + "' 暂不支持（当前支持: right_click/hit_entity）");
			}
		}
	}

	private static void checkBehaviors(List<Proposal.BehaviorSpec> behaviors, List<String> errs) {
		if (behaviors == null) {
			return;
		}
		if (behaviors.size() > 8) {
			errs.add("too many behaviors (max 8): " + behaviors.size());
			return;
		}
		for (Proposal.BehaviorSpec b : behaviors) {
			if (b.trigger() == null || !TRIGGERS.contains(b.trigger().toLowerCase(Locale.ROOT))) {
				errs.add("unknown trigger: " + b.trigger());
			}
			if (b.action() == null || !ACTIONS.contains(b.action().toLowerCase(Locale.ROOT))) {
				errs.add("unknown action: " + b.action());
				continue;
			}
			if (b.cooldownTicks() != null && (b.cooldownTicks() < 0 || b.cooldownTicks() > 72000)) {
				errs.add("cooldown out of range [0,72000]: " + b.cooldownTicks());
			}
			checkActionParams(b, errs);
		}
	}

	private static void checkActionParams(Proposal.BehaviorSpec b, List<String> errs) {
		Map<String, Object> params = b.parameters() == null ? Map.of() : b.parameters();
		for (var e : params.entrySet()) {
			Object v = e.getValue();
			switch (e.getKey()) {
				case "amount", "power", "distance", "range", "seconds", "duration", "amplifier" -> {
					if (!(v instanceof Number n)) {
						errs.add("param " + e.getKey() + " must be a number");
					} else {
						double d = n.doubleValue();
						if (Double.isNaN(d) || Double.isInfinite(d)) {
							errs.add("param " + e.getKey() + " must be finite");
						} else {
							double hi = switch (e.getKey()) {
								case "power" -> 4.0;
								case "distance", "range" -> 64.0;
								case "duration" -> 72000.0;
								case "seconds" -> 10.0;
								case "amplifier" -> 10.0;
								default -> 1000.0; // amount
							};
							if (d < 0 || d > hi) {
								errs.add("param " + e.getKey() + " out of range [0," + hi + "]: " + d);
							}
						}
					}
				}
				case "effect" -> {
					if (!(v instanceof String s) || s.length() > 128) {
						errs.add("param effect must be a short id string");
					}
				}
				case "fire", "target" -> {
					// fire: boolean-ish; target: self|target
					if (e.getKey().equals("target") && !(v instanceof String s && (s.equals("self") || s.equals("target")))) {
						errs.add("param target must be self|target");
					}
				}
				default -> errs.add("unknown param: " + e.getKey());
			}
		}
	}

	private static void checkId(String id, List<String> errs) {
		if (id == null || !ID.matcher(id).matches()) {
			errs.add("invalid id syntax: " + id);
		}
	}

	private static void checkName(String name, List<String> errs) {
		if (name == null || name.isBlank() || name.length() > 128) {
			errs.add("name must be 1-128 chars");
		}
	}

	private static void checkTexture(String texture, List<String> errs) {
		if (texture == null || texture.isBlank()) {
			return; // defaults apply
		}
		String norm = AppearanceSlots.normalizeTexture(texture);
		boolean known = AppearanceSlots.slots().stream().anyMatch(s -> s.texture().equals(norm));
		if (!known) {
			errs.add("texture not in the supported vanilla slot catalog: " + texture);
		}
	}

	private static void checkRange(String what, Double v, double lo, double hi, List<String> errs, boolean nullable) {
		if (v == null) {
			if (!nullable) {
				errs.add(what + " is required");
			}
			return;
		}
		if (Double.isNaN(v) || Double.isInfinite(v) || v < lo || v > hi) {
			errs.add(what + " out of range [" + lo + "," + hi + "]: " + v);
		}
	}

	/** convert a validated spec into the runtime BehaviorDefinition */
	public static BehaviorDefinition toBehavior(Proposal.BehaviorSpec b) {
		return new BehaviorDefinition(b.trigger(), List.of(b.action()),
				b.parameters() == null ? Map.of() : b.parameters(),
				b.cooldownTicks() == null ? BehaviorDefinition.DEFAULT_COOLDOWN_TICKS : b.cooldownTicks());
	}

	private ProposalValidator() {
	}
}
