package dev.worldforge.ops;

import java.util.List;

/**
 * Computes what a mutating tool is ABOUT to do, before the player confirms.
 *
 * <p>Splitting preparation from execution is what makes the confirmation
 * honest: the summary and the affected-id scope come from the real server
 * state, not from the model's own description of what it wants to do. A tool
 * that cannot determine its targets returns {@link ToolPreparation#none()},
 * which makes the orchestrator refuse the call rather than execute blindly.</p>
 */
@FunctionalInterface
public interface ToolPreparer {

	/**
	 * @param ctx     the authoritative context
	 * @param request the validated request
	 * @return the intended impact, or {@link ToolPreparation#none()} when the
	 *         operation cannot be described (which blocks execution)
	 */
	ToolPreparation prepare(ToolContext ctx, ToolRequest request);
}
