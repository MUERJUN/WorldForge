package dev.worldforge.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Agent configuration, loaded from {@code config/worldforge/agent.json} on the
 * SERVER side only (spec 六: keys never travel to clients, never enter world
 * saves, never appear in logs).
 *
 * <p>Supports any OpenAI-compatible Chat Completions endpoint
 * (OpenAI, DeepSeek, etc.). No native tool-calling is assumed: the agent
 * exchanges strict JSON proposals instead (spec 六).</p>
 */
public record AgentConfig(
		boolean enabled,
		String baseUrl,
		String model,
		String apiKey,
		int timeoutSeconds,
		int maxTokens,
		int retries,
		int rateLimitPerMinute,
		int historyLimit,
		int proposalTtlSeconds) {

	/** selectable service providers; CUSTOM accepts any OpenAI-compatible URL */
	public enum Provider {
		DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
		OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
		CUSTOM("自定义兼容接口", "", "");

		private final String display;
		private final String baseUrl;
		private final String defaultModel;

		Provider(String display, String baseUrl, String defaultModel) {
			this.display = display;
			this.baseUrl = baseUrl;
			this.defaultModel = defaultModel;
		}

		public String display() {
			return display;
		}

		public String baseUrl() {
			return baseUrl;
		}

		public String defaultModel() {
			return defaultModel;
		}
	}

	/** which preset (if any) this configuration matches */
	public Provider provider() {
		for (Provider p : Provider.values()) {
			if (p != Provider.CUSTOM && p.baseUrl().equalsIgnoreCase(trimSlash(baseUrl))) {
				return p;
			}
		}
		return Provider.CUSTOM;
	}

	private static String trimSlash(String u) {
		if (u == null) {
			return "";
		}
		return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
	}

	/** derived redacted summary safe for GUI display and logs */
	public String redactedSummary() {
		return "provider=" + provider().display() + ", model=" + model
				+ ", url=" + baseUrl + ", key=" + maskKey(apiKey) + ", enabled=" + enabled;
	}

	public static final AgentConfig DISABLED = new AgentConfig(false,
			"https://api.openai.com/v1", "gpt-4o-mini", "", 60, 1200, 1, 10, 12, 300);

	/** masked form safe for logs and GUI display - NEVER contains the key */
	@Override
	public String toString() {
		return "AgentConfig{enabled=" + enabled + ", baseUrl=" + baseUrl + ", model=" + model
				+ ", apiKey=" + maskKey(apiKey) + ", timeout=" + timeoutSeconds + "s}";
	}

	public static String maskKey(String key) {
		if (key == null || key.isBlank()) {
			return "<none>";
		}
		if (key.length() <= 8) {
			return "****";
		}
		return key.substring(0, 4) + "…" + key.substring(key.length() - 3);
	}

	public boolean usable() {
		return enabled && !keyIsPlaceholder(apiKey)
				&& baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
	}

	/**
	 * The generated template ships a visible placeholder instead of a key.
	 * Treating it as configured made the client attempt real calls with a
	 * garbage key (and report the agent as "enabled"), so placeholder values
	 * are explicitly rejected here.
	 */
	public static boolean keyIsPlaceholder(String key) {
		if (key == null || key.isBlank()) {
			return true;
		}
		String k = key.trim().toLowerCase(java.util.Locale.ROOT);
		return k.contains("paste") || k.contains("your-key") || k.contains("your_key")
				|| k.equals("changeme") || k.equals("none") || k.equals("null")
				|| k.startsWith("<");
	}

	public static AgentConfig load(Path configFile) {
		if (!Files.isRegularFile(configFile)) {
			return DISABLED;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8)).getAsJsonObject();
			return new AgentConfig(
					optBool(o, "enabled", true),
					optStr(o, "baseUrl", "https://api.openai.com/v1"),
					optStr(o, "model", "gpt-4o-mini"),
					optStr(o, "apiKey", ""),
					optInt(o, "timeoutSeconds", 60),
					optInt(o, "maxTokens", 1200),
					optInt(o, "retries", 1),
					optInt(o, "rateLimitPerMinute", 10),
					optInt(o, "historyLimit", 12),
					optInt(o, "proposalTtlSeconds", 300));
		} catch (Exception e) {
			// never log the file content (it may contain the key)
			return DISABLED;
		}
	}

	/** write a template config file; best-effort tightens permissions (0600) */
	public static void writeTemplate(Path file) {
		try {
			Files.createDirectories(file.getParent());
			if (Files.exists(file)) {
				return;
			}
			String tpl = """
					{
					  "enabled": true,
					  "baseUrl": "https://api.deepseek.com/v1",
					  "model": "deepseek-chat",
					  "apiKey": "PASTE-YOUR-KEY-HERE",
					  "timeoutSeconds": 60,
					  "maxTokens": 1200,
					  "retries": 1,
					  "rateLimitPerMinute": 10,
					  "historyLimit": 12,
					  "proposalTtlSeconds": 300
					}""";
			Files.writeString(file, tpl, StandardCharsets.UTF_8);
			try {
				Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
			} catch (UnsupportedOperationException | IOException ignored) {
				// Windows: ACLs inherited from profile dir; acceptable for MVP
			}
		} catch (IOException ignored) {
		}
	}

	private static String optStr(JsonObject o, String k, String def) {
		return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isString()
				? o.get(k).getAsString() : def;
	}

	private static int optInt(JsonObject o, String k, int def) {
		try {
			return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isNumber()
					? Math.max(1, o.get(k).getAsInt()) : def;
		} catch (Exception e) {
			return def;
		}
	}

	private static boolean optBool(JsonObject o, String k, boolean def) {
		return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean()
				? o.get(k).getAsBoolean() : def;
	}
}
