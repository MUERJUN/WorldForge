package dev.worldforge.tool;

import dev.worldforge.WorldForge;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import java.util.List;

/**
 * THE single mutation entry point for WorldForge (issue #3).
 *
 * <p>Every caller - the {@code /wf} commands today and the future
 * {@code AgentBridge} (AI Agent tool calls) - must go through these methods.
 * Each tool performs the complete safe pipeline: validate → mutate +
 * persist with rollback → re-bake held stacks → broadcast snapshot.</p>
 *
 * <p>v0.1.2 issue #2: results are no longer a single boolean. A mutation has
 * THREE separable outcomes and callers (especially an AI agent deciding
 * whether to retry) must be able to tell them apart:</p>
 * <ul>
 *   <li>{@code persisted} - the on-disk transaction committed (or rolled
 *       back). Disk failure ⇒ everything below is skipped, result is a
 *       plain failure.</li>
 *   <li>{@code applied} - server runtime state was changed AND held stacks
 *       were re-baked. A refresh exception leaves the definition live but
 *       stacks stale; {@code /wf reload} or the next join re-applies.</li>
 *   <li>{@code sync} - client snapshot broadcast: ok / partial (some players
 *       failed) / failed. Partial sync is NOT a mutation failure; vanilla
 *       clients that never registered the channel are skipped, not counted.
 *       A retry is simply {@link #resync(MinecraftServer)}.</li>
 * </ul>
 */
public final class WorldForgeTools {

	/** Three-phase outcome so agents/commands can decide retry semantics. */
	public record ToolResult(boolean persisted, boolean applied, SyncStatus sync, String message) {
		public enum SyncStatus { NONE, OK, PARTIAL, FAILED }

		/** overall success = the definition change actually took effect on the server */
		public boolean ok() {
			return persisted && applied;
		}

		public boolean needsRetry() {
			// disk is the only non-retriable-by-us failure; sync/applying can be
			// re-driven by resync()/reload without changing the definition again
			return persisted && (!applied || sync == SyncStatus.FAILED || sync == SyncStatus.PARTIAL);
		}

		private static ToolResult diskFailure(String message) {
			return new ToolResult(false, false, SyncStatus.NONE, message);
		}

		private ToolResult withSync(SyncStatus s, String extra) {
			return new ToolResult(persisted, applied, s, extra == null ? message : message + extra);
		}
	}

	/** Normalise "thunder_sword" -> "worldforge:thunder_sword". */
	public static String normalizeId(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		return id.contains(":") ? id : "worldforge:" + id;
	}

	// ---- mutation tools ----

	public static ToolResult createItem(MinecraftServer server, ItemDefinition def) {
		return finish(server, WorldForgeRuntimeManager.createItem(def));
	}

	public static ToolResult updateItem(MinecraftServer server, ItemDefinition def) {
		return finish(server, WorldForgeRuntimeManager.updateItem(def));
	}

	/** Single-attribute hot edit (what /wf setdamage and agent tools need). */
	public static ToolResult setAttribute(MinecraftServer server, String id, String attribute, double value) {
		ItemDefinition def = WorldForgeRuntimeManager.get(normalizeId(id));
		if (def == null) {
			return ToolResult.diskFailure("no such item: " + id);
		}
		return updateItem(server, def.withAttribute(attribute, value));
	}

	public static ToolResult deleteItem(MinecraftServer server, String id) {
		return finish(server, WorldForgeRuntimeManager.deleteItem(normalizeId(id)));
	}

	public static ToolResult reload(MinecraftServer server) {
		var r = WorldForgeRuntimeManager.reload();
		if (!r.ok()) {
			// issue #3: corrupt/unreadable file. The transaction did NOT commit
			// and live runtime state was preserved; callers get a failure with a
			// clear cause (never a silent "project emptied" success).
			return new ToolResult(false, false, ToolResult.SyncStatus.NONE, r.error());
		}
		return applyAndSync(server, true, "reloaded " + r.count() + " definitions");
	}

	public static List<ItemDefinition> listItems() {
		return WorldForgeRuntimeManager.list();
	}

	public static ItemDefinition getItem(String id) {
		return WorldForgeRuntimeManager.get(normalizeId(id));
	}

	/** Re-run apply+broadcast without changing any definition (retry hook). */
	public static ToolResult resync(MinecraftServer server) {
		return applyAndSync(server, true, "resync");
	}

	/**
	 * Compatibility wrapper used by demo batch flows: one apply+sync pass over
	 * all players after a sequence of mutations. (Single mutations already
	 * apply+sync inside {@link #createItem} etc.)
	 */
	public static void broadcast(MinecraftServer server) {
		applyAndSync(server, true, "broadcast");
	}

	// ---- pipeline internals ----

	private static ToolResult finish(MinecraftServer server, WorldForgeRuntimeManager.MutationResult r) {
		if (!r.ok()) {
			// manager already rolled memory back; nothing applied, nothing synced
			return ToolResult.diskFailure(r.message());
		}
		return applyAndSync(server, true, r.message());
	}

	/**
	 * Apply phase (re-bake held stacks) + sync phase (broadcast snapshot),
	 * each with its own failure accounting (issue #2).
	 */
	private static ToolResult applyAndSync(MinecraftServer server, boolean persisted, String message) {
		boolean applied = true;
		if (server != null) {
			try {
				server.getPlayerManager().getPlayerList().forEach(p -> {
					DynamicItem.refreshAllIn(p.getInventory());
					DynamicItem.refreshStack(p.playerScreenHandler.getCursorStack());
				});
			} catch (Exception e) {
				applied = false;
				WorldForge.LOGGER.error("[WorldForge] APPLY phase failed after persisted mutation "
						+ "(definitions stay live; next join/reload re-bakes stacks): {}", e.toString());
			}
			var sync = syncToAll(server);
			return new ToolResult(persisted, applied, sync, message);
		}
		// headless (tests / tool without server handle): change is live in memory,
		// but there is nothing to apply onto or sync to
		return new ToolResult(persisted, applied, ToolResult.SyncStatus.NONE, message);
	}

	/** Sync each connected WF-capable player; distinguishes full/partial/failed. */
	public static ToolResult.SyncStatus syncToAll(MinecraftServer server) {
		int sent = 0, failed = 0;
		for (var player : server.getPlayerManager().getPlayerList()) {
			if (!WorldForgePayloads.clientSupportsSync(player)) {
				continue; // vanilla client: not a failure, nothing to send
			}
			try {
				WorldForgePayloads.syncTo(player);
				sent++;
			} catch (Exception e) {
				failed++;
				WorldForge.LOGGER.warn("[WorldForge] sync to {} failed: {}",
						player.getName().getString(), e.toString());
			}
		}
		if (failed == 0) {
			return sent == 0 ? ToolResult.SyncStatus.NONE : ToolResult.SyncStatus.OK;
		}
		return sent == 0 ? ToolResult.SyncStatus.FAILED : ToolResult.SyncStatus.PARTIAL;
	}

	/** Convenience for command sources. */
	public static ToolResult createItem(ServerCommandSource src, ItemDefinition def) {
		return createItem(src.getServer(), def);
	}

	private WorldForgeTools() {
	}
}
