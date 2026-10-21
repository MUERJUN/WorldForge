package dev.worldforge.ai;

import java.util.List;
import java.util.Map;

/**
 * The structured conversation model used by the tool-calling loop.
 *
 * <p>Separate from the legacy single-turn {@code (systemPrompt, userMessage)}
 * contract so the existing provider path keeps working unchanged.</p>
 */
public final class ChatTurn {

	/** The role of a turn. */
	public enum Role {
		/** instructions to the model */
		SYSTEM,
		/** what the player said */
		USER,
		/** what the model said */
		ASSISTANT,
		/** the outcome of one tool call */
		TOOL
	}

	private final Role role;
	private final String content;
	private final String toolCallId;
	private final String toolName;
	private final List<ModelToolCall> toolCalls;

	private ChatTurn(Role role, String content, String toolCallId, String toolName,
			List<ModelToolCall> toolCalls) {
		this.role = role;
		this.content = content == null ? "" : content;
		this.toolCallId = toolCallId == null ? "" : toolCallId;
		this.toolName = toolName == null ? "" : toolName;
		this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}

	public static ChatTurn system(String content) {
		return new ChatTurn(Role.SYSTEM, content, "", "", List.of());
	}

	public static ChatTurn user(String content) {
		return new ChatTurn(Role.USER, content, "", "", List.of());
	}

	public static ChatTurn assistant(String content) {
		return new ChatTurn(Role.ASSISTANT, content, "", "", List.of());
	}

	/** An assistant turn that requested tool calls. */
	public static ChatTurn assistantToolCalls(List<ModelToolCall> calls) {
		return new ChatTurn(Role.ASSISTANT, "", "", "", calls);
	}

	/** The result of one tool call, fed back to the model as DATA. */
	public static ChatTurn toolResult(String toolCallId, String toolName, String content) {
		return new ChatTurn(Role.TOOL, content, toolCallId, toolName, List.of());
	}

	public Role role() {
		return role;
	}

	public String content() {
		return content;
	}

	public String toolCallId() {
		return toolCallId;
	}

	public String toolName() {
		return toolName;
	}

	public List<ModelToolCall> toolCalls() {
		return toolCalls;
	}

	/**
	 * One tool call requested by the model.
	 *
	 * @param id            the call id (echoed back with the result)
	 * @param name          the tool name
	 * @param argumentsJson the raw JSON arguments, still UNVALIDATED
	 */
	public record ModelToolCall(String id, String name, String argumentsJson) {

		public ModelToolCall {
			id = id == null ? "" : id;
			name = name == null ? "" : name;
			argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
		}

		/** Parsed arguments, or an empty map when the payload is not a JSON object. */
		public Map<String, Object> parsedArguments() {
			try {
				var el = com.google.gson.JsonParser.parseString(argumentsJson);
				if (!el.isJsonObject()) {
					return Map.of();
				}
				var out = new java.util.LinkedHashMap<String, Object>();
				el.getAsJsonObject().entrySet().forEach(e ->
						out.put(e.getKey(), dev.worldforge.ops.JsonSchemaValidator.toPlain(e.getValue())));
				return out;
			} catch (Exception e) {
				return Map.of();
			}
		}
	}

	/**
	 * What the model returned for one turn.
	 *
	 * <p>Either it asked for tools, or it produced its final answer.</p>
	 */
	public sealed interface Turn {
		/** The model wants tools executed. */
		record Calls(List<ModelToolCall> calls) implements Turn {
			public Calls {
				calls = calls == null ? List.of() : List.copyOf(calls);
			}
		}

		/** The model finished with an answer. */
		record Final(String text) implements Turn {
			public Final {
				text = text == null ? "" : text;
			}
		}
	}
}
