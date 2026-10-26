package dev.worldforge.ai;

import dev.worldforge.ai.ChatTurn.ModelToolCall;
import dev.worldforge.ai.ChatTurn.Turn;

import java.util.List;

/**
 * v0.7.4: the GameTest suite's RESERVED message routes.
 *
 * <p>The GameTest runner runs batches concurrently and the mock provider is
 * a global singleton: whichever test's provider happens to be attached must
 * still answer a RESERVED message identically, or a concurrent batch hands
 * the message the wrong tool call (observed on Linux CI as a refused indexed
 * ungraft).</p>
 *
 * <p>These routes live here (next to the other test doubles in the main
 * source set) and are consulted by EVERY test provider funnel:
 * {@link MockToolProvider} and the wrapper {@code AgentBridge} puts around
 * every attached {@link ToolCallingProvider}.</p>
 *
 * <p>The three SPECIFIC undo phrases are reserved because different ungraft
 * tests need different arguments and the phrases must produce the same call
 * no matter which provider is attached. The plain 「撤销」 family is
 * deliberately NOT reserved: its meaning differs by test state (one grafted
 * behaviour vs several), and every provider's own lambda already answers it
 * identically.</p>
 */
public final class SuiteReservedRoutes {

	/** Exact-match routes registered by test classes (v0.8.0 world edit). */
	private static final java.util.concurrent.ConcurrentHashMap<String, java.util.function.Supplier<Turn>>
			EXACT_ROUTES = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Registers an EXACT-match route for a test message. Reserved routes win
	 * over every per-test lambda, so a world-edit test gets its planned call
	 * no matter which provider is the global singleton.
	 */
	public static void registerExact(String message, java.util.function.Supplier<Turn> answer) {
		EXACT_ROUTES.put(message, answer);
	}

	private SuiteReservedRoutes() {
	}

	/**
	 * The canonical answer for a reserved message, or {@code null} when the
	 * message is not reserved and the per-test lambda should decide.
	 */
	public static Turn answer(String userMessage) {
		if (userMessage == null) {
			return null;
		}
		var exact = EXACT_ROUTES.get(userMessage);
		if (exact != null) {
			// one-shot: the orchestrator re-asks with the SAME newest user message
			// after the tool result; answering the same call twice would loop the
			// confirm flow forever. The second ask gets a clean final answer.
			if (!ANSWERED.add("exact:" + userMessage)) {
				return new Turn.Final("好了，已处理。");
			}
			return exact.get();
		}
		String phrase = reservedPhrase(userMessage);
		if (phrase == null) {
			return null;
		}
		// The orchestrator calls the model AGAIN after the tool result, and the
		// newest user message is still the same undo request. Answering it with
		// the same call a second time would try to undo an already-undone item
		// (preparer refuses) and the refusal would clobber the round-1 success
		// in the test's event recorder. So: the call ONCE, then a clean Final.
		if (!ANSWERED.add(phrase)) {
			return new Turn.Final("好了，已处理。");
		}
		return call(argsFor(phrase));
	}

	private static final java.util.Set<String> ANSWERED =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The reserved phrase the message contains, or null. */
	private static String reservedPhrase(String userMessage) {
		if (userMessage.contains("撤销第二个行为")) {
			return "撤销第二个行为";
		}
		if (userMessage.contains("全部撤销")) {
			return "全部撤销";
		}
		if (userMessage.contains("撤销第一个")) {
			return "撤销第一个";
		}
		return null;
	}

	private static String argsFor(String phrase) {
		return switch (phrase) {
			case "撤销第二个行为" -> "{\"behavior_index\":1}";
			case "全部撤销" -> "{\"all\":true}";
			default -> "{\"behavior_index\":0}";
		};
	}

	/** The canonical reserved answer: one ungraft call with fixed arguments. */
	private static Turn call(String argumentsJson) {
		return new Turn.Calls(List.of(new ModelToolCall(
				"reserved_ungraft", "ungraft_behavior", argumentsJson)));
	}
}
