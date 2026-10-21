package dev.worldforge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.WorldForge;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.PermissionGate;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolEvent;
import dev.worldforge.ops.ToolEventSink;
import dev.worldforge.ops.ToolEventStatus;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolRegistry;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The v0.7.0 agent loop: the model asks for tools, the tools run against
 * authoritative server state, the results go back to the model, and the player
 * sees every step as a real event in the chat.
 *
 * <p>Hard rules this class enforces:</p>
 * <ul>
 *   <li>The model NEVER decides an outcome. A tool result is the only source
 *       of truth, and a "success" is only ever reported after the real
 *       operation returned.</li>
 *   <li>Every mutating call is prepared (its real impact is computed), shown
 *       to the player, and only executed after an explicit confirmation. The
 *       impact is then RE-validated: permission, scope and the target
 *       snapshot must still match, or the call is refused.</li>
 *   <li>Tool results are fed back as TOOL turns, never as system content, so
 *       a tool result can never masquerade as an instruction.</li>
 *   <li>The loop runs on a worker thread; world access always happens on the
 *       server thread.</li>
 * </ul>
 */
public final class AgentOrchestrator {

	/** The system prompt: the model's contract with the player. */
	public static final String SYSTEM_PROMPT = """
			你是 WorldForge 的 AI 创造助手，运行在 Minecraft 游戏内。玩家用自然语言描述需求，你负责判断、执行并如实反馈。

			工作方式：
			1. 先读取必要的游戏上下文（inspect_context / inspect_definition），不要凭猜测。
			2. 优先复用 WorldForge 已实现的能力；需要新能力时先用 list_capabilities 确认是否存在。
			3. 工具返回的结果是唯一的事实来源。工具没有报告成功，就不要说成功。
			4. 绝不编造执行结果。持久化成功但同步失败要分别说明，不要笼统说"全部成功"。
			5. 绝不影响玩家授权范围之外的世界、存档或文件。
			6. 不要让玩家自己去开终端、跑 PowerShell 或手动导出；导出由 export_mod 完成。
			7. 做不到的事情要如实说明原因，并给出可行的替代方案。
			8. 复杂的 Mod 需求需要后续开发流程，不要假装游戏内已经热加载了任意 Java 代码。

			修改世界的操作会先向玩家展示影响摘要，玩家明确确认后才会执行，并在执行前再次校验目标是否变化。""";

	/** hard cap on tool rounds within one player turn */
	public static final int MAX_TOOL_ROUNDS = 6;

	/** hard cap on tool calls within one player turn */
	public static final int MAX_TOOL_CALLS = 12;

	/** how long the model gets for one turn */
	public static final int TURN_TIMEOUT_SECONDS = 120;

	private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "wf-agent-worker");
		t.setDaemon(true);
		return t;
	});

	/** player -> conversationId -> session */
	private static final Map<UUID, Map<String, Session>> SESSIONS = new ConcurrentHashMap<>();
	private static final AtomicLong REQUEST_SEQ = new AtomicLong();

	/** Delivers events to the player. Set by the network layer. */
	public interface AgentEventSink {
		void onMessage(String conversationId, String role, String text);

		void onToolEvent(ToolEvent event);

		void onStatus(String conversationId, String status, String detail);
	}

	/** Per-conversation agent state. */
	public static final class Session {
		final String conversationId;
		final List<ChatTurn> history = java.util.Collections.synchronizedList(new ArrayList<>());
		volatile PendingConfirmation pending;
		final AtomicBoolean cancelled = new AtomicBoolean(false);
		volatile String runningTask = "";

		Session(String conversationId) {
			this.conversationId = conversationId;
		}
	}

	private AgentOrchestrator() {
	}

	/** The session for a player + conversation, created on demand. */
	public static Session session(UUID player, String conversationId) {
		return SESSIONS.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(conversationId == null ? "" : conversationId, Session::new);
	}

	/** Drops a player's sessions (disconnect / world change). */
	public static void releasePlayer(UUID player) {
		var map = SESSIONS.remove(player);
		if (map != null) {
			map.values().forEach(s -> {
				if (s.pending != null) {
					s.pending.cancel();
				}
				s.cancelled.set(true);
			});
		}
	}

	/** Drops every session (server stop). */
	public static void releaseAll() {
		SESSIONS.values().forEach(map -> map.values().forEach(s -> {
			if (s.pending != null) {
				s.pending.cancel();
			}
			s.cancelled.set(true);
		}));
		SESSIONS.clear();
	}

	/**
	 * Starts a player turn. Returns immediately; the work happens on a worker
	 * thread and every step is reported through the sink.
	 *
	 * @param server       the server
	 * @param player       the asking player
	 * @param conversationId the conversation
	 * @param message      what the player said
	 * @param seedHistory  optional persisted history to restore (JSON array of
	 *                     {@code {role,text}} objects); may be null
	 * @param sink         the event sink
	 */
	public static void send(MinecraftServer server, ServerPlayerEntity player, String conversationId,
			String message, String seedHistory, AgentEventSink sink) {
		if (message == null || message.isBlank()) {
			sink.onStatus(conversationId, "error", "消息为空");
			return;
		}
		Session session = session(player.getUuid(), conversationId);
		session.cancelled.set(false);
		if (seedHistory != null && !seedHistory.isBlank()) {
			seedHistory(session, seedHistory);
		}
		session.history.add(ChatTurn.user(message));
		sink.onMessage(conversationId, "user", message);
		WORKERS.submit(() -> runTurn(server, player, conversationId, session, sink));
	}

	/** Restores persisted display history into the agent session (as text only). */
	private static void seedHistory(Session session, String json) {
		try {
			JsonElement el = JsonParser.parseString(json);
			if (!el.isJsonArray()) {
				return;
			}
			for (JsonElement item : el.getAsJsonArray()) {
				if (!item.isJsonObject()) {
					continue;
				}
				JsonObject o = item.getAsJsonObject();
				String role = o.has("role") ? o.get("role").getAsString() : "assistant";
				String text = o.has("text") ? o.get("text").getAsString() : "";
				if (text.isBlank() || text.length() > 2000) {
					continue;
				}
				// only conversational turns are restored: a restored "tool"
				// turn could otherwise pose as a real tool result
				if ("user".equals(role)) {
					session.history.add(ChatTurn.user(text));
				} else {
					session.history.add(ChatTurn.assistant(text));
				}
			}
		} catch (Exception e) {
			WorldForge.LOGGER.warn("[WorldForge] could not restore chat history: {}", e.toString());
		}
	}

	// ---------------------------------------------------------------- loop

	private static void runTurn(MinecraftServer server, ServerPlayerEntity player, String conversationId,
			Session session, AgentEventSink sink) {
		String requestId = "r" + REQUEST_SEQ.incrementAndGet();
		ToolCallingProvider provider = provider();
		if (provider == null) {
			sink.onStatus(conversationId, "error", "AI 未启用：服务器未配置 config/worldforge/agent.json");
			return;
		}
		List<ToolDescriptor> tools = ToolRegistry.availableTo(player);
		try {
			for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
				if (session.cancelled.get()) {
					sink.onStatus(conversationId, "cancelled", "任务已取消");
					return;
				}
				List<ChatTurn> snapshot;
				synchronized (session.history) {
					snapshot = new ArrayList<>(session.history);
				}
				ChatTurn.Turn turn = awaitTurn(provider, server, player, conversationId, requestId,
						snapshot, tools, sink);
				if (turn == null) {
					return; // cancelled or failed; the sink already reported it
				}
				if (turn instanceof ChatTurn.Turn.Final finalText) {
					session.history.add(ChatTurn.assistant(finalText.text()));
					trimHistory(session);
					sink.onMessage(conversationId, "assistant", finalText.text());
					sink.onStatus(conversationId, "done", "");
					return;
				}
				// the model wants tools
				ChatTurn.Turn.Calls calls = (ChatTurn.Turn.Calls) turn;
				if (calls.calls().size() > MAX_TOOL_CALLS) {
					sink.onStatus(conversationId, "error", "单轮工具调用次数超限");
					return;
				}
				session.history.add(ChatTurn.assistantToolCalls(calls.calls()));
				List<ChatTurn> results = new ArrayList<>();
				boolean anyExecuted = false;
				for (ChatTurn.ModelToolCall call : calls.calls()) {
					if (session.cancelled.get()) {
						break;
					}
					ChatTurn result = executeToolCall(server, player, conversationId, requestId,
							session, call, tools, sink);
					results.add(result);
					if (result.role() == ChatTurn.Role.TOOL
							&& !result.content().startsWith("{\"ok\":false")) {
						anyExecuted = true;
					}
				}
				if (results.isEmpty()) {
					return;
				}
				session.history.addAll(results);
				trimHistory(session);
				if (!anyExecuted && round > 0) {
					// the model only produced failures; give it one more chance
					// to explain, then stop burning turns
					continue;
				}
			}
			sink.onStatus(conversationId, "error", "达到本轮工具调用上限，已停止。");
		} catch (Exception e) {
			WorldForge.LOGGER.warn("[WorldForge] agent turn failed", e);
			sink.onStatus(conversationId, "error", "AI 执行出错：" + e.getClass().getSimpleName());
		}
	}

	/** Keeps the in-memory history bounded. */
	private static void trimHistory(Session session) {
		synchronized (session.history) {
			int limit = Math.max(4, AgentBridge.config().historyLimit() * 2);
			while (session.history.size() > limit) {
				session.history.remove(0);
			}
		}
	}

	/** The provider, when it supports tool calling. */
	private static ToolCallingProvider provider() {
		var p = AgentBridge.providerForTools();
		return p instanceof ToolCallingProvider t ? t : null;
	}

	/**
	 * Runs one model turn, hopping back to the server thread to evaluate it.
	 *
	 * @return the turn, or {@code null} when the turn failed or was cancelled
	 */
	private static ChatTurn.Turn awaitTurn(ToolCallingProvider provider, MinecraftServer server,
			ServerPlayerEntity player, String conversationId, String requestId,
			List<ChatTurn> messages, List<ToolDescriptor> tools, AgentEventSink sink) {
		String system = SYSTEM_PROMPT;
		if (!supportsNativeToolCalling(provider)) {
			system = system + ToolCallingSupport.structuredContract(tools);
		}
		AtomicReference<ChatTurn.Turn> ref = new AtomicReference<>();
		AtomicReference<Exception> err = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		sink.onStatus(conversationId, "thinking", "");
		provider.completeWithTools(system, messages, tools).whenComplete((turn, e) -> {
			if (e != null) {
				err.set(e instanceof Exception ex ? ex : new RuntimeException(e));
			} else {
				ref.set(turn);
			}
			latch.countDown();
		});
		try {
			if (!latch.await(TURN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
				sink.onStatus(conversationId, "error", "模型响应超时");
				return null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		if (err.get() != null) {
			sink.onStatus(conversationId, "error", "模型调用失败：" + rootCause(err.get()));
			return null;
		}
		return ref.get();
	}

	/** Native tool calling is assumed available; the structured fallback covers the rest. */
	private static boolean supportsNativeToolCalling(ToolCallingProvider provider) {
		return true;
	}

	// ------------------------------------------------------- tool execution

	/**
	 * Validates, prepares, confirms and executes one tool call.
	 *
	 * @return the turn that carries the result back to the model
	 */
	private static ChatTurn executeToolCall(MinecraftServer server, ServerPlayerEntity player,
			String conversationId, String requestId, Session session, ChatTurn.ModelToolCall call,
			List<ToolDescriptor> tools, AgentEventSink sink) {
		ToolDescriptor descriptor = ToolRegistry.descriptor(call.name()).orElse(null);
		if (descriptor == null) {
			sink.onToolEvent(event(conversationId, requestId, call.id(), call.name(),
					ToolEventStatus.FAILED, "未知工具: " + call.name(), "unknown_tool", List.of()));
			return ChatTurn.toolResult(call.id(), call.name(),
					"{\"ok\":false,\"error_code\":\"unknown_tool\",\"message\":\"未知工具: " + call.name() + "\"}");
		}
		// the player must be allowed to see the tool at all
		if (!tools.contains(descriptor)) {
			sink.onToolEvent(event(conversationId, requestId, call.id(), call.name(),
					ToolEventStatus.FAILED, "没有权限使用该工具", "no_permission", List.of()));
			return ChatTurn.toolResult(call.id(), call.name(),
					"{\"ok\":false,\"error_code\":\"no_permission\",\"message\":\"没有权限使用该工具\"}");
		}
		// strict schema validation of the model-supplied arguments
		JsonObject rawArgs = new JsonObject();
		try {
			JsonElement el = JsonParser.parseString(call.argumentsJson());
			if (el.isJsonObject()) {
				rawArgs = el.getAsJsonObject();
			}
		} catch (Exception ignored) {
			// treated as an empty argument object below
		}
		var validation = JsonSchemaValidator.validate(descriptor.parameters(), rawArgs);
		if (!validation.ok()) {
			String msg = String.join("; ", validation.errors());
			sink.onToolEvent(event(conversationId, requestId, call.id(), call.name(),
					ToolEventStatus.FAILED, "参数不合法: " + msg, "invalid_arguments", List.of()));
			return ChatTurn.toolResult(call.id(), call.name(),
					"{\"ok\":false,\"error_code\":\"invalid_arguments\",\"message\":\"" + escape(msg) + "\"}");
		}
		ToolRequest request = new ToolRequest(requestId, conversationId, call.id(), call.name(),
				validation.values());

		// authority check BEFORE anything else
		String auth = PermissionGate.checkAuthority(descriptor, player);
		if (auth != null) {
			sink.onToolEvent(event(conversationId, requestId, call.id(), call.name(),
					ToolEventStatus.FAILED, "权限不足", auth, List.of()));
			return ChatTurn.toolResult(call.id(), call.name(),
					"{\"ok\":false,\"error_code\":\"" + auth + "\",\"message\":\"权限不足\"}");
		}

		ToolContext base = context(server, player, conversationId, descriptor);
		sink.onToolEvent(event(conversationId, requestId, call.id(), call.name(),
				ToolEventStatus.STARTED, "开始执行 " + descriptor.name(), "", List.of()));

		if (descriptor.requiresConfirmation()) {
			return confirmThenExecute(server, player, conversationId, requestId, session, request,
					descriptor, base, sink);
		}
		ToolResult result = runTool(server, base, request, descriptor, sink);
		emitResult(conversationId, requestId, request, result, sink);
		return ChatTurn.toolResult(call.id(), call.name(), resultJson(result));
	}

	/**
	 * The prepare -&gt; confirm -&gt; re-validate -&gt; execute flow.
	 */
	private static ChatTurn confirmThenExecute(MinecraftServer server, ServerPlayerEntity player,
			String conversationId, String requestId, Session session, ToolRequest request,
			ToolDescriptor descriptor, ToolContext base, AgentEventSink sink) {
		ToolPreparation prep = runPrepare(server, base, request, descriptor);
		if (prep == null || prep.summary().isBlank()) {
			sink.onToolEvent(event(conversationId, requestId, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "无法确定该操作的影响范围，已拒绝执行", "not_allowed", List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"not_allowed\",\"message\":\"无法确定该操作的影响范围，已拒绝执行\"}");
		}
		PendingConfirmation pc = new PendingConfirmation("c" + requestId + "#" + request.toolCallId(),
				conversationId, requestId, request.toolCallId(), request.toolName(), prep.summary(),
				prep.itemIds(), prep.blockIds(), prep.snapshot());
		session.pending = pc;
		sink.onToolEvent(new ToolEvent(conversationId, requestId, request.toolCallId(), 0,
				System.currentTimeMillis(), request.toolName(), ToolEventStatus.AWAITING_CONFIRMATION,
				prep.summary(), "", prep.itemIds(), pc.confirmationId()));
		boolean approved = pc.await(PendingConfirmation.DEFAULT_TIMEOUT_SECONDS);
		session.pending = null;
		if (!approved) {
			sink.onToolEvent(event(conversationId, requestId, request.toolCallId(), request.toolName(),
					ToolEventStatus.CANCELLED, "玩家未确认，操作已取消，世界未发生任何变化",
					"cancelled", List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"cancelled\",\"message\":\"玩家未确认，操作已取消\"}");
		}
		// --- re-validate EVERYTHING after the confirmation ---
		String auth = PermissionGate.checkAuthority(descriptor, player);
		if (auth != null) {
			sink.onToolEvent(event(conversationId, requestId, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "确认后权限校验失败", auth, List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"" + auth + "\",\"message\":\"确认后权限校验失败\"}");
		}
		ToolContext scoped = base.withScope(ToolContext.ToolScope.mutating(conversationId,
				prep.itemIds(), prep.blockIds()));
		String scopeErr = PermissionGate.checkScope(scoped, prep.itemIds(), prep.blockIds());
		if (scopeErr != null) {
			sink.onToolEvent(event(conversationId, requestId, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "操作范围超出已授权内容", scopeErr, List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"" + scopeErr + "\",\"message\":\"操作范围超出已授权内容\"}");
		}
		// the target must not have changed while the player was deciding
		ToolPreparation now = runPrepare(server, scoped, request, descriptor);
		if (now == null || !now.snapshot().equals(prep.snapshot())) {
			sink.onToolEvent(event(conversationId, requestId, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "目标在你确认期间发生了变化，已拒绝执行",
					"target_changed", List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"target_changed\",\"message\":\"目标在你确认期间发生了变化，已拒绝执行\"}");
		}
		ToolResult result = runTool(server, scoped, request, descriptor, sink);
		emitResult(conversationId, requestId, request, result, sink);
		return ChatTurn.toolResult(request.toolCallId(), request.toolName(), resultJson(result));
	}

	/** Runs the preparer on the server thread. */
	private static ToolPreparation runPrepare(MinecraftServer server, ToolContext ctx, ToolRequest request,
			ToolDescriptor descriptor) {
		var tool = ToolRegistry.tool(descriptor.name()).orElse(null);
		if (tool == null || tool.preparer() == null) {
			return ToolPreparation.none();
		}
		AtomicReference<ToolPreparation> ref = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		server.execute(() -> {
			try {
				ref.set(tool.preparer().prepare(ctx, request));
			} catch (Exception e) {
				ref.set(ToolPreparation.none());
			} finally {
				latch.countDown();
			}
		});
		try {
			latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return ref.get();
	}

	/**
	 * Executes the tool. Short tools hop onto the server thread; a long
	 * running tool (a Gradle build) stays on this worker thread so the server
	 * is never blocked.
	 */
	private static ToolResult runTool(MinecraftServer server, ToolContext ctx, ToolRequest request,
			ToolDescriptor descriptor, AgentEventSink sink) {
		var tool = ToolRegistry.tool(descriptor.name()).orElse(null);
		if (tool == null) {
			return ToolResult.fail("unknown_tool", "未知工具");
		}
		ToolEventSink events = e -> sink.onToolEvent(e);
		try {
			if (descriptor.longRunning()) {
				return tool.executor().execute(ctx, request, events);
			}
			AtomicReference<ToolResult> ref = new AtomicReference<>();
			CountDownLatch latch = new CountDownLatch(1);
			server.execute(() -> {
				try {
					ref.set(tool.executor().execute(ctx, request, events));
				} catch (Throwable t) {
					ref.set(ToolResult.fail("server_error",
							"工具执行异常: " + t.getClass().getSimpleName()));
				} finally {
					latch.countDown();
				}
			});
			if (!latch.await(ExportTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
				return ToolResult.fail("server_error", "工具执行超时");
			}
			ToolResult r = ref.get();
			return r == null ? ToolResult.fail("server_error", "工具没有返回结果") : r;
		} catch (Exception e) {
			return ToolResult.fail("server_error", "工具执行异常: " + e.getClass().getSimpleName());
		}
	}

	/** A long running tool may take up to 12 minutes (Gradle build). */
	private static long ExportTimeoutSeconds() {
		return 12 * 60L;
	}

	/** Builds the tool context. */
	private static ToolContext context(MinecraftServer server, ServerPlayerEntity player,
			String conversationId, ToolDescriptor descriptor) {
		var world = player.getServerWorld();
		return new ToolContext(player, server, world, world.getRegistryKey(), conversationId,
				descriptor.mutatesWorld()
						? ToolContext.ToolScope.mutating(conversationId, java.util.Set.of(), java.util.Set.of())
						: ToolContext.ToolScope.readOnly(conversationId));
	}

	private static void emitResult(String conversationId, String requestId, ToolRequest request,
			ToolResult result, AgentEventSink sink) {
		sink.onToolEvent(event(conversationId, requestId, request.toolCallId(), request.toolName(),
				result.ok() ? ToolEventStatus.SUCCEEDED : ToolEventStatus.FAILED,
				result.displayText(), result.errorCode(), result.affectedTargets()));
	}

	private static ToolEvent event(String conversationId, String requestId, String toolCallId,
			String toolName, ToolEventStatus status, String summary, String errorCode,
			List<String> targets) {
		return new ToolEvent(conversationId, requestId, toolCallId, 0, System.currentTimeMillis(),
				toolName, status, summary, errorCode, targets);
	}

	/** The tool result as JSON for the model. */
	private static String resultJson(ToolResult result) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", result.ok());
		o.addProperty("message", result.displayText());
		if (!result.errorCode().isBlank()) {
			o.addProperty("error_code", result.errorCode());
		}
		o.addProperty("persisted", result.persisted());
		o.addProperty("applied", result.applied());
		o.addProperty("sync", result.syncStatus());
		if (result.dataJson() != null && !result.dataJson().isBlank()) {
			try {
				JsonElement el = JsonParser.parseString(result.dataJson());
				if (el.isJsonObject()) {
					o.add("data", el.getAsJsonObject());
				}
			} catch (Exception ignored) {
				o.addProperty("data_raw", result.dataJson());
			}
		}
		return o.toString();
	}

	private static String escape(String s) {
		return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
	}

	private static String rootCause(Throwable t) {
		Throwable c = t;
		while (c.getCause() != null && c.getCause() != c) {
			c = c.getCause();
		}
		String m = c.getMessage();
		return c.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
	}

	// ------------------------------------------------------------ confirming

	/** The player confirmed a pending operation. */
	public static boolean confirm(UUID player, String conversationId, String confirmationId) {
		Session s = SESSIONS.get(player) == null ? null : SESSIONS.get(player).get(conversationId);
		if (s == null || s.pending == null) {
			return false;
		}
		PendingConfirmation pc = s.pending;
		if (confirmationId != null && !confirmationId.isBlank()
				&& !pc.confirmationId().equals(confirmationId)) {
			return false;
		}
		pc.confirm();
		return true;
	}

	/** The player refused a pending operation. */
	public static boolean deny(UUID player, String conversationId, String confirmationId) {
		Session s = SESSIONS.get(player) == null ? null : SESSIONS.get(player).get(conversationId);
		if (s == null || s.pending == null) {
			return false;
		}
		PendingConfirmation pc = s.pending;
		if (confirmationId != null && !confirmationId.isBlank()
				&& !pc.confirmationId().equals(confirmationId)) {
			return false;
		}
		pc.cancel();
		return true;
	}

	/** The player cancelled the running task of a conversation. */
	public static boolean cancelTask(UUID player, String conversationId) {
		Session s = SESSIONS.get(player) == null ? null : SESSIONS.get(player).get(conversationId);
		if (s == null) {
			return false;
		}
		s.cancelled.set(true);
		if (s.pending != null) {
			s.pending.cancel();
		}
		return true;
	}

	/** The summary of the pending confirmation, or null. */
	public static PendingConfirmation pendingFor(UUID player, String conversationId) {
		Session s = SESSIONS.get(player) == null ? null : SESSIONS.get(player).get(conversationId);
		return s == null ? null : s.pending;
	}
}
