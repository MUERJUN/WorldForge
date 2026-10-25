package dev.worldforge.ai;

import dev.worldforge.ops.ToolDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A deterministic, scriptable provider for the tool-calling path.
 *
 * <p>Used by the automated flows (A/B/C): the test scripts exactly which tool
 * calls the "model" makes and in which order, so the whole agent loop —
 * preparation, confirmation, permission re-check, execution and the event
 * stream — is exercised for real while the model itself is replaced by a
 * script.</p>
 *
 * <p>This is a TEST double. It proves the agent machinery, never the
 * behaviour of a real DeepSeek/OpenAI service.</p>
 */
public final class MockToolProvider implements ModelProvider, ToolCallingProvider {

	private final Queue<Object> script = new ConcurrentLinkedQueue<>();
	private final List<List<ChatTurn>> received = java.util.Collections.synchronizedList(new ArrayList<>());
	private final List<ToolDescriptor> offeredTools = java.util.Collections.synchronizedList(new ArrayList<>());
	private volatile boolean cancelled;
	/** when set, every turn returns this call instead of the script */
	private volatile ChatTurn.ModelToolCall forcedCall;
	/** when true, every turn after the first is a final text */
	private volatile String finalTextAfterTools = "";
	/** how long a turn takes before it answers (v0.7.1: for the race tests) */
	private volatile long delayMillis = 0;
	/**
	 * v0.7.1: when true the provider answers EVEN AFTER the future was
	 * cancelled. It emulates a service that does not honour a cancellation,
	 * and it is how the tests prove that a late answer of an old task cannot
	 * touch the state of the task that replaced it.
	 */
	private volatile boolean ignoreCancellation = false;
	/** when set, the answer is derived from the player's message */
	private volatile java.util.function.Function<String, ChatTurn.Turn> byUserMessage;
	public int calls = 0;

	public MockToolProvider() {
	}

	/**
	 * A provider that always answers with the same text.
	 *
	 * <p>Kept for the same shape the legacy mock had, so the older tests keep
	 * working.</p>
	 */
	public MockToolProvider(java.util.function.BiFunction<String, String, String> reply) {
		this.finalTextAfterTools = reply.apply("", "");
	}

	/** A provider that walks the given script and then repeats the last entry. */
	public static MockToolProvider scripted(List<Object> turns) {
		MockToolProvider p = new MockToolProvider();
		p.script.addAll(turns);
		return p;
	}

	/** Convenience: one tool call, then a final answer. */
	public static MockToolProvider callsThenText(List<ChatTurn.ModelToolCall> calls, String finalText) {
		MockToolProvider p = new MockToolProvider();
		p.script.add(new ChatTurn.Turn.Calls(calls));
		p.script.add(new ChatTurn.Turn.Final(finalText));
		return p;
	}

	/** Convenience: a single tool call, then a final answer. */
	public static MockToolProvider callThenText(String toolName, String argumentsJson, String finalText) {
		return callsThenText(List.of(
				new ChatTurn.ModelToolCall("call_1", toolName, argumentsJson)), finalText);
	}

	/** Always answers with the same call (for loop tests). */
	public MockToolProvider alwaysCall(String toolName, String argumentsJson) {
		this.forcedCall = new ChatTurn.ModelToolCall("call_" + (calls + 1), toolName, argumentsJson);
		return this;
	}

	/** A provider that always answers with the same text. */
	public static MockToolProvider answering(String reply) {
		return new MockToolProvider((s, u) -> reply);
	}

	/**
	 * A provider that decides its answer from the PLAYER'S MESSAGE.
	 *
	 * <p>v0.7.1: the provider is global static state and the GameTest runner
	 * runs the batches concurrently, so a per-test scripted provider would be
	 * overwritten by another test at any moment. A message-driven provider can
	 * be SHARED by every test without that race.</p>
	 */
	public static MockToolProvider responding(
			java.util.function.Function<String, ChatTurn.Turn> byUserMessage) {
		MockToolProvider p = new MockToolProvider();
		p.byUserMessage = byUserMessage;
		return p;
	}

	/** Makes every turn take {@code millis} before it answers. */
	public MockToolProvider withDelay(long millis) {
		this.delayMillis = millis;
		return this;
	}

	/** Lets the provider answer even after its future was cancelled. */
	public MockToolProvider ignoringCancellation() {
		this.ignoreCancellation = true;
		return this;
	}

	/** The newest USER turn of a message list, or an empty string. */
	private static String newestUserMessage(List<ChatTurn> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			var m = messages.get(i);
			if (m.role() == ChatTurn.Role.USER) {
				return m.content();
			}
		}
		return "";
	}

	/**
	 * v0.7.4: the suite-wide reserved message routes for the graft/ungraft
	 * vocabulary. Returns the canonical tool call for a reserved message, or
	 * {@code null} when the message is not reserved and the per-test lambda
	 * should decide.
	 *
	 * <p>The three SPECIFIC undo phrases are reserved because different
	 * ungraft tests need different arguments and the phrases must produce the
	 * same call no matter which test's provider is attached. The plain
	 * 「撤销」 family is deliberately NOT reserved: its meaning differs by
	 * test state (one behaviour vs many), and every provider's own lambda
	 * already answers it identically.</p>
	 */
	private static ChatTurn.Turn reservedRoute(String userMessage) {
		// v0.7.4: the table lives in SuiteReservedRoutes so the AgentBridge
		// wrapper can apply the SAME routes to every attached provider type.
		return SuiteReservedRoutes.answer(userMessage);
	}

	/** The messages the "model" received, turn by turn. */
	public List<List<ChatTurn>> receivedMessages() {
		return List.copyOf(received);
	}

	/** A readable dump of what the "model" was asked, turn by turn. */
	public String receivedDump() {
		StringBuilder sb = new StringBuilder();
		List<List<ChatTurn>> turns = receivedMessages();
		for (int i = 0; i < turns.size(); i++) {
			sb.append("turn").append(i).append(": ");
			for (ChatTurn m : turns.get(i)) {
				sb.append("[").append(m.role()).append("=");
				String c = m.content();
				sb.append(c.length() > 24 ? c.substring(0, 24) : c).append("] ");
			}
			sb.append(" | ");
		}
		return sb.toString();
	}

	/** The tool descriptors the "model" was offered. */
	public List<ToolDescriptor> offeredTools() {
		return List.copyOf(offeredTools);
	}

	/** The last tool result the "model" was shown. */
	public String lastToolResult() {
		synchronized (received) {
			for (int i = received.size() - 1; i >= 0; i--) {
				var turn = received.get(i);
				for (var m : turn) {
					if (m.role() == ChatTurn.Role.TOOL) {
						return m.content();
					}
				}
			}
		}
		return "";
	}

	@Override
	public CompletableFuture<ChatTurn.Turn> completeWithTools(String systemPrompt,
			List<ChatTurn> messages, List<ToolDescriptor> tools) {
		CompletableFuture<ChatTurn.Turn> f = new CompletableFuture<>();
		calls++;
		received.add(List.copyOf(messages));
		// The newest USER turn is what a message-driven provider reacts to.
		//
		// It is computed LOCALLY and handed to the answer function: as a field it
		// was shared between concurrent calls, so two tasks talking to the
		// provider at the same time could read each other's message.
		final String userMessage = newestUserMessage(messages);
		offeredTools.clear();
		offeredTools.addAll(tools);
		Thread t = new Thread(() -> {
			if (cancelled) {
				f.cancel(true);
				return;
			}
			try {
				if (delayMillis > 0) {
					Thread.sleep(delayMillis);
				}
				// v0.7.4: suite-wide RESERVED routes answer BEFORE any per-test
				// lambda. The GameTest runner runs batches concurrently and this
				// provider is a global singleton, so whichever test's provider
				// happens to be attached must still answer a RESERVED message
				// identically — otherwise a concurrent batch's provider hands
				// the reserved message the wrong tool call (observed on Linux
				// CI as a refused indexed ungraft).
				ChatTurn.Turn reserved = reservedRoute(userMessage);
				Object next = forcedCall != null ? new ChatTurn.Turn.Calls(List.of(forcedCall))
						: (reserved != null ? reserved
								: (byUserMessage != null
										? byUserMessage.apply(userMessage)
										: script.poll()));
				if (next == null) {
					next = new ChatTurn.Turn.Final(finalTextAfterTools);
				} else if (next instanceof String s) {
					next = new ChatTurn.Turn.Final(s);
				}
				if (ignoreCancellation) {
					// the answer arrives even though the caller gave up on it
					f.complete((ChatTurn.Turn) next);
				} else {
					f.complete((ChatTurn.Turn) next);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				f.completeExceptionally(e);
			} catch (Throwable e) {
				f.completeExceptionally(e);
			}
		}, "wf-mock-tool-model");
		t.setDaemon(true);
		t.start();
		return f;
	}

	@Override
	public CompletableFuture<String> complete(String systemPrompt, String userMessage) {
		CompletableFuture<String> f = new CompletableFuture<>();
		Thread t = new Thread(() -> {
			if (cancelled) {
				f.cancel(true);
				return;
			}
			Object next = script.poll();
			if (next instanceof ChatTurn.Turn.Final fin) {
				f.complete(fin.text());
			} else {
				f.complete("");
			}
		}, "wf-mock-tool-model");
		t.setDaemon(true);
		t.start();
		return f;
	}

	@Override
	public String describe() {
		return "mock-tool-model";
	}

	@Override
	public void cancelAll() {
		cancelled = true;
	}
}
