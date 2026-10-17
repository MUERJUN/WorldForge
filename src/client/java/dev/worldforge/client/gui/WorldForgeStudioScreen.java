package dev.worldforge.client.gui;

import dev.worldforge.client.ClientStudioState;
import dev.worldforge.definition.AppearanceSlots;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.network.WorldForgeStudioProtocol;
import dev.worldforge.studio.StudioUiState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WorldForge Studio (v0.2.0): the in-game visual item editor.
 *
 * <p>View layer only. Every mutation is a C2S studio request; the server
 * re-checks OP permission, validates shape/values/whitelist and executes
 * through WorldForgeTools - the same pipeline the /wf commands use, so the
 * GUI cannot gain more authority than the command system and a future AI
 * agent shares exactly this surface. The list content comes from server
 * snapshots (definitions + tombstones), never hard-coded data.</p>
 *
 * <p>Drafts are local until 保存 (no per-keystroke writes); a ui.dirty() marker
 * warns before navigation; responses echo requestId so stale replies are
 * dropped; submits are locked while one request is in flight and a 5s
 * timeout releases the UI with a "refresh, don't blind-retry" hint.</p>
 */
public class WorldForgeStudioScreen extends Screen {

	public enum Mode { LIST, EDIT, CREATE, APPEAR_PICK, DELETE_CONFIRM, AI }

	/**
	 * v0.3.2: the AUTHORITATIVE UI state machine. Every navigation, draft
	 * guard, AI phase transition and widget-visibility decision goes through
	 * it, so the rules are unit-tested instead of living in widget callbacks.
	 * {@link #mode} below is only a render cache synchronised from it.
	 */
	private final StudioUiState ui = new StudioUiState();

	private Mode mode = Mode.LIST;
	private String selectedId = null; // edit target (server id), null in CREATE
	private boolean editorWasEditing = false; // remember EDIT vs CREATE for picker/confirm back-nav

	private TextFieldWidget idField, nameField, descField, damageField, durabilityField, cooldownField, param1, param2;
	private ButtonWidget appearanceButton, triggerButton, actionButton;
	private ButtonWidget saveButton, addButton, giveButton, deleteButton, backButton;

	private String draftTexture = AppearanceSlots.DEFAULT_TEXTURE;
	private String draftTrigger = "right_click";
	private String draftAction = "summon_lightning";
	private final List<BehaviorDefinition> draftBehaviors = new ArrayList<>();

	private int x0, y0, panelW, panelH;
	private int listScroll = 0;
	private String statusLine = "§7正在读取服务端数据…";

	private int nextReqId = 1;
	private int inFlightReqId = 0;
	private String inFlightKind = "";
	private long inFlightSince = 0L;

	private List<ItemDefinition> rows = new ArrayList<>();
	private AiStudioPanel aiPanel;

	// appearance picker hit zones, rebuilt each frame
	private final List<int[]> pickerZones = new ArrayList<>();

	public WorldForgeStudioScreen() {
		super(Text.literal("WorldForge Studio"));
	}

	@Override
	protected void init() {
		panelW = Math.min(500, width - 16);
		panelH = Math.min(246, height - 32);
		x0 = (width - panelW) / 2;
		y0 = (height - panelH) / 2;

		int lw = 160;
		int rx = x0 + lw + 16;
		int rw = panelW - lw - 32;
		int fy = y0 + 28;

		idField = field(rx, fy, rw, "worldforge:my_item");
		fy += 20;
		nameField = field(rx, fy, rw, "显示名称");
		fy += 20;
		descField = field(rx, fy, rw, "描述（可选）");
		fy += 20;
		int half = (rw - 6) / 2;
		damageField = field(rx, fy, half, "伤害");
		durabilityField = field(rx + half + 6, fy, half, "耐久(≥1)");
		fy += 20;
		appearanceButton = button(rx, fy, rw, 18, "外观: -", b -> openModal(Mode.APPEAR_PICK));
		fy += 20;
		triggerButton = button(rx, fy, half, 18, "触发: right_click", b -> cycleTrigger());
		actionButton = button(rx + half + 6, fy, half, 18, "动作: -", b -> cycleAction());
		fy += 20;
		param1 = field(rx, fy, half, "参数");
		param2 = field(rx + half + 6, fy, half - 60, "参数2");
		cooldownField = field(rx + rw - 54, fy, 54, "冷却");
		fy += 20;
		int bw = (rw - 24) / 4;
		saveButton = button(rx, fy, bw, 20, "保存", b -> submitSave());
		addButton = button(rx + bw + 8, fy, bw, 20, "＋加入行为", b -> appendDraftBehavior());
		giveButton = button(rx + 2 * (bw + 8), fy, bw, 20, "获取测试", b -> submitGive());
		deleteButton = button(rx + 3 * (bw + 8), fy, bw, 20, "删除", b -> openModal(Mode.DELETE_CONFIRM));
		fy += 22;
		backButton = button(rx, fy, rw, 16, "« 返回列表", b -> tryLeaveEditor());

		button(x0 + 8, y0 + panelH - 66, lw, 18, "§d✦ AI 创造面板", b -> enterAi());
		button(x0 + 8, y0 + panelH - 46, lw, 18, "＋ 创建物品", b -> enterCreate());
		button(x0 + 8, y0 + panelH - 26, lw / 2 - 2, 16, "刷新", b -> sendRequest("list", ""));
		button(x0 + 8 + lw / 2 + 2, y0 + panelH - 26, lw / 2 - 2, 16, "关闭 (ESC)", b -> guardedClose());

		aiPanel = new AiStudioPanel(ui, new AiStudioPanel.Bridge() {
			@Override
			public boolean sendChat(String text) {
				return sendRequest("agent_chat", jsonOf("message", text));
			}

			@Override
			public void sendCancel(String proposalId) {
				// best-effort only: the GUI already cancelled locally, so an
				// unreachable server must never block the player.
				sendRequestBestEffort("agent_cancel", jsonOf("proposalId", proposalId));
			}

			@Override
			public void sendConfirm(String proposalId) {
				sendRequest("agent_confirm", jsonOf("proposalId", proposalId));
			}

			@Override
			public void refreshList() {
				sendRequest("list", "");
			}
		});
		aiPanel.init(x0 + 176, y0 + 24, panelW - 192, panelH - 56);
		for (var b : aiPanel.buttons()) {
			addDrawableChild(b);
		}
		addDrawableChild(aiPanel.input()); // v0.3.1 fix: focusable text field

		refreshDraftWidgets();
		applyMode();
		sendRequest("list", "");
	}

	private boolean programmatic = false;

	private TextFieldWidget field(int x, int y, int w, String ph) {
		TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(ph));
		f.setPlaceholder(Text.literal(ph));
		f.setChangedListener(str -> {
			if (!programmatic) {
				ui.markDirty(); // v0.3.0 fix: our own setText() used to mark drafts ui.dirty()
			}
		});
		addDrawableChild(f);
		return f;
	}

	/** populate fields without tripping the ui.dirty() flag */
	private void fillFields(Runnable r) {
		programmatic = true;
		try {
			r.run();
		} finally {
			programmatic = false;
		}
	}

	private ButtonWidget button(int x, int y, int w, int h, String label, java.util.function.Consumer<ButtonWidget> press) {
		ButtonWidget b = ButtonWidget.builder(Text.literal(label), press::accept).position(x, y).size(w, h).build();
		addDrawableChild(b);
		return b;
	}

	// ---------- mode navigation (all decisions live in StudioUiState) ----------

	/** mirror the authoritative state machine into the render cache */
	private void syncMode() {
		mode = Mode.valueOf(ui.mode().name());
		editorWasEditing = ui.editorWasEditing();
	}

	private boolean dirty() {
		return ui.dirty();
	}

	/** open a modal from the editor through the state machine */
	private void openModal(Mode m) {
		syncMode();
		ui.openModal(StudioUiState.Mode.valueOf(m.name()));
		syncMode();
		applyMode();
	}

	private void closeModal() {
		ui.closeModal();
		syncMode();
		applyMode();
	}

	/**
	 * Navigate through the draft guard. Returns true when the navigation
	 * happened; when it returns false the guard has armed itself and the
	 * caller has already shown the warning.
	 */
	private boolean navigate(Mode target, String warnMessage) {
		syncMode();
		var nav = ui.requestNavigation(StudioUiState.Mode.valueOf(target.name()));
		if (nav == StudioUiState.Nav.NEEDS_CONFIRM) {
			setStatus(warnMessage);
			applyMode();
			return false;
		}
		syncMode();
		applyMode();
		return nav == StudioUiState.Nav.MOVED;
	}

	private void applyMode() {
		// v0.3.2: widget visibility is derived from the state machine so that
		// drawing and interaction can never disagree.
		if (ui.aiVisible()) {
			for (TextFieldWidget f : new TextFieldWidget[]{idField, nameField, descField, damageField,
					durabilityField, param1, param2, cooldownField}) {
				f.visible = false;
			}
			for (ButtonWidget b : new ButtonWidget[]{appearanceButton, triggerButton, actionButton,
					saveButton, addButton, giveButton, deleteButton, backButton}) {
				b.visible = false;
			}
			aiPanel.input().visible = ui.inputVisible();
			aiPanel.input().active = ui.inputVisible();
			var bs = aiPanel.buttons();
			bs.get(0).visible = ui.sendVisible();
			bs.get(0).active = ui.sendActive();
			bs.get(1).visible = ui.confirmVisible();
			bs.get(1).active = ui.confirmVisible();
			bs.get(2).visible = ui.cancelVisible();
			bs.get(2).active = ui.cancelVisible();
			return;
		}
		aiPanel.input().visible = ui.inputVisible();
		for (var b : aiPanel.buttons()) {
			b.visible = false;
		}
		boolean editor = ui.editorFieldsVisible();
		idField.visible = mode == Mode.CREATE; // id immutable once created (spec 5.1)
		idField.active = mode == Mode.CREATE;
		for (TextFieldWidget f : new TextFieldWidget[]{nameField, descField, damageField, durabilityField, param1, param2, cooldownField}) {
			f.visible = editor;
			f.active = editor;
		}
		param2.visible = editor && hasParam2();
		for (ButtonWidget b : new ButtonWidget[]{appearanceButton, triggerButton, actionButton, saveButton, addButton, giveButton, deleteButton, backButton}) {
			b.visible = editor;
		}
		giveButton.visible = mode == Mode.EDIT;
		deleteButton.visible = mode == Mode.EDIT;
		// double-submit guard: lock mutating buttons while a write is in flight
		boolean locked = inFlightReqId != 0 && (inFlightKind.equals("create") || inFlightKind.equals("update") || inFlightKind.equals("delete") || inFlightKind.equals("give"));
		saveButton.active = editor && !locked;
		addButton.active = editor && !locked;
		giveButton.active = mode == Mode.EDIT && !locked;
		deleteButton.active = mode == Mode.EDIT && !locked;
	}

	private void enterCreate() {
		selectedId = null;
		draftBehaviors.clear();
		draftTexture = AppearanceSlots.DEFAULT_TEXTURE;
		draftTrigger = "right_click";
		draftAction = "summon_lightning";
		fillFields(() -> {
			idField.setText("worldforge:");
			nameField.setText("新物品");
			descField.setText("");
			damageField.setText("4");
			durabilityField.setText("100");
			cooldownField.setText("100");
			param1.setText("");
			param2.setText("");
		});
		refreshDraftWidgets();
		ui.beginCreate();
		syncMode();
		applyMode();
		setStatus("§7填写后点「保存」创建；ID 创建后不可修改");
	}

	private void enterEdit(ItemDefinition d) {
		if (ui.dirty() && ui.editorVisible()) {
			setStatus("§e当前草稿未保存：先「保存」或点「返回列表」两次放弃");
			return;
		}
		selectedId = d.id();
		draftTexture = d.texture() == null ? AppearanceSlots.DEFAULT_TEXTURE : d.texture().resource();
		draftBehaviors.clear();
		draftBehaviors.addAll(d.behaviors());
		if (!draftBehaviors.isEmpty()) {
			BehaviorDefinition b0 = draftBehaviors.get(draftBehaviors.size() - 1);
			draftTrigger = b0.trigger();
			draftAction = b0.actions().isEmpty() ? draftAction : b0.actions().get(0);
		}
		fillFields(() -> {
			idField.setText(d.id());
			nameField.setText(d.name() == null ? "" : d.name());
			descField.setText(d.description() == null ? "" : d.description());
			damageField.setText(trim(d.damage()));
			durabilityField.setText(String.valueOf(d.maxDurability()));
			if (!draftBehaviors.isEmpty()) {
				BehaviorDefinition b0 = draftBehaviors.get(draftBehaviors.size() - 1);
				cooldownField.setText(String.valueOf(b0.cooldownTicks()));
				param1.setText(String.valueOf(b0.parameters().getOrDefault(paramKey1(), "")).replace("null", ""));
				param2.setText(String.valueOf(b0.parameters().getOrDefault(paramKey2(), "")).replace("null", ""));
			} else {
				cooldownField.setText("100");
				param1.setText("");
				param2.setText("");
			}
		});
		refreshDraftWidgets();
		ui.beginEdit();
		syncMode();
		applyMode();
	}

	private void setSelectedField(TextFieldWidget f) {
		if (client != null) {
			setInitialFocus(f);
		}
	}

	/**
	 * Close the editor. Always ends in a close: with unsaved edits the first
	 * press arms the guard and the second closes, so there is NO state in
	 * which the player cannot exit (the reported "cannot close" bug).
	 */
	private void guardedClose() {
		var outcome = ui.requestClose();
		if (outcome == StudioUiState.Esc.NEEDS_CONFIRM) {
			setStatus("§e存在未保存修改：再点「关闭」将放弃草稿并退出编辑器");
			return;
		}
		close();
	}

	private void enterAi() {
		if (!navigate(Mode.AI, "§e存在未保存修改：再点「AI 创造面板」将放弃草稿")) {
			return;
		}
		setSelectedField(aiPanel.input());
	}

	/**
	 * v0.3.1 P1 fix: the "press twice to discard" guard used to be inferred
	 * from the status line text (statusLine.startsWith("§e")). Any other
	 * message overwriting the status bar (e.g. a server reply or a hint)
	 * permanently disarmed the guard, so the player could never leave the
	 * editor - exactly the "cannot cancel / cannot close" report. The armed
	 * state is now an explicit flag, reset on every entry and every other
	 * action, and never derived from display text.
	 */
	/**
	 * v0.3.2: "back to list" goes through the same guard as every other
	 * navigation entry point. The armed state is an explicit flag inside the
	 * state machine - never inferred from status-bar text, which is what used
	 * to trap the player in the editor.
	 */
	private void tryLeaveEditor() {
		if (navigate(Mode.LIST, "§e存在未保存修改：再点「返回列表」将放弃草稿")) {
			sendRequest("list", "");
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// the state machine already resolved this ESC into CLOSE; modals and
		// the editor never reach here (they handle ESC themselves)
		return ui.mode() == StudioUiState.Mode.LIST;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (ui.aiVisible() && (keyCode == 257 || keyCode == 335) && aiPanel.onEnter()) {
			return true; // ENTER sends in the AI panel
		}
		if (keyCode == 256) { // GLFW_ESCAPE
			switch (ui.pressEsc()) {
				case CANCEL_PROPOSAL -> {
					aiPanel.add("§7(已取消提案，世界数据未发生任何变化)");
					setStatus("§7已取消 AI 提案；再按 ESC 返回列表");
					applyMode();
					return true;
				}
				case NEEDS_CONFIRM -> {
					setStatus("§e存在未保存修改：再按 ESC 将放弃草稿");
					return true;
				}
				case MOVED -> {
					syncMode();
					applyMode();
					return true;
				}
				case CLOSE -> {
					return super.keyPressed(keyCode, scanCode, modifiers);
				}
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// ---------- draft widgets ----------

	private void refreshDraftWidgets() {
		appearanceButton.setMessage(Text.literal("外观: " + shortTex(draftTexture)));
		triggerButton.setMessage(Text.literal("触发: " + draftTrigger));
		actionButton.setMessage(Text.literal("动作: " + draftAction));
		param1.setPlaceholder(Text.literal(param1Hint()));
		param2.setPlaceholder(Text.literal(param2Hint()));
		param2.visible = (mode == Mode.EDIT || mode == Mode.CREATE) && hasParam2();
	}

	private void cycleTrigger() {
		draftTrigger = draftTrigger.equals("right_click") ? "hit_entity" : "right_click";
		ui.markDirty();
		refreshDraftWidgets();
	}

	private static final List<String> ACTIONS = List.of(
			"summon_lightning", "apply_effect", "heal", "experience", "teleport", "explosion", "ignite");

	private void cycleAction() {
		int i = Math.max(0, ACTIONS.indexOf(draftAction));
		draftAction = ACTIONS.get((i + 1) % ACTIONS.size());
		ui.markDirty();
		refreshDraftWidgets();
	}

	private String paramKey1() {
		return switch (draftAction) {
			case "heal" -> "amount";
			case "experience" -> "amount";
			case "ignite" -> "seconds";
			case "teleport" -> "distance";
			case "explosion" -> "power";
			case "apply_effect" -> "effect";
			default -> "range";
		};
	}

	private String paramKey2() {
		return switch (draftAction) {
			case "apply_effect" -> "duration";
			case "explosion" -> "fire";
			default -> "";
		};
	}

	private boolean hasParam2() {
		return draftAction.equals("apply_effect") || draftAction.equals("explosion");
	}

	private String param1Hint() {
		return switch (draftAction) {
			case "summon_lightning" -> "距离 2-32";
			case "apply_effect" -> "效果ID 如 minecraft:speed";
			case "heal" -> "治疗量 ≤20";
			case "experience" -> "经验 ≤1000";
			case "teleport" -> "距离 ≤64";
			case "explosion" -> "威力 0.1-4";
			case "ignite" -> "燃烧秒数";
			default -> "参数";
		};
	}

	private String param2Hint() {
		return switch (draftAction) {
			case "apply_effect" -> "持续tick";
			case "explosion" -> "点火0/1";
			default -> "";
		};
	}

	/** Build one behavior rule from the draft fields; returns null and sets status on bad input. */
	private BehaviorDefinition buildDraftBehavior() {
		int cooldown = 100;
		String cd = cooldownField.getText().trim();
		if (!cd.isEmpty()) {
			if (!isInt(cd)) {
				setStatus("§c冷却必须是整数 tick");
				return null;
			}
			cooldown = Math.max(0, Math.min(72000, Integer.parseInt(cd)));
		}
		Map<String, Object> params = new LinkedHashMap<>();
		String p1 = param1.getText().trim();
		String p2 = param2.getText().trim();
		switch (draftAction) {
			case "apply_effect" -> {
				if (p1.isEmpty()) {
					setStatus("§c请填写效果ID（例如 minecraft:speed）");
					return null;
				}
				if (Identifier.tryParse(p1) == null) {
					setStatus("§c效果ID格式非法");
					return null;
				}
				params.put("effect", p1);
				if (!p2.isEmpty()) {
					if (!isInt(p2)) {
						setStatus("§c持续时间必须是整数 tick");
						return null;
					}
					params.put("duration", Integer.parseInt(p2));
				}
			}
			case "explosion" -> {
				if (!p1.isEmpty()) {
					if (!isDouble(p1)) {
						setStatus("§c威力必须是数字");
						return null;
					}
					params.put("power", Double.parseDouble(p1));
				}
				if (!p2.isEmpty()) {
					params.put("fire", "1".equals(p2) || "true".equalsIgnoreCase(p2));
				}
			}
			case "heal", "experience", "teleport", "ignite", "summon_lightning" -> {
				if (!p1.isEmpty()) {
					if (!isDouble(p1)) {
						setStatus("§c参数必须是数字");
						return null;
					}
					params.put(paramKey1(), Double.parseDouble(p1));
				}
			}
			default -> {
			}
		}
		return new BehaviorDefinition(draftTrigger, List.of(draftAction), params, cooldown);
	}

	private void appendDraftBehavior() {
		BehaviorDefinition b = buildDraftBehavior();
		if (b == null) {
			return;
		}
		draftBehaviors.removeIf(x -> x.trigger().equals(b.trigger()) && x.actions().equals(b.actions()));
		draftBehaviors.add(b);
		ui.markDirty();
		fillFields(() -> {
			param1.setText("");
			param2.setText("");
		});
		setStatus("§a行为已加入草稿（共 " + draftBehaviors.size() + " 条），点「保存」提交到服务端");
	}

	// ---------- submit ----------

	private void submitSave() {
		String id = (mode == Mode.CREATE ? idField.getText().trim() : selectedId);
		if (mode == Mode.CREATE) {
			if (id.isEmpty() || id.equals("worldforge:")) {
				setStatus("§cID 不能为空");
				return;
			}
			if (!id.matches("[a-z0-9_.-]+:[a-z0-9_.-/]+")) {
				setStatus("§cID 格式非法（小写 namespace:path）");
				return;
			}
		}
		if (id == null) {
			setStatus("§c内部错误：无编辑目标");
			return;
		}
		if (!damageField.getText().trim().isEmpty() && !isDouble(damageField.getText().trim())) {
			setStatus("§c伤害必须是数字");
			return;
		}
		if (!durabilityField.getText().trim().isEmpty() && !isInt(durabilityField.getText().trim())) {
			setStatus("§c耐久必须是整数");
			return;
		}
		// v0.3.0 fix: behaviors are ONLY added via the explicit "+行为" button.
		// The old auto-fold fired whenever the pre-filled cooldown ("100") was
		// non-empty, so every saved item silently gained summon_lightning.
		com.google.gson.JsonObject body = new com.google.gson.JsonObject();
		body.addProperty("version", ItemDefinition.CURRENT_VERSION);
		body.addProperty("id", id);
		body.addProperty("name", nameField.getText());
		body.addProperty("description", descField.getText());
		com.google.gson.JsonObject tex = new com.google.gson.JsonObject();
		tex.addProperty("source", TextureSource.SOURCE_VANILLA);
		tex.addProperty("resource", draftTexture);
		body.add("texture", tex);
		com.google.gson.JsonObject attrs = new com.google.gson.JsonObject();
		if (!damageField.getText().trim().isEmpty()) {
			attrs.addProperty("damage", Double.parseDouble(damageField.getText().trim()));
		}
		attrs.addProperty("max_durability",
				durabilityField.getText().trim().isEmpty() ? 100 : Integer.parseInt(durabilityField.getText().trim()));
		body.add("attributes", attrs);
		com.google.gson.JsonArray behArr = new com.google.gson.JsonArray();
		for (BehaviorDefinition b : draftBehaviors) {
			com.google.gson.JsonObject bo = new com.google.gson.JsonObject();
			bo.addProperty("trigger", b.trigger());
			com.google.gson.JsonArray a = new com.google.gson.JsonArray();
			b.actions().forEach(a::add);
			bo.add("actions", a);
			bo.addProperty("cooldown_ticks", b.cooldownTicks());
			if (!b.parameters().isEmpty()) {
				bo.add("parameters", com.google.gson.JsonParser.parseString(mapToJson(b.parameters())));
			}
			behArr.add(bo);
		}
		body.add("behaviors", behArr);
		sendRequest(mode == Mode.CREATE ? "create" : "update", body.toString());
	}

	private static String mapToJson(Map<String, Object> m) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		for (var e : m.entrySet()) {
			Object v = e.getValue();
			if (v instanceof Number n) {
				o.addProperty(e.getKey(), n);
			} else if (v instanceof Boolean b) {
				o.addProperty(e.getKey(), b);
			} else if (v != null) {
				o.addProperty(e.getKey(), String.valueOf(v));
			}
		}
		return o.toString();
	}

	private void submitGive() {
		if (selectedId == null) {
			return;
		}
		com.google.gson.JsonObject body = new com.google.gson.JsonObject();
		body.addProperty("id", selectedId);
		body.addProperty("count", 1);
		sendRequest("give", body.toString());
	}

	private void confirmDelete() {
		if (selectedId == null) {
			return;
		}
		com.google.gson.JsonObject body = new com.google.gson.JsonObject();
		body.addProperty("id", selectedId);
		sendRequest("delete", body.toString());
	}

	// ---------- network ----------

	/**
	 * Send one studio request.
	 *
	 * @return true only when the packet was actually handed to the transport,
	 *         so callers (the AI panel in particular) never enter a loading
	 *         state for a request that was never sent.
	 */
	private boolean sendRequest(String kind, String payload) {
		if (!ClientPlayNetworking.canSend(WorldForgeStudioProtocol.StudioRequest.ID)) {
			setStatus("§c当前服务端没有安装 WorldForge - 编辑不可用（只读）");
			return false;
		}
		if (inFlightReqId != 0) {
			setStatus("§e上一个请求尚未返回（5 秒内自动解除，可稍后点刷新）");
			return false;
		}
		int rid = nextReqId++;
		inFlightReqId = rid;
		inFlightKind = kind;
		inFlightSince = System.currentTimeMillis();
		try {
			ClientPlayNetworking.send(new WorldForgeStudioProtocol.StudioRequest(kind, rid, payload));
		} catch (Exception e) {
			inFlightReqId = 0;
			inFlightKind = "";
			setStatus("§c请求发送失败：" + e.getClass().getSimpleName());
			return false;
		}
		setStatus(switch (kind) {
			case "agent_chat" -> "§d已发送给 AI，等待模型响应…";
			case "agent_confirm" -> "§7确认提案中…";
			case "agent_cancel" -> "§7取消提案…";
			case "list" -> "§7正在读取服务端权威列表…";
			case "create" -> "§7创建中…";
			case "update" -> "§7保存中…";
			case "delete" -> "§7删除中…";
			case "give" -> "§7发放测试物品中…";
			default -> "§7处理中…";
		});
		applyMode();
		return true;
	}

	/**
	 * v0.3.2: best-effort notification that must never block the GUI. Used for
	 * cancelling an AI proposal, which is already resolved locally; a dead or
	 * busy connection simply means the server keeps the proposal until its TTL.
	 */
	private void sendRequestBestEffort(String kind, String payload) {
		if (!ClientPlayNetworking.canSend(WorldForgeStudioProtocol.StudioRequest.ID)) {
			return;
		}
		try {
			ClientPlayNetworking.send(new WorldForgeStudioProtocol.StudioRequest(kind, nextReqId++, payload));
		} catch (Exception ignored) {
			// local state is already consistent; nothing to recover
		}
	}

	/** called on the client thread from the S2C receiver */
	public void onResponse(WorldForgeStudioProtocol.StudioResponse r) {
		if ("agent".equals(r.phase()) && r.requestId() == 0) {
			// unsolicited AI outcome (async model completion)
			aiPanel.onAgentEvent(r);
			// v0.3.1: the phase changed (IDLE <-> THINKING <-> PROPOSAL), which
			// decides which panel widgets are visible/active. Without this the
			// 确认/取消 buttons never appear when a proposal arrives.
			applyMode();
			setStatus(r.ok() ? "§aAI 响应完成" : "§cAI 返回错误");
			return;
		}
		if (r.requestId() != inFlightReqId) {
			return; // stale reply (abandoned edit / already timed out): never clobber current state
		}
		String kind = inFlightKind;
		inFlightReqId = 0;
		inFlightKind = "";

		if (!r.ok()) {
			setStatus(switch (r.phase()) {
				case "permission" -> "§c权限不足：" + r.message();
				case "persistence-failed" -> "§c保存失败（存档未写入，可修正后重试）：" + r.message();
				case "rejected" -> "§c请求被拒绝：" + r.message();
				case "server-error" -> "§c服务端错误：" + r.message();
				default -> "§c失败[" + r.phase() + "]：" + r.message();
			});
			if (!kind.equals("list")) {
				ui.markDirty(); // keep the draft so the player can fix and retry
			}
			applyMode();
			return;
		}

		// any successful response (except give) carries the authoritative snapshot
		boolean snapshotOk = true;
		if (!r.snapshot().isEmpty()) {
			snapshotOk = ClientStudioState.applySnapshot(r.snapshot());
			rows = ClientStudioState.list();
		}

		String snapWarn = snapshotOk ? "" : " §c(快照解析失败，请点刷新)";
		switch (kind) {
			case "agent_chat" -> {
				aiPanel.onChatAccepted(r.ok(), r.message());
				applyMode();
			}
			case "agent_confirm" -> {
				if (!r.snapshot().isEmpty()) {
					ClientStudioState.applySnapshot(r.snapshot());
					rows = ClientStudioState.list();
				}
				aiPanel.onConfirmResult(r.ok(), r.message());
				syncMode();
				applyMode();
				if (r.ok() && r.data() != null && r.data().contains("itemId")) {
					try {
						var env = com.google.gson.JsonParser.parseString(r.data()).getAsJsonObject();
						String iid = env.get("itemId").getAsString();
						var def = ClientStudioState.get(iid);
						if (def != null) {
							enterEdit(def); // one click away from 获取测试
						}
					} catch (Exception ignored) {
					}
				}
			}
			case "agent_cancel" -> {
				// the GUI already cancelled locally; this is only the server ack
				applyMode();
			}
			case "list" -> setStatus((snapshotOk
					? "§a已同步服务端列表 · " + rows.size() + " 个物品"
							+ (ClientStudioState.tombstones().isEmpty() ? "" : " · §c" + ClientStudioState.tombstones().size() + " 个失效标记")
					: "§c列表快照无效 - 请点刷新重试") + snapWarn);
			case "create" -> {
				ui.markSaved();
				String newId = idField.getText().trim();
				if (ClientStudioState.get(newId) != null) {
					selectedId = newId;
					ui.beginEdit();
					syncMode();
					applyMode();
				}
				setStatus("§a创建成功并已保存到当前世界" + syncNote(r.phase()));
			}
			case "update" -> {
				ui.markSaved();
				setStatus("§a保存成功；背包中的存量物品已即时应用新属性" + syncNote(r.phase()));
			}
			case "delete" -> {
				ui.markSaved();
				selectedId = null;
				ui.requestNavigation(StudioUiState.Mode.LIST);
				syncMode();
				setStatus("§a定义已删除；存量物品进入失效标记（未销毁），重建同名 ID 可恢复" + syncNote(r.phase()));
			}
			case "give" -> setStatus("§a" + r.message() + " - 按 ESC 关闭编辑器即可使用");
			default -> setStatus("§a完成");
		}
		applyMode();
	}

	private static String syncNote(String phase) {
		return switch (phase) {
			case "sync-partial" -> " §e（部分玩家同步失败，稍后可点刷新重试，无需重新保存）";
			case "sync-failed" -> " §e（其他客户端同步失败，请用刷新重试同步，勿重复保存）";
			case "applied-failed" -> " §e（已存档，但背包重刷失败 - 重新登录将自动应用）";
			default -> "";
		};
	}

	@Override
	public void tick() {
		super.tick();
		if (aiPanel != null) {
			aiPanel.tick();
		}
		// v0.3.2: self-healing against a dead/unreachable server. If we are
		// waiting on the model but the studio channel is gone, release the AI
		// state so the player is never stuck in "思考中" (request state fix #1).
		if (ui.aiPhase() == StudioUiState.AiPhase.THINKING
				&& !ClientPlayNetworking.canSend(WorldForgeStudioProtocol.StudioRequest.ID)) {
			ui.onDisconnected();
			setStatus("§e与服务端的连接已断开，AI 请求已取消（编辑器仍可使用）");
			applyMode();
		}
		// 5s request guard is released without ever touching local AI state
		if (inFlightReqId != 0 && System.currentTimeMillis() - inFlightSince > 5000) {
			inFlightReqId = 0;
			inFlightKind = "";
			setStatus("§e请求超时未响应 - 点「刷新」核对状态；已提交的修改请勿重复提交");
			applyMode();
		}
	}

	private static String jsonOf(String key, String value) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty(key, value == null ? "" : value);
		return o.toString();
	}

	private void setStatus(String s) {
		statusLine = s;
	}

	private String clip(String s, int maxW) {
		if (textRenderer.getWidth(s) <= maxW) {
			return s;
		}
		int end = s.length();
		while (end > 1 && textRenderer.getWidth(s.substring(0, end) + "…") > maxW) {
			end--;
		}
		return s.substring(0, end) + "…";
	}

	private static String shortTex(String t) {
		int i = t.lastIndexOf('/');
		return i >= 0 && i < t.length() - 1 ? t.substring(i + 1) : t;
	}

	private static String trim(double d) {
		return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
	}

	private static boolean isInt(String s) {
		try {
			Integer.parseInt(s);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static boolean isDouble(String s) {
		try {
			double d = Double.parseDouble(s);
			return !Double.isNaN(d) && !Double.isInfinite(d);
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public boolean shouldPause() {
		return false; // the studio must never freeze the running world
	}
}
