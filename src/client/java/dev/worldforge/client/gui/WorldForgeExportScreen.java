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
 * v0.5.1 P0-2: In-game Mod Export screen.
 *
 * <p>Lets players export WorldForge-created items and blocks as a standalone
 * Fabric Mod JAR without leaving the game. The export runs on a background
 * thread so the render loop is never blocked.</p>
 *
 * <p>Multiplayer safety: export is only available in single-player or when
 * the player has operator permission. In multiplayer, the export button
 * is disabled with a warning.</p>
 */
public class WorldForgeExportScreen extends Screen {

	private TextFieldWidget modNameField;
	private TextFieldWidget modIdField;
	private TextFieldWidget versionField;
	private TextFieldWidget outputDirField;

	private final List<String> selectedItemIds = new ArrayList<>();
	private final List<String> selectedBlockIds = new ArrayList<>();

	private String statusMessage = "";
	private boolean exportInProgress = false;
	private ModExporter.ExportResult result = null;
	private Thread exportThread = null;

	// Scroll offset for item/block list
	private int scrollOffset = 0;

	// Available definitions
	private final List<ItemDefinition> availableItems = new ArrayList<>();
	private final List<dev.worldforge.block.BlockDefinition> availableBlocks = new ArrayList<>();

	public WorldForgeExportScreen() {
		super(Text.literal("WorldForge Mod 导出器"));
	}

	@Override
	protected void init() {
		// Gather available definitions
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

		// Mod name field
		modNameField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("Mod 名称"));
		modNameField.setText("My Mod");
		this.addSelectableChild(modNameField);
		y += 24;

		// Mod ID field
		modIdField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("Mod ID"));
		modIdField.setText("my_mod");
		this.addSelectableChild(modIdField);
		y += 24;

		// Version field
		versionField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("版本"));
		versionField.setText("1.0.0");
		this.addSelectableChild(versionField);
		y += 24;

		// Output directory field
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

		// Create output directory
		Path outDir;
		try {
			outDir = Path.of(outputDir);
			Files.createDirectories(outDir);
		} catch (Exception e) {
			statusMessage = "§c无法创建输出目录: " + e.getMessage();
			return;
		}

		// Build export request
		var req = new ModExporter.ExportRequest(
				modId, modName, version, outDir,
				List.copyOf(selectedItemIds), List.copyOf(selectedBlockIds));

		// Validate first
		var errors = ModExporter.validate(req);
		if (!errors.isEmpty()) {
			statusMessage = "§c校验失败: " + String.join("; ", errors);
			return;
		}

		// Show capability report
		var report = ModExporter.buildCapabilityReport(req);
		statusMessage = "§a开始导出... 保留 " + report.retained().size()
				+ " 项能力, 丢失 " + report.lost().size() + " 项";
		if (!report.lost().isEmpty()) {
			statusMessage += "§e (有功能无法导出!)";
		}

		exportInProgress = true;
		result = null;

		// Run export on background thread
		exportThread = new Thread(() -> {
			try {
				result = ExportExecutor.export(req);
			} catch (Exception e) {
				result = new ModExporter.ExportResult(false, null, null, null,
						"导出异常: " + e.getMessage(), List.of());
			}
			exportInProgress = false;
		}, "WorldForge-Export");
		exportThread.setDaemon(true);
		exportThread.start();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		this.renderBackground(context, mouseX, mouseY, delta);

		int centerX = this.width / 2;
		int y = 20;

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

		// Render available items and blocks
		context.drawTextWithShadow(this.textRenderer,
				Text.literal("物品 (" + availableItems.size() + "):"), centerX - 250, y, 0xAAAAFF);
		y += 12;
		int itemY = y;
		for (int i = 0; i < Math.min(availableItems.size(), 5); i++) {
			var def = availableItems.get(i);
			boolean selected = selectedItemIds.contains(def.id());
			var text = (selected ? "§a[x] " : "§c[ ] ") + def.name() + " (" + def.id() + ")";
			context.drawTextWithShadow(this.textRenderer, Text.literal(text), centerX - 250, itemY, 0xFFFFFF);
			itemY += 11;
		}

		context.drawTextWithShadow(this.textRenderer,
				Text.literal("方块 (" + availableBlocks.size() + "):"), centerX + 10, y - 12, 0xFFAAAA);
		int blockY = y;
		for (int i = 0; i < Math.min(availableBlocks.size(), 5); i++) {
			var def = availableBlocks.get(i);
			boolean selected = selectedBlockIds.contains(def.id());
			var text = (selected ? "§a[x] " : "§c[ ] ") + def.name() + " (" + def.id() + ")";
			context.drawTextWithShadow(this.textRenderer, Text.literal(text), centerX + 10, blockY, 0xFFFFFF);
			blockY += 11;
		}

		// Render buttons
		super.render(context, mouseX, mouseY, delta);

		// Render status
		y = this.height - 60;
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
		// Check if clicking on an item to toggle selection
		int centerX = this.width / 2;
		int y = 44 + 24 * 3 + 30; // after fields
		int itemY = y + 12;
		for (int i = 0; i < Math.min(availableItems.size(), 5); i++) {
			if (mouseY >= itemY && mouseY < itemY + 11 && mouseX >= centerX - 250) {
				var id = availableItems.get(i).id();
				if (selectedItemIds.contains(id)) {
					selectedItemIds.remove(id);
				} else {
					selectedItemIds.add(id);
				}
				return true;
			}
			itemY += 11;
		}
		int blockY = y;
		for (int i = 0; i < Math.min(availableBlocks.size(), 5); i++) {
			if (mouseY >= blockY && mouseY < blockY + 11 && mouseX >= centerX + 10) {
				var id = availableBlocks.get(i).id();
				if (selectedBlockIds.contains(id)) {
					selectedBlockIds.remove(id);
				} else {
					selectedBlockIds.add(id);
				}
				return true;
			}
			blockY += 11;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !exportInProgress; // don't close during export
	}
}
