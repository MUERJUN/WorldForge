package dev.worldforge.ops;

import java.util.List;

/**
 * What a mutating tool intends to do, computed BEFORE the player is asked to
 * confirm.
 *
 * <p>This is what makes the confirmation meaningful: the summary the player
 * reads is derived from the real target state, not from the model's prose. The
 * {@code snapshot} is captured here and re-checked at execution time, so a
 * target that changed while the player was deciding is refused instead of
 * silently modified.</p>
 *
 * @param itemIds   item definition ids the operation will touch
 * @param blockIds  block definition ids the operation will touch
 * @param summary   one-paragraph description shown in the chat confirm control
 * @param snapshot  identity snapshot re-validated at execution time
 * @param worldId   the world the target was seen in
 * @param dimensionId the dimension the target was seen in
 */
public record ToolPreparation(
		List<String> itemIds,
		List<String> blockIds,
		String summary,
		String snapshot,
		String worldId,
		String dimensionId) {

	public ToolPreparation {
		itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
		blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
		summary = summary == null ? "" : summary;
		snapshot = snapshot == null ? "" : snapshot;
	}

	public static ToolPreparation none() {
		return new ToolPreparation(List.of(), List.of(), "", "", "", "");
	}

	/** A preparation that is bound to a world and a dimension. */
	public static ToolPreparation of(List<String> itemIds, List<String> blockIds, String summary,
			String snapshot, String worldId, String dimensionId) {
		return new ToolPreparation(itemIds, blockIds, summary, snapshot, worldId, dimensionId);
	}
}
