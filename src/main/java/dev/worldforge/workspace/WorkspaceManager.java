package dev.worldforge.workspace;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The single owner of the WorldForge directory layout inside the running
 * Minecraft instance.
 *
 * <pre>
 * &lt;GameDir&gt;/worldforge/
 *     chats/      conversation history (no API keys, ever)
 *     workspace/  scratch space for generated projects
 *     exports/    exported standalone mod artifacts
 *     logs/       local diagnostic logs
 * </pre>
 *
 * <p>The API configuration deliberately lives OUTSIDE this tree (in
 * {@code config/worldforge/agent.json}) so that a chat history can never
 * contain a credential, and so that copying the worldforge folder never
 * copies a key.</p>
 *
 * <p>This class performs directory creation only — it never downloads a JDK,
 * a Gradle distribution or anything else. That is explicitly out of scope for
 * this release.</p>
 */
public final class WorkspaceManager {

	/** The folder name inside the game directory. */
	public static final String ROOT_NAME = "worldforge";

	private static volatile Path root;

	private WorkspaceManager() {
	}

	/** The root directory, created on demand. */
	public static Path root() {
		Path r = root;
		if (r != null) {
			return r;
		}
		synchronized (WorkspaceManager.class) {
			if (root == null) {
				Path base = FabricLoader.getInstance().getGameDir();
				Path dir = base.resolve(ROOT_NAME);
				try {
					Files.createDirectories(dir);
				} catch (IOException ignored) {
					// a read-only game dir must not crash the game; callers
					// report the real failure when they try to write
				}
				root = dir;
			}
			return root;
		}
	}

	/** Chat history directory. */
	public static Path chatsDir() {
		return ensure(root().resolve("chats"));
	}

	/** Scratch workspace for generated projects. */
	public static Path workspaceDir() {
		return ensure(root().resolve("workspace"));
	}

	/** Exported standalone mod artifacts. */
	public static Path exportsDir() {
		return ensure(root().resolve("exports"));
	}

	/** Local diagnostic logs. */
	public static Path logsDir() {
		return ensure(root().resolve("logs"));
	}

	/** Creates a directory (and parents) if it does not exist yet. */
	public static Path ensure(Path dir) {
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
			// best effort; the caller reports the real failure on write
		}
		return dir;
	}

	/**
	 * Resolves a path INSIDE the workspace, refusing anything that escapes it.
	 *
	 * <p>Used by the export tool so a model-supplied output path can never
	 * write outside the WorldForge directory.</p>
	 *
	 * @param relative a path relative to the workspace root
	 * @return the absolute path, or {@code null} when it escapes the root
	 */
	public static Path resolveInsideWorkspace(String relative) {
		if (relative == null || relative.isBlank()) {
			return null;
		}
		Path base = root().toAbsolutePath().normalize();
		Path candidate;
		try {
			candidate = base.resolve(relative).toAbsolutePath().normalize();
		} catch (Exception e) {
			return null;
		}
		return candidate.startsWith(base) ? candidate : null;
	}

	/** Test/embedding hook: point the workspace at a different directory. */
	public static void overrideRootForTest(Path dir) {
		root = dir;
	}
}
