package dev.worldforge.item;

import dev.worldforge.WorldForge;
import dev.worldforge.behavior.BehaviorEngine;
import dev.worldforge.component.BehaviorState;
import dev.worldforge.component.VirtualId;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.List;

/**
 * THE single native item backing every virtual item (spec 3.1).
 *
 * <p>Identity lives in the persisted, networked {@code worldforge:virtual_id}
 * data component - never in a transient object. Display name and description
 * are resolved from the runtime definition manager on every read, so a
 * definition edit shows up immediately on stacks already in the game.
 * Numeric attributes (attack damage / speed), durability and appearance
 * (custom_model_data) are baked onto stacks by {@link #refreshStack(ItemStack)}
 * because vanilla reads them from stack components; that refresh runs on
 * definition create/update, on world reload and on player join, and is
 * idempotent.</p>
 *
 * <p>Deleted definitions produce an explicit "missing item" state: the stack
 * keeps its virtual id, shows an invalid marker in name and tooltip, performs
 * no behaviour, and is never destroyed (spec 3.3 / test F).</p>
 */
public class DynamicItem extends Item {

	public static final Identifier DAMAGE_MODIFIER_ID = WorldForge.id("dynamic_damage");
	public static final Identifier SPEED_MODIFIER_ID = WorldForge.id("dynamic_speed");

	public DynamicItem(Settings settings) {
		super(settings);
	}

	// ---------- identity ----------

	public static VirtualId virtualIdOf(ItemStack stack) {
		return stack.get(WorldForgeComponents.VIRTUAL_ID);
	}

	public static ItemDefinition definitionOf(ItemStack stack) {
		VirtualId vid = virtualIdOf(stack);
		return vid == null ? null : WorldForgeRuntimeManager.get(vid.id());
	}

	/** Create a fresh stack for a virtual id; returns null if the definition is unknown. */
	public static ItemStack createStack(Item item, String virtualId, int count) {
		ItemDefinition def = WorldForgeRuntimeManager.get(virtualId);
		if (def == null) {
			return null;
		}
		ItemStack stack = new ItemStack(item, Math.max(1, Math.min(count, def.maxStack())));
		stack.set(WorldForgeComponents.VIRTUAL_ID, new VirtualId(virtualId));
		stack.set(WorldForgeComponents.BEHAVIOR_STATE, BehaviorState.EMPTY);
		refreshStack(stack);
		return stack;
	}

	// ---------- stack refresh (bake numeric attributes & appearance) ----------

	/**
	 * (Re-)apply definition-derived components to one stack: durability,
	 * appearance slot and attribute modifiers. Stacks whose definition is
	 * missing are left untouched (recovery-friendly); the display layer marks
	 * them as invalid.
	 */
	public static void refreshStack(ItemStack stack) {
		ItemDefinition def = definitionOf(stack);
		if (def == null) {
			return;
		}
		stack.set(DataComponentTypes.MAX_DAMAGE, def.maxDurability());
		// keep current damage within the (possibly new) maximum after hot edits
		int dmg = stack.getOrDefault(DataComponentTypes.DAMAGE, 0);
		if (dmg >= def.maxDurability()) {
			stack.set(DataComponentTypes.DAMAGE, def.maxDurability() - 1);
		}
		stack.set(DataComponentTypes.CUSTOM_MODEL_DATA,
				new CustomModelDataComponent(def.appearanceSlotIndex()));

		AttributeModifiersComponent.Builder amcb = AttributeModifiersComponent.builder();
		if (def.damage() != 0.0) {
			amcb.add(EntityAttributes.GENERIC_ATTACK_DAMAGE,
					new EntityAttributeModifier(DAMAGE_MODIFIER_ID, def.damage(), EntityAttributeModifier.Operation.ADD_VALUE),
					AttributeModifierSlot.MAINHAND);
		}
		if (def.attackSpeed() != 0.0) {
			amcb.add(EntityAttributes.GENERIC_ATTACK_SPEED,
					new EntityAttributeModifier(SPEED_MODIFIER_ID, def.attackSpeed(), EntityAttributeModifier.Operation.ADD_VALUE),
					AttributeModifierSlot.MAINHAND);
		}
		stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, amcb.build());
	}

	/** Refresh every dynamic stack in a container (player inventory etc.). */
	public static void refreshAllIn(net.minecraft.entity.player.PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			refreshStack(inv.getStack(i));
		}
	}

	// ---------- dynamic display ----------

	@Override
	public Text getName(ItemStack stack) {
		ItemDefinition def = definitionOf(stack);
		if (def != null && def.name() != null && !def.name().isBlank()) {
			return Text.literal(def.name());
		}
		VirtualId vid = virtualIdOf(stack);
		if (vid != null) {
			if (WorldForgeRuntimeManager.isMissing(vid.id())) {
				return Text.literal("§6[已失效]§r " + vid.shortId());
			}
			return Text.literal("§7[未定义]§r " + vid.shortId());
		}
		return Text.literal("WorldForge 动态物品");
	}

	@Override
	public void appendTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType type) {
		ItemDefinition def = definitionOf(stack);
		VirtualId vid = virtualIdOf(stack);
		if (def == null) {
			String id = vid == null ? "?" : vid.id();
			boolean tomb = vid != null && WorldForgeRuntimeManager.isMissing(id);
			tooltip.add(Text.literal(tomb
					? "定义已删除 - 物品失效 (id: " + id + ")"
					: "定义缺失 - 无法解析 (id: " + id + ")").formatted(Formatting.RED));
			return;
		}
		if (def.description() != null && !def.description().isBlank()) {
			tooltip.add(Text.literal(def.description()).formatted(Formatting.GRAY, Formatting.ITALIC));
		}
		tooltip.add(Text.literal("id: " + def.id()).formatted(Formatting.DARK_GRAY));
		if (def.damage() > 0) {
			tooltip.add(Text.literal("攻击力 +" + trim(def.damage())).formatted(Formatting.WHITE));
		}
	}

	private static String trim(double d) {
		return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.1f", d);
	}

	@Override
	public boolean hasGlint(ItemStack stack) {
		ItemDefinition def = definitionOf(stack);
		return def != null && !def.behaviors().isEmpty();
	}

	// ---------- behaviour dispatch (listeners registered ONCE, at item class level) ----------

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (!world.isClient) {
			// Fabric guarantees Item#use runs on the server thread server-side.
			ActionResult r = BehaviorEngine.onRightClick(
					(ServerWorld) world,
					user instanceof net.minecraft.server.network.ServerPlayerEntity sp ? sp : null,
					stack, hand);
			if (r == ActionResult.SUCCESS) {
				return TypedActionResult.success(stack);
			}
			if (r == ActionResult.FAIL) {
				return TypedActionResult.fail(stack);
			}
		}
		return TypedActionResult.pass(stack);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		// Virtual items never place blocks.
		return ActionResult.PASS;
	}

	@Override
	public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		World world = target.getWorld();
		if (!world.isClient) {
			BehaviorEngine.onHitEntity((ServerWorld) world, attacker, target, stack);
			ItemDefinition def = definitionOf(stack);
			if (def != null && def.maxDurability() > 1) {
				stack.damage(1, (ServerWorld) world,
						attacker instanceof net.minecraft.server.network.ServerPlayerEntity sp ? sp : null,
						broken -> { });
			}
		}
		return true;
	}
}
