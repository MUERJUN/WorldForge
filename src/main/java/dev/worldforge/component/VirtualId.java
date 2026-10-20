package dev.worldforge.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * The stable virtual identity of a WorldForge item.
 *
 * <p>Stored on every {@code worldforge:dynamic_item} stack as the
 * {@code worldforge:virtual_id} data component. All behaviour, appearance and
 * display lookups go through this id; nothing depends on transient in-memory
 * objects.</p>
 */
public record VirtualId(String id) {
	public static final Codec<VirtualId> CODEC = Codec.STRING.xmap(VirtualId::new, VirtualId::id);
	public static final PacketCodec<? super RegistryByteBuf, VirtualId> PACKET_CODEC =
			PacketCodecs.STRING.xmap(VirtualId::new, VirtualId::id);

	/** Path part of the id, e.g. "thunder_sword". */
	public String shortId() {
		int i = id.indexOf(':');
		return i >= 0 ? id.substring(i + 1) : id;
	}

	public boolean isValidSyntax() {
		return id != null && !id.isBlank()
				&& id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
	}
}
