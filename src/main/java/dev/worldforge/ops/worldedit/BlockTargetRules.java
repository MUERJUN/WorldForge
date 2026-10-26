package dev.worldforge.ops.worldedit;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * v0.8.0: which world blocks may be edited, and which are refused.
 *
 * <p>Hard rules, checked server-side on EVERY target position before any
 * write:</p>
 * <ul>
 *   <li>command / structure / jigsaw blocks and other world-control blocks
 *       are always forbidden;</li>
 *   <li>ANY position holding a BlockEntity (chests, furnaces, signs, other
 *       mods' tiles) is refused in phase one — replacing it would destroy
 *       the contained state;</li>
 *   <li>the block id must resolve through the live registry and every state
 *       property must exist on that block with a parseable value — strings
 *       from the model never construct a state directly.</li>
 * </ul>
 */
public final class BlockTargetRules {

	private static final Set<String> FORBIDDEN = Set.of(
			"minecraft:command_block",
			"minecraft:chain_command_block",
			"minecraft:repeating_command_block",
			"minecraft:structure_block",
			"minecraft:structure_void",
			"minecraft:jigsaw",
			"minecraft:barrier",
			"minecraft:moving_piston");

	private BlockTargetRules() {
	}

	/** True when the block id is one of the world-control blocks. */
	public static boolean isForbiddenId(String blockId) {
		return FORBIDDEN.contains(blockId);
	}

	/** True when the state's block carries a BlockEntity in this world position. */
	public static boolean holdsBlockEntity(BlockState state) {
		return state.getBlock() instanceof BlockEntityProvider;
	}

	/**
	 * Resolves a plan entry to a live {@link BlockState} through the registry,
	 * with strict property validation. Throws when the id is unknown, the id
	 * is forbidden, or any property does not exist / fails to parse — the
	 * whole plan is then refused before any write.
	 */
	public static BlockState resolve(WorldEditEntry entry) {
		if (isForbiddenId(entry.blockId())) {
			throw new IllegalArgumentException("禁止修改的世界控制方块: " + entry.blockId());
		}
		Identifier id = Identifier.tryParse(entry.blockId());
		if (id == null) {
			throw new IllegalArgumentException("无法解析的方块 id: " + entry.blockId());
		}
		Block block = Registries.BLOCK.get(id);
		if (block == null || block == Blocks.AIR && !entry.blockId().equals("minecraft:air")) {
			throw new IllegalArgumentException("注册表中不存在该方块: " + entry.blockId());
		}
		BlockState state = block.getDefaultState();
		for (var e : entry.properties().entrySet()) {
			net.minecraft.state.property.Property<?> prop =
					state.getBlock().getStateManager().getProperty(e.getKey());
			if (prop == null) {
				throw new IllegalArgumentException("方块 " + entry.blockId()
						+ " 没有属性 " + e.getKey());
			}
			var parsed = prop.parse(e.getValue());
			if (parsed.isEmpty()) {
				throw new IllegalArgumentException("属性 " + e.getKey()
						+ " 不接受值 " + e.getValue());
			}
			state = withState(state, prop, parsed.get());
		}
		return state;
	}

	/** Applies a parsed property value with the capture helper pattern. */
	private static <T extends Comparable<T>> BlockState withState(
			BlockState state, net.minecraft.state.property.Property<T> prop, Object value) {
		return state.with(prop, prop.getType().cast(value));
	}

	/** The stable text form of a state for the plan's original-state records. */
	public static WorldEditEntry describe(BlockState state) {
		String id = Registries.BLOCK.getId(state.getBlock()).toString();
		var props = new java.util.LinkedHashMap<String, String>();
		state.getEntries().forEach((prop, value) -> props.put(prop.getName(), value.toString()));
		return new WorldEditEntry(id, props);
	}
}
