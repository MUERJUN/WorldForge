package dev.worldforge.ops;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * The authoritative context a tool executes in.
 *
 * <p>Everything here is established by the SERVER, never by the model. A tool
 * may only reach the world through the handles in this record, which is what
 * keeps "the model decided the outcome" structurally impossible.</p>
 *
 * @param player          the acting player (never null for player-facing tools)
 * @param server          the server the call runs on
 * @param world           the world the player is currently in
 * @param dimension       the dimension key of {@code world}
 * @param conversationId  the conversation the call belongs to
 * @param scope           the authorization scope granted for this call
 */
public record ToolContext(
		ServerPlayerEntity player,
		MinecraftServer server,
		ServerWorld world,
		RegistryKey<net.minecraft.world.World> dimension,
		String conversationId,
		ToolScope scope) {

	/**
	 * What a single tool call is allowed to touch. A WRITE call may only
	 * affect the objects named in its own confirmed summary — the scope is
	 * built from the confirmed operation, not from the model's free text.
	 */
	public record ToolScope(
			String conversationId,
			java.util.Set<String> allowedItemIds,
			java.util.Set<String> allowedBlockIds,
			boolean worldMutationAllowed) {

		public static ToolScope readOnly(String conversationId) {
			return new ToolScope(conversationId, java.util.Set.of(), java.util.Set.of(), false);
		}

		public static ToolScope mutating(String conversationId,
				java.util.Collection<String> itemIds, java.util.Collection<String> blockIds) {
			return new ToolScope(conversationId, java.util.Set.copyOf(itemIds), java.util.Set.copyOf(blockIds), true);
		}

		public boolean allowsItem(String id) {
			return allowedItemIds.contains(id);
		}

		public boolean allowsBlock(String id) {
			return allowedBlockIds.contains(id);
		}
	}

	/** A copy of this context with a different authorization scope. */
	public ToolContext withScope(ToolScope newScope) {
		return new ToolContext(player, server, world, dimension, conversationId, newScope);
	}

	public UUID playerId() {
		return player == null ? null : player.getUuid();
	}

	public String playerName() {
		return player == null ? "" : player.getName().getString();
	}

	/** Identifier of the world the player is in, for display purposes. */
	public String worldId() {
		return world == null ? "" : world.getRegistryKey().getValue().toString();
	}

	public Identifier dimensionId() {
		return dimension == null ? null : dimension.getValue();
	}
}
