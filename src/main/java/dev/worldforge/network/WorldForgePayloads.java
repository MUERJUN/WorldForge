package dev.worldforge.network;

import dev.worldforge.WorldForge;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * S2C definition synchronisation.
 *
 * <p>The client needs definitions to render names/tooltips of dynamic stacks
 * (custom_model_data travels on the stack itself; definition-derived display
 * data does not). Payload = the full definition snapshot in the same JSON
 * format as the on-disk file, sent on join and after every mutation, then
 * mirrored into a client-side definition registry.</p>
 */
public final class WorldForgePayloads {

	public record SyncDefinitions(String json) implements CustomPayload {
		public static final Id<SyncDefinitions> ID =
				new Id<>(WorldForge.id("sync_definitions"));
		public static final PacketCodec<? super RegistryByteBuf, SyncDefinitions> CODEC =
				PacketCodecs.STRING.xmap(SyncDefinitions::new, SyncDefinitions::json);

		@Override
		public Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(SyncDefinitions.ID, SyncDefinitions.CODEC);
	}

	/** Send the current definition snapshot to one player (no-op for vanilla clients). */
	public static void syncTo(net.minecraft.server.network.ServerPlayerEntity player) {
		if (!ServerPlayNetworking.canSend(player, SyncDefinitions.ID)) {
			return;
		}
		ServerPlayNetworking.send(player,
				new SyncDefinitions(DefinitionStore.toJsonString(WorldForgeRuntimeManager.list())));
	}

	/** Sync to all connected players (after create/update/delete/reload). */
	public static void syncToAll(net.minecraft.server.MinecraftServer server) {
		server.getPlayerManager().getPlayerList().forEach(WorldForgePayloads::syncTo);
	}

	private WorldForgePayloads() {
	}
}
