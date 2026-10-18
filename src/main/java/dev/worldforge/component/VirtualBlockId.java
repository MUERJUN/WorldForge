package dev.worldforge.component;

import com.mojang.serialization.Codec;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * Virtual BLOCK identity carried by a placed block entity (v0.4.0).
 *
 * <p>Blocks cannot be registered after the registry freeze, so the identity of
 * a "dynamic block" is data, not a registry entry: the block item carries this
 * component, placement copies it into {@link dev.worldforge.block.DynamicBlockEntity},
 * and the block entity persists it through NBT like any vanilla block entity.
 * The same value on the item and on the placed block is what makes the pair
 * resolvable to one {@link dev.worldforge.block.BlockDefinition}.</p>
 */
public record VirtualBlockId(String id) {

	public static final Codec<VirtualBlockId> CODEC =
			Codec.STRING.xmap(VirtualBlockId::new, VirtualBlockId::id);

	public static final PacketCodec<? super net.minecraft.network.RegistryByteBuf, VirtualBlockId> PACKET_CODEC =
			PacketCodecs.STRING.xmap(VirtualBlockId::new, VirtualBlockId::id);

	public VirtualBlockId {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("virtual block id must not be blank");
		}
	}
}