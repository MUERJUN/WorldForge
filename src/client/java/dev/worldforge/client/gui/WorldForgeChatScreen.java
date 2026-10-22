package dev.worldforge.client.gui;

import dev.worldforge.client.chat.ChatClientState;
import dev.worldforge.chat.ChatMessage;
import dev.worldforge.chat.ChatSession;
import dev.worldforge.chat.ChatTaskState;
import dev.worldforge.client.chat.ChatUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The v0.7.0 default screen: an AI chat, opened with K.
 *
 * <p>Deliberately plain. There is no item editor, no block editor, no export
 * panel, no console and no debug dashboard here — those live in the legacy
 * Studio, which stays reachable from the settings entry for players who want
 * them. The home surface is: the conversation, the input, the session
 * switcher, and settings.</p>
 *
 * <p>Everything the player sees about the AI's work is a real tool event, so
 * a green "done" can only appear after the server actually did the thing.</p>
 */
public class WorldForgeChatScreen extends Screen {

	/** the entry point to the legacy Studio, for players who need it */
	public interface StudioOpener {
		void openStudio();
	}

	/** the entry point to the AI settings panel */
	public interface SettingsOpener {
		void openSettings();
	}

	private static final int HEADER_H = 22;
	private static final int FOOTER_H = 26;
	private static final int LINE_H = 10;
	private static final int PAD = 6;

	private final StudioOpener studioOpener;
	private final SettingsOpener settingsOpener;

	private TextFieldWidget input;
	private ButtonWidget sendButton;
	private ButtonWidget cancelButton;
	private ButtonWidget confirmButton;
	private ButtonWidget denyButton;
	private ButtonWidget newButton;
	private ButtonWidget sessionsButton;
	private ButtonWidget settingsButton;

	/** scroll offset in PIXELS of the message list */
	private int scrollPixels;
	/** total content height of the message list, for clamping */
	private int contentHeight;
	/** true while the list is pinned to the bottom */
	private boolean stickToBottom = true;
	/** true while the session picker is open */
	private boolean sessionsOpen;
	/** the message that is currently waiting for a decision */
	private ChatMessage pendingConfirm;

	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;
	private int listX;
	private int listY;
	private int listW;
	private int listH;

	public WorldForgeChatScreen(StudioOpener studioOpener, SettingsOpener settingsOpener) {
		super(Text.literal("WorldForge AI"));
		this.studioOpener = studioOpener;
		this.settingsOpener = settingsOpener;
	}

	@Override
	protected void init() {
		ChatClientState.load();

		var l = dev.worldforge.ui.ChatLayout.compute(width, height);
		panelW = l.panelW();
		panelH = l.panelH();
		panelX = l.panelX();
		panelY = l.panelY();
		listX = l.listX();
		listY = l.listY();
		listW = l.listW();
		listH = l.listH();

		// ---- header ----
		int btnH = 16;
		sessionsButton = ButtonWidget.builder(Text.literal("☰ 会话"), b -> toggleSessions())
				.position(l.sessionX(), l.headerY()).size(46, btnH).build();
		newButton = ButtonWidget.builder(Text.literal("＋"), b -> {
			ChatClientState.createSession();
			stickToBottom = true;
			scrollPixels = 0;
			refreshButtons();
		}).position(l.newX(), l.headerY()).size(20, btnH).build();
		settingsButton = ButtonWidget.builder(Text.literal("⚙"), b -> {
			if (settingsOpener != null) {
				settingsOpener.openSettings();
			}
		}).position(l.settingsX(), l.headerY()).size(20, btnH).build();

		// ---- footer ----
		int fy = l.inputY();
		int sendW = l.sendW();
		input = new TextFieldWidget(textRenderer, l.inputX(), fy, l.inputW(), 18,
				Text.literal("chat input"));
		input.setMaxLength(4000);
		input.setPlaceholder(Text.literal("描述你想要的物品或方块，例如：创建雷霆之剑，右键召唤闪电，冷却 5 秒"));
		input.setChangedListener(s -> refreshButtons());

		sendButton = ButtonWidget.builder(Text.literal("发送"), b -> submit())
				.position(l.sendX(), l.sendY()).size(sendW, 18).build();
		cancelButton = ButtonWidget.builder(Text.literal("取消任务"), b -> {
			ChatClientState.cancelTask();
		}).position(panelX + PAD, fy - 20).size(56, 18).build();

		// ---- inline confirmation controls ----
		confirmButton = ButtonWidget.builder(Text.literal("确认执行"), b -> {
			if (pendingConfirm != null) {
				ChatClientState.confirm(pendingConfirm.confirmationId());
				pendingConfirm = null;
				refreshButtons();
			}
		}).position(0, 0).size(60, 18).build();
		denyButton = ButtonWidget.builder(Text.literal("取消"), b -> {
			if (pendingConfirm != null) {
				ChatClientState.deny(pendingConfirm.confirmationId());
				pendingConfirm = null;
				refreshButtons();
			}
		}).position(0, 0).size(40, 18).build();

		addDrawableChild(sessionsButton);
		addDrawableChild(newButton);
		addDrawableChild(settingsButton);
		addDrawableChild(input);
		addDrawableChild(sendButton);
		addDrawableChild(cancelButton);
		addDrawableChild(confirmButton);
		addDrawableChild(denyButton);
		refreshButtons();
		setInitialFocus(input);
	}

	/** Shows or hides the controls that depend on the current state. */
	private void refreshButtons() {
		if (input == null || sendButton == null) {
			return;
		}
		boolean hasText = !input.getText().trim().isEmpty();
		sendButton.active = hasText;
		ChatSession session = ChatClientState.active();
		cancelButton.visible = session.busy();
		pendingConfirm = null;
		for (ChatMessage m : session.messages()) {
			if (m.isPendingConfirmation()) {
				pendingConfirm = m;
			}
		}
		boolean pending = pendingConfirm != null;
		confirmButton.visible = pending;
		denyButton.visible = pending;
		if (pending) {
			int y = Math.min(panelY + panelH - FOOTER_H - 22, listY + listH - 22);
			confirmButton.setX(panelX + PAD);
			confirmButton.setY(y);
			denyButton.setX(panelX + PAD + 64);
			denyButton.setY(y);
		}
	}

	/** Sends the current input to the server. */
	private void submit() {
		String text = input.getText().trim();
		if (text.isEmpty()) {
			return;
		}
		input.setText("");
		ChatClientState.sendMessage(text);
		stickToBottom = true;
		scrollPixels = 0;
		refreshButtons();
	}

	/** Opens or closes the session picker. */
	private void toggleSessions() {
		sessionsOpen = !sessionsOpen;
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		// A flat translucent scrim instead of the vanilla blurred background:
		// the game stays visible but the chat is clearly in front, and nothing
		// is blurred twice.
		ctx.fill(0, 0, width, height, ChatUi.SCRIM);

		ChatUi.roundedOutline(ctx, panelX, panelY, panelW, panelH, 6, ChatUi.PANEL, ChatUi.BORDER);
		ChatUi.roundedRect(ctx, panelX, panelY, panelW, HEADER_H, 6, ChatUi.HEADER);
		ctx.fill(panelX, panelY + HEADER_H - 1, panelX + panelW, panelY + HEADER_H, ChatUi.BORDER);

		renderHeader(ctx);
		renderMessages(ctx, mouseX, mouseY);
		renderFooter(ctx);

		super.render(ctx, mouseX, mouseY, delta);

		if (sessionsOpen) {
			renderSessionPicker(ctx, mouseX, mouseY);
		}
	}

	/**
	 * The running indicator the header shows, or an empty string when no task
	 * is running.
	 *
	 * <p>Used by the client auto-test to prove that the indicator follows the
	 * REAL task state (a confirmation is not "done", and a cancel request is
	 * not "stopped").</p>
	 */
	public String busyIndicator() {
		var session = ChatClientState.active();
		if (!session.busy()) {
			return "";
		}
		return switch (session.taskState()) {
			case THINKING -> "思考中…";
			case AWAITING_CONFIRMATION -> "等待确认";
			case EXECUTING -> "执行中…";
			case CANCELLING -> "取消中…";
			case QUEUED -> "排队中";
			default -> "…";
		};
	}

	private void renderHeader(DrawContext ctx) {
		ChatSession session = ChatClientState.active();
		String title = session.title();
		int tx = panelX + PAD + 74;
		int avail = panelW - (tx - panelX) - PAD - 24;
		ctx.drawTextWithShadow(textRenderer, ChatUi.clip(textRenderer, title, Math.max(20, avail)),
				tx, panelY + 7, ChatUi.TEXT);
		if (session.busy()) {
			// the indicator follows the REAL task state, so "confirmed but
			// still running" does not look like "finished"
			String mark = switch (session.taskState()) {
				case THINKING -> "思考中…";
				case AWAITING_CONFIRMATION -> "等待确认";
				case EXECUTING -> "执行中…";
				case CANCELLING -> "取消中…";
				case QUEUED -> "排队中";
				default -> "…";
			};
			int color = session.taskState() == ChatTaskState.AWAITING_CONFIRMATION
					? ChatUi.TEXT_WARN : ChatUi.ACCENT;
			ctx.drawTextWithShadow(textRenderer, mark,
					panelX + panelW - PAD - 26 - textRenderer.getWidth(mark),
					panelY + 7, color);
		}
	}

	/** Lays the messages out and draws the visible part. */
	private void renderMessages(DrawContext ctx, int mouseX, int mouseY) {
		ChatSession session = ChatClientState.active();
		List<ChatMessage> messages = session.messages();

		// --- layout pass ---
		List<int[]> layout = new ArrayList<>();   // {y, height, kind}
		List<List<String>> lines = new ArrayList<>();
		int y = 0;
		for (ChatMessage m : messages) {
			List<String> body = switch (m.kind()) {
				case TEXT -> {
					List<String> out = new ArrayList<>();
					out.add(prefix(m) + m.text());
					yield out;
				}
				case TOOL -> {
					List<String> out = new ArrayList<>();
					out.add(toolPrefix(m) + m.text());
					yield out;
				}
				case CONFIRM -> {
					List<String> out = new ArrayList<>();
					out.add("⏳ " + m.text());
					yield out;
				}
				case STATUS -> {
					List<String> out = new ArrayList<>();
					out.add(m.text());
					yield out;
				}
			};
			List<String> wrapped = ChatUi.wrap(textRenderer, String.join("\n", body), listW - 8);
			if (wrapped.isEmpty()) {
				wrapped = List.of("");
			}
			lines.add(wrapped);
			int h = wrapped.size() * LINE_H + 3;
			layout.add(new int[]{y, h, m.kind().ordinal()});
			y += h;
		}
		contentHeight = y;

		// --- clamp scroll ---
		int maxScroll = Math.max(0, contentHeight - listH);
		if (stickToBottom) {
			scrollPixels = maxScroll;
		} else {
			scrollPixels = Math.max(0, Math.min(scrollPixels, maxScroll));
		}

		// --- draw (scissored to the list area) ---
		ctx.enableScissor(listX, listY, listX + listW, listY + listH);
		int drawY = listY - scrollPixels;
		for (int i = 0; i < layout.size(); i++) {
			int[] row = layout.get(i);
			int rowY = drawY + row[0];
			if (rowY + row[1] < listY || rowY > listY + listH) {
				continue;
			}
			ChatMessage m = messages.get(i);
			int color = colorOf(m);
			int ty = rowY + 1;
			for (String line : lines.get(i)) {
				if (ty + LINE_H > listY && ty < listY + listH) {
					ctx.drawText(textRenderer, line, listX + 4, ty, color, false);
				}
				ty += LINE_H;
			}
		}
		ctx.disableScissor();

		// a scroll hint when there is more content
		if (contentHeight > listH) {
			String hint = scrollPixels < maxScroll ? "▼ 还有更多" : "▲ 回到最新";
			ctx.drawText(textRenderer, hint, listX + listW - textRenderer.getWidth(hint) - 4,
					listY + listH - LINE_H, ChatUi.TEXT_DIM, false);
		}
	}

	private String prefix(ChatMessage m) {
		return switch (m.role()) {
			case PLAYER -> "你: ";
			case ASSISTANT -> "AI: ";
			case TOOL -> "  ⚙ ";
			case SYSTEM -> "";
		};
	}

	private String toolPrefix(ChatMessage m) {
		String mark = switch (m.status()) {
			case "SUCCEEDED" -> "✔";
			case "FAILED" -> "✖";
			case "CANCELLED" -> "⊘";
			case "AWAITING_CONFIRMATION" -> "⏳";
			case "RUNNING" -> "…";
			default -> "▶";
		};
		return "  " + mark + " " + (m.toolName().isBlank() ? "" : m.toolName() + ": ");
	}

	private int colorOf(ChatMessage m) {
		if (m.kind() == ChatMessage.Kind.STATUS) {
			return ChatUi.TEXT_DIM;
		}
		if (m.kind() == ChatMessage.Kind.TOOL) {
			return switch (m.status()) {
				case "SUCCEEDED" -> ChatUi.TEXT_OK;
				case "FAILED" -> ChatUi.TEXT_ERR;
				case "CANCELLED" -> ChatUi.TEXT_DIM;
				case "AWAITING_CONFIRMATION" -> ChatUi.TEXT_WARN;
				default -> ChatUi.TEXT_DIM;
			};
		}
		if (m.kind() == ChatMessage.Kind.CONFIRM) {
			return ChatUi.TEXT_WARN;
		}
		return switch (m.role()) {
			case PLAYER -> ChatUi.TEXT;
			case ASSISTANT -> ChatUi.TEXT;
			default -> ChatUi.TEXT_DIM;
		};
	}

	private void renderFooter(DrawContext ctx) {
		ChatSession session = ChatClientState.active();
		if (session.awaitingConfirmation()) {
			ctx.drawText(textRenderer, "等待你确认上面的操作", listX + 110,
					panelY + panelH - FOOTER_H + 4, ChatUi.TEXT_WARN, false);
		}
		String notice = ChatClientState.lastServerNotice();
		if (!notice.isBlank() && !session.hasPendingConfirmation()) {
			ctx.drawText(textRenderer, ChatUi.clip(textRenderer, notice, listW - 120),
					listX + 2, panelY + panelH - FOOTER_H + 4, ChatUi.TEXT_ERR, false);
		}
	}

	/** The session picker, drawn as a small overlay list. */
	private void renderSessionPicker(DrawContext ctx, int mouseX, int mouseY) {
		List<ChatSession> sessions = ChatClientState.sessions();
		int w = Math.min(220, panelW - 20);
		int rowH = 14;
		int h = Math.min(sessions.size() * rowH + 26, panelH - 20);
		int x = panelX + PAD;
		int y = panelY + HEADER_H + 2;
		ChatUi.roundedOutline(ctx, x, y, w, h, 5, 0xF0222632, ChatUi.BORDER);
		ctx.drawText(textRenderer, "切换会话", x + 6, y + 5, ChatUi.TEXT_DIM, false);
		int ry = y + 16;
		for (ChatSession s : sessions) {
			if (ry + rowH > y + h) {
				break;
			}
			boolean active = s.conversationId().equals(ChatClientState.activeConversationId());
			if (active) {
				ChatUi.roundedRect(ctx, x + 3, ry - 1, w - 6, rowH, 3, ChatUi.ELEVATED);
			}
			String label = (active ? "▸ " : "  ") + s.title();
			ctx.drawText(textRenderer, ChatUi.clip(textRenderer, label, w - 20), x + 6, ry,
					active ? ChatUi.ACCENT : ChatUi.TEXT, false);
			// delete marker
			ctx.drawText(textRenderer, "✕", x + w - 14, ry, ChatUi.TEXT_DIM, false);
			ry += rowH;
		}
	}

	@Override
	public void tick() {
		// keep the pinned-to-bottom behaviour while new events arrive
		if (stickToBottom) {
			scrollPixels = Math.max(0, contentHeight - listH);
		}
		refreshButtons();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (sessionsOpen) {
			return true;
		}
		if (mouseY >= listY && mouseY <= listY + listH) {
			int maxScroll = Math.max(0, contentHeight - listH);
			scrollPixels = Math.max(0, Math.min(maxScroll, scrollPixels - (int) (amountY * 12)));
			stickToBottom = scrollPixels >= maxScroll;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (sessionsOpen) {
			int x = panelX + PAD;
			int y = panelY + HEADER_H + 2;
			int w = Math.min(220, panelW - 20);
			int rowH = 14;
			List<ChatSession> sessions = ChatClientState.sessions();
			int ry = y + 16;
			for (ChatSession s : sessions) {
				if (mouseX >= x && mouseX <= x + w && mouseY >= ry && mouseY < ry + rowH) {
					if (mouseX >= x + w - 18) {
						ChatClientState.deleteSession(s.conversationId());
					} else {
						ChatClientState.switchTo(s.conversationId());
					}
					sessionsOpen = false;
					stickToBottom = true;
					scrollPixels = 0;
					refreshButtons();
					return true;
				}
				ry += rowH;
			}
			sessionsOpen = false;
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		// Enter sends, Shift+Enter is a newline
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
				|| keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
			boolean shift = (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0;
			if (!shift) {
				submit();
				return true;
			}
		}
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			if (sessionsOpen) {
				sessionsOpen = false;
				return true;
			}
			close();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		super.close();
	}

	/**
	 * The panel bounds as {x, y, width, height}.
	 *
	 * <p>Used by the client auto-test to prove that every widget stays inside
	 * the panel at every GUI scale — a layout claim that is checked, not
	 * eyeballed.</p>
	 */
	public int[] panelBounds() {
		return new int[]{panelX, panelY, panelW, panelH};
	}

	// ------------------------------------------------------------ test hooks

	/** True when the input field has the keyboard focus. */
	public boolean isInputFocused() {
		return input != null && input.isFocused();
	}

	/** Fills the input field (used by the client auto-test). */
	public void setInputText(String text) {
		if (input != null) {
			input.setText(text);
			refreshButtons();
		}
	}

	/** Sends the current input (used by the client auto-test). */
	public void submitForTest() {
		submit();
	}

	/** Forces the "running" state so the cancel control can be checked. */
	public void setRunningForTest(boolean running) {
		ChatClientState.active().setTaskState(running
				? dev.worldforge.chat.ChatTaskState.EXECUTING
				: dev.worldforge.chat.ChatTaskState.IDLE);
		refreshButtons();
	}

	/** True when a settings entry is offered. */
	public boolean hasSettingsEntry() {
		return settingsButton != null;
	}

	/** Opens the settings screen (used by the client auto-test). */
	public void openSettingsForTest() {
		if (settingsOpener != null) {
			settingsOpener.openSettings();
		}
	}

	/** Opens the legacy Studio (used by the settings entry). */
	public void openStudio() {
		if (studioOpener != null) {
			studioOpener.openStudio();
		}
	}
}
