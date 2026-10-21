package dev.worldforge.client.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * One conversation: its own messages, its own pending confirmation and its
 * own running task.
 *
 * <p>Conversations are fully independent. Switching between them never mixes
 * one conversation's result into another, because every incoming event
 * carries its {@code conversationId} and is routed by it.</p>
 */
public final class ChatSession {

	/** hard cap on messages kept in one conversation */
	public static final int MAX_MESSAGES = 500;

	private final String conversationId;
	private final List<ChatMessage> messages = new ArrayList<>();
	private String title = "";
	private String pendingConfirmationId = "";
	private String pendingToolName = "";
	private String pendingSummary = "";
	private boolean busy;
	/** true when the last persisted copy of this conversation could not be read */
	private boolean corrupted;

	public ChatSession(String conversationId) {
		this.conversationId = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
	}

	public String conversationId() {
		return conversationId;
	}

	public List<ChatMessage> messages() {
		return messages;
	}

	public void add(ChatMessage message) {
		messages.add(message);
		while (messages.size() > MAX_MESSAGES) {
			messages.remove(0);
		}
		if (title.isBlank() && message.role() == ChatMessage.Role.PLAYER) {
			title = message.preview();
		}
	}

	public String title() {
		return title.isBlank() ? conversationId : title;
	}

	public void setTitle(String title) {
		this.title = title == null ? "" : title;
	}

	public boolean hasPendingConfirmation() {
		return !pendingConfirmationId.isBlank();
	}

	public String pendingConfirmationId() {
		return pendingConfirmationId;
	}

	public String pendingToolName() {
		return pendingToolName;
	}

	public String pendingSummary() {
		return pendingSummary;
	}

	/** Records that a tool is waiting for the player's decision. */
	public void setPending(String confirmationId, String toolName, String summary) {
		this.pendingConfirmationId = confirmationId == null ? "" : confirmationId;
		this.pendingToolName = toolName == null ? "" : toolName;
		this.pendingSummary = summary == null ? "" : summary;
	}

	/** Clears the pending confirmation (after approve, refuse or timeout). */
	public void clearPending() {
		this.pendingConfirmationId = "";
		this.pendingToolName = "";
		this.pendingSummary = "";
	}

	public boolean busy() {
		return busy;
	}

	public void setBusy(boolean busy) {
		this.busy = busy;
	}

	public boolean corrupted() {
		return corrupted;
	}

	public void setCorrupted(boolean corrupted) {
		this.corrupted = corrupted;
	}

	/** The text of the last N conversational turns, for context restoration. */
	public String recentContext(int turns) {
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (int i = messages.size() - 1; i >= 0 && count < turns; i--) {
			ChatMessage m = messages.get(i);
			if (m.kind() != ChatMessage.Kind.TEXT) {
				continue;
			}
			String role = m.role() == ChatMessage.Role.PLAYER ? "user" : "assistant";
			sb.insert(0, "{\"role\":\"").append(role).append("\",\"text\":\"")
					.append(escape(m.text())).append("\"},");
			count++;
		}
		if (sb.length() == 0) {
			return "";
		}
		sb.setLength(sb.length() - 1);
		return "[" + sb + "]";
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
	}
}
