package dev.worldforge.client.gui;

import com.google.gson.JsonObject;
import dev.worldforge.block.BlockCatalog;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.client.ClientBlockState;
import dev.worldforge.studio.StudioUiState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * The BLOCK category of WorldForge Studio (v0.4.0).
 *
 * <p>Reuses the existing studio architecture instead of a second editor: the
 * same screen, the same widget helpers, the same protocol kinds
 * ({@code blocks_list} / {@code block_create} / {@code block_update} /
 * {@code block_delete} / {@code block_give}) and the same server-side
 * {@code WorldForgeBlockTools} pipeline the commands and the AI agent use.</p>
 *
 * <p>Draft protection is handled by {@link StudioUiState}: leaving the block
 * editor with unsaved edits warns first, exactly like the item editor.</p>
 */
public final class BlockStudioPanel {

	public interface Bridge {
		void requestList();

		boolean sendCreate(String json);

		boolean sendUpdate(String json);

		boolean sendDelete(String id);

		boolean sendGive(String id);

		void markDirty();

		void markSaved();
	}

	private final StudioUiState ui;
	private final Bridge bridge;

	private TextFieldWidget nameField, hardnessField, intervalField, outputField;
	private ButtonWidget categoryButton, appearanceButton, saveButton, giveButton,
			deleteButton, backButton, createButton, refreshButton;
	private String editingId = null;
	private String draftCategory = "decoration";
	private String draftAppearance = "minecraft:stone";
	private int listScroll = 0;

	public BlockStudioPanel(StudioUiState ui, Bridge bridge) {
		this.ui = ui;
		this.bridge = bridge;
	}

	public void init(int x, int y, int w, int h) {
		int rowY = y + 26;
		nameField = field(x, rowY, w, "方块名称");
		rowY += 22;
		hardnessField = field(x, rowY, (w - 6) / 2, "硬度 0-50");
		intervalField = field(x + (w - 6) / 2 + 6, rowY, (w - 6) / 2, "周期 tick (≥20)");
		rowY += 22;
		outputField = field(x, rowY, w, "产出物品 id（周期方块）");
		rowY += 24;
		categoryButton = ButtonWidget.builder(Text.literal("类型: 装饰"), b -> cycleCategory())
				.position(x, rowY).size((w - 6) / 2, 18).build();
		appearanceButton = ButtonWidget.builder(Text.literal("外观: stone"), b -> cycleAppearance())
				.position(x + (w - 6) / 2 + 6, rowY).size((w - 6) / 2, 18).build();
		rowY += 24;
		int bw = (w - 18) / 4;
		saveButton = ButtonWidget.builder(Text.literal("保存"), b -> save())
				.position(x, rowY).size(bw, 20).build();
		giveButton = ButtonWidget.builder(Text.literal("获取方块"), b -> give())
				.position(x + bw + 6, rowY).size(bw, 20).build();
		deleteButton = ButtonWidget.builder(Text.literal("删除"), b -> delete())
				.position(x + 2 * (bw + 6), rowY).size(bw, 20).build();
		backButton = ButtonWidget.builder(Text.literal("« 返回"), b -> leave())
				.position(x + 3 * (bw + 6), rowY).size(bw, 20).build();
		rowY += 24;
		createButton = ButtonWidget.builder(Text.literal("＋ 新建方块"), b -> startCreate())
				.position(x, rowY).size((w - 6) / 2, 18).build();
		refreshButton = ButtonWidget.builder(Text.literal("刷新列表"), b -> bridge.requestList())
				.position(x + (w - 6) / 2 + 6, rowY).size((w - 6) / 2, 18).build();
	}

	private TextFieldWidget field(int x, int y, int w, String placeholder) {
		TextFieldWidget f = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, x, y, w, 18,
				Text.literal(placeholder));
		f.setPlaceholder(Text.literal(placeholder));
		f.setChangedListener(s -> bridge.markDirty());
		return f;
	}

	// ---------------------------------------------------------------- state

	public List<ButtonWidget> buttons() {
		java.util.List<ButtonWidget> out = new java.util.ArrayList<>();
		for (ButtonWidget b : new ButtonWidget[]{categoryButton, appearanceButton, saveButton,
				giveButton, deleteButton, backButton, createButton, refreshButton}) {
			if (b != null) {
				out.add(b);
			}
		}
		return out;
	}

	public List<TextFieldWidget> fields() {
		java.util.List<TextFieldWidget> out = new java.util.ArrayList<>();
		for (TextFieldWidget f : new TextFieldWidget[]{nameField, hardnessField, intervalField, outputField}) {
			if (f != null) {
				out.add(f);
			}
		}
		return out;
	}

	public void applyVisibility(boolean visible, boolean editorOpen) {
		if (nameField == null) {
			return;
		}
		for (TextFieldWidget f : fields()) {
			f.visible = visible && editorOpen;
			f.active = visible && editorOpen;
		}
		boolean hasSelection = editingId != null;
		saveButton.visible = visible && editorOpen;
		giveButton.visible = visible && hasSelection;
		deleteButton.visible = visible && hasSelection;
		categoryButton.visible = visible && editorOpen;
		appearanceButton.visible = visible && editorOpen;
		backButton.visible = visible;
		createButton.visible = visible;
		refreshButton.visible = visible;
	}

	public boolean editorOpen() {
		return editingId != null || createMode;
	}

	private boolean createMode = false;

	private void startCreate() {
		createMode = true;
		editingId = null;
		draftCategory = "decoration";
		draftAppearance = "minecraft:stone";
		setFields("", "2", "200", "");
		refreshLabels();
	}

	private void setFields(String name, String hardness, String interval, String output) {
		if (nameField == null) {
			return;
		}
		nameField.setText(name);
		hardnessField.setText(hardness);
		intervalField.setText(interval);
		outputField.setText(output);
	}

	/** load an existing definition into the form */
	public void edit(BlockDefinition d) {
		createMode = false;
		editingId = d.id();
		draftCategory = d.category().name().toLowerCase(Locale.ROOT);
		draftAppearance = BlockCatalog.normalize(d.appearance());
		setFields(d.name(), String.valueOf(d.hardness()),
				String.valueOf(d.tickIntervalTicks()),
				d.outputItemId() == null ? "" : d.outputItemId());
		refreshLabels();
	}

	private void cycleCategory() {
		String[] all = {"decoration", "interactive", "periodic"};
		int i = 0;
		for (int k = 0; k < all.length; k++) {
			if (all[k].equals(draftCategory)) {
				i = k;
			}
		}
		draftCategory = all[(i + 1) % all.length];
		bridge.markDirty();
		refreshLabels();
	}

	private void cycleAppearance() {
		List<String> all = BlockCatalog.supportedAppearances();
		int i = all.indexOf(draftAppearance);
		draftAppearance = all.get((i + 1) % all.size());
		bridge.markDirty();
		refreshLabels();
	}

	private void refreshLabels() {
		if (categoryButton != null) {
			categoryButton.setMessage(Text.literal("类型: " + switch (draftCategory) {
				case "interactive" -> "交互";
				case "periodic" -> "周期";
				default -> "装饰";
			}));
		}
		if (appearanceButton != null) {
			String shortName = draftAppearance.contains(":")
					? draftAppearance.substring(draftAppearance.indexOf(':') + 1) : draftAppearance;
			appearanceButton.setMessage(Text.literal("外观: " + shortName));
		}
	}

	// ---------------------------------------------------------------- actions

	private void save() {
		if (nameField == null) {
			return;
		}
		String name = nameField.getText().trim();
		if (name.isEmpty()) {
			ui.markSettingsDirty(); // reuse the dirty channel; the screen shows status
			return;
		}
		double hardness = parse(hardnessField.getText(), 2.0);
		int interval = (int) parse(intervalField.getText(), 200);
		String output = outputField.getText().trim();
		String id = editingId != null ? editingId
				: "worldforge:block_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
		JsonObject body = new JsonObject();
		body.addProperty("version", BlockDefinition.CURRENT_VERSION);
		body.addProperty("id", id);
		body.addProperty("name", name);
		body.addProperty("description", "");
		body.addProperty("appearance", draftAppearance);
		body.addProperty("category", draftCategory.toUpperCase(Locale.ROOT));
		body.addProperty("hardness", hardness);
		body.addProperty("tick_interval_ticks", interval);
		if (!output.isEmpty()) {
			body.addProperty("output_item", output);
			body.addProperty("output_count", 1);
		}
		boolean ok = editingId != null ? bridge.sendUpdate(body.toString()) : bridge.sendCreate(body.toString());
		if (ok) {
			bridge.markSaved();
			if (editingId == null) {
				editingId = id;
				createMode = false;
			}
		}
	}

	private void give() {
		if (editingId != null) {
			bridge.sendGive(editingId);
		}
	}

	private void delete() {
		if (editingId != null) {
			bridge.sendDelete(editingId);
			editingId = null;
			createMode = false;
		}
	}

	private void leave() {
		createMode = false;
		editingId = null;
		ui.switchCategory(false);
	}

	private static double parse(String s, double def) {
		try {
			return Double.parseDouble(s.trim());
		} catch (Exception e) {
			return def;
		}
	}

	// ---------------------------------------------------------------- render

	public void render(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
		var tr = MinecraftClient.getInstance().textRenderer;
		ctx.drawTextWithShadow(tr, "§b§l方块编辑器", x, y + 4, 0xFFFFFF);
		List<BlockDefinition> defs = ClientBlockState.list();
		ctx.drawTextWithShadow(tr, "§7当前世界虚拟方块：" + defs.size()
				+ "（服务端权威 · 客户端镜像）", x, y + 14, 0x909090);

		int listY = y + 30;
		int rowH = 11;
		int maxRows = Math.max(3, (h - 150) / rowH);
		if (defs.isEmpty()) {
			ctx.drawTextWithShadow(tr, "§7（还没有方块，点「＋ 新建方块」）", x, listY, 0x808080);
		}
		int from = Math.max(0, Math.min(listScroll, Math.max(0, defs.size() - maxRows)));
		for (int i = 0; i < maxRows && from + i < defs.size(); i++) {
			BlockDefinition d = defs.get(from + i);
			int ry = listY + i * rowH;
			boolean hov = mouseX >= x && mouseX <= x + w && mouseY >= ry && mouseY <= ry + rowH;
			boolean sel = d.id().equals(editingId);
			if (sel) {
				ctx.fill(x - 2, ry - 1, x + w, ry + rowH - 1, 0x503060C0);
			} else if (hov) {
				ctx.fill(x - 2, ry - 1, x + w, ry + rowH - 1, 0x30FFFFFF);
			}
			ctx.drawTextWithShadow(tr, "§f" + d.name() + " §8" + d.shortId()
					+ " §7[" + d.category().name().toLowerCase(Locale.ROOT) + "]", x, ry, 0xFFFFFF);
		}
		if (defs.size() > maxRows) {
			ctx.drawTextWithShadow(tr, "§8滚轮查看更多 (" + (from + 1) + "-"
					+ Math.min(defs.size(), from + maxRows) + "/" + defs.size() + ")", x, listY + maxRows * rowH, 0x707070);
		}
		if (editingId != null) {
			ctx.drawTextWithShadow(tr, "§7编辑中：" + editingId, x, y + h - 26, 0xA0A0A0);
		}
	}

	/** row hit-test in the block list (returns the definition or null) */
	public BlockDefinition rowAt(double mouseX, double mouseY, int x, int y, int w, int h) {
		List<BlockDefinition> defs = ClientBlockState.list();
		int listY = y + 30;
		int rowH = 11;
		int maxRows = Math.max(3, (h - 150) / rowH);
		if (mouseX < x || mouseX > x + w || mouseY < listY) {
			return null;
		}
		int idx = listScroll + (int) ((mouseY - listY) / rowH);
		if (idx < 0 || idx >= defs.size() || idx >= listScroll + maxRows) {
			return null;
		}
		return defs.get(idx);
	}

	public boolean scroll(double amountY) {
		int size = ClientBlockState.list().size();
		int max = Math.max(0, size - 3);
		int next = Math.max(0, Math.min(max, listScroll + (amountY > 0 ? -1 : 1)));
		if (next != listScroll) {
			listScroll = next;
			return true;
		}
		return false;
	}

	// diagnostics for the automated client check
	public String debugEditingId() {
		return editingId;
	}

	public String debugCategory() {
		return draftCategory;
	}

	public String debugAppearance() {
		return draftAppearance;
	}

	public boolean debugCreateMode() {
		return createMode;
	}

	public void debugEditFirst() {
		List<BlockDefinition> defs = ClientBlockState.list();
		if (!defs.isEmpty()) {
			edit(defs.get(0));
		}
	}
}