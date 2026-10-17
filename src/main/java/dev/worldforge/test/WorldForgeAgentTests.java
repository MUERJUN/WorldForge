package dev.worldforge.test;

import dev.worldforge.ai.AgentBridge;
import dev.worldforge.ai.AgentConfig;
import dev.worldforge.ai.MockModelProvider;
import dev.worldforge.ai.Proposal;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
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
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * v0.3.0 AgentBridge end-to-end business chain (spec 十一 验收链 + 十 tests
 * 1-13), as ONE sequential GameTest: the runtime manager's store is global
 * static state and GameTest runs tests within a batch concurrently, so the
 * multi-tick AI flow must not race itself. MockModelProvider keeps every
 * step deterministic - real API calls are NOT a test prerequisite.
 *
 * <p>Outcomes are matched by CONTENT (last match wins), never by index:
 * earlier async turns (rate-limited queries) can land between steps.</p>
 */
public class WorldForgeAgentTests {

	private record Sink(List<AgentBridge.AgentOutcome> got) implements java.util.function.BiConsumer<ServerPlayerEntity, AgentBridge.AgentOutcome> {
		@Override
		public void accept(ServerPlayerEntity p, AgentBridge.AgentOutcome o) {
			got.add(o);
		}
	}

	private static AgentConfig cfg(int rate) {
		return new AgentConfig(true, "http://mock", "mock", "sk-test", 5, 100, 0, rate, 12, 300);
	}

	private static AgentBridge.AgentOutcome findLast(Sink sink, Predicate<AgentBridge.AgentOutcome> p) {
		var list = sink.got();
		for (int i = list.size() - 1; i >= 0; i--) {
			if (p.test(list.get(i))) {
				return list.get(i);
			}
		}
		return null;
	}

	private static void awaitMatch(TestContext ctx, Sink sink, Predicate<AgentBridge.AgentOutcome> p,
			String what, Consumer<AgentBridge.AgentOutcome> then) {
		ctx.waitAndRun(60, () -> {
			var o = findLast(sink, p);
			if (o == null) {
				var g = sink.got();
				String last = g.isEmpty() ? "(empty)" : ("ok=" + g.get(g.size() - 1).ok()
						+ " text=" + g.get(g.size() - 1).text()
						+ " proposal=" + (g.get(g.size() - 1).proposal() == null ? "null" : g.get(g.size() - 1).proposal().getClass().getSimpleName()));
				ctx.assertTrue(false, what + " outcome missing; n=" + g.size() + " last=" + last);
				return;
			}
			then.accept(o);
		});
	}

	// long chain: 8 nested async waits; give it 10 minutes of game ticks
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-ai", tickLimit = 1200)
	public void testAgentBridgeEndToEnd(TestContext ctx) {
		final java.nio.file.Path dir;
		try {
			dir = Files.createTempDirectory("wf-agent");
		} catch (Exception e) {
			ctx.assertTrue(false, "tempdir: " + e);
			return;
		}
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		var server = ctx.getWorld().getServer();
		ServerPlayerEntity op = ctx.createMockCreativeServerPlayerInWorld();
		server.getPlayerManager().getOpList().add(new net.minecraft.server.OperatorEntry(op.getGameProfile(), 2, false));
		var sink = new Sink(new CopyOnWriteArrayList<>());
		AgentBridge.OUTCOME_SINK = sink;

		// ---------- step 1 (test 9): no config -> clear guidance, no crash ----------
		AgentBridge.attachProviderForTesting(null, AgentConfig.DISABLED);
		ctx.assertTrue(!AgentBridge.available(), "bridge reports unavailable without config");
		AgentBridge.chat(server, op, "你好");
		ctx.assertTrue(!sink.got().isEmpty() && !sink.got().get(0).ok()
						&& sink.got().get(0).text().contains("agent.json"),
				"disabled AI must explain how to enable, not crash");

		// ---------- step 2: rate limit refuses beyond the per-minute cap ----------
		AgentBridge.attachProviderForTesting(MockModelProvider.canned("{\"kind\":\"query\",\"what\":\"x\"}"), cfg(3));
		int base = sink.got().size();
		for (int i = 0; i < 5; i++) {
			AgentBridge.chat(server, op, "刷屏 " + i);
		}
		long limited = sink.got().subList(base, sink.got().size()).stream()
				.filter(o -> !o.ok() && o.text().contains("频繁")).count();
		ctx.assertTrue(limited == 2, "requests beyond the cap must be refused, got " + limited);

		// ---------- step 3 (tests 1,4,11): create -> preview -> confirm -> persist ----------
		AgentBridge.attachProviderForTesting(MockModelProvider.canned(
				"{\"kind\":\"create\",\"id\":\"worldforge:ai_thunder\",\"name\":\"雷霆之剑\",\"description\":\"右键落雷\","
						+ "\"texture\":\"minecraft:item/diamond_sword\",\"damage\":12,\"max_durability\":500,"
						+ "\"behaviors\":[{\"trigger\":\"right_click\",\"action\":\"summon_lightning\",\"cooldown_ticks\":100,\"parameters\":{\"range\":12}}]}"),
				cfg(30));
		AgentBridge.chat(server, op, "创建雷霆之剑，攻击力12，右键召唤闪电，冷却5秒");
		awaitMatch(ctx, sink, x -> x.proposal() instanceof Proposal.Create && ((Proposal.Create) x.proposal()).id().equals("worldforge:ai_thunder"),
				"create", o -> {
			ctx.assertTrue(o.ok() && o.diffSummary().contains("创建新物品") && o.diffSummary().contains("雷霆之剑"),
					"preview must describe the change: " + o.diffSummary());
			ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:ai_thunder") == null,
					"phase A must NOT mutate the world before confirmation");
			var r = AgentBridge.confirmProposal(server, op, o.proposalId());
			ctx.assertTrue(r.ok(), "confirm must succeed: " + r.message());
			var def = WorldForgeRuntimeManager.get("worldforge:ai_thunder");
			ctx.assertTrue(def != null && def.damage() == 12.0 && def.behaviors().size() == 1
					&& def.behaviors().get(0).cooldownTicks() == 100, "definition applied exactly as proposed");
			try {
				ctx.assertTrue(Files.readString(dir.resolve("items.json")).contains("ai_thunder"),
						"persisted to the world file");
			} catch (Exception e) {
				ctx.assertTrue(false, "file: " + e);
			}
			var r2 = AgentBridge.confirmProposal(server, op, o.proposalId());
			ctx.assertTrue(r2.message().contains("重复确认已忽略"), "replay idempotent: " + r2.message());
			ctx.assertTrue(WorldForgeRuntimeManager.list().stream().filter(d -> d.id().equals("worldforge:ai_thunder")).count() == 1,
					"exactly one definition after double confirm");

			// ---------- step 4 (test 5): cancel -> zero world change ----------
			AgentBridge.attachProviderForTesting(MockModelProvider.canned(
					"{\"kind\":\"create\",\"id\":\"worldforge:ghost\",\"name\":\"G\",\"damage\":1,\"max_durability\":10}"),
					cfg(30));
			AgentBridge.chat(server, op, "再造个幽灵剑");
			awaitMatch(ctx, sink, x -> x.proposal() instanceof Proposal.Create && ((Proposal.Create) x.proposal()).id().equals("worldforge:ghost"),
					"ghost", res2 -> {
				var c = AgentBridge.cancelProposal(op, res2.proposalId());
				ctx.assertTrue(c.ok(), "cancel must ack");
				ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:ghost") == null, "cancelled = no trace");
				try {
					ctx.assertTrue(!Files.readString(dir.resolve("items.json")).contains("ghost"), "file untouched");
				} catch (Exception e) {
					ctx.assertTrue(false, "file: " + e);
				}

				// ---------- step 5 (test 3): unsupported skill reported ----------
				AgentBridge.attachProviderForTesting(MockModelProvider.canned(
						"{\"kind\":\"create\",\"id\":\"worldforge:wing\",\"name\":\"翅膀\",\"damage\":1,\"max_durability\":10,"
								+ "\"behaviors\":[{\"trigger\":\"right_click\",\"action\":\"fly\"}]}"),
						cfg(30));
				AgentBridge.chat(server, op, "给我一对翅膀能飞");
				awaitMatch(ctx, sink, x -> !x.ok() && x.capabilityGaps().stream().anyMatch(g -> g.contains("fly")),
						"capability gap", res3 -> {
					ctx.assertTrue(res3.proposal() == null, "unsupported action must block the proposal");
					ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:wing") == null, "nothing created");

					// ---------- step 6 (test 2): garbage model output is safe ----------
					AgentBridge.attachProviderForTesting(MockModelProvider.canned("抱歉，我无法处理 {broken json"), cfg(30));
					AgentBridge.chat(server, op, "随便说点什么");
					awaitMatch(ctx, sink, x -> x.ok() && x.proposal() == null && x.text().contains("抱歉"),
							"garbage fallback", res4 -> {
						ctx.assertTrue(WorldForgeRuntimeManager.list().size() == 1, "world untouched by garbage reply");

						// ---------- step 7 (test 8): provider failure surfaces cleanly ----------
						AgentBridge.attachProviderForTesting(new MockModelProvider((s, u) -> {
							throw new RuntimeException("simulated network failure");
						}), cfg(30));
						AgentBridge.chat(server, op, "测试网络故障");
						awaitMatch(ctx, sink, x -> !x.ok() && x.text().contains("simulated network failure"),
								"provider failure", res5 -> {

							// ---------- step 8 (tests 6+7): conversational update, delta-safe ----------
							AgentBridge.attachProviderForTesting(MockModelProvider.canned(
									"{\"kind\":\"update\",\"id\":\"worldforge:ai_thunder\",\"damage\":20,"
											+ "\"behaviors\":[{\"trigger\":\"right_click\",\"action\":\"summon_lightning\",\"cooldown_ticks\":40,\"parameters\":{\"range\":12}}]}"),
									cfg(30));
							AgentBridge.chat(server, op, "把伤害改成20，冷却改成2秒");
							awaitMatch(ctx, sink, x -> x.proposal() instanceof Proposal.Update && ((Proposal.Update) x.proposal()).damage() != null && ((Proposal.Update) x.proposal()).damage() == 20.0,
									"update 20", res6 -> {
								ctx.assertTrue(res6.diffSummary().contains("12") && res6.diffSummary().contains("20"),
										"diff must show old->new damage: " + res6.diffSummary());
								var r6 = AgentBridge.confirmProposal(server, op, res6.proposalId());
								ctx.assertTrue(r6.ok(), r6.message());
								var def6 = WorldForgeRuntimeManager.get("worldforge:ai_thunder");
								ctx.assertTrue(def6.damage() == 20.0, "damage 20");
								ctx.assertTrue(def6.behaviors().get(0).cooldownTicks() == 40, "cooldown 2s");
								ctx.assertTrue(Math.abs(def6.attributes().get("max_durability") - 500.0) < 0.001,
										"max_durability untouched (test 7)");
								ctx.assertTrue("雷霆之剑".equals(def6.name()), "name untouched");

								// ---------- step 9: conflict refuses blind overwrite ----------
								AgentBridge.attachProviderForTesting(MockModelProvider.canned(
										"{\"kind\":\"update\",\"id\":\"worldforge:ai_thunder\",\"damage\":30}"), cfg(30));
								AgentBridge.chat(server, op, "把伤害改30");
								awaitMatch(ctx, sink, x -> x.proposal() instanceof Proposal.Update && ((Proposal.Update) x.proposal()).damage() != null && ((Proposal.Update) x.proposal()).damage() == 30.0,
										"update 30", res7 -> {
									// someone else edits the item between preview and confirm
									updateThunder(33.0);
									var r7 = AgentBridge.confirmProposal(server, op, res7.proposalId());
									ctx.assertTrue(!r7.ok() && r7.message().contains("冲突"),
											"stale preview must conflict: " + r7.message());
									ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:ai_thunder").damage() == 33.0,
											"conflict must NOT overwrite the newer edit");

									// ---------- step 10 (test 12): session isolation ----------
									AgentBridge.releasePlayer(op.getUuid());
									var s2 = AgentBridge.sessionFor(op.getUuid());
									ctx.assertTrue(s2.history().isEmpty() && s2.focusItem() == null,
											"released session must not leak (cross-world rule)");

									// ---------- step 11 (test 13): reload keeps AI-made definitions ----------
									var rr = WorldForgeRuntimeManager.reload();
									ctx.assertTrue(rr.ok(), "strict reload of the AI-modified file must succeed: " + rr.error());
									ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:ai_thunder").damage() == 33.0,
											"definition survives reload exactly as on disk");

									// ---------- step 12 (test 10): non-OP cannot execute ----------
									ServerPlayerEntity nonOp = ctx.createMockCreativeServerPlayerInWorld();
									AgentBridge.attachProviderForTesting(MockModelProvider.canned(
											"{\"kind\":\"create\",\"id\":\"worldforge:hacked\",\"name\":\"H\",\"damage\":999,\"max_durability\":10}"),
											cfg(30));
									AgentBridge.chat(server, nonOp, "造个破坏平衡的剑");
									awaitMatch(ctx, sink, x -> x.proposal() instanceof Proposal.Create && ((Proposal.Create) x.proposal()).id().equals("worldforge:hacked"),
											"non-op create", res8 -> {
										try {
											var r8 = AgentBridge.confirmProposal(server, nonOp, res8.proposalId());
											ctx.assertTrue(!r8.ok() && r8.message().contains("权限"),
													"confirm must require OP2: " + r8.message());
											ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:hacked") == null,
													"nothing created by non-OP");
										} finally {
											AgentBridge.OUTCOME_SINK = null;
											AgentBridge.attachProviderForTesting(null, AgentConfig.DISABLED);
											disconnectQuiet(op);
											ctx.complete();
										}
									});
								});
							});
						});
					});
				});
			});
		});
	}

	/** direct manager update (simulates a concurrent editor, bypassing AI) */
	private static void updateThunder(double damage) {
		var cur = WorldForgeRuntimeManager.get("worldforge:ai_thunder");
		Map<String, Double> attrs = new java.util.LinkedHashMap<>(cur.attributes());
		attrs.put("damage", damage);
		WorldForgeRuntimeManager.updateItem(new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				cur.id(), cur.name(), cur.description(), cur.texture(), Map.copyOf(attrs),
				List.of(new BehaviorDefinition("right_click", List.of("summon_lightning"),
						Map.of("range", 12.0), 40))));
	}

	private static void disconnectQuiet(ServerPlayerEntity p) {
		try {
			p.networkHandler.disconnect(Text.literal("ai test done"));
		} catch (Exception ignored) {
		}
	}
}
