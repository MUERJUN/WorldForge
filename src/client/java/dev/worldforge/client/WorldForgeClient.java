package dev.worldforge.client;

import dev.worldforge.WorldForge;
import dev.worldforge.client.chat.ChatClientState;
import dev.worldforge.client.gui.WorldForgeChatScreen;
import dev.worldforge.client.gui.WorldForgeStudioScreen;
import dev.worldforge.network.WorldForgeChatProtocol;
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
 * Client init (v0.7.0).
 *
 * <p>The K key now opens the AI CHAT, not the old Studio. The Studio is still
 * available (from the chat's settings entry and the old key binding) so
 * nothing that worked in v0.6.0 is lost — but a normal player never has to
 * go there.</p>
 *
 * <p>Receivers:</p>
 * <ul>
 *   <li>definition sync -&gt; client mirror ONLY (never server state);</li>
 *   <li>studio response -&gt; routes replies to the open editor screen;</li>
 *   <li>chat push -&gt; the conversation store.</li>
 * </ul>
 */
public class WorldForgeClient implements ClientModInitializer {

	private static KeyBinding chatKey;
	private static KeyBinding studioKey;

	/** The K key binding that opens the AI chat (used by the GUI auto-test). */
	public static KeyBinding chatKeyBinding() {
		return chatKey;
	}

	/** The legacy Studio key binding. */
	public static KeyBinding studioKeyBinding() {
		return studioKey;
	}

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(WorldForgePayloads.SyncDefinitions.ID,
				(payload, context) -> context.client().execute(() -> {
					ClientDefinitionMirror.replaceAllFromSync(payload.json(), payload.tombstones());
					// v0.4.0: the same payload may carry virtual block definitions
					dev.worldforge.client.ClientBlockState.applySnapshot(payload.blocksJson());
				}));

		ClientPlayNetworking.registerGlobalReceiver(WorldForgeStudioProtocol.StudioResponse.ID,
				(payload, context) -> context.client().execute(() -> {
					if (context.client().currentScreen instanceof WorldForgeStudioScreen screen) {
						screen.onResponse(payload);
					}
				}));

		// v0.7.0: the chat event stream
		ClientPlayNetworking.registerGlobalReceiver(WorldForgeChatProtocol.ChatPush.ID,
				(payload, context) -> context.client().execute(() ->
						ChatClientState.onPush(payload.type(), payload.json())));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientDefinitionMirror.clear();
			dev.worldforge.client.ClientStudioState.clear();
			dev.worldforge.client.ClientBlockState.clear();
		});
		// joining any world: force a fresh mirror pull; server auto-syncs on JOIN too
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				dev.worldforge.client.ClientStudioState.clear());

		// v0.7.0: K opens the AI chat
		chatKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.worldforge.chat",
				75, // GLFW_KEY_K
				"category.worldforge"));

		// the legacy Studio keeps its own binding for players who want it
		studioKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.worldforge.studio",
				76, // GLFW_KEY_L
				"category.worldforge"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (chatKey.wasPressed()) {
				if (client.currentScreen == null) {
					// only open in-game; the title screen has no server to talk to
					if (client.world != null || client.getNetworkHandler() != null) {
						client.setScreen(new WorldForgeChatScreen(
								() -> client.setScreen(new WorldForgeStudioScreen()),
								() -> client.setScreen(new dev.worldforge.client.gui.WorldForgeSettingsScreen())));
					}
					break;
				}
				// another Screen is open: do not steal (spec 3.1). Consume and ignore.
				break;
			}
			while (studioKey.wasPressed()) {
				if (client.currentScreen == null && (client.world != null || client.getNetworkHandler() != null)) {
					client.setScreen(new WorldForgeStudioScreen());
				}
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

		// v0.7.0: chat screen auto-test (only active with run/wf-chat-autotest.flag
		// or -Dworldforge.chat.autotest=true)
		dev.worldforge.client.dev.ChatAutoTest.register();

		// v0.7.0: chat-driven export acceptance (only active with
		// -Dworldforge.chat.export.autotest=true) — it runs a REAL Gradle build
		dev.worldforge.client.dev.ChatExportAutoTest.register();

		WorldForge.LOGGER.info("[WorldForge] client init done (K = AI chat, L = legacy studio)");
	}
}
