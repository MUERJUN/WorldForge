package dev.worldforge.client.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.workspace.WorkspaceManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent conversation history under {@code <GameDir>/worldforge/chats/}.
 *
 * <p>Rules this store keeps:</p>
 * <ul>
 *   <li><b>No credentials, ever.</b> Only chat text is written. The AI
 *       configuration lives in {@code config/worldforge/agent.json}, outside
 *       this tree, so a chat history can never contain a key.</li>
 *   <li><b>A corrupt history is never silently replaced.</b> When a file
 *       cannot be read, the session is marked corrupted and saving is
 *       REFUSED for it, so the original bytes survive for inspection.</li>
 *   <li><b>Bounded.</b> At most {@link #MAX_CONVERSATIONS} conversations and
 *       {@link ChatSession#MAX_MESSAGES} messages each; the oldest are
 *       dropped on load.</li>
 * </ul>
 */
public final class ChatStore {

	/** hard cap on stored conversations */
	public static final int MAX_CONVERSATIONS = 50;

	/** hard cap on the text of one stored message */
	public static final int MAX_MESSAGE_CHARS = 4000;

	private ChatStore() {
	}

	/** The directory holding the chat files. */
	public static Path dir() {
		return WorkspaceManager.chatsDir();
	}

	private static Path file(String conversationId) {
		return dir().resolve(safeName(conversationId) + ".json");
	}

	/** Makes an arbitrary id safe as a file name. */
	public static String safeName(String id) {
		if (id == null || id.isBlank()) {
			return "default";
		}
		StringBuilder sb = new StringBuilder();
		for (char c : id.toCharArray()) {
			sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
		}
		String out = sb.toString();
		return out.length() > 64 ? out.substring(0, 64) : out;
	}

	/**
	 * Loads every stored conversation.
	 *
	 * @return the conversations, newest first; a corrupt file yields a session
	 *         marked {@link ChatSession#corrupted()} rather than an empty one
	 */
	public static Map<String, ChatSession> loadAll() {
		Map<String, ChatSession> out = new LinkedHashMap<>();
		Path d = dir();
		if (!Files.isDirectory(d)) {
			return out;
		}
		List<Path> files = new ArrayList<>();
		try (var stream = Files.list(d)) {
			stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
		} catch (IOException e) {
			return out;
		}
		files.sort((a, b) -> {
			try {
				return Long.compare(Files.getLastModifiedTime(b).toMillis(),
						Files.getLastModifiedTime(a).toMillis());
			} catch (IOException e) {
				return 0;
			}
		});
		int loaded = 0;
		for (Path f : files) {
			if (loaded >= MAX_CONVERSATIONS) {
				break;
			}
			try {
				ChatSession session = parse(Files.readString(f, StandardCharsets.UTF_8), f);
				if (session != null) {
					out.put(session.conversationId(), session);
					loaded++;
				}
			} catch (Exception e) {
				// keep the original file: mark the session as corrupted and
				// refuse to overwrite it later
				String id = f.getFileName().toString().replace(".json", "");
				ChatSession broken = new ChatSession(id);
				broken.setCorrupted(true);
				out.put(broken.conversationId(), broken);
			}
		}
		return out;
	}

	private static ChatSession parse(String json, Path source) {
		var el = JsonParser.parseString(json);
		if (!el.isJsonObject()) {
			return null;
		}
		JsonObject o = el.getAsJsonObject();
		String id = o.has("id") ? o.get("id").getAsString() : source.getFileName().toString();
		ChatSession session = new ChatSession(id);
		if (o.has("title")) {
			session.setTitle(o.get("title").getAsString());
		}
		if (o.has("messages") && o.get("messages").isJsonArray()) {
			JsonArray arr = o.getAsJsonArray("messages");
			int start = Math.max(0, arr.size() - ChatSession.MAX_MESSAGES);
			for (int i = start; i < arr.size(); i++) {
				if (arr.get(i).isJsonObject()) {
					session.add(ChatMessage.fromJson(arr.get(i).getAsJsonObject()));
				}
			}
		}
		return session;
	}

	/**
	 * Saves one conversation.
	 *
	 * @return true when it was written; false when the session is marked
	 *         corrupted (its original file is left untouched on purpose)
	 */
	public static boolean save(ChatSession session) {
		if (session == null || session.corrupted()) {
			return false;
		}
		Path target = file(session.conversationId());
		try {
			Files.createDirectories(target.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("id", session.conversationId());
			o.addProperty("title", session.title());
			o.addProperty("savedAt", System.currentTimeMillis());
			JsonArray arr = new JsonArray();
			List<ChatMessage> msgs = session.messages();
			int start = Math.max(0, msgs.size() - ChatSession.MAX_MESSAGES);
			for (int i = start; i < msgs.size(); i++) {
				arr.add(msgs.get(i).toJson());
			}
			o.add("messages", arr);
			// write to a temp file first, then move: a crash mid-write must not
			// destroy the previous history
			Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
			Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** Deletes a stored conversation. */
	public static boolean delete(String conversationId) {
		try {
			return Files.deleteIfExists(file(conversationId));
		} catch (IOException e) {
			return false;
		}
	}

	/** True when a stored file exists for this conversation. */
	public static boolean exists(String conversationId) {
		return Files.isRegularFile(file(conversationId));
	}
}
