package dev.worldforge.test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

/**
 * Shared temp-directory lifecycle for the GameTest suites.
 *
 * <p>Before this existed, every test helper called {@code Files.createTempDirectory(...)}
 * directly and never deleted the result. Because GameTest re-runs the whole suite
 * many times per session, that leaked thousands of directories into the system
 * temp folder and slowly filled the system drive.</p>
 *
 * <p>Now every temporary directory lives under ONE session root
 * ({@code wf-test-session-<pid>}) that is removed by a JVM shutdown hook. The
 * session root is also swept on startup, so directories orphaned by a killed
 * JVM are reclaimed on the next run instead of accumulating forever.</p>
 */
final class TestTempDirs {

	/** Prefix of the per-JVM session root. */
	private static final String SESSION_PREFIX = "wf-test-session-";

	private static volatile Path sessionRoot;

	private TestTempDirs() {
	}

	/**
	 * Creates a temporary directory under this JVM's session root.
	 *
	 * @param prefix human-readable prefix, e.g. {@code "wf-e2e"}
	 * @return the freshly created (empty) directory
	 */
	static synchronized Path create(String prefix) {
		Path root = sessionRoot();
		try {
			return Files.createTempDirectory(root, prefix + "-");
		} catch (IOException e) {
			throw new RuntimeException("could not create temp dir under " + root, e);
		}
	}

	private static Path sessionRoot() {
		Path root = sessionRoot;
		if (root != null) {
			return root;
		}
		synchronized (TestTempDirs.class) {
			if (sessionRoot == null) {
				long pid = ProcessHandle.current().pid();
				try {
					Path base = Path.of(System.getProperty("java.io.tmpdir"));
					sweepStaleSessions(base, pid);
					Path created = Files.createTempDirectory(base, SESSION_PREFIX + pid + "-");
					Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(created),
							"worldforge-test-temp-cleanup"));
					sessionRoot = created;
				} catch (IOException e) {
					throw new RuntimeException("could not create test session root", e);
				}
			}
			return sessionRoot;
		}
	}

	/**
	 * Removes session roots left behind by JVMs that are no longer running.
	 *
	 * <p>Safety: the owning PID is encoded in the directory name, and a root is
	 * only removed when that process is confirmed dead — so a concurrently
	 * running test session is never touched.</p>
	 */
	private static void sweepStaleSessions(Path base, long currentPid) {
		if (!Files.isDirectory(base)) {
			return;
		}
		try (Stream<Path> children = Files.list(base)) {
			children.filter(p -> p.getFileName().toString().startsWith(SESSION_PREFIX))
					.forEach(p -> {
						long owner = parsePid(p.getFileName().toString());
						if (owner < 0 || owner == currentPid) {
							return;
						}
						if (ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false)) {
							return; // owner still running — leave it alone
						}
						deleteRecursively(p);
					});
		} catch (IOException ignored) {
			// sweeping is best-effort; a failure here must never fail a test
		}
	}

	private static long parsePid(String dirName) {
		int start = SESSION_PREFIX.length();
		int end = dirName.indexOf('-', start);
		if (end < 0) {
			return -1;
		}
		try {
			return Long.parseLong(dirName.substring(start, end));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/** Recursively deletes a file or directory tree. Never throws. */
	static void deleteRecursively(Path path) {
		if (path == null || !Files.exists(path)) {
			return;
		}
		try {
			Files.walkFileTree(path, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
			// best-effort cleanup; a locked file must not fail the run
		}
	}

	/** Test-only helper: removes the session root immediately. */
	static synchronized void cleanupNow() {
		Path root = sessionRoot;
		sessionRoot = null;
		if (root != null) {
			deleteRecursively(root);
		}
	}

	/** Test-only helper: current session root, or {@code null} if not yet created. */
	static synchronized Path sessionRootForTest() {
		return sessionRoot;
	}
}
