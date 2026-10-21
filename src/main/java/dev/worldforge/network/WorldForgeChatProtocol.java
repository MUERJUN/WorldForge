package dev.worldforge.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.WorldForge;
import dev.worldforge.ai.AgentOrchestrator;
import dev.worldforge.ai.ToolCallingProvider;
import dev.worldforge.ops.ToolCatalog;
import dev.worldforge.ops.ToolEvent;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chat-first protocol (v0.7.0).
 *
 * <p>C2S {@code ChatRequest(requestId, kind, payload)} — the client sends
 * intents only; the server decides what actually happens.</p>
 *
 * <p>S2C {@code ChatPush(requestId, type, json)} — a stream of conversation
 * events: player/assistant messages, tool events and status changes. The
 * {@code type} keeps the client switch cheap, and the {@code json} keeps the
 * protocol open for new event kinds without a protocol change.</p>
 *
 * <p>Everything runs on the server thread; the AI model call is asynchronous
 * and reports back through the sink below.</p>
 */
public final class WorldForgeChatProtocol {

	public static final int MAX_PAYLOAD_CHARS = 256 * 1024;
	public static final int MAX_MESSAGE_CHARS = 4000;
	public static final int MAX_HISTORY_CHARS = 64 * 1024;

	// ---------------- C2S ----------------

	public record ChatRequest(int requestId, String kind, String payload) implements CustomPayload {
		public static final Id<ChatRequest> ID = new Id<>(WorldForge.id("chat_request"));
		public static final PacketCodec<? super RegistryByteBuf, ChatRequest> CODEC =
				PacketCodec.tuple(
						PacketCodecs.VAR_INT, ChatRequest::requestId,
						PacketCodecs.STRING, ChatRequest::kind,
						PacketCodecs.STRING, ChatRequest::payload,
						ChatRequest::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	// ---------------- S2C ----------------

	public record ChatPush(int requestId, String type, String json) implements CustomPayload {
		public static final Id<ChatPush> ID = new Id<>(WorldForge.id("chat_push"));
		public static final PacketCodec<? super RegistryByteBuf, ChatPush> CODEC =
				PacketCodec.tuple(
						PacketCodecs.VAR_INT, ChatPush::requestId,
						PacketCodecs.STRING, ChatPush::type,
						PacketCodecs.STRING, ChatPush::json,
						ChatPush::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** conversation id -> the player's conversations, for the session list */
	private static final Map<java.util.UUID, java.util.concurrent.CopyOnWriteArrayList<String>> CONVERSATIONS =
			new ConcurrentHashMap<>();

	private WorldForgeChatProtocol() {
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(ChatRequest.ID, ChatRequest.CODEC);
		PayloadTypeRegistry.playS2C().register(ChatPush.ID, ChatPush.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ChatRequest.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			MinecraftServer server = context.server();
			if (payload.kind() == null || payload.kind().length() > 24
					|| payload.payload() == null || payload.payload().length() > MAX_PAYLOAD_CHARS) {
				pushError(player, payload.requestId(), "请求过大或格式非法");
				return;
			}
			// world-state access MUST happen on the server thread
			server.execute(() -> handle(server, player, payload));
		});
	}

	// ---------------- server side ----------------

	private static void handle(MinecraftServer server, ServerPlayerEntity player, ChatRequest req) {
		try {
			switch (req.kind()) {
				case "send" -> handleSend(server, player, req);
				case "confirm" -> handleConfirm(player, req);
				case "deny" -> handleDeny(player, req);
				case "cancel" -> handleCancel(player, req);
				case "list_sessions" -> handleListSessions(player, req);
				case "delete_session" -> handleDeleteSession(player, req);
				default -> pushError(player, req.requestId(), "未知请求类型: " + req.kind());
			}
		} catch (Exception e) {
			WorldForge.LOGGER.error("[WorldForge] chat request '{}' failed: {}", req.kind(), e.toString());
			pushError(player, req.requestId(), "服务端内部错误: " + e.getClass().getSimpleName());
		}
	}

	private static void handleSend(MinecraftServer server, ServerPlayerEntity player, ChatRequest req) {
		JsonObject body = parse(req.payload());
		String conversationId = str(body, "conversationId");
		if (conversationId == null || conversationId.isBlank()) {
			conversationId = "default";
		}
		String message = str(body, "message");
		if (message == null || message.isBlank()) {
			pushError(player, req.requestId(), "消息为空");
			return;
		}
		if (message.length() > MAX_MESSAGE_CHARS) {
			message = message.substring(0, MAX_MESSAGE_CHARS);
		}
		String seed = str(body, "history");
		if (seed != null && seed.length() > MAX_HISTORY_CHARS) {
			seed = null;
		}
		rememberConversation(player, conversationId);
		if (!isToolCallingAvailable()) {
			pushStatus(player, req.requestId(), conversationId, "error",
					"AI 未启用：服务器未配置 config/worldforge/agent.json（需要 apiKey）。");
			return;
		}
		AgentOrchestrator.send(server, player, conversationId, message, seed,
				new PushSink(player));
	}

	private static void handleConfirm(ServerPlayerEntity player, ChatRequest req) {
		JsonObject body = parse(req.payload());
		String conversationId = str(body, "conversationId");
		String confirmationId = str(body, "confirmationId");
		boolean ok = AgentOrchestrator.confirm(player.getUuid(), conversationId, confirmationId);
		pushStatus(player, req.requestId(), conversationId, ok ? "info" : "error",
				ok ? "已确认，服务器正在执行…" : "没有待确认的操作");
	}

	private static void handleDeny(ServerPlayerEntity player, ChatRequest req) {
		JsonObject body = parse(req.payload());
		String conversationId = str(body, "conversationId");
		String confirmationId = str(body, "confirmationId");
		boolean ok = AgentOrchestrator.deny(player.getUuid(), conversationId, confirmationId);
		pushStatus(player, req.requestId(), conversationId, ok ? "info" : "error",
				ok ? "已取消，世界数据未发生任何变化" : "没有待确认的操作");
	}

	private static void handleCancel(ServerPlayerEntity player, ChatRequest req) {
		JsonObject body = parse(req.payload());
		String conversationId = str(body, "conversationId");
		boolean ok = AgentOrchestrator.cancelTask(player.getUuid(), conversationId);
		pushStatus(player, req.requestId(), conversationId, ok ? "info" : "error",
				ok ? "已请求取消当前任务" : "当前没有正在执行的任务");
	}

	private static void handleListSessions(ServerPlayerEntity player, ChatRequest req) {
		JsonObject o = new JsonObject();
		JsonArray arr = new JsonArray();
		for (String id : CONVERSATIONS.getOrDefault(player.getUuid(),
				new java.util.concurrent.CopyOnWriteArrayList<>())) {
			arr.add(id);
		}
		o.add("sessions", arr);
		push(player, req.requestId(), "sessions", o.toString());
	}

	private static void handleDeleteSession(ServerPlayerEntity player, ChatRequest req) {
		JsonObject body = parse(req.payload());
		String conversationId = str(body, "conversationId");
		if (conversationId != null) {
			CONVERSATIONS.computeIfPresent(player.getUuid(), (k, v) -> {
				v.remove(conversationId);
				return v.isEmpty() ? null : v;
			});
			AgentOrchestrator.releasePlayer(player.getUuid());
		}
		pushStatus(player, req.requestId(), conversationId, "info", "会话已删除");
	}

	/** True when the configured provider can do structured tool calls. */
	private static boolean isToolCallingAvailable() {
		var p = dev.worldforge.ai.AgentBridge.providerForTools();
		return p instanceof ToolCallingProvider;
	}

	private static void rememberConversation(ServerPlayerEntity player, String conversationId) {
		CONVERSATIONS.computeIfAbsent(player.getUuid(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
				.addIfAbsent(conversationId);
	}

	// ---------------- client push ----------------

	/** The sink that streams agent events to one player. */
	private static final class PushSink implements AgentOrchestrator.AgentEventSink {

		private final ServerPlayerEntity player;

		PushSink(ServerPlayerEntity player) {
			this.player = player;
		}

		@Override
		public void onMessage(String conversationId, String role, String text) {
			JsonObject o = new JsonObject();
			o.addProperty("conversationId", conversationId);
			o.addProperty("role", role);
			o.addProperty("text", text);
			push(player, 0, "message", o.toString());
		}

		@Override
		public void onToolEvent(ToolEvent event) {
			push(player, 0, "tool", event.toJson().toString());
		}

		@Override
		public void onStatus(String conversationId, String status, String detail) {
			pushStatus(player, 0, conversationId, status, detail);
		}
	}

	/** Sends one event to a player (no-op for a vanilla client). */
	public static void push(ServerPlayerEntity player, int requestId, String type, String json) {
		if (!ServerPlayNetworking.canSend(player, ChatPush.ID)) {
			return;
		}
		String body = json == null ? "{}" : json;
		if (body.length() > MAX_PAYLOAD_CHARS) {
			body = body.substring(0, MAX_PAYLOAD_CHARS);
		}
		ServerPlayNetworking.send(player, new ChatPush(requestId, type, body));
	}

	private static void pushStatus(ServerPlayerEntity player, int requestId, String conversationId,
			String status, String detail) {
		JsonObject o = new JsonObject();
		o.addProperty("conversationId", conversationId == null ? "" : conversationId);
		o.addProperty("status", status);
		o.addProperty("detail", detail == null ? "" : detail);
		push(player, requestId, "status", o.toString());
	}

	private static void pushError(ServerPlayerEntity player, int requestId, String message) {
		pushStatus(player, requestId, "", "error", message);
	}

	// ---------------- helpers ----------------

	static JsonObject parse(String payload) {
		if (payload == null || payload.isBlank()) {
			return new JsonObject();
		}
		try {
			var el = JsonParser.parseString(payload);
			return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			return new JsonObject();
		}
	}

	static String str(JsonObject o, String key) {
		if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) {
			return null;
		}
		return o.get(key).getAsString();
	}

	/** Forgets a player's conversations (disconnect / world change). */
	public static void releasePlayer(java.util.UUID player) {
		CONVERSATIONS.remove(player);
		AgentOrchestrator.releasePlayer(player);
	}
}
