package dev.worldforge.ops;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * A single observable fact about a tool call.
 *
 * <p>Every field the chat shows about "what the AI is doing" comes from one of
 * these. The model cannot fabricate a success: success is only ever produced
 * by {@link ToolEventStatus#SUCCEEDED}, which the tool layer emits after the
 * real operation returned.</p>
 *
 * @param conversationId conversation the event belongs to
 * @param requestId      the turn this event belongs to
 * @param toolCallId     the specific call
 * @param sequence       monotonically increasing per conversation, starting at 1
 * @param timestampMillis wall-clock time of the event
 * @param toolName       tool that produced it ("" for conversation-level events)
 * @param status         lifecycle status
 * @param summary        one-line human-readable summary
 * @param errorCode      stable error code, or ""
 * @param affectedTargets ids the event refers to, or empty
 * @param confirmationId  the id the player must echo back to approve or
 *                        refuse; only set for AWAITING_CONFIRMATION
 */
public record ToolEvent(
		String conversationId,
		String requestId,
		String toolCallId,
		long sequence,
		long timestampMillis,
		String toolName,
		ToolEventStatus status,
		String summary,
		String errorCode,
		List<String> affectedTargets,
		String confirmationId) {

	/** Compact form for events that carry no confirmation id. */
	public ToolEvent(String conversationId, String requestId, String toolCallId, long sequence,
			long timestampMillis, String toolName, ToolEventStatus status, String summary,
			String errorCode, List<String> affectedTargets) {
		this(conversationId, requestId, toolCallId, sequence, timestampMillis, toolName, status,
				summary, errorCode, affectedTargets, "");
	}

	public ToolEvent {
		conversationId = conversationId == null ? "" : conversationId;
		requestId = requestId == null ? "" : requestId;
		toolCallId = toolCallId == null ? "" : toolCallId;
		toolName = toolName == null ? "" : toolName;
		status = status == null ? ToolEventStatus.FAILED : status;
		summary = summary == null ? "" : summary;
		errorCode = errorCode == null ? "" : errorCode;
		affectedTargets = affectedTargets == null ? List.of() : List.copyOf(affectedTargets);
		confirmationId = confirmationId == null ? "" : confirmationId;
	}

	/** Serialised form shipped to the client. */
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("conversationId", conversationId);
		o.addProperty("requestId", requestId);
		o.addProperty("toolCallId", toolCallId);
		o.addProperty("sequence", sequence);
		o.addProperty("timestamp", timestampMillis);
		o.addProperty("tool", toolName);
		o.addProperty("status", status.name());
		o.addProperty("summary", summary);
		o.addProperty("errorCode", errorCode);
		var arr = new com.google.gson.JsonArray();
		affectedTargets.forEach(arr::add);
		o.add("targets", arr);
		o.addProperty("confirmationId", confirmationId);
		return o;
	}
}
