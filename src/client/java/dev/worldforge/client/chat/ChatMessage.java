package dev.worldforge.client.chat;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * One entry in a conversation.
 *
 * <p>Four kinds, all rendered in the same message flow:</p>
 * <ul>
 *   <li>{@code text} — something the player or the assistant said;</li>
 *   <li>{@code tool} — one observable tool event (started / running / waiting
 *       for confirmation / succeeded / failed / cancelled);</li>
 *   <li>{@code confirm} — a pending operation the player can approve or
 *       refuse; carries the tool's real impact summary;</li>
 *   <li>{@code status} — a transport or permission note.</li>
 * </ul>
 */
public final class ChatMessage {

	/** What kind of entry this is. */
	public enum Kind {
		TEXT, TOOL, CONFIRM, STATUS
	}

	/** The role that produced the entry. */
	public enum Role {
		PLAYER, ASSISTANT, TOOL, SYSTEM
	}

	private final Kind kind;
	private final Role role;
	private final String text;
	private final long timestamp;
	private final String toolName;
	private final String status;
	private final String errorCode;
	private final String confirmationId;

	private ChatMessage(Kind kind, Role role, String text, long timestamp, String toolName,
			String status, String errorCode, String confirmationId) {
		this.kind = kind;
		this.role = role;
		this.text = text == null ? "" : text;
		this.timestamp = timestamp;
		this.toolName = toolName == null ? "" : toolName;
		this.status = status == null ? "" : status;
		this.errorCode = errorCode == null ? "" : errorCode;
		this.confirmationId = confirmationId == null ? "" : confirmationId;
	}

	public static ChatMessage player(String text) {
		return new ChatMessage(Kind.TEXT, Role.PLAYER, text, System.currentTimeMillis(), "", "", "", "");
	}

	public static ChatMessage assistant(String text) {
		return new ChatMessage(Kind.TEXT, Role.ASSISTANT, text, System.currentTimeMillis(), "", "", "", "");
	}

	public static ChatMessage system(String text) {
		return new ChatMessage(Kind.STATUS, Role.SYSTEM, text, System.currentTimeMillis(), "", "", "", "");
	}

	public static ChatMessage tool(String toolName, String status, String text, String errorCode) {
		return new ChatMessage(Kind.TOOL, Role.TOOL, text, System.currentTimeMillis(),
				toolName, status, errorCode, "");
	}

	public static ChatMessage confirmation(String confirmationId, String toolName, String summary) {
		return new ChatMessage(Kind.CONFIRM, Role.TOOL, summary, System.currentTimeMillis(),
				toolName, "awaiting_confirmation", "", confirmationId);
	}

	public Kind kind() {
		return kind;
	}

	public Role role() {
		return role;
	}

	public String text() {
		return text;
	}

	public long timestamp() {
		return timestamp;
	}

	public String toolName() {
		return toolName;
	}

	public String status() {
		return status;
	}

	public String errorCode() {
		return errorCode;
	}

	public String confirmationId() {
		return confirmationId;
	}

	/** True when this entry still waits for the player's decision. */
	public boolean isPendingConfirmation() {
		return kind == Kind.CONFIRM;
	}

	/** Serialised form for the on-disk history. */
	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind.name());
		o.addProperty("role", role.name());
		o.addProperty("text", text);
		o.addProperty("timestamp", timestamp);
		o.addProperty("tool", toolName);
		o.addProperty("status", status);
		o.addProperty("error", errorCode);
		o.addProperty("confirmationId", confirmationId);
		return o;
	}

	public static ChatMessage fromJson(JsonObject o) {
		return new ChatMessage(
				kindOf(o.has("kind") ? o.get("kind").getAsString() : "TEXT"),
				roleOf(o.has("role") ? o.get("role").getAsString() : "ASSISTANT"),
				o.has("text") ? o.get("text").getAsString() : "",
				o.has("timestamp") ? o.get("timestamp").getAsLong() : 0L,
				o.has("tool") ? o.get("tool").getAsString() : "",
				o.has("status") ? o.get("status").getAsString() : "",
				o.has("error") ? o.get("error").getAsString() : "",
				o.has("confirmationId") ? o.get("confirmationId").getAsString() : "");
	}

	private static Kind kindOf(String s) {
		try {
			return Kind.valueOf(s);
		} catch (Exception e) {
			return Kind.TEXT;
		}
	}

	private static Role roleOf(String s) {
		try {
			return Role.valueOf(s);
		} catch (Exception e) {
			return Role.ASSISTANT;
		}
	}

	/** A short one-line rendering used by the history list. */
	public String preview() {
		String t = text.replace('\n', ' ').trim();
		return t.length() > 60 ? t.substring(0, 60) + "…" : t;
	}

	/** Convenience: the messages of a conversation as plain text. */
	public static List<String> texts(List<ChatMessage> messages) {
		return messages.stream().map(ChatMessage::text).toList();
	}
}
