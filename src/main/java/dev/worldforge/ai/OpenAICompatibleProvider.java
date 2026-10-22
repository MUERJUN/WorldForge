package dev.worldforge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI-compatible Chat Completions backend (spec 三/六): plain HTTPS via
 * the JDK client - no extra runtime, no native tool-calling assumption.
 * The proposal contract is a strict JSON object in the assistant message,
 * enforced by the system prompt and re-validated server-side regardless.
 *
 * <p>Requests run on a small bounded pool (concurrency cap), never on the
 * render or server thread; each has a hard timeout, bounded retries with
 * backoff, and a response size cap. The API key exists only inside this
 * object and the outgoing header - it is never logged (toString masks it),
 * never sent to clients, never written into world data.</p>
 */
public final class OpenAICompatibleProvider implements ModelProvider, ToolCallingProvider {

	private final AgentConfig cfg;
	private final HttpClient http;
	private final ExecutorService pool;
	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	public OpenAICompatibleProvider(AgentConfig cfg) {
		this.cfg = cfg;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(Math.min(15, cfg.timeoutSeconds())))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		this.pool = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "wf-agent-http");
			t.setDaemon(true);
			return t;
		});
	}

	@Override
	public CompletableFuture<String> complete(String systemPrompt, String userMessage) {
		AtomicReference<Thread> worker = new AtomicReference<>();
		CompletableFuture<String> out = cancellable(worker);
		pool.submit(() -> {
			worker.set(Thread.currentThread());
			String body = requestBody(systemPrompt, userMessage);
			try {
				out.complete(extractContent(sendWithRetry(body, out)));
			} catch (Exception e) {
				out.completeExceptionally(e);
			} finally {
				worker.set(null);
			}
		});
		return out;
	}

	/**
	 * A future whose cancellation really stops the request.
	 *
	 * <p>v0.7.1: {@link CompletableFuture#cancel(boolean)} does NOT interrupt
	 * the thread that is running the request, so a cancelled agent task left
	 * its HTTP call (and the provider thread) running until the response
	 * arrived. This future interrupts the worker, and the JDK HTTP client
	 * aborts the request when its thread is interrupted.</p>
	 */
	private static <T> CompletableFuture<T> cancellable(AtomicReference<Thread> worker) {
		return new CompletableFuture<T>() {
			@Override
			public boolean cancel(boolean mayInterruptIfRunning) {
				Thread w = worker.get();
				if (w != null && mayInterruptIfRunning) {
					w.interrupt();
				}
				return super.cancel(mayInterruptIfRunning);
			}
		};
	}

	/**
	 * v0.7.0: the tool-calling turn. Native {@code tools[]} is offered to the
	 * service, and the same tool contract is ALSO written into the system
	 * prompt, so a service without native tool calling can still answer with
	 * the strict structured envelope. Both routes end up in the same
	 * {@link ChatTurn.Turn}, hence the same permission boundary.
	 */
	@Override
	public CompletableFuture<ChatTurn.Turn> completeWithTools(String systemPrompt,
			List<ChatTurn> messages, List<dev.worldforge.ops.ToolDescriptor> tools) {
		AtomicReference<Thread> worker = new AtomicReference<>();
		CompletableFuture<ChatTurn.Turn> out = cancellable(worker);
		pool.submit(() -> {
			worker.set(Thread.currentThread());
			try {
				String body = toolRequestBody(systemPrompt, messages, tools);
				out.complete(ToolCallingSupport.parseResponse(sendWithRetry(body, out)));
			} catch (Exception e) {
				out.completeExceptionally(e);
			} finally {
				worker.set(null);
			}
		});
		return out;
	}

	/** Builds the tool-calling request body with this config's model settings. */
	private String toolRequestBody(String systemPrompt, List<ChatTurn> messages,
			List<dev.worldforge.ops.ToolDescriptor> tools) {
		String body = ToolCallingSupport.requestBody(systemPrompt, messages, tools, true);
		return body.replace("MODEL_PLACEHOLDER", cfg.model())
				.replace("\"max_tokens\":1200", "\"max_tokens\":" + cfg.maxTokens());
	}

	/**
	 * Shared HTTP path: bounded retries with backoff, hard timeout and a
	 * response size cap. Never logs the body (it may echo the config).
	 */
	private String sendWithRetry(String body, CompletableFuture<?> out) throws Exception {
		{
			Exception last = null;
			for (int attempt = 0; attempt <= Math.max(0, cfg.retries()); attempt++) {
				if (cancelled.get() || out.isCancelled()) {
					throw new java.util.concurrent.CancellationException("cancelled");
				}
				try {
					HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint()))
							.timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
							.header("Content-Type", "application/json")
							.header("Authorization", "Bearer " + cfg.apiKey())
							.POST(HttpRequest.BodyPublishers.ofString(body))
							.build();
					HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
					int code = res.statusCode();
					if (code >= 200 && code < 300) {
						String raw = res.body();
						if (raw != null && raw.length() > 256 * 1024) {
							throw new IllegalStateException("响应过大");
						}
						return raw;
					}
					// error body may echo config; log only the status, never the payload
					last = new IllegalStateException("HTTP " + code);
					if (code >= 400 && code < 500 && code != 429) {
						break; // client-side errors won't fix themselves on retry
					}
				} catch (java.net.http.HttpTimeoutException e) {
					last = e;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw e;
				} catch (Exception e) {
					last = new Exception(e.getClass().getSimpleName());
				}
				try {
					Thread.sleep(500L * (attempt + 1));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			throw last == null ? new IllegalStateException("未知网络错误") : last;
		}
	}

	String endpoint() {
		String base = cfg.baseUrl().endsWith("/") ? cfg.baseUrl().substring(0, cfg.baseUrl().length() - 1) : cfg.baseUrl();
		return base + "/chat/completions";
	}

	private String requestBody(String systemPrompt, String userMessage) {
		JsonObject o = new JsonObject();
		o.addProperty("model", cfg.model());
		o.addProperty("max_tokens", cfg.maxTokens());
		o.addProperty("temperature", 0.2);
		JsonArray msgs = new JsonArray();
		msgs.add(msg("system", systemPrompt));
		msgs.add(msg("user", userMessage));
		o.add("messages", msgs);
		return o.toString();
	}

	private static JsonObject msg(String role, String content) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}

	/** pull choices[0].message.content; tolerant of a few response-shape variants */
	static String extractContent(String json) {
		var el = JsonParser.parseString(json);
		var root = el.getAsJsonObject();
		var choices = root.getAsJsonArray("choices");
		if (choices == null || choices.isEmpty()) {
			throw new IllegalStateException("响应缺少 choices");
		}
		var msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
		if (msg == null || !msg.has("content") || !msg.get("content").isJsonPrimitive()) {
			throw new IllegalStateException("响应缺少 message.content");
		}
		return msg.get("content").getAsString();
	}

	/**
	 * v0.4.0 P0: one real, minimal request used by the in-game "测试连接"
	 * button. Runs asynchronously and classifies the outcome (auth, model,
	 * rate limit, network, timeout) with a redacted message.
	 */
	public static java.util.concurrent.CompletableFuture<AgentConnectionTest.Result> probe(
			AgentConfig cfg, String key) {
		long t0 = System.currentTimeMillis();
		return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				HttpClient http = HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(Math.min(10, cfg.timeoutSeconds())))
						.build();
				JsonObject o = new JsonObject();
				o.addProperty("model", cfg.model());
				o.addProperty("max_tokens", 1);
				JsonArray msgs = new JsonArray();
				msgs.add(msg("user", "ping"));
				o.add("messages", msgs);
				String base = cfg.baseUrl().endsWith("/")
						? cfg.baseUrl().substring(0, cfg.baseUrl().length() - 1) : cfg.baseUrl();
				HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
						.timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer " + key)
						.POST(HttpRequest.BodyPublishers.ofString(o.toString()))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				return AgentConnectionTest.classify(res.statusCode(), res.body(), cfg.model(),
						System.currentTimeMillis() - t0, key);
			} catch (Exception e) {
				String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				return AgentConnectionTest.networkFailure(msg, cfg.model(), key);
			}
		});
	}

	@Override
	public String describe() {
		return cfg.model() + " @ " + cfg.baseUrl();
	}

	@Override
	public void cancelAll() {
		cancelled.set(true);
	}

	public void shutdown() {
		pool.shutdownNow();
	}

	@Override
	public String toString() {
		return "OpenAICompatibleProvider{" + cfg + "}"; // cfg.toString masks the key
	}
}
