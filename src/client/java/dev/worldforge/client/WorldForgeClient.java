package dev.worldforge.client;

import dev.worldforge.WorldForge;
import dev.worldforge.client.gui.WorldForgeStudioScreen;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.network.WorldForgeStudioProtocol;
import dev.worldforge.runtime.ClientDefinitionMirror;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;

/**
 * Client init (v0.2.0):
 * - definition sync receiver -> client mirror ONLY (never server state);
 * - studio response receiver -> routes replies back to the open editor screen
 *   by requestId (a stale screen simply drops mismatches);
 * - K keybinding (rebindable in vanilla controls) opens WorldForge Studio;
 * - disconnect clears all client-side editor state (no cross-world residue).
 */
public class WorldForgeClient implements ClientModInitializer {

	private static KeyBinding studioKey;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(WorldForgePayloads.SyncDefinitions.ID,
				(payload, context) -> context.client().execute(() -> {
					ClientDefinitionMirror.replaceAllFromSync(payload.json(), payload.tombstones());
					// v0.4.0: the same payload may carry virtual block definitions
					ClientBlockState.applySnapshot(payload.blocksJson());
				}));

		ClientPlayNetworking.registerGlobalReceiver(WorldForgeStudioProtocol.StudioResponse.ID,
				(payload, context) -> context.client().execute(() -> {
					if (context.client().currentScreen instanceof WorldForgeStudioScreen screen) {
						screen.onResponse(payload);
					}
				}));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientDefinitionMirror.clear();
			ClientStudioState.clear();
			ClientBlockState.clear();
		});
		// joining any world: force a fresh mirror pull; server auto-syncs on JOIN too
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				ClientStudioState.clear());

		studioKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.worldforge.studio",
				75, // GLFW_KEY_K
				"category.worldforge"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (studioKey.wasPressed()) {
				if (client.currentScreen == null) {
					// only open in-game; title screen has no server to talk to
					if (client.world != null || client.getNetworkHandler() != null) {
						client.setScreen(new WorldForgeStudioScreen());
					}
					break;
				}
				// another Screen is open: do not steal (spec 3.1). Consume and ignore.
				break;
			}
		});

		// v0.4.0: renderer for placed virtual blocks (borrows vanilla block models)
		net.minecraft.client.render.block.entity.BlockEntityRendererFactories.register(
				dev.worldforge.registry.WorldForgeBlocks.DYNAMIC_BLOCK_ENTITY,
				dev.worldforge.client.render.DynamicBlockEntityRenderer::new);

		// v0.3.2: in-game automated GUI check (only active with run/wf-autotest.flag)
		dev.worldforge.client.dev.StudioAutoTest.register();

		// v0.5.2: export screen auto-test (only active with -Dworldforge.export.autotest=true)
		dev.worldforge.client.dev.ExportScreenAutoTest.init();

		WorldForge.LOGGER.info("[WorldForge] client init done (studio key = K, receivers registered)");
	}
}
