package dev.worldforge.ai;

/**
 * A real password-editing model (v0.4.0 phase 2b).
 *
 * <p>Replaces the previous "infer the edit from the masked string length"
 * approach, which could not tell a mid-field delete from a trailing one and
 * quietly reinterpreted ambiguous edits. This model owns the real value, the
 * caret and the selection anchor, so every editing operation has an exact,
 * testable meaning and the rendering layer only ever sees a mask.</p>
 *
 * <p><b>Secrecy contract.</b> {@link #toString()} deliberately does NOT contain
 * the value - the previous {@code record State(String real, String display)}
 * leaked the whole key through the auto-generated {@code toString()}, which any
 * debug log, exception message or IDE inspection would print. Use
 * {@link #masked()} for display and {@link #value()} only where the secret is
 * actually needed (building the Authorization header).</p>
 */
public final class PasswordEdit {

	private final StringBuilder value = new StringBuilder();
	private int caret;
	private int anchor; // -1 = no selection; otherwise selection spans [min(caret,anchor), max)

	public PasswordEdit() {
	}

	public PasswordEdit(String initial) {
		if (initial != null) {
			value.append(initial);
		}
		caret = value.length();
		anchor = -1;
	}

	// ---------------------------------------------------------------- accessors

	/** the real secret; call only where the value is genuinely required */
	public String value() {
		return value.toString();
	}

	public int length() {
		return value.length();
	}

	public boolean isEmpty() {
		return value.length() == 0;
	}

	public int caret() {
		return caret;
	}

	public boolean hasSelection() {
		return anchor >= 0 && anchor != caret;
	}

	public int selectionStart() {
		return hasSelection() ? Math.min(caret, anchor) : caret;
	}

	public int selectionEnd() {
		return hasSelection() ? Math.max(caret, anchor) : caret;
	}

	/** what the widget may render: one mask character per real character */
	public String masked() {
		return "*".repeat(value.length());
	}

	/**
	 * Safe string form. Contains the length only - never the value, so logging
	 * an instance, printing it in an exception or inspecting it in a debugger
	 * cannot disclose the secret.
	 */
	@Override
	public String toString() {
		return "PasswordEdit{length=" + value.length() + ", caret=" + caret
				+ (hasSelection() ? ", selection=" + selectionStart() + ".." + selectionEnd() : "") + "}";
	}

	// ---------------------------------------------------------------- editing

	/** replace the selection (if any) with {@code text} */
	public PasswordEdit insert(String text) {
		if (text == null || text.isEmpty()) {
			return this;
		}
		// strip control characters so a paste cannot inject newlines into a secret
		String clean = text.replaceAll("[\\r\\n\\t]", "");
		deleteSelection();
		value.insert(caret, clean);
		caret += clean.length();
		anchor = -1;
		return this;
	}

	/** Backspace: delete the selection, or the character BEFORE the caret */
	public PasswordEdit backspace() {
		if (deleteSelection()) {
			return this;
		}
		if (caret > 0) {
			value.deleteCharAt(caret - 1);
			caret--;
		}
		return this;
	}

	/** Delete: delete the selection, or the character AT the caret */
	public PasswordEdit delete() {
		if (deleteSelection()) {
			return this;
		}
		if (caret < value.length()) {
			value.deleteCharAt(caret);
		}
		return this;
	}

	private boolean deleteSelection() {
		if (!hasSelection()) {
			return false;
		}
		int from = selectionStart();
		int to = selectionEnd();
		value.delete(from, to);
		caret = from;
		anchor = -1;
		return true;
	}

	public PasswordEdit home() {
		caret = 0;
		anchor = -1;
		return this;
	}

	public PasswordEdit end() {
		caret = value.length();
		anchor = -1;
		return this;
	}

	/** Ctrl+A */
	public PasswordEdit selectAll() {
		anchor = 0;
		caret = value.length();
		return this;
	}

	public PasswordEdit moveLeft(boolean extendSelection) {
		int next = hasSelection() && !extendSelection ? selectionStart() : Math.max(0, caret - 1);
		moveTo(next, extendSelection);
		return this;
	}

	public PasswordEdit moveRight(boolean extendSelection) {
		int next = hasSelection() && !extendSelection ? selectionEnd() : Math.min(value.length(), caret + 1);
		moveTo(next, extendSelection);
		return this;
	}

	private void moveTo(int next, boolean extendSelection) {
		if (extendSelection) {
			if (anchor < 0) {
				anchor = caret;
			}
		} else {
			anchor = -1;
		}
		caret = next;
	}

	/** clear everything (used after a save so the field is empty again) */
	public PasswordEdit clear() {
		value.setLength(0);
		caret = 0;
		anchor = -1;
		return this;
	}

	/** replace the whole value (pasting into an empty field, tests, preset loading) */
	public PasswordEdit setValue(String newValue) {
		value.setLength(0);
		if (newValue != null) {
			value.append(newValue);
		}
		caret = value.length();
		anchor = -1;
		return this;
	}

	// ---------------------------------------------------------------- validation helpers

	/** true when the text looks like a real credential rather than a placeholder */
	public boolean looksConfigured() {
		return !AgentConfig.keyIsPlaceholder(value.toString());
	}

	/**
	 * Scrub this value out of arbitrary text. Any string that might be shown in
	 * the GUI, logged or attached to an exception must pass through here first.
	 */
	public String scrubFrom(String text) {
		return AgentConnectionTest.scrub(text, value.toString());
	}
}