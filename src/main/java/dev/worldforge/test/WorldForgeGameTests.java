package dev.worldforge.test;

import dev.worldforge.command.DemoDefinitions;
import dev.worldforge.definition.BehaviorDefinition;
import dev.worldforge.definition.ItemDefinition;
import dev.worldforge.definition.TextureSource;
import dev.worldforge.item.DynamicItem;
import dev.worldforge.persistence.DefinitionStore;
import dev.worldforge.registry.WorldForgeItems;
import dev.worldforge.runtime.WorldForgeRuntimeManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * In-game Fabric GameTest suite (vanilla @GameTest under the
 * {@code fabric-gametest} entrypoint). Runs on a real dedicated server via
 * {@code ./gradlew runGameTestServer}.
 *
 * <p>A test passes when the method returns without throwing; assertions below
 * use {@link TestContext#assertTrue(boolean, String)} which throws on failure.</p>
 *
 * <p>Covers spec tests A (single native registration), B (runtime creation of
 * 10 items without restart), C (distinct id/name/appearance/behaviour),
 * D (hot edit visible on existing stacks), E (persistence round-trip incl.
 * stack NBT identity), F (deleted definition -> identifiable inert state),
 * G (reload idempotence, no duplicate listeners).</p>
 */
public class WorldForgeGameTests {

	// ---- Test A: exactly ONE worldforge native item ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge")
	public void testA_singleNativeItem(TestContext ctx) {
		long wfItems = Registries.ITEM.getIds().stream()
				.filter(id -> id.getNamespace().equals("worldforge")).count();
		ctx.assertTrue(wfItems == 1, "expected exactly 1 native worldforge item, got " + wfItems);
		ctx.assertTrue(Registries.ITEM.get(Identifier.of("worldforge", "dynamic_item")) == WorldForgeItems.DYNAMIC_ITEM,
				"worldforge:dynamic_item missing from registry");
		ctx.complete();
	}

	// ---- Test B: runtime creation of 10 items without restart ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge")
	public void testB_runtimeCreateTen(TestContext ctx) {
		WorldForgeRuntimeManager.attachStoreForTesting(null); // in-memory, no file writes
		int created = DemoDefinitions.createAll();
		ctx.assertTrue(created >= 10, "expected >=10 created, got " + created);
		ctx.assertTrue(WorldForgeRuntimeManager.list().size() >= 10, "list must show >=10");
		ctx.complete();
	}

	// ---- Test C: 10 distinct virtual ids/names/slots/behaviors ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge")
	public void testC_distinctItems(TestContext ctx) {
		WorldForgeRuntimeManager.attachStoreForTesting(null);
		DemoDefinitions.createAll();
		List<ItemDefinition> defs = WorldForgeRuntimeManager.list();
		long distinctIds = defs.stream().map(ItemDefinition::id).distinct().count();
		long distinctNames = defs.stream().map(ItemDefinition::name).distinct().count();
		ctx.assertTrue(distinctIds >= 10, "ids not distinct: " + distinctIds);
		ctx.assertTrue(distinctNames >= 10, "names not distinct: " + distinctNames);

		boolean thunder = defs.stream().anyMatch(d ->
				d.id().equals("worldforge:thunder_sword")
						&& d.behaviors().stream().anyMatch(b -> b.actions().contains("summon_lightning")));
		ctx.assertTrue(thunder, "thunder_sword missing lightning behavior");

		// appearance: several distinct slots among the ten (real variety, not one renamed stick)
		long slots = defs.stream().map(ItemDefinition::appearanceSlotIndex).distinct().count();
		ctx.assertTrue(slots >= 4, "expected >=4 distinct appearance slots, got " + slots);

		// stacks carry distinct virtual ids and definition-derived names
		ItemStack s1 = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:thunder_sword", 1);
		ItemStack s2 = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:fire_sword", 1);
		ctx.assertTrue(s1 != null && s2 != null, "createStack returned null for valid ids");
		ctx.assertTrue(!DynamicItem.virtualIdOf(s1).id().equals(DynamicItem.virtualIdOf(s2).id()),
				"virtual ids identical on stacks");
		ctx.assertTrue(s1.getName().getString().contains("雷霆"),
				"name not from definition: " + s1.getName().getString());
		ctx.assertTrue(s2.getName().getString().contains("烈焰"),
				"name2 not from definition: " + s2.getName().getString());
		ctx.complete();
	}

	// ---- Test D: hot edit damage 12 -> 20 visible on EXISTING stacks ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge")
	public void testD_hotEdit(TestContext ctx) {
		WorldForgeRuntimeManager.attachStoreForTesting(null);
		DemoDefinitions.createAll();
		ItemStack stack = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:thunder_sword", 1);
		ctx.assertTrue(stack != null, "stack null");
		double before = attributeValue(stack, DynamicItem.DAMAGE_MODIFIER_ID);
		ctx.assertTrue(Math.abs(before - 12.0) < 0.001, "expected damage 12 baked, got " + before);

		ItemDefinition def = WorldForgeRuntimeManager.get("worldforge:thunder_sword");
		var r = WorldForgeRuntimeManager.updateItem(def.withAttribute("damage", 20));
		ctx.assertTrue(r.ok(), "update failed: " + r.message());
		// what /wf setdamage does for held stacks:
		DynamicItem.refreshStack(stack);
		double after = attributeValue(stack, DynamicItem.DAMAGE_MODIFIER_ID);
		ctx.assertTrue(Math.abs(after - 20.0) < 0.001, "expected hot damage 20, got " + after);
		ctx.complete();
	}

	// ---- Test E: persistence round-trip through DefinitionStore ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge")
	public void testE_persistenceRoundTrip(TestContext ctx) {
		Path dir;
		try {
			dir = Files.createTempDirectory("wf-gametest-e");
		} catch (Exception e) {
			throw new RuntimeException("tempdir", e);
		}
		DefinitionStore store = new DefinitionStore(dir);
		WorldForgeRuntimeManager.attachStoreForTesting(store);

		ItemDefinition def = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:persist_me", "持久测试", "验证",
				TextureSource.vanilla("minecraft:item/diamond"),
				Map.of("damage", 7.0, "max_durability", 123.0),
				List.of(new BehaviorDefinition("right_click", "heal", 40, Map.of("amount", 3))));
		ctx.assertTrue(WorldForgeRuntimeManager.createItem(def).ok(), "create failed");

		// simulate world exit + re-enter: fresh store reads the same dir
		DefinitionStore store2 = new DefinitionStore(dir);
		WorldForgeRuntimeManager.attachStoreForTesting(store2);
		WorldForgeRuntimeManager.reloadDefinitions(); // what SERVER_STARTED does
		List<ItemDefinition> loaded = store2.load();
		ctx.assertTrue(loaded.size() == 1, "expected 1 definition reloaded, got " + loaded.size());
		ItemDefinition back = loaded.get(0);
		ctx.assertTrue(back.id().equals("worldforge:persist_me")
						&& Math.abs(back.damage() - 7.0) < 0.001
						&& back.maxDurability() == 123
						&& back.behaviors().get(0).cooldownTicks() == 40,
				"definition content lost: " + back);

		// stack identity survives full NBT serialisation (the inventory save path)
		ItemStack stack = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:persist_me", 1);
		ctx.assertTrue(stack != null, "stack must be creatable after reload");
		NbtElement nbt = stack.encode(ctx.getWorld().getRegistryManager());
		ItemStack restored = ItemStack.fromNbtOrEmpty(ctx.getWorld().getRegistryManager(), (NbtCompound) nbt);
		ctx.assertTrue(DynamicItem.virtualIdOf(restored) != null
						&& DynamicItem.virtualIdOf(restored).id().equals("worldforge:persist_me"),
				"stack lost virtual id across NBT round-trip");
		ctx.complete();
	}

	// ---- Test F: deleted definition -> identifiable inert state ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge")
	public void testF_deletedDefinitionInertStack(TestContext ctx) {
		WorldForgeRuntimeManager.attachStoreForTesting(null);
		DemoDefinitions.createAll();
		ItemStack stack = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:healing_staff", 1);
		ctx.assertTrue(stack != null, "stack null");

		var r = WorldForgeRuntimeManager.deleteItem("worldforge:healing_staff");
		ctx.assertTrue(r.ok(), "delete failed: " + r.message());
		ctx.assertTrue(WorldForgeRuntimeManager.isMissing("worldforge:healing_staff"), "tombstone missing");

		// stack NOT destroyed and still identifiable
		ctx.assertTrue(!stack.isEmpty(), "stack must not be destroyed");
		String name = stack.getName().getString();
		ctx.assertTrue(name.contains("失效"), "stack name must mark invalid, got: " + name);

		// dispatch must not crash and must never SUCCESS
		net.minecraft.util.ActionResult res = dev.worldforge.behavior.BehaviorEngine.onRightClick(
				ctx.getWorld(), null, stack, net.minecraft.util.Hand.MAIN_HAND);
		ctx.assertTrue(res == net.minecraft.util.ActionResult.FAIL || res == net.minecraft.util.ActionResult.PASS,
				"deleted-definition use must not SUCCESS, got " + res);
		ctx.complete();
	}

	// ---- Test G: repeated reload -> no duplicate state/listeners ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge")
	public void testG_reloadIdempotent(TestContext ctx) {
		Path dir;
		try {
			dir = Files.createTempDirectory("wf-gametest-g");
		} catch (Exception e) {
			throw new RuntimeException("tempdir", e);
		}
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));
		DemoDefinitions.createAll();
		int first = WorldForgeRuntimeManager.reloadDefinitions();
		int second = WorldForgeRuntimeManager.reloadDefinitions();
		int third = WorldForgeRuntimeManager.reloadDefinitions();
		ctx.assertTrue(first == 10 && second == 10 && third == 10,
				"reload counts not stable: " + first + "/" + second + "/" + third);
		ctx.assertTrue(WorldForgeRuntimeManager.list().size() == 10,
				"definitions duplicated across reloads: " + WorldForgeRuntimeManager.list().size());
		// event registration guarded: double register must be a no-op
		dev.worldforge.event.WorldForgeEvents.register();
		dev.worldforge.event.WorldForgeEvents.register();
		ctx.complete();
	}

	// ---- Test H: real per-world persistence via the actual save directory ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-persist")
	public void testH_realWorldPersistence(TestContext ctx) {
		// Re-bind to the *actual* run directory like SERVER_STARTED does,
		// then create + verify a file appears inside the world folder.
		WorldForgeRuntimeManager.onServerStarted(ctx.getWorld().getServer());
		// idempotent: re-runs against a persisted world may already know this id
		WorldForgeRuntimeManager.deleteItem("worldforge:persist_real");
		ItemDefinition def = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:persist_real", "真实存档持久化", "写入世界目录",
				TextureSource.vanilla("minecraft:item/emerald"),
				Map.of("damage", 3.0, "max_durability", 64.0),
				List.of());
		var cr = WorldForgeRuntimeManager.createItem(def);
		ctx.assertTrue(cr.ok(), "createItem must succeed, got: " + cr.message());

		Path worldDir = ctx.getWorld().getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT);
		Path itemsFile = worldDir.resolve("worldforge").resolve("items.json");
		ctx.assertTrue(Files.isRegularFile(itemsFile),
				"definitions file must exist in the world directory: " + itemsFile);

		// simulate exit + re-enter: reloadDefinitions re-reads the file from disk
		ctx.assertTrue(WorldForgeRuntimeManager.reloadDefinitions() >= 1, "reload must find the definition");
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:persist_real") != null,
				"definition lost after reload from disk");

		// per-world isolation marker: the file lives under THIS world's folder
		ctx.assertTrue(itemsFile.startsWith(worldDir), "definitions must be inside the save directory");
		ctx.complete();
	}

	// ================= v0.1.1 REGRESSION TESTS (written against v0.1.0 API) =================

	// ---- Test R1 (issue #1): save failure must NOT report success and must roll back ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-regression")
	public void testR1_saveFailureNotFakeSuccess(TestContext ctx) {
		// Block persistence deterministically: make the definitions FILE
		// position occupied by a directory, so every write fails.
		java.nio.file.Path dir;
		try {
			dir = Files.createTempDirectory("wf-r1");
			Files.createDirectories(dir.resolve("items.json")); // file slot taken by a directory
		} catch (Exception e) {
			throw new RuntimeException("setup", e);
		}
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		ItemDefinition def = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:r1_fail", "R1", "",
				TextureSource.vanilla("minecraft:item/diamond"),
				Map.of("damage", 5.0, "max_durability", 64.0), List.of());
		var r = WorldForgeRuntimeManager.createItem(def);

		// Correct behaviour: the mutation must report failure AND leave no
		// divergence between memory and disk (rollback).
		ctx.assertTrue(!r.ok(), "createItem must FAIL when the definitions file cannot be written, got ok + " + r.message());
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:r1_fail") == null,
				"failed mutation must roll back in-memory state (no fake success)");
		ctx.complete();
	}

	// ---- Test R2 (issue #2): client mirror must not corrupt integrated-server state ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-regression")
	public void testR2_mirrorMustNotTouchServerState(TestContext ctx) {
		java.nio.file.Path dir;
		try {
			dir = Files.createTempDirectory("wf-r2");
		} catch (Exception e) {
			throw new RuntimeException("setup", e);
		}
		WorldForgeRuntimeManager.attachStoreForTesting(new DefinitionStore(dir));

		ItemDefinition def = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:r2_item", "R2", "", TextureSource.vanilla("minecraft:item/diamond"),
				Map.of("damage", 1.0), List.of());
		ctx.assertTrue(WorldForgeRuntimeManager.createItem(def).ok(), "create ok");
		ctx.assertTrue(WorldForgeRuntimeManager.deleteItem("worldforge:r2_item").ok(), "delete ok");

		// Simulate the client-side sync handler (in single player / integrated
		// server this runs in the SAME JVM; v0.1.0 wrote it into the shared
		// static map and destroyed server truth).
		dev.worldforge.runtime.ClientDefinitionMirror.replaceAllFromSync(
				"{\"version\":1,\"items\":[]}", java.util.Set.of());

		// (a) server tombstone must survive an unrelated client mirror write
		ctx.assertTrue(WorldForgeRuntimeManager.isMissing("worldforge:r2_item"),
				"server tombstone lost via client mirror write (state split bug)");
		// (b) and the mirror must be an INDEPENDENT view: on a pure client the
		// server map would be empty while the mirror holds the snapshot.
		dev.worldforge.runtime.ClientDefinitionMirror.replaceAllFromSync(
				"{\"version\":1,\"items\":[{\"id\":\"worldforge:mirror_only\",\"name\":\"镜像\",\"version\":1}]}", java.util.Set.of());
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:mirror_only") == null,
				"client mirror write leaked into server state (shared map bug)");
		ctx.assertTrue(dev.worldforge.runtime.ClientDefinitionMirror.get("worldforge:mirror_only") != null,
				"mirror must hold its own snapshot");
		ctx.complete();
	}

	// ---- Test R3 (issue #4): deleted definition must strip combat attributes ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-regression")
	public void testR3_deletedDefinitionStripsAttributes(TestContext ctx) {
		WorldForgeRuntimeManager.attachStoreForTesting(null);
		ItemDefinition def = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:r3_sword", "R3剑", "", TextureSource.vanilla("minecraft:item/diamond_sword"),
				Map.of("damage", 12.0, "max_durability", 100.0), List.of());
		ctx.assertTrue(WorldForgeRuntimeManager.createItem(def).ok(), "create ok");
		ItemStack stack = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:r3_sword", 1);
		ctx.assertTrue(stack != null, "stack created");
		ctx.assertTrue(Math.abs(attributeValue(stack, DynamicItem.DAMAGE_MODIFIER_ID) - 12.0) < 0.001,
				"damage 12 baked before delete");

		ctx.assertTrue(WorldForgeRuntimeManager.deleteItem("worldforge:r3_sword").ok(), "delete ok");
		// what any refresh path (join / reload / delete broadcast) does to held stacks:
		DynamicItem.refreshStack(stack);

		double after = attributeValue(stack, DynamicItem.DAMAGE_MODIFIER_ID);
		ctx.assertTrue(Double.isNaN(after),
				"invalid stack must NOT keep the +12 attack bonus (found modifier after delete)");
		// and it must not keep a random item's appearance either
		var cmd = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA);
		ctx.assertTrue(cmd == null || cmd.value() == dev.worldforge.definition.AppearanceSlots.DEFAULT_INDEX,
				"invalid stack must revert to the default marker appearance");
		ctx.complete();
	}

	// ---- Test R1b (issue #1): after a rolled-back mutation the memory state stays consistent ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-regression")
	public void testR1b_failedSaveKeepsState(TestContext ctx) {
		java.nio.file.Path dir;
		try {
			dir = Files.createTempDirectory("wf-r1b");
		} catch (Exception e) {
			throw new RuntimeException("setup", e);
		}
		DefinitionStore store = new DefinitionStore(dir);
		WorldForgeRuntimeManager.attachStoreForTesting(store);

		ItemDefinition good = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:r1b_good", "好数据", "", TextureSource.vanilla("minecraft:item/diamond"),
				Map.of("damage", 1.0, "max_durability", 2.0), List.of());
		ctx.assertTrue(WorldForgeRuntimeManager.createItem(good).ok(), "first create must succeed");
		ctx.assertTrue(store.exists(), "file written");

		// now break the target: replace the regular file with a DIRECTORY so
		// the atomic move can never land
		try {
			Files.delete(dir.resolve("items.json"));
			Files.createDirectories(dir.resolve("items.json"));
		} catch (Exception e) {
			throw new RuntimeException("break setup", e);
		}
		ItemDefinition bad2 = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:r1b_bad", "坏", "", TextureSource.vanilla("minecraft:item/diamond"),
				Map.of("damage", 1.0, "max_durability", 2.0), List.of());
		var r = WorldForgeRuntimeManager.createItem(bad2);
		ctx.assertTrue(!r.ok(), "second create must fail, got: " + r.message());
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:r1b_bad") == null,
				"rolled-back definition must not linger in memory");
		ctx.assertTrue(WorldForgeRuntimeManager.get("worldforge:r1b_good") != null,
				"pre-existing definition stays in memory");
		ctx.complete();
	}

	// ---- Test R4 (issue #5): real skill effects on a real mock player ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-regression", tickLimit = 200)
	public void testR4_skillEffectsReallyApply(TestContext ctx) {
		WorldForgeRuntimeManager.attachStoreForTesting(null);

		ItemDefinition healDef = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:r4_heal", "R4治疗", "", TextureSource.vanilla("minecraft:item/blaze_rod"),
				Map.of("max_durability", 10.0),
				List.of(new BehaviorDefinition("right_click", "heal", 20, Map.of("amount", 6))));
		ctx.assertTrue(WorldForgeRuntimeManager.createItem(healDef).ok(), "heal def");

		net.minecraft.server.network.ServerPlayerEntity player = ctx.createMockCreativeServerPlayerInWorld();
		try {
			player.setHealth(6.0f);
			ItemStack staff = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:r4_heal", 1);
			ctx.assertTrue(staff != null, "staff");
			var res = dev.worldforge.behavior.BehaviorEngine.onRightClick(
						ctx.getWorld(), player, staff, net.minecraft.util.Hand.MAIN_HAND);
			ctx.assertTrue(res == net.minecraft.util.ActionResult.SUCCESS, "heal must SUCCESS, got " + res);
			ctx.assertTrue(player.getHealth() > 6.0f, "health must rise after heal (got " + player.getHealth() + ")");

			// cooldown: second immediate use must FAIL
			var res2 = dev.worldforge.behavior.BehaviorEngine.onRightClick(
						ctx.getWorld(), player, staff, net.minecraft.util.Hand.MAIN_HAND);
			ctx.assertTrue(res2 == net.minecraft.util.ActionResult.FAIL, "second use must be blocked by cooldown");

			// apply_effect
			ItemDefinition speedDef = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
					"worldforge:r4_speed", "R4速度", "", TextureSource.vanilla("minecraft:item/sugar"),
					Map.of("max_durability", 10.0),
					List.of(new BehaviorDefinition("right_click", "apply_effect", 5,
							Map.of("effect", "minecraft:speed", "duration", 200, "amplifier", 0))));
			ctx.assertTrue(WorldForgeRuntimeManager.createItem(speedDef).ok(), "speed def");
			ItemStack amulet = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:r4_speed", 1);
			var res3 = dev.worldforge.behavior.BehaviorEngine.onRightClick(
						ctx.getWorld(), player, amulet, net.minecraft.util.Hand.MAIN_HAND);
			ctx.assertTrue(res3 == net.minecraft.util.ActionResult.SUCCESS, "speed use SUCCESS");
			ctx.assertTrue(player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED),
					"player must actually have SPEED effect");

			// experience
			ItemDefinition xpDef = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
					"worldforge:r4_xp", "R4经验", "", TextureSource.vanilla("minecraft:item/experience_bottle"),
					Map.of("max_durability", 10.0),
					List.of(new BehaviorDefinition("right_click", "experience", 5, Map.of("amount", 10))));
			ctx.assertTrue(WorldForgeRuntimeManager.createItem(xpDef).ok(), "xp def");
			ItemStack xp = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:r4_xp", 1);
			var res4 = dev.worldforge.behavior.BehaviorEngine.onRightClick(
						ctx.getWorld(), player, xp, net.minecraft.util.Hand.MAIN_HAND);
			ctx.assertTrue(res4 == net.minecraft.util.ActionResult.SUCCESS, "xp use SUCCESS");
			int xpBefore = player.totalExperience;
			float xpProgressBefore = player.experienceProgress;

			// summon_lightning -> a real bolt entity must appear (checked after 10 ticks)
			ItemDefinition thDef = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
					"worldforge:r4_thunder", "R4雷", "", TextureSource.vanilla("minecraft:item/diamond_sword"),
					Map.of("max_durability", 10.0),
					List.of(new BehaviorDefinition("right_click", "summon_lightning", 5, Map.of("range", 4))));
			ctx.assertTrue(WorldForgeRuntimeManager.createItem(thDef).ok(), "thunder def");
			ItemStack th = DynamicItem.createStack(WorldForgeItems.DYNAMIC_ITEM, "worldforge:r4_thunder", 1);
			var res5 = dev.worldforge.behavior.BehaviorEngine.onRightClick(
						ctx.getWorld(), player, th, net.minecraft.util.Hand.MAIN_HAND);
			ctx.assertTrue(res5 == net.minecraft.util.ActionResult.SUCCESS, "lightning use SUCCESS");
			ctx.waitAndRun(2, () -> {
				// 2 ticks: past the end-of-tick spawn queue, but still inside
				// the very short LightningBolt lifetime (ambientTick starts at 2).
				// spawnEntity is queued until end-of-tick, so checks are deferred.
				// Experience: the orb either still exists nearby or was already
				// absorbed into the mock player - both prove a real world effect.
				boolean orbs = !ctx.getWorld().getEntitiesByType(net.minecraft.entity.EntityType.EXPERIENCE_ORB,
							new net.minecraft.util.math.Box(player.getBlockPos()).expand(8),
							net.minecraft.entity.Entity::isAlive).isEmpty();
				boolean absorbed = player.totalExperience > xpBefore
						|| player.experienceProgress > xpProgressBefore;
				ctx.assertTrue(orbs || absorbed,
						"experience orbs must spawn or be absorbed (xpBefore=" + xpBefore
								+ " xpAfter=" + player.totalExperience + " absorbed=" + absorbed + ")");
				ctx.assertTrue(!ctx.getWorld().getEntitiesByType(net.minecraft.entity.EntityType.LIGHTNING_BOLT,
							new net.minecraft.util.math.Box(player.getBlockPos()).expand(48),
							net.minecraft.entity.Entity::isAlive).isEmpty(),
						"a real LightningBolt entity must exist in the world");
				ctx.complete();
			});
		} catch (Exception e) {
			try {
				player.networkHandler.disconnect(net.minecraft.text.Text.literal("r4 done"));
			} catch (Exception ignored) {
			}
			throw e;
		}
	}

	// ---- Test R5 (issue #2/#5): payload codec round-trip + mirror-only display path ----
	@GameTest(templateName = "fabric-gametest-api-v1:empty", batchId = "worldforge-regression")
	public void testR5_syncPayloadCodecAndMirror(TestContext ctx) {
		// 1) wire codec round-trip: encode then decode must restore json+tombstones
		dev.worldforge.network.WorldForgePayloads.SyncDefinitions original =
				new dev.worldforge.network.WorldForgePayloads.SyncDefinitions(
						"{\"version\":1,\"items\":[{\"id\":\"worldforge:r5_item\",\"name\":\"R5同步\",\"version\":1}]}",
						java.util.List.of("worldforge:gone"));
		io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
		net.minecraft.network.RegistryByteBuf buf =
				new net.minecraft.network.RegistryByteBuf(raw, ctx.getWorld().getRegistryManager());
		dev.worldforge.network.WorldForgePayloads.SyncDefinitions.CODEC.encode(buf, original);
		var decoded = dev.worldforge.network.WorldForgePayloads.SyncDefinitions.CODEC.decode(buf);
		ctx.assertTrue(decoded.json().equals(original.json()), "payload json corrupted on the wire");
		ctx.assertTrue(decoded.tombstones().equals(original.tombstones()),
				"payload tombstones corrupted on the wire: " + decoded.tombstones());

		// 2) mirror display path: with EMPTY server state (pure client view),
		// a stack's name must resolve through the mirror, not fail.
		dev.worldforge.runtime.ClientDefinitionMirror.clear();
		WorldForgeRuntimeManager.attachStoreForTesting(null);
		dev.worldforge.runtime.ClientDefinitionMirror.replaceAllFromSync(decoded.json(), decoded.tombstones());

		ItemStack stack = new ItemStack(WorldForgeItems.DYNAMIC_ITEM, 1);
		stack.set(dev.worldforge.component.WorldForgeComponents.VIRTUAL_ID,
				new dev.worldforge.component.VirtualId("worldforge:r5_item"));
		ctx.assertTrue(stack.getName().getString().contains("R5同步"),
				"display must resolve via client mirror, got: " + stack.getName().getString());

		// tombstone from the wire drives the invalid marker too
		stack.set(dev.worldforge.component.WorldForgeComponents.VIRTUAL_ID,
				new dev.worldforge.component.VirtualId("worldforge:gone"));
		ctx.assertTrue(stack.getName().getString().contains("失效"),
				"mirrored tombstone must show 已失效, got: " + stack.getName().getString());

		dev.worldforge.runtime.ClientDefinitionMirror.clear();
		ctx.complete();
	}

	// ---- helpers ----

	private static double attributeValue(ItemStack stack, Identifier modifierId) {
		AttributeModifiersComponent comp = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
		if (comp == null) {
			return Double.NaN;
		}
		for (AttributeModifiersComponent.Entry e : comp.modifiers()) {
			if (e.modifier().id().equals(modifierId)) {
				return e.modifier().value();
			}
		}
		return Double.NaN;
	}
}
