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
	 * is the final answer.</p>
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
		// 3) plain answer
		return new ChatTurn.Turn.Final(content);
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
