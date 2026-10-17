package dev.worldforge.client;

import dev.worldforge.WorldForge;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.runtime.ClientDefinitionMirror;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client init: receive definition sync into the CLIENT mirror ONLY (issue #2:
 * never into server-authoritative state - in single player both sides share
 * this JVM and a shared map let sync writes destroy server truth).
 *
 * <p>The optional K-panel GUI stays out of scope; {@code /wf list|info}
 * commands provide the same read path.</p>
 */
public class WorldForgeClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(WorldForgePayloads.SyncDefinitions.ID,
				(payload, context) -> context.client().execute(() ->
						ClientDefinitionMirror.replaceAllFromSync(
								payload.json(), payload.tombstones())));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				ClientDefinitionMirror.clear());

		WorldForge.LOGGER.info("[WorldForge] client init done (mirror receiver registered)");
	}
}
