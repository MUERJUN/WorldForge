package dev.worldforge.client.gui;

import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.AgentConfigStore;
import dev.worldforge.ai.AgentConnectionTest;
import dev.worldforge.ai.PasswordEdit;
import dev.worldforge.ai.SecretInput;
import dev.worldforge.studio.StudioUiState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.List;

/**
 * In-game AI settings (v0.4.0 P0): configure the agent without editing JSON by
 * hand, verify the endpoint for real, and apply changes without restarting.
 *
 * <p><b>Where the key may go (spec 六 / P0 安全要求).</b> In single player the
 * client and the integrated server share this JVM and this machine, so the
 * panel reads and writes the local config file DIRECTLY - the API key never
 * travels through a network packet. On a dedicated server
 * ({@code client.getServer() == null}) the panel is strictly read-only: it
 * neither displays nor accepts the server's key, and there is no protocol kind
 * that could read or overwrite it.</p>
 *
 * <p>The panel never re-displays a stored key (it reports "已配置" only), and
 * every message is passed through {@link AgentConnectionTest#scrub}.</p>
 */
public final class AiSettingsPanel {

	/** what the panel reports back to the screen */
	public interface Host {
		/** the local config file (single player) or null when not editable locally */
		Path localConfigFile();

		/** ask the integrated server to reload its provider from the file (same JVM, no packets) */
		void applyToServer();
	}

	private final StudioUiState ui;
	private final Host host;
	private final AgentConfigStore store;

	private TextFieldWidget baseUrlField, modelField, keyField;
	private ButtonWidget providerButton, enabledButton, testButton, saveButton, clearKeyButton, backButton;

	private String provider = AgentConfig.Provider.DEEPSEEK.name();
	private String baseUrl = "";
	private String model = "";
	private boolean enabled = true;
	private String keyStatus = "未配置";
	/** the real editor: owns value, caret and selection; the widget only shows a mask */
	private PasswordEdit keyEdit = new PasswordEdit();
	private boolean rewritingKeyField = false;
	private String statusLine = "§7修改后点「保存」即可生效，无需重启游戏";
	private String statusColor = "§7";
	private TextFieldWidget focusedOnEntry;

	/** masked, never a fragment of the real key */
	public static final String KEY_PLACEHOLDER = "输入新的 API Key（留空表示不修改）";

	public AiSettingsPanel(StudioUiState ui, Host host) {
		this.ui = ui;
		this.host = host;
		this.store = new AgentConfigStore(host.localConfigFile() == null
				? Path.of("worldforge-agent.json") : host.localConfigFile());
	}

	public boolean editable() {
		return host.localConfigFile() != null && MinecraftClient.getInstance().getServer() != null;
	}

	public void init(int x, int y, int w, int h, Runnable onDirty, Runnable onBack) {
		int rowY = y + 26;
		providerButton = ButtonWidget.builder(Text.literal("服务商: -"), b -> {
			provider = nextProvider(provider);
			applyPreset();
			onDirty.run();
		}).position(x, rowY).size(w, 18).build();
		rowY += 22;

		baseUrlField = field(x, rowY, w, "https://api.deepseek.com/v1", onDirty);
		rowY += 22;
		modelField = field(x, rowY, w, "deepseek-chat", onDirty);
		rowY += 22;

		keyField = field(x, rowY, w - 76, KEY_PLACEHOLDER, onDirty);
		keyField.setChangedListener(text -> {
			if (rewritingKeyField) {
				return;
			}
			// Reconcile a raw widget edit with the editor. Because the widget can
			// only ever show asterisks, we treat any edit as a replacement of the
			// whole value when the new text carries no mask character; otherwise we
			// keep the editor's own state and re-render the mask. The precise
			// caret/selection behaviour (Backspace, Delete, Home, End, Ctrl+A) is
			// handled by the key handlers below, which call PasswordEdit directly.
			keyEdit = reconcile(text);
			renderKeyField();
			onDirty.run();
		});
		rowY += 26;

		enabledButton = ButtonWidget.builder(Text.literal("AI: 已启用"), b -> {
			enabled = !enabled;
			onDirty.run();
			refreshLabels();
		}).position(x, rowY).size(96, 18).build();
		testButton = ButtonWidget.builder(Text.literal("测试连接"), b -> testConnection())
				.position(x + 100, rowY).size(84, 18).build();
		saveButton = ButtonWidget.builder(Text.literal("§a保存并应用"), b -> save())
				.position(x + 188, rowY).size(96, 18).build();
		rowY += 22;
		backButton = ButtonWidget.builder(Text.literal("« 返回"), b -> onBack.run())
				.position(x, rowY).size(72, 18).build();

		load();
	}

	private TextFieldWidget field(int x, int y, int w, String placeholder, Runnable onDirty) {
		TextFieldWidget f = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, x, y, w, 18,
				Text.literal(placeholder));
		f.setPlaceholder(Text.literal(placeholder));
		f.setChangedListener(s -> onDirty.run());
		return f;
	}

	/** the API key field is never pre-filled, so a stored key is never echoed */
	public String keyInput() {
		return keyEdit.value().trim();
	}

	/** the masked key widget, for the automated GUI check */
	public TextFieldWidget debugKeyField() {
		return keyField;
	}

	/**
	 * Precise editing for the masked key field.
	 *
	 * <p>The widget cannot express caret position or selection over asterisks, so
	 * the real operations are applied to {@link PasswordEdit} here and the widget
	 * is re-rendered. Returns true when the key was consumed.</p>
	 */
	public boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
		boolean ctrl = (modifiers & 2) != 0; // GLFW MOD_CONTROL
		boolean shift = (modifiers & 1) != 0;
		switch (keyCode) {
			case 259 -> { // BACKSPACE
				keyEdit.backspace();
			}
			case 261 -> { // DELETE
				keyEdit.delete();
			}
			case 268 -> { // HOME
				keyEdit.home();
			}
			case 269 -> { // END
				keyEdit.end();
			}
			case 263 -> { // LEFT
				keyEdit.moveLeft(shift);
			}
			case 262 -> { // RIGHT
				keyEdit.moveRight(shift);
			}
			case 65 -> { // A
				if (!ctrl) {
					return false;
				}
				keyEdit.selectAll();
			}
			default -> {
				return false;
			}
		}
		renderKeyField();
		return true;
	}

	/** true when the key field currently has focus */
	public boolean keyFieldFocused() {
		return keyField != null && keyField.isFocused();
	}

	/** insert text at the caret (typing or pasting) */
	public void handleText(String text) {
		keyEdit.insert(text);
		renderKeyField();
	}

	/** diagnostics: the mask rendered by the widget, and the real length only */
	public String debugKeyDisplay() {
		return keyField == null ? "" : keyField.getText();
	}

	public int debugKeyRealLength() {
		return keyEdit.length();
	}

	// ---------------------------------------------------------------- state

	public void load() {
		if (host.localConfigFile() == null) {
			baseUrl = "";
			model = "";
			keyStatus = "服务器管理";
			status("§e专用服务器：AI 由服务器管理员配置，本机不读取也不修改密钥");
			refreshLabels();
			return;
		}
		AgentConfig c = store.loadOrCreateTemplate();
		baseUrl = c.baseUrl();
		model = c.model();
		enabled = c.enabled();
		provider = c.provider().name();
		keyStatus = AgentConfig.keyIsPlaceholder(c.apiKey()) ? "未配置" : "已配置";
		if (baseUrlField != null) {
			baseUrlField.setText(baseUrl);
			modelField.setText(model);
			clearKeyState();
		}
		refreshLabels();
	}

	private void applyPreset() {
		AgentConfig.Provider p = AgentConfig.Provider.valueOf(provider);
		if (p != AgentConfig.Provider.CUSTOM) {
			baseUrl = p.baseUrl();
			model = p.defaultModel();
			if (baseUrlField != null) {
				baseUrlField.setText(baseUrl);
				modelField.setText(model);
			}
		}
		refreshLabels();
	}

	private static String nextProvider(String current) {
		AgentConfig.Provider[] all = AgentConfig.Provider.values();
		for (int i = 0; i < all.length; i++) {
			if (all[i].name().equals(current)) {
				return all[(i + 1) % all.length].name();
			}
		}
		return all[0].name();
	}

	private void refreshLabels() {
		if (provider == null) {
			provider = AgentConfig.Provider.CUSTOM.name();
		}
		if (providerButton != null) {
			providerButton.setMessage(Text.literal("服务商: " + AgentConfig.Provider.valueOf(provider).display()));
		}
		if (enabledButton != null) {
			enabledButton.setMessage(Text.literal(enabled ? "§aAI: 已启用" : "§cAI: 已禁用"));
		}
		if (keyField != null) {
			keyField.setPlaceholder(Text.literal("密钥: " + keyStatus + " · " + KEY_PLACEHOLDER));
		}
	}

	private AgentConfig current() {
		String url = AgentConnectionTest.defaultToHttps(read(baseUrlField, baseUrl));
		String m = read(modelField, model);
		String key = keyInput();
		String existing = store.exists() ? store.load().apiKey() : "";
		return new AgentConfig(enabled, url, m, key.isEmpty() ? existing : key, 30, 512, 1, 10, 12, 300);
	}

	private static String read(TextFieldWidget f, String fallback) {
		return f == null ? fallback : f.getText().trim();
	}

	/**
	 * Map a raw widget edit onto the editor. The widget text is either a mask of
	 * the current value (a no-op) or new raw characters (a full replacement, as
	 * happens with select-all + paste).
	 */
	private PasswordEdit reconcile(String widgetText) {
		String text = widgetText == null ? "" : widgetText;
		if (SecretInput.isMasked(text) && text.length() == keyEdit.length()) {
			return keyEdit; // the widget already shows our mask
		}
		if (SecretInput.isMasked(text) && text.length() != keyEdit.length()) {
			// a deletion performed through the widget: remove from the end (the
			// only position a mask can express)
			while (keyEdit.length() > text.length()) {
				keyEdit.end().backspace();
			}
			return keyEdit;
		}
		return keyEdit.setValue(text); // raw replacement
	}

	/** rewrite the widget with the mask and restore the caret */
	private void renderKeyField() {
		if (keyField == null) {
			return;
		}
		rewritingKeyField = true;
		try {
			keyField.setText(keyEdit.masked());
			keyField.setCursor(Math.min(keyEdit.caret(), keyField.getText().length()), false);
		} finally {
			rewritingKeyField = false;
		}
	}

	/** wipe the masked widget and the secret it mirrors */
	private void clearKeyState() {
		keyEdit = new PasswordEdit();
		if (keyField != null) {
			rewritingKeyField = true;
			try {
				keyField.setText("");
			} finally {
				rewritingKeyField = false;
			}
		}
	}

	private void refuse() {
		status("§c" + (editable() ? "配置无效" : "专用服务器：本机无权修改服务器 AI 配置"));
	}

	// ---------------------------------------------------------------- actions

	private void testConnection() {
		AgentConfig cfg = current();
		String urlError = AgentConnectionTest.validateBaseUrl(cfg.baseUrl());
		if (!urlError.isEmpty()) {
			status("§cBase URL 无效：" + urlError);
			return;
		}
		if (cfg.model().isBlank()) {
			status("§c模型名称不能为空");
			return;
		}
		status("§7正在测试连接（异步）…");
		// async: never blocks the render thread
		AgentConnectionTest.test(cfg, keyInput().isEmpty() ? null : keyInput())
				// MinecraftClient is an Executor: the result is applied on the
				// client thread, so widget state is never touched off-thread.
				.thenAcceptAsync(r -> {
					switch (r.verdict()) {
						case OK -> status("§a测试成功：" + r.message());
						case AUTH_ERROR -> status("§c鉴权错误（HTTP " + r.httpStatus() + "）：" + r.message());
						case MODEL_NOT_FOUND -> status("§c模型或地址错误（HTTP " + r.httpStatus() + "）：" + r.message());
						case RATE_LIMITED -> status("§e被限流（HTTP " + r.httpStatus() + "）：" + r.message());
						case SERVER_ERROR -> status("§c服务端/响应异常（HTTP " + r.httpStatus() + "）：" + r.message());
						case TIMEOUT, NETWORK_ERROR -> status("§c网络异常：" + r.message());
						case DISABLED -> status("§eAI 当前为禁用状态，请先启用再测试");
						case NO_KEY -> status("§e未配置 API Key");
						case BAD_URL -> status("§c" + r.message());
					}
				}, MinecraftClient.getInstance());
	}

	private void save() {
		if (!editable()) {
			refuse();
			return;
		}
		AgentConfig cfg = current();
		String urlError = AgentConnectionTest.validateBaseUrl(cfg.baseUrl());
		if (!urlError.isEmpty()) {
			status("§c保存被拒绝：" + urlError);
			return;
		}
		if (cfg.model().isBlank()) {
			status("§c保存被拒绝：模型名称不能为空");
			return;
		}
		if (!store.save(cfg)) {
			status("§c写入配置失败（未改动原文件）；详情见日志");
			return;
		}
		keyStatus = AgentConfig.keyIsPlaceholder(cfg.apiKey()) ? "未配置" : "已配置";
		clearKeyState();
		ui.markSettingsSaved();
		// hot apply: the integrated server reloads in this JVM, no packets, no restart
		host.applyToServer();
		status("§a已保存并立即生效（无需重启）：" + cfg.provider().display() + " / " + cfg.model());
		refreshLabels();
	}

	private void status(String line) {
		statusLine = line;
		statusColor = line.startsWith("§") ? line.substring(0, 2) : "§7";
	}

	// ---------------------------------------------------------------- widgets / render

	public List<ButtonWidget> buttons() {
		// List.of() rejects nulls; the panel may be queried before init() (the
		// screen applies visibility every frame), so build the list defensively.
		java.util.List<ButtonWidget> out = new java.util.ArrayList<>(6);
		for (ButtonWidget b : new ButtonWidget[]{providerButton, enabledButton, testButton,
				saveButton, clearKeyButton, backButton}) {
			if (b != null) {
				out.add(b);
			}
		}
		return out;
	}

	public List<TextFieldWidget> fields() {
		java.util.List<TextFieldWidget> out = new java.util.ArrayList<>(3);
		for (TextFieldWidget f : new TextFieldWidget[]{baseUrlField, modelField, keyField}) {
			if (f != null) {
				out.add(f);
			}
		}
		return out;
	}

	public void applyVisibility(boolean visible) {
		if (providerButton == null) {
			return; // not initialised yet (first frames before init())
		}
		boolean canEdit = visible && editable();
		for (var b : buttons()) {
			b.visible = visible;
			b.active = visible && (canEdit || b == backButton);
		}
		for (var f : fields()) {
			f.visible = visible;
			f.active = canEdit;
		}
		if (clearKeyButton != null) {
			clearKeyButton.active = canEdit;
		}
	}

	public void render(DrawContext ctx, int x, int y, int w, int h) {
		var tr = MinecraftClient.getInstance().textRenderer;
		ctx.drawTextWithShadow(tr, "§b§lAI 设置", x, y + 4, 0xFFFFFF);
		ctx.drawTextWithShadow(tr, editable()
						? "§7密钥仅保存在本机配置文件，不进入世界存档、不发送给其他玩家、日志已脱敏"
						: "§7专用服务器模式：只读。请在服务器端用安全途径配置",
				x, y + 14, 0x909090);
		ctx.drawTextWithShadow(tr, "§7密钥状态：" + keyStatus + " · 输入框已掩码显示（不回显任何字符）",
				x, y + h - 40, 0xA0A0A0);
		ctx.drawTextWithShadow(tr, clip(statusLine, w), x, y + h - 26, 0xFFFFFF);
	}

	private static String clip(String s, int maxW) {
		var tr = MinecraftClient.getInstance().textRenderer;
		if (tr.getWidth(s) <= maxW) {
			return s;
		}
		int end = s.length();
		while (end > 1 && tr.getWidth(s.substring(0, end) + "…") > maxW) {
			end--;
		}
		return s.substring(0, end) + "…";
	}

	public TextFieldWidget firstField() {
		return baseUrlField;
	}

	/** diagnostics for the automated GUI check */
	public String debugProvider() {
		return provider;
	}

	public String debugKeyStatus() {
		return keyStatus;
	}

	public String debugStatusLine() {
		return statusLine;
	}

	public boolean debugEditable() {
		return editable();
	}
}