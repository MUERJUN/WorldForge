package dev.worldforge.ops;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * The static contract of a WF-Ops tool: what it is called, what it does, what
 * arguments it accepts and what authority it needs.
 *
 * <p>Descriptors are the ONLY thing the model ever sees about the tool layer.
 * The model may not learn internal class names, Java types or file paths —
 * it sees a name, a human description and a JSON Schema.</p>
 *
 * @param name            stable tool name as the model calls it (snake_case)
 * @param description     one-paragraph purpose, shown to the model
 * @param parameters      JSON Schema (draft-07 subset) of the arguments
 * @param permission      authority level required to invoke it
 * @param mutatesWorld    true when a successful call changes the WORLD
 *                        (a definition, a placed block, a file). A tool that
 *                        changes only the player's own item does NOT set it.
 * @param requiresConfirmation true when the player must approve a summary first
 * @param longRunning     true when the call takes minutes (a Gradle build) and
 *                        therefore must NOT run on the server thread
 * @param timeoutSeconds  how long this tool may take. It is never shorter
 *                        than the work the tool actually waits for: a tool
 *                        that runs a 20-minute build must not declare a
 *                        shorter budget, or a healthy slow build would be
 *                        reported as a failure.
 */
public record ToolDescriptor(
		String name,
		String description,
		JsonObject parameters,
		ToolPermission permission,
		boolean mutatesWorld,
		boolean requiresConfirmation,
		boolean longRunning,
		int timeoutSeconds) {

	/** The default budget of a tool that does not declare its own. */
	public static final int DEFAULT_TIMEOUT_SECONDS = 120;

	public ToolDescriptor {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("tool name is required");
		}
		description = description == null ? "" : description;
		parameters = parameters == null ? new JsonObject() : parameters;
		permission = permission == null ? ToolPermission.READ : permission;
	}

	public static ToolDescriptor read(String name, String description, JsonObject parameters) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.READ, false, false, false,
				DEFAULT_TIMEOUT_SECONDS);
	}

	public static ToolDescriptor write(String name, String description, JsonObject parameters) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.WRITE, true, true, false,
				DEFAULT_TIMEOUT_SECONDS);
	}

	public static ToolDescriptor admin(String name, String description, JsonObject parameters) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.ADMIN, true, true, false,
				DEFAULT_TIMEOUT_SECONDS);
	}

	/** A tool that changes only the player's own items; no admin authority. */
	public static ToolDescriptor self(String name, String description, JsonObject parameters) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.SELF, false, true, false,
				DEFAULT_TIMEOUT_SECONDS);
	}

	/** A write tool with an explicit permission level. */
	public static ToolDescriptor of(String name, String description, JsonObject parameters,
			ToolPermission permission, boolean mutatesWorld, boolean requiresConfirmation) {
		return new ToolDescriptor(name, description, parameters, permission, mutatesWorld,
				requiresConfirmation, false, DEFAULT_TIMEOUT_SECONDS);
	}

	/** A tool whose execution takes minutes and must not block the server thread. */
	public static ToolDescriptor longRunningAdmin(String name, String description, JsonObject parameters,
			int timeoutSeconds) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.ADMIN, true, true, true,
				timeoutSeconds);
	}

	/**
	 * The OpenAI-compatible {@code tools[]} entry for this descriptor.
	 *
	 * <p>Only the name, the description and the JSON Schema cross the network
	 * boundary — no internal state, no class names.</p>
	 */
	public JsonObject toOpenAiTool() {
		JsonObject fn = new JsonObject();
		fn.addProperty("name", name);
		fn.addProperty("description", description);
		fn.add("parameters", parameters);
		JsonObject tool = new JsonObject();
		tool.addProperty("type", "function");
		tool.add("function", fn);
		return tool;
	}

	/** Compact human-readable one-liner used in the chat event stream. */
	public String summary(Map<String, Object> arguments) {
		return name + (arguments == null || arguments.isEmpty() ? "" : " " + arguments);
	}

	/** Argument names this tool accepts, in schema order. */
	public List<String> argumentNames() {
		if (!parameters.has("properties")) {
			return List.of();
		}
		var props = parameters.getAsJsonObject("properties");
		return props.keySet().stream().toList();
	}
}
