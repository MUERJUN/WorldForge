package dev.worldforge.behavior;

import dev.worldforge.WorldForge;
import dev.worldforge.component.BehaviorState;
import dev.worldforge.component.VirtualId;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * The dynamic behaviour engine (spec section 5).
 *
 * <p>Design rules honoured here:</p>
 * <ul>
 *   <li>All entry points are invoked from {@link DynamicItem} only - one set
 *       of native listeners registered once at mod init. New definitions are
 *       dispatched through these existing entry points, never by registering
 *       more listeners (test G).</li>
 *   <li>Dispatch happens against an explicit whitelist of triggers and
 *       actions with parameters taken from the definition. No arbitrary code
 *       is generated or executed.</li>
 *   <li>Everything runs on the server thread: {@code Item#use} /
 *       {@code postHit} are server-side when {@code !world.isClient}.</li>
 *   <li>Destructive actions are clamped: explosion power 0.1..4.0, no fire by
 *       default, distance-limited; lightning within 48 blocks; teleport range
 *       1..64 with safe-ground search.</li>
 * </ul>
 */
public final class BehaviorEngine {

	// ---------- trigger: right_click ----------

	public static ActionResult onRightClick(ServerWorld world, ServerPlayerEntity player, ItemStack stack, Hand hand) {
		ItemDefinition def = DynamicItem.definitionForLogic(stack);
		if (def == null) {
			// deleted/missing definition: identifiable inert state, never a crash (test F)
			VirtualId vid = DynamicItem.virtualIdOf(stack);
			if (player != null && vid != null) {
				boolean tomb = WorldForgeRuntimeManager.isMissing(vid.id());
				player.sendMessage(Text.literal((tomb ? "§c[WorldForge] 物品已失效: " : "§c[WorldForge] 未知物品: ") + vid.id()), true);
			}
			return ActionResult.FAIL;
		}

		long now = world.getTime();
		BehaviorState st = stack.getOrDefault(WorldForgeComponents.BEHAVIOR_STATE, BehaviorState.EMPTY);
		if (st.onCooldown(now)) {
			if (player != null) {
				player.sendMessage(Text.literal("§e[WorldForge] 冷却中 "
						+ ((st.cooldownUntilGameTime() - now + 19) / 20) + "s"), true);
			}
			return ActionResult.FAIL;
		}

		if (player == null) {
			return ActionResult.PASS; // dispensers etc. are out of MVP scope
		}

		boolean anyFired = false;
		int cooldown = 0;
		for (BehaviorDefinition b : def.behaviors()) {
			if (!"right_click".equals(b.trigger())) {
				continue;
			}
			for (String action : b.actions()) {
				if (runAction(action.toLowerCase(Locale.ROOT), b, world, player, stack, null)) {
					anyFired = true;
					cooldown = Math.max(cooldown, b.cooldownTicks());
				}
			}
		}
		if (anyFired && cooldown > 0) {
			stack.set(WorldForgeComponents.BEHAVIOR_STATE, new BehaviorState(now + cooldown));
		}
		return anyFired ? ActionResult.SUCCESS : ActionResult.PASS;
	}

	// ---------- trigger: hit_entity ----------

	public static void onHitEntity(ServerWorld world, LivingEntity attacker, LivingEntity target, ItemStack stack) {
		ItemDefinition def = DynamicItem.definitionForLogic(stack);
		if (def == null || !(attacker instanceof PlayerEntity player)) {
			return;
		}
		long now = world.getTime();
		BehaviorState st = stack.getOrDefault(WorldForgeComponents.BEHAVIOR_STATE, BehaviorState.EMPTY);
		if (st.onCooldown(now)) {
			return;
		}
		boolean anyFired = false;
		int cooldown = 0;
		for (BehaviorDefinition b : def.behaviors()) {
			if (!"hit_entity".equals(b.trigger())) {
				continue;
			}
			for (String action : b.actions()) {
				if (runAction(action.toLowerCase(Locale.ROOT), b, world, player, stack, target)) {
					anyFired = true;
					cooldown = Math.max(cooldown, b.cooldownTicks());
				}
			}
		}
		if (anyFired && cooldown > 0) {
			stack.set(WorldForgeComponents.BEHAVIOR_STATE, new BehaviorState(now + cooldown));
		}
	}

	// ---------- whitelisted actions ----------

	private static boolean runAction(String action, BehaviorDefinition b, ServerWorld world,
			PlayerEntity player, ItemStack stack, LivingEntity target) {
		switch (action) {
			case "summon_lightning" -> {
				Vec3d pos = player.getEyePos();
				Vec3d look = player.getRotationVec(1.0f);
				double range = Math.min(32.0, Math.max(2.0, b.paramDouble("range", 12.0)));
				BlockPos bp = BlockPos.ofFloored(pos.add(look.multiply(range, range * 0.5, range)));
				if (bp.isWithinDistance(player.getBlockPos(), 48)) {
					Entity bolt = net.minecraft.entity.EntityType.LIGHTNING_BOLT.create(world);
					if (bolt != null) {
						bolt.refreshPositionAndAngles(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5, 0.0f, 0.0f);
						world.spawnEntity(bolt);
						return true;
					}
				}
				return false;
			}
			case "apply_effect" -> {
				String effectId = b.paramString("effect", "minecraft:speed");
				int duration = clampInt(b.paramInt("duration", 200), 1, 20 * 60 * 60);
				int amplifier = clampInt(b.paramInt("amplifier", 0), 0, 10);
				RegistryEntry<StatusEffect> effect = Registries.STATUS_EFFECT.getEntry(Identifier.tryParse(effectId)).orElse(null);
				LivingEntity receiver = "target".equals(b.paramString("target", "self")) && target != null ? target : player;
				if (effect != null) {
					receiver.addStatusEffect(new StatusEffectInstance(effect, duration, amplifier), player);
					return true;
				}
				WorldForge.LOGGER.warn("[WorldForge] unknown effect id '{}' - skipped", effectId);
				return false;
			}
			case "heal" -> {
				double amount = Math.min(20.0, Math.max(0.5, b.paramDouble("amount", 4.0)));
				player.heal((float) amount);
				return true;
			}
			case "experience" -> {
				int xp = clampInt(b.paramInt("amount", 20), 0, 1000);
				if (xp > 0) {
					world.spawnEntity(new ExperienceOrbEntity(world, player.getX(), player.getY(), player.getZ(), xp));
				}
				return true;
			}
			case "teleport" -> {
				double dist = Math.min(64.0, Math.max(1.0, b.paramDouble("distance", 16.0)));
				Vec3d look = player.getRotationVec(1.0f);
				BlockPos base = BlockPos.ofFloored(player.getPos().add(look.x * dist, 0, look.z * dist));
				BlockPos landed = findSafeGround(world, base);
				if (landed != null && player instanceof ServerPlayerEntity sp) {
					sp.networkHandler.requestTeleport(landed.getX() + 0.5, landed.getY() + 1.0, landed.getZ() + 0.5,
							sp.getYaw(), sp.getPitch());
					return true;
				}
				return false;
			}
			case "explosion" -> {
				// safety limits (spec 5): small, no fire by default, distance clamped
				float power = (float) Math.min(4.0, Math.max(0.1, b.paramDouble("power", 1.5)));
				boolean fire = b.paramBool("fire", false);
				Vec3d look = player.getRotationVec(1.0f);
				BlockPos ep = BlockPos.ofFloored(player.getEyePos().add(look.multiply(6, 2, 6)));
				world.createExplosion(player, ep.getX() + 0.5, ep.getY() + 0.5, ep.getZ() + 0.5,
						power, fire, World.ExplosionSourceType.MOB);
				return true;
			}
			case "ignite" -> {
				if (target != null) {
					target.setOnFireForTicks(clampInt(b.paramInt("seconds", 5) * 20, 20, 200));
					return true;
				}
				return false;
			}
			default -> {
				return false; // unknown actions skipped by design (validator warned at creation)
			}
		}
	}

	private static BlockPos findSafeGround(ServerWorld world, BlockPos target) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dy = -4; dy <= 4; dy++) {
			m.set(target.getX(), target.getY() + dy, target.getZ());
			if (world.isAir(m) && world.isAir(m.up())
					&& !world.getBlockState(m.down()).isAir()
					&& world.getBlockState(m.down()).isSolidBlock(world, m.down())) {
				return m.toImmutable();
			}
		}
		return null;
	}

	private static int clampInt(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private BehaviorEngine() {
	}
}
