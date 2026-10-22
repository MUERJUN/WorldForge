package dev.worldforge.client.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.chat.ChatMessage;
import dev.worldforge.chat.ChatSession;
import dev.worldforge.chat.ChatTaskState;
import dev.worldforge.chat.ChatStore;
import dev.worldforge.network.WorldForgeChatProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client-side conversation store and transport.
 *
 * <p>Holds every conversation, routes incoming events by
 * {@code conversationId}, and persists the visible history. Kept outside the
 * screen so that opening the settings panel and coming back does not lose the
 * conversation.</p>
 */
public final class ChatClientState {

	/** the conversation that is open */
	private static volatile String activeId = "default";
	/** every known conversation, in creation order */
	private static final Map<String, ChatSession> SESSIONS = new LinkedHashMap<>();
	/** set when the server reports the AI is not configured */
	private static volatile String lastServerNotice = "";
	/** monotonically increasing request id for outgoing requests */
	private static int nextRequestId = 1;
	/**
	 * The rules that keep the chat in step with the server. They live in the
	 * MAIN source set so the ordinary unit suite can cover them.
	 */
	private static final dev.worldforge.chat.ChatEventRouter ROUTER =
			new dev.worldforge.chat.ChatEventRouter();

	private ChatClientState() {
	}

	/** Loads the persisted conversations (once per game start). */
	public static synchronized void load() {
		if (!SESSIONS.isEmpty()) {
			return;
		}
		SESSIONS.putAll(ChatStore.loadAll());
		if (!SESSIONS.containsKey(activeId)) {
			SESSIONS.computeIfAbsent(activeId, ChatSession::new);
		}
	}

	/** The active conversation. */
	public static synchronized ChatSession active() {
		load();
		return SESSIONS.computeIfAbsent(activeId, ChatSession::new);
	}

	/** The id of the active conversation. */
	public static String activeConversationId() {
		return activeId;
	}

	/** All conversations, in creation order. */
	public static synchronized List<ChatSession> sessions() {
		load();
		return new ArrayList<>(SESSIONS.values());
	}

	/** Switches to another conversation. */
	public static synchronized void switchTo(String conversationId) {
		load();
		activeId = conversationId == null || conversationId.isBlank() ? "default" : conversationId;
		ROUTER.markUsed(activeId);
		SESSIONS.computeIfAbsent(activeId, ChatSession::new);
	}

	/** Creates a new conversation and switches to it. */
	public static synchronized ChatSession createSession() {
		load();
		String id = "c" + System.currentTimeMillis();
		ChatSession s = new ChatSession(id);
		SESSIONS.put(id, s);
		activeId = id;
		ChatStore.save(s);
		return s;
	}

	/** Deletes a conversation (and its stored file). */
	public static synchronized void deleteSession(String conversationId) {
		load();
		SESSIONS.remove(conversationId);
		ChatStore.delete(conversationId);
		// a late event of this conversation must not bring it back
		ROUTER.markDeleted(conversationId);
		if (conversationId.equals(activeId)) {
			activeId = SESSIONS.isEmpty() ? "default" : SESSIONS.keySet().iterator().next();
			SESSIONS.computeIfAbsent(activeId, ChatSession::new);
		}
	}

	/** The last server notice (AI not configured, permission, ...). */
	public static String lastServerNotice() {
		return lastServerNotice;
	}

	public static void clearServerNotice() {
		lastServerNotice = "";
	}

	// ------------------------------------------------------------ transport

	/** Sends a chat message to the server. */
	public static void sendMessage(String text) {
		ChatSession session = active();
		String trimmed = text == null ? "" : text.trim();
		if (trimmed.isEmpty()) {
			return;
		}
		session.add(ChatMessage.player(trimmed));
		session.setTaskState(ChatTaskState.THINKING);
		persist(session);
		JsonObject body = new JsonObject();
		body.addProperty("conversationId", session.conversationId());
		body.addProperty("message", trimmed);
		// the client-side id lets the server echo be recognised as a duplicate
		String clientRequestId = "c" + (nextRequestId++);
		body.addProperty("clientRequestId", clientRequestId);
		// send a little context so a restored conversation keeps its thread
		String context = session.recentContext(6);
		if (!context.isEmpty()) {
			body.addProperty("history", context);
		}
		send("send", body);
	}

	/** Approves a pending operation. */
	public static void confirm(String confirmationId) {
		JsonObject body = new JsonObject();
		body.addProperty("conversationId", active().conversationId());
		body.addProperty("confirmationId", confirmationId);
		send("confirm", body);
	}

	/** Refuses a pending operation. */
	public static void deny(String confirmationId) {
		JsonObject body = new JsonObject();
		body.addProperty("conversationId", active().conversationId());
		body.addProperty("confirmationId", confirmationId);
		send("deny", body);
	}

	/** Cancels the running task of the active conversation. */
	public static void cancelTask() {
		JsonObject body = new JsonObject();
		body.addProperty("conversationId", active().conversationId());
		send("cancel", body);
	}

	/** Asks the server for the conversation list. */
	public static void requestSessions() {
		send("list_sessions", new JsonObject());
	}

	private static void send(String kind, JsonObject body) {
		var client = MinecraftClient.getInstance();
		if (client == null || client.getNetworkHandler() == null) {
			lastServerNotice = "未连接到服务器：无法发送请求。";
			return;
		}
		if (!ClientPlayNetworking.canSend(WorldForgeChatProtocol.ChatRequest.ID)) {
			lastServerNotice = "服务端没有安装 WorldForge（或版本过旧），无法使用 AI 聊天。";
			return;
		}
		int reqId = nextRequestId++;
		ClientPlayNetworking.send(new WorldForgeChatProtocol.ChatRequest(reqId, kind, body.toString()));
	}

	// --------------------------------------------------------------- events

	/** Handles one push from the server. */
	public static synchronized void onPush(String type, String json) {
		try {
			JsonObject o = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json)
					.getAsJsonObject();
			switch (type == null ? "" : type) {
				case "message" -> {
					String cid = str(o, "conversationId");
					ChatSession s = sessionFor(cid);
					if (s == null) {
						return; // a deleted conversation must not be recreated
					}
					String role = str(o, "role");
					String text = str(o, "text");
					String taskId = str(o, "taskId");
					String clientRequestId = str(o, "clientRequestId");
					if ("user".equals(role)) {
						// the client already shows the message it sent, so the
						// server echo only CONFIRMS it
						if (ROUTER.isDuplicateUserMessage(clientRequestId)) {
							return; // the client already shows this message
						}
						if (s.messages().stream().noneMatch(m -> m.role() == ChatMessage.Role.PLAYER
								&& m.text().equals(text))) {
							s.add(ChatMessage.player(text));
						}
					} else {
						s.add(ChatMessage.assistant(text));
					}
					if (!taskId.isBlank()) {
						s.setTaskId(taskId);
					}
					persist(s);
				}
				case "tool" -> {
					String cid = str(o, "conversationId");
					ChatSession s = sessionFor(cid);
					if (s == null) {
						return;
					}
					String tool = str(o, "tool");
					String status = str(o, "status");
					String summary = str(o, "summary");
					String errorCode = str(o, "error");
					String confirmationId = str(o, "confirmationId");
					String taskId = str(o, "taskId");
					if (!isCurrentTask(s, taskId)) {
						return; // an event of a task that is no longer current
					}
					if ("AWAITING_CONFIRMATION".equals(status)) {
						s.setPending(confirmationId, tool, summary);
						s.setTaskState(ChatTaskState.AWAITING_CONFIRMATION);
						s.add(ChatMessage.confirmation(confirmationId, tool, summary));
					} else {
						s.add(ChatMessage.tool(tool, status, summary, errorCode));
						if ("SUCCEEDED".equals(status) || "FAILED".equals(status)
								|| "CANCELLED".equals(status)) {
							s.clearPending();
						}
					}
					persist(s);
				}
				case "status" -> {
					String cid = str(o, "conversationId");
					ChatSession s = sessionFor(cid == null || cid.isBlank() ? activeId : cid);
					if (s == null) {
						return;
					}
					String status = str(o, "status");
					String detail = str(o, "detail");
					String taskId = str(o, "taskId");
					if (!isCurrentTask(s, taskId)) {
						return; // a stale event must not overwrite the new state
					}
					ChatTaskState state = ChatTaskState.fromServer(status);
					if (state == null) {
						// an unknown state never clears a busy state
						if (detail != null && !detail.isBlank() && !s.busy()) {
							s.add(ChatMessage.system(detail));
						}
						return;
					}
					s.setTaskState(state);
					if (state == ChatTaskState.AWAITING_CONFIRMATION) {
						s.add(ChatMessage.system(detail == null || detail.isBlank()
								? "等待你确认上面的操作" : detail));
					} else if (state.isTerminal()) {
						s.clearPending();
						if (detail != null && !detail.isBlank()) {
							s.add(ChatMessage.system(detail));
						}
						if (state == ChatTaskState.FAILED && detail != null && !detail.isBlank()) {
							lastServerNotice = detail;
						}
					} else if (state == ChatTaskState.QUEUED && detail != null && !detail.isBlank()) {
						s.add(ChatMessage.system(detail));
					}
					persist(s);
				}
				case "sessions" -> {
					if (o.has("sessions") && o.get("sessions").isJsonArray()) {
						JsonArray arr = o.getAsJsonArray("sessions");
						for (var el : arr) {
							String cid = el.getAsString();
							SESSIONS.computeIfAbsent(cid, ChatSession::new);
						}
					}
				}
				default -> {
					// unknown event kinds are ignored on purpose
				}
			}
		} catch (Exception e) {
			lastServerNotice = "无法解析服务端事件：" + e.getClass().getSimpleName();
		}
	}

	/**
	 * The session an event belongs to, or {@code null} when the conversation
	 * was deleted and the event is a leftover.
	 *
	 * <p>v0.7.1: an old event must never bring a deleted conversation back.</p>
	 */
	private static ChatSession sessionFor(String conversationId) {
		if (conversationId == null || conversationId.isBlank()) {
			return null;
		}
		if (ROUTER.isDeleted(conversationId)) {
			return null;
		}
		return SESSIONS.computeIfAbsent(conversationId, ChatSession::new);
	}

	/**
	 * True when the event belongs to the task the client currently follows.
	 *
	 * <p>An event of an OLD task must not overwrite the state of the task
	 * that replaced it.</p>
	 */
	private static boolean isCurrentTask(ChatSession session, String taskId) {
		if (taskId == null || taskId.isBlank()) {
			return true; // an event without a task id cannot be judged
		}
		String current = session.taskId();
		return current.isBlank() || current.equals(taskId);
	}

	/** Persists a conversation (best effort). */
	private static void persist(ChatSession session) {
		ChatStore.save(session);
	}

	private static String str(JsonObject o, String key) {
		if (o == null || !o.has(key) || o.get(key).isJsonPrimitive()) {
			return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
		}
		return "";
	}

	/** Test hook: forget everything. */
	public static synchronized void resetForTest() {
		SESSIONS.clear();
		activeId = "default";
		lastServerNotice = "";
		ROUTER.reset();
	}
}
