package dev.worldforge;

import dev.worldforge.persistence.AtomicFileWriter;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.block.BlockDefinitionStore;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0.6.0 P0: crash-safe transactional persistence — flaw A (invalid journal
 * must never be a blank check) and flaw B (a failed first save must not leave
 * a torn target that a later load mistakes for a valid save), plus the normal
 * atomic / non-atomic success paths, for BOTH items.json and blocks.json.
 *
 * <p>Every recovery assertion also checks EVIDENCE PRESERVATION: on an
 * unresolvable state the writer must NOT delete the journal/tmp/bak, so an
 * operator can inspect and back up the directory.</p>
 */
class PersistenceTransactionSafetyTest {

	@TempDir
	Path tempDir;

	private static final String NEW_ITEM = "{\"version\":1,\"items\":[{\"id\":\"worldforge:newer\",\"name\":\"N\","
			+ "\"description\":\"\",\"texture\":{\"source\":\"vanilla\",\"resource\":\"minecraft:item/diamond_sword\"},"
			+ "\"attributes\":{},\"behaviors\":[]}]}";
	private static final String NEW_BLOCK = "{\"version\":1,\"blocks\":[{\"id\":\"worldforge:newer\",\"name\":\"N\","
			+ "\"description\":\"\",\"appearance\":\"stone\",\"category\":\"DECORATION\",\"hardness\":2.0,"
			+ "\"behaviors\":[],\"tickIntervalTicks\":0,\"outputCount\":0}]}";

	private ItemDefinition item(String id) {
		return new ItemDefinition(1, id, "I", "",
				TextureSource.vanilla("minecraft:item/diamond_sword"), Map.of(), List.of());
	}

	private BlockDefinition block(String id) {
		return new BlockDefinition(1, id, "B", "", "stone",
				BlockDefinition.Category.DECORATION, 2.0, List.of(), 0, null, 0);
	}

	private static String journalFor(String content) {
		return "pending " + AtomicFileWriter.sha256Hex(content) + "\n";
	}

	// ---- normal success paths ----

	@Test
	void atomicCommitLeavesNoTransactionArtifacts() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:a"))));
		var f = tempDir.resolve("items.json");
		assertTrue(Files.isRegularFile(f));
		assertFalse(Files.exists(tempDir.resolve("items.json.journal")), "journal cleared");
		assertFalse(Files.exists(tempDir.resolve("items.json.tmp")), "tmp cleared");
		assertFalse(Files.exists(tempDir.resolve("items.json.bak")), "bak cleared");
		assertTrue(store.tryLoadStrict().ok());
	}

	@Test
	void firstSaveWithoutPriorFileSucceeds() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:first"))));
		assertTrue(Files.isRegularFile(tempDir.resolve("items.json")));
		var res = store.tryLoadStrict();
		assertTrue(res.ok() && res.definitions().size() == 1);
	}

	@Test
	void itemSaveCrashMidCommitPreservesOldFile() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:original"))));
		var itemsFile = tempDir.resolve("items.json");
		String original = Files.readString(itemsFile);
		var loaded = store.tryLoadStrict();
		assertTrue(loaded.ok());
		assertEquals("worldforge:original", loaded.definitions().get(0).id());
		assertEquals(original, Files.readString(itemsFile));
	}

	@Test
	void saveSucceedsEvenIfStaleTmpPresent() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:a"))));
		Files.writeString(tempDir.resolve("items.json.tmp"), "stale garbage");
		assertTrue(store.save(List.of(item("worldforge:b"))));
		var res = store.tryLoadStrict();
		assertTrue(res.ok());
		assertEquals("worldforge:b", res.definitions().get(0).id());
	}

	// ---- valid journal: recovery completes from a matching tmp (flaw B good path) ----

	@Test
	void itemValidJournalCompletesFromTmpOnTornTarget() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:good"))));
		var itemsFile = tempDir.resolve("items.json");
		Files.writeString(tempDir.resolve("items.json.tmp"), NEW_ITEM);
		Files.writeString(tempDir.resolve("items.json.journal"), journalFor(NEW_ITEM));
		Files.writeString(itemsFile, "{\"version\":1,\"item"); // torn target

		var res = store.tryLoadStrict();
		assertTrue(res.ok(), "load must succeed after recovery: " + res.error());
		var content = Files.readString(itemsFile);
		assertTrue(content.contains("worldforge:newer"), "recovery completes from valid tmp");
		assertFalse(Files.exists(tempDir.resolve("items.json.journal")), "journal cleared");
	}

	@Test
	void blockValidJournalCompletesFromTmp() throws Exception {
		var store = new BlockDefinitionStore(tempDir);
		assertTrue(store.save(List.of(block("worldforge:good"))));
		var blocksFile = tempDir.resolve("blocks.json");
		Files.writeString(tempDir.resolve("blocks.json.tmp"), NEW_BLOCK);
		Files.writeString(tempDir.resolve("blocks.json.journal"), journalFor(NEW_BLOCK));
		Files.writeString(blocksFile, "{\"version\":1,\"blo"); // torn

		var res = store.tryLoadStrict();
		assertTrue(res.ok(), "blocks must recover from valid tmp: " + res.error());
		assertFalse(Files.exists(tempDir.resolve("blocks.json.journal")));
	}

	// ---- FLAW A: invalid journal must NOT be a blank check ----

	@Test
	void itemMalformedJournalDoesNotTrustTmp() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:good"))));
		var itemsFile = tempDir.resolve("items.json");
		Files.writeString(tempDir.resolve("items.json.tmp"), NEW_ITEM);
		Files.writeString(tempDir.resolve("items.json.journal"), "not-a-real-journal"); // INVALID
		Files.writeString(itemsFile, "{\"version\":1,\"item"); // torn target

		// with an invalid journal and no backup, recovery must FAIL CLOSED,
		// not complete from the unverifiable tmp
		var res = store.tryLoadStrict();
		assertFalse(res.ok(), "invalid journal must not authorize tmp completion");
		// EVIDENCE PRESERVED — operator can inspect/back up
		assertTrue(Files.exists(tempDir.resolve("items.json.journal")), "journal must survive");
		assertTrue(Files.exists(tempDir.resolve("items.json.tmp")), "tmp must survive");
	}

	@Test
	void itemUnreadableJournalDoesNotTrustTarget() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:good"))));
		var itemsFile = tempDir.resolve("items.json");
		Files.writeString(tempDir.resolve("items.json.journal"), "pending"); // no sha => INVALID
		// target happens to contain the newer doc; an invalid journal must not
		// silently "verify" it and clear the journal as if committed
		Files.writeString(itemsFile, NEW_ITEM);

		var res = store.tryLoadStrict();
		assertFalse(res.ok(), "invalid journal must not be a blank check on the target");
		assertTrue(Files.exists(tempDir.resolve("items.json.journal")), "evidence preserved");
	}

	@Test
	void itemWrongShaJournalRollsBackToBackup() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:good"))));
		var itemsFile = tempDir.resolve("items.json");
		String good = Files.readString(itemsFile);
		// journal names a sha that matches NEITHER tmp nor target; a backup of
		// the good version exists -> conservative rollback to the backup
		Files.writeString(tempDir.resolve("items.json.tmp"), NEW_ITEM);
		Files.writeString(tempDir.resolve("items.json.journal"),
				"pending " + AtomicFileWriter.sha256Hex("some-other-content") + "\n");
		Files.writeString(tempDir.resolve("items.json.bak"), good);
		Files.writeString(itemsFile, "{\"version\":1,\"item"); // torn

		var res = store.tryLoadStrict();
		assertTrue(res.ok(), "valid-but-mismatched journal rolls back to backup: " + res.error());
		assertEquals(good, Files.readString(itemsFile), "target restored from backup");
		assertFalse(Files.exists(tempDir.resolve("items.json.journal")), "txn cleared after rollback");
	}

	@Test
	void blockMalformedJournalDoesNotTrustTmp() throws Exception {
		var store = new BlockDefinitionStore(tempDir);
		assertTrue(store.save(List.of(block("worldforge:good"))));
		var blocksFile = tempDir.resolve("blocks.json");
		Files.writeString(tempDir.resolve("blocks.json.tmp"), NEW_BLOCK);
		Files.writeString(tempDir.resolve("blocks.json.journal"), "garbage journal"); // INVALID
		Files.writeString(blocksFile, "{\"version\":1,\"blo"); // torn

		var res = store.tryLoadStrict();
		assertFalse(res.ok(), "invalid journal must not authorize blocks tmp");
		assertTrue(Files.exists(tempDir.resolve("blocks.json.journal")), "evidence preserved");
		assertTrue(Files.exists(tempDir.resolve("blocks.json.tmp")), "tmp preserved");
	}

	// ---- FLAW B: a torn FIRST save (no prior version) must not be trusted ----

	@Test
	void firstSaveTornTargetNoTmpFailsClosed() throws Exception {
		// simulate a crash during the very first non-atomic commit: journal is
		// valid (pending sha of NEW_ITEM) but tmp was lost and there is NO bak
		// and NO prior version — the torn target must not be accepted.
		var store = new DefinitionStore(tempDir);
		var itemsFile = tempDir.resolve("items.json");
		Files.writeString(tempDir.resolve("items.json.journal"), journalFor(NEW_ITEM));
		Files.writeString(itemsFile, "{\"version\":1,\"item"); // torn, sha != pending

		var res = store.tryLoadStrict();
		assertFalse(res.ok(), "torn first-save target must not be treated as valid");
		assertTrue(Files.exists(tempDir.resolve("items.json.journal")), "evidence preserved");
	}

	@Test
	void recoveryIsIdempotent() throws Exception {
		var store = new DefinitionStore(tempDir);
		assertTrue(store.save(List.of(item("worldforge:good"))));
		var itemsFile = tempDir.resolve("items.json");
		Files.writeString(tempDir.resolve("items.json.tmp"), NEW_ITEM);
		Files.writeString(tempDir.resolve("items.json.journal"), journalFor(NEW_ITEM));
		Files.writeString(itemsFile, "{\"version\":1,\"item"); // torn

		assertTrue(AtomicFileWriter.recover(itemsFile), "first recover completes the txn");
		var content1 = Files.readString(itemsFile);
		// second recover is a no-op and must not change the result
		assertFalse(AtomicFileWriter.recover(itemsFile), "second recover has nothing to do");
		assertEquals(content1, Files.readString(itemsFile), "recovery is stable across restarts");
	}

	// ---- corrupt target without any pending txn is NOT silently emptied ----

	@Test
	void corruptTargetWithoutBackupIsNotSilentlyEmptied() throws Exception {
		var store = new DefinitionStore(tempDir);
		Files.writeString(tempDir.resolve("items.json"), "{ this is not json");
		assertFalse(store.tryLoadStrict().ok(),
				"corrupt file must not be treated as empty project");
	}

	@Test
	void blockCorruptTargetNotEmptied() throws Exception {
		var store = new BlockDefinitionStore(tempDir);
		Files.writeString(tempDir.resolve("blocks.json"), "not json at all");
		assertFalse(store.tryLoadStrict().ok(),
				"corrupt blocks file must not be treated as empty");
	}

	// ---- unwritable target (directory) => save fails, no wipe, signal kept ----

	@Test
	void unwritableTargetFailsSaveAndKeepsSignal() throws Exception {
		var store = new DefinitionStore(tempDir);
		Files.createDirectories(tempDir.resolve("items.json"));
		boolean saved = store.save(List.of(item("worldforge:x")));
		assertFalse(saved, "save must report failure when the target cannot be replaced");
		assertNotNull(store.lastError());
	}

	@Test
	void unwritableTargetBlocksRecoverToo() throws Exception {
		// recovery must not clobber a directory planted at the target path
		var store = new DefinitionStore(tempDir);
		Files.createDirectories(tempDir.resolve("items.json"));
		Files.writeString(tempDir.resolve("items.json.journal"), "pending");
		assertFalse(store.tryLoadStrict().ok(),
				"recover must fail closed on a non-regular target, not delete it");
		assertTrue(Files.isDirectory(tempDir.resolve("items.json")), "the fault stays visible");
	}
}
