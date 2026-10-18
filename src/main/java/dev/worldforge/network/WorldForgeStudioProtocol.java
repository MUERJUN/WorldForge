package dev.worldforge.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.WorldForge;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import dev.worldforge.tool.WorldForgeTools;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * GUI / future-agent request protocol (v0.2.0).
 *
 * <p>C2S {@code StudioRequest(kind, requestId, payload)}: the client sends an
 * INTENT only. The server re-checks everything: size caps, permission (same
 * OP2 threshold as the /wf mutating commands - the GUI never grants more
 * authority than the command layer), identifier shape, numeric ranges and the
 * behavior whitelist (inside DefinitionValidator via WorldForgeTools), and
 * executes ONLY through {@link WorldForgeTools} (the same entry point the
 * commands use - one mutation pipeline for /wf, Studio and the future agent).</p>
 *
 * <p>Network-thread handlers never touch world state directly; everything is
 * scheduled onto the server thread with {@code server.execute} (spec 十一.6).</p>
 *
 * <p>S2C {@code StudioResponse(requestId, ok, phase, message, snapshot)}:
 * requestId echoes the request so the GUI can drop stale replies; phase maps
 * the three-phase ToolResult (persisted/applied/sync) honestly; snapshot is
 * the full definitions+tombstones JSON (same shape as the on-disk file) sent
 * point-to-point to the requester, so the client view converges after every
 * operation without trusting the client's local copy.</p>
 *
 * <p>Kinds: list | create | update | delete | give | resync.</p>
 */
public final class WorldForgeStudioProtocol {

	/** hard wire limits (spec 十一.3: 验证消息大小与字符串长度) */
	public static final int MAX_PAYLOAD_CHARS = 256 * 1024;
	public static final int MAX_ID_CHARS = 256;
	public static final int EDIT_PERMISSION_LEVEL = 2;

	public record StudioRequest(String kind, int requestId, String payload) implements CustomPayload {
		public static final Id<StudioRequest> ID = new Id<>(WorldForge.id("studio_request"));
		public static final PacketCodec<? super RegistryByteBuf, StudioRequest> CODEC =
				PacketCodec.tuple(
						PacketCodecs.STRING, StudioRequest::kind,
						PacketCodecs.VAR_INT, StudioRequest::requestId,
						PacketCodecs.STRING, StudioRequest::payload,
						StudioRequest::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public record StudioResponse(int requestId, boolean ok, String phase, String message, String snapshot, String data) implements CustomPayload {
		public static final Id<StudioResponse> ID = new Id<>(WorldForge.id("studio_response"));
		public static final PacketCodec<? super RegistryByteBuf, StudioResponse> CODEC =
				PacketCodec.tuple(
						PacketCodecs.VAR_INT, StudioResponse::requestId,
						PacketCodecs.BOOL, StudioResponse::ok,
						PacketCodecs.STRING, StudioResponse::phase,
						PacketCodecs.STRING, StudioResponse::message,
						PacketCodecs.STRING, StudioResponse::snapshot,
						PacketCodecs.STRING, StudioResponse::data,
						StudioResponse::new);

		public StudioResponse(int requestId, boolean ok, String phase, String message, String snapshot) {
			this(requestId, ok, phase, message, snapshot, "");
		}

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(StudioRequest.ID, StudioRequest.CODEC);
		PayloadTypeRegistry.playS2C().register(StudioResponse.ID, StudioResponse.CODEC);
		// AI outcomes arrive asynchronously; push them to the requesting player
		// as a synthetic response (requestId 0 = unsolicited agent event channel)
		dev.worldforge.ai.AgentBridge.OUTCOME_SINK = (player, outcome) -> {
			if (ServerPlayNetworking.canSend(player, StudioResponse.ID)) {
				ServerPlayNetworking.send(player, new StudioResponse(0, outcome.ok(), "agent",
						truncate(outcome.text(), 512), "", truncate(agentEnvelope(outcome), MAX_PAYLOAD_CHARS)));
			}
		};
		ServerPlayNetworking.registerGlobalReceiver(StudioRequest.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			MinecraftServer server = context.server();
			// wire guards BEFORE any parsing / thread hop
			if (payload.payload() == null || payload.payload().length() > MAX_PAYLOAD_CHARS
					|| payload.kind() == null || payload.kind().length() > 16) {
				reply(server, player, payload.requestId(), false, "rejected", "请求过大或格式非法", false);
				return;
			}
			// world-state access MUST happen on the server thread (spec 十一.6)
			server.execute(() -> handle(server, player, payload));
		});
	}

	// ---------- server-thread dispatch ----------

	/**
	 * Full request pipeline for one player. Public so the GameTest business
	 * suite can drive the exact production path (permission + validation +
	 * tools) with a mock player; callers must already be on the server thread.
	 */
	public static void handle(MinecraftServer server, ServerPlayerEntity player, StudioRequest req) {
		String kind = req.kind();
		try {
			switch (kind) {
				case "list" -> reply(server, player, req.requestId(), true, "ok", "list", true);
				case "create", "update", "delete" -> {
					if (!hasEditPermission(player)) {
						reply(server, player, req.requestId(), false, "permission",
								"需要管理员权限（OP2）才能修改全局定义", false);
						return;
					}
					var r = dispatchMutation(server, kind, req.payload());
					reply(server, player, req.requestId(), r.ok(), phaseOf(r), r.message(), true);
				}
				case "give" -> {
					// gives do not touch global definitions; same authority as
					// /wf give (non-OP allowed). Server still validates the id.
					var r = Kinds.give(player, req.payload());
					reply(server, player, req.requestId(), r.ok(), r.ok() ? "ok" : "rejected", r.message(), false);
				}
				case "resync" -> {
					WorldForgePayloads.syncTo(player);
					reply(server, player, req.requestId(), true, "ok", "resync-sent", true);
				}
				case "blocks_list" -> {
					reply(server, player, req.requestId(), true, "ok", "blocks", true);
				}
				case "block_create", "block_update", "block_delete" -> {
					if (!hasEditPermission(player)) {
						reply(server, player, req.requestId(), false, "permission",
								"需要管理员权限（OP2）才能修改方块定义", false);
						return;
					}
					var r = dispatchBlockMutation(server, kind, req.payload());
					reply(server, player, req.requestId(), r.ok(),
							r.ok() ? "ok" : (r.persisted() ? "applied-failed" : "rejected"),
							r.message(), true);
				}
				case "block_give" -> {
					String bid = optId(req.payload());
					var def = dev.worldforge.tool.WorldForgeBlockTools.getBlock(bid);
					if (def == null) {
						reply(server, player, req.requestId(), false, "rejected", "方块定义不存在：" + bid, false);
						return;
					}
					var r = dev.worldforge.tool.WorldForgeBlockTools.give(player, bid, 1);
					reply(server, player, req.requestId(), r.ok(), r.ok() ? "ok" : "rejected", r.message(), false);
				}
				case "agent_chat" -> {
					// AI is server-side by design; the player only sends text.
					// Non-OP players may still CHAT (read-only proposals), but
					// confirmProposal enforces OP2 before any mutation.
					if (!dev.worldforge.ai.AgentBridge.available()) {
						reply(server, player, req.requestId(), false, "rejected",
								"AI 未启用：服务器需配置 config/worldforge/agent.json", false);
						return;
					}
					String msg = req.payload() == null ? "" : truncate(req.payload(), 4000);
					if (msg.isBlank()) {
						reply(server, player, req.requestId(), false, "rejected", "消息为空", false);
						return;
					}
					// async: outcome arrives via OUTCOME_SINK with requestId 0;
					// ack now so the GUI can leave its "thinking" state
					reply(server, player, req.requestId(), true, "agent-accepted", "AI 正在思考…", false);
					dev.worldforge.ai.AgentBridge.chat(server, player, msg);
				}
				case "agent_confirm" -> {
					String pidId = optId(req.payload());
					var r = dev.worldforge.ai.AgentBridge.confirmProposal(server, player, pidId);
					JsonObject data = new JsonObject();
					data.addProperty("itemId", r.itemId() == null ? "" : r.itemId());
					replyData(server, player, req.requestId(), r.ok(), r.ok() ? "ok" : "rejected",
							r.message(), true, data.toString());
				}
				case "agent_cancel" -> {
					String pidId = optId(req.payload());
					var r = dev.worldforge.ai.AgentBridge.cancelProposal(player, pidId);
					reply(server, player, req.requestId(), r.ok(), r.ok() ? "ok" : "rejected", r.message(), false);
				}
				default -> reply(server, player, req.requestId(), false, "rejected", "未知请求类型: " + kind, false);
			}
		} catch (Exception e) {
			WorldForge.LOGGER.error("[WorldForge] studio request '{}' failed: {}", kind, e.toString());
			reply(server, player, req.requestId(), false, "server-error",
					"服务端内部错误: " + e.getClass().getSimpleName(), false);
		}
	}

	/** parse + shape-check the submitted definition, then hand to the tools */
	public static WorldForgeTools.ToolResult dispatchMutation(MinecraftServer server, String kind, String payload) {
		JsonObject body;
		try {
			body = parseBody(payload);
		} catch (IllegalArgumentException e) {
			return reject(e.getMessage());
		}
		// server-side field length caps (S2 business test found create/update
		// accepted 400-char ids: the caps existed only on delete/give paths)
		var idEl = body.get("id");
		if (idEl == null || !idEl.isJsonPrimitive() || idEl.getAsString().length() > MAX_ID_CHARS) {
			return reject("id 缺失或超过 " + MAX_ID_CHARS + " 字符上限");
		}
		for (String key : new String[]{"name", "description"}) {
			var el = body.get(key);
			if (el != null && el.isJsonPrimitive() && el.getAsString().length() > 1024) {
				return reject(key + " 超过 1024 字符上限");
			}
		}
		return switch (kind) {
			case "create" -> Kinds.create(server, body);
			case "update" -> Kinds.update(server, body);
			case "delete" -> Kinds.delete(server, body);
			default -> reject("unknown kind");
		};
	}

	/** block mutations: parse + validate + execute through the ONE block tool layer */
	public static dev.worldforge.tool.WorldForgeBlockTools.BlockToolResult dispatchBlockMutation(
			MinecraftServer server, String kind, String payload) {
		JsonObject body;
		try {
			body = parseBody(payload);
		} catch (IllegalArgumentException e) {
			return new dev.worldforge.tool.WorldForgeBlockTools.BlockToolResult(false, false,
					dev.worldforge.tool.WorldForgeBlockTools.SyncStatus.NONE, e.getMessage());
		}
		return switch (kind) {
			case "block_create" -> dev.worldforge.tool.WorldForgeBlockTools.createBlock(server,
					dev.worldforge.block.BlockDefinitionStore.fromJson(body));
			case "block_update" -> dev.worldforge.tool.WorldForgeBlockTools.updateBlock(server,
					dev.worldforge.block.BlockDefinitionStore.fromJson(body));
			case "block_delete" -> dev.worldforge.tool.WorldForgeBlockTools.deleteBlock(server,
					body.has("id") ? body.get("id").getAsString() : "");
			default -> new dev.worldforge.tool.WorldForgeBlockTools.BlockToolResult(false, false,
					dev.worldforge.tool.WorldForgeBlockTools.SyncStatus.NONE, "unknown block kind");
		};
	}

	static JsonObject parseBody(String payload) {
		if (payload == null || payload.isBlank()) {
			throw new IllegalArgumentException("请求体为空");
		}
		JsonElement el;
		try {
			el = JsonParser.parseString(payload);
		} catch (Exception e) {
			throw new IllegalArgumentException("请求体不是合法 JSON");
		}
		if (!el.isJsonObject()) {
			throw new IllegalArgumentException("请求体不是 JSON object");
		}
		return el.getAsJsonObject();
	}

	private static WorldForgeTools.ToolResult reject(String msg) {
		return new WorldForgeTools.ToolResult(false, false, WorldForgeTools.ToolResult.SyncStatus.NONE, msg);
	}

	private static String phaseOf(WorldForgeTools.ToolResult r) {
		return !r.persisted() ? "persistence-failed"
				: !r.applied() ? "applied-failed"
				: r.sync() == WorldForgeTools.ToolResult.SyncStatus.FAILED ? "sync-failed"
				: r.sync() == WorldForgeTools.ToolResult.SyncStatus.PARTIAL ? "sync-partial"
				: "ok";
	}

	public static boolean hasEditPermission(ServerPlayerEntity player) {
		return player.getServer().getPermissionLevel(player.getGameProfile()) >= EDIT_PERMISSION_LEVEL;
	}

	private static void reply(MinecraftServer server, ServerPlayerEntity player, int reqId,
			boolean ok, String phase, String message, boolean withSnapshot) {
		replyData(server, player, reqId, ok, phase, message, withSnapshot, "");
	}

	private static void replyData(MinecraftServer server, ServerPlayerEntity player, int reqId,
			boolean ok, String phase, String message, boolean withSnapshot, String data) {
		// v0.3.0 fix: a truncated snapshot is INVALID JSON and the client would
		// silently keep stale state. Never cut JSON mid-stream: when the
		// snapshot exceeds the wire cap, omit it and tell the player to refresh.
		String snapshot = "";
		String snapshotNote = "";
		if (withSnapshot) {
			String full = currentSnapshotJson();
			if (full.length() <= MAX_PAYLOAD_CHARS) {
				snapshot = full;
			} else {
				snapshotNote = "（快照过大已省略，请点刷新获取列表）";
			}
		}
		StudioResponse resp = new StudioResponse(reqId, ok, phase, truncate(message, 512) + snapshotNote,
				snapshot, truncate(data, 4096));
		// test hook: the business GameTest suite observes production responses
		// without a real client connection (mock players cannot "receive").
		var sink = RESPONSE_SINK;
		if (sink != null) {
			sink.accept(resp);
		}
		if (ServerPlayNetworking.canSend(player, StudioResponse.ID)) {
			ServerPlayNetworking.send(player, resp);
		} else if (!ok) {
			// vanilla client fallback: at least surface errors in chat
			player.sendMessage(Text.literal("§c[WorldForge] " + truncate(message, 200)), true);
		}
	}

	/** testing observation point (set only by the GameTest business suite) */
	public static volatile java.util.function.Consumer<StudioResponse> RESPONSE_SINK = null;

	/** definitions + tombstones in the exact on-disk JSON shape (client view convergence) */
	public static String currentSnapshotJson() {
		JsonArray items = new JsonArray();
		for (ItemDefinition d : WorldForgeRuntimeManager.list()) {
			items.add(DefinitionStore.toJson(d));
		}
		JsonArray tombs = new JsonArray();
		WorldForgeRuntimeManager.tombstones().forEach(tombs::add);
		JsonObject o = new JsonObject();
		o.addProperty("version", DefinitionStore.FILE_VERSION);
		o.add("items", items);
		o.add("tombstones", tombs);
		// v0.4.0: virtual BLOCK definitions travel in the same snapshot so the
		// client mirror (and the block entity renderer) always has them
		JsonArray blocks = new JsonArray();
		for (dev.worldforge.block.BlockDefinition d : dev.worldforge.block.BlockRuntimeManager.list()) {
			blocks.add(dev.worldforge.block.BlockDefinitionStore.toJson(d));
		}
		o.add("blocks", blocks);
		JsonArray blockTombs = new JsonArray();
		dev.worldforge.block.BlockRuntimeManager.tombstones().forEach(blockTombs::add);
		o.add("block_tombstones", blockTombs);
		return o.toString();
	}

	private static String agentEnvelope(dev.worldforge.ai.AgentBridge.AgentOutcome o) {
		JsonObject env = new JsonObject();
		env.addProperty("proposalId", o.proposalId() == null ? "" : o.proposalId());
		env.addProperty("diff", o.diffSummary() == null ? "" : o.diffSummary());
		JsonArray gaps = new JsonArray();
		o.capabilityGaps().forEach(gaps::add);
		env.add("gaps", gaps);
		env.addProperty("hasProposal", o.proposal() != null);
		if (o.proposal() != null) {
			env.addProperty("kind", o.proposal().kind());
			env.addProperty("itemId", proposalItemId(o.proposal()));
		}
		return env.toString();
	}

	private static String proposalItemId(dev.worldforge.ai.Proposal p) {
		if (p instanceof dev.worldforge.ai.Proposal.Create c) return c.id();
		if (p instanceof dev.worldforge.ai.Proposal.Update u) return u.id();
		if (p instanceof dev.worldforge.ai.Proposal.Delete d) return d.id();
		return "";
	}

	static String optId(String payload) {
		try {
			JsonObject o = parseBody(payload);
			var el = o.get("proposalId");
			return el != null && el.isJsonPrimitive() ? truncate(el.getAsString(), 128) : "";
		} catch (Exception e) {
			return "";
		}
	}

	static String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() <= max ? s : s.substring(0, max);
	}

	// ---------- kinds (public for direct GameTest business coverage) ----------

	public static final class Kinds {

		public static WorldForgeTools.ToolResult create(MinecraftServer server, JsonObject body) {
			ItemDefinition def;
			try {
				def = DefinitionStore.fromJson(body);
			} catch (Exception e) {
				return reject("非法定义数据: " + truncate(String.valueOf(e.getMessage()), 200));
			}
			return WorldForgeTools.createItem(server, def);
		}

		public static WorldForgeTools.ToolResult update(MinecraftServer server, JsonObject body) {
			ItemDefinition def;
			try {
				def = DefinitionStore.fromJson(body);
			} catch (Exception e) {
				return reject("非法定义数据: " + truncate(String.valueOf(e.getMessage()), 200));
			}
			return WorldForgeTools.updateItem(server, def);
		}

		public static WorldForgeTools.ToolResult delete(MinecraftServer server, JsonObject body) {
			String id = optString(body, "id");
			if (id == null || id.isBlank() || id.length() > MAX_ID_CHARS) {
				return reject("缺少或过长的 id");
			}
			return WorldForgeTools.deleteItem(server, id);
		}

		public static WorldForgeTools.ToolResult give(ServerPlayerEntity player, String payload) {
			JsonObject body;
			try {
				body = parseBody(payload);
			} catch (IllegalArgumentException e) {
				return reject(e.getMessage());
			}
			String id = optString(body, "id");
			if (id == null || id.isBlank() || id.length() > MAX_ID_CHARS) {
				return reject("缺少或过长的 id");
			}
			int count = 1;
			if (body.has("count") && body.get("count").isJsonPrimitive() && body.get("count").getAsJsonPrimitive().isNumber()) {
				count = Math.max(1, Math.min(99, body.get("count").getAsInt()));
			}
			var stack = dev.worldforge.item.DynamicItem.createStack(
					dev.worldforge.registry.WorldForgeItems.DYNAMIC_ITEM, id, count);
			if (stack == null) {
				return reject("物品定义不存在或已失效: " + id);
			}
			if (!player.getInventory().insertStack(stack)) {
				// inventory full: drop in front of the player instead of losing it
				player.dropItem(stack, false);
				return new WorldForgeTools.ToolResult(true, true, WorldForgeTools.ToolResult.SyncStatus.NONE,
						"背包已满，物品已掉落在脚下");
			}
			return new WorldForgeTools.ToolResult(true, true, WorldForgeTools.ToolResult.SyncStatus.NONE,
					"已获取 " + stack.getName().getString() + " x" + stack.getCount());
		}

		private static String optString(JsonObject o, String key) {
			return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isString()
					? o.get(key).getAsString() : null;
		}

		private Kinds() {
		}
	}

	private WorldForgeStudioProtocol() {
	}
}
