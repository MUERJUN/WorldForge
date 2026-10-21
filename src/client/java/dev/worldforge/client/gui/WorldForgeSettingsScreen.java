package dev.worldforge.client.gui;

import dev.worldforge.studio.StudioUiState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.List;

/**
 * The AI settings, reachable from the chat's gear button.
 *
 * <p>Hosts the same {@link AiSettingsPanel} the legacy Studio uses, so there
 * is exactly ONE place where the endpoint, model and key are configured —
 * and exactly one place that decides whether the key is editable at all.</p>
 *
 * <p>On a dedicated server the panel stays strictly read-only: it never
 * displays or accepts the server's key, and no packet can read or overwrite
 * it.</p>
 */
public class WorldForgeSettingsScreen extends Screen {

	private final StudioUiState ui = new StudioUiState();
	private AiSettingsPanel panel;
	private int x0;
	private int y0;
	private int panelW;
	private int panelH;

	public WorldForgeSettingsScreen() {
		super(Text.literal("WorldForge AI 设置"));
	}

	@Override
	protected void init() {
		var client = this.client;
		panelW = Math.max(220, Math.min(430, width - 20));
		panelH = Math.max(150, Math.min(210, height - 20));
		x0 = (width - panelW) / 2;
		y0 = (height - panelH) / 2;

		panel = new AiSettingsPanel(ui, new AiSettingsPanel.Host() {
			@Override
			public Path localConfigFile() {
				// single player: the client and the integrated server share this
				// JVM and this machine, so the file is read and written directly
				// and the key never enters a network packet. A dedicated server
				// provides no path at all — the panel then stays read-only.
				return client != null && client.getServer() != null
						? net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
								.resolve("worldforge").resolve("agent.json")
						: null;
			}

			@Override
			public void applyToServer() {
				var server = client == null ? null : client.getServer();
				if (server != null) {
					server.execute(() -> dev.worldforge.ai.AgentBridge.reloadConfig(server));
				}
			}
		});
		panel.init(x0 + 10, y0 + 22, panelW - 20, panelH - 34,
				() -> ui.markSettingsDirty(), this::close);
		for (ButtonWidget b : panel.buttons()) {
			addDrawableChild(b);
		}
		for (var f : panel.fields()) {
			addDrawableChild(f);
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, width, height, dev.worldforge.client.chat.ChatUi.SCRIM);
		dev.worldforge.client.chat.ChatUi.roundedOutline(ctx, x0, y0, panelW, panelH, 6,
				dev.worldforge.client.chat.ChatUi.PANEL, dev.worldforge.client.chat.ChatUi.BORDER);
		ctx.drawTextWithShadow(textRenderer, "AI 设置", x0 + 10, y0 + 7, 0xFFE6E9F0);
		if (panel != null) {
			panel.render(ctx, x0 + 10, y0 + 22, panelW - 20, panelH - 34);
		}
		super.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
