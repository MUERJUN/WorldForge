package dev.worldforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.worldforge.command.WorldForgeCommand;
import dev.worldforge.component.WorldForgeComponents;
import dev.worldforge.definition.AppearanceSlots;
import dev.worldforge.event.WorldForgeEvents;
import dev.worldforge.network.WorldForgePayloads;
import dev.worldforge.registry.WorldForgeBlocks;
import dev.worldforge.registry.WorldForgeItems;
import dev.worldforge.runtime.WorldForgeRuntimeManager;

/**
 * WorldForge - runtime dynamic item engine (MVP 0.1).
 *
 * <p>Architecture in one paragraph: exactly ONE native item is registered
 * ({@code worldforge:dynamic_item}). Every virtual item is an ItemStack of
 * that item carrying the {@code worldforge:virtual_id} data component. The
 * virtual item's definition (name, stats, behaviors) lives in a per-world
 * {@code worldforge/definitions.json} file, managed at runtime by
 * {@link WorldForgeRuntimeManager}. Because identity lives in data components
 * and definitions live in per-world files, definitions can be created,
 * updated and deleted while the game runs, persist across world sessions, and
 * stay isolated between saves. Display name, lore, durability, attack damage,
 * cooldown and appearance (custom_model_data slot) are all resolved from the
 * definition every time the item is used or inspected, so hot edits apply to
 * stacks already in inventories without rebuilding them.</p>
 */
public class WorldForge implements ModInitializer {
	public static final String MOD_ID = "worldforge";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		LOGGER.info("WorldForge {} initialising (MVP 0.1)", MOD_ID);

		// Order matters: component types -> item -> payloads -> events -> commands.
		WorldForgeComponents.register();
		WorldForgeItems.register();
		WorldForgeBlocks.register(); // v0.4.0: one generic dynamic block + its block entity type
		WorldForgePayloads.register();
		dev.worldforge.network.WorldForgeStudioProtocol.register();
		// v0.7.0: the chat-first protocol and the WF-Ops tool catalogue
		dev.worldforge.network.WorldForgeChatProtocol.register();
		dev.worldforge.ops.ToolCatalog.registerAll();
		// v0.7.2: the hook that lets a VANILLA item carry a grafted behaviour
		dev.worldforge.behavior.VanillaBehaviorHooks.register();
		WorldForgeEvents.register();
		WorldForgeCommand.register();

		int slots = dev.worldforge.definition.AppearanceSlots.count();
		LOGGER.info("[WorldForge] ready: 1 native dynamic item, {} pre-baked appearance slots, {} WF-Ops tools",
				slots, dev.worldforge.ops.ToolRegistry.all().size());
	}
}
