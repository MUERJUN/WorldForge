package dev.worldforge.event;

import dev.worldforge.WorldForge;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Game event wiring - registered exactly ONCE at mod init (test G:
 * repeated world load / reload must not duplicate listeners; Fabric event
 * registration happens a single time here, world load only refreshes state).
 */
public final class WorldForgeEvents {

	private static volatile boolean registered = false;

	public static synchronized void register() {
		if (registered) {
			return; // idempotent: no duplicate listeners under any init path
		}
		registered = true;

		// World (server) lifecycle: per-world definition isolation + persistence.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			WorldForgeRuntimeManager.onServerStarted(server);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			WorldForgeRuntimeManager.onServerStopping(server);
		});

		// Player joined: refresh stacks against current definitions and
		// push the definition snapshot so client tooltips/names resolve.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			DynamicItem.refreshAllIn(handler.player.getInventory());
			DynamicItem.refreshStack(handler.player.playerScreenHandler.getCursorStack());
			WorldForgePayloads.syncTo(handler.player);
		});

		WorldForge.LOGGER.info("[WorldForge] event listeners registered (once)");
	}

	private WorldForgeEvents() {
	}
}
