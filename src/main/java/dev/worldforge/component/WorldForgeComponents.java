package dev.worldforge.component;

import dev.worldforge.WorldForge;
import net.minecraft.component.ComponentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * WorldForge data component types.
 *
 * <p>Both components carry a codec (persisted into the world / item NBT and
 * the creative-inventory / command JSON path) and an explicit packet codec
 * (client synchronisation). This is the real 1.21.1 Data Components mechanism;
 * no temporary in-memory identity objects are involved.</p>
 */
public final class WorldForgeComponents {

	/** Stable virtual identity on every dynamic stack. */
	public static final ComponentType<VirtualId> VIRTUAL_ID =
			Registry.register(
					Registries.DATA_COMPONENT_TYPE,
					WorldForge.id("virtual_id"),
					ComponentType.<VirtualId>builder()
							.codec(VirtualId.CODEC)
							.packetCodec(VirtualId.PACKET_CODEC)
							.build());

	/** Per-stack behaviour runtime state (currently: cooldown deadline in game time). */
	public static final ComponentType<BehaviorState> BEHAVIOR_STATE =
			Registry.register(
					Registries.DATA_COMPONENT_TYPE,
					WorldForge.id("behavior_state"),
					ComponentType.<BehaviorState>builder()
							.codec(BehaviorState.CODEC)
							.packetCodec(BehaviorState.PACKET_CODEC)
							.build());

	public static void register() {
		// Class-initialisation registers; nothing to do here. Kept as an
		// explicit hook so the init order in WorldForge#onInitialize is obvious.
		WorldForge.LOGGER.debug("[WorldForge] data components registered");
	}

	private WorldForgeComponents() {
	}
}
