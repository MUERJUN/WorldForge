package dev.worldforge.studio;

/**
 * Pure layout geometry for the WorldForge Studio screen (v0.3.2).
 *
 * <p>Widget coordinates and mouse hit-testing used to be computed inline in
 * the screen and in several click handlers, which made them impossible to
 * verify and easy to desynchronise. All of it now lives here as arithmetic
 * on integers, so the exact same numbers drive drawing and hit-testing and
 * can be asserted in unit tests at any window size (including the 854x480
 * small-window case) without a graphics context.</p>
 */
public final class StudioLayout {

	public record Rect(int x, int y, int w, int h) {
		public int right() {
			return x + w;
		}

		public int bottom() {
			return y + h;
		}

		public boolean contains(double px, double py) {
			return px >= x && px < right() && py >= y && py < bottom();
		}
	}

	/** outer panel size limits (the panel is clamped into the window) */
	public static final int PANEL_MAX_W = 500;
	public static final int PANEL_MAX_H = 246;
	public static final int LIST_W = 160;
	public static final int ROW_H = 22;
	public static final int MARGIN = 8;

	private final Rect panel;
	private final Rect list;
	private final int listMaxRows;
	private final int contentX;
	private final int contentW;

	private StudioLayout(Rect panel, Rect list, int listMaxRows, int contentX, int contentW) {
		this.panel = panel;
		this.list = list;
		this.listMaxRows = listMaxRows;
		this.contentX = contentX;
		this.contentW = contentW;
	}

	/**
	 * Compute the layout for a window of {@code screenW x screenH} logical
	 * pixels. Never produces negative sizes, so tiny windows degrade instead
	 * of throwing or drawing off-screen.
	 */
	public static StudioLayout of(int screenW, int screenH) {
		int pw = Math.min(PANEL_MAX_W, Math.max(120, screenW - 16));
		int ph = Math.min(PANEL_MAX_H, Math.max(100, screenH - 32));
		int px = Math.max(0, (screenW - pw) / 2);
		int py = Math.max(0, (screenH - ph) / 2);
		Rect panel = new Rect(px, py, pw, ph);

		int listX = px + MARGIN;
		int listY = py + 26;
		// reserve the bottom button block; at least one row is always usable
		int listH = Math.max(ROW_H, ph - 100);
		int rows = Math.max(1, listH / ROW_H);
		Rect list = new Rect(listX, listY, Math.min(LIST_W, pw - 2 * MARGIN), rows * ROW_H);

		int contentX = px + LIST_W + 16;
		int contentW = Math.max(40, pw - LIST_W - 32);
		return new StudioLayout(panel, list, rows, contentX, contentW);
	}

	public Rect panel() {
		return panel;
	}

	public Rect list() {
		return list;
	}

	public int listMaxRows() {
		return listMaxRows;
	}

	public int contentX() {
		return contentX;
	}

	public int contentW() {
		return contentW;
	}

	/** visible row rectangle (index within the visible window, 0-based) */
	public Rect rowRect(int visibleIndex) {
		return new Rect(list.x(), list.y() + visibleIndex * ROW_H, list.w(), ROW_H);
	}

	/** full rectangle of the scrollable list content, used for hit-tests */
	public Rect listContentRect() {
		return new Rect(list.x(), list.y(), list.w(), listMaxRows * ROW_H);
	}

	/** Y coordinate of the status bar inside the panel */
	public int statusBarY() {
		return panel.bottom() - 14;
	}

	/**
	 * Map a mouse position to an absolute row index in the backing list, or
	 * -1 when the pointer is outside the list viewport. Uses the SAME rect as
	 * {@link #rowRect}, which is the invariant the old code violated.
	 */
	public int rowIndexAt(double mouseX, double mouseY, int scroll) {
		if (!listContentRect().contains(mouseX, mouseY)) {
			return -1;
		}
		int visible = (int) ((mouseY - list.y()) / ROW_H);
		if (visible < 0 || visible >= listMaxRows) {
			return -1;
		}
		return scroll + visible;
	}

	/** clamp a scroll offset so the last page stays visible */
	public int clampScroll(int scroll, int totalRows) {
		int max = Math.max(0, totalRows - listMaxRows);
		return Math.max(0, Math.min(max, scroll));
	}

	/** scroll by a wheel delta (+1 up / -1 down), clamped */
	public int scrollBy(int scroll, int totalRows, double amountY) {
		int step = amountY > 0 ? -1 : (amountY < 0 ? 1 : 0);
		return clampScroll(scroll + step, totalRows);
	}

	/** bottom button row inside the panel (create / refresh / close) */
	public Rect bottomButton(int slot, int count) {
		int gap = 6;
		int totalW = list.w();
		int bw = Math.max(24, (totalW - gap * (count - 1)) / count);
		int y = panel.bottom() - 26;
		return new Rect(list.x() + slot * (bw + gap), y, bw, 16);
	}

	/** AI panel input/button row geometry, kept in sync with AiStudioPanel */
	public Rect aiInputRow(int contentX, int contentY, int contentW, int contentH, int slot) {
		int rowY = contentY + contentH - 20;
		return switch (slot) {
			case 0 -> new Rect(contentX, rowY, Math.max(40, contentW - 110), 18); // input
			case 1 -> new Rect(contentX + contentW - 106, rowY, 50, 18);          // send / cancel
			default -> new Rect(contentX + contentW - 52, rowY, 52, 18);          // confirm
		};
	}
}