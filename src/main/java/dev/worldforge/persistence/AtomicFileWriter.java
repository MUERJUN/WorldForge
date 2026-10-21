package dev.worldforge.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * v0.6.0 crash-safe transactional writer for the definition files.
 *
 * <p>Guarantee (bounded honestly): after a save that RETURNS normally, the
 * file is complete. After a save that throws or a process crash, the most
 * recent COMPLETE valid version is recoverable and the sole live file is
 * never silently replaced by a half-written one. This is best-effort crash
 * consistency: it relies on fsync where the platform provides it and on the
 * journal/tmp/bak artifacts surviving in the same directory; it does NOT
 * promise "no loss on any arbitrary power failure" (that needs O_DIRECT +
 * directory fsync semantics not available through java.nio on every OS).</p>
 *
 * <p>Commit protocol (all artifacts share the target's directory so rename is
 * same-filesystem, atomic where the FS supports it):</p>
 * <ol>
 *   <li>write full new bytes to {@code <file>.tmp}, fsync;</li>
 *   <li>write {@code <file>.journal} = {@code "pending <sha256>"} , fsync,
 *       fsync the directory (so the pending marker is durable before the move);</li>
 *   <li>try ATOMIC_MOVE tmp-&gt;file; on success delete journal — indivisible;</li>
 *   <li>if the move is refused: back up the current file to {@code <file>.bak}
 *       (fsync), copy tmp-&gt;file (fsync), re-read and verify the sha-256,
 *       then clear tmp/journal/bak. A crash at any point leaves journal +
 *       (bak or tmp) for {@link #recover} to finish or roll back.</li>
 * </ol>
 *
 * <p>Journal states are strictly distinguished by {@link #recover}: MISSING
 * (no pending txn), VALID (a well-formed sha to verify against), and INVALID
 * (present but unreadable/malformed). An INVALID journal is never a blank
 * check: recovery only performs a conservative rollback to a prior backup,
 * and otherwise PRESERVES all evidence and fails closed so the caller keeps
 * live state instead of overwriting with a possibly-empty parse.</p>
 */
public final class AtomicFileWriter {

	private static final String TMP_SUFFIX = ".tmp";
	private static final String BAK_SUFFIX = ".bak";
	private static final String JOURNAL_SUFFIX = ".journal";
	private static final String PENDING = "pending ";

	/** Thrown when a save could not be completed; the previous file is intact. */
	public static final class SaveFailureException extends IOException {
		public SaveFailureException(String msg) { super(msg); }
		public SaveFailureException(String msg, Throwable cause) { super(msg, cause); }
	}

	/** Thrown when recovery cannot confirm a valid version; evidence is preserved. */
	public static final class RecoveryNeededException extends IOException {
		public RecoveryNeededException(String msg) { super(msg); }
	}

	public static Path tmpOf(Path file) { return file.resolveSibling(file.getFileName() + TMP_SUFFIX); }
	public static Path bakOf(Path file) { return file.resolveSibling(file.getFileName() + BAK_SUFFIX); }
	public static Path journalOf(Path file) { return file.resolveSibling(file.getFileName() + JOURNAL_SUFFIX); }

	/** Public sha-256 hex helper (also used by tests to build valid journals). */
	public static String sha256Hex(String s) {
		try {
			var md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return null; // SHA-256 is mandated in every JDK; unreachable
		}
	}

	/**
	 * Commit {@code content} to {@code file}. Throws {@link SaveFailureException}
	 * on any failure. The failure path never leaves a torn target that a later
	 * load would mistake for a valid save, and never destroys the prior version.
	 */
	public static void commit(Path file, String content) throws IOException {
		var dir = file.getParent();
		Files.createDirectories(dir);
		var tmp = tmpOf(file);
		var journal = journalOf(file);
		var bak = bakOf(file);
		var sha = sha256Hex(content);

		// 1) full new bytes to tmp, durable before we claim anything
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		fsync(tmp);

		// 2) pending marker with the expected digest; fsync file + directory so
		//    the intent survives a crash before the move
		Files.writeString(journal, PENDING + sha + "\n", StandardCharsets.UTF_8);
		fsync(journal);
		fsyncDir(dir);

		// 3) indivisible path
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			deleteQuietly(journal);
			fsyncDir(dir);
			return;
		} catch (IOException moveFailure) {
			// includes AtomicMoveNotSupportedException — fall through to the
			// recoverable non-atomic path
		}

		// 4) non-atomic degrade: back up, copy, verify, then clear markers
		boolean targetTouched = false;
		try {
			// a non-regular target (e.g. a directory planted to block writes)
			// must NOT be clobbered — that would swallow a real disk failure
			if (Files.exists(file) && !Files.isRegularFile(file)) {
				throw new SaveFailureException("target is not a regular file: " + file);
			}
			if (Files.isRegularFile(file)) {
				Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
				fsync(bak);
			}
			targetTouched = true;
			Files.copy(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			fsync(file);
			String written = Files.readString(file, StandardCharsets.UTF_8);
			if (!sha256Hex(written).equals(sha)) {
				throw new SaveFailureException("post-copy verification failed for " + file);
			}
			deleteQuietly(tmp);
			deleteQuietly(journal);
			deleteQuietly(bak);
			fsyncDir(dir);
		} catch (IOException | RuntimeException e) {
			if (targetTouched) {
				if (Files.isRegularFile(bak)) {
					// a prior version existed: roll the target back to it
					try {
						Files.copy(bak, file, StandardCopyOption.REPLACE_EXISTING);
						fsync(file);
						deleteQuietly(journal);
						deleteQuietly(tmp);
						deleteQuietly(bak);
						fsyncDir(dir);
					} catch (IOException rollbackFail) {
						// rollback FAILED: keep journal+bak+tmp so recover() can
						// finish; do NOT delete evidence
						throw new SaveFailureException(
								"commit failed AND rollback failed for " + file
								+ "; recovery artifacts preserved on disk", e);
					}
				} else {
					// FIRST save: no prior version existed, so any target present
					// is this transaction's torn output — safe to remove. Never
					// delete a user's original here (there was none).
					try {
						if (Files.isRegularFile(file)) {
							Files.delete(file);
						}
						deleteQuietly(journal);
						deleteQuietly(tmp);
						fsyncDir(dir);
					} catch (IOException cleanupFail) {
						// could not clean up: preserve the pending txn so the next
						// load fails closed instead of trusting a torn file
						throw new SaveFailureException(
								"commit failed and cleanup failed for " + file
								+ "; pending transaction preserved for recovery", e);
					}
				}
			} else {
				// never touched the target (e.g. backup read failed): the original
				// is intact; drop the txn markers so nothing is resurrected
				deleteQuietly(journal);
				deleteQuietly(tmp);
				fsyncDir(dir);
			}
			throw new SaveFailureException("commit failed for " + file, e);
		}
	}

	/**
	 * Repair an interrupted transaction. Call before every load.
	 * Returns true if a recovery action was taken. On an unresolvable state it
	 * throws {@link RecoveryNeededException} and PRESERVES all artifacts.
	 */
	public static boolean recover(Path file) throws IOException {
		var journal = journalOf(file);
		var tmp = tmpOf(file);
		var bak = bakOf(file);
		// a non-regular target means the disk fault is STILL present — never
		// clobber it during recovery or a genuine failure gets swallowed
		if (Files.exists(file) && !Files.isRegularFile(file)) {
			throw new RecoveryNeededException("target path is not a regular file (unwritable?): " + file);
		}
		if (!Files.exists(journal)) {
			// no pending txn; tidy a stray tmp only when the target is good
			if (Files.isRegularFile(file)) {
				deleteQuietly(tmp);
			}
			return false;
		}

		// journal present — classify it
		String want = readExpectedSha(journal); // null => present but INVALID

		if (want == null) {
			// v0.6.0 flaw A: an invalid/unreadable journal is NOT a blank check.
			// Only a conservative rollback to a prior complete backup is allowed.
			if (Files.isRegularFile(bak)) {
				Files.copy(bak, file, StandardCopyOption.REPLACE_EXISTING);
				fsync(file);
				fsyncDir(file.getParent());
				cleanup(file);
				return true;
			}
			// cannot confirm any valid version -> PRESERVE evidence, fail closed
			throw new RecoveryNeededException(
					"journal present but unreadable/malformed for " + file
					+ "; refusing to trust tmp/target without a verifiable digest."
					+ " Evidence preserved (journal/tmp/target/bak) — back up this"
					+ " directory before retrying.");
		}

		// valid journal with an expected digest: complete from a matching tmp
		if (Files.isRegularFile(tmp)) {
			try {
				String content = Files.readString(tmp, StandardCharsets.UTF_8);
				if (sha256Hex(content).equals(want)) {
					commitFromTmpNoJournal(file, tmp);
					fsyncDir(file.getParent());
					cleanup(file);
					return true;
				}
			} catch (IOException ignored) {
			}
		}
		// else roll back to the prior backup if present
		if (Files.isRegularFile(bak)) {
			Files.copy(bak, file, StandardCopyOption.REPLACE_EXISTING);
			fsync(file);
			fsyncDir(file.getParent());
			cleanup(file);
			return true;
		}
		// else the target may already be complete (crash after copy, before
		// journal delete) — verify against the digest and clear
		if (Files.isRegularFile(file)) {
			try {
				String content = Files.readString(file, StandardCharsets.UTF_8);
				if (sha256Hex(content).equals(want)) {
					cleanup(file);
					return true;
				}
			} catch (IOException ignored) {
			}
		}
		// unresolvable: do NOT fabricate empties or delete evidence
		throw new RecoveryNeededException("unresolvable pending transaction for " + file
				+ " (journal digest " + want + " matches no tmp/bak/target); evidence preserved");
	}

	private static void commitFromTmpNoJournal(Path file, Path tmp) throws IOException {
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Files.copy(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			Files.deleteIfExists(tmp);
		}
	}

	private static void cleanup(Path file) {
		deleteQuietly(journalOf(file));
		deleteQuietly(tmpOf(file));
		deleteQuietly(bakOf(file));
	}

	private static void deleteQuietly(Path p) {
		try { Files.deleteIfExists(p); } catch (IOException ignored) { }
	}

	/**
	 * Returns the expected sha for a WELL-FORMED journal, or null when the
	 * journal is present but unreadable or malformed (never a blank check).
	 */
	private static String readExpectedSha(Path journal) {
		String line;
		try {
			line = Files.readString(journal, StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			return null;
		}
		if (!line.startsWith(PENDING)) {
			return null;
		}
		var sha = line.substring(PENDING.length()).trim();
		// must look like a sha-256 hex digest (64 hex chars)
		return sha.matches("[0-9a-fA-F]{64}") ? sha : null;
	}

	private static void fsync(Path p) {
		try (var ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
			ch.force(true);
		} catch (IOException | RuntimeException ignored) {
			// best-effort durability hint
		}
	}

	private static void fsyncDir(Path dir) {
		if (dir == null) return;
		try (var ch = FileChannel.open(dir, StandardOpenOption.READ)) {
			ch.force(true);
		} catch (IOException | RuntimeException ignored) {
			// directory fsync is not supported on every platform (e.g. Windows);
			// recovery does not depend on it, it only narrows the crash window
		}
	}

	private AtomicFileWriter() { }
}
