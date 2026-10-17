package dev.worldforge.ai;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Deterministic scriptable provider for automated tests (spec 十: Mock
 * ModelProvider; real API calls are NOT a test prerequisite).
 */
public final class MockModelProvider implements ModelProvider {

	private final BiFunction<String, String, String> script;
	private volatile boolean cancelled = false;
	public int calls = 0;

	public MockModelProvider(BiFunction<String, String, String> systemAndUserToReply) {
		this.script = systemAndUserToReply;
	}

	/** always replies with the same text */
	public static MockModelProvider canned(String reply) {
		return new MockModelProvider((s, u) -> reply);
	}

	/** simulates a slow/hanging API: future never completes until cancelled */
	public static MockModelProvider hanging() {
		return new MockModelProvider((s, u) -> {
			try {
				Thread.sleep(60_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "";
		});
	}

	@Override
	public CompletableFuture<String> complete(String systemPrompt, String userMessage) {
		calls++;
		CompletableFuture<String> f = new CompletableFuture<>();
		Thread t = new Thread(() -> {
			if (cancelled) {
				f.cancel(true);
				return;
			}
			try {
				f.complete(script.apply(systemPrompt, userMessage));
			} catch (Throwable e) {
				f.completeExceptionally(e);
			}
		}, "wf-mock-model");
		t.setDaemon(true);
		t.start();
		return f;
	}

	@Override
	public String describe() {
		return "mock-model";
	}

	@Override
	public void cancelAll() {
		cancelled = true;
	}
}
