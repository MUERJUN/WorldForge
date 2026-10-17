package dev.worldforge.registry;

import dev.worldforge.WorldForge;
import dev.worldforge.component.BehaviorState;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.item.DynamicItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * The ONLY native item WorldForge registers (spec 3.1; test A asserts count == 1).
 *
 * <p>Base settings: stack size 1 (dynamic items are tools/collectibles by
 * design; per-definition max_stack could be applied by baking
 * {@code max_stack_size} onto stacks at creation - kept simple in MVP),
 * durability comes from definitions. The generic item is intentionally dumb:
 * everything user-visible is resolved through the runtime manager.</p>
 */
public final class WorldForgeItems {

	public static final Item DYNAMIC_ITEM = new DynamicItem(new Item.Settings()
			.maxDamage(1)
			.component(WorldForgeComponents.BEHAVIOR_STATE, BehaviorState.EMPTY));

	public static void register() {
		Registry.register(Registries.ITEM, WorldForge.id("dynamic_item"), DYNAMIC_ITEM);
		WorldForge.LOGGER.info("[WorldForge] registered exactly one native item: {}",
				Registries.ITEM.getId(DYNAMIC_ITEM));
	}

	private WorldForgeItems() {
	}
}
