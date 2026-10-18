package dev.worldforge.network;

import dev.worldforge.WorldForge;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C definition synchronisation.
 *
 * <p>Payload = full definition snapshot (same JSON format as the on-disk
 * file) plus the server tombstone set (ids deleted while stacks survive),
 * sent on join and after every successful mutation. The client feeds this
 * into {@link dev.worldforge.runtime.ClientDefinitionMirror} ONLY (issue #2:
 * never into server state).</p>
 */
public final class WorldForgePayloads {

	public record SyncDefinitions(String json, List<String> tombstones, String blocksJson) implements CustomPayload {
		public static final Id<SyncDefinitions> ID =
				new Id<>(WorldForge.id("sync_definitions"));
		public static final PacketCodec<? super RegistryByteBuf, SyncDefinitions> CODEC =
				PacketCodec.tuple(
						PacketCodecs.STRING, SyncDefinitions::json,
						PacketCodecs.collection((java.util.function.IntFunction<List<String>>) ArrayList::new,
								PacketCodecs.STRING), SyncDefinitions::tombstones,
						PacketCodecs.STRING, SyncDefinitions::blocksJson,
						SyncDefinitions::new);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(SyncDefinitions.ID, SyncDefinitions.CODEC);
	}

	/** Build the exact payload the server would broadcast right now. */
	public static SyncDefinitions buildSnapshot() {
		return new SyncDefinitions(
				DefinitionStore.toJsonString(WorldForgeRuntimeManager.list()),
				List.copyOf(WorldForgeRuntimeManager.tombstones()),
				dev.worldforge.block.BlockDefinitionStore.toJsonString(
						dev.worldforge.block.BlockRuntimeManager.list()));
	}

	/** True when this player's client registered our sync channel (WF client). */
	public static boolean clientSupportsSync(net.minecraft.server.network.ServerPlayerEntity player) {
		return ServerPlayNetworking.canSend(player, SyncDefinitions.ID);
	}

	/** Send the current definition snapshot to one player (no-op for vanilla clients). */
	public static void syncTo(net.minecraft.server.network.ServerPlayerEntity player) {
		if (!ServerPlayNetworking.canSend(player, SyncDefinitions.ID)) {
			return;
		}
		ServerPlayNetworking.send(player, buildSnapshot());
	}

	/** Sync to all connected players (after create/update/delete/reload/join). */
	public static void syncToAll(net.minecraft.server.MinecraftServer server) {
		server.getPlayerManager().getPlayerList().forEach(WorldForgePayloads::syncTo);
	}

	private WorldForgePayloads() {
	}
}
