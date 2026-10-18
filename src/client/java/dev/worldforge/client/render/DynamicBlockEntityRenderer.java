package dev.worldforge.client.render;

import dev.worldforge.block.BlockCatalog;
import dev.worldforge.block.BlockDefinition;
import dev.worldforge.block.DynamicBlockEntity;
import dev.worldforge.client.ClientStudioState;
import dev.worldforge.client.ClientBlockState;
import dev.worldforge.registry.WorldForgeBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;

/**
 * Client renderer for placed virtual blocks (v0.4.0).
 *
 * <p>A virtual block is ONE registered block whose identity lives in its block
 * entity, so the appearance cannot come from the block state. This renderer
 * draws the vanilla model of the definition's {@link BlockCatalog} appearance on
 * top of the (invisible) dynamic block, using the vanilla block render manager -
 * the same model/texture pipeline the real block would use, not an item icon.</p>
 *
 * <p>Fallbacks are explicit: a missing definition (deleted or not yet synced)
 * falls back to stone, and a definition whose appearance is not in the catalog is
 * resolved through {@link BlockCatalog#normalize} so rendering never crashes.</p>
 */
public class DynamicBlockEntityRenderer implements BlockEntityRenderer<DynamicBlockEntity> {

	public DynamicBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
	}

	@Override
	public void render(DynamicBlockEntity entity, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light, int overlay) {
		BlockState appearance = appearanceState(entity);
		var client = MinecraftClient.getInstance();
		var world = client.world;
		BlockPos pos = entity.getPos();
		if (world == null) {
			return;
		}
		matrices.push();
		// The dynamic block itself renders nothing visible; draw the borrowed
		// vanilla model in its place, at the block's own position.
		matrices.translate(0.0, 0.0, 0.0);
		client.getBlockRenderManager().renderBlockAsEntity(appearance, matrices, vertexConsumers, light, overlay);
		matrices.pop();
	}

	/**
	 * Resolve the appearance block state for this entity.
	 *
	 * <p>The definition comes from the CLIENT mirror (never from server state),
	 * so rendering cannot leak server-only data and a dedicated server stays
	 * authoritative. A missing definition falls back to the block's own state.</p>
	 */
	private BlockState appearanceState(DynamicBlockEntity entity) {
		String virtualId = entity.virtualId();
		BlockDefinition def = virtualId == null || virtualId.isBlank()
				? null : ClientBlockState.get(virtualId);
		if (def == null) {
			// deleted / not synced yet: render the generic fallback so the block is
			// still visible and the player can see it is inert
			return BlockCatalog.resolveState(BlockCatalog.DEFAULT_APPEARANCE);
		}
		return BlockCatalog.resolveState(def.appearance());
	}

	/** diagnostics for the automated client check */
	public static String debugAppearanceOf(String virtualId) {
		BlockDefinition def = virtualId == null ? null : ClientBlockState.get(virtualId);
		return def == null ? BlockCatalog.DEFAULT_APPEARANCE : BlockCatalog.normalize(def.appearance());
	}
}