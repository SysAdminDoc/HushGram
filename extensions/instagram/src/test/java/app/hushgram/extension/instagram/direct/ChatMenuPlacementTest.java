/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Where a chat's long press menu opens (#728): below the chat while it fits above the tab bar, else above it. */
public class ChatMenuPlacementTest {
    /** The room on screen: below the status bar and above the tab bar. */
    private static final int TOP = 80;
    private static final int TAB_BAR = 2100;

    @Test
    public void aMenuThatFitsBelowTheChatStaysWhereInstagramPutsIt() {
        assertNull(ChatMenuPlacement.place(500, 1000, 1158, TOP, TAB_BAR, false, 0));
        // Exactly down to the tab bar still fits.
        assertNull(ChatMenuPlacement.place(942, 1000, 1158, TOP, TAB_BAR, false, 0));
    }

    @Test
    public void aMenuThatWouldCoverTheTabBarOpensAboveTheChat() {
        // The reported case: a 774 tall menu for a chat low in the list.
        int[] place = ChatMenuPlacement.place(774, 1342, 1500, TOP, TAB_BAR, false, 0);
        assertArrayEquals(new int[]{-932, 0}, place);
        // Shown from the chat's bottom, it ends right on top of the chat.
        int menuTop = 1500 + place[0];
        assertArrayEquals(new int[]{568, 1342}, new int[]{menuTop, menuTop + 774});
    }

    @Test
    public void anOverlappingMenuCountsFromTheChatsTop() {
        int[] place = ChatMenuPlacement.place(774, 1342, 1500, TOP, TAB_BAR, true, 0);
        assertArrayEquals(new int[]{-774, 0}, place);
        assertNull(ChatMenuPlacement.place(758, 1342, 1500, TOP, TAB_BAR, true, 0));
    }

    @Test
    public void instagramsOwnOffsetCountsAgainstTheRoomBelow() {
        assertNull(ChatMenuPlacement.place(580, 1342, 1500, TOP, TAB_BAR, false, 20));
        assertArrayEquals(new int[]{-739, 0}, ChatMenuPlacement.place(581, 1342, 1500, TOP, TAB_BAR, false, 20));
    }

    @Test
    public void aMenuTooTallForEitherSideTakesTheRoomierOneAndScrolls() {
        // More room below: Instagram's offset, cut to the room left above the tab bar.
        assertArrayEquals(new int[]{0, 1442}, ChatMenuPlacement.place(2000, 500, 658, TOP, TAB_BAR, false, 0));
        // More room above: from the top of the room down to the chat.
        int[] above = ChatMenuPlacement.place(2000, 1500, 1658, TOP, TAB_BAR, false, 0);
        assertArrayEquals(new int[]{TOP - 1658, 1420}, above);
        assertArrayEquals(new int[]{TOP, 1500}, new int[]{1658 + above[0], 1658 + above[0] + above[1]});
    }

    @Test
    public void noRoomOnEitherSideLeavesInstagramsPlacement() {
        assertNull(ChatMenuPlacement.place(774, 0, 2200, TOP, TAB_BAR, false, 0));
        assertNull(ChatMenuPlacement.place(774, TOP - 5, TAB_BAR, TOP, TAB_BAR, false, 0));
    }
}
