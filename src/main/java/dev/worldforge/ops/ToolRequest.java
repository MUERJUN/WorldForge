package dev.worldforge.ops;

import java.util.Map;
import java.util.UUID;

/**
 * One tool invocation requested by the model.
 *
 * <p>Every field is server-generated or validated server-side. The
 * {@code requestId} and {@code toolCallId} together make the call idempotent:
 * replaying the same pair never executes the operation twice.</p>
 *
 * @param requestId      per-turn request id, unique within a conversation
 * @param conversationId conversation the call belongs to
 * @param toolCallId     model-assigned call id (echoed back in results/events)
 * @param toolName       registered tool name
 * @param arguments      parsed arguments (already schema-validated)
 */
public record ToolRequest(
		String requestId,
		String conversationId,
		String toolCallId,
		String toolName,
		Map<String, Object> arguments) {

	public ToolRequest {
		requestId = requestId == null ? "" : requestId;
		conversationId = conversationId == null ? "" : conversationId;
		toolCallId = toolCallId == null ? "" : toolCallId;
		toolName = toolName == null ? "" : toolName;
		arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
	}

	/** Stable identity used for idempotency: same pair => same operation. */
	public String idempotencyKey() {
		return conversationId + "|" + requestId + "|" + toolCallId + "|" + toolName;
	}

	public String argumentString(String key) {
		Object v = arguments.get(key);
		return v == null ? null : String.valueOf(v);
	}

	public int argumentInt(String key, int fallback) {
		Object v = arguments.get(key);
		if (v instanceof Number n) {
			return n.intValue();
		}
		if (v instanceof String s) {
			try {
				return Integer.parseInt(s.trim());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	public boolean argumentBoolean(String key, boolean fallback) {
		Object v = arguments.get(key);
		if (v instanceof Boolean b) {
			return b;
		}
		if (v instanceof String s) {
			if (s.equalsIgnoreCase("true")) {
				return true;
			}
			if (s.equalsIgnoreCase("false")) {
				return false;
			}
		}
		return fallback;
	}

	/** Stable per-player identity attached by the server, never by the model. */
	public static String playerKey(java.util.UUID id) {
		return id == null ? "" : id.toString();
	}
}
