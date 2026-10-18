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

	/**
	 * Save the configuration with a verifiable atomic replace.
	 *
	 * <p>Order of operations (each step matters for secrecy):</p>
	 * <ol>
	 *   <li>the temp file is created and RESTRICTED <em>before</em> any byte of
	 *       the key is written, so there is no window in which a
	 *       world-readable file contains the secret;</li>
	 *   <li>the content is written and flushed;</li>
	 *   <li>the temp file replaces the target with
	 *       {@link StandardCopyOption#ATOMIC_MOVE} so a reader never observes a
	 *       partially written configuration;</li>
	 *   <li>if the filesystem does not support atomic moves, the code falls
	 *       back to a plain {@code REPLACE_EXISTING} move and <em>reports that
	 *       degradation explicitly</em> ({@link #lastSaveWasAtomic()} and a log
	 *       line) instead of pretending atomicity.</li>
	 * </ol>
	 */
	public boolean save(AgentConfig cfg) {
		JsonObject o = toJson(cfg);
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			deleteQuietly(tmp);
			createRestricted(tmp);
			Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
			return replace(tmp, file);
		} catch (IOException e) {
			dev.worldforge.WorldForge.LOGGER.error("[WorldForge] failed to write agent config: {}",
					e.getClass().getSimpleName());
			deleteQuietly(tmp);
			return false;
		}
	}

	/** true when the last successful save used an atomic replace */
	public boolean lastSaveWasAtomic() {
		return lastSaveWasAtomic;
	}

	private boolean lastSaveWasAtomic = false;

	/** atomically replace the target, degrading loudly when unsupported */
	private boolean replace(Path tmp, Path target) throws IOException {
		try {
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
			lastSaveWasAtomic = true;
			return true;
		} catch (java.nio.file.AtomicMoveNotSupportedException | UnsupportedOperationException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			lastSaveWasAtomic = false;
			dev.worldforge.WorldForge.LOGGER.warn(
					"[WorldForge] filesystem does not support ATOMIC_MOVE; the config was replaced "
							+ "non-atomically (a concurrent reader could observe a partial file)");
			return true;
		}
	}

	/** create the file with owner-only permissions BEFORE writing any secret */
	private void createRestricted(Path p) throws IOException {
		try {
			Files.createFile(p, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
					java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
							java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
		} catch (UnsupportedOperationException e) {
			// Windows: no POSIX permissions; ACLs are inherited from the profile.
			Files.createFile(p);
		}
	}

	private static void deleteQuietly(Path p) {
		try {
			Files.deleteIfExists(p);
		} catch (IOException ignored) {
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