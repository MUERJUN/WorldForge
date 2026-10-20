package dev.worldforge.exporter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * v0.5.3: Plan-driven export orchestration with lifecycle guarantees.
 *
 * <p>Phases: validate plan → generate project → build (JAR) → checksum.</p>
 *
 * <p>Lifecycle rules:</p>
 * <ul>
 *   <li>The executor reads ONLY the {@link ExportPlan} — definitions come from the
 *       plan's deep-copied snapshot via {@link DefinitionResolver#fromSnapshot}.
 *       Runtime managers are never queried from the background thread.</li>
 *   <li>{@link #cancel()} reliably destroys the Gradle process (and its children on
 *       Windows via taskkill on the process tree fallback).</li>
 *   <li>Build timeout counts from subprocess start; log reading is asynchronous so
 *       it can never starve the timeout.</li>
 *   <li>Terminal states OK / CANCELLED / TIMEOUT / FAILED are mutually exclusive;
 *       a cancelled task can never be overwritten by a later success.</li>
 *   <li>On any non-OK terminal state, produced JARs are deleted so no half-built
 *       artifact can look successful.</li>
 * </ul>
 */
public class ExportExecutor {

	/** Build timeout in minutes. */
	static final long BUILD_TIMEOUT_MINUTES = 10;

	/** Terminal result states — mutually exclusive and auditable. */
	public enum TerminalState { OK, CANCELLED, TIMEOUT, FAILED }

	private final ExportPlan plan;
	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
	private final AtomicReference<TerminalState> terminalState = new AtomicReference<>(null);
	private final List<String> auditLog = new ArrayList<>();

	public ExportExecutor(ExportPlan plan) {
		this.plan = plan;
	}

	public ExportPlan plan() {
		return plan;
	}

	public boolean isCancelled() {
		return cancelled.get();
	}

	public TerminalState state() {
		var s = terminalState.get();
		return s == null ? TerminalState.FAILED : s; // unset == never finished
	}

	/** Snapshot of the audit trail (thread-safe copy). */
	public synchronized List<String> auditTrail() {
		return List.copyOf(auditLog);
	}

	private synchronized void audit(String event) {
		auditLog.add(System.currentTimeMillis() + " " + event);
	}

	/**
	 * Cancel an in-progress export. Destroys the Gradle subprocess if running.
	 * First terminal state wins; later results cannot override a cancellation.
	 */
	public void cancel() {
		if (cancelled.compareAndSet(false, true)) {
			audit("cancel requested");
		}
		var proc = currentProcess.get();
		if (proc != null && proc.isAlive()) {
			proc.descendants().forEach(ProcessHandle::destroyForcibly);
			proc.destroyForcibly();
			audit("subprocess tree destroyed");
		}
	}

	/**
	 * Run the full export synchronously from the plan. Call from a background thread.
	 */
	public ModExporter.ExportResult doExport() {
		// 0) v0.5.5 core degraded guard — the executor refuses unconfirmed
		// capability loss itself; the GUI check is convenience, not the boundary.
		var degradedError = ModExporter.degradedGuardError(plan.report(), plan.degradedExportConfirmed());
		if (degradedError != null) {
			return finish(TerminalState.FAILED, false, null, null, degradedError, plan.report().lost());
		}

		// 1) validate plan metadata (no runtime lookups — pure plan data)
		var errors = ModExporter.validatePlanShape(plan);
		if (!errors.isEmpty()) {
			return finish(TerminalState.FAILED, false, null, null,
					"校验失败: " + String.join("; ", errors), plan.report().lost());
		}
		// v0.5.5: unsafe resource-path ids can never export
		var idErrors = ModExporter.validateIdSafety(
				plan.items().stream().map(dev.worldforge.definition.ItemDefinition::id).toList(),
				plan.blocks().stream().map(dev.worldforge.block.BlockDefinition::id).toList());
		if (!idErrors.isEmpty()) {
			return finish(TerminalState.FAILED, false, null, null,
					"校验失败: " + String.join("; ", idErrors), plan.report().lost());
		}

		var warnings = new ArrayList<>(plan.report().lost());
		if (plan.degradedExportConfirmed()) {
			warnings.add(0, "用户已确认降级导出（丢失 " + plan.report().lost().size() + " 项能力）");
		}

		// 2) generate project — snapshot resolver only
		Path projectDir;
		try {
			Path outputDir = ModExporter.planOutputDir(plan);
			projectDir = outputDir.resolve(plan.modId());
			Files.createDirectories(projectDir);
			var req = new ModExporter.ExportRequest(plan.modId(), plan.modName(), plan.version(),
					outputDir,
					plan.items().stream().map(ItemDefId::of).toList(),
					plan.blocks().stream().map(BlockDefId::of).toList());
			var gen = new TemplateProjectGenerator(req, projectDir,
					DefinitionResolver.fromSnapshot(plan));
			gen.generate();
			gen.copyGradleWrapper(Path.of(".").toAbsolutePath());
			audit("project generated at " + projectDir);
		} catch (Exception e) {
			return finish(TerminalState.FAILED, false, null, null,
					"项目生成失败: " + e.getMessage(), warnings);
		}

		if (cancelled.get()) {
			return finish(TerminalState.CANCELLED, false, projectDir, null, "导出已取消", warnings);
		}

		// 3) build with async log reader + timeout-from-start + cancellation.
		// Terminal-state rules (v0.5.4): user cancel → CANCELLED (never FAILED);
		// normal failure → FAILED; timeout → TIMEOUT; completion → OK. The first
		// terminal state wins (compareAndSet in finish()); a cancel racing a
		// successful build loses to the committed result once the pre-hash
		// checkpoint has passed — the artifact exists, so the export is OK.
		try {
			var jarPath = buildProject(projectDir);
			if (cancelled.get()) {
				deleteProducedJars(projectDir);
				return finish(TerminalState.CANCELLED, false, projectDir, null,
						"导出已取消（构建中断，产物已清理）", warnings);
			}
			if (jarPath == null) {
				deleteProducedJars(projectDir);
				return finish(TerminalState.FAILED, false, projectDir, null,
						"Gradle 构建未产生 JAR 文件。源码工程位于 " + projectDir, warnings);
			}
			// pre-hash checkpoint: past this point the build result is committed;
			// a cancel() during SHA-256 cannot retroactively discard the artifact
			var sha = sha256(jarPath);
			return finish(TerminalState.OK, true, projectDir, jarPath, null, warnings, sha);
		} catch (BuildCancelledException e) {
			deleteProducedJars(projectDir);
			return finish(TerminalState.CANCELLED, false, projectDir, null,
					"导出已取消（构建中断，产物已清理）", warnings);
		} catch (BuildTimeoutException e) {
			deleteProducedJars(projectDir);
			return finish(TerminalState.TIMEOUT, false, projectDir, null,
					"构建超时 (" + BUILD_TIMEOUT_MINUTES + " 分钟)，产物已清理。源码工程位于 " + projectDir, warnings);
		} catch (Exception e) {
			deleteProducedJars(projectDir);
			return finish(TerminalState.FAILED, false, projectDir, null,
					"构建失败: " + sanitizeLog(String.valueOf(e.getMessage()))
							+ "。源码工程位于 " + projectDir + " 可供检查。", warnings);
		}
	}

	// ------------------------------------------------------------------ build

	// ------------------------------------------------------- subprocess core

	/** Public bridge for tests in other packages. */
	public static int runForTest(Process proc, StringBuilder output, long timeout, TimeUnit unit)
			throws Exception {
		return runWithWatcher(proc, output, timeout, unit);
	}

	/** Public accessor for the package-private timeout exception type. */
	public static final class BuildTimeoutExceptionAccessor {
		public static Class<? extends RuntimeException> timeoutClass() {
			return BuildTimeoutException.class;
		}
	}

	/**
	 * v0.5.3 Phase 2: run a process with an asynchronous log reader and a
	 * bounded wait. Package-private static for direct testing with fake
	 * long-running subprocesses (no real Gradle needed).
	 *
	 * <p>Guarantees:</p>
	 * <ul>
	 *   <li>Timeout counts from the moment the process was started (caller starts
	 *       it and calls this immediately).</li>
	 *   <li>On timeout the process tree is destroyed and
	 *       {@link BuildTimeoutException} is thrown.</li>
	 *   <li>The log reader never blocks the timeout: it runs on its own daemon
	 *       thread and is drained/joined in the finally path.</li>
	 * </ul>
	 *
	 * @return process exit code (only reached when the process finished in time)
	 */
	static int runWithWatcher(Process proc, StringBuilder output, long timeout, TimeUnit unit)
			throws Exception {
		var readerDone = new CountDownLatch(1);
		Thread reader = Thread.ofPlatform().name("wf-export-log").daemon().unstarted(() -> {
			try (var r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					synchronized (output) {
						output.append(line).append('\n');
					}
				}
			} catch (Exception ignored) {
				// stream closed by process teardown — expected on cancel/timeout
			} finally {
				readerDone.countDown();
			}
		});
		reader.start();

		try {
			boolean finished = proc.waitFor(timeout, unit);
			if (!finished) {
				proc.descendants().forEach(ProcessHandle::destroyForcibly);
				proc.destroyForcibly();
				throw new BuildTimeoutException("subprocess exceeded " + timeout + " " + unit);
			}
		} finally {
			// give the reader a moment to drain, then wait for it to finish
			readerDone.await(5, TimeUnit.SECONDS);
		}
		return proc.exitValue();
	}

	private static final class BuildTimeoutException extends RuntimeException {
		BuildTimeoutException(String msg) { super(msg); }
	}

	/**
	 * The subprocess command that builds the project.
	 * v0.5.5: wrapper choice follows the OS — Windows uses gradlew.bat,
	 * Linux/macOS use gradlew — never file-existence order (both files are
	 * always present after copyGradleWrapper). Falls back to the other
	 * wrapper, then system gradle.
	 */
	protected List<String> buildCommand(Path projectDir) {
		var gradlew = projectDir.resolve("gradlew").toAbsolutePath();
		var gradleBat = projectDir.resolve("gradlew.bat").toAbsolutePath();
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		var choice = ModExporter.gradleCommandFor(windows,
				Files.exists(gradleBat), Files.exists(gradlew));
		// v0.5.6: a missing platform wrapper is a hard error — silently falling
		// back to a system-installed Gradle hides broken exports and can pick
		// an incompatible Gradle version.
		if ("system".equals(choice)) {
			throw new IllegalStateException(
					"导出工程缺少 " + (windows ? "gradlew.bat" : "gradlew")
					+ "——构建模板不完整，无法构建。请重新导出。");
		}
		// v0.5.5: 'build' minus runGameTest — the exported JAR must build on a
		// player machine without launching a second Minecraft (memory-safe
		// inside a running client; gameTests are run by the dev workflow).
		// --no-daemon: single short-lived JVM — a resident daemon doubles the
		// commit charge and fails on memory-tight player machines.
		return "bat".equals(choice)
				? List.of(gradleBat.toString(), "build", "-x", "runGameTest",
						"--no-daemon", "--console=plain")
				: List.of(gradlew.toString(), "build", "-x", "runGameTest",
						"--no-daemon", "--console=plain");
	}

	/**
	 * Build the project using the Gradle wrapper.
	 * Log output is read on a dedicated daemon thread so the bounded
	 * {@code waitFor(timeout)} can never be starved by a full pipe.
	 */
	private Path buildProject(Path projectDir) throws Exception {
		List<String> gradleCmd = buildCommand(projectDir);

		var pb = new ProcessBuilder(gradleCmd);
		pb.directory(projectDir.toFile());
		pb.redirectErrorStream(true);
		// don't inherit any sensitive environment
		pb.environment().remove("WORLDFORGE_API_KEY");
		pb.environment().remove("DEEPSEEK_API_KEY");
		pb.environment().remove("FABRIC_API_KEY");

		long startNanos = System.nanoTime();
		var proc = pb.start();
		currentProcess.set(proc);
		audit("subprocess started: " + gradleCmd.get(0));

		var output = new StringBuilder();
		try {
			runWithWatcher(proc, output, BUILD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		} finally {
			currentProcess.compareAndSet(proc, null);
		}

		if (cancelled.get()) {
			proc.descendants().forEach(ProcessHandle::destroyForcibly);
			proc.destroyForcibly();
			audit("cancelled during build wait");
			throw new BuildCancelledException();
		}

		int exit = proc.exitValue();
		long elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000L;
		audit("subprocess exited " + exit + " after " + elapsedSec + "s");
		if (exit != 0) {
			String tail;
			synchronized (output) {
				// v0.5.5: keep the LAST 4000 chars — the real error (remap
				// crash, OOM, gameTest failure) sits at the end of the log;
				// head-truncation hid it entirely in earlier investigations.
				int len = output.length();
				tail = output.substring(Math.max(0, len - 4000), len);
			}
			throw new RuntimeException("Gradle exit " + exit + ":\n" + sanitizeLog(tail));
		}

		// find the JAR
		var jarDir = projectDir.resolve("build/libs");
		if (!Files.exists(jarDir)) return null;
		try (var stream = Files.list(jarDir)) {
			return stream
					.filter(p -> p.toString().endsWith(".jar")
							&& !p.toString().endsWith("-sources.jar"))
					.findFirst()
					.orElse(null);
		}
	}

	private static final class BuildCancelledException extends RuntimeException {
		BuildCancelledException() { super("cancelled"); }
	}

	/** Delete any produced JARs so a failed/cancelled build leaves no misleading artifact. */
	private static void deleteProducedJars(Path projectDir) {
		var jarDir = projectDir.resolve("build/libs");
		if (!Files.exists(jarDir)) return;
		try (var stream = Files.list(jarDir)) {
			stream.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception ignored) {
				}
			});
		} catch (Exception ignored) {
		}
	}

	// ----------------------------------------------------------- result/audit

	private ModExporter.ExportResult finish(TerminalState state, boolean ok, Path projectDir,
			Path jarPath, String error, List<String> warnings) {
		return finish(state, ok, projectDir, jarPath, error, warnings, null);
	}

	private ModExporter.ExportResult finish(TerminalState state, boolean ok, Path projectDir,
			Path jarPath, String error, List<String> warnings, String sha) {
		// First terminal state wins — a cancelled task cannot be overwritten by success.
		if (!terminalState.compareAndSet(null, state)) {
			var first = terminalState.get();
			audit("attempted terminal state " + state + " ignored; already " + first);
			if (first == TerminalState.CANCELLED) {
				return new ModExporter.ExportResult(false, projectDir, null, null,
						"导出已取消", warnings);
			}
		} else {
			audit("terminal state: " + state + (ok ? " jar=" + jarPath : " error=" + error));
		}
		return new ModExporter.ExportResult(ok, projectDir, jarPath, sha, error, warnings);
	}

	/** Sanitize build log: remove API keys, tokens, and sensitive values. */
	static String sanitizeLog(String log) {
		if (log == null) return "";
		return log
				.replaceAll("(?i)api[_-]?key[=: ]+\\S+", "api_key=***REDACTED***")
				.replaceAll("(?i)token[=: ]+\\S+", "token=***REDACTED***")
				.replaceAll("(?i)password[=: ]+\\S+", "password=***REDACTED***");
	}

	private static String sha256(Path file) throws Exception {
		var md = MessageDigest.getInstance("SHA-256");
		try (var is = Files.newInputStream(file)) {
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
		}
		return HexFormat.of().formatHex(md.digest());
	}

	// Tiny ID carriers so the plan can build a request without runtime lookups.
	// (ExportRequest stores ID strings; these map definition -> id at plan time.)
	private static final class ItemDefId {
		static String of(dev.worldforge.definition.ItemDefinition def) { return def.id(); }
	}

	private static final class BlockDefId {
		static String of(dev.worldforge.block.BlockDefinition def) { return def.id(); }
	}
}
