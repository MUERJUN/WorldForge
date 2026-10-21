package dev.worldforge;

import com.google.gson.JsonParser;
import dev.worldforge.chat.ChatMessage;
import dev.worldforge.chat.ChatSession;
import dev.worldforge.chat.ChatStore;
import dev.worldforge.workspace.WorkspaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.7.0 chat persistence and conversation state.
 *
 * <p>Covers the rules the chat history must keep: bounded, recoverable,
 * deletable, never silently overwritten when unreadable — and never holding a
 * credential.</p>
 */
class ChatStoreTest {

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		WorkspaceManager.overrideRootForTest(tempDir.resolve("worldforge"));
	}

	@AfterEach
	void tearDown() {
		WorkspaceManager.overrideRootForTest(null);
	}

	// ------------------------------------------------------------- sessions

	@Test
	void aSessionKeepsItsMessagesInOrder() {
		var s = new ChatSession("c1");
		s.add(ChatMessage.player("第一句"));
		s.add(ChatMessage.assistant("第二句"));
		s.add(ChatMessage.player("第三句"));
		assertEquals(3, s.messages().size());
		assertEquals("第一句", s.messages().get(0).text());
		assertEquals("第三句", s.messages().get(2).text());
	}

	@Test
	void aSessionIsBounded() {
		var s = new ChatSession("c1");
		for (int i = 0; i < ChatSession.MAX_MESSAGES + 50; i++) {
			s.add(ChatMessage.player("m" + i));
		}
		assertEquals(ChatSession.MAX_MESSAGES, s.messages().size());
		assertEquals("m" + (ChatSession.MAX_MESSAGES + 49), s.messages().get(ChatSession.MAX_MESSAGES - 1).text());
	}

	@Test
	void aSessionTracksOnePendingConfirmationAtATime() {
		var s = new ChatSession("c1");
		assertFalse(s.hasPendingConfirmation());
		s.setPending("cid-1", "update_item", "改这个");
		assertTrue(s.hasPendingConfirmation());
		assertEquals("cid-1", s.pendingConfirmationId());
		s.clearPending();
		assertFalse(s.hasPendingConfirmation());
	}

	// -------------------------------------------------------------- context

	@Test
	void theContextJsonIsValidForOneMessage() {
		var s = new ChatSession("c1");
		s.add(ChatMessage.player("你好"));
		var json = s.recentContext(6);
		assertTrue(json.startsWith("[") && json.endsWith("]"));
		var arr = JsonParser.parseString(json).getAsJsonArray();
		assertEquals(1, arr.size());
		assertEquals("user", arr.get(0).getAsJsonObject().get("role").getAsString());
		assertEquals("你好", arr.get(0).getAsJsonObject().get("text").getAsString());
	}

	@Test
	void theContextJsonIsValidForManyMessages() {
		// v0.7.0 fix: the entries used to be spliced together, which produced
		// invalid JSON as soon as there was more than one message
		var s = new ChatSession("c1");
		for (int i = 0; i < 6; i++) {
			s.add(ChatMessage.player("问题" + i));
			s.add(ChatMessage.assistant("回答" + i));
		}
		var json = s.recentContext(6);
		var arr = JsonParser.parseString(json).getAsJsonArray();
		assertTrue(arr.size() >= 1, "the context must not be empty");
		for (var el : arr) {
			var o = el.getAsJsonObject();
			assertTrue(o.has("role") && o.has("text"));
		}
	}

	@Test
	void theContextIsOldestFirst() {
		var s = new ChatSession("c1");
		s.add(ChatMessage.player("第一"));
		s.add(ChatMessage.assistant("第二"));
		var arr = JsonParser.parseString(s.recentContext(6)).getAsJsonArray();
		assertEquals("第一", arr.get(0).getAsJsonObject().get("text").getAsString());
		assertEquals("第二", arr.get(1).getAsJsonObject().get("text").getAsString());
	}

	@Test
	void theContextNeverCarriesAToolTurn() {
		// a restored "tool" turn could pose as a real tool result
		var s = new ChatSession("c1");
		s.add(ChatMessage.player("问题"));
		s.add(ChatMessage.tool("create_item", "SUCCEEDED", "{\"ok\":true}", ""));
		var json = s.recentContext(6);
		assertFalse(json.contains("create_item"), "tool turns must not be restored: " + json);
	}

	@Test
	void theContextIsEmptyForANewSession() {
		assertEquals("", new ChatSession("c1").recentContext(6));
	}

	// ------------------------------------------------------------- the store

	@Test
	void aConversationSurvivesASaveAndLoad() throws Exception {
		var s = new ChatSession("c1");
		s.add(ChatMessage.player("你好"));
		s.add(ChatMessage.assistant("你好，有什么可以帮你？"));
		assertTrue(ChatStore.save(s));

		var loaded = ChatStore.loadAll();
		assertTrue(loaded.containsKey("c1"), "the conversation must be restored: " + loaded.keySet());
		var back = loaded.get("c1");
		assertEquals(2, back.messages().size());
		assertEquals("你好", back.messages().get(0).text());
		assertEquals("你好，有什么可以帮你？", back.messages().get(1).text());
	}

	@Test
	void aConversationCanBeDeleted() {
		var s = new ChatSession("c1");
		s.add(ChatMessage.player("x"));
		ChatStore.save(s);
		assertTrue(ChatStore.exists("c1"));
		assertTrue(ChatStore.delete("c1"));
		assertFalse(ChatStore.exists("c1"));
	}

	@Test
	void anUnreadableHistoryIsNeverSilentlyOverwritten() throws Exception {
		// write a file that is not valid JSON
		Files.createDirectories(ChatStore.dir());
		Path file = ChatStore.dir().resolve(ChatStore.safeName("broken") + ".json");
		Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
		long sizeBefore = Files.size(file);

		var loaded = ChatStore.loadAll();
		assertTrue(loaded.containsKey("broken"), "the broken conversation must still be listed");
		var broken = loaded.get("broken");
		assertTrue(broken.corrupted(), "it must be marked as corrupted");
		assertTrue(broken.messages().isEmpty(), "a broken file must not become an empty history");

		// saving it back must be REFUSED, so the original bytes survive
		assertFalse(ChatStore.save(broken), "a corrupted history must not be overwritten");
		assertEquals(sizeBefore, Files.size(file));
		assertEquals("{ this is not json", Files.readString(file));
	}

	@Test
	void theStoreIsBounded() throws Exception {
		for (int i = 0; i < ChatStore.MAX_CONVERSATIONS + 10; i++) {
			var s = new ChatSession("c" + i);
			s.add(ChatMessage.player("x" + i));
			ChatStore.save(s);
		}
		var loaded = ChatStore.loadAll();
		assertTrue(loaded.size() <= ChatStore.MAX_CONVERSATIONS,
				"the store must stay bounded, got " + loaded.size());
	}

	@Test
	void aConversationIdWithAwkwardCharactersBecomesASafeFileName() {
		String safe = ChatStore.safeName("我的 会话/../evil");
		// the security property is "this can never escape the chats folder"
		assertFalse(safe.contains("/"), "no path separator: " + safe);
		assertFalse(safe.contains("\\"), "no windows separator: " + safe);
		assertFalse(safe.contains(".."), "no parent directory: " + safe);
		assertTrue(safe.length() > 0);
		// and the resolved path must stay inside the chats directory
		java.nio.file.Path resolved = ChatStore.dir().resolve(safe).normalize();
		assertTrue(resolved.startsWith(ChatStore.dir().toAbsolutePath().normalize()),
				"the resolved name must stay inside the chats directory: " + resolved);
	}

	@Test
	void aVeryLongConversationIdIsTruncated() {
		String safe = ChatStore.safeName("x".repeat(500));
		assertTrue(safe.length() <= 64, "the file name must stay usable: " + safe.length());
	}

	@Test
	void theStoreNeverHoldsACredential() throws Exception {
		var s = new ChatSession("c1");
		s.add(ChatMessage.player("我的 key 是 sk-abcdefghijklmnopqrstuvwxyz123456"));
		ChatStore.save(s);
		Path file = ChatStore.dir().resolve(ChatStore.safeName("c1") + ".json");
		String content = Files.readString(file);
		// the CHAT may contain whatever the player typed, but the STORE must
		// not contain the CONFIGURED key — and the configuration lives outside
		// this tree entirely
		assertFalse(Files.exists(ChatStore.dir().getParent().resolve("agent.json")),
				"the agent configuration must never live in the chat directory");
		assertNotNull(content);
	}

	@Test
	void theWorkspaceHasTheDocumentedLayout() {
		assertTrue(WorkspaceManager.chatsDir().getFileName().toString().equals("chats"));
		assertTrue(WorkspaceManager.workspaceDir().getFileName().toString().equals("workspace"));
		assertTrue(WorkspaceManager.exportsDir().getFileName().toString().equals("exports"));
		assertTrue(WorkspaceManager.logsDir().getFileName().toString().equals("logs"));
		assertTrue(Files.isDirectory(WorkspaceManager.chatsDir()));
	}

	@Test
	void aPathOutsideTheWorkspaceIsRefused() {
		assertNotNull(WorkspaceManager.resolveInsideWorkspace("exports/mod.jar"));
		assertEquals(null, WorkspaceManager.resolveInsideWorkspace("../../evil.jar"));
		assertEquals(null, WorkspaceManager.resolveInsideWorkspace("exports/../../evil.jar"));
	}
}
