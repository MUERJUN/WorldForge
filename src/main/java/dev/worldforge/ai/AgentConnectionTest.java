package dev.worldforge.ai;

import java.util.concurrent.CompletableFuture;

/**
 * Real (not mocked) connectivity probe for the AI configuration (v0.4.0 P0).
 *
 * <p>It performs an actual minimal Chat Completions request and CLASSIFIES the
 * outcome so the player is told what is actually wrong - network unreachable,
 * bad key, wrong model, rate limited or server error - instead of a generic
 * failure. Every message is built from the HTTP status and a short, redacted
 * reason; the API key never appears in a result, and response bodies are
 * truncated and scrubbed before being surfaced.</p>
 *
 * <p>Runs asynchronously: callers get a future and must never block the render
 * or server thread on it.</p>
 */
public final class AgentConnectionTest {

	public enum Verdict {
		OK,
		DISABLED,
		NO_KEY,
		BAD_URL,
		NETWORK_ERROR,
		AUTH_ERROR,
		MODEL_NOT_FOUND,
		RATE_LIMITED,
		SERVER_ERROR,
		TIMEOUT
	}

	public record Result(Verdict verdict, int httpStatus, String message, String model, long elapsedMs) {
		public boolean ok() {
			return verdict == Verdict.OK;
		}
	}

	private static final int MAX_DETAIL = 160;

	/**
	 * Probe the configuration. Never returns a message containing the key.
	 *
	 * @param keyOverride a key typed in the GUI that has not been saved yet;
	 *                    pass {@code null} to use the stored one
	 */
	public static CompletableFuture<Result> test(AgentConfig cfg, String keyOverride) {
		String key = keyOverride != null && !keyOverride.isBlank() ? keyOverride.trim() : cfg.apiKey();
		if (!cfg.enabled()) {
			return CompletableFuture.completedFuture(
					new Result(Verdict.DISABLED, 0, "AI 已禁用：请先勾选启用", cfg.model(), 0));
		}
		if (key == null || key.isBlank()) {
			return CompletableFuture.completedFuture(
					new Result(Verdict.NO_KEY, 0, "未配置 API Key", cfg.model(), 0));
		}
		if (!validBaseUrl(cfg.baseUrl())) {
			return CompletableFuture.completedFuture(
					new Result(Verdict.BAD_URL, 0, "Base URL 非法：需要以 http:// 或 https:// 开头的完整地址",
							cfg.model(), 0));
		}
		if (cfg.model() == null || cfg.model().isBlank()) {
			return CompletableFuture.completedFuture(
					new Result(Verdict.MODEL_NOT_FOUND, 0, "模型名称为空", "", 0));
		}
		AgentConfig probe = new AgentConfig(cfg.enabled(), cfg.baseUrl(), cfg.model(), key,
				Math.min(cfg.timeoutSeconds(), 30), 16, 0, cfg.rateLimitPerMinute(),
				cfg.historyLimit(), cfg.proposalTtlSeconds());
		return OpenAICompatibleProvider.probe(probe, key);
	}

	/** human-readable reason when a Base URL is rejected, or "" when it is fine */
	public static String validateBaseUrl(String url) {
		if (url == null || url.isBlank()) {
			return "Base URL 为空";
		}
		String u = url.trim();
		if (u.contains(" ") || u.contains("\n") || u.contains("\t")) {
			return "Base URL 不能包含空白字符";
		}
		java.net.URI uri;
		try {
			uri = java.net.URI.create(u);
		} catch (Exception e) {
			return "Base URL 不是合法 URI";
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			return "Base URL 必须以 http://（仅本机）或 https:// 开头";
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			return "Base URL 缺少主机名";
		}
		// review fix: credentials, query strings and fragments must never be part
		// of an endpoint - they would leak into logs and break path joining
		if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
			return "Base URL 不能包含用户信息（user:pass@）";
		}
		if (uri.getQuery() != null) {
			return "Base URL 不能包含查询参数（?）";
		}
		if (uri.getFragment() != null) {
			return "Base URL 不能包含片段（#）";
		}
		if (scheme.equals("http") && !AgentConfig.isLocalHost(uri.getHost())) {
			return "非本机端点必须使用 HTTPS：明文 HTTP 会把 API Key 暴露在网络上";
		}
		return "";
	}

	public static boolean validBaseUrl(String url) {
		return validateBaseUrl(url).isEmpty();
	}

	/** GUI convenience: a bare host becomes HTTPS (never plaintext by default) */
	public static String defaultToHttps(String url) {
		if (url == null) {
			return "";
		}
		String u = url.trim();
		if (u.isEmpty() || u.contains("://")) {
			return u;
		}
		return "https://" + u;
	}

	/**
	 * v0.4.0 review fix: HTTP 200 alone does not prove a usable endpoint - a
	 * captive portal or an unrelated JSON API answers 200 too. The body must
	 * carry the Chat Completions shape (a non-empty {@code choices} array whose
	 * entry has a {@code message}).
	 */
	public static String validateChatCompletionsShape(String body) {
		if (body == null || body.isBlank()) {
			return "响应为空";
		}
		com.google.gson.JsonElement el;
		try {
			el = com.google.gson.JsonParser.parseString(body);
		} catch (Exception e) {
			return "响应不是 JSON（可能被网关或代理拦截）";
		}
		if (!el.isJsonObject()) {
			return "响应不是 JSON 对象";
		}
		var root = el.getAsJsonObject();
		if (!root.has("choices") || !root.get("choices").isJsonArray()
				|| root.getAsJsonArray("choices").isEmpty()) {
			return "响应缺少 choices 数组（不是 Chat Completions 接口）";
		}
		var first = root.getAsJsonArray("choices").get(0);
		if (!first.isJsonObject() || !first.getAsJsonObject().has("message")) {
			return "响应缺少 choices[0].message";
		}
		return "";
	}

	/** map an HTTP status to a verdict + a human explanation (no secrets) */
	public static Result classify(int status, String body, String model, long elapsedMs, String key) {
		String detail = scrub(body, key);
		return switch (status) {
			case 200, 201 -> {
				String shape = validateChatCompletionsShape(body);
				yield shape.isEmpty()
						? new Result(Verdict.OK, status,
								"连接成功，模型 " + model + " 可用（" + elapsedMs + "ms）", model, elapsedMs)
						: new Result(Verdict.SERVER_ERROR, status,
								"HTTP " + status + " 但响应结构异常：" + shape, model, elapsedMs);
			}
			case 401 -> new Result(Verdict.AUTH_ERROR, status,
					"鉴权失败（HTTP 401）：API Key 无效或已过期", model, elapsedMs);
			case 403 -> new Result(Verdict.AUTH_ERROR, status,
					"鉴权被拒绝（HTTP 403）：Key 无权访问该模型或地区受限", model, elapsedMs);
			case 404 -> new Result(Verdict.MODEL_NOT_FOUND, status,
					"资源不存在（HTTP 404）：Base URL 或模型名称错误" + suffix(detail), model, elapsedMs);
			case 422, 400 -> new Result(Verdict.MODEL_NOT_FOUND, status,
					"请求被拒绝（HTTP " + status + "）：模型名称可能不存在" + suffix(detail), model, elapsedMs);
			case 429 -> new Result(Verdict.RATE_LIMITED, status,
					"被限流（HTTP 429）：请稍后重试或降低频率", model, elapsedMs);
			default -> status >= 500
					? new Result(Verdict.SERVER_ERROR, status,
							"服务端错误（HTTP " + status + "）：稍后重试" + suffix(detail), model, elapsedMs)
					: new Result(Verdict.NETWORK_ERROR, status,
							"意外的 HTTP 状态 " + status + suffix(detail), model, elapsedMs);
		};
	}

	public static Result networkFailure(String reason, String model, String key) {
		String r = scrub(reason, key);
		Verdict v = r != null && r.toLowerCase(java.util.Locale.ROOT).contains("timed out")
				? Verdict.TIMEOUT : Verdict.NETWORK_ERROR;
		return new Result(v, 0, (v == Verdict.TIMEOUT ? "连接超时：" : "网络不可达：") + r, model, 0);
	}

	private static String suffix(String detail) {
		return detail == null || detail.isBlank() ? "" : "（" + detail + "）";
	}

	/**
	 * Remove anything resembling the key and cap the length. A provider that
	 * echoes the request (some gateways do) must not leak the secret into the
	 * GUI, a log line or a bug report.
	 */
	public static String scrub(String text, String key) {
		if (text == null) {
			return "";
		}
		String out = text;
		if (key != null && !key.isBlank()) {
			out = out.replace(key, "***");
			// also scrub key-shaped substrings (sk-..., Bearer ...)
			out = out.replaceAll("sk-[A-Za-z0-9_\\-]{6,}", "***");
			out = out.replaceAll("(?i)bearer\\s+[A-Za-z0-9._\\-]{6,}", "Bearer ***");
		} else {
			out = out.replaceAll("sk-[A-Za-z0-9_\\-]{6,}", "***");
		}
		out = out.replaceAll("\\s+", " ").trim();
		return out.length() > MAX_DETAIL ? out.substring(0, MAX_DETAIL) + "…" : out;
	}

	private AgentConnectionTest() {
	}
}