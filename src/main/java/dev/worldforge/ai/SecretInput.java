package dev.worldforge.ai;

/**
 * Backwards-compatible facade over {@link PasswordEdit} (v0.4.0 phase 2b).
 *
 * <p>The previous version exposed {@code record State(String real, String
 * display)}; the auto-generated {@code toString()} printed the API key, so any
 * log line, exception message or debugger inspection disclosed the secret. This
 * class keeps the old entry points but never carries the value in an object
 * whose {@code toString()} could be logged: the editor owns the secret and its
 * {@code toString()} reports only its length.</p>
 */
public final class SecretInput {

	public static final char MASK = '*';

	/** the widget text for a secret of this length */
	public static String mask(int length) {
		return String.valueOf(MASK).repeat(Math.max(0, length));
	}

	/** a fresh editor (empty) */
	public static PasswordEdit editor() {
		return new PasswordEdit();
	}

	/** an editor seeded with a value (used only for tests / preset loading) */
	public static PasswordEdit editor(String initial) {
		return new PasswordEdit(initial);
	}

	public static boolean isMasked(String display) {
		return display != null && display.chars().allMatch(c -> c == MASK);
	}

	/** display form of a known secret: never includes any character of it */
	public static String display(String real) {
		return mask(real == null ? 0 : real.length());
	}

	private SecretInput() {
	}
}
