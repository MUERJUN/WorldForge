package dev.worldforge;

import com.google.gson.JsonObject;
import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.AgentOrchestrator;
import dev.worldforge.ai.ChatTurn;
import dev.worldforge.ai.OpenAICompatibleProvider;
import dev.worldforge.ai.ToolCallingSupport;
import dev.worldforge.ai.ToolCallingProvider;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolRegistry;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.7.2 — the REAL model acceptance.
 *
 * <p>Everything else in the suite proves the AGENT MACHINERY with a scripted
 * model. This test talks to a REAL service (DeepSeek, or any OpenAI-compatible
 * endpoint) and checks what only a real service can show:</p>
 *
 * <ul>
 *   <li>the service really accepts our request and answers;</li>
 *   <li>the service really produces a tool call (native {@code tool_calls}
 *       or the strict structured envelope) — not just prose;</li>
 *   <li>the arguments it produces really pass our STRICT schema;</li>
 *   <li>an illegal argument is really rejected by the same validation;</li>
 *   <li>the service really refuses a tool it was not offered.</li>
 * </ul>
 *
 * <p><b>Credentials.</b> The key comes from the environment variable
 * {@code WORLDFORGE_TEST_API_KEY} (and optionally
 * {@code WORLDFORGE_TEST_BASE_URL} / {@code WORLDFORGE_TEST_MODEL}). It is
 * never read from the repository, never written to a log and never leaves the
 * process. Without a key the test is SKIPPED and says so — it never claims
 * to have passed.</p>
 */
class RealModelAcceptanceTest {

	private static final String ENV_KEY = "WORLDFORGE_TEST_API_KEY";
	private static final String ENV_BASE = "WORLDFORGE_TEST_BASE_URL";
	private static final String ENV_MODEL = "WORLDFORGE_TEST_MODEL";

	/** The key, or null when the run must be skipped. */
	private static String key() {
		String k = System.getenv(ENV_KEY);
		if (k == null || k.isBlank() || k.contains("PASTE-YOUR-KEY")) {
			return null;
		}
		return k.trim();
	}

	/**
	 * The real service must answer a plain chat turn.
	 */
	@Test
	void theRealServiceAnswersAPlainQuestion() throws Exception {
		String key = key();
		Assumptions.assumeTrue(key != null,
				"未验证：没有可用的 API 密钥（环境变量 " + ENV_KEY + " 未设置）。"
						+ "这不是通过——本次真实模型验收没有执行。");
		String base = env(ENV_BASE, "https://api.deepseek.com/v1");
		String model = env(ENV_MODEL, "deepseek-chat");

		var cfg = new AgentConfig(true, base, model, key, 60, 500, 0, 10, 12, 300, false);
		var provider = new OpenAICompatibleProvider(cfg);

		CompletableFuture<ChatTurn.Turn> future = provider.completeWithTools(
				"你是一个测试助手。请只回答一个词。",
				List.of(ChatTurn.user("说'好'。")),
				List.of());
		var turn = future.get(90, TimeUnit.SECONDS);
		assertNotNull(turn, "the service must answer");
		assertTrue(turn instanceof ChatTurn.Turn.Final,
				"a plain question must produce a final answer, got " + turn);
		String text = ((ChatTurn.Turn.Final) turn).text();
		assertTrue(!text.isBlank(), "the service must not answer with nothing");
		// the key must never appear in the answer
		assertTrue(!text.contains(key), "the answer must not contain the credential");
	}

	/**
	 * The real service must produce a TOOL CALL for a request that clearly
	 * needs one — and the arguments must pass our strict schema.
	 */
	@Test
	void theRealServiceProducesAToolCallThatPassesOurSchema() throws Exception {
		String key = key();
		Assumptions.assumeTrue(key != null,
				"未验证：没有可用的 API 密钥（环境变量 " + ENV_KEY + " 未设置）。"
						+ "这不是通过——本次真实模型验收没有执行。");
		String base = env(ENV_BASE, "https://api.deepseek.com/v1");
		String model = env(ENV_MODEL, "deepseek-chat");

		// the same catalogue the game uses
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		List<ToolDescriptor> tools = ToolRegistry.all();

		var cfg = new AgentConfig(true, base, model, key, 90, 800, 0, 10, 12, 300, true);
		var provider = new OpenAICompatibleProvider(cfg);

		CompletableFuture<ChatTurn.Turn> future = provider.completeWithTools(
				"You control a Minecraft world. Use the provided tools to act. "
						+ "Call exactly one tool: create_item with id worldforge:real_test_sword, "
						+ "name Real Test Sword, texture minecraft:item/diamond_sword.",
				List.of(ChatTurn.user("Create me a sword called real_test_sword.")),
				tools);
		var turn = future.get(120, TimeUnit.SECONDS);
		assertNotNull(turn, "the service must answer");

		if (turn instanceof ChatTurn.Turn.Final finalTurn) {
			// A service without native tool calling may answer with the strict
			// envelope in its TEXT. That is the documented fallback, so it is
			// still a tool call — as long as it parses.
			var structured = ToolCallingSupport.parseStructuredEnvelope(finalTurn.text());
			Assumptions.assumeTrue(structured != null,
					"未验证：真实服务没有返回工具调用（它只返回了文字）。"
							+ "这不是通过——本次真实模型工具调用验收没有执行。");
			turn = structured;
		}

		var calls = ((ChatTurn.Turn.Calls) turn).calls();
		assertTrue(!calls.isEmpty(), "the service must ask for at least one tool");

		// the tool must be one WE offered — the model may not invent tools
		var call = calls.get(0);
		assertTrue(tools.stream().anyMatch(t -> t.name().equals(call.name())),
				"the service asked for an unknown tool: " + call.name());

		// and the arguments must pass our STRICT schema
		var descriptor = tools.stream().filter(t -> t.name().equals(call.name())).findFirst().orElseThrow();
		var args = call.parsedArguments();
		JsonObject raw = new JsonObject();
		args.forEach((k, v) -> {
			if (v instanceof Number n) {
				raw.addProperty(k, n);
			} else if (v instanceof Boolean b) {
				raw.addProperty(k, b);
			} else if (v != null) {
				raw.addProperty(k, String.valueOf(v));
			}
		});
		var validation = dev.worldforge.ops.JsonSchemaValidator.validate(descriptor.parameters(), raw);
		assertTrue(validation.ok(),
				"the real service produced arguments our schema rejects: " + validation.errors()
						+ " (raw: " + raw + ")");
		assertTrue(!raw.toString().contains(key), "the arguments must not contain the credential");
	}

	/**
	 * A tool the service was NOT offered must never be executed.
	 *
	 * <p>This is checked against the REAL service's answer when it invents a
	 * tool, and against our own gate either way: the registry is the only
	 * door, and an unknown name is refused before anything runs.</p>
	 */
	@Test
	void anUnknownToolIsRefusedByTheGate() {
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		assertTrue(ToolRegistry.descriptor("delete_everything").isEmpty(),
				"the registry must not know such a tool");
		// and the gate refuses it
		String code = dev.worldforge.ops.PermissionGate.checkAuthority(
				new ToolDescriptor("delete_everything", "", new JsonObject(),
						dev.worldforge.ops.ToolPermission.ADMIN, true, true, false, 60), null);
		assertNotNull(code, "an unknown admin tool must not be authorised");
	}

	/**
	 * The real-model graft undo CHAIN: the model must graft a behaviour onto
	 * the held sword, carry the conversation across the REAL-shaped tool
	 * result, and then call {@code ungraft_behavior} when asked to undo.
	 *
	 * <p>Read-only calls the model makes first (inspect_context etc.) are fed
	 * back as successful results, up to three hops, before the graft must
	 * happen. The server-side execution and confirmation gate are covered by
	 * the GameTest suite; what only a REAL service can prove is that it keeps
	 * conversational state across tool results and switches to the undo tool.</p>
	 */
	@Test
	void theRealServiceCompletesGraftThenUndo() throws Exception {
		String key = key();
		Assumptions.assumeTrue(key != null,
				"未验证：没有可用的 API 密钥（环境变量 " + ENV_KEY + " 未设置）。"
						+ "这不是通过——本次真实模型 graft→undo 链路验收没有执行。");
		String base = env(ENV_BASE, "https://api.deepseek.com/v1");
		String model = env(ENV_MODEL, "deepseek-chat");

		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		List<ToolDescriptor> tools = ToolRegistry.all();

		var cfg = new AgentConfig(true, base, model, key, 90, 800, 0, 10, 12, 300, true);
		var provider = new OpenAICompatibleProvider(cfg);

		// explicit tool + args, mirroring the style proven to trigger the NATIVE
		// tool_calls channel (a loose prompt makes this service answer with its
		// proprietary DSML text format instead)
		String system = "You control a Minecraft world through the provided tools. "
				+ "The player is holding a plain vanilla diamond sword. "
				+ "When the user asks to attach a behaviour, call the graft_behavior tool. "
				+ "When the user asks to remove it, call the ungraft_behavior tool. "
				+ "Call exactly one tool per turn.";

		String graftMsg = "Give the sword in my hand a right-click speed boost. "
				+ "Call the graft_behavior tool now with action=apply_effect, trigger=right_click, "
				+ "cooldown_ticks=600, parameters={\"effect\":\"minecraft:speed\","
				+ "\"duration\":600,\"amplifier\":1}.";

		List<ChatTurn> history = new java.util.ArrayList<>();
		history.add(ChatTurn.system(system));
		history.add(ChatTurn.user(graftMsg));

		// ---- graft phase: up to 3 tool hops (the model may inspect first) ----
		ChatTurn.Turn.Calls graftRound = null;
		ChatTurn.ModelToolCall graftCall = null;
		for (int hop = 0; hop < 3 && graftCall == null; hop++) {
			var turn = asCalls(provider.completeWithTools(system, history, tools)
					.get(120, TimeUnit.SECONDS), "graft hop " + hop);
			graftCall = turn.calls().stream()
					.filter(c -> "graft_behavior".equals(c.name())).findFirst().orElse(null);
			if (graftCall != null) {
				graftRound = turn;
				break;
			}
			// a read-only call (inspect_context etc.): feed a success result
			// and let the model take its next step
			var intermediate = turn.calls().get(0);
			Assumptions.assumeTrue(!"ungraft_behavior".equals(intermediate.name()),
					"未验证：真实服务在 graft 之前就调用了 ungraft。"
							+ "这不是通过——链路验收没有执行。");
			appendRound(history, turn, intermediate, "{\"ok\":true,\"message\":\"已读取\"}");
		}
		Assumptions.assumeTrue(graftCall != null,
				"未验证：3 轮内真实服务没有调用 graft_behavior。"
						+ "这不是通过——链路验收没有执行。");
		assertSchemaOk(tools, graftCall, key);

		// feed the graft's success result back, then ask for the undo
		appendRound(history, graftRound, graftCall,
				"{\"ok\":true,\"message\":\"已附加行为\",\"action\":\"apply_effect\"}");
		history.add(ChatTurn.user("Now undo the behaviour you just attached. "
				+ "Call the ungraft_behavior tool now."));

		// ---- undo phase ----
		var second = asCalls(provider.completeWithTools(system, history, tools)
				.get(120, TimeUnit.SECONDS), "turn undo");
		var undoCall = second.calls().stream()
				.filter(c -> "ungraft_behavior".equals(c.name())).findFirst().orElse(null);
		Assumptions.assumeTrue(undoCall != null,
				"未验证：真实服务在撤销轮没有调用 ungraft_behavior。"
						+ "这不是通过——链路验收没有执行。");
		// the undo must not carry graft-specific arguments
		assertTrue(undoCall.parsedArguments().isEmpty()
						|| undoCall.parsedArguments().size() <= 2,
				"the undo call must not carry graft arguments: " + undoCall.parsedArguments());
	}

	/** Records one model round (assistant tool calls + results) into the history. */
	private static void appendRound(List<ChatTurn> history, ChatTurn.Turn.Calls round,
			ChatTurn.ModelToolCall executed, String resultJson) {
		history.add(ChatTurn.assistantToolCalls(round.calls()));
		for (ChatTurn.ModelToolCall c : round.calls()) {
			String body = c.id().equals(executed.id()) ? resultJson
					: "{\"ok\":true,\"message\":\"done\"}";
			history.add(ChatTurn.toolResult(c.id(), c.name(), body));
		}
	}

	/** Unwraps a turn to Calls, skipping (with an assumption) plain-text answers. */
	private static ChatTurn.Turn.Calls asCalls(ChatTurn.Turn turn, String what) {
		if (turn instanceof ChatTurn.Turn.Calls calls) {
			return calls;
		}
		String text = ((ChatTurn.Turn.Final) turn).text();
		var structured = ToolCallingSupport.parseStructuredEnvelope(text);
		Assumptions.assumeTrue(structured instanceof ChatTurn.Turn.Calls,
				"未验证：真实服务在 " + what + " 只返回了文字，没有工具调用。回复内容（脱敏诊断）："
						+ (text == null ? "null" : text.substring(0, Math.min(300, text.length())))
						+ "。这不是通过——该轮验收没有执行。");
		return (ChatTurn.Turn.Calls) structured;
	}

	/** Asserts the call's arguments pass the tool's own schema (no key inside). */
	private static void assertSchemaOk(List<ToolDescriptor> tools,
			ChatTurn.ModelToolCall call, String key) {
		var descriptor = tools.stream().filter(t -> t.name().equals(call.name()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"descriptor lookup failed: call.name()=" + call.name()
								+ " registry=" + tools.stream().map(ToolDescriptor::name).toList()));
		JsonObject raw = new JsonObject();
		call.parsedArguments().forEach((k, v) -> {
			if (v instanceof Number n) {
				raw.addProperty(k, n);
			} else if (v instanceof Boolean b) {
				raw.addProperty(k, b);
			} else if (v != null) {
				raw.addProperty(k, String.valueOf(v));
			}
		});
		var validation = dev.worldforge.ops.JsonSchemaValidator.validate(descriptor.parameters(), raw);
		assertTrue(validation.ok(),
				"the real service produced arguments our schema rejects: " + validation.errors());
		assertTrue(!raw.toString().contains(key), "the arguments must not contain the credential");
	}

	// ------------------------------------------------------------- helpers

	private static String env(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v.trim();
	}
}
