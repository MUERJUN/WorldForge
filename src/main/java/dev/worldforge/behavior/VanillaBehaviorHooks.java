package dev.worldforge.behavior;

import dev.worldforge.component.WorldForgeComponents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;

/**
 * The hook that lets a VANILLA item take part in the behaviour engine.
 *
 * <p>v0.7.2: until now the behaviour engine was only ever reached through
 * {@code DynamicItem#use} — that is, only a WorldForge <em>virtual</em> item
 * could do anything. A vanilla sword could not.</p>
 *
 * <p>A stack that carries a {@link dev.worldforge.component.BehaviorGraft}
 * component is handled here. The item keeps its identity (id, texture,
 * enchantments, durability) — it only gains the behaviour.</p>
 *
 * <p>Two rules keep this safe:</p>
 * <ul>
 *   <li>a VIRTUAL item is skipped here: it already runs its own behaviours
 *       through {@code DynamicItem}, and handling it twice would fire every
 *       behaviour twice;</li>
 *   <li>the use event is only CONSUMED when a behaviour actually fired, so a
 *       grafted sword still behaves like a sword (drawing a bow, eating food),
 *       and a graft on cooldown does not block the vanilla action.</li>
 * </ul>
 */
public final class VanillaBehaviorHooks {

	private VanillaBehaviorHooks() {
	}

	/** Registers the hooks. Called once at mod init. */
	public static void register() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity sp)) {
				return TypedActionResult.pass(player.getStackInHand(hand));
			}
			ItemStack stack = sp.getStackInHand(hand);
			// the virtual item runs its own behaviours — do not double-fire
			if (stack.get(WorldForgeComponents.VIRTUAL_ID) != null) {
				return TypedActionResult.pass(stack);
			}
			if (!BehaviorEngine.isGrafted(stack)) {
				return TypedActionResult.pass(stack);
			}
			ActionResult result = BehaviorEngine.onRightClick((ServerWorld) world, sp, stack, hand);
			// only consume the event when something really happened, so the
			// vanilla action of the item still works
			return result == ActionResult.SUCCESS
					? TypedActionResult.success(stack)
					: TypedActionResult.pass(stack);
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (world.isClient || !(player instanceof ServerPlayerEntity sp)) {
				return ActionResult.PASS;
			}
			ItemStack stack = sp.getStackInHand(hand);
			if (stack.get(WorldForgeComponents.VIRTUAL_ID) != null) {
				return ActionResult.PASS; // the virtual item handles itself
			}
			if (!BehaviorEngine.isGrafted(stack)) {
				return ActionResult.PASS;
			}
			if (entity instanceof LivingEntity living) {
				BehaviorEngine.onHitEntity((ServerWorld) world, sp, living, stack);
			}
			return ActionResult.PASS;
		});
	}
}
