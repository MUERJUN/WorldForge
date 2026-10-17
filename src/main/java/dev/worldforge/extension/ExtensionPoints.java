package dev.worldforge.extension;

/**
 * Future extension surface of WorldForge (spec section 9).
 *
 * <p>MVP 0.1 ships these as lightweight documentation-only markers so the
 * architecture is explicit without dead abstraction. Later milestones replace
 * each marker with a real implementation in its own package:</p>
 *
 * <ul>
 *   <li>{@code WorldForgeProject} - a named collection of definitions bound to
 *       a world; today the implicit project is the per-world
 *       {@code worldforge/} directory managed by WorldForgeRuntimeManager.</li>
 *   <li>{@code ItemDefinition} - IMPLEMENTED (definition/).</li>
 *   <li>{@code BlockDefinition}, {@code EntityDefinition} - planned: same
 *       generic-proxy-item pattern plus a data-driven block/entity registry
 *       hook; blocked on 1.21.1 native registry freezing, needs runtime
 *       re-registration experiments or proxy-block techniques.</li>
 *   <li>{@code BehaviorDefinition} - IMPLEMENTED (definition/ + behavior/).</li>
 *   <li>{@code RuntimeManager} - IMPLEMENTED as
 *       WorldForgeRuntimeManager (currently static; will become an instance
 *       owned by WorldForgeProject when projects land).</li>
 *   <li>{@code AssetManager} - planned: turn the fixed AppearanceSlots catalog
 *       into dynamic slot allocation backed by temporary resource packs
 *       (AI {@code generated} / user {@code custom} textures), with
 *       client reload - the fallback chain (vanilla -> default) already
 *       exists and stays.</li>
 *   <li>{@code AgentBridge} - planned: a tool-call API over
 *       WorldForgeRuntimeManager (create/update/delete/list) that an LLM
 *       agent can drive safely; the command layer in /wf already mirrors the
 *       exact tool surface a bridge would expose.</li>
 *   <li>{@code ModExporter} - planned: emit definitions + assets as a
 *       standalone datapack/resource-pack or JAR.</li>
 * </ul>
 */
public final class ExtensionPoints {

	private ExtensionPoints() {
	}
}
