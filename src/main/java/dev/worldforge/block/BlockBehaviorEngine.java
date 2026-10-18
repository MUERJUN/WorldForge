package dev.worldforge.block;

import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.registry.WorldForgeBlocks;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;

/**
 * Runtime behaviour for interactive virtual blocks (v0.4.0).
 *
 * <p>Only capabilities that the generic block can genuinely perform are
 * implemented; everything else is reported by the validator as unsupported
 * rather than silently ignored. Actions run server-side only, and the
 * definition is re-checked at use time so a deleted/replaced definition can
 * never execute stale behaviour.</p>
 */
public final class BlockBehaviorEngine {

	public static ActionResult onUse(World world, BlockPos pos, PlayerEntity player, BlockDefinition def,
			DynamicBlockEntity be) {
		if (world.isClient || def == null) {
			return ActionResult.PASS;
		}
		boolean acted = false;
		for (BehaviorDefinition b : def.behaviors()) {
			if (!"right_click".equalsIgnoreCase(b.trigger())) {
				continue;
			}
			for (String action : b.actions()) {
				acted |= run(world, pos, player, action, b.parameters());
			}
		}
		return acted ? ActionResult.SUCCESS : ActionResult.PASS;
	}

	private static boolean run(World world, BlockPos pos, PlayerEntity player, String action,
			Map<String, Object> params) {
		switch (action) {
			case "apply_effect" -> {
				String effectId = str(params, "effect");
				if (effectId == null) {
					return false;
				}
				Identifier id = Identifier.tryParse(effectId);
				StatusEffect effect = id == null ? null : Registries.STATUS_EFFECT.get(id);
				if (effect == null) {
					return false;
				}
				int duration = (int) Math.max(20, Math.min(72000, num(params, "duration", 200)));
				int amplifier = (int) Math.max(0, Math.min(10, num(params, "amplifier", 0)));
				player.addStatusEffect(new StatusEffectInstance(
						Registries.STATUS_EFFECT.getEntry(effect), duration, amplifier));
				return true;
			}
			case "heal" -> {
				player.heal((float) Math.max(0, Math.min(20, num(params, "amount", 4))));
				return true;
			}
			case "experience" -> {
				player.addExperience((int) Math.max(0, Math.min(1000, num(params, "amount", 10))));
				return true;
			}
			case "ignite" -> {
				int seconds = (int) Math.max(1, Math.min(10, num(params, "seconds", 3)));
				player.setOnFireFor(seconds);
				return true;
			}
			case "give_item" -> {
				Item item = resolveItem(str(params, "item"));
				if (item == null) {
					return false;
				}
				int count = (int) Math.max(1, Math.min(8, num(params, "count", 1)));
				ItemStack stack = new ItemStack(item, count);
				if (!player.getInventory().insertStack(stack)) {
					player.dropItem(stack, false);
				}
				return true;
			}
			case "sound" -> {
				String s = str(params, "sound");
				if (s == null) {
					return false;
				}
				Identifier id = Identifier.tryParse(s);
				if (id == null || !Registries.SOUND_EVENT.containsId(id)) {
					return false;
				}
				if (world instanceof ServerWorld sw) {
					sw.playSound(null, pos, Registries.SOUND_EVENT.get(id),
							net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
				}
				return true;
			}
			case "particles" -> {
				if (world instanceof ServerWorld sw) {
					sw.spawnParticles(net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER,
							pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.01);
				}
				return true;
			}
			default -> {
				return false; // unsupported: never silently "succeed"
			}
		}
	}

	private static Item resolveItem(String id) {
		if (id == null) {
			return null;
		}
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) {
			return null;
		}
		Item item = Registries.ITEM.get(parsed);
		return item == net.minecraft.item.Items.AIR ? null : item;
	}

	private static String str(Map<String, Object> params, String key) {
		Object v = params == null ? null : params.get(key);
		return v == null ? null : String.valueOf(v);
	}

	private static double num(Map<String, Object> params, String key, double def) {
		Object v = params == null ? null : params.get(key);
		return v instanceof Number n ? n.doubleValue() : def;
	}

	private BlockBehaviorEngine() {
	}
}