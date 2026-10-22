package dev.worldforge.event;

import dev.worldforge.WorldForge;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
			// v0.4.0: bind the per-world virtual BLOCK definitions
			dev.worldforge.block.BlockRuntimeManager.bind(
					server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("worldforge"));
			// v0.3.0: (re)load the server-side agent config for this session
			dev.worldforge.ai.AgentBridge.reloadConfig(server);
		});

		// v0.4.0: reset the periodic-block quota every server tick so a large
		// field of periodic blocks can never stall the server
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// v0.4.0 phase 2b: bounded round-robin scheduler drives periodic blocks
			dev.worldforge.block.PeriodicScheduler.tick();
			dev.worldforge.block.PeriodicScheduler.runDueTasks(
					key -> server.getWorld(key));
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			WorldForgeRuntimeManager.onServerStopping(server);
		});

		// v0.1.2 issue #1: release ALL server static state after the world is
		// fully stopped. In the integrated server this happens when leaving a
		// single-player world inside the same JVM; without it, world A's
		// definitions lingered and could leak into a later multiplayer session.
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			WorldForgeRuntimeManager.onServerStopped();
			// v0.4.0: block definitions are world-bound too
			dev.worldforge.block.BlockRuntimeManager.release();
			// v0.3.0: sessions/proposals/provider are world-bound - never reuse
			// them across worlds/servers (spec 四.5 / test 12)
			dev.worldforge.ai.AgentBridge.releaseAll();
		});

		// Player joined: refresh stacks against current definitions and
		// push the definition snapshot so client tooltips/names resolve.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			DynamicItem.refreshAllIn(handler.player.getInventory());
			DynamicItem.refreshStack(handler.player.playerScreenHandler.getCursorStack());
			WorldForgePayloads.syncTo(handler.player);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// AI sessions are per-connection: no cross-world/cross-server reuse
			dev.worldforge.ai.AgentBridge.releasePlayer(handler.player.getUuid());
			// v0.7.1: the CHAT runtime must be cleaned up as well — a
			// disconnected player's running task would otherwise keep working
			// (and keep its confirmation pending) after they left.
			dev.worldforge.network.WorldForgeChatProtocol.releasePlayer(handler.player.getUuid());
		});

		WorldForge.LOGGER.info("[WorldForge] event listeners registered (once)");
	}

	private WorldForgeEvents() {
	}
}
