package dev.worldforge.ops;

import dev.worldforge.ops.tools.BlockMutationTools;
import dev.worldforge.ops.tools.CheckExportCapabilitiesTool;
import dev.worldforge.ops.tools.ExportModTool;
import dev.worldforge.ops.tools.GiveVirtualItemTool;
import dev.worldforge.ops.tools.GraftBehaviorTool;
import dev.worldforge.ops.tools.InspectContextTool;
import dev.worldforge.ops.tools.InspectDefinitionTool;
import dev.worldforge.ops.tools.ItemMutationTools;
import dev.worldforge.ops.tools.ListCapabilitiesTool;

/**
 * The single registration point for every WF-Ops tool.
 *
 * <p>Registration is idempotent and happens once at server start. The catalog
 * is deliberately explicit: adding a capability means adding it HERE, which
 * makes the complete set of things the AI can do reviewable in one screen.</p>
 *
 * <p>There is intentionally no generic "run a command" tool. The AI can only
 * cause what is listed below, and every one of those is implemented on top of
 * the existing, server-authoritative WorldForge services.</p>
 */
public final class ToolCatalog {

	private static volatile boolean registered;

	private ToolCatalog() {
	}

	/** Registers every tool. Safe to call more than once. */
	public static synchronized void registerAll() {
		if (registered) {
			return;
		}
		registered = true;

		// ---- read-only ----
		ToolRegistry.register(InspectContextTool.descriptor(), InspectContextTool.EXECUTOR);
		ToolRegistry.register(InspectDefinitionTool.descriptor(), InspectDefinitionTool.EXECUTOR);
		ToolRegistry.register(ListCapabilitiesTool.descriptor(), ListCapabilitiesTool.EXECUTOR);
		ToolRegistry.register(CheckExportCapabilitiesTool.descriptor(), CheckExportCapabilitiesTool.EXECUTOR);

		// ---- mutating ----
		ToolRegistry.register(ItemMutationTools.createDescriptor(), ItemMutationTools.CREATE_EXEC,
				ItemMutationTools.CREATE_PREPARER);
		ToolRegistry.register(ItemMutationTools.updateDescriptor(), ItemMutationTools.UPDATE_EXEC,
				ItemMutationTools.UPDATE_PREPARER);
		ToolRegistry.register(BlockMutationTools.createDescriptor(), BlockMutationTools.CREATE_EXEC,
				BlockMutationTools.CREATE_PREPARER);
		ToolRegistry.register(BlockMutationTools.updateDescriptor(), BlockMutationTools.UPDATE_EXEC,
				BlockMutationTools.UPDATE_PREPARER);
		ToolRegistry.register(BlockMutationTools.inspectTargetDescriptor(), BlockMutationTools.INSPECT_TARGET_EXEC);
		ToolRegistry.register(BlockMutationTools.modifyTargetDescriptor(), BlockMutationTools.MODIFY_TARGET_EXEC,
				BlockMutationTools.MODIFY_TARGET_PREPARER);
		ToolRegistry.register(GiveVirtualItemTool.descriptor(), GiveVirtualItemTool.EXECUTOR,
				GiveVirtualItemTool.PREPARER);

		// ---- the player's own items (no admin authority, but still confirmed) ----
		ToolRegistry.register(GraftBehaviorTool.graftDescriptor(), GraftBehaviorTool.GRAFT_EXEC,
				GraftBehaviorTool.GRAFT_PREPARER);
		ToolRegistry.register(GraftBehaviorTool.ungraftDescriptor(), GraftBehaviorTool.UNGRAFT_EXEC,
				GraftBehaviorTool.UNGRAFT_PREPARER);

		// ---- long running (must not block the server thread) ----
		ToolRegistry.register(ExportModTool.descriptor(), ExportModTool.EXECUTOR, ExportModTool.PREPARER);
	}

	/** Test hook: forget that registration happened. */
	public static synchronized void resetForTest() {
		registered = false;
		ToolRegistry.resetForTest();
	}
}
