package dev.worldforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.ai.ChatTurn;
import dev.worldforge.ai.ToolCallingSupport;
import dev.worldforge.ops.JsonSchemaValidator;
import dev.worldforge.ops.PermissionGate;
import dev.worldforge.ops.ToolContext;
import dev.worldforge.ops.ToolDescriptor;
import dev.worldforge.ops.ToolEvent;
import dev.worldforge.ops.ToolEventStatus;
import dev.worldforge.ops.ToolPermission;
import dev.worldforge.ops.ToolPreparation;
import dev.worldforge.ops.ToolRegistry;
import dev.worldforge.ops.ToolRequest;
import dev.worldforge.ops.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.7.0 WF-Ops tool layer: the contract that decides what the AI may do and
 * how a tool call is validated before anything happens.
 */
class WfOpsToolLayerTest {

	@BeforeEach
	void setUp() {
		// reset the CATALOG, not just the registry: the catalog also owns the
		// "already registered" flag, so resetting only the registry would
		// leave later tests with an empty catalogue
		dev.worldforge.ops.ToolCatalog.resetForTest();
	}

	// ---------------------------------------------------------------- schema

	/** Builds a schema with one property. */
	private static JsonObject schemaWith(JsonObject property, String... required) {
		JsonObject props = new JsonObject();
		props.add("id", property);
		return JsonSchemaValidator.object(props, List.of(required));
	}

	@Test
	void schemaAcceptsAValidArgumentObject() {
		JsonObject props = new JsonObject();
		props.add("id", JsonSchemaValidator.prop("string", "id"));
		props.add("count", JsonSchemaValidator.prop("integer", "count", 1, 10));
		JsonObject schema = JsonSchemaValidator.object(props, List.of("id"));
		JsonObject args = new JsonObject();
		args.addProperty("id", "worldforge:x");
		args.addProperty("count", 3);
		var r = JsonSchemaValidator.validate(schema, args);
		assertTrue(r.ok(), "errors: " + r.errors());
		assertEquals("worldforge:x", r.values().get("id"));
		assertEquals(3L, r.values().get("count"));
	}

	@Test
	void schemaRejectsAMissingRequiredArgument() {
		JsonObject schema = schemaWith(JsonSchemaValidator.prop("string", "id"), "id");
		var r = JsonSchemaValidator.validate(schema, new JsonObject());
		assertFalse(r.ok());
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("id")), r.errors().toString());
	}

	@Test
	void schemaRejectsAnUnknownArgument() {
		JsonObject schema = schemaWith(JsonSchemaValidator.prop("string", "id"), "id");
		JsonObject args = new JsonObject();
		args.addProperty("id", "worldforge:x");
		args.addProperty("sneaky", "value");
		var r = JsonSchemaValidator.validate(schema, args);
		assertFalse(r.ok(), "unknown properties must be rejected, not ignored");
		assertTrue(r.errors().stream().anyMatch(e -> e.contains("sneaky")), r.errors().toString());
	}

	@Test
	void schemaEnforcesTheNumericRange() {
		JsonObject schema = schemaWith(JsonSchemaValidator.prop("integer", "count", 1, 10), "count");
		JsonObject args = new JsonObject();
		args.addProperty("count", 99);
		var r = JsonSchemaValidator.validate(schema, args);
		assertFalse(r.ok());
	}

	@Test
	void schemaEnforcesTheEnum() {
		JsonObject schema = schemaWith(JsonSchemaValidator.propEnum("type", "item", "block"), "type");
		JsonObject args = new JsonObject();
		args.addProperty("type", "entity");
		assertFalse(JsonSchemaValidator.validate(schema, args).ok());
	}

	// -------------------------------------------------------------- registry

	@Test
	void registryKnowsRegisteredToolsAndNothingElse() {
		ToolDescriptor d = ToolDescriptor.read("probe", "a probe", new JsonObject());
		ToolRegistry.register(d, (ctx, req, sink) -> ToolResult.ok("ok", "{}"));
		assertTrue(ToolRegistry.knows("probe"));
		assertFalse(ToolRegistry.knows("rm_rf"));
		assertTrue(ToolRegistry.descriptor("probe").isPresent());
		assertTrue(ToolRegistry.descriptor("rm_rf").isEmpty());
	}

	@Test
	void registryRejectsANullDescriptor() {
		assertThrows(() -> ToolRegistry.register(null, (c, r, s) -> null));
	}

	private static void assertThrows(Runnable r) {
		try {
			r.run();
			assertFalse(true, "expected an exception");
		} catch (IllegalArgumentException expected) {
			assertNotNull(expected);
		}
	}

	@Test
	void theCatalogRegistersEveryDocumentedTool() {
		dev.worldforge.ops.ToolCatalog.registerAll();
		List<String> names = ToolRegistry.all().stream().map(ToolDescriptor::name).toList();
		for (String expected : List.of(
				"inspect_context", "inspect_definition", "list_capabilities",
				"check_export_capabilities", "create_item", "update_item",
				"create_block", "update_block", "inspect_target_block",
				"modify_target_block", "give_virtual_item", "export_mod",
				// v0.7.2: the player's own item
				"graft_behavior", "ungraft_behavior")) {
			assertTrue(names.contains(expected), "missing tool: " + expected + " (have " + names + ")");
		}
	}

	@Test
	void theCatalogHasNoGenericCodeExecutionTool() {
		dev.worldforge.ops.ToolCatalog.registerAll();
		for (ToolDescriptor d : ToolRegistry.all()) {
			String n = d.name().toLowerCase(java.util.Locale.ROOT);
			assertFalse(n.contains("shell"), "a shell tool must never exist: " + n);
			assertFalse(n.contains("powershell"), "a shell tool must never exist: " + n);
			assertFalse(n.contains("bash"), "a shell tool must never exist: " + n);
			assertFalse(n.contains("exec"), "an exec tool must never exist: " + n);
			assertFalse(n.contains("eval"), "an eval tool must never exist: " + n);
		}
	}

	@Test
	void everyMutatingToolRequiresConfirmation() {
		dev.worldforge.ops.ToolCatalog.registerAll();
		for (ToolDescriptor d : ToolRegistry.all()) {
			if (d.mutatesWorld()) {
				assertTrue(d.requiresConfirmation(), d.name() + " mutates the world but needs no confirmation");
				assertNotEquals(ToolPermission.READ, d.permission(), d.name());
			}
		}
	}

	// ------------------------------------------------------------ permission

	@Test
	void readOnlyToolsNeedNoAuthority() {
		ToolDescriptor d = ToolDescriptor.read("probe", "a probe", new JsonObject());
		assertNull(PermissionGate.checkAuthority(d, null), "a read-only tool must work without a player");
	}

	@Test
	void mutatingToolsAreRefusedWithoutAuthority() {
		ToolDescriptor d = ToolDescriptor.write("mutate", "mutates", new JsonObject());
		assertEquals(PermissionGate.ERR_NO_PERMISSION, PermissionGate.checkAuthority(d, null));
	}

	@Test
	void aCallOutsideTheConfirmedScopeIsRefused() {
		var scope = ToolContext.ToolScope.mutating("c1", Set.of("worldforge:a"), Set.of());
		var ctx = new ToolContext(null, null, null, null, "c1", scope);
		assertNull(PermissionGate.checkScope(ctx, List.of("worldforge:a"), List.of()));
		assertEquals(PermissionGate.ERR_OUT_OF_SCOPE,
				PermissionGate.checkScope(ctx, List.of("worldforge:other"), List.of()));
	}

	@Test
	void aReadOnlyScopeRefusesEveryMutation() {
		var ctx = new ToolContext(null, null, null, null, "c1", ToolContext.ToolScope.readOnly("c1"));
		assertEquals(PermissionGate.ERR_OUT_OF_SCOPE, PermissionGate.checkScope(ctx, List.of(), List.of()));
	}

	@Test
	void aChangedTargetIsRefused() {
		assertNull(PermissionGate.checkSnapshot("a", "a"));
		assertEquals(PermissionGate.ERR_TARGET_CHANGED, PermissionGate.checkSnapshot("a", "b"));
	}

	// -------------------------------------------------------------- requests

	@Test
	void theIdempotencyKeyIsStableForTheSameCall() {
		var a = new ToolRequest("r1", "c1", "t1", "create_item", Map.of("id", "x"));
		var b = new ToolRequest("r1", "c1", "t1", "create_item", Map.of("id", "x"));
		var c = new ToolRequest("r1", "c1", "t2", "create_item", Map.of("id", "x"));
		assertEquals(a.idempotencyKey(), b.idempotencyKey());
		assertFalse(a.idempotencyKey().equals(c.idempotencyKey()),
				"a different tool call id must be a different operation");
	}

	@Test
	void toolArgumentsAreReadWithSafeDefaults() {
		var r = new ToolRequest("r1", "c1", "t1", "give_virtual_item", Map.of("count", 5));
		assertEquals(5, r.argumentInt("count", 1));
		assertEquals(1, r.argumentInt("missing", 1));
		assertEquals("x", new ToolRequest("r", "c", "t", "n", Map.of("id", "x")).argumentString("id"));
	}

	// ---------------------------------------------------------------- events

	@Test
	void aToolEventSerialisesEveryFieldTheChatNeeds() {
		var e = new ToolEvent("c1", "r1", "t1", 3, 1234L, "create_item",
				ToolEventStatus.SUCCEEDED, "已创建", "", List.of("worldforge:x"), "");
		JsonObject o = e.toJson();
		assertEquals("c1", o.get("conversationId").getAsString());
		assertEquals("r1", o.get("requestId").getAsString());
		assertEquals("t1", o.get("toolCallId").getAsString());
		assertEquals(3, o.get("sequence").getAsInt());
		assertEquals(1234L, o.get("timestamp").getAsLong());
		assertEquals("create_item", o.get("tool").getAsString());
		assertEquals("SUCCEEDED", o.get("status").getAsString());
		assertEquals("已创建", o.get("summary").getAsString());
		assertEquals("worldforge:x", o.getAsJsonArray("targets").get(0).getAsString());
	}

	@Test
	void aFailedResultIsNeverReportedAsSuccess() {
		var r = ToolResult.fail("not_found", "没有这个物品");
		assertFalse(r.ok());
		assertEquals("not_found", r.errorCode());
		assertFalse(r.persisted());
		assertFalse(r.applied());
	}

	@Test
	void aPersistSuccessWithAFailedSyncIsNotAPlainSuccess() {
		var r = ToolResult.partial("已保存但部分客户端未同步", "{}", "PARTIAL");
		assertTrue(r.ok());
		assertEquals("PARTIAL", r.syncStatus());
	}

	// ------------------------------------------------------- structured calls

	@Test
	void theStructuredEnvelopeIsParsedIntoAToolCall() {
		var turn = ToolCallingSupport.parseStructuredEnvelope(
				"{\"tool_call\":{\"name\":\"inspect_context\",\"arguments\":{}}}");
		assertNotNull(turn);
		assertTrue(turn instanceof ChatTurn.Turn.Calls);
		var calls = ((ChatTurn.Turn.Calls) turn).calls();
		assertEquals(1, calls.size());
		assertEquals("inspect_context", calls.get(0).name());
	}

	@Test
	void plainTextIsNotAToolCall() {
		assertNull(ToolCallingSupport.parseStructuredEnvelope("好的，我来帮你创建这把剑。"));
		assertNull(ToolCallingSupport.parseStructuredEnvelope(""));
		assertNull(ToolCallingSupport.parseStructuredEnvelope("{broken"));
	}

	@Test
	void aStructuredEnvelopeWithScalarArgumentsIsRejected() {
		assertNull(ToolCallingSupport.parseStructuredEnvelope(
				"{\"tool_call\":{\"name\":\"x\",\"arguments\":5}}"));
	}

	@Test
	void aStructuredEnvelopeWithoutANameIsRejected() {
		assertNull(ToolCallingSupport.parseStructuredEnvelope("{\"tool_call\":{\"arguments\":{}}}"));
	}

	@Test
	void aNativeToolCallResponseIsPreferredOverTheText() {
		String raw = "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":["
				+ "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"list_capabilities\","
				+ "\"arguments\":\"{}\"}}]}}]}";
		var turn = ToolCallingSupport.parseResponse(raw);
		assertTrue(turn instanceof ChatTurn.Turn.Calls);
		assertEquals("list_capabilities",
				((ChatTurn.Turn.Calls) turn).calls().get(0).name());
	}

	@Test
	void aPlainAnswerResponseBecomesFinalText() {
		String raw = "{\"choices\":[{\"message\":{\"content\":\"已经好了\"}}]}";
		var turn = ToolCallingSupport.parseResponse(raw);
		assertTrue(turn instanceof ChatTurn.Turn.Final);
		assertEquals("已经好了", ((ChatTurn.Turn.Final) turn).text());
	}

	// ------------------------------------------------------------ preparation

	@Test
	void aPreparationWithoutASummaryBlocksTheOperation() {
		assertTrue(ToolPreparation.none().summary().isBlank());
	}

	@Test
	void theCapabilityReportSeparatesReadOnlyFromMutatingTools() {
		dev.worldforge.ops.ToolCatalog.registerAll();
		var report = ToolRegistry.capabilityReport(null);
		@SuppressWarnings("unchecked")
		var readable = (List<Map<String, Object>>) report.get("read_only_tools");
		@SuppressWarnings("unchecked")
		var writable = (List<Map<String, Object>>) report.get("mutating_tools");
		assertTrue(readable.stream().anyMatch(m -> "inspect_context".equals(m.get("name"))));
		// WITHOUT a player only the tools that need no authority may be
		// offered — an authority-less caller must not even see the dangerous
		// operations
		assertTrue(writable.stream().noneMatch(m ->
						"create_item".equals(m.get("name"))
								|| "update_item".equals(m.get("name"))
								|| "export_mod".equals(m.get("name"))),
				"no world-changing tool may be offered without authority: " + writable);
		// the graft tools change only the player's own item: they need no
		// admin authority, but they are still NOT read-only
		assertTrue(writable.stream().anyMatch(m -> "graft_behavior".equals(m.get("name"))),
				"the graft tool must be offered to any player: " + writable);
	}
}
