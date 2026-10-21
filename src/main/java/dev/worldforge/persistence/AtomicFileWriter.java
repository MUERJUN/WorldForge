package dev.worldforge.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * v0.6.0-gate P0-2: crash-safe transactional writer for the definition files.
 *
 * <p>Guarantee: after any failure (crash, unwritable target, torn copy) the
 * most recent COMPLETE valid version is recoverable — the sole live file is
 * never destroyed by a half-finished write.</p>
 *
 * <p>Commit protocol (all artifacts live in the SAME directory as the target,
 * so rename is same-filesystem and atomic where the FS supports it):</p>
 * <ol>
 *   <li>write the full new bytes to {@code <file>.tmp}, flush to disk;</li>
 *   <li>write {@code <file>.journal} recording the pending sha-256;</li>
 *   <li>try ATOMIC_MOVE tmp -&gt; file. On success: delete journal, done —
 *       the whole update was indivisible;</li>
 *   <li>if atomic move is refused (Windows TEMP junctions, AV, some FS):
 *       back up the current file to {@code <file>.bak}, copy tmp -&gt; file,
 *       re-read and verify the sha-256 matches, then delete journal. A crash
 *       at any step leaves the journal + (bak or tmp) so {@link #recover}
 *       can finish or roll back.</li>
 * </ol>
 *
 * <p>{@link #recover} runs before every load: if a journal is present it
 * completes the commit from a valid tmp, else restores the backup, else (file
 * itself valid) just clears the journal. It NEVER invents an empty document —
 * a corrupt target with no recoverable source is reported as a failure so the
 * caller keeps live state and does not overwrite with empties.</p>
 */
public final class AtomicFileWriter {

	private static final String TMP_SUFFIX = ".tmp";
	private static final String BAK_SUFFIX = ".bak";
	private static final String JOURNAL_SUFFIX = ".journal";

	/** Thrown when a save could not be completed; the previous file is intact. */
	public static final class SaveFailureException extends IOException {
		public SaveFailureException(String msg) { super(msg); }
		public SaveFailureException(String msg, Throwable cause) { super(msg, cause); }
	}

	/** Thrown when recovery found an inconsistent but non-fatal state. */
	public static final class RecoveryNeededException extends IOException {
		public RecoveryNeededException(String msg) { super(msg); }
	}

	public static Path tmpOf(Path file) { return file.resolveSibling(file.getFileName() + TMP_SUFFIX); }
	public static Path bakOf(Path file) { return file.resolveSibling(file.getFileName() + BAK_SUFFIX); }
	public static Path journalOf(Path file) { return file.resolveSibling(file.getFileName() + JOURNAL_SUFFIX); }

	/**
	 * Atomically (where possible) commit {@code content} to {@code file}.
	 * Throws {@link SaveFailureException} on any failure, leaving the previous
	 * file intact and recoverable.
	 */
	public static void commit(Path file, String content) throws IOException {
		var dir = file.getParent();
		Files.createDirectories(dir);
		var tmp = tmpOf(file);
		var journal = journalOf(file);
		var sha = sha256(content);

		// 1) full new bytes to tmp
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		fsync(tmp);

		// 2) journal marks the pending intent + expected digest
		Files.writeString(journal, "pending " + sha + "\n", StandardCharsets.UTF_8);

		// 3) try the indivisible path
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			try { Files.deleteIfExists(journal); } catch (IOException ignored) { }
			return; // atomic commit
		} catch (IOException moveFailure) {
			// includes AtomicMoveNotSupportedException (a subclass) — fall
			// through to the recoverable non-atomic path
		}

		// 4) non-atomic degrade: back up current, copy, verify, then clear journal
		try {
			// v0.6.0: a non-regular target (e.g. a directory planted to make the
			// path unwritable) must NOT be clobbered by copy+REPLACE_EXISTING —
			// that would silently swallow a genuine disk failure.
			if (Files.exists(file) && !Files.isRegularFile(file)) {
				throw new SaveFailureException("target is not a regular file: " + file);
			}
			if (Files.isRegularFile(file)) {
				Files.copy(file, bakOf(file), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.copy(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			fsync(file);
			// verify the committed bytes are exactly what we intended
			String written = Files.readString(file, StandardCharsets.UTF_8);
			if (!sha256(written).equals(sha)) {
				throw new SaveFailureException("post-copy verification failed for " + file);
			}
			try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
			try { Files.deleteIfExists(journal); } catch (IOException ignored) { }
			try { Files.deleteIfExists(bakOf(file)); } catch (IOException ignored) { }
		} catch (IOException | RuntimeException e) {
			// The caller rolls its in-memory change back on failure, so a
			// pending transaction must NOT survive to be "completed" by a later
			// recover() — that would resurrect a change the caller abandoned.
			// If the non-atomic copy tore the target, restore the backup first.
			try {
				var bak = bakOf(file);
				if (Files.isRegularFile(bak) && Files.isRegularFile(file)) {
					Files.copy(bak, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} catch (IOException rollbackIgnored) {
				// a crash right here leaves journal+bak: recover() rolls back
			}
			try { Files.deleteIfExists(journal); } catch (IOException ignored) { }
			try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
			throw new SaveFailureException("commit failed for " + file, e);
		}
	}

	/**
	 * Repair any interrupted transaction. Call before every load.
	 * Returns true if a recovery action was taken.
	 */
	public static boolean recover(Path file) throws IOException {
		var journal = journalOf(file);
		var tmp = tmpOf(file);
		var bak = bakOf(file);
		// v0.6.0: a non-regular target (directory planted to block writes) means
		// the disk fault is STILL present — never clobber it during recovery,
		// or a genuine failure gets silently swallowed.
		if (Files.exists(file) && !Files.isRegularFile(file)) {
			throw new RecoveryNeededException("target path is not a regular file (unwritable?): " + file);
		}
		if (!Files.exists(journal)) {
			// no pending transaction; just clean stray tmp/bak if the file is good
			if (Files.isRegularFile(file)) {
				try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
			}
			return false;
		}
		String want = readExpectedSha(journal);

		// prefer completing from a valid tmp (the intended new version)
		if (Files.isRegularFile(tmp)) {
			try {
				String content = Files.readString(tmp, StandardCharsets.UTF_8);
				if (want == null || sha256(content).equals(want)) {
					commitFromTmpNoJournal(file, tmp);
					cleanup(file);
					return true;
				}
			} catch (IOException ignored) {
			}
		}
		// else roll back to the backup if present and valid
		if (Files.isRegularFile(bak)) {
			Files.copy(bak, file, StandardCopyOption.REPLACE_EXISTING);
			cleanup(file);
			return true;
		}
		// else the target itself may already be complete (crash after copy,
		// before journal delete) — verify and clear
		if (Files.isRegularFile(file)) {
			try {
				String content = Files.readString(file, StandardCharsets.UTF_8);
				if (want == null || sha256(content).equals(want)) {
					cleanup(file);
					return true;
				}
			} catch (IOException ignored) {
			}
		}
		// inconsistent: do NOT fabricate empties — surface for the caller to keep
		// live state; leave artifacts for a human/diagnostic
		throw new RecoveryNeededException("unresolvable pending transaction for " + file
				+ " (journal present but no valid tmp/bak/target)");
	}

	private static void commitFromTmpNoJournal(Path file, Path tmp) throws IOException {
		// tmp is the intended bytes; put them in place (atomic if we can)
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Files.copy(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			Files.deleteIfExists(tmp);
		}
	}

	private static void cleanup(Path file) {
		try { Files.deleteIfExists(journalOf(file)); } catch (IOException ignored) { }
		try { Files.deleteIfExists(tmpOf(file)); } catch (IOException ignored) { }
		try { Files.deleteIfExists(bakOf(file)); } catch (IOException ignored) { }
	}

	private static String readExpectedSha(Path journal) {
		try {
			String line = Files.readString(journal, StandardCharsets.UTF_8).trim();
			var parts = line.split(" ");
			return parts.length >= 2 ? parts[1] : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static String sha256(String s) {
		try {
			var md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return null; // sha-256 is mandatory in every JDK; unreachable
		}
	}

	private static void fsync(Path p) {
		// best-effort durability hint; ignored on platforms without it
		try (var ch = Files.newByteChannel(p,
				java.nio.file.StandardOpenOption.WRITE)) {
			if (ch instanceof java.nio.channels.FileChannel fc) {
				fc.force(true);
			}
		} catch (IOException ignored) {
		}
	}

	private AtomicFileWriter() { }
}
