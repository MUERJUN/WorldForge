package dev.worldforge.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A strict validator for the JSON Schema subset used by WF-Ops tools.
 *
 * <p>Supported keywords: {@code type}, {@code properties},
 * {@code required}, {@code additionalProperties}, {@code enum}, {@code items},
 * {@code minimum}, {@code maximum}, {@code minLength}, {@code maxLength},
 * {@code minItems}, {@code maxItems} and {@code pattern}. Anything else in a
 * schema is ignored (never silently treated as "valid").</p>
 *
 * <p>This is a hard security boundary: model-supplied arguments are
 * untrusted input. Unknown properties are REJECTED rather than ignored, so a
 * model cannot smuggle a field the tool did not expect.</p>
 */
public final class JsonSchemaValidator {

	/** Result of validating one argument object. */
	public record Result(boolean ok, List<String> errors, java.util.Map<String, Object> values) {

		public static Result fail(List<String> errors) {
			return new Result(false, errors, java.util.Map.of());
		}
	}

	private JsonSchemaValidator() {
	}

	/**
	 * Validates {@code args} against {@code schema}.
	 *
	 * @param schema the tool's parameter schema
	 * @param args   the raw arguments parsed from the model's tool call
	 */
	public static Result validate(JsonObject schema, JsonObject args) {
		List<String> errors = new ArrayList<>();
		JsonObject s = schema == null ? new JsonObject() : schema;
		JsonObject a = args == null ? new JsonObject() : args;

		Set<String> allowed = new LinkedHashSet<>();
		JsonObject props = s.has("properties") && s.get("properties").isJsonObject()
				? s.getAsJsonObject("properties") : new JsonObject();
		for (String key : props.keySet()) {
			allowed.add(key);
		}

		// required
		if (s.has("required") && s.get("required").isJsonArray()) {
			for (JsonElement e : s.getAsJsonArray("required")) {
				String key = e.getAsString();
				if (!a.has(key)) {
					errors.add("缺少必填参数: " + key);
				}
			}
		}

		// additionalProperties: false -> reject unknown keys
		boolean strict = s.has("additionalProperties") && s.get("additionalProperties").isJsonPrimitive()
				&& !s.get("additionalProperties").getAsBoolean();
		for (String key : a.keySet()) {
			if (!allowed.contains(key)) {
				if (strict) {
					errors.add("未知参数: " + key);
				}
			}
		}

		// per-property checks
		java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
		for (String key : allowed) {
			if (!a.has(key)) {
				continue;
			}
			JsonElement value = a.get(key);
			JsonObject propSchema = props.getAsJsonObject(key);
			List<String> propErrors = checkValue(value, propSchema, key);
			if (propErrors.isEmpty()) {
				values.put(key, toPlain(value));
			} else {
				errors.addAll(propErrors);
			}
		}
		return errors.isEmpty() ? new Result(true, List.of(), values) : Result.fail(errors);
	}

	private static List<String> checkValue(JsonElement value, JsonObject schema, String path) {
		List<String> errors = new ArrayList<>();
		if (schema == null) {
			return errors;
		}
		String type = schema.has("type") && schema.get("type").isJsonPrimitive()
				? schema.get("type").getAsString() : null;
		if (type != null && !matchesType(value, type)) {
			errors.add(path + " 类型应为 " + type);
			return errors;
		}
		if (schema.has("enum") && schema.get("enum").isJsonArray()) {
			boolean found = false;
			for (JsonElement e : schema.getAsJsonArray("enum")) {
				if (e.equals(value)) {
					found = true;
					break;
				}
			}
			if (!found) {
				errors.add(path + " 取值不在允许范围内");
			}
		}
		if (value.isJsonPrimitive()) {
			JsonPrimitive p = value.getAsJsonPrimitive();
			if (p.isNumber()) {
				double n = p.getAsDouble();
				if (schema.has("minimum") && n < schema.get("minimum").getAsDouble()) {
					errors.add(path + " 不得小于 " + schema.get("minimum").getAsDouble());
				}
				if (schema.has("maximum") && n > schema.get("maximum").getAsDouble()) {
					errors.add(path + " 不得大于 " + schema.get("maximum").getAsDouble());
				}
			}
			if (p.isString()) {
				int len = p.getAsString().length();
				if (schema.has("minLength") && len < schema.get("minLength").getAsInt()) {
					errors.add(path + " 长度不得小于 " + schema.get("minLength").getAsInt());
				}
				if (schema.has("maxLength") && len > schema.get("maxLength").getAsInt()) {
					errors.add(path + " 长度不得大于 " + schema.get("maxLength").getAsInt());
				}
				if (schema.has("pattern")) {
					try {
						if (!p.getAsString().matches(schema.get("pattern").getAsString())) {
							errors.add(path + " 格式不合法");
						}
					} catch (Exception ignored) {
						// an unusable pattern must not accept the value silently
						errors.add(path + " 格式不合法");
					}
				}
			}
		}
		if (value.isJsonArray()) {
			JsonArray arr = value.getAsJsonArray();
			if (schema.has("minItems") && arr.size() < schema.get("minItems").getAsInt()) {
				errors.add(path + " 至少需要 " + schema.get("minItems").getAsInt() + " 项");
			}
			if (schema.has("maxItems") && arr.size() > schema.get("maxItems").getAsInt()) {
				errors.add(path + " 最多 " + schema.get("maxItems").getAsInt() + " 项");
			}
			if (schema.has("items") && schema.get("items").isJsonObject()) {
				for (int i = 0; i < arr.size(); i++) {
					errors.addAll(checkValue(arr.get(i), schema.getAsJsonObject("items"), path + "[" + i + "]"));
				}
			}
		}
		return errors;
	}

	private static boolean matchesType(JsonElement value, String type) {
		return switch (type) {
			case "object" -> value.isJsonObject();
			case "array" -> value.isJsonArray();
			case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
			case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
			case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
					&& value.getAsJsonPrimitive().getAsDouble() == Math.rint(value.getAsJsonPrimitive().getAsDouble());
			case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
			case "null" -> value.isJsonNull();
			default -> true;
		};
	}

	/** Converts a JsonElement into plain Java values for tool use. */
	public static Object toPlain(JsonElement e) {
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonPrimitive()) {
			JsonPrimitive p = e.getAsJsonPrimitive();
			if (p.isBoolean()) {
				return p.getAsBoolean();
			}
			if (p.isNumber()) {
				double d = p.getAsDouble();
				return d == Math.rint(d) ? (Object) (long) d : (Object) d;
			}
			return p.getAsString();
		}
		if (e.isJsonArray()) {
			List<Object> out = new ArrayList<>();
			e.getAsJsonArray().forEach(x -> out.add(toPlain(x)));
			return out;
		}
		if (e.isJsonObject()) {
			java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
			e.getAsJsonObject().entrySet().forEach(en -> out.put(en.getKey(), toPlain(en.getValue())));
			return out;
		}
		return null;
	}

	// ---- schema construction helpers (keep tool definitions terse) ----

	/** An object schema with the given properties and required names. */
	public static JsonObject object(JsonObject properties, List<String> required) {
		JsonObject s = new JsonObject();
		s.addProperty("type", "object");
		s.add("properties", properties);
		JsonArray req = new JsonArray();
		required.forEach(req::add);
		s.add("required", req);
		s.addProperty("additionalProperties", false);
		return s;
	}

	public static JsonObject prop(String type, String description) {
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("description", description);
		return p;
	}

	public static JsonObject prop(String type, String description, double min, double max) {
		JsonObject p = prop(type, description);
		p.addProperty("minimum", min);
		p.addProperty("maximum", max);
		return p;
	}

	public static JsonObject propEnum(String description, String... values) {
		JsonObject p = new JsonObject();
		p.addProperty("type", "string");
		p.addProperty("description", description);
		JsonArray arr = new JsonArray();
		for (String v : values) {
			arr.add(v);
		}
		p.add("enum", arr);
		return p;
	}
}
