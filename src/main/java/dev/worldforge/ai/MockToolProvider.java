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
	public int calls = 0;

	private MockToolProvider() {
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

	/** The messages the "model" received, turn by turn. */
	public List<List<ChatTurn>> receivedMessages() {
		return List.copyOf(received);
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
		offeredTools.clear();
		offeredTools.addAll(tools);
		Thread t = new Thread(() -> {
			if (cancelled) {
				f.cancel(true);
				return;
			}
			try {
				Object next = forcedCall != null ? new ChatTurn.Turn.Calls(List.of(forcedCall))
						: script.poll();
				if (next == null) {
					next = new ChatTurn.Turn.Final(finalTextAfterTools);
				} else if (next instanceof String s) {
					next = new ChatTurn.Turn.Final(s);
				}
				f.complete((ChatTurn.Turn) next);
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
