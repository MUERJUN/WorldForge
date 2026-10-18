package dev.worldforge.ai;

/**
 * Masking state machine for the in-game API key field (v0.4.0 review fix).
 *
 * <p>The previous implementation merely refused to PRE-FILL a stored key; a
 * newly typed key was still rendered in clear text by the plain
 * {@code TextFieldWidget}, so it could appear in a screenshot, a stream or a
 * screen recording.</p>
 *
 * <p>This helper keeps the real secret out of the widget entirely: the widget
 * only ever contains asterisks, and typing/pasting/deleting is inferred from
 * the difference between the previous display and the new one. Kept free of
 * Minecraft imports so the rules are unit-tested rather than eyeballed.</p>
 */
public final class SecretInput {

	public record State(String real, String display) {
	}

	public static final char MASK = '*';

	public static String mask(int length) {
		return String.valueOf(MASK).repeat(Math.max(0, length));
	}

	public static State empty() {
		return new State("", "");
	}

	/**
	 * Reconcile a widget edit with the real secret.
	 *
	 * <p>Two families of edit are distinguished by what the widget now
	 * contains:</p>
	 *
	 * <ul>
	 *   <li><b>No mask character</b> - the field was effectively REPLACED: the
	 *       player selected all (or cleared) and typed/pasted new text. The new
	 *       text IS the new secret. This is the case the previous
	 *       length-only inference got wrong: replacing a 20-character key with a
	 *       7-character one used to keep a stale 7-character PREFIX of the old
	 *       key and silently discard the pasted value.</li>
	 *   <li><b>Contains mask characters</b> - the edit happened over our own
	 *       masked rendering: appending extends the secret, deleting a trailing
	 *       run truncates it, and any other (mid-field) edit keeps the secret
	 *       and snaps the display back to a correct mask, so an ambiguous edit
	 *       can never corrupt or reveal the stored value.</li>
	 * </ul>
	 */
	public static State apply(String previousReal, String newDisplay) {
		String prev = previousReal == null ? "" : previousReal;
		String display = newDisplay == null ? "" : newDisplay;
		String prevMask = mask(prev.length());

		if (display.indexOf(MASK) < 0) {
			// full replacement: select-all + type/paste, or clear then type
			return new State(display, mask(display.length()));
		}
		if (display.length() > prev.length() && display.startsWith(prevMask)) {
			return new State(prev + display.substring(prev.length()),
					mask(prev.length() + (display.length() - prev.length())));
		}
		if (display.length() <= prev.length() && prevMask.startsWith(display)) {
			// trailing deletion (backspace / delete at the end)
			return new State(prev.substring(0, display.length()), mask(display.length()));
		}
		// ambiguous mid-field edit over the mask: keep the secret, fix the display
		return new State(prev, prevMask);
	}

	/** display form of a known secret */
	public static String display(String real) {
		return mask(real == null ? 0 : real.length());
	}

	public static boolean isMasked(String display) {
		return display != null && display.chars().allMatch(c -> c == MASK);
	}

	private SecretInput() {
	}
}