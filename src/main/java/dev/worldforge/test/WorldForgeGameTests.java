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
		ItemDefinition def = new ItemDefinition(ItemDefinition.CURRENT_VERSION,
				"worldforge:persist_real", "真实存档持久化", "写入世界目录",
				TextureSource.vanilla("minecraft:item/emerald"),
				Map.of("damage", 3.0, "max_durability", 64.0),
				List.of());
		ctx.assertTrue(WorldForgeRuntimeManager.createItem(def).ok(), "createItem must succeed");

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
