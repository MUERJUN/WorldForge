package dev.worldforge.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.network.WorldForgeStudioProtocol;
import dev.worldforge.studio.StudioUiState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI creation panel inside WorldForge Studio.
 *
 * <p>v0.3.2: all business state (phase, pending proposal, visibility, send
 * gating, watchdog) lives in {@link StudioUiState} so it is unit-testable and
 * identical for drawing and interaction. This class is now only the widget
 * host and the text log.</p>
 *
 * <p>Key rule enforced by the state machine: THINKING is entered only when the
 * transport actually accepted the request. A refused send, a failed request,
 * a timeout, a cancellation or a disconnect all return to IDLE immediately,
 * so the player can never be locked out of the input.</p>
 */
public final class AiStudioPanel {

	/** what the panel needs from the screen (so it can be driven in tests too) */
	public interface Bridge {
		/** @return true when the request was actually handed to the transport */
		boolean sendChat(String text);

		/** best-effort server notification; must never block the GUI */
		void sendCancel(String proposalId);

		void sendConfirm(String proposalId);

		void refreshList();
	}

	private final StudioUiState ui;
	private final Bridge bridge;
	private final List<String> chat = new ArrayList<>();
	private TextFieldWidget input;
	private ButtonWidget sendBtn, confirmBtn, cancelBtn;

	private static final int MAX_LINES = 200;

	public AiStudioPanel(StudioUiState ui, Bridge bridge) {
		this.ui = ui;
		this.bridge = bridge;
	}

	public void init(int x, int y, int w, int h) {
		input = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, x, y + h - 20, Math.max(40, w - 110), 18,
				Text.literal("ai input"));
		input.setPlaceholder(Text.literal("用自然语言描述物品，如：创建雷霆之剑，伤害12，右键召唤闪电，冷却5秒"));

		sendBtn = ButtonWidget.builder(Text.literal("发送"), b -> submit()).position(x + w - 106, y + h - 20).size(50, 18).build();
		confirmBtn = ButtonWidget.builder(Text.literal("§a确认应用"), b -> {
			String pid = ui.pendingProposalId();
			if (pid != null) {
				add("§7(已提交确认，等待服务端…)");
				bridge.sendConfirm(pid);
			}
		}).position(x + w - 52, y + h - 20).size(52, 18).build();
		cancelBtn = ButtonWidget.builder(Text.literal("取消"), b -> cancelLocally()).position(x + w - 106, y + h - 20).size(50, 18).build();
	}

	/** the send path: gated by the state machine, never leaves a stuck spinner */
	public void submit() {
		String text = input == null ? "" : input.getText().trim();
		boolean transport = bridge != null; // screen decides real availability in sendChat
		StudioUiState.AiSend result = ui.beginAiSend(text, transport);
		switch (result) {
			case EMPTY -> add("§7请输入内容");
			case BUSY -> add("§e上一个请求还在进行中，请稍候");
			case NO_TRANSPORT -> {
				add("§c当前无法连接 WorldForge 服务端，请稍后重试（编辑器仍可使用）");
				ui.aiTransportRejected();
			}
			case SENT -> {
				add("§b你: §f" + clip(text, 160));
				if (input != null) {
					input.setText("");
				}
				// v0.3.2: if the transport refuses at the last moment, restore
				// the input immediately instead of staying in THINKING.
				if (!bridge.sendChat(text)) {
					ui.aiTransportRejected();
					add("§c请求未能发出（服务端未安装 WorldForge 或连接已断开）");
				}
			}
		}
	}

	/**
	 * v0.3.2: cancelling is a LOCAL operation first, so it works even when the
	 * server is unresponsive; the notification is best-effort and never gates
	 * the GUI.
	 */
	public void cancelLocally() {
		String pid = ui.pendingProposalId();
		ui.cancelProposalLocal();
		add("§7(已取消提案，世界数据未发生任何变化)");
		if (pid != null) {
			bridge.sendCancel(pid);
		}
	}

	/** enter-key handling; returns true when the key was consumed */
	public boolean onEnter() {
		if (ui.sendActive()) {
			submit();
			return true;
		}
		return false;
	}

	public TextFieldWidget input() {
		return input;
	}

	public List<ButtonWidget> buttons() {
		return List.of(sendBtn, confirmBtn, cancelBtn);
	}

	public List<String> chatLines() {
		return List.copyOf(chat);
	}

	public void add(String line) {
		chat.add(line);
		while (chat.size() > MAX_LINES) {
			chat.remove(0);
		}
	}

	/**
	 * Release a stuck THINKING state. Delegates to the state machine watchdog
	 * so the same timeout rule is exercised by unit tests.
	 */
	public void tick() {
		if (ui.tick(System.currentTimeMillis())) {
			add("§eAI 响应超时 - 可重试；可视化编辑器不受影响");
		}
	}

	/** feed one "agent" phase response (requestId 0) from the protocol */
	public void onAgentEvent(WorldForgeStudioProtocol.StudioResponse r) {
		String diff = "";
		boolean hasProposal = false;
		String pid = null, kind = "", itemId = "";
		try {
			JsonObject env = r.data() == null || r.data().isBlank()
					? new JsonObject() : JsonParser.parseString(r.data()).getAsJsonObject();
			diff = env.has("diff") ? env.get("diff").getAsString() : "";
			hasProposal = env.has("hasProposal") && env.get("hasProposal").getAsBoolean();
			pid = env.has("proposalId") ? env.get("proposalId").getAsString() : null;
			kind = env.has("kind") ? env.get("kind").getAsString() : "";
			itemId = env.has("itemId") ? env.get("itemId").getAsString() : "";
		} catch (Exception e) {
			ui.aiFailed();
			add("§cAI 事件解析失败（已恢复输入）");
			return;
		}
		ui.aiOutcome(r.ok(), hasProposal, pid, diff, kind, itemId);
		add((r.ok() ? "§aAI: §f" : "§cAI: §f") + clip(r.message(), 400));
		if (!hasProposal && !diff.isEmpty()) {
			add("§7" + clip(diff.replace("\n", " · "), 300));
		}
	}

	/** the ack for our agent_chat request */
	public void onChatAccepted(boolean ok, String message) {
		if (ok) {
			return; // still waiting for the real outcome
		}
		ui.aiAcknowledged(false);
		add("§c" + clip(message, 300));
	}

	public void onConfirmResult(boolean ok, String message) {
		add((ok ? "§a" : "§c") + clip(message, 400));
		ui.cancelProposalLocal(); // the proposal is resolved either way
		bridge.refreshList();
	}

	private static String clip(String s, int maxChars) {
		if (s == null) {
			return "";
		}
		return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…";
	}

	/** paint the conversation + proposal preview (widgets are drawn by the Screen) */
	public void render(DrawContext ctx, int x, int y, int w, int h) {
		var tr = MinecraftClient.getInstance().textRenderer;
		var state = StudioUiStateContext.of(ui);
		int line = y + 4;
		int visibleLines = Math.max(3, (h - 30) / 10 - 2);

		if (state.proposal()) {
			String[] dl = ui.pendingDiff().split("\n");
			ctx.drawTextWithShadow(tr, "§e§lAI 提案预览（" + ui.pendingKind() + "）- 确认后才会修改世界:", x + 4, line, 0xFFFFFF);
			line += 10;
			for (int i = 0; i < Math.min(dl.length, Math.max(0, visibleLines - 3)); i++) {
				ctx.drawTextWithShadow(tr, clip(dl[i], Math.max(8, (w - 12) / 5)), x + 8, line, 0xD0D0D0);
				line += 10;
			}
			line += 4;
		}
		int from = Math.max(0, chat.size() - visibleLines);
		for (int i = from; i < chat.size(); i++) {
			if (line + 10 > y + h - 24) {
				break;
			}
			ctx.drawTextWithShadow(tr, clip(chat.get(i), Math.max(8, (w - 10) / 5)), x + 4, line, 0xFFFFFF);
			line += 10;
		}
		if (state.thinking()) {
			ctx.drawTextWithShadow(tr, "§eAI 思考中…（服务端异步调用模型，不会卡游戏）", x + 4, y + h - 34, 0xFFFF60);
		}
	}

	/** small projection used only for drawing decisions */
	record StudioUiStateContext(boolean proposal, boolean thinking) {
		static StudioUiStateContext of(StudioUiState ui) {
			return new StudioUiStateContext(ui.confirmVisible(), ui.thinkingHintVisible());
		}
	}
}