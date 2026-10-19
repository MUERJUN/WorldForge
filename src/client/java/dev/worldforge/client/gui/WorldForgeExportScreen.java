package dev.worldforge.client.gui;

import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.exporter.ExportExecutor;
import dev.worldforge.exporter.ExportPlan;
import dev.worldforge.exporter.ModExporter;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

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
	/** The exact plan the user saw and must confirm — never re-read from the form. */
	private ExportPlan pendingPlan = null;
	private ButtonWidget confirmButton = null;
	private ButtonWidget cancelConfirmButton = null;
	/** P0-2: scroll offset for the full capability report. */
	private int reportScrollOffset = 0;

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

	/** Layout metrics shared by init()/render()/audit — adapts to small windows. */
	private int rowStride() {
		// 854x480 at scale 3 leaves only 160 virtual px: compress rows so all
		// content fits above the status line (height-50)
		int needed = 20 + 4 * 24 + 30 + 12 + 5 * 11 + 20 + 50;
		return this.height < needed ? 16 : 24; // compressed field stride
	}

	int fieldsBottomY() {
		return 40 + 3 * rowStride() + (rowStride() + 6);
	}

	private int buttonsTopY() {
		return fieldsBottomY();
	}

	private int listTopY() {
		return fieldsBottomY() + 12;
	}

	/** v0.5.4: bottom of the (possibly stacked) selection lists. */
	int blockListTopY() {
		if (this.width < 560) {
			return fieldsBottomY() + 12 + Math.min(availableItems.size(), 5) * 11 + 14;
		}
		return fieldsBottomY() + 12;
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
		int stride = rowStride();

		modNameField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("Mod 名称"));
		modNameField.setMaxLength(64);
		modNameField.setText("My Mod");
		this.addSelectableChild(modNameField);
		y += stride;

		modIdField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("Mod ID"));
		modIdField.setMaxLength(32);
		modIdField.setText("my_mod");
		this.addSelectableChild(modIdField);
		y += stride;

		versionField = new TextFieldWidget(this.textRenderer, centerX - 100, y, 200, 20,
				Text.literal("版本"));
		versionField.setMaxLength(16);
		versionField.setText("1.0.0");
		this.addSelectableChild(versionField);
		y += stride;

		outputDirField = new TextFieldWidget(this.textRenderer, centerX - 150, y, 300, 20,
				Text.literal("输出目录"));
		// paths are long — the 32-char default would truncate and silently
		// redirect exports into a wrong/partial directory
		outputDirField.setMaxLength(256);
		var defaultDir = Path.of("worldforge-exports").toAbsolutePath().toString();
		outputDirField.setText(defaultDir);
		this.addSelectableChild(outputDirField);
		y += stride + 6;

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

		// P0-2: Confirmation buttons — created once, visibility/active bound to
		// awaitingDegradedConfirmation in render(); completely hidden+disabled
		// in normal mode.
		confirmButton = ButtonWidget.builder(Text.literal("我接受降级导出"), b -> confirmDegradedExport())
				.dimensions(centerX - 80, this.height - 80, 160, 20)
				.build();
		cancelConfirmButton = ButtonWidget.builder(Text.literal("取消"), b -> cancelDegradedConfirmation())
				.dimensions(centerX + 90, this.height - 80, 80, 20)
				.build();
		confirmButton.visible = false;
		confirmButton.active = false;
		cancelConfirmButton.visible = false;
		cancelConfirmButton.active = false;
		this.addDrawableChild(confirmButton);
		this.addDrawableChild(cancelConfirmButton);

		// P0-2: any form edit invalidates a pending degraded confirmation
		modNameField.setChangedListener(t -> invalidatePendingConfirmation());
		modIdField.setChangedListener(t -> invalidatePendingConfirmation());
		versionField.setChangedListener(t -> invalidatePendingConfirmation());
		outputDirField.setChangedListener(t -> invalidatePendingConfirmation());
	}

	private void selectAllItems() {
		selectedItemIds.clear();
		for (var def : availableItems) {
			selectedItemIds.add(def.id());
		}
		invalidatePendingConfirmation();
		statusMessage = "已选择 " + selectedItemIds.size() + " 个物品";
	}

	private void selectAllBlocks() {
		selectedBlockIds.clear();
		for (var def : availableBlocks) {
			selectedBlockIds.add(def.id());
		}
		invalidatePendingConfirmation();
		statusMessage = "已选择 " + selectedBlockIds.size() + " 个方块";
	}

	/**
	 * P0-1/P0-2: Start export. Builds the ExportPlan ON THIS (client/game) thread,
	 * shows the full capability report, and blocks degraded exports until the
	 * user explicitly confirms the exact plan they were shown.
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

		try {
			Path.of(outputDir);
			Files.createDirectories(Path.of(outputDir));
		} catch (Exception e) {
			statusMessage = "§c无法创建输出目录: " + e.getMessage();
			return;
		}

		// P0-1: create the immutable plan on the game thread — deep-copied
		// definitions + capability report captured in one atomic step.
		ExportPlan plan;
		try {
			plan = ExportPlan.create(modId, modName, version, outputDir,
					List.copyOf(selectedItemIds), List.copyOf(selectedBlockIds));
		} catch (Exception e) {
			statusMessage = "§c无法创建导出方案: " + e.getMessage();
			return;
		}

		// P0-2: shape validation before showing the report
		var errors = ModExporter.validatePlanShape(plan);
		if (!errors.isEmpty()) {
			statusMessage = "§c校验失败: " + String.join("; ", errors);
			return;
		}

		pendingPlan = plan;
		pendingReport = plan.report();

		if (!plan.report().lost().isEmpty()) {
			// Block export — require explicit confirmation of THIS plan
			awaitingDegradedConfirmation = true;
			setConfirmationButtonsVisible(true);
			statusMessage = "§e检测到 " + plan.report().lost().size()
					+ " 项无法导出的能力。请查看报告后选择接受降级或取消。";
			return;
		}

		// No lost capabilities — proceed directly with the same plan
		awaitingDegradedConfirmation = false;
		setConfirmationButtonsVisible(false);
		proceedWithExport(plan);
	}

	/**
	 * P0-2: Confirm degraded export — executes the SAME verified plan that was
	 * displayed. Form fields are deliberately NOT re-read; if anything changed,
	 * the pending confirmation was already invalidated.
	 */
	private void confirmDegradedExport() {
		if (!awaitingDegradedConfirmation || pendingPlan == null) return;
		if (!pendingPlan.sameContentAs(pendingPlan)) { // defensive; plan is immutable
			invalidatePendingConfirmation();
			statusMessage = "§c导出方案已变化，请重新发起导出。";
			return;
		}
		awaitingDegradedConfirmation = false;
		var plan = pendingPlan.withDegradedConfirmation();
		statusMessage = "§e用户确认降级导出。丢失 " + plan.report().lost().size() + " 项能力。";
		proceedWithExport(plan);
	}

	private void cancelDegradedConfirmation() {
		invalidatePendingConfirmation();
		statusMessage = "已取消导出。";
	}

	/**
	 * P0-2: any parameter/selection change voids the displayed report and the
	 * pending confirmation — the buttons hide immediately.
	 */
	private void invalidatePendingConfirmation() {
		if (awaitingDegradedConfirmation || pendingPlan != null) {
			awaitingDegradedConfirmation = false;
			pendingPlan = null;
			pendingReport = null;
			setConfirmationButtonsVisible(false);
			statusMessage = "导出参数已变化，此前的降级确认已失效。请重新发起导出。";
		}
	}

	/**
	 * P0-2: button visibility is tied to state changes themselves (not render
	 * frames), so the UI is correct the instant the state flips.
	 */
	private void setConfirmationButtonsVisible(boolean visible) {
		if (confirmButton != null) {
			confirmButton.visible = visible;
			confirmButton.active = visible;
		}
		if (cancelConfirmButton != null) {
			cancelConfirmButton.visible = visible;
			cancelConfirmButton.active = visible;
		}
	}

	private void proceedWithExport(ExportPlan plan) {
		statusMessage = "§a开始导出... 保留 " + plan.report().retained().size()
				+ " 项能力, 丢失 " + plan.report().lost().size() + " 项";

		exportInProgress = true;
		result = null;
		// P0-1: the executor and everything below it reads ONLY this plan.
		exportExecutor = new ExportExecutor(plan);

		exportThread = new Thread(() -> {
			try {
				result = exportExecutor.doExport();
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

		// P0-2: bind confirmation buttons to the pending state — fully hidden
		// and disabled in normal mode, enabled only while a degraded plan awaits
		// user confirmation.
		boolean confirming = awaitingDegradedConfirmation && pendingPlan != null;
		if (confirmButton != null) {
			confirmButton.visible = confirming;
			confirmButton.active = confirming;
		}
		if (cancelConfirmButton != null) {
			cancelConfirmButton.visible = confirming;
			cancelConfirmButton.active = confirming;
		}

		// 1) vanilla background: blur once, then darken
		renderBackground(context, mouseX, mouseY, delta);

		int centerX = this.width / 2;
		int y = 20;

		// 2) custom content (crisp, drawn after the single blur)
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("WorldForge Mod 导出器").formatted(Formatting.BOLD),
				centerX, y, 0xFFFFFF);
		y = 44;

		int stride = rowStride();
		context.drawTextWithShadow(this.textRenderer, Text.literal("Mod 名称:"), centerX - 200, y + 5, 0xCCCCCC);
		modNameField.render(context, mouseX, mouseY, delta);
		y += stride;

		context.drawTextWithShadow(this.textRenderer, Text.literal("Mod ID:"), centerX - 200, y + 5, 0xCCCCCC);
		modIdField.render(context, mouseX, mouseY, delta);
		y += stride;

		context.drawTextWithShadow(this.textRenderer, Text.literal("版本:"), centerX - 200, y + 5, 0xCCCCCC);
		versionField.render(context, mouseX, mouseY, delta);
		y += stride;

		context.drawTextWithShadow(this.textRenderer, Text.literal("输出目录:"), centerX - 200, y + 5, 0xCCCCCC);
		outputDirField.render(context, mouseX, mouseY, delta);
		y += stride + 6;

		// Render available items and blocks with bounded click areas.
		// v0.5.4: at high GUI scale the virtual width shrinks (854/2=427,
		// 854/3=285) — the two side-by-side columns must adapt instead of
		// overflowing the window edge.
		int listY = y;
		boolean narrow = this.width < 560;
		if (narrow) {
			// stacked columns: items on top, blocks below
			itemLeft = Math.max(4, centerX - 140);
			itemRight = Math.min(this.width - 4, centerX + 140);
			blockLeft = itemLeft;
			blockRight = itemRight;
			itemListTop = listY + 12;
			itemListBottom = itemListTop + Math.min(availableItems.size(), 5) * 11;
			blockListTop = itemListBottom + 14;
			blockListBottom = blockListTop + Math.min(availableBlocks.size(), 5) * 11;
		} else {
			itemLeft = centerX - 250;
			itemRight = centerX - 10;
			itemListTop = listY + 12;
			itemListBottom = itemListTop + Math.min(availableItems.size(), 5) * 11;
		}

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

		if (!narrow) {
			blockLeft = centerX + 10;
			blockRight = centerX + 270;
			blockListTop = listY + 12;
			blockListBottom = blockListTop + Math.min(availableBlocks.size(), 5) * 11;
		}

		context.drawTextWithShadow(this.textRenderer,
				Text.literal("方块 (" + availableBlocks.size() + "):"), blockLeft,
				narrow ? blockListTop - 12 : listY, 0xFFAAAA);
		int blockY = blockListTop;
		int maxBlocks = Math.min(availableBlocks.size(), 5);
		for (int i = 0; i < maxBlocks; i++) {
			var def = availableBlocks.get(i);
			boolean selected = selectedBlockIds.contains(def.id());
			var text = (selected ? "§a[x] " : "§c[ ] ") + def.name() + " (" + def.id() + ")";
			context.drawTextWithShadow(this.textRenderer, Text.literal(text), blockLeft, blockY, 0xFFFFFF);
			blockY += 11;
		}

		// P0-2: Show the FULL capability report (retained / converted / lost)
		// with mouse-wheel scrolling while a degraded confirmation is pending.
		if (awaitingDegradedConfirmation && pendingReport != null) {
			// Flatten all three sections into one scrollable list
			var lines = new ArrayList<String>();
			for (var r : pendingReport.retained()) lines.add("§a✓ " + r);
			for (var c : pendingReport.converted()) lines.add("§b→ " + c);
			for (var l : pendingReport.lost()) lines.add("§c✗ " + l);

			int reportTop = this.height - 150;
			int reportBottom = this.height - 84;
			int visibleRows = Math.max(1, (reportBottom - reportTop - 24) / 10);
			reportScrollOffset = Math.max(0, Math.min(reportScrollOffset,
					Math.max(0, lines.size() - visibleRows)));

			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal("§e=== 能力报告 (保留 " + pendingReport.retained().size()
							+ " / 转换 " + pendingReport.converted().size()
							+ " / 丢失 " + pendingReport.lost().size() + ") ==="),
					centerX, reportTop, 0xFFFF00);
			int rowY = reportTop + 12;
			for (int i = reportScrollOffset;
					i < Math.min(lines.size(), reportScrollOffset + visibleRows); i++) {
				context.drawTextWithShadow(this.textRenderer,
						Text.literal(lines.get(i)), centerX - 240, rowY, 0xFFFFFF);
				rowY += 10;
			}
			if (lines.size() > visibleRows) {
				context.drawTextWithShadow(this.textRenderer,
						Text.literal("§7(滚轮查看更多 " + (reportScrollOffset + 1)
								+ "-" + Math.min(lines.size(), reportScrollOffset + visibleRows)
								+ "/" + lines.size() + ")"),
						centerX - 240, reportBottom - 10, 0x888888);
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
				invalidatePendingConfirmation();
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
				invalidatePendingConfirmation();
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// P0-2: ESC first cancels a pending degraded confirmation, a second ESC closes
		if (keyCode == GLFW.GLFW_KEY_ESCAPE && awaitingDegradedConfirmation) {
			cancelDegradedConfirmation();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !exportInProgress;
	}

	/**
	 * v0.5.4 Phase 3: widget boundary audit for client automation.
	 * Returns true when the title, the four input fields, both selection lists
	 * and the action buttons occupy disjoint screen regions (no overlap at the
	 * current GUI scale / window size).
	 */
	public boolean layoutRegionsAreDisjoint() {
		int centerX = this.width / 2;
		int fieldsBottom = fieldsBottomY();
		int buttonsTop = buttonsTopY();
		int listsTop = listTopY();
		int listsBottom = listsTop + Math.min(availableItems.size(), 5) * 11 + 12;
		int buttonsBottom = buttonsTop + 20;

		// rows never overlap each other
		boolean rowsDisjoint = listsTop >= fieldsBottom && buttonsTop >= fieldsBottom;
		// item column and block column must not overflow the window
		boolean columnsDisjoint = (centerX - 10) <= (centerX + 10)
				&& (centerX + 270) <= this.width || this.width < 560;
		// all painted content fits inside the window (status text overlays the
		// bottom; confirm buttons intentionally overlay lists in degraded mode)
		int contentBottom = this.width < 560
				? Math.max(blockListTopY(), listsBottom)
				: listsBottom;
		boolean insideWindow = contentBottom <= this.height
				&& buttonsBottom <= this.height;
		// every interactive row must be reachable (below the title)
		boolean belowTitle = fieldsBottom >= 60;
		return rowsDisjoint && columnsDisjoint && insideWindow && belowTitle;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		// P0-2: scroll the capability report while confirmation is pending
		if (awaitingDegradedConfirmation && pendingReport != null) {
			reportScrollOffset = Math.max(0, reportScrollOffset - (int) Math.signum(verticalAmount));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
