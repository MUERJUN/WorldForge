package dev.worldforge.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.network.WorldForgeStudioProtocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The AI creation panel inside WorldForge Studio (v0.3.0).
 *
 * <p>State machine: idle -> (send chat) thinking -> (agent event) either a
 * plain answer, or a PROPOSAL PREVIEW with diff + 确认/取消 buttons. Nothing
 * here mutates anything: 确认 sends agent_confirm and the server executes
 * through AgentBridge/WorldForgeTools; the authoritative snapshot in every
 * reply rebuilds the item list.</p>
 */
public final class AiStudioPanel {

	public enum Phase { IDLE, THINKING, PROPOSAL }

	private Phase phase = Phase.IDLE;
	private long thinkingSince = 0L;
	private final List<String> chat = new ArrayList<>();
	private String pendingProposalId = null;
	private String pendingDiff = "";
	private String pendingKind = "";
	private String pendingItemId = "";
	private TextFieldWidget input;
	private ButtonWidget sendBtn, confirmBtn, cancelBtn;
	private int scroll;
	private Runnable onAfterConfirm;

	private static final int MAX_LINES = 200;

	public void init(int x, int y, int w, int h, java.util.function.Consumer<String> onSend,
			Runnable onConfirm, Runnable onCancel, Runnable afterConfirmRefresh) {
		this.onAfterConfirm = afterConfirmRefresh;
		input = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, x, y + h - 20, w - 110, 18,
				Text.literal("ai input"));
		input.setPlaceholder(Text.literal("用自然语言描述物品，如：创建雷霆之剑，伤害12，右键召唤闪电，冷却5秒"));
		onSendRef = onSend;
		sendBtn = ButtonWidget.builder(Text.literal("发送"), b -> {
			String t = input.getText().trim();
			if (!t.isEmpty() && phase != Phase.THINKING) {
				chat.add("§b你: §f" + clip(t, 160));
				input.setText("");
				phase = Phase.THINKING;
				thinkingSince = System.currentTimeMillis();
				onSend.accept(t);
			}
		}).position(x + w - 106, y + h - 20).size(50, 18).build();
		confirmBtn = ButtonWidget.builder(Text.literal("§a确认应用"), b -> {
			if (pendingProposalId != null) {
				chat.add("§7(已提交确认，等待服务端…)");
				onConfirm.run();
			}
		}).position(x + w - 52, y + h - 20).size(52, 18).build();
		cancelBtn = ButtonWidget.builder(Text.literal("取消"), b -> {
			if (pendingProposalId != null) {
				onCancel.run();
				chat.add("§7(已取消提案)");
				clearProposal();
			}
		}).position(x + w - 106, y + h - 20).size(50, 18).build();
	}

	private java.util.function.Consumer<String> onSendRef;

	public TextFieldWidget input() {
		return input;
	}

	public List<ButtonWidget> buttons() {
		return List.of(sendBtn, confirmBtn, cancelBtn);
	}

	/** release a stuck THINKING state (server never answered / model hung) */
	public void tick() {
		if (phase == Phase.THINKING && System.currentTimeMillis() - thinkingSince > 75_000) {
			phase = Phase.IDLE;
			chat.add("§eAI 响应超时 - 可重试；可视化编辑器不受影响");
			trimChat();
		}
	}

	/** feed one "agent" phase response (requestId 0) from the protocol */
	public void onAgentEvent(WorldForgeStudioProtocol.StudioResponse r) {
		try {
			JsonObject env = r.data() == null || r.data().isBlank()
					? new JsonObject() : JsonParser.parseString(r.data()).getAsJsonObject();
			String diff = env.has("diff") ? env.get("diff").getAsString() : "";
			boolean hasProposal = env.has("hasProposal") && env.get("hasProposal").getAsBoolean();
			phase = Phase.IDLE;
			chat.add((r.ok() ? "§aAI: §f" : "§cAI: §f") + clip(r.message(), 400));
			if (hasProposal && r.ok()) {
				pendingProposalId = env.get("proposalId").getAsString();
				pendingDiff = diff;
				pendingKind = env.has("kind") ? env.get("kind").getAsString() : "";
				pendingItemId = env.has("itemId") ? env.get("itemId").getAsString() : "";
				phase = Phase.PROPOSAL;
			} else if (!diff.isEmpty()) {
				chat.add("§7" + clip(diff.replace("\n", " · "), 300));
			}
		} catch (Exception e) {
			phase = Phase.IDLE;
			chat.add("§cAI 事件解析失败");
		}
		trimChat();
	}

	/** the ack for our agent_chat request */
	public void onChatAccepted(boolean ok, String message) {
		if (phase == Phase.THINKING && ok) {
			return; // keep thinking until the real outcome arrives
		}
		phase = Phase.IDLE;
		if (!ok) {
			chat.add("§c" + clip(message, 300));
			trimChat();
		}
	}

	public void onConfirmResult(boolean ok, String message) {
		chat.add((ok ? "§a" : "§c") + clip(message, 400));
		clearProposal();
		phase = Phase.IDLE;
		trimChat();
		if (ok && onAfterConfirm != null) {
			onAfterConfirm.run();
		}
	}

	public void onCancelResult() {
		clearProposal();
		phase = Phase.IDLE;
		trimChat();
	}

	private void clearProposal() {
		pendingProposalId = null;
		pendingDiff = "";
		pendingKind = "";
		pendingItemId = "";
	}

	private void trimChat() {
		while (chat.size() > MAX_LINES) {
			chat.remove(0);
		}
		scroll = Math.max(0, chat.size() - visibleLines);
	}

	private int visibleLines = 10;

	public void render(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
		var tr = MinecraftClient.getInstance().textRenderer;
		ctx.fill(x, y, x + w, y + h, 0x60000000);
		visibleLines = Math.max(3, (h - 30) / 10 - 2);
		int line = y + 4;
		int from = Math.max(0, chat.size() - visibleLines);
		if (phase == Phase.PROPOSAL) {
			// proposal preview occupies the top block
			String[] dl = pendingDiff.split("\n");
			ctx.drawTextWithShadow(tr, "§e§lAI 提案预览（" + pendingKind + "）- 确认后才会修改世界:", x + 4, line, 0xFFFFFF);
			line += 10;
			for (int i = 0; i < Math.min(dl.length, visibleLines - 3); i++) {
				ctx.drawTextWithShadow(tr, clip(dl[i], (w - 12) / 5), x + 8, line, 0xD0D0D0);
				line += 10;
			}
			line += 4;
		}
		for (int i = from; i < chat.size(); i++) {
			String t = chat.get(i);
			if (line + 10 > y + h - 24) {
				break;
			}
			ctx.drawTextWithShadow(tr, clip(t, (w - 10) / 5), x + 4, line, 0xFFFFFF);
			line += 10;
		}
		if (phase == Phase.THINKING) {
			ctx.drawTextWithShadow(tr, "§eAI 思考中…（服务端异步调用模型，不会卡游戏）", x + 4, y + h - 34, 0xFFFF60);
		}
		// bottom row: input + buttons
		input.render(ctx, mouseX, mouseY, 0f);
		sendBtn.visible = phase != Phase.PROPOSAL && phase != Phase.THINKING;
		sendBtn.active = phase == Phase.IDLE;
		confirmBtn.visible = cancelBtn.visible = phase == Phase.PROPOSAL;
		sendBtn.render(ctx, mouseX, mouseY, 0f);
		confirmBtn.render(ctx, mouseX, mouseY, 0f);
		cancelBtn.render(ctx, mouseX, mouseY, 0f);
	}

	public boolean click(double mx, double my) {
		if (phase == Phase.PROPOSAL) {
			if (confirmBtn.isHovered() || cancelBtn.isHovered()) {
				return false; // let the widget handle it
			}
		} else if (sendBtn.isHovered() || input.isMouseOver(mx, my)) {
			return false;
		}
		return false;
	}

	public boolean charTyped(char chr, int modifiers) {
		return input.charTyped(chr, modifiers);
	}

	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 257 || keyCode == 335) { // ENTER sends
			if (phase == Phase.IDLE) {
				sendBtn.onPress();
				return true;
			}
		}
		return input.keyPressed(keyCode, scanCode, modifiers);
	}

	public String pendingProposalId() {
		return pendingProposalId;
	}

	public boolean hasPending() {
		return pendingProposalId != null;
	}

	public String pendingItemId() {
		return pendingItemId;
	}

	public boolean thinking() {
		return phase == Phase.THINKING;
	}

	private static String clip(String s, int maxChars) {
		if (s == null) {
			return "";
		}
		return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…";
	}
}
