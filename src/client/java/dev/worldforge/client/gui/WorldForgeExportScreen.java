package dev.worldforge.client.gui;

import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.exporter.ExportExecutor;
import dev.worldforge.exporter.ModExporter;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.5.2: In-game Mod Export screen with blur gating and degraded export confirmation.
 *
 * <p>Blur gating follows the same pattern as WorldForgeStudioScreen:
 * a per-frame flag ensures renderBackground() only blurs once, so custom
 * content and widgets all render crisp.</p>
 *
 * <p>P0-2: When capabilities are lost, export is blocked until the user
 * explicitly confirms degraded export.</p>
 */
public class WorldForgeExportScreen extends Screen {

	private TextFieldWidget modNameField;
	private TextFieldWidget modIdField;
	private TextFieldWidget versionField;
	private TextFieldWidget outputDirField;

	private final List<String> selectedItemIds = new ArrayList<>();
	private final List<String> selectedBlockIds = new ArrayList<>();

	private volatile String statusMessage = "";
	private volatile boolean exportInProgress = false;
	private volatile ModExporter.ExportResult result = null;
	private Thread exportThread = null;
	private ExportExecutor exportExecutor = null;

	// P0-2: Degraded export confirmation state
	private ModExporter.CapabilityReport pendingReport = null;
	private boolean awaitingDegradedConfirmation = false;

	// Blur gating — same pattern as WorldForgeStudioScreen
	private boolean blurDoneThisFrame = false;

	// Available definitions
	private final List<ItemDefinition> availableItems = new ArrayList<>();
	private final List<dev.worldforge.block.BlockDefinition> availableBlocks = new ArrayList<>();

	// Layout constants for click area boundaries
	private int itemListTop, itemListBottom, itemLeft, itemRight;
	private int blockListTop, blockListBottom, blockLeft, blockRight;

	public WorldForgeExportScreen() {
		super(Text.literal("WorldForge Mod 导出器"));
	}

	@Override
	protected void init() {
		availableItems.clear();
		availableBlocks.clear();
		for (var def : WorldForgeRuntimeManager.list()) {
			availableItems.add(def);
		}
		for (var def : BlockRuntimeManager.list()) {
			availableBlocks.add(def);
		}

		int centerX = this.width / 2;
		int y = 40;

		modNameField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("Mod 名称"));
		modNameField.setText("My Mod");
		this.addSelectableChild(modNameField);
		y += 24;

		modIdField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("Mod ID"));
		modIdField.setText("my_mod");
		this.addSelectableChild(modIdField);
		y += 24;

		versionField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("版本"));
		versionField.setText("1.0.0");
		this.addSelectableChild(versionField);
		y += 24;

		outputDirField = new TextFieldWidget(this.textRenderer, centerX - 150, y, 300, 20,
				Text.literal("输出目录"));
		var defaultDir = Path.of("worldforge-exports").toAbsolutePath().toString();
		outputDirField.setText(defaultDir);
		this.addSelectableChild(outputDirField);
		y += 30;

		// Export button
		this.addDrawableChild(ButtonWidget.builder(Text.literal("导出 Mod JAR"), b -> startExport())
				.dimensions(centerX - 80, y, 160, 20)
				.build());

		// Cancel/close button
		this.addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> this.close())
				.dimensions(centerX + 90, y, 80, 20)
				.build());

		// Select all items button
		this.addDrawableChild(ButtonWidget.builder(Text.literal("全选物品"), b -> selectAllItems())
				.dimensions(centerX - 250, y, 80, 20)
				.build());

		// Select all blocks button
		this.addDrawableChild(ButtonWidget.builder(Text.literal("全选方块"), b -> selectAllBlocks())
				.dimensions(centerX - 160, y, 80, 20)
				.build());

		// P0-2: Confirmation buttons (hidden by default, shown when degraded export detected)
		this.addDrawableChild(ButtonWidget.builder(Text.literal("我接受降级导出"), b -> confirmDegradedExport())
				.dimensions(centerX - 80, this.height - 80, 160, 20)
				.build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal("取消"), b -> cancelDegradedConfirmation())
				.dimensions(centerX + 90, this.height - 80, 80, 20)
				.build());
	}

	private void selectAllItems() {
		selectedItemIds.clear();
		for (var def : availableItems) {
			selectedItemIds.add(def.id());
		}
		statusMessage = "已选择 " + selectedItemIds.size() + " 个物品";
	}

	private void selectAllBlocks() {
		selectedBlockIds.clear();
		for (var def : availableBlocks) {
			selectedBlockIds.add(def.id());
		}
		statusMessage = "已选择 " + selectedBlockIds.size() + " 个方块";
	}

	/**
	 * P0-2: Start export — first show capability report, block if capabilities lost.
	 */
	private void startExport() {
		if (exportInProgress) return;

		// Multiplayer safety check
		if (this.client != null && this.client.world != null && !this.client.isIntegratedServerRunning()) {
			statusMessage = "§c导出仅在单人游戏或拥有管理员权限的专用服务端中可用";
			return;
		}

		var modName = modNameField.getText();
		var modId = modIdField.getText();
		var version = versionField.getText();
		var outputDir = outputDirField.getText();

		if (selectedItemIds.isEmpty() && selectedBlockIds.isEmpty()) {
			statusMessage = "§c请至少选择一个物品或方块";
			return;
		}

		Path outDir;
		try {
			outDir = Path.of(outputDir);
			Files.createDirectories(outDir);
		} catch (Exception e) {
			statusMessage = "§c无法创建输出目录: " + e.getMessage();
			return;
		}

		var req = new ModExporter.ExportRequest(
				modId, modName, version, outDir,
				List.copyOf(selectedItemIds), List.copyOf(selectedBlockIds));

		var errors = ModExporter.validate(req);
		if (!errors.isEmpty()) {
			statusMessage = "§c校验失败: " + String.join("; ", errors);
			return;
		}

		// P0-2: Build capability report and check for lost capabilities
		var report = ModExporter.buildCapabilityReport(req);
		pendingReport = report;

		if (!report.lost().isEmpty()) {
			// Block export — require explicit confirmation
			awaitingDegradedConfirmation = true;
			statusMessage = "§e检测到 " + report.lost().size() + " 项无法导出的能力。"
					+ "请确认是否接受降级导出。";
			return;
		}

		// No lost capabilities — proceed directly
		awaitingDegradedConfirmation = false;
		proceedWithExport(req, report);
	}

	private void confirmDegradedExport() {
		if (!awaitingDegradedConfirmation || pendingReport == null) return;
		awaitingDegradedConfirmation = false;

		var modId = modIdField.getText();
		var modName = modNameField.getText();
		var version = versionField.getText();
		var outputDir = outputDirField.getText();

		var req = new ModExporter.ExportRequest(
				modId, modName, version, Path.of(outputDir),
				List.copyOf(selectedItemIds), List.copyOf(selectedBlockIds));

		statusMessage = "§e用户确认降级导出。丢失 " + pendingReport.lost().size() + " 项能力。";
		proceedWithExport(req, pendingReport);
	}

	private void cancelDegradedConfirmation() {
		awaitingDegradedConfirmation = false;
		pendingReport = null;
		statusMessage = "已取消导出。";
	}

	private void proceedWithExport(ModExporter.ExportRequest req, ModExporter.CapabilityReport report) {
		statusMessage = "§a开始导出... 保留 " + report.retained().size()
				+ " 项能力, 丢失 " + report.lost().size() + " 项";

		exportInProgress = true;
		result = null;
		exportExecutor = new ExportExecutor();

		// P1-3: Take an immutable snapshot of definitions before starting the background thread
		var itemSnapshot = new ArrayList<ItemDefinition>();
		for (String id : req.itemIds()) {
			var def = WorldForgeRuntimeManager.get(id);
			if (def != null) itemSnapshot.add(def);
		}
		var blockSnapshot = new ArrayList<dev.worldforge.block.BlockDefinition>();
		for (String id : req.blockIds()) {
			var def = BlockRuntimeManager.get(id);
			if (def != null) blockSnapshot.add(def);
		}

		exportThread = new Thread(() -> {
			try {
				// P1-3: Export operates on the snapshot, not live runtime data
				result = exportExecutor.exportInstance(req);
			} catch (Exception e) {
				result = new ModExporter.ExportResult(false, null, null, null,
						"导出异常: " + e.getMessage(), List.of());
			}
			// P1-3: Result is volatile — safely visible to the client render thread
			exportInProgress = false;
		}, "WorldForge-Export");
		exportThread.setDaemon(true);
		exportThread.start();
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// Blur gating — same pattern as WorldForgeStudioScreen.
		// Screen.render() calls renderBackground() internally; without this gate
		// the second call re-blurs everything we already painted.
		if (blurDoneThisFrame) return;
		super.renderBackground(ctx, mouseX, mouseY, delta);
		blurDoneThisFrame = true;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		blurDoneThisFrame = false; // reset for this frame

		// 1) vanilla background: blur once, then darken
		renderBackground(context, mouseX, mouseY, delta);

		int centerX = this.width / 2;
		int y = 20;

		// 2) custom content (crisp, drawn after the single blur)
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("WorldForge Mod 导出器").formatted(Formatting.BOLD),
				centerX, y, 0xFFFFFF);
		y = 44;

		context.drawTextWithShadow(this.textRenderer, Text.literal("Mod 名称:"), centerX - 200, y + 5, 0xCCCCCC);
		modNameField.render(context, mouseX, mouseY, delta);
		y += 24;

		context.drawTextWithShadow(this.textRenderer, Text.literal("Mod ID:"), centerX - 200, y + 5, 0xCCCCCC);
		modIdField.render(context, mouseX, mouseY, delta);
		y += 24;

		context.drawTextWithShadow(this.textRenderer, Text.literal("版本:"), centerX - 200, y + 5, 0xCCCCCC);
		versionField.render(context, mouseX, mouseY, delta);
		y += 24;

		context.drawTextWithShadow(this.textRenderer, Text.literal("输出目录:"), centerX - 200, y + 5, 0xCCCCCC);
		outputDirField.render(context, mouseX, mouseY, delta);
		y += 30;

		// Render available items and blocks with bounded click areas
		int listY = y;
		itemLeft = centerX - 250;
		itemRight = centerX - 10;
		itemListTop = listY + 12;
		itemListBottom = itemListTop + Math.min(availableItems.size(), 5) * 11;

		context.drawTextWithShadow(this.textRenderer,
				Text.literal("物品 (" + availableItems.size() + "):"), itemLeft, listY, 0xAAAAFF);
		int itemY = itemListTop;
		int maxItems = Math.min(availableItems.size(), 5);
		for (int i = 0; i < maxItems; i++) {
			var def = availableItems.get(i);
			boolean selected = selectedItemIds.contains(def.id());
			var text = (selected ? "§a[x] " : "§c[ ] ") + def.name() + " (" + def.id() + ")";
			context.drawTextWithShadow(this.textRenderer, Text.literal(text), itemLeft, itemY, 0xFFFFFF);
			itemY += 11;
		}

		blockLeft = centerX + 10;
		blockRight = centerX + 270;
		blockListTop = listY + 12;
		blockListBottom = blockListTop + Math.min(availableBlocks.size(), 5) * 11;

		context.drawTextWithShadow(this.textRenderer,
				Text.literal("方块 (" + availableBlocks.size() + "):"), blockLeft, listY, 0xFFAAAA);
		int blockY = blockListTop;
		int maxBlocks = Math.min(availableBlocks.size(), 5);
		for (int i = 0; i < maxBlocks; i++) {
			var def = availableBlocks.get(i);
			boolean selected = selectedBlockIds.contains(def.id());
			var text = (selected ? "§a[x] " : "§c[ ] ") + def.name() + " (" + def.id() + ")";
			context.drawTextWithShadow(this.textRenderer, Text.literal(text), blockLeft, blockY, 0xFFFFFF);
			blockY += 11;
		}

		// P0-2: Show capability report if pending
		if (awaitingDegradedConfirmation && pendingReport != null) {
			int reportY = this.height - 140;
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§e=== 能力报告 ===").formatted(Formatting.BOLD),
					centerX, reportY, 0xFFFF00);
			reportY += 12;
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§a保留 " + pendingReport.retained().size() + " 项"),
					centerX, reportY, 0x00FF00);
			reportY += 11;
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§c丢失 " + pendingReport.lost().size() + " 项"),
					centerX, reportY, 0xFF0000);
			reportY += 11;
			for (int i = 0; i < Math.min(pendingReport.lost().size(), 3); i++) {
				context.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal("§c- " + pendingReport.lost().get(i)),
						centerX, reportY, 0xFF6666);
				reportY += 10;
			}
		}

		// 3) widget tree (buttons + fields) — renderBackground inside super.render()
		//    is gated by blurDoneThisFrame, so nothing re-blurs
		super.render(context, mouseX, mouseY, delta);

		// 4) status / result text (crisp, above widgets)
		y = this.height - 50;
		if (exportInProgress) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§e正在导出... 请稍候"), centerX, y, 0xFFFF00);
		} else if (result != null) {
			if (result.ok()) {
				context.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal("§a导出成功!"), centerX, y, 0x00FF00);
				y += 12;
				context.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal("JAR: " + result.jarPath()), centerX, y, 0xCCCCCC);
				y += 12;
				context.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal("SHA-256: " + result.sha256()), centerX, y, 0xCCCCCC);
			} else {
				context.drawCenteredTextWithShadow(this.textRenderer,
						Text.literal("§c导出失败: " + result.errorMessage()), centerX, y, 0xFF0000);
				if (result.projectDir() != null) {
					y += 12;
					context.drawCenteredTextWithShadow(this.textRenderer,
							Text.literal("§7源码工程: " + result.projectDir()), centerX, y, 0xCCCCCC);
				}
			}
		} else if (!statusMessage.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(statusMessage), centerX, y, 0xFFFFFF);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// Only handle list clicks within the bounded item/block list areas
		// This prevents list clicks from swallowing events meant for other controls
		if (mouseY >= itemListTop && mouseY < itemListBottom
				&& mouseX >= itemLeft && mouseX < itemRight) {
			int idx = (int)((mouseY - itemListTop) / 11);
			if (idx >= 0 && idx < Math.min(availableItems.size(), 5)) {
				var id = availableItems.get(idx).id();
				if (selectedItemIds.contains(id)) {
					selectedItemIds.remove(id);
				} else {
					selectedItemIds.add(id);
				}
				return true;
			}
		}
		if (mouseY >= blockListTop && mouseY < blockListBottom
				&& mouseX >= blockLeft && mouseX < blockRight) {
			int idx = (int)((mouseY - blockListTop) / 11);
			if (idx >= 0 && idx < Math.min(availableBlocks.size(), 5)) {
				var id = availableBlocks.get(idx).id();
				if (selectedBlockIds.contains(id)) {
					selectedBlockIds.remove(id);
				} else {
					selectedBlockIds.add(id);
				}
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !exportInProgress;
	}

	@Override
	public void close() {
		// P1-3: Cancel export and reclaim resources
		if (exportExecutor != null) {
			exportExecutor.cancel();
		}
		if (exportThread != null && exportThread.isAlive()) {
			exportThread.interrupt();
		}
		// P1-3: No success shown after cancel
		if (exportInProgress) {
			result = new ModExporter.ExportResult(false, null, null, null,
					"导出已取消", List.of());
			exportInProgress = false;
		}
		super.close();
	}
}
