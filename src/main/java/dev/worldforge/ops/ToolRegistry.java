package dev.worldforge.ops;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The catalogue of WF-Ops tools.
 *
 * <p>Registration happens once at server start. The registry is the single
 * source of truth for "what can the AI do" — the model never learns about a
 * tool that is not registered here, and a registered tool is the only way the
 * model can cause anything to happen.</p>
 *
 * <p>There is deliberately NO generic "run command" or "execute code" tool:
 * the layer exposes only WorldForge's own structured operations.</p>
 */
public final class ToolRegistry {

	/** A tool as the registry knows it: contract + implementation + preparation. */
	public record RegisteredTool(ToolDescriptor descriptor, ToolExecutor executor, ToolPreparer preparer) {
		public RegisteredTool {
			preparer = preparer == null ? (ctx, req) -> ToolPreparation.none() : preparer;
		}
	}

	private static final Map<String, RegisteredTool> TOOLS = new ConcurrentHashMap<>();
	/** insertion order for stable capability listings */
	private static final List<String> ORDER = java.util.Collections.synchronizedList(new ArrayList<>());

	private ToolRegistry() {
	}

	/** Registers a read-only tool. */
	public static void register(ToolDescriptor descriptor, ToolExecutor executor) {
		register(descriptor, executor, null);
	}

	/**
	 * Registers a tool.
	 *
	 * @param preparer computes the intended impact before confirmation; a
	 *                mutating tool without one can never be confirmed and
	 *                therefore never executes
	 */
	public static void register(ToolDescriptor descriptor, ToolExecutor executor, ToolPreparer preparer) {
		if (descriptor == null || executor == null) {
			throw new IllegalArgumentException("descriptor and executor are required");
		}
		TOOLS.put(descriptor.name(), new RegisteredTool(descriptor, executor, preparer));
		synchronized (ORDER) {
			if (!ORDER.contains(descriptor.name())) {
				ORDER.add(descriptor.name());
			}
		}
	}

	public static Optional<RegisteredTool> tool(String name) {
		return name == null ? Optional.empty() : Optional.ofNullable(TOOLS.get(name));
	}

	public static Optional<ToolDescriptor> descriptor(String name) {
		return tool(name).map(RegisteredTool::descriptor);
	}

	public static Optional<ToolExecutor> executor(String name) {
		return tool(name).map(RegisteredTool::executor);
	}

	public static boolean knows(String name) {
		return name != null && TOOLS.containsKey(name);
	}

	/** All registered tools, in registration order. */
	public static List<RegisteredTool> allTools() {
		List<RegisteredTool> out = new ArrayList<>();
		synchronized (ORDER) {
			for (String n : ORDER) {
				RegisteredTool t = TOOLS.get(n);
				if (t != null) {
					out.add(t);
				}
			}
		}
		return out;
	}

	/** All descriptors, in registration order. */
	public static List<ToolDescriptor> all() {
		List<ToolDescriptor> out = new ArrayList<>();
		for (RegisteredTool t : allTools()) {
			out.add(t.descriptor());
		}
		return out;
	}

	/** Descriptors the given player is actually allowed to see and call. */
	public static List<ToolDescriptor> availableTo(net.minecraft.server.network.ServerPlayerEntity player) {
		List<ToolDescriptor> out = new ArrayList<>();
		for (ToolDescriptor d : all()) {
			if (PermissionGate.checkAuthority(d, player) == null) {
				out.add(d);
			}
		}
		return out;
	}

	/** The OpenAI-compatible {@code tools[]} array for the model. */
	public static List<JsonObject> toOpenAiTools(Collection<ToolDescriptor> descriptors) {
		List<JsonObject> out = new ArrayList<>();
		for (ToolDescriptor d : descriptors) {
			out.add(d.toOpenAiTool());
		}
		return out;
	}

	/** Human-readable capability listing for {@code list_capabilities}. */
	public static Map<String, Object> capabilityReport(net.minecraft.server.network.ServerPlayerEntity player) {
		Map<String, Object> out = new LinkedHashMap<>();
		var readable = new ArrayList<Map<String, Object>>();
		var writable = new ArrayList<Map<String, Object>>();
		for (ToolDescriptor d : availableTo(player)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("name", d.name());
			entry.put("purpose", d.description());
			entry.put("mutates_world", d.mutatesWorld());
			entry.put("requires_confirmation", d.requiresConfirmation());
			entry.put("arguments", d.argumentNames());
			(d.permission() == ToolPermission.READ ? readable : writable).add(entry);
		}
		out.put("read_only_tools", readable);
		out.put("mutating_tools", writable);
		out.put("note", "mutating tools require player confirmation and server-side re-validation");
		return out;
	}

	/** Test/embedding hook: drop every registration. */
	public static void resetForTest() {
		TOOLS.clear();
		synchronized (ORDER) {
			ORDER.clear();
		}
	}
}
