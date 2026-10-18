package dev.worldforge.ai;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local, atomic persistence for the AI configuration (v0.4.0 P0).
 *
 * <p>Threat model (spec 六 / P0 安全要求):</p>
 * <ul>
 *   <li>the API key lives ONLY in this file on the machine running the server
 *       (integrated server in single player, or the dedicated server host);</li>
 *   <li>it is never written into a world save, never sent in a C2S/S2C packet,
 *       never logged, never included in an error message or a client
 *       snapshot - {@link #save} only writes it to disk, and every diagnostic
 *       path masks it;</li>
 *   <li>saving is atomic (temp file + move) so a crash cannot leave a
 *       half-written key file;</li>
 *   <li>{@link #redactedSummary()} is the ONLY shape intended for display or
 *       logging.</li>
 * </ul>
 */
public final class AgentConfigStore {

	private final Path file;

	public AgentConfigStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	public boolean exists() {
		return Files.isRegularFile(file);
	}

	/** load, or DISABLED when absent/unreadable (never throws, never logs content) */
	public AgentConfig load() {
		return AgentConfig.load(file);
	}

	/** load, creating the documented template on first run */
	public AgentConfig loadOrCreateTemplate() {
		if (!exists()) {
			writeTemplate();
		}
		return load();
	}

	/** atomic save; the key is written to disk only, never to a log line */
	public boolean save(AgentConfig cfg) {
		JsonObject o = toJson(cfg);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			restrictPermissions();
			return true;
		} catch (IOException e) {
			// log the failure WITHOUT the content (which contains the key)
			dev.worldforge.WorldForge.LOGGER.error("[WorldForge] failed to write agent config: {}",
					e.getClass().getSimpleName());
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
			}
			return false;
		}
	}

	/** remove the stored key while keeping the rest of the configuration */
	public boolean clearKey() {
		AgentConfig cfg = load();
		return save(new AgentConfig(cfg.enabled(), cfg.baseUrl(), cfg.model(), "",
				cfg.timeoutSeconds(), cfg.maxTokens(), cfg.retries(), cfg.rateLimitPerMinute(),
				cfg.historyLimit(), cfg.proposalTtlSeconds()));
	}

	public static JsonObject toJson(AgentConfig cfg) {
		JsonObject o = new JsonObject();
		o.addProperty("enabled", cfg.enabled());
		o.addProperty("baseUrl", cfg.baseUrl());
		o.addProperty("model", cfg.model());
		o.addProperty("apiKey", cfg.apiKey());
		o.addProperty("timeoutSeconds", cfg.timeoutSeconds());
		o.addProperty("maxTokens", cfg.maxTokens());
		o.addProperty("retries", cfg.retries());
		o.addProperty("rateLimitPerMinute", cfg.rateLimitPerMinute());
		o.addProperty("historyLimit", cfg.historyLimit());
		o.addProperty("proposalTtlSeconds", cfg.proposalTtlSeconds());
		return o;
	}

	private void writeTemplate() {
		AgentConfig.writeTemplate(file);
	}

	private void restrictPermissions() {
		try {
			Files.setPosixFilePermissions(file,
					java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
							java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Windows: ACLs are inherited from the profile directory
		}
	}
}