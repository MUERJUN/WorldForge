package dev.worldforge.test;

import com.google.gson.JsonObject;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.network.WorldForgeStudioProtocol;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v0.2.0 Studio business tests (spec 十四.C): drive the PRODUCTION protocol
 * entry point (WorldForgeStudioProtocol.handle) with mock server players -
 * permission checks, validation, tool routing, response framing and
 * id-matching - without pretending to render a GUI.
 *
 * <p>Responses are observed through the production reply() sink; the mock
 * players' connections can't receive packets, but the pipeline is identical
 * up to the send gate.</p>
 */
public class WorldForgeStudioBusinessTests {

	private static ServerPlayerEntity opPlayer(TestContext ctx) {
		return ctx.createMockCreativeServerPlayerInWorld();
	}

	/**
	 * Grant operator status with an explicit permission level of 2.
	 * (PlayerManager.addToOperators would inherit the server default, and the
	 * GameTest TestServer reports level 0 - so insert the OperatorEntry with
	 * the same level /op produces on a real server.)
	 */
	private static void grantOp(ServerPlayerEntity p) {
		p.getServer().getPlayerManager().getOpList()
				.add(new net.minecraft.server.OperatorEntry(p.getGameProfile(), 2, false));
	}

	/** record responses from the production sink while driving requests */
	private record Recorder(java.util.List<WorldForgeStudioProtocol.StudioResponse> got) implements java.util.function.Consumer<WorldForgeStudioProtocol.StudioResponse> {
		@Override
		public void accept(WorldForgeStudioProtocol.StudioResponse r) {
			got.add(r);
		}
	}

	private static void drive(TestContext ctx, ServerPlayerEntity player, String kind, int reqId, String payload) {
		WorldForgeStudioProtocol.handle(ctx.getWorld().getServer(), player,
				new WorldForgeStudioProtocol.StudioRequest(kind, reqId, payload));
	}

	private static JsonObject defBody(String id, String name, double damage, int dur) {
		JsonObject o = new JsonObject();
		o.addProperty("version", ItemDefinition.CURRENT_VERSION);
		o.addProperty("id", id);
		o.addProperty("name", name);
		o.addProperty("description", "GUI 业务测试");
		JsonObject tex = new JsonObject();
		tex.addProperty("source", "vanilla");
		tex.addProperty("resource", "minecraft:item/diamond_sword");
		o.add("texture", tex);
		JsonObject attrs = new JsonObject();
		attrs.addProperty("damage", damage);
		attrs.addProperty("max_durability", dur);
		o.add("attributes", attrs);
		o.add("behaviors", new com.google.gson.JsonArray());
		return o;
	}

	// ---- S1: full business flow through the protocol handler ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-studio")
	public void testS1_createUpdateGiveDeleteFlow(TestContext ctx) {
		var dir = tempDir(ctx, "wf-s1");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		var server = ctx.getWorld().getServer();
		ServerPlayerEntity op = opPlayer(ctx);
		grantOp(op);

		var rec = new Recorder(new CopyOnWriteArrayList<>());
		WorldForgeStudioProtocol.RESPONSE_SINK = rec;
		try {
			// create
			drive(ctx, op, "create", 101, defBody("worldforge:s1_item", "S1物品", 7, 64).toString());
			var created = find(rec.got(), 101);
			ctx.assertTrue(created != null && created.ok() && created.phase().equals("ok"),
					"create must succeed via protocol, got " + created);
			// snapshot in the response contains the item
			ctx.assertTrue(created.snapshot().contains("worldforge:s1_item"),
					"response snapshot must carry the new definition");
			ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:s1_item") != null, "server state updated");

			// duplicate create rejected with a clear cause
			drive(ctx, op, "create", 102, defBody("worldforge:s1_item", "S1物品", 7, 64).toString());
			var dup = find(rec.got(), 102);
			ctx.assertTrue(dup != null && !dup.ok() && dup.message().contains("already exists"),
					"duplicate id must be refused, got " + dup);

			// update: damage 7 -> 20 (same pipeline as /wf setdamage)
			var updBody = defBody("worldforge:s1_item", "S1物品改", 20, 64);
			drive(ctx, op, "update", 103, updBody.toString());
			var upd = find(rec.got(), 103);
			ctx.assertTrue(upd != null && upd.ok(), "update must succeed, got " + upd);
			ctx.assertTrue(Math.abs(WorldForgeRuntimeManager.get("worldforge:s1_item").damage() - 20.0) < 0.001,
					"server damage must be 20");

			// give: real ItemStack into the mock player's inventory
			int before = countDyn(op, "worldforge:s1_item");
			JsonObject giveBody = new JsonObject();
			giveBody.addProperty("id", "worldforge:s1_item");
			giveBody.addProperty("count", 1);
			drive(ctx, op, "give", 104, giveBody.toString());
			var gave = find(rec.got(), 104);
			ctx.assertTrue(gave != null && gave.ok(), "give must succeed, got " + gave);
			ctx.assertTrue(countDyn(op, "worldforge:s1_item") == before + 1, "inventory must actually hold the stack");
			// the given stack carries the live definition damage via baked modifiers
			var st = firstDyn(op, "worldforge:s1_item");
			ctx.assertTrue(st != null && dev.worldforge.item.DynamicItem.definitionForLogic(st) != null
							&& Math.abs(dev.worldforge.item.DynamicItem.definitionForLogic(st).damage() - 20.0) < 0.001,
					"given stack must resolve the current definition (20 dmg)");

			// delete + tombstone visible in snapshot
			JsonObject delBody = new JsonObject();
			delBody.addProperty("id", "worldforge:s1_item");
			drive(ctx, op, "delete", 105, delBody.toString());
			var del = find(rec.got(), 105);
			ctx.assertTrue(del != null && del.ok(), "delete must succeed, got " + del);
			ctx.assertTrue(del.snapshot().contains("worldforge:s1_item") && del.snapshot().contains("tombstones"),
					"snapshot must list the tombstone for the deleted id");

			// give after delete: rejected, not a crash
			drive(ctx, op, "give", 106, delBody.toString());
			var gaveBad = find(rec.got(), 106);
			ctx.assertTrue(gaveBad != null && !gaveBad.ok(), "give of deleted def must be refused, got " + gaveBad);

			// unknown kind rejected
			drive(ctx, op, "hack", 107, "{}");
			var unk = find(rec.got(), 107);
			ctx.assertTrue(unk != null && !unk.ok(), "unknown kind must be rejected");
		} finally {
			WorldForgeStudioProtocol.RESPONSE_SINK = null;
			try {
				op.networkHandler.disconnect(Text.literal("s1 done"));
			} catch (Exception ignored) {
			}
		}
		ctx.complete();
	}

	// ---- S2: server-side validation of hostile client payloads ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-studio")
	public void testS2_serverRejectsBadPayloads(TestContext ctx) {
		var dir = tempDir(ctx, "wf-s2");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		ServerPlayerEntity op = opPlayer(ctx);
		grantOp(op);
		var rec = new Recorder(new CopyOnWriteArrayList<>());
		WorldForgeStudioProtocol.RESPONSE_SINK = rec;
		try {
			// insane damage (validator range)
			drive(ctx, op, "create", 201, defBody("worldforge:s2_a", "A", 999999, 10).toString());
			var a = find(rec.got(), 201);
			ctx.assertTrue(a != null && !a.ok(), "insane damage must be rejected server-side, got " + a);

			// illegal id shape
			drive(ctx, op, "create", 202, defBody("worldforge:BAD ID!!", "B", 1, 10).toString());
			var b = find(rec.got(), 202);
			ctx.assertTrue(b != null && !b.ok(), "illegal id must be rejected, got " + b);

			// garbage body
			drive(ctx, op, "create", 203, "{{{ not json");
			var c = find(rec.got(), 203);
			ctx.assertTrue(c != null && !c.ok(), "garbage JSON must be rejected, not crash, got " + c);

			// oversized id string
			String huge = "worldforge:" + "x".repeat(400);
			var body = defBody(huge, "huge", 1, 10);
			drive(ctx, op, "create", 204, body.toString());
			var d = find(rec.got(), 204);
			ctx.assertTrue(d != null && !d.ok(), "oversized id must be rejected, got " + d);
		} finally {
			WorldForgeStudioProtocol.RESPONSE_SINK = null;
			try {
				op.networkHandler.disconnect(Text.literal("s2 done"));
			} catch (Exception ignored) {
			}
		}
		ctx.complete();
	}

	// ---- S3: permission - non-OP players cannot mutate, CAN give ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-studio")
	public void testS3_permissionGate(TestContext ctx) {
		var dir = tempDir(ctx, "wf-s3");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		var server = ctx.getWorld().getServer();
		ServerPlayerEntity nonOp = opPlayer(ctx); // mock default is NOT op
		ctx.assertTrue(!WorldForgeStudioProtocol.hasEditPermission(nonOp),
				"fresh mock player must not hold OP by default");
		// seed one definition via tools directly
		WorldForgeRuntimeManager.createItem(new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:s3_item", "S3", "", TextureSource.vanilla("minecraft:item/diamond"),
				Map.of("damage", 2.0, "max_durability", 10.0), List.of()));

		var rec = new Recorder(new CopyOnWriteArrayList<>());
		WorldForgeStudioProtocol.RESPONSE_SINK = rec;
		try {
			// non-OP create -> permission denial
			drive(ctx, nonOp, "create", 301, defBody("worldforge:s3_hack", "hack", 99, 100).toString());
			var r = find(rec.got(), 301);
			ctx.assertTrue(r != null && !r.ok() && r.phase().equals("permission"),
					"non-OP create must be denied with permission phase, got " + r);
			ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:s3_hack") == null,
					"denied mutation must not change server state");
			// non-OP update/delete too
			drive(ctx, nonOp, "delete", 302, "{\"id\":\"worldforge:s3_item\"}");
			var rd = find(rec.got(), 302);
			ctx.assertTrue(rd != null && !rd.ok() && rd.phase().equals("permission"),
					"non-OP delete must be denied, got " + rd);
			// but give works for any player
			drive(ctx, nonOp, "give", 303, "{\"id\":\"worldforge:s3_item\",\"count\":1}");
			var rg = find(rec.got(), 303);
			ctx.assertTrue(rg != null && rg.ok(), "non-OP give must work, got " + rg);
			ctx.assertTrue(countDyn(nonOp, "worldforge:s3_item") == 1, "give delivered to inventory");
		} finally {
			WorldForgeStudioProtocol.RESPONSE_SINK = null;
			try {
				nonOp.networkHandler.disconnect(Text.literal("s3 done"));
			} catch (Exception ignored) {
			}
		}
		ctx.complete();
	}

	// ---- S4: response id routing (GUI drops stale replies) ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-studio")
	public void testS4_responseRequestIdMatches(TestContext ctx) {
		var dir = tempDir(ctx, "wf-s4");
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		ServerPlayerEntity op = opPlayer(ctx);
		grantOp(op);
		var rec = new Recorder(new CopyOnWriteArrayList<>());
		WorldForgeStudioProtocol.RESPONSE_SINK = rec;
		try {
			drive(ctx, op, "create", 777, defBody("worldforge:s4_item", "S4", 1, 10).toString());
			drive(ctx, op, "list", 778, "");
			ctx.assertTrue(find(rec.got(), 777) != null, "response must echo requestId 777");
			ctx.assertTrue(find(rec.got(), 778) != null, "response must echo requestId 778");
			// list snapshot includes the item created by request 777
			var l = find(rec.got(), 778);
			ctx.assertTrue(l.snapshot().contains("worldforge:s4_item"), "list snapshot is authoritative");
		} finally {
			WorldForgeStudioProtocol.RESPONSE_SINK = null;
			try {
				op.networkHandler.disconnect(Text.literal("s4 done"));
			} catch (Exception ignored) {
			}
		}
		ctx.complete();
	}

	// ---- helpers ----

	private static java.nio.file.Path tempDir(TestContext ctx, String prefix) {
		return TestTempDirs.create(prefix);
	}

	private static WorldForgeStudioProtocol.StudioResponse find(
			List<WorldForgeStudioProtocol.StudioResponse> list, int reqId) {
		for (var r : list) {
			if (r.requestId() == reqId) {
				return r;
			}
		}
		return null;
	}

	private static int countDyn(ServerPlayerEntity p, String vid) {
		int n = 0;
		for (int i = 0; i < p.getInventory().size(); i++) {
			var st = p.getInventory().getStack(i);
			var v = dev.worldforge.item.DynamicItem.virtualIdOf(st);
			if (v != null && v.id().equals(vid)) {
				n += st.getCount();
			}
		}
		return n;
	}

	private static net.minecraft.item.ItemStack firstDyn(ServerPlayerEntity p, String vid) {
		for (int i = 0; i < p.getInventory().size(); i++) {
			var st = p.getInventory().getStack(i);
			var v = dev.worldforge.item.DynamicItem.virtualIdOf(st);
			if (v != null && v.id().equals(vid)) {
				return st;
			}
		}
		return null;
	}
}
