package dev.worldforge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.ops.ToolDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tool-calling support for the OpenAI-compatible backend.
 *
 * <p>Two wire formats, one result type:</p>
 * <ol>
 *   <li><b>native</b> — the request carries the OpenAI {@code tools[]} array
 *       and {@code tool_choice: "auto"}; a response may contain
 *       {@code message.tool_calls}.</li>
 *   <li><b>structured fallback</b> — a service that ignores {@code tools} can
 *       still be driven: the tool contract is also written into the system
 *       prompt, and the assistant message is parsed as a STRICT JSON envelope
 *       {@code {"tool_call":{"name":"...","arguments":{...}}}}. Anything that
 *       does not parse cleanly is treated as plain text — never as a tool
 *       call.</li>
 * </ol>
 *
 * <p>Both paths produce identical {@link ChatTurn.ModelToolCall} objects, so
 * the schema validation, permission checks and confirmation flow downstream
 * cannot tell them apart. That is the point: a service without native tool
 * calling gets the SAME permission boundary.</p>
 */
public final class ToolCallingSupport {

	/** Envelope key used by the structured fallback. */
	public static final String ENVELOPE_KEY = "tool_call";

	private ToolCallingSupport() {
	}

	/**
	 * Builds the request body for a tool-calling turn.
	 *
	 * @param systemPrompt system instructions
	 * @param messages     the conversation so far
	 * @param tools        the tools the model may call
	 * @param nativeTools  whether to include the OpenAI {@code tools[]} array
	 */
	public static String requestBody(String systemPrompt, List<ChatTurn> messages,
			List<ToolDescriptor> tools, boolean nativeTools) {
		JsonObject o = new JsonObject();
		o.addProperty("model", "MODEL_PLACEHOLDER");
		o.addProperty("max_tokens", 1200);
		o.addProperty("temperature", 0.2);
		JsonArray msgs = new JsonArray();
		msgs.add(message("system", systemPrompt, null, null));
		for (ChatTurn m : messages) {
			switch (m.role()) {
				case USER -> msgs.add(message("user", m.content(), null, null));
				case ASSISTANT -> {
					if (m.toolCalls().isEmpty()) {
						msgs.add(message("assistant", m.content(), null, null));
					} else {
						JsonObject msg = new JsonObject();
						msg.addProperty("role", "assistant");
						msg.addProperty("content", "");
						JsonArray calls = new JsonArray();
						for (ChatTurn.ModelToolCall c : m.toolCalls()) {
							JsonObject fn = new JsonObject();
							fn.addProperty("name", c.name());
							fn.addProperty("arguments", c.argumentsJson());
							JsonObject call = new JsonObject();
							call.addProperty("id", c.id());
							call.addProperty("type", "function");
							call.add("function", fn);
							calls.add(call);
						}
						msg.add("tool_calls", calls);
						msgs.add(msg);
					}
				}
				case TOOL -> msgs.add(message("tool", m.content(), m.toolCallId(), m.toolName()));
				case SYSTEM -> msgs.add(message("system", m.content(), null, null));
			}
		}
		o.add("messages", msgs);
		if (nativeTools && tools != null && !tools.isEmpty()) {
			JsonArray arr = new JsonArray();
			for (ToolDescriptor t : tools) {
				arr.add(t.toOpenAiTool());
			}
			o.add("tools", arr);
			o.addProperty("tool_choice", "auto");
		}
		return o.toString();
	}

	private static JsonObject message(String role, String content, String toolCallId, String toolName) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content == null ? "" : content);
		if (toolCallId != null && !toolCallId.isBlank()) {
			m.addProperty("tool_call_id", toolCallId);
		}
		if (toolName != null && !toolName.isBlank()) {
			m.addProperty("name", toolName);
		}
		return m;
	}

	/**
	 * Parses a raw provider response into a turn.
	 *
	 * <p>Order matters: native {@code tool_calls} win; otherwise the assistant
	 * text is checked for the strict structured envelope; otherwise the text
	 * is checked for DeepSeek's proprietary DSML markup (some deployments of
	 * the service answer in their own text format instead of using the OpenAI
	 * {@code tool_calls} field); otherwise the text is the final answer.</p>
	 */
	public static ChatTurn.Turn parseResponse(String rawJson) {
		var root = JsonParser.parseString(rawJson).getAsJsonObject();
		var choices = root.getAsJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new IllegalStateException("响应缺少 choices");
		}
		var msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
		if (msg == null) {
			throw new IllegalStateException("响应缺少 message");
		}
		// 1) native tool calls
		if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
			List<ChatTurn.ModelToolCall> calls = new ArrayList<>();
			for (var el : msg.getAsJsonArray("tool_calls")) {
				var call = el.getAsJsonObject();
				var fn = call.getAsJsonObject("function");
				if (fn == null) {
					continue;
				}
				String name = fn.has("name") ? fn.get("name").getAsString() : "";
				if (name.isBlank()) {
					continue;
				}
				String args = fn.has("arguments") && fn.get("arguments").isJsonPrimitive()
						? fn.get("arguments").getAsString() : "{}";
				String id = call.has("id") ? call.get("id").getAsString() : ("call_" + calls.size());
				calls.add(new ChatTurn.ModelToolCall(id, name, args));
			}
			if (!calls.isEmpty()) {
				return new ChatTurn.Turn.Calls(calls);
			}
		}
		// 2) structured fallback
		String content = msg.has("content") && msg.get("content").isJsonPrimitive()
				? msg.get("content").getAsString() : "";
		var structured = parseStructuredEnvelope(content);
		if (structured != null) {
			return structured;
		}
		// 3) DeepSeek DSML text markup (v0.7.4): some deployments emit their
		//    proprietary <DSML invoke> format in the CONTENT field instead of
		//    the native tool_calls channel. Parsing it here keeps the
		//    permission boundary identical: whatever is extracted goes through
		//    the SAME schema validation and confirmation gate as a native call.
		var dsml = parseDsmlCalls(content);
		if (dsml != null) {
			return dsml;
		}
		// 4) plain answer
		return new ChatTurn.Turn.Final(content);
	}

	/**
	 * Parses DeepSeek's proprietary DSML tool-call markup from assistant
	 * text, e.g. {@code <｜｜DSML｜｜ invoke name="graft_behavior">…<｜｜DSML｜｜
	 * parameter name="action">apply_effect</…parameter>…}.
	 *
	 * <p>Returns {@code null} for anything that does not parse cleanly — a
	 * malformed reply must never be executed as a tool call. The extracted
	 * arguments remain UNVALIDATED here: downstream they pass through the
	 * same schema validation as native calls.</p>
	 */
	public static ChatTurn.Turn.Calls parseDsmlCalls(String content) {
		if (content == null || content.isBlank() || !content.contains("invoke name=")) {
			return null;
		}
		try {
			List<ChatTurn.ModelToolCall> calls = new ArrayList<>();
			// the separator decoration (full-width bars) is ignored by
			// matching from the keyword outward
			var invokePattern = java.util.regex.Pattern.compile(
					"invoke name=\"([^\"]{1,64})\"(.*?)</[^>]*invoke>",
					java.util.regex.Pattern.DOTALL);
			var paramPattern = java.util.regex.Pattern.compile(
					"parameter name=\"([^\"]+)\"[^>]*>(.*?)</[^>]*parameter>",
					java.util.regex.Pattern.DOTALL);
			var inv = invokePattern.matcher(content);
			int n = 0;
			while (inv.find()) {
				String name = inv.group(1).trim();
				if (name.isBlank() || !name.matches("[a-z_]{1,64}")) {
					return null; // a weird name must never become a call
				}
				StringBuilder args = new StringBuilder("{");
				var pm = paramPattern.matcher(inv.group(2));
				boolean first = true;
				while (pm.find()) {
					String key = pm.group(1).trim();
					String value = pm.group(2).trim();
					if (key.isEmpty()) {
						return null;
					}
					if (!first) {
						args.append(',');
					}
					first = false;
					// emit JSON-safe: raw JSON / numbers / booleans verbatim,
					// everything else as a JSON string
					if (value.startsWith("{") || value.startsWith("[")
							|| value.matches("-?\\d+(\\.\\d+)?")
							|| value.equals("true") || value.equals("false")) {
						args.append(jsonString(key)).append(':').append(value);
					} else {
						args.append(jsonString(key)).append(':').append(jsonString(value));
					}
				}
				args.append('}');
				// the reconstruction must actually parse before it is used
				var parsed = JsonParser.parseString(args.toString());
				if (!parsed.isJsonObject()) {
					return null;
				}
				calls.add(new ChatTurn.ModelToolCall("dsml_" + name + "_" + n, name, args.toString()));
				n++;
			}
			return calls.isEmpty() ? null : new ChatTurn.Turn.Calls(calls);
		} catch (Exception e) {
			return null;
		}
	}

	/** JSON string literal for a key/value (control characters escaped). */
	private static String jsonString(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

	/**
	 * Parses the strict structured envelope from assistant text.
	 *
	 * <p>Accepts either a bare envelope object or one wrapped in a markdown
	 * code fence. Any parse failure, missing key, unknown tool name shape or
	 * non-object arguments yields {@code null} (plain text) — a malformed
	 * model reply must never be executed as a tool call.</p>
	 */
	public static ChatTurn.Turn parseStructuredEnvelope(String content) {
		if (content == null || content.isBlank()) {
			return null;
		}
		String json = extractJsonObject(content);
		if (json == null) {
			return null;
		}
		try {
			var el = JsonParser.parseString(json);
			if (!el.isJsonObject()) {
				return null;
			}
			var obj = el.getAsJsonObject();
			if (!obj.has(ENVELOPE_KEY)) {
				return null;
			}
			var call = obj.get(ENVELOPE_KEY);
			if (!call.isJsonObject()) {
				return null;
			}
			var callObj = call.getAsJsonObject();
			String name = callObj.has("name") && callObj.get("name").isJsonPrimitive()
					? callObj.get("name").getAsString() : "";
			if (name.isBlank() || name.length() > 64) {
				return null;
			}
			JsonObject args = new JsonObject();
			if (callObj.has("arguments") && callObj.get("arguments").isJsonObject()) {
				args = callObj.getAsJsonObject("arguments");
			} else if (callObj.has("arguments") && !callObj.get("arguments").isJsonNull()) {
				return null; // arguments must be an object, never a scalar
			}
			return new ChatTurn.Turn.Calls(List.of(
					new ChatTurn.ModelToolCall("structured_" + name, name, args.toString())));
		} catch (Exception e) {
			return null;
		}
	}

	/** Extracts the first balanced JSON object from arbitrary text. */
	static String extractJsonObject(String text) {
		int start = text.indexOf('{');
		if (start < 0) {
			return null;
		}
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		for (int i = start; i < text.length(); i++) {
			char c = text.charAt(i);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (c == '\\' && inString) {
				escaped = true;
				continue;
			}
			if (c == '"') {
				inString = !inString;
				continue;
			}
			if (inString) {
				continue;
			}
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return text.substring(start, i + 1);
				}
			}
		}
		return null;
	}

	/**
	 * The structured tool contract appended to the system prompt, used when
	 * the service may not support native tool calling.
	 */
	public static String structuredContract(List<ToolDescriptor> tools) {
		StringBuilder sb = new StringBuilder();
		sb.append("\n\n如果本服务不支持原生工具调用，请用严格 JSON 表达工具调用：\n");
		sb.append("{\"tool_call\":{\"name\":\"<工具名>\",\"arguments\":{...}}}\n");
		sb.append("只能输出这一个 JSON 对象（不要附加解释文字）。可用工具：\n");
		for (ToolDescriptor t : tools) {
			sb.append("- ").append(t.name()).append(": ").append(t.description()).append("\n");
			sb.append("  参数: ").append(String.join(", ", t.argumentNames())).append("\n");
		}
		return sb.toString();
	}
}
