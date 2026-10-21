package dev.worldforge.ops.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.BlockRuntimeManager;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.ops.ToolContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Counts the PLACED instances of a virtual block id in the player's world.
 *
 * <p>Bounded by design: only chunks that are currently loaded are scanned and
 * the scan is capped, because this runs while the player waits for a
 * confirmation prompt.</p>
 */
public final class PlacedBlockCounter {

	/** hard cap on chunks scanned for one count */
	public static final int MAX_CHUNKS_SCANNED = 512;

	/** radius (in chunks) around the player that is scanned for instances */
	public static final int SCAN_RADIUS_CHUNKS = 4;

	private PlacedBlockCounter() {
	}

	/**
	 * @return the number of loaded instances with that virtual id, and whether
	 *         the scan was truncated (so the UI can say "at least N")
	 */
	public static Count count(ToolContext ctx, String virtualId) {
		if (ctx == null || ctx.world() == null || virtualId == null || virtualId.isBlank()) {
			return new Count(0, false);
		}
		ServerWorld world = ctx.world();
		int found = 0;
		int chunks = 0;
		boolean truncated = false;
		try {
			var chunkManager = world.getChunkManager();
			int pcx = ctx.player() == null ? 0 : ctx.player().getBlockPos().getX() >> 4;
			int pcz = ctx.player() == null ? 0 : ctx.player().getBlockPos().getZ() >> 4;
			for (int cx = pcx - SCAN_RADIUS_CHUNKS; cx <= pcx + SCAN_RADIUS_CHUNKS; cx++) {
				for (int cz = pcz - SCAN_RADIUS_CHUNKS; cz <= pcz + SCAN_RADIUS_CHUNKS; cz++) {
					if (chunks++ >= MAX_CHUNKS_SCANNED) {
						truncated = true;
						break;
					}
					// load=false: never force a chunk to generate just to count
					var chunk = chunkManager.getChunk(cx, cz,
							net.minecraft.world.chunk.ChunkStatus.FULL, false);
					if (chunk == null) {
						continue;
					}
					// only block entities can carry a virtual block id
					for (BlockPos pos : chunk.getBlockEntityPositions()) {
						var be = chunk.getBlockEntity(pos);
						if (be instanceof DynamicBlockEntity dbe && virtualId.equals(dbe.virtualId())) {
							found++;
						}
					}
				}
				if (truncated) {
					break;
				}
			}
		} catch (Exception e) {
			// a failed count must not block the operation; report what we know
			return new Count(found, true);
		}
		return new Count(found, truncated);
	}

	/**
	 * A count plus whether it is a lower bound.
	 *
	 * @param count     instances found
	 * @param truncated true when the scan stopped early (count is a lower bound)
	 */
	public record Count(int count, boolean truncated) {

		public String describe() {
			if (count == 0) {
				return "当前世界里没有已放置的该方块";
			}
			return truncated
					? "当前世界里至少有 " + count + " 个已放置的该方块"
					: "当前世界里有 " + count + " 个已放置的该方块";
		}
	}

	/** Human-readable impact statement for the confirmation summary. */
	public static String impactStatement(int count, boolean truncated) {
		if (count == 0) {
			return "目前没有已放置的实例，但修改的是共享定义，之后放置的方块也会使用新定义。";
		}
		String base = "修改的是共享定义，会影响世界里所有同 ID 的方块实例（"
				+ (truncated ? "至少 " : "") + count + " 个）。";
		return base;
	}

	/** Utility: the virtual ids of the blocks a player is looking at. */
	public static List<String> virtualIdsAt(ServerWorld world, BlockPos pos) {
		List<String> out = new ArrayList<>();
		if (world == null) {
			return out;
		}
		var be = world.getBlockEntity(pos);
		if (be instanceof DynamicBlockEntity dbe) {
			out.add(dbe.virtualId());
		}
		return out;
	}

	/** Utility: a definition lookup that never throws. */
	public static BlockDefinition definitionOf(String id) {
		return id == null ? null : BlockRuntimeManager.get(id);
	}

	/** Utility: serialises a small description of a block definition. */
	public static JsonObject describe(BlockDefinition def) {
		JsonObject o = new JsonObject();
		if (def == null) {
			return o;
		}
		o.addProperty("id", def.id());
		o.addProperty("name", def.name());
		o.addProperty("appearance", def.appearance());
		o.addProperty("category", def.category().name());
		JsonArray behaviours = new JsonArray();
		def.behaviors().forEach(b -> behaviours.add(InspectDefinitionTool.behaviourJson(b)));
		o.add("behaviors", behaviours);
		return o;
	}
}
