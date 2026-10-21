package dev.worldforge.client.chat;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Small drawing helpers for the chat screen.
 *
 * <p>The look is deliberately restrained: a dark translucent surface, a
 * modest corner radius and clear spacing. No blur (the game stays visible
 * behind a flat scrim), no gradients, no animation.</p>
 */
public final class ChatUi {

	/** the scrim over the game */
	public static final int SCRIM = 0xB0101016;
	/** the main panel */
	public static final int PANEL = 0xF2181B24;
	/** the header strip */
	public static final int HEADER = 0xFF232733;
	/** a raised element inside the panel */
	public static final int ELEVATED = 0xFF2C3140;
	/** the message bubble of the player */
	public static final int BUBBLE_PLAYER = 0xFF2E3A52;
	/** a thin border */
	public static final int BORDER = 0xFF39404F;
	/** normal text */
	public static final int TEXT = 0xFFE6E9F0;
	/** dimmed text */
	public static final int TEXT_DIM = 0xFF9AA3B5;
	/** success text */
	public static final int TEXT_OK = 0xFF6FCF8E;
	/** warning text */
	public static final int TEXT_WARN = 0xFFE8C46A;
	/** error text */
	public static final int TEXT_ERR = 0xFFE87A7A;
	/** the accent used for the assistant */
	public static final int ACCENT = 0xFF7FB2FF;

	private ChatUi() {
	}

	/**
	 * A rectangle with rounded corners.
	 *
	 * <p>Implemented with plain fills (no shader): the corners are stepped
	 * with a small square approximation, which at the radii used here is
	 * visually indistinguishable from a true rounded rect and costs nothing.</p>
	 */
	public static void roundedRect(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) {
			return;
		}
		if (r <= 0) {
			ctx.fill(x, y, x + w, y + h, color);
			return;
		}
		int rr = Math.min(r, Math.min(w, h) / 2);
		ctx.fill(x + rr, y, x + w - rr, y + h, color);
		ctx.fill(x, y + rr, x + rr, y + h - rr, color);
		ctx.fill(x + w - rr, y + rr, x + w, y + h - rr, color);
		// corner steps
		for (int i = 0; i < rr; i++) {
			int inset = rr - (int) Math.round(Math.sqrt(Math.max(0, rr * rr - (rr - i - 1) * (rr - i - 1))));
			ctx.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			ctx.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
		}
	}

	/** A rounded rectangle with a 1px border. */
	public static void roundedOutline(DrawContext ctx, int x, int y, int w, int h, int r, int fill, int border) {
		roundedRect(ctx, x, y, w, h, r, fill);
		roundedRect(ctx, x, y, w, 1, 0, border);
		roundedRect(ctx, x, y + h - 1, w, 1, 0, border);
		roundedRect(ctx, x, y, 1, h, 0, border);
		roundedRect(ctx, x + w - 1, y, 1, h, 0, border);
	}

	/**
	 * Wraps text to the given width, breaking on spaces where possible and
	 * hard-breaking a single very long token (a URL, a long id).
	 */
	public static List<String> wrap(TextRenderer tr, String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return out;
		}
		if (maxWidth <= 10) {
			out.add(text);
			return out;
		}
		for (String rawLine : text.split("\n", -1)) {
			if (rawLine.isEmpty()) {
				out.add("");
				continue;
			}
			StringBuilder current = new StringBuilder();
			for (String word : rawLine.split(" ", -1)) {
				String candidate = current.isEmpty() ? word : current + " " + word;
				if (tr.getWidth(candidate) <= maxWidth) {
					current.setLength(0);
					current.append(candidate);
					continue;
				}
				if (!current.isEmpty()) {
					out.add(current.toString());
					current.setLength(0);
				}
				// the word alone may still be too wide
				if (tr.getWidth(word) <= maxWidth) {
					current.append(word);
					continue;
				}
				StringBuilder hard = new StringBuilder();
				for (char c : word.toCharArray()) {
					if (tr.getWidth(hard.toString() + c) > maxWidth && !hard.isEmpty()) {
						out.add(hard.toString());
						hard.setLength(0);
					}
					hard.append(c);
				}
				current.append(hard);
			}
			out.add(current.toString());
		}
		return out;
	}

	/** Truncates a single line to the width, adding an ellipsis. */
	public static String clip(TextRenderer tr, String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (tr.getWidth(text) <= maxWidth) {
			return text;
		}
		String s = text;
		while (s.length() > 1 && tr.getWidth(s + "…") > maxWidth) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}
}
