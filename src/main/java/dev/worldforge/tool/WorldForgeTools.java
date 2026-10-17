package dev.worldforge.tool;

import dev.worldforge.WorldForge;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import java.util.List;
import java.util.Map;

/**
 * THE single mutation entry point for WorldForge (issue #3).
 *
 * <p>Every caller - the {@code /wf} commands today and the future
 * {@code AgentBridge} (AI Agent tool calls) - must go through these methods.
 * Each tool performs the complete safe pipeline:</p>
 *
 * <ol>
 *   <li>validate (DefinitionValidator inside the manager),</li>
 *   <li>mutate + persist with rollback (manager returns a failure result if
 *       the disk write fails - no fake success, issue #1),</li>
 *   <li>on success: re-bake every player-held stack (hot edit / invalid
 *       stripping, issue #4) and broadcast the new definition snapshot +
 *       tombstones to all clients (issue #2 mirror feed).</li>
 * </ol>
 *
 * <p>No caller may touch {@link WorldForgeRuntimeManager} mutation methods
 * directly except tests and tool internals; the tools are also the natural
 * JSON-friendly surface an LLM bridge will expose (create_item, update_item,
 * delete_item, list_items, set_attribute, reload).</p>
 */
public final class WorldForgeTools {

	public record ToolResult(boolean ok, String message) {}

	/** Normalise "thunder_sword" -> "worldforge:thunder_sword". */
	public static String normalizeId(String id) {
		if (id == null || id.isBlank()) {
			return null;
		}
		return id.contains(":") ? id : "worldforge:" + id;
	}

	public static ToolResult createItem(MinecraftServer server, ItemDefinition def) {
		var r = WorldForgeRuntimeManager.createItem(def);
		if (r.ok()) {
			broadcast(server);
			return new ToolResult(true, r.message());
		}
		return new ToolResult(false, r.message());
	}

	public static ToolResult updateItem(MinecraftServer server, ItemDefinition def) {
		var r = WorldForgeRuntimeManager.updateItem(def);
		if (r.ok()) {
			broadcast(server);
			return new ToolResult(true, r.message());
		}
		return new ToolResult(false, r.message());
	}

	/** Single-attribute hot edit (what /wf setdamage and agent tools need). */
	public static ToolResult setAttribute(MinecraftServer server, String id, String attribute, double value) {
		ItemDefinition def = WorldForgeRuntimeManager.get(normalizeId(id));
		if (def == null) {
			return new ToolResult(false, "no such item: " + id);
		}
		return updateItem(server, def.withAttribute(attribute, value));
	}

	public static ToolResult deleteItem(MinecraftServer server, String id) {
		var r = WorldForgeRuntimeManager.deleteItem(normalizeId(id));
		if (r.ok()) {
			broadcast(server);
			return new ToolResult(true, r.message());
		}
		return new ToolResult(false, r.message());
	}

	public static ToolResult reload(MinecraftServer server) {
		int n = WorldForgeRuntimeManager.reloadDefinitions();
		if (n < 0) {
			return new ToolResult(false, "当前世界尚未绑定存储");
		}
		broadcast(server);
		return new ToolResult(true, "reloaded " + n + " definitions");
	}

	public static List<ItemDefinition> listItems() {
		return WorldForgeRuntimeManager.list();
	}

	public static ItemDefinition getItem(String id) {
		return WorldForgeRuntimeManager.get(normalizeId(id));
	}

	/**
	 * Post-mutation broadcast: re-bake held stacks against the new server
	 * truth (applies hot edits AND strips invalid stacks), then push the
	 * snapshot + tombstone set to every client mirror.
	 */
	public static void broadcast(MinecraftServer server) {
		if (server == null) {
			return;
		}
		try {
			server.getPlayerManager().getPlayerList().forEach(p -> {
				DynamicItem.refreshAllIn(p.getInventory());
				// held cursor item too
				DynamicItem.refreshStack(p.playerScreenHandler.getCursorStack());
			});
			WorldForgePayloads.syncToAll(server);
		} catch (Exception e) {
			WorldForge.LOGGER.error("[WorldForge] broadcast after mutation failed: {}", e.toString());
		}
	}

	/** Convenience for command sources. */
	public static ToolResult createItem(ServerCommandSource src, ItemDefinition def) {
		return createItem(src.getServer(), def);
	}

	private WorldForgeTools() {
	}
}
