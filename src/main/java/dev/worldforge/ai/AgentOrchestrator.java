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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The agent loop: the model asks for tools, the tools run against
 * authoritative server state, the results go back to the model, and the player
 * sees every step as a real event in the chat.
 *
 * <p>v0.7.1 — the concurrency rules the v0.7.0 release did not have:</p>
 * <ul>
 *   <li><b>One task per conversation.</b> A second message while a task is
 *       running is either QUEUED (bounded) or REFUSED with a clear reason —
 *       never silently executed in parallel. Different conversations run
 *       independently.</li>
 *   <li><b>Every task owns its identity.</b> A task has a task id, a cancel
 *       token and a generation. It may only touch the session while it still
 *       owns it, so a slow, cancelled task can never clear the state of the
 *       task that replaced it.</li>
 *   <li><b>Exactly one terminal state.</b> A task ends in SUCCEEDED, FAILED or
 *       CANCELLED — never two of them, and never a success after a
 *       cancellation.</li>
 *   <li><b>Cancellation really cancels.</b> The cancel token carries hooks, so
 *       a running Gradle build is terminated and an in-flight model request
 *       is aborted.</li>
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

	/** The task states the client can see. */
	public enum TaskState {
		/** the model is being asked */
		THINKING,
		/** a tool is waiting for the player's approval */
		AWAITING_CONFIRMATION,
		/** an approved tool is running */
		EXECUTING,
		/** a cancellation was requested and is being carried out */
		CANCELLING,
		/** the task finished successfully (terminal) */
		SUCCEEDED,
		/** the task failed (terminal) */
		FAILED,
		/** the task was cancelled (terminal) */
		CANCELLED,
		/** the task is queued behind another one */
		QUEUED
	}

	/** player -> conversationId -> session */
	private static final Map<UUID, Map<String, Session>> SESSIONS = new ConcurrentHashMap<>();
	/** player -> how many tasks are running for them right now */
	private static final Map<UUID, AtomicInteger> ACTIVE_TASKS = new ConcurrentHashMap<>();
	private static final AtomicLong REQUEST_SEQ = new AtomicLong();
	private static final AtomicLong TASK_SEQ = new AtomicLong();

	/**
	 * The worker pool.
	 *
	 * <p>v0.7.1: a FIXED pool with a hard cap, not a cached pool. A cached
	 * pool grows without bound, so a player who spams messages could spawn
	 * unlimited workers.</p>
	 */
	private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
			AgentTimeouts.MAX_WORKERS, new ThreadFactory() {
				private final AtomicInteger seq = new AtomicInteger();

				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "wf-agent-worker-" + seq.incrementAndGet());
					t.setDaemon(true);
					return t;
				}
			});

	/** Delivers events to the player. Set by the network layer. */
	public interface AgentEventSink {
		void onMessage(String conversationId, String role, String text, String taskId);

		void onToolEvent(ToolEvent event);

		void onStatus(String conversationId, String state, String detail, String taskId);
	}

	/** What the network layer needs to know after a send request. */
	public record SendResult(boolean accepted, boolean queued, String reason, String taskId) {

		public static SendResult accepted(String taskId) {
			return new SendResult(true, false, "", taskId);
		}

		public static SendResult queued(String reason, String taskId) {
			return new SendResult(false, true, reason, taskId);
		}

		public static SendResult rejected(String reason) {
			return new SendResult(false, false, reason, "");
		}
	}

	/** A message waiting for the current task to finish. */
	private record QueuedMessage(String message, String seedHistory, AgentEventSink sink) {
	}

	/** Per-conversation agent state. */
	public static final class Session {
		final String conversationId;
		final List<ChatTurn> history = java.util.Collections.synchronizedList(new ArrayList<>());
		final AtomicReference<TaskToken> activeTask = new AtomicReference<>();
		final AtomicReference<PendingConfirmation> pending = new AtomicReference<>();
		final AtomicLong generation = new AtomicLong();
		final AtomicBoolean released = new AtomicBoolean(false);
		final Deque<QueuedMessage> queue = new ArrayDeque<>();

		Session(String conversationId) {
			this.conversationId = conversationId;
		}

		/** A read-only view of the conversation history. */
		public List<ChatTurn> historyView() {
			synchronized (history) {
				return List.copyOf(history);
			}
		}
	}

	private AgentOrchestrator() {
	}

	// ------------------------------------------------------------- sessions

	/** The session for a player + conversation, created on demand. */
	public static Session session(UUID player, String conversationId) {
		return SESSIONS.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(conversationId == null ? "" : conversationId, Session::new);
	}

	/** The session for a player + conversation, or {@code null} when it does not exist. */
	public static Session existingSession(UUID player, String conversationId) {
		var map = SESSIONS.get(player);
		return map == null ? null : map.get(conversationId == null ? "" : conversationId);
	}

	/**
	 * Releases ONE conversation: its running task is cancelled and its pending
	 * confirmation is closed. The player's other conversations keep running.
	 *
	 * <p>v0.7.1: this is what "delete a conversation" must do. The previous
	 * implementation called {@link #releasePlayer(UUID)} and therefore killed
	 * EVERY conversation of the player.</p>
	 *
	 * @return true when a conversation was released
	 */
	public static boolean releaseSession(UUID player, String conversationId) {
		var map = SESSIONS.get(player);
		if (map == null) {
			return false;
		}
		Session session = map.remove(conversationId == null ? "" : conversationId);
		if (session == null) {
			return false;
		}
		session.released.set(true);
		cancelTaskOf(session);
		return true;
	}

	/** Drops a player's sessions (disconnect / world change). */
	public static void releasePlayer(UUID player) {
		var map = SESSIONS.remove(player);
		if (map != null) {
			map.values().forEach(AgentOrchestrator::cancelTaskOf);
		}
		ACTIVE_TASKS.remove(player);
	}

	/** Drops every session (server stop). */
	public static void releaseAll() {
		SESSIONS.values().forEach(map -> map.values().forEach(AgentOrchestrator::cancelTaskOf));
		SESSIONS.clear();
		ACTIVE_TASKS.clear();
	}

	/** Cancels the running task and the pending confirmation of a session. */
	private static void cancelTaskOf(Session session) {
		TaskToken token = session.activeTask.get();
		if (token != null) {
			token.requestCancel();
		}
		PendingConfirmation pc = session.pending.get();
		if (pc != null) {
			pc.cancel();
		}
	}

	// ------------------------------------------------------------------ send

	/**
	 * Starts a player turn, or queues it behind the running one.
	 *
	 * @return what the network layer should tell the player
	 */
	public static SendResult send(MinecraftServer server, ServerPlayerEntity player, String conversationId,
			String message, String seedHistory, AgentEventSink sink) {
		if (message == null || message.isBlank()) {
			return SendResult.rejected("消息为空");
		}
		Session session = session(player.getUuid(), conversationId);
		if (session.released.get()) {
			return SendResult.rejected("这个会话已经被删除");
		}
		TaskToken active = session.activeTask.get();
		if (active != null && active.state() != TaskToken.State.FINISHED) {
			// one task per conversation: queue it, or say why it cannot run
			synchronized (session.queue) {
				if (session.queue.size() >= AgentTimeouts.MAX_QUEUED_MESSAGES) {
					String reason = "这个对话已经有 " + session.queue.size() + " 条排队消息了，"
							+ "请先取消或等当前任务完成再发。";
					sink.onStatus(conversationId, TaskState.FAILED.name(), reason, active.taskId());
					return SendResult.rejected(reason);
				}
				session.queue.add(new QueuedMessage(message, seedHistory, sink));
				int position = session.queue.size();
				String reason = "上一个任务还在进行中，这条消息已排队（第 " + position + " 条）。";
				sink.onStatus(conversationId, TaskState.QUEUED.name(), reason, active.taskId());
				return SendResult.queued(reason, active.taskId());
			}
		}
		// the per-player cap: a player may not run an unbounded number of tasks
		int running = activeTaskCount(player.getUuid());
		if (running >= AgentTimeouts.MAX_ACTIVE_TASKS_PER_PLAYER) {
			String reason = "你同时运行的任务太多（上限 " + AgentTimeouts.MAX_ACTIVE_TASKS_PER_PLAYER
					+ " 个），请先等一个结束。";
			sink.onStatus(conversationId, TaskState.FAILED.name(), reason, "");
			return SendResult.rejected(reason);
		}
		startTask(server, player, session, message, seedHistory, sink);
		return SendResult.accepted(session.activeTask.get() == null ? "" : session.activeTask.get().taskId());
	}

	/** How many tasks a player currently runs. */
	public static int activeTaskCount(UUID player) {
		var c = ACTIVE_TASKS.get(player);
		return c == null ? 0 : c.get();
	}

	/** Starts one task and hands the session over to it. */
	private static void startTask(MinecraftServer server, ServerPlayerEntity player, Session session,
			String message, String seedHistory, AgentEventSink sink) {
		long generation = session.generation.incrementAndGet();
		String taskId = "t" + TASK_SEQ.incrementAndGet();
		TaskToken token = new TaskToken(taskId, session.conversationId, player.getUuid(), generation);
		if (!session.activeTask.compareAndSet(null, token)) {
			// someone else took the slot in the meantime — refuse instead of
			// running two tasks in one conversation
			sink.onStatus(session.conversationId, TaskState.FAILED.name(),
					"这个对话已经有任务在执行，请稍候。", taskId);
			return;
		}
		if (seedHistory != null && !seedHistory.isBlank()) {
			seedHistory(session, seedHistory);
		}
		session.history.add(ChatTurn.user(message));
		sink.onMessage(session.conversationId, "user", message, taskId);
		ACTIVE_TASKS.computeIfAbsent(player.getUuid(), k -> new AtomicInteger()).incrementAndGet();
		WORKERS.submit(() -> {
			try {
				runTurn(server, player, session.conversationId, session, token, sink);
			} finally {
				session.activeTask.compareAndSet(token, null);
				ACTIVE_TASKS.computeIfAbsent(player.getUuid(), k -> new AtomicInteger()).decrementAndGet();
				startQueuedNext(server, player, session);
			}
		});
	}

	/** Starts the next queued message, if the session is still alive. */
	private static void startQueuedNext(MinecraftServer server, ServerPlayerEntity player, Session session) {
		if (session.released.get()) {
			return;
		}
		QueuedMessage next;
		synchronized (session.queue) {
			next = session.queue.poll();
		}
		if (next == null) {
			return;
		}
		if (activeTaskCount(player.getUuid()) >= AgentTimeouts.MAX_ACTIVE_TASKS_PER_PLAYER) {
			next.sink.onStatus(session.conversationId, TaskState.FAILED.name(),
					"同时运行的任务太多，排队消息没有执行。", "");
			return;
		}
		startTask(server, player, session, next.message(), next.seedHistory(), next.sink());
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

	/** True while the task still owns the session. */
	private static boolean owns(Session session, TaskToken token) {
		return !session.released.get()
				&& session.activeTask.get() == token
				&& session.generation.get() == token.generation();
	}

	private static void runTurn(MinecraftServer server, ServerPlayerEntity player, String conversationId,
			Session session, TaskToken token, AgentEventSink sink) {
		String requestId = "r" + REQUEST_SEQ.incrementAndGet();
		ToolCallingProvider provider = provider();
		if (provider == null) {
			sink.onStatus(conversationId, TaskState.FAILED.name(),
					"AI 未启用：服务器未配置 config/worldforge/agent.json", token.taskId());
			token.finish();
			return;
		}
		List<ToolDescriptor> tools = ToolRegistry.availableTo(player);
		// the budgets accumulate over the WHOLE task, not per round
		int roundsUsed = 0;
		int callsUsed = 0;
		try {
			while (roundsUsed < AgentTimeouts.MAX_TOOL_ROUNDS_PER_TASK) {
				roundsUsed++;
				if (token.isCancelled()) {
					reportCancelled(conversationId, token, sink, "任务已取消");
					return;
				}
				if (!owns(session, token)) {
					// the session was released or taken over — stop quietly
					return;
				}
				List<ChatTurn> snapshot;
				synchronized (session.history) {
					snapshot = new ArrayList<>(session.history);
				}
				ChatTurn.Turn turn = awaitTurn(provider, server, player, conversationId, requestId,
						token, snapshot, tools, sink);
				if (turn == null) {
					// cancelled, timed out or failed — the sink already reported it
					token.finish();
					return;
				}
				if (turn instanceof ChatTurn.Turn.Final finalText) {
					if (!owns(session, token)) {
						return;
					}
					session.history.add(ChatTurn.assistant(finalText.text()));
					trimHistory(session);
					sink.onMessage(conversationId, "assistant", finalText.text(), token.taskId());
					sink.onStatus(conversationId, TaskState.SUCCEEDED.name(), "", token.taskId());
					token.finish();
					return;
				}
				ChatTurn.Turn.Calls calls = (ChatTurn.Turn.Calls) turn;
				callsUsed += calls.calls().size();
				if (callsUsed > AgentTimeouts.MAX_TOOL_CALLS_PER_TASK) {
					sink.onStatus(conversationId, TaskState.FAILED.name(),
							"这个任务的工具调用次数超限（上限 "
									+ AgentTimeouts.MAX_TOOL_CALLS_PER_TASK + " 次）。", token.taskId());
					token.finish();
					return;
				}
				if (!owns(session, token)) {
					return;
				}
				session.history.add(ChatTurn.assistantToolCalls(calls.calls()));
				List<ChatTurn> results = new ArrayList<>();
				boolean anyExecuted = false;
				for (ChatTurn.ModelToolCall call : calls.calls()) {
					if (token.isCancelled()) {
						break;
					}
					ChatTurn result = executeToolCall(server, player, conversationId, requestId,
							session, token, call, tools, sink);
					results.add(result);
					if (result.role() == ChatTurn.Role.TOOL
							&& !result.content().startsWith("{\"ok\":false")) {
						anyExecuted = true;
					}
				}
				if (token.isCancelled()) {
					reportCancelled(conversationId, token, sink, "任务已取消");
					return;
				}
				if (results.isEmpty()) {
					token.finish();
					return;
				}
				if (owns(session, token)) {
					session.history.addAll(results);
					trimHistory(session);
				}
				if (!anyExecuted && roundsUsed > 1) {
					// the model only produced failures; give it one more chance
					// to explain, then stop burning turns
					continue;
				}
			}
			sink.onStatus(conversationId, TaskState.FAILED.name(),
					"达到本轮工具调用上限，已停止。", token.taskId());
			token.finish();
		} catch (Exception e) {
			WorldForge.LOGGER.warn("[WorldForge] agent turn failed", e);
			if (!token.isCancelled()) {
				sink.onStatus(conversationId, TaskState.FAILED.name(),
						"AI 执行出错：" + e.getClass().getSimpleName(), token.taskId());
			}
			token.finish();
		}
	}

	/** Reports the single terminal CANCELLED state. */
	private static void reportCancelled(String conversationId, TaskToken token, AgentEventSink sink,
			String reason) {
		token.cancelModelRequest();
		sink.onStatus(conversationId, TaskState.CANCELLED.name(), reason, token.taskId());
		token.finish();
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
	 * Runs one model turn.
	 *
	 * <p>The wait is sliced, so a cancellation is noticed within half a
	 * second and the in-flight HTTP request is aborted instead of being left
	 * to finish on its own.</p>
	 *
	 * @return the turn, or {@code null} when the turn failed or was cancelled
	 */
	private static ChatTurn.Turn awaitTurn(ToolCallingProvider provider, MinecraftServer server,
			ServerPlayerEntity player, String conversationId, String requestId, TaskToken token,
			List<ChatTurn> messages, List<ToolDescriptor> tools, AgentEventSink sink) {
		String system = SYSTEM_PROMPT + ToolCallingSupport.structuredContract(tools);
		sink.onStatus(conversationId, TaskState.THINKING.name(), "", token.taskId());
		var future = provider.completeWithTools(system, messages, tools);
		token.setModelRequest(future);
		long deadline = System.currentTimeMillis()
				+ AgentTimeouts.MODEL_REQUEST_SECONDS * 1000L;
		while (true) {
			if (token.isCancelled()) {
				future.cancel(true);
				return null;
			}
			long remaining = deadline - System.currentTimeMillis();
			if (remaining <= 0) {
				future.cancel(true);
				sink.onStatus(conversationId, TaskState.FAILED.name(), "模型响应超时", token.taskId());
				return null;
			}
			try {
				// the VALUE of the future is the turn: waiting for a
				// dependent callback instead would race with the completion
				return future.get(Math.min(remaining, 500), TimeUnit.MILLISECONDS);
			} catch (TimeoutException ignored) {
				// look again: the task may have been cancelled meanwhile
			} catch (ExecutionException e) {
				sink.onStatus(conversationId, TaskState.FAILED.name(),
						"模型调用失败：" + rootCause(e.getCause() == null ? e : e.getCause()),
						token.taskId());
				return null;
			} catch (java.util.concurrent.CancellationException e) {
				return null;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
	}

	// ------------------------------------------------------- tool execution

	/**
	 * Validates, prepares, confirms and executes one tool call.
	 *
	 * @return the turn that carries the result back to the model
	 */
	private static ChatTurn executeToolCall(MinecraftServer server, ServerPlayerEntity player,
			String conversationId, String requestId, Session session, TaskToken token,
			ChatTurn.ModelToolCall call, List<ToolDescriptor> tools, AgentEventSink sink) {
		ToolDescriptor descriptor = ToolRegistry.descriptor(call.name()).orElse(null);
		if (descriptor == null) {
			sink.onToolEvent(event(conversationId, requestId, token, call.id(), call.name(),
					ToolEventStatus.FAILED, "未知工具: " + call.name(), "unknown_tool", List.of()));
			return ChatTurn.toolResult(call.id(), call.name(),
					"{\"ok\":false,\"error_code\":\"unknown_tool\",\"message\":\"未知工具: " + call.name() + "\"}");
		}
		// the player must be allowed to see the tool at all
		if (!tools.contains(descriptor)) {
			sink.onToolEvent(event(conversationId, requestId, token, call.id(), call.name(),
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
			sink.onToolEvent(event(conversationId, requestId, token, call.id(), call.name(),
					ToolEventStatus.FAILED, "参数不合法: " + msg, "invalid_arguments", List.of()));
			return ChatTurn.toolResult(call.id(), call.name(),
					"{\"ok\":false,\"error_code\":\"invalid_arguments\",\"message\":\"" + escape(msg) + "\"}");
		}
		ToolRequest request = new ToolRequest(requestId, conversationId, call.id(), call.name(),
				validation.values());

		// authority check BEFORE anything else
		String auth = PermissionGate.checkAuthority(descriptor, player);
		if (auth != null) {
			sink.onToolEvent(event(conversationId, requestId, token, call.id(), call.name(),
					ToolEventStatus.FAILED, "权限不足", auth, List.of()));
			return ChatTurn.toolResult(call.id(), call.name(),
					"{\"ok\":false,\"error_code\":\"" + auth + "\",\"message\":\"权限不足\"}");
		}

		ToolContext base = context(server, player, conversationId, descriptor, token);
		sink.onToolEvent(event(conversationId, requestId, token, call.id(), call.name(),
				ToolEventStatus.STARTED, "开始执行 " + descriptor.name(), "", List.of()));

		if (descriptor.requiresConfirmation()) {
			return confirmThenExecute(server, player, conversationId, requestId, session, token,
					request, descriptor, base, sink);
		}
		ToolResult result = runTool(server, base, request, descriptor, token, sink);
		emitResult(conversationId, requestId, token, request, result, sink);
		return ChatTurn.toolResult(call.id(), call.name(), resultJson(result));
	}

	/**
	 * The prepare -&gt; confirm -&gt; re-validate -&gt; execute flow.
	 */
	private static ChatTurn confirmThenExecute(MinecraftServer server, ServerPlayerEntity player,
			String conversationId, String requestId, Session session, TaskToken token, ToolRequest request,
			ToolDescriptor descriptor, ToolContext base, AgentEventSink sink) {
		ToolPreparation prep = runPrepare(server, base, request, descriptor);
		if (prep == null || prep.summary().isBlank()) {
			sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "无法确定该操作的影响范围，已拒绝执行", "not_allowed", List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"not_allowed\",\"message\":\"无法确定该操作的影响范围，已拒绝执行\"}");
		}
		PendingConfirmation pc = new PendingConfirmation("c" + requestId + "#" + request.toolCallId(),
				conversationId, requestId, request.toolCallId(), request.toolName(), prep.summary(),
				prep.itemIds(), prep.blockIds(), prep.snapshot(), token.generation());
		// only the OWNING task may publish its confirmation
		if (!session.pending.compareAndSet(null, pc)) {
			pc.cancel();
			sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "该会话还有另一个操作在等待确认", "busy", List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"busy\",\"message\":\"该会话还有另一个操作在等待确认\"}");
		}
		sink.onStatus(conversationId, TaskState.AWAITING_CONFIRMATION.name(), prep.summary(),
				token.taskId());
		sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
				ToolEventStatus.AWAITING_CONFIRMATION, prep.summary(), "", prep.itemIds(),
				pc.confirmationId()));
		boolean approved = pc.await(AgentTimeouts.CONFIRMATION_WAIT_SECONDS);
		session.pending.compareAndSet(pc, null);
		if (!approved) {
			String reason = pc.reason();
			sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
					pc.state() == PendingConfirmation.State.TIMED_OUT
							? ToolEventStatus.FAILED : ToolEventStatus.CANCELLED,
					reason, pc.state() == PendingConfirmation.State.TIMED_OUT ? "confirmation_timeout" : "cancelled",
					List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"cancelled\",\"message\":\"" + escape(reason) + "\"}");
		}
		if (token.isCancelled()) {
			sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
					ToolEventStatus.CANCELLED, "任务已取消，操作未执行", "cancelled", List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"cancelled\",\"message\":\"任务已取消，操作未执行\"}");
		}
		// --- re-validate EVERYTHING after the confirmation ---
		String auth = PermissionGate.checkAuthority(descriptor, player);
		if (auth != null) {
			sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "确认后权限校验失败", auth, List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"" + auth + "\",\"message\":\"确认后权限校验失败\"}");
		}
		ToolContext scoped = base.withScope(ToolContext.ToolScope.mutating(conversationId,
				prep.itemIds(), prep.blockIds()))
				.withConfirmedSummary(prep.summary());
		String scopeErr = PermissionGate.checkScope(scoped, prep.itemIds(), prep.blockIds());
		if (scopeErr != null) {
			sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "操作范围超出已授权内容", scopeErr, List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"" + scopeErr + "\",\"message\":\"操作范围超出已授权内容\"}");
		}
		// the target must not have changed while the player was deciding
		ToolPreparation now = runPrepare(server, scoped, request, descriptor);
		if (now == null || !now.snapshot().equals(prep.snapshot())) {
			sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
					ToolEventStatus.FAILED, "目标在你确认期间发生了变化，已拒绝执行",
					"target_changed", List.of()));
			return ChatTurn.toolResult(request.toolCallId(), request.toolName(),
					"{\"ok\":false,\"error_code\":\"target_changed\",\"message\":\"目标在你确认期间发生了变化，已拒绝执行\"}");
		}
		sink.onStatus(conversationId, TaskState.EXECUTING.name(), "", token.taskId());
		ToolResult result = runTool(server, scoped, request, descriptor, token, sink);
		emitResult(conversationId, requestId, token, request, result, sink);
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
			if (!latch.await(AgentTimeouts.TOOL_PREPARE_SECONDS, TimeUnit.SECONDS)) {
				// a preparation that takes too long is a failure, and the
				// operation must NOT run
				return ToolPreparation.none();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return ref.get();
	}

	/**
	 * Executes the tool.
	 *
	 * <p>A short tool hops onto the server thread; a long running tool (a
	 * Gradle build) stays on this worker thread so the server is never
	 * blocked. The wait is the TOOL's own budget, never shorter than the work
	 * it waits for.</p>
	 */
	private static ToolResult runTool(MinecraftServer server, ToolContext ctx, ToolRequest request,
			ToolDescriptor descriptor, TaskToken token, AgentEventSink sink) {
		var tool = ToolRegistry.tool(descriptor.name()).orElse(null);
		if (tool == null) {
			return ToolResult.fail("unknown_tool", "未知工具");
		}
		if (token.isCancelled()) {
			return ToolResult.fail("cancelled", "任务已取消");
		}
		ToolEventSink events = e -> sink.onToolEvent(e);
		int budget = Math.max(descriptor.timeoutSeconds(),
				AgentTimeouts.DEFAULT_TOOL_SECONDS) + AgentTimeouts.TOOL_DISPATCH_GRACE_SECONDS;
		try {
			if (descriptor.longRunning()) {
				// the tool owns its own budget; the orchestrator only waits longer
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
			if (!latch.await(budget, TimeUnit.SECONDS)) {
				return ToolResult.fail("tool_timeout", "工具执行超时（" + budget + " 秒）");
			}
			ToolResult r = ref.get();
			return r == null ? ToolResult.fail("server_error", "工具没有返回结果") : r;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return ToolResult.fail("cancelled", "工具执行被中断");
		} catch (Exception e) {
			return ToolResult.fail("server_error", "工具执行异常: " + e.getClass().getSimpleName());
		}
	}

	/** Builds the tool context. */
	private static ToolContext context(MinecraftServer server, ServerPlayerEntity player,
			String conversationId, ToolDescriptor descriptor, TaskToken token) {
		var world = player.getServerWorld();
		return new ToolContext(player, server, world, world.getRegistryKey(), conversationId,
				descriptor.mutatesWorld()
						? ToolContext.ToolScope.mutating(conversationId, java.util.Set.of(), java.util.Set.of())
						: ToolContext.ToolScope.readOnly(conversationId),
				token);
	}

	private static void emitResult(String conversationId, String requestId, TaskToken token,
			ToolRequest request, ToolResult result, AgentEventSink sink) {
		ToolEventStatus status;
		if (result.cancelled() || (token.isCancelled() && result.ok())) {
			// a cancellation that arrived while the tool was finishing: the
			// operation may well have happened — say so, do not claim success
			status = ToolEventStatus.CANCELLED;
		} else {
			status = result.ok() ? ToolEventStatus.SUCCEEDED : ToolEventStatus.FAILED;
		}
		sink.onToolEvent(event(conversationId, requestId, token, request.toolCallId(), request.toolName(),
				status, result.displayText(), result.errorCode(), result.affectedTargets()));
	}

	private static ToolEvent event(String conversationId, String requestId, TaskToken token,
			String toolCallId, String toolName, ToolEventStatus status, String summary,
			String errorCode, List<String> targets) {
		return event(conversationId, requestId, token, toolCallId, toolName, status, summary,
				errorCode, targets, "");
	}

	/** An event that carries the confirmation the player has to answer. */
	private static ToolEvent event(String conversationId, String requestId, TaskToken token,
			String toolCallId, String toolName, ToolEventStatus status, String summary,
			String errorCode, List<String> targets, String confirmationId) {
		return new ToolEvent(conversationId, requestId, toolCallId, 0, System.currentTimeMillis(),
				toolName, status, summary, errorCode, targets, confirmationId,
				token == null ? "" : token.taskId());
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

	/**
	 * The player confirmed a pending operation.
	 *
	 * <p>v0.7.1: the confirmation id must match EXACTLY. The old code
	 * accepted a blank id and then approved whatever was pending.</p>
	 */
	public static boolean confirm(UUID player, String conversationId, String confirmationId) {
		Session session = existingSession(player, conversationId);
		if (session == null) {
			return false;
		}
		PendingConfirmation pc = session.pending.get();
		if (pc == null) {
			return false;
		}
		if (!pc.approve(confirmationId)) {
			return false;
		}
		TaskToken token = session.activeTask.get();
		if (token == null || token.isCancelled()) {
			// the task is gone: the approval must not lead to an execution
			pc.cancel();
			return false;
		}
		return true;
	}

	/** The player refused a pending operation. */
	public static boolean deny(UUID player, String conversationId, String confirmationId) {
		Session session = existingSession(player, conversationId);
		if (session == null) {
			return false;
		}
		PendingConfirmation pc = session.pending.get();
		if (pc == null) {
			return false;
		}
		return pc.deny(confirmationId);
	}

	/**
	 * Cancels the running task of a conversation.
	 *
	 * <p>The cancellation really stops the work: the task's cancel hooks run
	 * (which terminate a running Gradle build) and the in-flight model request
	 * is aborted. An action that already COMPLETED cannot be taken back — the
	 * call returns false in that case, so the client can say "已经完成，无法撤回"
	 * instead of lying.</p>
	 */
	public static boolean cancelTask(UUID player, String conversationId) {
		Session session = existingSession(player, conversationId);
		if (session == null) {
			return false;
		}
		TaskToken token = session.activeTask.get();
		if (token == null) {
			return false;
		}
		if (token.state() == TaskToken.State.FINISHED) {
			return false; // already done — cannot be taken back
		}
		boolean started = token.requestCancel();
		PendingConfirmation pc = session.pending.get();
		if (pc != null) {
			pc.cancel();
		}
		return started;
	}

	/** The summary of the pending confirmation, or null. */
	public static PendingConfirmation pendingFor(UUID player, String conversationId) {
		Session session = existingSession(player, conversationId);
		return session == null ? null : session.pending.get();
	}

	/**
	 * The state the CLIENT may show for a conversation.
	 *
	 * <p>v0.7.1: the client asks the SERVER for this after a reconnect, so
	 * the recovered state is the server's truth and not the client's guess.</p>
	 */
	public static TaskState stateOf(UUID player, String conversationId) {
		Session session = existingSession(player, conversationId);
		if (session == null) {
			return null;
		}
		TaskToken token = session.activeTask.get();
		if (token == null) {
			return null;
		}
		if (token.isCancelled()) {
			return TaskState.CANCELLING;
		}
		PendingConfirmation pc = session.pending.get();
		if (pc != null && pc.isPending()) {
			return TaskState.AWAITING_CONFIRMATION;
		}
		return TaskState.EXECUTING;
	}
}
