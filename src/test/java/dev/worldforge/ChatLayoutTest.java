package dev.worldforge;

import dev.worldforge.ui.ChatLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v0.7.0 chat layout audit.
 *
 * <p>The layout is pure arithmetic, so it can be checked for EVERY size
 * instead of eyeballed in a screenshot: the real window sizes of every GUI
 * scale, plus deliberately narrow ones.</p>
 */
class ChatLayoutTest {

	/**
	 * The real scaled sizes of a 854x480 window at GUI scale 1..4, and a few
	 * common full-HD sizes.
	 */
	private static final int[][] SIZES = {
			{854, 480},   // 854x480 @ scale 1
			{427, 240},   // 854x480 @ scale 2
			{284, 160},   // 854x480 @ scale 3
			{213, 120},   // 854x480 @ scale 4  (the narrowest realistic case)
			{1920, 1080}, // full HD @ scale 1
			{960, 540},   // full HD @ scale 2
			{640, 360},
			{480, 270},
			{320, 240},   // tiny
			{213, 120},   // the narrowest the game allows
	};

	@Test
	void thePanelAlwaysFitsTheScreen() {
		for (int[] size : SIZES) {
			var l = ChatLayout.compute(size[0], size[1]);
			assertTrue(l.panelX() >= 0 && l.panelY() >= 0,
					"the panel must not start off-screen at " + size[0] + "x" + size[1]);
			assertTrue(l.panelX() + l.panelW() <= size[0],
					"the panel must fit the width at " + size[0] + "x" + size[1]
							+ " (panel=" + l.panelW() + " x=" + l.panelX() + ")");
			assertTrue(l.panelY() + l.panelH() <= size[1],
					"the panel must fit the height at " + size[0] + "x" + size[1]
							+ " (panel=" + l.panelH() + " y=" + l.panelY() + ")");
		}
	}

	@Test
	void everyWidgetStaysInsideThePanel() {
		for (int[] size : SIZES) {
			var l = ChatLayout.compute(size[0], size[1]);
			int[] panel = {l.panelX(), l.panelY(), l.panelW(), l.panelH()};
			List<int[]> rects = l.widgetRects();
			List<String> names = l.widgetNames();
			for (int i = 0; i < rects.size(); i++) {
				assertTrue(ChatLayout.inside(rects.get(i), panel),
						"widget '" + names.get(i) + "' escapes the panel at " + size[0] + "x" + size[1]
								+ " rect=" + java.util.Arrays.toString(rects.get(i))
								+ " panel=" + java.util.Arrays.toString(panel));
			}
		}
	}

	@Test
	void noTwoWidgetsOverlap() {
		for (int[] size : SIZES) {
			var l = ChatLayout.compute(size[0], size[1]);
			List<int[]> rects = l.widgetRects();
			List<String> names = l.widgetNames();
			for (int i = 0; i < rects.size(); i++) {
				for (int j = i + 1; j < rects.size(); j++) {
					assertFalse(ChatLayout.overlaps(rects.get(i), rects.get(j)),
							"widgets '" + names.get(i) + "' and '" + names.get(j)
									+ "' overlap at " + size[0] + "x" + size[1]
									+ " " + java.util.Arrays.toString(rects.get(i))
									+ " vs " + java.util.Arrays.toString(rects.get(j)));
				}
			}
		}
	}

	@Test
	void theInputFieldStaysUsable() {
		for (int[] size : SIZES) {
			var l = ChatLayout.compute(size[0], size[1]);
			assertTrue(l.inputW() >= 40,
					"the input must stay usable at " + size[0] + "x" + size[1]
							+ " (width=" + l.inputW() + ")");
			assertTrue(l.listW() >= 40,
					"the message list must stay readable at " + size[0] + "x" + size[1]);
			assertTrue(l.listH() >= 20,
					"the message list must have room at " + size[0] + "x" + size[1]
							+ " (height=" + l.listH() + ")");
		}
	}

	@Test
	void theSendButtonSitsToTheRightOfTheInput() {
		for (int[] size : SIZES) {
			var l = ChatLayout.compute(size[0], size[1]);
			assertTrue(l.sendX() >= l.inputX() + l.inputW(),
					"the send button must not sit on the input at " + size[0] + "x" + size[1]);
		}
	}

	@Test
	void theHeaderButtonsDoNotCollide() {
		for (int[] size : SIZES) {
			var l = ChatLayout.compute(size[0], size[1]);
			// session picker .. new session
			assertTrue(l.newX() >= l.sessionX() + 46,
					"the session and new-session buttons must not touch at "
							+ size[0] + "x" + size[1]);
			// settings button .. session picker
			assertTrue(l.settingsX() >= l.sessionX() + 46 + 50,
					"the settings button must not touch the header buttons at "
							+ size[0] + "x" + size[1]);
		}
	}

	@Test
	void thePanelIsCentred() {
		for (int[] size : SIZES) {
			var l = ChatLayout.compute(size[0], size[1]);
			int leftGap = l.panelX();
			int rightGap = size[0] - (l.panelX() + l.panelW());
			assertTrue(Math.abs(leftGap - rightGap) <= 1,
					"the panel must be centred horizontally at " + size[0] + "x" + size[1]);
		}
	}

	@Test
	void theLayoutIsStableAcrossCalls() {
		for (int[] size : SIZES) {
			var a = ChatLayout.compute(size[0], size[1]);
			var b = ChatLayout.compute(size[0], size[1]);
			assertEquals(a, b, "the layout must be deterministic");
		}
	}
}
