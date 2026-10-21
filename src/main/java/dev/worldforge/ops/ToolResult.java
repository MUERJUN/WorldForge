package dev.worldforge.ops;

import java.util.List;

/**
 * The outcome of one tool execution.
 *
 * <p>Three separable facts, mirroring the existing three-phase tool result
 * ({@code persisted} / {@code applied} / {@code sync}): a tool must never
 * report a blanket "success" when only part of the work landed. The chat UI
 * renders these separately, exactly as they happened.</p>
 *
 * @param ok             true only when the intended effect really happened
 * @param errorCode      stable machine-readable code, or {@code null} on success
 * @param displayText    human-readable summary shown in the chat stream
 * @param dataJson       tool payload returned to the model (never contains secrets)
 * @param affectedTargets ids of items/blocks the operation actually touched
 * @param persisted      the change reached durable storage
 * @param applied        the change reached live server state
 * @param syncStatus     client snapshot broadcast status
 */
public record ToolResult(
		boolean ok,
		String errorCode,
		String displayText,
		String dataJson,
		List<String> affectedTargets,
		boolean persisted,
		boolean applied,
		String syncStatus) {

	public ToolResult {
		errorCode = errorCode == null ? "" : errorCode;
		displayText = displayText == null ? "" : displayText;
		dataJson = dataJson == null ? "{}" : dataJson;
		affectedTargets = affectedTargets == null ? List.of() : List.copyOf(affectedTargets);
		syncStatus = syncStatus == null ? "none" : syncStatus;
	}

	/** Successful read-only result. */
	public static ToolResult ok(String displayText, String dataJson) {
		return new ToolResult(true, "", displayText, dataJson, List.of(), true, true, "none");
	}

	/** Successful read-only result that also names what it observed. */
	public static ToolResult ok(String displayText, String dataJson, List<String> targets) {
		return new ToolResult(true, "", displayText, dataJson, targets, true, true, "none");
	}

	/** Failure with a stable error code the model can react to. */
	public static ToolResult fail(String errorCode, String displayText) {
		return new ToolResult(false, errorCode, displayText, "{}", List.of(), false, false, "none");
	}

	/**
	 * A write that persisted and applied but whose client broadcast did not
	 * fully succeed. The definition IS live on the server; the client view may
	 * lag. The chat must say exactly that — never "everything failed".
	 */
	public static ToolResult partial(String displayText, String dataJson, String syncStatus) {
		return new ToolResult(true, "", displayText, dataJson, List.of(), true, true, syncStatus);
	}

	/**
	 * A write that reached disk but failed to apply to live state (or vice
	 * versa). Never reported as plain success.
	 */
	public static ToolResult degraded(String errorCode, String displayText, boolean persisted, boolean applied) {
		return new ToolResult(false, errorCode, displayText, "{}", List.of(), persisted, applied, "none");
	}
}
