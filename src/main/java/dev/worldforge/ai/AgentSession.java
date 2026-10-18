package dev.worldforge.ai;

import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.runtime.WorldForgeRuntimeManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * One player's AI conversation state, bound to a world session.
 *
 * <p>Boundary rules (spec 四.5 / test 12): a session belongs to exactly one
 * server start + player; it is dropped on disconnect and on world stop, so
 * "它" can never reference an item from a previous world. History is capped
 * and carries only item id/name/summary lines, never full world data or
 * files (spec 九: 不向模型发送不必要的本地文件).</p>
 */
public final class AgentSession {

	public record Turn(boolean user, String text) {
	}

	private final ArrayDeque<Turn> history = new ArrayDeque<>();
	private final int historyLimit;
	/** id of the item last created/touched in this conversation - "它" resolves here */
	private String focusItem = null;
	/** v0.4.0: blocks are a separate focus so a shared id cannot cross types */
	private String focusBlock = null;
	/** monotonic per-session turn counter doubles as the model step budget */
	private int stepsThisTurn = 0;

	public AgentSession(int historyLimit) {
		this.historyLimit = Math.max(2, historyLimit);
	}

	public void addUser(String text) {
		push(new Turn(true, clip(text)));
	}

	public void addAssistant(String text) {
		push(new Turn(false, clip(text)));
	}

	private void push(Turn t) {
		history.addLast(t);
		while (history.size() > historyLimit) {
			history.removeFirst();
		}
	}

	private static String clip(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 2000 ? s.substring(0, 2000) + "…" : s;
	}

	public void setFocusItem(String id) {
		focusItem = id;
		focusBlock = null; // a new item focus supersedes a block focus
	}

	public void setFocusBlock(String id) {
		focusBlock = id;
		focusItem = null;
	}

	public String focusBlock() {
		return focusBlock;
	}

	public String focusItem() {
		return focusItem;
	}

	public void beginTurn() {
		stepsThisTurn = 0;
	}

	/** guard against runaway tool loops (spec 九) */
	public boolean stepAllowed(int maxSteps) {
		return ++stepsThisTurn <= maxSteps;
	}

	/**
	 * Build the user message: current project context (compact item table),
	 * the focus item if any, and the conversation tail + latest input.
	 */
	public String buildUserMessage(String latest) {
		StringBuilder sb = new StringBuilder();
		sb.append("=== 当前项目物品（服务端权威，只读上下文） ===\n");
		List<ItemDefinition> items = WorldForgeRuntimeManager.list();
		if (items.isEmpty()) {
			sb.append("(空)\n");
		}
		for (ItemDefinition d : items) {
			sb.append("- ").append(d.id())
					.append(" | ").append(d.name())
					.append(" | damage=").append(d.damage())
					.append(" dur=").append(d.maxDurability());
			if (!d.behaviors().isEmpty()) {
				List<String> bs = new ArrayList<>();
				d.behaviors().forEach(b -> bs.add(b.trigger() + "->" + String.join(",", b.actions()) + " cd" + b.cooldownTicks()));
				sb.append(" | behaviors: ").append(bs);
			}
			sb.append('\n');
		}
		if (focusItem != null && WorldForgeRuntimeManager.get(focusItem) != null) {
			sb.append("focus_item: ").append(focusItem).append("（“它/这个物品”默认指代）\n");
		}
		if (focusBlock != null && dev.worldforge.block.BlockRuntimeManager.get(focusBlock) != null) {
			sb.append("focus_block: ").append(focusBlock).append("（“它/这个方块”默认指代）\n");
		}
		sb.append("=== 当前虚拟方块（服务端权威，只读上下文） ===\n");
		var blocks = dev.worldforge.block.BlockRuntimeManager.list();
		if (blocks.isEmpty()) {
			sb.append("(空)\n");
		}
		for (var b : blocks) {
			sb.append("- ").append(b.id()).append(" | ").append(b.name())
					.append(" | category=").append(b.category().name().toLowerCase())
					.append(" | appearance=").append(b.appearance());
			if (b.periodic()) {
				sb.append(" | every ").append(b.tickIntervalTicks()).append("t -> ")
						.append(b.outputItemId()).append(" x").append(b.outputCount());
			}
			sb.append('\n');
		}
		sb.append("注意：物品与方块是不同类型，id 相同也不得互相修改。\n");
		sb.append("=== 对话 ===\n");
		for (Turn t : history) {
			sb.append(t.user() ? "玩家: " : "AI: ").append(t.text()).append('\n');
		}
		sb.append("玩家: ").append(clip(latest)).append('\n');
		return sb.toString();
	}

	public List<Turn> history() {
		return List.copyOf(history);
	}
}
