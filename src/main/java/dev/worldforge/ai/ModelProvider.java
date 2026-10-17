package dev.worldforge.ai;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable model backend (spec 三: 预留 AgentProvider 接口).
 *
 * <p>Implementations must be non-blocking: {@link #complete} returns a
 * future and NEVER runs on the caller's (render/server) thread. Tests use
 * {@link MockModelProvider} for deterministic runs; production default is
 * {@link OpenAICompatibleProvider}. A future DSH sidecar provider would
 * implement this same interface.</p>
 */
public interface ModelProvider {

	/**
	 * One chat turn.
	 *
	 * @param systemPrompt system instructions (capability whitelist, JSON contract)
	 * @param userMessage  the player's message plus embedded context
	 * @return the raw assistant text (expected to contain one proposal JSON)
	 */
	CompletableFuture<String> complete(String systemPrompt, String userMessage);

	/** human-readable provider identity for the GUI status line */
	String describe();

	/** cancel everything currently in flight (world change / player left) */
	default void cancelAll() {
	}
}
