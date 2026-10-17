package dev.worldforge.client;

import dev.worldforge.WorldForge;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client init: receive definition sync. The runtime manager doubles as the
 * client mirror, so names / tooltips / behaviour hints of dynamic stacks
 * render correctly. Registered exactly once.
 *
 * <p>The optional K-panel GUI is deliberately out of MVP-0.1 priority (spec
 * section 7: 底层功能优先); {@code /wf list|info} commands provide the same
 * read path for now.</p>
 */
public class WorldForgeClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(WorldForgePayloads.SyncDefinitions.ID,
				(payload, context) -> context.client().execute(() ->
						WorldForgeRuntimeManager.replaceAllFromSync(payload.json())));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				WorldForgeRuntimeManager.clearClientMirror());

		WorldForge.LOGGER.info("[WorldForge] client init done (definition sync receiver registered)");
	}
}
