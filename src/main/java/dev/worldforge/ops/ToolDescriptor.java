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
 * @param mutatesWorld    true when a successful call changes world state
 * @param requiresConfirmation true when the player must approve a summary first
 * @param longRunning     true when the call takes minutes (a Gradle build) and
 *                        therefore must NOT run on the server thread
 */
public record ToolDescriptor(
		String name,
		String description,
		JsonObject parameters,
		ToolPermission permission,
		boolean mutatesWorld,
		boolean requiresConfirmation,
		boolean longRunning) {

	public ToolDescriptor {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("tool name is required");
		}
		description = description == null ? "" : description;
		parameters = parameters == null ? new JsonObject() : parameters;
		permission = permission == null ? ToolPermission.READ : permission;
	}

	public static ToolDescriptor read(String name, String description, JsonObject parameters) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.READ, false, false, false);
	}

	public static ToolDescriptor write(String name, String description, JsonObject parameters) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.WRITE, true, true, false);
	}

	public static ToolDescriptor admin(String name, String description, JsonObject parameters) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.ADMIN, true, true, false);
	}

	/** A write tool with an explicit permission level. */
	public static ToolDescriptor of(String name, String description, JsonObject parameters,
			ToolPermission permission, boolean mutatesWorld, boolean requiresConfirmation) {
		return new ToolDescriptor(name, description, parameters, permission, mutatesWorld,
				requiresConfirmation, false);
	}

	/** A tool whose execution takes minutes and must not block the server thread. */
	public static ToolDescriptor longRunningAdmin(String name, String description, JsonObject parameters) {
		return new ToolDescriptor(name, description, parameters, ToolPermission.ADMIN, true, true, true);
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
