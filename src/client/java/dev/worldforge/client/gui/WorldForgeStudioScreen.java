package dev.worldforge.client.gui;

import dev.worldforge.client.ClientStudioState;
import dev.worldforge.definition.AppearanceSlots;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.network.WorldForgeStudioProtocol;
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
 * <p>Drafts are local until 保存 (no per-keystroke writes); a dirty marker
 * warns before navigation; responses echo requestId so stale replies are
 * dropped; submits are locked while one request is in flight and a 5s
 * timeout releases the UI with a "refresh, don't blind-retry" hint.</p>
 */
public class WorldForgeStudioScreen extends Screen {

	public enum Mode { LIST, EDIT, CREATE, APPEAR_PICK, DELETE_CONFIRM, AI }

	private Mode mode = Mode.LIST;
	private String selectedId = null; // edit target (server id), null in CREATE
	private boolean dirty = false;
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
		appearanceButton = button(rx, fy, rw, 18, "外观: -", b -> setMode(Mode.APPEAR_PICK));
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
		deleteButton = button(rx + 3 * (bw + 8), fy, bw, 20, "删除", b -> setMode(Mode.DELETE_CONFIRM));
		fy += 22;
		backButton = button(rx, fy, rw, 16, "« 返回列表", b -> tryLeaveEditor());

		button(x0 + 8, y0 + panelH - 66, lw, 18, "§d✦ AI 创造面板", b -> enterAi());
		button(x0 + 8, y0 + panelH - 46, lw, 18, "＋ 创建物品", b -> enterCreate());
		button(x0 + 8, y0 + panelH - 26, lw / 2 - 2, 16, "刷新", b -> sendRequest("list", ""));
		button(x0 + 8 + lw / 2 + 2, y0 + panelH - 26, lw / 2 - 2, 16, "关闭 (ESC)", b -> guardedClose());

		aiPanel = new AiStudioPanel();
		aiPanel.init(x0 + 176, y0 + 24, panelW - 192, panelH - 56,
				text -> sendRequest("agent_chat", jsonOf("message", text)),
				() -> sendRequest("agent_confirm", jsonOf("proposalId", aiPanel.pendingProposalId())),
				() -> sendRequest("agent_cancel", jsonOf("proposalId", aiPanel.pendingProposalId())),
				() -> sendRequest("list", ""));
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
				dirty = true; // v0.3.0 fix: our own setText() used to mark drafts dirty
			}
		});
		addDrawableChild(f);
		return f;
	}

	/** populate fields without tripping the dirty flag */
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

	// ---------- mode navigation ----------

	private void setMode(Mode m) {
		mode = m;
		applyMode();
	}

	private void applyMode() {
		if (mode == Mode.AI) {
			for (TextFieldWidget f : new TextFieldWidget[]{idField, nameField, descField, damageField,
					durabilityField, param1, param2, cooldownField}) {
				f.visible = false;
			}
			for (ButtonWidget b : new ButtonWidget[]{appearanceButton, triggerButton, actionButton,
					saveButton, addButton, giveButton, deleteButton, backButton}) {
				b.visible = false;
			}
			aiPanel.applyVisibility(true);
			return;
		}
		aiPanel.applyVisibility(false);
		boolean editor = mode == Mode.EDIT || mode == Mode.CREATE;
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
		discardArmed = false;
		closeArmed = false;
		selectedId = null;
		editorWasEditing = false;
		dirty = false;
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
		setMode(Mode.CREATE);
		setStatus("§7填写后点「保存」创建；ID 创建后不可修改");
	}

	private void enterEdit(ItemDefinition d) {
		if (dirty && (mode == Mode.EDIT || mode == Mode.CREATE)) {
			setStatus("§e当前草稿未保存：先「保存」或点「返回列表」两次放弃");
			return;
		}
		selectedId = d.id();
		editorWasEditing = true;
		dirty = false;
		discardArmed = false;
		closeArmed = false;
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
		setMode(Mode.EDIT);
	}

	private void setSelectedField(TextFieldWidget f) {
		if (client != null) {
			setInitialFocus(f);
		}
	}

	private boolean closeArmed = false;

	/** v0.3.0 fix: closing with unsaved edits warns first (double-press to confirm). */
	private void guardedClose() {
		discardArmed = false;
		if ((mode == Mode.EDIT || mode == Mode.CREATE) && dirty) {
			if (closeArmed) {
				close();
				return;
			}
			closeArmed = true;
			setStatus("§e存在未保存修改：再点「关闭」将放弃草稿并退出编辑器");
			return;
		}
		close();
	}

	private void enterAi() {
		setMode(Mode.AI);
		setSelectedField(aiPanel.inputWidget()); // focus the chat input once, on entry
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
	private boolean discardArmed = false;

	private void tryLeaveEditor() {
		closeArmed = false;
		if (dirty) {
			if (discardArmed) {
				discardArmed = false;
				dirty = false;
				setMode(Mode.LIST);
				sendRequest("list", "");
				return;
			}
			discardArmed = true;
			setStatus("§e存在未保存修改：再点「返回」将放弃草稿");
			return;
		}
		discardArmed = false;
		setMode(Mode.LIST);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return mode == Mode.LIST; // overlays/editor handle ESC themselves
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (mode == Mode.AI && (keyCode == 257 || keyCode == 335) && aiPanel.trySendOnEnter()) {
			return true; // ENTER sends in the AI panel
		}
		if (keyCode == 256) { // GLFW_ESCAPE
			switch (mode) {
				case APPEAR_PICK, DELETE_CONFIRM -> {
					setMode(editorWasEditing ? Mode.EDIT : Mode.CREATE);
					return true;
				}
				case EDIT, CREATE -> {
					tryLeaveEditor();
					return true;
				}
				case AI -> {
					// v0.3.1: ESC is layered so it can never trap the player:
					// with a pending proposal -> cancel that proposal only
					// (server clears PENDING, world untouched);
					// otherwise -> back to LIST; from LIST -> closes the screen.
					if (aiPanel.hasPending()) {
						sendRequest("agent_cancel", jsonOf("proposalId", aiPanel.pendingProposalId()));
						return true;
					}
					setMode(Mode.LIST);
					return true;
				}
				default -> {
					return super.keyPressed(keyCode, scanCode, modifiers); // LIST: closes screen
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
		dirty = true;
		refreshDraftWidgets();
	}

	private static final List<String> ACTIONS = List.of(
			"summon_lightning", "apply_effect", "heal", "experience", "teleport", "explosion", "ignite");

	private void cycleAction() {
		int i = Math.max(0, ACTIONS.indexOf(draftAction));
		draftAction = ACTIONS.get((i + 1) % ACTIONS.size());
		dirty = true;
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
		dirty = true;
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

	private void sendRequest(String kind, String payload) {
		if (!ClientPlayNetworking.canSend(WorldForgeStudioProtocol.StudioRequest.ID)) {
			setStatus("§c当前服务端没有安装 WorldForge - 编辑不可用（只读）");
			return;
		}
		if (inFlightReqId != 0) {
			if (kind.startsWith("agent_")) {
				setStatus("§e上一个请求尚未返回（5 秒内自动解除，可稍后点刷新）");
			}
			return;
		}
		int rid = nextReqId++;
		inFlightReqId = rid;
		inFlightKind = kind;
		inFlightSince = System.currentTimeMillis();
		ClientPlayNetworking.send(new WorldForgeStudioProtocol.StudioRequest(kind, rid, payload));
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
				dirty = true; // keep the draft so the player can fix and retry
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
				aiPanel.onCancelResult();
				applyMode();
			}
			case "list" -> setStatus((snapshotOk
					? "§a已同步服务端列表 · " + rows.size() + " 个物品"
							+ (ClientStudioState.tombstones().isEmpty() ? "" : " · §c" + ClientStudioState.tombstones().size() + " 个失效标记")
					: "§c列表快照无效 - 请点刷新重试") + snapWarn);
			case "create" -> {
				dirty = false;
				String newId = idField.getText().trim();
				if (ClientStudioState.get(newId) != null) {
					selectedId = newId;
					editorWasEditing = true;
					setMode(Mode.EDIT);
				}
				setStatus("§a创建成功并已保存到当前世界" + syncNote(r.phase()));
			}
			case "update" -> {
				dirty = false;
				discardArmed = false;
				closeArmed = false;
				setStatus("§a保存成功；背包中的存量物品已即时应用新属性" + syncNote(r.phase()));
			}
			case "delete" -> {
				dirty = false;
				selectedId = null;
				setMode(Mode.LIST);
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
		if (inFlightReqId != 0 && System.currentTimeMillis() - inFlightSince > 5000) {
			inFlightReqId = 0;
			inFlightKind = "";
			setStatus("§e请求超时未响应 - 点「刷新」核对状态；已保存成功的内容请勿重复提交");
			applyMode();
		}
	}

	// ---------- rendering ----------

	/** v0.3.1 P0: background-blur gate - exactly ONE blur pass per frame. */
	private boolean blurDoneThisFrame = false;

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// Screen.render() calls renderBackground() again internally. Without
		// this gate the second applyBlur() re-blurs the WHOLE framebuffer -
		// including the WorldForge panel and text we already painted - which
		// is precisely the "custom content blurry, vanilla widgets sharp"
		// symptom. One blur per frame; everything we draw afterwards is crisp.
		if (blurDoneThisFrame) {
			return;
		}
		super.renderBackground(ctx, mouseX, mouseY, delta);
		blurDoneThisFrame = true;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		blurDoneThisFrame = false;
		// 1) vanilla background: blur the game world once, then darken
		renderBackground(ctx, mouseX, mouseY, delta);

		// 2) WorldForge panel + all custom content paint AFTER the blur
		ctx.fill(x0, y0, x0 + panelW, y0 + panelH, 0xC8101014);
		ctx.drawBorder(x0, y0, panelW, panelH, 0x90FFFFFF);
		ctx.drawTextWithShadow(textRenderer, Text.literal("§b§lWorldForge Studio"), x0 + 8, y0 + 8, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer,
				client.getServer() != null ? "§7当前世界（单人）" : "§7当前服务器（多人）",
				x0 + panelW - 120, y0 + 9, 0x808080);

		renderListColumn(ctx, mouseX, mouseY);

		if (mode == Mode.AI) {
			aiPanel.render(ctx, x0 + 176, y0 + 24, panelW - 192, panelH - 56, mouseX, mouseY);
		}

		if (dirty && (mode == Mode.EDIT || mode == Mode.CREATE)) {
			ctx.drawTextWithShadow(textRenderer, "§e● 未保存的修改", x0 + panelW - 100, y0 + 18, 0xFFFF55);
		}

		if (mode == Mode.LIST) {
			int rx = x0 + 176;
			ctx.drawTextWithShadow(textRenderer, "§7点击左侧物品进行编辑", rx, y0 + 32, 0xAAAAAA);
			ctx.drawTextWithShadow(textRenderer, "§7或「＋ 创建物品」新建", rx, y0 + 44, 0xAAAAAA);
			ctx.drawTextWithShadow(textRenderer, "§8所有修改经服务端校验后保存到当前世界存档", rx, y0 + 68, 0x808080);
			ctx.drawTextWithShadow(textRenderer, "§8退出重进后定义与物品自动恢复", rx, y0 + 80, 0x808080);
			if (!ClientStudioState.serverReady()) {
				ctx.drawTextWithShadow(textRenderer, "§e等待服务端 WorldForge 数据…（若服务器未装 Mod 则只读）", rx, y0 + 108, 0xFFAA00);
			}
			if (!ClientStudioState.tombstones().isEmpty()) {
				ctx.drawTextWithShadow(textRenderer, "§c失效定义：" + String.join(", ", ClientStudioState.tombstones()),
						x0 + 176, y0 + panelH - 60, 0xFF6060);
			}
		}

		ctx.fill(x0, y0 + panelH - 14, x0 + panelW, y0 + panelH, 0xFF0A0A12);
		ctx.drawTextWithShadow(textRenderer, clip(statusLine, panelW - 16), x0 + 6, y0 + panelH - 11, 0xFFFFFF);

		// 3) widget tree above the panel; the renderBackground() inside
		// super.render() is suppressed by the gate, so nothing re-blurs
		super.render(ctx, mouseX, mouseY, delta);

		// modal overlays draw above widgets
		if (mode == Mode.APPEAR_PICK) {
			renderPickerOverlay(ctx, mouseX, mouseY);
		} else if (mode == Mode.DELETE_CONFIRM) {
			renderDeleteConfirmOverlay(ctx);
		}
	}

	private void renderPickerOverlay(DrawContext ctx, int mouseX, int mouseY) {
		int ox = x0 + 160, oy = y0 + 16, ow = panelW - 168, oh = panelH - 40;
		ctx.fill(ox, oy, ox + ow, oy + oh, 0xF014141C);
		renderPickerContent(ctx, mouseX, mouseY);
	}

	private void renderDeleteConfirmOverlay(DrawContext ctx) {
		int ox = x0 + 90, oy = y0 + 60, ow = panelW - 180, oh = 130;
		ctx.fill(ox, oy, ox + ow, oy + oh, 0xF414141C);
		renderDeleteConfirmContent(ctx);
	}

	private void renderListColumn(DrawContext ctx, int mouseX, int mouseY) {
		int lx = x0 + 8, ly = y0 + 26, lw = 160, rowH = 22;
		int maxRows = Math.max(1, (panelH - 100) / rowH);
		ctx.fill(lx - 2, ly - 2, lx + lw + 2, ly + maxRows * rowH + 2, 0x60000000);
		if (rows.isEmpty()) {
			ctx.drawTextWithShadow(textRenderer, "§7（当前世界还没有物品定义）", lx + 4, ly + 6, 0x909090);
		}
		for (int i = 0; i < maxRows; i++) {
			int idx = listScroll + i;
			if (idx >= rows.size()) {
				break;
			}
			ItemDefinition d = rows.get(idx);
			int ry = ly + i * rowH;
			boolean hov = mouseX >= lx && mouseX <= lx + lw && mouseY >= ry && mouseY < ry + rowH;
			boolean sel = d.id().equals(selectedId);
			if (sel) {
				ctx.fill(lx, ry, lx + lw, ry + rowH, 0x603060C0);
			} else if (hov) {
				ctx.fill(lx, ry, lx + lw, ry + rowH, 0x30FFFFFF);
			}
			drawIcon(ctx, d.texture() == null ? AppearanceSlots.DEFAULT_TEXTURE : d.texture().resource(), lx + 3, ry + 3);
			ctx.drawTextWithShadow(textRenderer, clip(d.name() == null || d.name().isBlank() ? d.shortId() : d.name(), lw - 30),
					lx + 22, ry + 2, sel ? 0xFFFFA0 : 0xFFFFFF);
			ctx.drawTextWithShadow(textRenderer, "§8" + d.shortId(), lx + 22, ry + 12, 0x808080);
			if (hov) {
				ctx.drawTooltip(textRenderer,
						List.of(Text.literal(d.id()),
								Text.literal("§7伤害 " + trim(d.damage()) + " 耐久 " + d.maxDurability()
										+ (ClientStudioState.isMissing(d.id()) ? " §c(失效)" : ""))),
						mouseX, mouseY);
			}
		}
		if (rows.size() > maxRows) {
			ctx.drawTextWithShadow(textRenderer, "§7" + (listScroll + 1) + "-" + Math.min(rows.size(), listScroll + maxRows)
					+ " / " + rows.size() + "（滚轮滚动）", lx, ly + maxRows * rowH + 4, 0x808080);
		}
	}

	private void drawIcon(DrawContext ctx, String texture, int x, int y) {
		Item item = iconItemFor(texture);
		if (item != Items.AIR) {
			ctx.drawItem(new ItemStack(item), x, y);
		}
	}

	/** vanilla texture id -> the Item that owns it, for icon rendering in the picker/list */
	static Item iconItemFor(String texture) {
		if (texture == null) {
			return Items.AIR;
		}
		String path = texture.substring(texture.lastIndexOf('/') + 1);
		Identifier id = Identifier.tryParse("minecraft", path);
		return id == null ? Items.AIR : Registries.ITEM.get(id);
	}

	// ---------- appearance picker ----------

	private void renderPickerContent(DrawContext ctx, int mouseX, int mouseY) {
		int ox = x0 + 160, oy = y0 + 16, ow = panelW - 168, oh = panelH - 40;
		ctx.drawBorder(ox, oy, ow, oh, 0xFF6A6A9E);
		ctx.drawTextWithShadow(textRenderer, "§b选择外观（原版贴图槽位目录）", ox + 8, oy + 6, 0xFFFFFF);
		ctx.drawTextWithShadow(textRenderer, "§7当前: " + draftTexture, ox + 8, oy + 17, 0x909090);

		var slots = AppearanceSlots.slots();
		int cell = 30, cols = Math.max(1, (ow - 16) / (cell + 4));
		pickerZones.clear();
		for (int i = 0; i < slots.size(); i++) {
			int cx = ox + 8 + (i % cols) * (cell + 4);
			int cy = oy + 30 + (i / cols) * (cell + 4);
			boolean hov = mouseX >= cx && mouseX <= cx + cell && mouseY >= cy && mouseY <= cy + cell;
			ctx.fill(cx, cy, cx + cell, cy + cell, slots.get(i).texture().equals(draftTexture) ? 0x803060C0 : hov ? 0x50FFFFFF : 0x40000000);
			ctx.drawBorder(cx, cy, cell, cell, 0xFF555555);
			drawIcon(ctx, slots.get(i).texture(), cx + 7, cy + 7);
			pickerZones.add(new int[]{cx, cy, cell, i});
		}
		ctx.drawTextWithShadow(textRenderer, "§7点击选择 · ESC 取消（仅目录内 15 个预置槽位可用）", ox + 8, oy + oh - 14, 0x808080);
	}

	// ---------- delete confirm ----------

	private void renderDeleteConfirmContent(DrawContext ctx) {
		int ox = x0 + 90, oy = y0 + 60, ow = panelW - 180, oh = 130;
		ctx.drawBorder(ox, oy, ow, oh, 0xFF8A3A3A);
		ctx.drawCenteredTextWithShadow(textRenderer, "§c确认删除该物品定义？", ox + ow / 2, oy + 14, 0xFFFFFF);
		ctx.drawCenteredTextWithShadow(textRenderer, "§f" + clip(selectedId == null ? "" : selectedId, ow - 20), ox + ow / 2, oy + 28, 0xCCCCCC);
		ctx.drawTextWithShadow(textRenderer, "§7· 删除定义 ≠ 删除玩家背包中的物品实例", ox + 14, oy + 50, 0xA0A0A0);
		ctx.drawTextWithShadow(textRenderer, "§7· 存量物品会进入可识别的失效状态（不崩溃、不销毁）", ox + 14, oy + 62, 0xA0A0A0);
		ctx.drawTextWithShadow(textRenderer, "§7· 以相同 ID 重新创建定义后自动恢复", ox + 14, oy + 74, 0xA0A0A0);
		// manual hit-zone buttons (kept out of the widget tree so ESC zones stay clean)
		int byy = oy + oh - 30;
		ctx.fill(ox + 14, byy, ox + 14 + 90, byy + 20, 0xFF7A2020);
		ctx.drawBorder(ox + 14, byy, 90, 20, 0xFFB06060);
		ctx.drawCenteredTextWithShadow(textRenderer, "§c确认删除", ox + 59, byy + 6, 0xFFFFFF);
		ctx.fill(ox + ow - 104, byy, ox + ow - 14, byy + 20, 0xFF305030);
		ctx.drawBorder(ox + ow - 104, byy, 90, 20, 0xFF60A060);
		ctx.drawCenteredTextWithShadow(textRenderer, "取消", ox + ow - 59, byy + 6, 0xFFFFFF);
		deleteYesZone = new int[]{ox + 14, byy, ox + 104, byy + 20};
		deleteNoZone = new int[]{ox + ow - 104, byy, ox + ow - 14, byy + 20};
	}

	private int[] deleteYesZone, deleteNoZone;

	// ---------- input ----------

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (mode == Mode.APPEAR_PICK) {
			for (int[] z : pickerZones) {
				if (mouseX >= z[0] && mouseX <= z[0] + z[2] && mouseY >= z[1] && mouseY <= z[1] + z[2]) {
					draftTexture = AppearanceSlots.slots().get(z[3]).texture();
					dirty = true;
					refreshDraftWidgets();
					setMode(editorWasEditing ? Mode.EDIT : Mode.CREATE);
					setStatus("§a外观已选择 " + shortTex(draftTexture) + "，点「保存」提交到服务端");
					return true;
				}
			}
			return true; // swallow clicks behind the modal
		}
		if (mode == Mode.DELETE_CONFIRM) {
			if (deleteYesZone != null && inZone(deleteYesZone, mouseX, mouseY)) {
				confirmDelete();
				return true;
			}
			if (deleteNoZone != null && inZone(deleteNoZone, mouseX, mouseY)) {
				setMode(Mode.EDIT);
				return true;
			}
			return true; // swallow
		}
		// list row click
		int lx = x0 + 8, ly = y0 + 26, lw = 160, rowH = 22;
		int maxRows = Math.max(1, (panelH - 100) / rowH);
		if (mouseX >= lx && mouseX <= lx + lw && mouseY >= ly && mouseY < ly + maxRows * rowH) {
			int idx = listScroll + (int) ((mouseY - ly) / rowH);
			if (idx >= 0 && idx < rows.size()) {
				enterEdit(rows.get(idx));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private static boolean inZone(int[] z, double mx, double my) {
		return mx >= z[0] && mx <= z[2] && my >= z[1] && my <= z[3];
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		int lx = x0 + 8, ly = y0 + 26, lw = 160;
		int maxRows = Math.max(1, (panelH - 100) / 22);
		if (!inModal() && mouseX >= lx && mouseX <= lx + lw && mouseY >= ly && mouseY <= ly + maxRows * 22) {
			int ns = Math.max(0, Math.min(Math.max(0, rows.size() - maxRows), listScroll - (int) Math.signum(amountY)));
			if (ns != listScroll) {
				listScroll = ns;
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
	}

	private boolean inModal() {
		return mode == Mode.APPEAR_PICK || mode == Mode.DELETE_CONFIRM;
	}

	// ---------- helpers ----------

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
