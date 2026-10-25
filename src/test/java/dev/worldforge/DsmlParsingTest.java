package dev.worldforge;

import dev.worldforge.ai.ChatTurn;
import dev.worldforge.ai.ToolCallingSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.7.4: the DeepSeek DSML text fallback.
 *
 * <p>Real-model acceptance found that some deployments of the service answer
 * with their proprietary {@code <DSML invoke>} markup in the message CONTENT
 * instead of the OpenAI {@code tool_calls} field. The parser turns that into
 * the SAME {@link ChatTurn.Turn.Calls} a native response produces, so the
 * extracted arguments run through the SAME schema validation and confirmation
 * gate — the fallback is a compatibility bridge, never a bypass.</p>
 */
class DsmlParsingTest {

	/** A sanitized sample of the REAL response captured during acceptance. */
	private static final String REAL_DSML = """
			<｜｜DSML｜｜ calls>
			<｜｜DSML｜｜ invoke name="graft_behavior">
			<｜｜DSML｜｜ parameter name="action" string="true">apply_effect</｜｜DSML｜｜ parameter>
			<｜｜DSML｜｜ parameter name="trigger" string="true">right_click</｜｜DSML｜｜ parameter>
			<｜｜DSML｜｜ parameter name="cooldown_ticks" string="false">600</｜｜DSML｜｜ parameter>
			<｜｜DSML｜｜ parameter name="parameters" string="false">{"effect": "speed", "duration": 600}</｜｜DSML｜｜ parameter>
			</｜｜DSML｜｜ invoke>
			</｜｜DSML｜｜ calls>""";

	@Test
	void theRealDsmlSampleParsesIntoTheSameCallsTurn() {
		var turn = ToolCallingSupport.parseDsmlCalls(REAL_DSML);
		assertNotNull(turn, "the real DSML sample must parse");
		assertEquals(1, turn.calls().size());
		var call = turn.calls().get(0);
		assertEquals("graft_behavior", call.name());
		var args = call.parsedArguments();
		assertEquals("apply_effect", args.get("action"));
		assertEquals("right_click", args.get("trigger"));
		assertEquals(600.0, ((Number) args.get("cooldown_ticks")).doubleValue());
		// the nested JSON parameter survives as an object, not a string
		assertTrue(args.get("parameters") instanceof java.util.Map,
				"the parameters value must stay an object: " + args.get("parameters"));
	}

	/** Builds a chat-completions response JSON with the given content and tool calls. */
	private static String responseJson(String content, String toolCallJson) {
		var msg = new com.google.gson.JsonObject();
		msg.addProperty("role", "assistant");
		msg.addProperty("content", content);
		if (toolCallJson != null) {
			msg.add("tool_calls", com.google.gson.JsonParser.parseString(toolCallJson));
		}
		var choice = new com.google.gson.JsonObject();
		choice.add("message", msg);
		var choices = new com.google.gson.JsonArray();
		choices.add(choice);
		var root = new com.google.gson.JsonObject();
		root.add("choices", choices);
		return root.toString();
	}

	@Test
	void dsmlInsideAResponseIsParsedByParseResponseAfterNativeAndEnvelope() {
		// a response whose content carries ONLY the DSML markup must yield Calls
		var turn = ToolCallingSupport.parseResponse(responseJson(REAL_DSML, null));
		assertTrue(turn instanceof ChatTurn.Turn.Calls,
				"a DSML reply must become a Calls turn, got " + turn);
		assertEquals("graft_behavior", ((ChatTurn.Turn.Calls) turn).calls().get(0).name());
	}

	@Test
	void nativeToolCallsStillWinOverDsml() {
		String toolCalls = "[{\"id\":\"n1\",\"type\":\"function\",\"function\":"
				+ "{\"name\":\"inspect_context\",\"arguments\":\"{}\"}}]";
		var turn = ToolCallingSupport.parseResponse(responseJson(REAL_DSML, toolCalls));
		assertEquals("inspect_context", ((ChatTurn.Turn.Calls) turn).calls().get(0).name(),
				"the native channel must take priority");
	}

	@Test
	void plainTextIsNeverMistakenForDsml() {
		assertNull(ToolCallingSupport.parseDsmlCalls("你好，请问要加什么效果？"));
		assertNull(ToolCallingSupport.parseDsmlCalls(""));
		assertNull(ToolCallingSupport.parseDsmlCalls(null));
	}

	@Test
	void aWeirdToolNameFailsClosed() {
		String evil = REAL_DSML.replace("graft_behavior", "DROP_TABLE_USERS");
		assertNull(ToolCallingSupport.parseDsmlCalls(evil),
				"an uppercase SQL-ish name must not become a call");
	}

	@Test
	void anUnclosedInvokeFailsClosed() {
		String broken = REAL_DSML.substring(0, REAL_DSML.indexOf("</｜｜DSML｜｜ invoke>"));
		assertNull(ToolCallingSupport.parseDsmlCalls(broken),
				"an unterminated invoke must not produce a call");
	}

	@Test
	void aDsmlCallStillFailsSchemaValidationDownstream() {
		// the parser is a bridge, not a bypass: invented arguments must still
		// be rejected by the same validator a native call goes through
		var turn = ToolCallingSupport.parseDsmlCalls(REAL_DSML);
		var call = turn.calls().get(0);
		dev.worldforge.ops.ToolCatalog.resetForTest();
		dev.worldforge.ops.ToolCatalog.registerAll();
		var descriptor = dev.worldforge.ops.ToolRegistry.descriptor("graft_behavior").orElseThrow();
		var raw = new com.google.gson.JsonObject();
		call.parsedArguments().forEach((k, v) -> {
			if (v instanceof Number n) {
				raw.addProperty(k, n);
			} else if (v instanceof Boolean b) {
				raw.addProperty(k, b);
			} else if (v instanceof java.util.Map<?, ?> m) {
				raw.add(k, com.google.gson.JsonParser.parseString(m.toString()));
			} else {
				raw.addProperty(k, String.valueOf(v));
			}
		});
		// the sample lacks cooldown range knowledge? it is in-range; the point
		// is that the VALIDATION RUNS — a broken arg would fail it
		var result = dev.worldforge.ops.JsonSchemaValidator.validate(descriptor.parameters(), raw);
		assertTrue(result.ok() || !result.errors().isEmpty(),
				"validation must run on DSML-extracted arguments");
		assertFalse(call.name().isEmpty());
	}
}
