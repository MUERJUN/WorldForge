package dev.worldforge.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The geometry of the v0.7.0 chat screen.
 *
 * <p>Pure arithmetic, deliberately free of any Minecraft type, so the layout
 * can be audited for EVERY screen size — including very narrow ones and every
 * GUI scale — by a plain unit test. A layout claim that is checked, not
 * eyeballed in a screenshot.</p>
 *
 * <p>The client screen renders exactly these numbers; there is no second
 * implementation that could drift.</p>
 */
public final class ChatLayout {

	/** the left/right padding inside the panel */
	public static final int PAD = 6;
	/** the header strip height */
	public static final int HEADER_H = 22;
	/** the footer (input) strip height */
	public static final int FOOTER_H = 26;
	/** one line of text */
	public static final int LINE_H = 10;

	/**
	 * The computed layout.
	 *
	 * @param panelX    panel left
	 * @param panelY    panel top
	 * @param panelW    panel width
	 * @param panelH    panel height
	 * @param listX     message list left
	 * @param listY     message list top
	 * @param listW     message list width
	 * @param listH     message list height
	 * @param headerY   header row top
	 * @param sessionX  session picker button left
	 * @param newX      new-session button left
	 * @param settingsX settings button left
	 * @param inputX    input field left
	 * @param inputY    input field top
	 * @param inputW    input field width
	 * @param sendX     send button left
	 * @param sendY     send button top
	 * @param sendW     send button width
	 */
	public record Layout(int panelX, int panelY, int panelW, int panelH,
			int listX, int listY, int listW, int listH,
			int headerY, int sessionX, int newX, int settingsX,
			int inputX, int inputY, int inputW,
			int sendX, int sendY, int sendW) {

		/** Every widget rectangle, in the order the screen creates them. */
		public List<int[]> widgetRects() {
			List<int[]> out = new ArrayList<>();
			out.add(new int[]{sessionX, headerY, 46, 16});   // session picker
			out.add(new int[]{newX, headerY, 20, 16});       // new session
			out.add(new int[]{settingsX, headerY, 20, 16});  // settings
			out.add(new int[]{inputX, inputY, inputW, 18});  // input
			out.add(new int[]{sendX, sendY, sendW, 18});     // send
			return out;
		}

		/** The names matching {@link #widgetRects()}, for diagnostics. */
		public List<String> widgetNames() {
			return List.of("会话", "＋", "⚙", "chat input", "发送");
		}
	}

	private ChatLayout() {
	}

	/**
	 * Computes the layout for a given screen size.
	 *
	 * @param width  the scaled screen width
	 * @param height the scaled screen height
	 */
	public static Layout compute(int width, int height) {
		// The panel may never be larger than the screen: at GUI scale 4 on an
		// 854x480 window the scaled screen is only 213x120, and a fixed
		// minimum height would push the footer off the bottom edge.
		int panelW = Math.min(440, Math.max(1, width - 8));
		int panelH = Math.min(230, Math.max(1, height - 8));
		int panelX = (width - panelW) / 2;
		int panelY = (height - panelH) / 2;
		int listX = panelX + PAD;
		int listY = panelY + HEADER_H + 4;
		int listW = panelW - PAD * 2;
		int listH = panelH - HEADER_H - FOOTER_H - 8;
		int headerY = panelY + 3;
		int sessionX = panelX + PAD;
		int newX = sessionX + 50;
		int settingsX = panelX + panelW - PAD - 20;
		int footerY = panelY + panelH - FOOTER_H + 2;
		int sendW = 44;
		int inputW = Math.max(40, panelW - PAD * 2 - sendW - 4);
		int inputX = panelX + PAD;
		int sendX = panelX + panelW - PAD - sendW;
		return new Layout(panelX, panelY, panelW, panelH,
				listX, listY, listW, listH,
				headerY, sessionX, newX, settingsX,
				inputX, footerY, inputW,
				sendX, footerY, sendW);
	}

	/** True when two widget rectangles overlap. */
	public static boolean overlaps(int[] a, int[] b) {
		return a[0] < b[0] + b[2] && b[0] < a[0] + a[2]
				&& a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
	}

	/** True when the rectangle lies completely inside the other. */
	public static boolean inside(int[] inner, int[] outer) {
		return inner[0] >= outer[0] && inner[1] >= outer[1]
				&& inner[0] + inner[2] <= outer[0] + outer[2]
				&& inner[1] + inner[3] <= outer[1] + outer[3];
	}
}
