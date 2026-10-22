package dev.worldforge.chat;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides which incoming server events a conversation accepts.
 *
 * <p>v0.7.1: the rules that keep the chat in step with the server lived in
 * the client class, where they could not be tested. They are here instead, in
 * the main source set, so they are covered by the ordinary unit suite.</p>
 *
 * <p>Three rules:</p>
 * <ul>
 *   <li>a user's message is shown ONCE — the client shows it immediately and
 *       the server's echo is recognised by its request id;</li>
 *   <li>an event of a task that is no longer the current one is DROPPED, so a
 *       slow, cancelled task cannot overwrite the state of the task that
 *       replaced it;</li>
 *   <li>a DELETED conversation is not brought back by a late event.</li>
 * </ul>
 */
public final class ChatEventRouter {

	/** the request ids the client already showed */
	private final Set<String> seenRequestIds = Collections.synchronizedSet(new LinkedHashSet<>());
	/** the conversations the player deleted: their late events are dropped */
	private final Set<String> deletedConversations = Collections.synchronizedSet(new LinkedHashSet<>());

	/**
	 * True when the client already showed the message of this request.
	 *
	 * <p>The first time a request id is seen it is remembered, so the second
	 * time (the server's echo) it is recognised as a duplicate.</p>
	 */
	public boolean isDuplicateUserMessage(String clientRequestId) {
		if (clientRequestId == null || clientRequestId.isBlank()) {
			return false;
		}
		if (!seenRequestIds.add(clientRequestId)) {
			return true;
		}
		// keep the set bounded
		while (seenRequestIds.size() > 512) {
			var it = seenRequestIds.iterator();
			it.next();
			it.remove();
		}
		return false;
	}

	/** True when the conversation was deleted and the event is a leftover. */
	public boolean isDeleted(String conversationId) {
		return conversationId != null && deletedConversations.contains(conversationId);
	}

	/** Marks a conversation as deleted. */
	public void markDeleted(String conversationId) {
		if (conversationId != null && !conversationId.isBlank()) {
			deletedConversations.add(conversationId);
		}
	}

	/** A conversation that is used again is not deleted any more. */
	public void markUsed(String conversationId) {
		if (conversationId != null && !conversationId.isBlank()) {
			deletedConversations.remove(conversationId);
		}
	}

	/**
	 * True when the event belongs to the task the conversation currently
	 * follows.
	 *
	 * <p>An event WITHOUT a task id cannot be judged and is accepted: the
	 * server always sends one, but an old client or a hand-made event must not
	 * be dropped for that reason.</p>
	 */
	public boolean appliesTo(ChatSession session, String taskId) {
		if (taskId == null || taskId.isBlank()) {
			return true;
		}
		if (session == null) {
			return false;
		}
		String current = session.taskId();
		return current.isBlank() || current.equals(taskId);
	}

	/** Forgets everything (used when a new session starts). */
	public void reset() {
		seenRequestIds.clear();
		deletedConversations.clear();
	}
}
