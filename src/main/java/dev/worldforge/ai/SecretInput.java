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
	 * <ul>
	 *   <li>text appended at the end (typing, pasting) -> appended to the secret</li>
	 *   <li>text shortened (backspace, delete, select-all + delete) -> secret truncated</li>
	 *   <li>any other edit (e.g. inserting in the middle of a masked field) ->
	 *       the secret is left untouched and the display snaps back to the
	 *       correct mask length, so a partial overwrite can never corrupt or
	 *       reveal the stored value</li>
	 * </ul>
	 */
	public static State apply(String previousReal, String newDisplay) {
		String prev = previousReal == null ? "" : previousReal;
		String display = newDisplay == null ? "" : newDisplay;
		String prevMask = mask(prev.length());

		if (display.length() > prev.length() && display.startsWith(prevMask)) {
			String appended = display.substring(prev.length());
			// an appended run must be the raw characters the user typed; if it
			// contains our own mask character we cannot tell them apart, so fall
			// back to the safe path instead of guessing a secret
			if (appended.indexOf(MASK) < 0) {
				return new State(prev + appended, mask(prev.length() + appended.length()));
			}
		}
		if (display.length() <= prev.length()) {
			return new State(prev.substring(0, display.length()), mask(display.length()));
		}
		return new State(prev, prevMask); // non-prefix edit: keep the secret, fix the display
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