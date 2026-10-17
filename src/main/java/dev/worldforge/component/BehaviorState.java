package dev.worldforge.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * Per-stack dynamic-behaviour state. Currently only the remaining cooldown,
 * measured against {@code level.getGameTime()}.
 *
 * <p>Kept deliberately small and forward-compatible: new per-stack runtime
 * state is added as new fields with defaults, and the packet codec below
 * serialises each field explicitly so old and new payloads can be told apart
 * in a later version.</p>
 */
public record BehaviorState(long cooldownUntilGameTime) {
	public static final BehaviorState EMPTY = new BehaviorState(0L);

	public static final Codec<BehaviorState> CODEC =
			Codec.LONG.xmap(BehaviorState::new, BehaviorState::cooldownUntilGameTime);
	public static final PacketCodec<? super RegistryByteBuf, BehaviorState> PACKET_CODEC =
			PacketCodecs.VAR_LONG.xmap(BehaviorState::new, BehaviorState::cooldownUntilGameTime);

	public boolean onCooldown(long gameTime) {
		return gameTime < cooldownUntilGameTime;
	}
}
