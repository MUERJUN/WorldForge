package dev.worldforge.ai;

import dev.worldforge.WorldForge;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.tool.WorldForgeTools;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server-side AI agent bridge (v0.3.0 core).
 *
 * <p>Trust boundaries (spec 五/九):</p>
 * <ul>
 *   <li>Model output is ONLY ever a proposal - it can validate but never
 *       mutate. Execution happens exclusively in {@link #confirmProposal}
 *       after the PLAYER clicked 确认, and re-runs every check server-side:
 *       OP permission (same threshold as /wf writes), proposal validity,
 *       capability whitelist, and a BASE-VERSION CONFLICT check so an item
 *       edited by someone else in the meantime triggers "重新预览" instead
 *       of a blind overwrite.</li>
 *   <li>Idempotency: each pending proposal carries a proposalId; confirming
 *       an already-executed id replays the stored result instead of
 *       creating a second item (timeout-retry safe, spec 五).</li>
 *   <li>Rate limiting per player, hard step budget per turn, proposal TTL,
 *       session isolation per world start (cleared on stop/disconnect,
 *       spec 四.5 / test 12).</li>
 *   <li>Model text and player input are data, never instructions: the system
 *       prompt is built from the static capability contract only; nothing
 *       from tool results is re-injected as system content.</li>
 * </ul>
 *
 * <p>All public methods must be called on the server thread except
 * {@link #chat}, which internally completes on the model's own executor and
 * hops back via {@code server.execute}.</p>
 */
public final class AgentBridge {

	/** the JSON contract the model must follow; also the whitelist documentation */
	public static final String SYSTEM_PROMPT = """
			你是 WorldForge 物品编辑器助手。你只能通过输出一个 JSON 提案来改变游戏，永远不要声称已经创建成功——提案需玩家确认后由服务端执行。
			当前支持的能力（白名单，超出即拒绝）：
			- 触发器 trigger: right_click | hit_entity
			- 动作 action: summon_lightning(参数 range 2-32) | apply_effect(effect 效果ID, duration ≤72000, amplifier ≤10, target self|target) | heal(amount ≤20) | experience(amount ≤1000) | teleport(distance ≤64) | explosion(power 0.1-4, fire 布尔) | ignite(seconds ≤10)
			- 冷却 cooldown_ticks: 0-72000（20 tick = 1 秒）
			- 外观 texture: 仅限原版槽位目录: minecraft:item/diamond_sword, golden_sword, iron_sword, stone_sword, blaze_rod, ender_eye, gunpowder, nether_star, heart_of_the_sea, emerald, diamond, experience_bottle, sugar, rabbit_foot, stick
			- 属性: damage(-1000..1000), max_durability(1..65536), attack_speed(-10..10)
			方块能力（v0.4.0，与物品是不同类型）：
			- 外观 appearance 仅限原版方块目录: stone/oak_planks/furnace/crafting_table/iron_block/gold_block/emerald_block/amethyst_block/redstone_lamp/bookshelf/barrel/deepslate_tiles
			- 类型 category: decoration | interactive | periodic（periodic 的 tick_interval_ticks 必须 ≥20）
			- 方块交互动作: apply_effect/heal/experience/ignite/give_item/sound/particles
			- 方块提案格式:
			  创建: {"kind":"create_block","id":"worldforge:xxx","name":"..","appearance":"furnace","category":"periodic","tick_interval_ticks":200,"output_item":"minecraft:iron_ingot","output_count":1,"behaviors":[..]}
			  修改(差量): {"kind":"update_block","id":"worldforge:xxx","appearance?":"..","tick_interval_ticks?":..}
			  删除: {"kind":"delete_block","id":"worldforge:xxx"}
			- 重要：物品与方块即使 id 相同也绝不能互相修改；修改方块必须用 *_block 提案。
			输出格式（只输出一个 JSON 对象，不要多余文字）：
			创建: {"kind":"create","id":"worldforge:xxx","name":"..","description":"..","texture":"minecraft:item/..","damage":12,"max_durability":500,"behaviors":[{"trigger":"right_click","action":"summon_lightning","cooldown_ticks":100,"parameters":{}}]}
			修改(差量，未写字段保持不变): {"kind":"update","id":"worldforge:xxx","name?":"..","damage?":20,"add_behaviors":[..],"set_behaviors?":[..],"remove_triggers?":[..]}
			删除: {"kind":"delete","id":"worldforge:xxx"}
			纯问答: {"kind":"query","what":".."}
			规则：id 用小写 namespace:path；修改前先确认上下文列表里有该物品；用户说“它/这个”指 focus_item；冷却按秒换算成 tick（5秒=100tick）；不支持的能力在回复中如实说明并给出可行替代提案。""";

	public record PendingProposal(String proposalId, Proposal proposal, String replyText,
			String baseSnapshotJson, long createdAtMillis) {
	}

	/** result handed to the GUI: either assistant text, or a validated proposal preview */
	public record AgentOutcome(boolean ok, String text, String proposalId, Proposal proposal,
			List<String> capabilityGaps, String diffSummary) {
	}

	/** callback delivered on the server thread */
	public interface OutcomeListener {
		void accept(AgentOutcome outcome);
	}

	private static volatile AgentConfig config = AgentConfig.DISABLED;
	private static volatile ModelProvider provider = null;
	/**
	 * v0.5.6 P1: bumped on every config (re)load. Async AI callbacks capture
	 * the epoch at request time and re-check it inside server.execute — a
	 * response computed with retired credentials can never write into the new
	 * session, create a proposal, or overwrite the player's UI state.
	 */
	private static volatile long configEpoch = 0;
	private static final Map<UUID, AgentSession> SESSIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, PendingProposal> PENDING = new ConcurrentHashMap<>();
	/** executed proposal ids -> stored result for idempotent replays */
	private static final Map<String, AgentOutcome> EXECUTED = new ConcurrentHashMap<>();
	private static final Map<UUID, AtomicInteger> RATE = new ConcurrentHashMap<>();
	private static volatile long rateWindowStart = System.currentTimeMillis();

	public static final int MAX_STEPS_PER_TURN = 1; // one proposal per user turn (v1)
	public static final int MAX_PENDING = 64;

	public static void reloadConfig(MinecraftServer server) {
		// Fabric-idiomatic: config/worldforge/agent.json in the instance dir
		// (dedicated server => its own config folder; keys stay server-side).
		java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
				.resolve("worldforge").resolve("agent.json");
		AgentConfig newCfg = AgentConfig.load(file);
		if (!java.nio.file.Files.exists(file)) {
			AgentConfig.writeTemplate(file);
			WorldForge.LOGGER.info("[WorldForge] agent config template written to {}", file);
		}
		// v0.5.5: retire the OLD provider FIRST — cancel its in-flight requests
		// and release its executor — so a hot config swap can never leave
		// requests running against stale credentials/endpoints.
		var old = provider;
		if (old != null) {
			try {
				old.cancelAll();
				if (old instanceof OpenAICompatibleProvider oo) {
					oo.shutdown();
				}
			} catch (Throwable t) {
				WorldForge.LOGGER.warn("[WorldForge] old AI provider shutdown issue", t);
			}
		}
		config = newCfg;
		configEpoch++; // v0.5.6: invalidate every in-flight callback
		if (newCfg.usable()) {
			provider = new OpenAICompatibleProvider(newCfg);
			WorldForge.LOGGER.info("[WorldForge] AI agent enabled: {}", newCfg); // masked
		} else {
			provider = null;
			WorldForge.LOGGER.info("[WorldForge] AI agent disabled (no key or enabled=false)");
		}
	}

	/** test/embedding hook: swap in a deterministic provider (Mock) */
	public static void attachProviderForTesting(ModelProvider p, AgentConfig cfg) {
		provider = p;
		config = cfg;
		configEpoch++; // same invalidation semantics as a real reload
	}

	/** v0.5.6 test hook: reset only the rate window (keeps sessions/pending). */
	public static void clearRateForTest() {
		RATE.clear();
		rateWindowStart = System.currentTimeMillis();
	}

	/** v0.5.6 test hook: current config generation. */
	public static long configEpochForTest() {
		return configEpoch;
	}

	public static boolean available() {
		return provider != null && config.usable();
	}

	public static AgentConfig config() {
		return config;
	}

	public static String providerDescription() {
		var p = provider;
		return p == null ? null : p.describe();
	}

	public static AgentSession sessionFor(UUID player) {
		return SESSIONS.computeIfAbsent(player, k -> new AgentSession(Math.max(2, config.historyLimit())));
	}

	/** drop everything a player had (disconnect / world stop): no cross-world reuse */
	public static void releasePlayer(UUID player) {
		SESSIONS.remove(player);
		PENDING.remove(player);
		RATE.remove(player);
	}

	public static void releaseAll() {
		SESSIONS.clear();
		PENDING.clear();
		EXECUTED.clear();
		RATE.clear();
		var p = provider;
		if (p != null) {
			p.cancelAll();
			if (p instanceof OpenAICompatibleProvider o) {
				o.shutdown();
			}
		}
		provider = null;
	}

	// ---------- rate limiting ----------

	private static boolean rateLimited(UUID player) {
		long now = System.currentTimeMillis();
		if (now - rateWindowStart > 60_000) {
			rateWindowStart = now;
			RATE.clear();
		}
		return RATE.computeIfAbsent(player, k -> new AtomicInteger()).incrementAndGet()
				> Math.max(1, config.rateLimitPerMinute());
	}

	// ---------- phase A: chat -> proposal (never mutates) ----------

	public static void chat(MinecraftServer server, ServerPlayerEntity player, String message) {
		UUID pid = player.getUuid();
		if (!available()) {
			deliverNow(player, new AgentOutcome(false, "AI 未启用：服务器未配置 config/worldforge/agent.json（需要 apiKey）。可视化编辑器不受影响。",
					null, null, List.of(), null));
			return;
		}
		if (rateLimited(pid)) {
			deliverNow(player, new AgentOutcome(false, "AI 请求过于频繁（每分钟上限 " + config.rateLimitPerMinute() + "），请稍后再试。",
					null, null, List.of(), null));
			return;
		}
		var session = sessionFor(pid);
		session.beginTurn();
		if (!session.stepAllowed(MAX_STEPS_PER_TURN)) {
			deliverNow(player, new AgentOutcome(false, "已达本轮步骤上限。", null, null, List.of(), null));
			return;
		}
		session.addUser(message);
		String userMsg = session.buildUserMessage(message);
		var p = provider;
		// v0.5.6: bind this request to the current config generation
		final long requestEpoch = configEpoch;
		p.complete(SYSTEM_PROMPT, userMsg).whenComplete((reply, err) -> server.execute(() -> {
			// back on the server thread; player may have left meanwhile
			// v0.5.6: stale-epoch check — a response computed with retired
			// credentials must be dropped silently (the UI was already
			// restored by the config switch that retired them)
			if (requestEpoch != configEpoch) {
				WorldForge.LOGGER.info("[WorldForge] dropped stale AI response (config epoch {} != {})",
						requestEpoch, configEpoch);
				return;
			}
			if (err != null) {
				deliverNow(player, new AgentOutcome(false, "模型调用失败：" + rootCause(err) + "（可视化编辑器仍可用）",
						null, null, List.of(), null));
				return;
			}
			session.addAssistant(reply);
			var parsed = ProposalJson.parse(reply);
			if (!parsed.ok()) {
				// not a proposal: plain conversational answer (query/clarification)
				deliverNow(player, new AgentOutcome(true, stripJsonNoise(reply), null, null, List.of(), null));
				return;
			}
			var proposal = parsed.proposal();
			var gaps = ProposalValidator.capabilityGaps(proposal);
			var errs = ProposalValidator.validate(proposal);
			if (!errs.ok() || !gaps.isEmpty()) {
				var sb = new StringBuilder("提案未通过校验：");
				sb.append(errs.joined());
				gaps.forEach(g -> sb.append(" | 能力限制: ").append(g));
				deliverNow(player, new AgentOutcome(false, sb.toString(), null, null, gaps, null));
				return;
			}
			// store pending + base snapshot for conflict detection
			String proposalId = pid + "#" + System.nanoTime();
			String base = switch (proposal) {
				case Proposal.Update u -> snapshotOf(u.id());
				case Proposal.Delete d -> snapshotOf(d.id());
				case Proposal.UpdateBlock b -> blockSnapshotOf(b.id());
				case Proposal.DeleteBlock b -> blockSnapshotOf(b.id());
				default -> "";
			};
			if (PENDING.size() > MAX_PENDING) {
				PENDING.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().createdAtMillis()
						> config.proposalTtlSeconds() * 1000L);
			}
			PENDING.put(pid, new PendingProposal(proposalId, proposal, stripJsonNoise(reply), base,
					System.currentTimeMillis()));
			if (proposal instanceof Proposal.Create c) {
				session.setFocusItem(c.id());
			} else if (proposal instanceof Proposal.Update u) {
				session.setFocusItem(u.id());
			} else if (proposal instanceof Proposal.CreateBlock cb) {
				session.setFocusBlock(cb.id());
			} else if (proposal instanceof Proposal.UpdateBlock ub) {
				session.setFocusBlock(ub.id());
			}
			deliverNow(player, new AgentOutcome(true, stripJsonNoise(reply), proposalId, proposal,
					List.of(), ProposalDiff.describe(proposal)));
		}));
	}

	/** result of executing a block proposal */
	private record BlockExec(boolean ok, String message, String itemId) {
	}

	/** snapshot of a block definition, for the conflict check */
	private static String blockSnapshotOf(String id) {
		dev.worldforge.block.BlockDefinition d = dev.worldforge.block.BlockRuntimeManager.get(id);
		return d == null ? "" : dev.worldforge.block.BlockDefinitionStore.toJson(d).toString();
	}

	/**
	 * Execute a block proposal through the unified block tool layer.
	 *
	 * @return null when the proposal is not a block proposal
	 */
	private static BlockExec executeBlockProposal(MinecraftServer server, Proposal p) {
		return switch (p) {
			case Proposal.CreateBlock cb -> {
				var def = new dev.worldforge.block.BlockDefinition(
						dev.worldforge.block.BlockDefinition.CURRENT_VERSION, cb.id(), cb.name(),
						cb.description() == null ? "" : cb.description(),
						cb.appearance() == null ? dev.worldforge.block.BlockCatalog.DEFAULT_APPEARANCE
								: cb.appearance(),
						parseCategory(cb.category()),
						cb.hardness() == null ? 2.0 : cb.hardness(),
						cb.behaviors() == null ? List.of()
								: cb.behaviors().stream().map(ProposalValidator::toBehavior).toList(),
						cb.tickIntervalTicks() == null ? 200 : cb.tickIntervalTicks(),
						cb.outputItemId(), cb.outputCount() == null ? 1 : cb.outputCount());
				var r = dev.worldforge.tool.WorldForgeBlockTools.createBlock(server, def);
				yield new BlockExec(r.ok(), r.ok()
						? "已通过 AI 提案创建方块 " + cb.name() + "（" + cb.id() + "）" : r.message(), cb.id());
			}
			case Proposal.UpdateBlock ub -> {
				var cur = dev.worldforge.block.BlockRuntimeManager.get(ub.id());
				if (cur == null) {
					yield new BlockExec(false, "方块定义不存在：" + ub.id(), ub.id());
				}
				// delta merge: unset fields keep their current values
				var merged = new dev.worldforge.block.BlockDefinition(
						cur.version(), cur.id(),
						ub.name() == null ? cur.name() : ub.name(),
						ub.description() == null ? cur.description() : ub.description(),
						ub.appearance() == null ? cur.appearance() : ub.appearance(),
						ub.category() == null ? cur.category() : parseCategory(ub.category()),
						ub.hardness() == null ? cur.hardness() : ub.hardness(),
						cur.behaviors(),
						ub.tickIntervalTicks() == null ? cur.tickIntervalTicks() : ub.tickIntervalTicks(),
						ub.outputItemId() == null ? cur.outputItemId() : ub.outputItemId(),
						ub.outputCount() == null ? cur.outputCount() : ub.outputCount());
				var r = dev.worldforge.tool.WorldForgeBlockTools.updateBlock(server, merged);
				yield new BlockExec(r.ok(), r.ok()
						? "已通过 AI 提案更新方块 " + ub.id() + "（已放置方块立即使用新配置）" : r.message(), ub.id());
			}
			case Proposal.DeleteBlock db -> {
				var r = dev.worldforge.tool.WorldForgeBlockTools.deleteBlock(server, db.id());
				yield new BlockExec(r.ok(), r.ok()
						? "已通过 AI 提案删除方块 " + db.id() + "（已放置方块安全失效）" : r.message(), db.id());
			}
			default -> null;
		};
	}

	private static dev.worldforge.block.BlockDefinition.Category parseCategory(String category) {
		try {
			return dev.worldforge.block.BlockDefinition.Category.valueOf(
					category == null ? "DECORATION" : category.toUpperCase(java.util.Locale.ROOT));
		} catch (Exception e) {
			return dev.worldforge.block.BlockDefinition.Category.DECORATION;
		}
	}

	private static String snapshotOf(String id) {
		ItemDefinition d = WorldForgeRuntimeManager.get(id);
		return d == null ? "" : DefinitionStore.toJson(d).toString();
	}

	// ---------- phase B: player confirm -> execute ----------

	public record ConfirmResult(boolean ok, String message, String proposalId, String itemId) {

		ConfirmResult(boolean ok, String message, String proposalId) {
			this(ok, message, proposalId, "");
		}
	}

	public static ConfirmResult confirmProposal(MinecraftServer server, ServerPlayerEntity player, String proposalId) {
		UUID pid = player.getUuid();
		if (proposalId == null || proposalId.isBlank()) {
			return new ConfirmResult(false, "缺少提案 ID", "");
		}
		if (!WorldForgeStudioAccess.hasEditPermission(player)) {
			return new ConfirmResult(false, "权限不足：需要 OP2 才能通过 AI 修改世界定义", proposalId);
		}
		// idempotent replay of an already-executed proposal
		var done = EXECUTED.get(proposalId);
		if (done != null) {
			return new ConfirmResult(done.ok(), "（重复确认已忽略）" + done.text(), proposalId);
		}
		PendingProposal pending = PENDING.get(pid);
		if (pending == null || !pending.proposalId().equals(proposalId)) {
			return new ConfirmResult(false, "提案不存在或已过期，请重新描述需求", proposalId);
		}
		if (System.currentTimeMillis() - pending.createdAtMillis() > config.proposalTtlSeconds() * 1000L) {
			PENDING.remove(pid);
			return new ConfirmResult(false, "提案已超时作废，请重新生成", proposalId);
		}
		// conflict check: base version must still match (spec 五)
		String current = switch (pending.proposal()) {
			case Proposal.Update u -> snapshotOf(u.id());
			case Proposal.Delete d -> snapshotOf(d.id());
			case Proposal.UpdateBlock b -> blockSnapshotOf(b.id());
			case Proposal.DeleteBlock b -> blockSnapshotOf(b.id());
			default -> "";
		};
		if (!pending.baseSnapshotJson().equals(current)) {
			PENDING.remove(pid);
			return new ConfirmResult(false, "冲突：该物品在你预览后已被其他操作修改，提案作废，请重新预览", proposalId);
		}
		// execute through the ONE tools pipeline (rollback + broadcast included)
		// v0.4.0: BLOCK proposals are executed through the block tool layer, never
		// through the item pipeline - a shared virtual id cannot cross types.
		BlockExec blockExec = executeBlockProposal(server, pending.proposal());
		if (blockExec != null) {
			PENDING.remove(pid);
			if (blockExec.ok()) {
				EXECUTED.put(proposalId, new AgentOutcome(true, blockExec.message(), proposalId,
						null, List.of(), null));
			}
			WorldForgePayloads.syncToAll(server);
			return new ConfirmResult(blockExec.ok(), blockExec.message(), proposalId, blockExec.itemId());
		}
		WorldForgeTools.ToolResult r = switch (pending.proposal()) {
			case Proposal.Create c -> WorldForgeTools.createItem(server, ProposalJson.toDefinition(c));
			case Proposal.Update u -> {
				ItemDefinition cur = WorldForgeRuntimeManager.get(u.id());
				yield cur == null
						? new WorldForgeTools.ToolResult(false, false, WorldForgeTools.ToolResult.SyncStatus.NONE, "no such item")
						: WorldForgeTools.updateItem(server, ProposalJson.mergeUpdate(cur, u));
			}
			case Proposal.Delete d -> WorldForgeTools.deleteItem(server, d.id());
			case Proposal.Query q -> new WorldForgeTools.ToolResult(true, true, WorldForgeTools.ToolResult.SyncStatus.NONE, "query");
			// block kinds returned above and never reach the item pipeline
			case Proposal.CreateBlock cb -> new WorldForgeTools.ToolResult(false, false,
					WorldForgeTools.ToolResult.SyncStatus.NONE, "block proposals use the block tools");
			case Proposal.UpdateBlock ub -> new WorldForgeTools.ToolResult(false, false,
					WorldForgeTools.ToolResult.SyncStatus.NONE, "block proposals use the block tools");
			case Proposal.DeleteBlock db -> new WorldForgeTools.ToolResult(false, false,
					WorldForgeTools.ToolResult.SyncStatus.NONE, "block proposals use the block tools");
		};
		PENDING.remove(pid);
		String itemId = switch (pending.proposal()) {
			case Proposal.Create c -> c.id();
			case Proposal.Update u -> u.id();
			case Proposal.Delete d -> d.id();
			case Proposal.Query q -> "";
			case Proposal.CreateBlock cb -> cb.id();
			case Proposal.UpdateBlock ub -> ub.id();
			case Proposal.DeleteBlock db -> db.id();
		};
		String msg;
		if (r.ok()) {
			EXECUTED.put(proposalId, new AgentOutcome(true, r.message(), proposalId, null, List.of(), null));
			// keep EXECUTED bounded
			if (EXECUTED.size() > 256) {
				EXECUTED.keySet().stream().limit(64).toList().forEach(EXECUTED::remove);
			}
			msg = switch (pending.proposal()) {
				case Proposal.Create c -> "已通过 AI 提案创建 " + c.name() + "（" + c.id() + "）" + r.message();
				case Proposal.Update u -> "已通过 AI 提案更新 " + u.id() + "，背包存量物品已即时生效";
				case Proposal.Delete d -> "已通过 AI 提案删除 " + d.id() + "（存量物品进入失效状态）";
				default -> r.message();
			};
		} else {
			msg = "提案执行失败（未写入）：" + r.message();
		}
		WorldForgePayloads.syncToAll(server);
		return new ConfirmResult(r.ok(), msg, proposalId, itemId);
	}

	public static ConfirmResult cancelProposal(ServerPlayerEntity player, String proposalId) {
		UUID pid = player.getUuid();
		PendingProposal pending = PENDING.get(pid);
		if (pending != null && pending.proposalId().equals(proposalId)) {
			PENDING.remove(pid);
			return new ConfirmResult(true, "提案已取消，世界数据未发生任何变化", proposalId);
		}
		return new ConfirmResult(false, "无此待确认提案", proposalId);
	}

	// ---------- plumbing ----------

	private static void deliverNow(ServerPlayerEntity player, AgentOutcome o) {
		var sink = OUTCOME_SINK;
		if (sink != null) {
			sink.accept(player, o);
		}
	}

	/** production send hook (set by the network layer); tests observe here */
	public static volatile java.util.function.BiConsumer<ServerPlayerEntity, AgentOutcome> OUTCOME_SINK = null;

	private static String stripJsonNoise(String reply) {
		if (reply == null) {
			return "";
		}
		int brace = reply.indexOf('{');
		if (brace < 0) {
			return reply.length() > 400 ? reply.substring(0, 400) : reply;
		}
		String prose = reply.substring(0, brace).replaceAll("```json|```", "").trim();
		return prose.isEmpty() ? "（已生成提案，见预览）" : (prose.length() > 400 ? prose.substring(0, 400) : prose);
	}

	private static String rootCause(Throwable t) {
		Throwable c = t;
		while (c.getCause() != null && c.getCause() != c) {
			c = c.getCause();
		}
		String m = c.getMessage();
		return c.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
	}

	/** small indirection so ai/ does not import network/ (layering) */
	static final class WorldForgeStudioAccess {
		static boolean hasEditPermission(ServerPlayerEntity player) {
			return dev.worldforge.network.WorldForgeStudioProtocol.hasEditPermission(player);
		}
	}

	private AgentBridge() {
	}
}
