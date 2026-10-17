package dev.worldforge.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsing of model JSON into proposals, and merging proposals into concrete
 * definitions (delta semantics: unset fields keep current values - spec 四.2
 * and test 7 "修改提案保留未涉及的原属性").
 *
 * <p>Accepts the fenced/raw JSON object the model returns; anything that is
 * not exactly one proposal object fails with a reason instead of throwing.
 * Hostile inputs (huge strings, wrong types, nested garbage) are bounded
 * here, before any validation or execution.</p>
 */
public final class ProposalJson {

	public record ParseResult(Proposal proposal, String error) {
		public boolean ok() {
			return proposal != null;
		}
	}

	private static final int MAX_JSON_CHARS = 32 * 1024;

	public static ParseResult parse(String modelJson) {
		if (modelJson == null || modelJson.isBlank()) {
			return new ParseResult(null, "模型未返回内容");
		}
		String s = extractJsonObject(modelJson);
		if (s == null) {
			return new ParseResult(null, "模型响应中未找到 JSON 对象");
		}
		if (s.length() > MAX_JSON_CHARS) {
			return new ParseResult(null, "提案 JSON 过大");
		}
		JsonElement el;
		try {
			el = JsonParser.parseString(s);
		} catch (Exception e) {
			return new ParseResult(null, "非法 JSON: " + e.getClass().getSimpleName());
		}
		if (!el.isJsonObject()) {
			return new ParseResult(null, "提案不是 JSON object");
		}
		JsonObject o = el.getAsJsonObject();
		String kind = str(o, "kind");
		if (kind == null) {
			return new ParseResult(null, "缺少 kind 字段");
		}
		try {
			return switch (kind) {
				case "create" -> new ParseResult(parseCreate(o), null);
				case "update" -> new ParseResult(parseUpdate(o), null);
				case "delete" -> new ParseResult(new Proposal.Delete(str(o, "id")), null);
				case "query" -> new ParseResult(new Proposal.Query(str(o, "what")), null);
				default -> new ParseResult(null, "未知 kind: " + kind);
			};
		} catch (Exception e) {
			return new ParseResult(null, "提案字段类型错误: " + e.getClass().getSimpleName());
		}
	}

	/** tolerate ```json fences and prose around the object (models love that) */
	static String extractJsonObject(String text) {
		int fence = text.indexOf("```");
		if (fence >= 0) {
			int nl = text.indexOf('\n', fence);
			int end = text.indexOf("```", fence + 3);
			if (nl > 0 && end > nl) {
				text = text.substring(nl + 1, end);
			}
		}
		int start = text.indexOf('{');
		if (start < 0) {
			return null;
		}
		int depth = 0;
		boolean inStr = false, esc = false;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inStr) {
				if (esc) {
					esc = false;
				} else if (c == '\\') {
					esc = true;
				} else if (c == '"') {
					inStr = false;
				}
				continue;
			}
			if (c == '"') {
				inStr = true;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return text.substring(start, i + 1);
				}
			}
		}
		return null; // unbalanced -> reject (truncated response)
	}

	private static Proposal.Create parseCreate(JsonObject o) {
		return new Proposal.Create(str(o, "id"), str(o, "name"), str(o, "description"),
				str(o, "texture"), num(o, "damage"), intg(o, "max_durability"), num(o, "attack_speed"),
				behaviors(o));
	}

	private static Proposal.Update parseUpdate(JsonObject o) {
		JsonObject patch = o.has("patch") && o.get("patch").isJsonObject()
				? o.getAsJsonObject("patch") : o;
		List<Proposal.BehaviorSpec> setB = null;
		if (patch.has("behaviors") && patch.get("behaviors").isJsonArray()) {
			setB = behaviorsFrom(patch);
		}
		List<Proposal.BehaviorSpec> addB = null;
		if (patch.has("add_behaviors") && patch.get("add_behaviors").isJsonArray()) {
			addB = parseBehaviorArray(patch.getAsJsonArray("add_behaviors"));
		}
		List<String> rem = null;
		if (patch.has("remove_triggers") && patch.get("remove_triggers").isJsonArray()) {
			rem = new ArrayList<>();
			for (var el : patch.getAsJsonArray("remove_triggers")) {
				rem.add(el.isJsonPrimitive() ? el.getAsString() : null);
			}
		}
		return new Proposal.Update(str(patch, "id"), str(patch, "name"), str(patch, "description"),
				str(patch, "texture"), num(patch, "damage"), intg(patch, "max_durability"),
				setB, addB, rem);
	}

	private static List<Proposal.BehaviorSpec> behaviorsFrom(JsonObject o) {
		return parseBehaviorArray(o.getAsJsonArray("behaviors"));
	}

	private static List<Proposal.BehaviorSpec> behaviors(JsonObject o) {
		if (!o.has("behaviors") || !o.get("behaviors").isJsonArray()) {
			return null;
		}
		return behaviorsFrom(o);
	}

	private static List<Proposal.BehaviorSpec> parseBehaviorArray(com.google.gson.JsonArray arr) {
		List<Proposal.BehaviorSpec> out = new ArrayList<>();
		for (JsonElement el : arr) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject b = el.getAsJsonObject();
			Map<String, Object> params = new LinkedHashMap<>();
			if (b.has("parameters") && b.get("parameters").isJsonObject()) {
				for (var e2 : b.getAsJsonObject("parameters").entrySet()) {
					JsonElement v = e2.getValue();
					if (v.isJsonNull()) {
						continue;
					}
					if (v.getAsJsonPrimitive().isBoolean()) {
						params.put(e2.getKey(), v.getAsBoolean());
					} else if (v.getAsJsonPrimitive().isNumber()) {
						params.put(e2.getKey(), v.getAsDouble());
					} else {
						String s2 = v.getAsString();
						if (s2.length() > 128) {
							s2 = s2.substring(0, 128);
						}
						params.put(e2.getKey(), s2);
					}
				}
			}
			out.add(new Proposal.BehaviorSpec(str(b, "trigger"), str(b, "action"),
					intg(b, "cooldown_ticks"), params));
		}
		return out;
	}

	// ---------- merge into definitions ----------

	/** build a full ItemDefinition from a validated create proposal */
	public static ItemDefinition toDefinition(Proposal.Create c) {
		List<BehaviorDefinition> behaviors = new ArrayList<>();
		if (c.behaviors() != null) {
			c.behaviors().forEach(b -> behaviors.add(ProposalValidator.toBehavior(b)));
		}
		Map<String, Double> attrs = new LinkedHashMap<>();
		attrs.put("damage", c.damage() == null ? 0.0 : c.damage());
		attrs.put("max_durability", c.maxDurability() == null ? 100.0 : c.maxDurability().doubleValue());
		if (c.attackSpeed() != null) {
			attrs.put("attack_speed", c.attackSpeed());
		}
		return new ItemDefinition(ItemDefinition.CURRENT_VERSION, c.id(),
				c.name() == null ? c.id() : c.name(),
				c.description() == null ? "" : c.description(),
				c.texture() == null ? TextureSource.default_() : TextureSource.vanilla(c.texture()),
				Map.copyOf(attrs), behaviors);
	}

	/**
	 * Apply a validated update proposal as a DELTA on the current definition.
	 * Unset fields keep their values; behavior patching follows set/add/remove
	 * semantics. Returns the new definition (immutable copy).
	 */
	public static ItemDefinition mergeUpdate(ItemDefinition current, Proposal.Update u) {
		String name = u.name() != null ? u.name() : current.name();
		String desc = u.description() != null ? u.description() : current.description();
		TextureSource tex = u.texture() != null ? TextureSource.vanilla(u.texture()) : current.texture();
		Map<String, Double> attrs = new LinkedHashMap<>(current.attributes());
		if (u.damage() != null) {
			attrs.put("damage", u.damage());
		}
		if (u.maxDurability() != null) {
			attrs.put("max_durability", u.maxDurability().doubleValue());
		}
		List<BehaviorDefinition> behaviors = new ArrayList<>(current.behaviors());
		if (u.setBehaviors() != null) {
			behaviors.clear();
			u.setBehaviors().forEach(b -> behaviors.add(ProposalValidator.toBehavior(b)));
		}
		if (u.addBehaviors() != null) {
			for (Proposal.BehaviorSpec b : u.addBehaviors()) {
				BehaviorDefinition nb = ProposalValidator.toBehavior(b);
				behaviors.removeIf(x -> x.trigger().equals(nb.trigger()) && x.actions().equals(nb.actions()));
				behaviors.add(nb);
			}
		}
		if (u.removeTriggers() != null) {
			for (String t : u.removeTriggers()) {
				String tl = t.toLowerCase(java.util.Locale.ROOT);
				behaviors.removeIf(x -> x.trigger().equals(tl));
			}
		}
		return new ItemDefinition(current.version(), current.id(), name, desc, tex, Map.copyOf(attrs), behaviors);
	}

	// ---------- small helpers ----------

	private static String str(JsonObject o, String k) {
		if (!o.has(k) || !o.get(k).isJsonPrimitive() || !o.get(k).getAsJsonPrimitive().isString()) {
			return null;
		}
		String s = o.get(k).getAsString();
		return s.length() > 256 ? s.substring(0, 256) : s;
	}

	private static Double num(JsonObject o, String k) {
		if (!o.has(k) || !o.get(k).isJsonPrimitive() || !o.get(k).getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return o.get(k).getAsDouble();
	}

	private static Integer intg(JsonObject o, String k) {
		if (!o.has(k) || !o.get(k).isJsonPrimitive() || !o.get(k).getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return o.get(k).getAsInt();
	}

	private ProposalJson() {
	}
}
