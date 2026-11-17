/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;

import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Where a chat's long press menu opens (#728): at the pressed chat, below it while it fits above the
 * tab bar, else above it, and never pushed down over the tab bar the way Instagram's call leaves it
 * once 456 anchors it to the whole list.
 */
@RunWith(RobolectricTestRunner.class)
public class ChatMenuPlacementTest {
    /** The room on screen: below the status bar and above the tab bar. */
    private static final int TOP = 80;
    private static final int TAB_BAR = 2100;

    /** The S25 on 456: the menu, the inbox list Instagram anchors it to, and the room around it. */
    private static final int MENU = 1239;
    private static final int LIST_TOP = 254;
    private static final int LIST_BOTTOM = 2340;
    private static final int ROOM_TOP = 96;
    private static final int ROOM_BOTTOM = 2205;

    @After
    public void clearReport() {
        HookStatus.clear();
    }

    @Test
    public void aMenuThatFitsWhereInstagramPutsItStaysThere() {
        assertNull(ChatMenuPlacement.place(500, 1000, 1158, TOP, TAB_BAR, 1158));
        // Exactly down to the tab bar still fits.
        assertNull(ChatMenuPlacement.place(942, 1000, 1158, TOP, TAB_BAR, 1158));
    }

    @Test
    public void aMenuThatWouldCoverTheTabBarOpensAboveTheChat() {
        assertArrayEquals(new int[]{568, 0}, ChatMenuPlacement.place(774, 1342, 1500, TOP, TAB_BAR, 1500));
        // Instagram's own offset is kept while it fits, and dropped for the chat's edge once it doesn't.
        assertNull(ChatMenuPlacement.place(580, 1342, 1500, TOP, TAB_BAR, 1520));
        assertArrayEquals(new int[]{1500, 0}, ChatMenuPlacement.place(581, 1342, 1500, TOP, TAB_BAR, 1520));
    }

    @Test
    public void theWholeListAsTheAnchorOpensTheMenuAtTheFinger() {
        int room = ROOM_BOTTOM - ROOM_TOP;
        assertTrue(ChatMenuPlacement.isList(LIST_TOP, LIST_BOTTOM, room));
        // A chat high in the list: right under the finger.
        int[] high = ChatMenuPlacement.press(LIST_TOP, LIST_BOTTOM, 400, room);
        assertArrayEquals(new int[]{400, 400}, high);
        assertArrayEquals(new int[]{400, 0}, ChatMenuPlacement.place(MENU, high[0], high[1], ROOM_TOP, ROOM_BOTTOM, LIST_BOTTOM));
        // A chat low in the list: ending at the finger.
        assertArrayEquals(new int[]{1900 - MENU, 0}, ChatMenuPlacement.place(MENU, 1900, 1900, ROOM_TOP, ROOM_BOTTOM, LIST_BOTTOM));
        // In the middle, with no room on either side: full height, its bottom on the tab bar.
        assertArrayEquals(new int[]{ROOM_BOTTOM - MENU, 0}, ChatMenuPlacement.place(MENU, 1100, 1100, ROOM_TOP, ROOM_BOTTOM, LIST_BOTTOM));
        assertArrayEquals(new int[]{ROOM_TOP, 0}, ChatMenuPlacement.place(MENU, 1200, 1200, ROOM_TOP, ROOM_BOTTOM, LIST_BOTTOM));
    }

    @Test
    public void theWholeListWithNoFingerOpensTheMenuAtItsTop() {
        int room = ROOM_BOTTOM - ROOM_TOP;
        assertArrayEquals(new int[]{LIST_TOP, LIST_TOP}, ChatMenuPlacement.press(LIST_TOP, LIST_BOTTOM, -1, room));
        // A touch outside the list isn't the press that opened it.
        assertArrayEquals(new int[]{LIST_TOP, LIST_TOP}, ChatMenuPlacement.press(LIST_TOP, LIST_BOTTOM, 50, room));
        // The device check's case: the whole menu, at the top, never squeezed into the strip above the list.
        assertArrayEquals(new int[]{LIST_TOP, 0}, ChatMenuPlacement.place(MENU, LIST_TOP, LIST_TOP, ROOM_TOP, ROOM_BOTTOM, LIST_BOTTOM));
    }

    @Test
    public void aChatSizedAnchorIsTheChatWhateverTheFinger() {
        assertArrayEquals(new int[]{1342, 1500}, ChatMenuPlacement.press(1342, 1500, 1400, TAB_BAR - TOP));
        assertArrayEquals(new int[]{1342, 1500}, ChatMenuPlacement.press(1342, 1500, -1, TAB_BAR - TOP));
    }

    @Test
    public void aMenuTallerThanTheRoomFillsItAndScrolls() {
        assertArrayEquals(new int[]{ROOM_TOP, ROOM_BOTTOM - ROOM_TOP},
                ChatMenuPlacement.place(2300, 400, 400, ROOM_TOP, ROOM_BOTTOM, LIST_BOTTOM));
    }

    @Test
    public void noRoomLeavesInstagramsPlacement() {
        assertNull(ChatMenuPlacement.place(500, 300, 300, 1000, 1000, 2000));
    }

    @Test
    public void theFingerIsTheLastTouchWhileItIsStillTheLongPress() {
        MotionEvent down = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, 300, 1234.4f, 0);
        MotionEvent move = MotionEvent.obtain(1000, 1400, MotionEvent.ACTION_MOVE, 300, 1240.6f, 0);
        MotionEvent cancel = MotionEvent.obtain(1000, 1400, MotionEvent.ACTION_CANCEL, 300, 1240, 0);
        try {
            assertEquals(1234, ChatMenuPlacement.fingerY(down, 1500));
            assertEquals(1241, ChatMenuPlacement.fingerY(move, 1500));
            assertEquals(-1, ChatMenuPlacement.fingerY(cancel, 1500));
            assertEquals(-1, ChatMenuPlacement.fingerY(null, 1500));
            // A touch from long before isn't the one that opened the menu.
            assertEquals(-1, ChatMenuPlacement.fingerY(down, 1000 + ChatMenuPlacement.PRESS_MAX_AGE_MS + 1));
        } finally {
            down.recycle();
            move.recycle();
            cancel.recycle();
        }
    }

    @Test
    public void theLastTouchIsTheComposeViewsOneMotionEvent() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout list = new FrameLayout(context);
        AndroidComposeView compose = new AndroidComposeView(context);
        list.addView(new View(context));
        list.addView(compose);
        assertSame(compose, ChatMenuPlacement.composeView(list, 2));
        assertSame(compose, ChatMenuPlacement.composeView(compose, 0));
        assertNull(ChatMenuPlacement.composeView(new FrameLayout(context), 2));

        Field field = ChatMenuPlacement.touchField(AndroidComposeView.class);
        compose.lastTouch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10, 900, 0);
        try {
            assertSame(compose.lastTouch, field.get(compose));
        } finally {
            compose.lastTouch.recycle();
        }
    }

    @Test
    public void aComposeViewWithNoneOrSeveralTouchesGivesNoFingerAndSaysSo() {
        HookStatus.clear();
        assertNull(ChatMenuPlacement.touchField(TwoTouches.class));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("found 2"));
        assertNull(ChatMenuPlacement.touchField(NoTouch.class));
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(ChatMenuPlacement.LAST_TOUCH));
    }

    /** Compose's view as Instagram ships it: one MotionEvent field, the last touch it handled. */
    static final class AndroidComposeView extends View {
        static MotionEvent unrelated;
        MotionEvent lastTouch;

        AndroidComposeView(Context context) {
            super(context);
        }
    }

    static final class TwoTouches extends View {
        MotionEvent first;
        MotionEvent second;

        TwoTouches(Context context) {
            super(context);
        }
    }

    static final class NoTouch extends View {
        NoTouch(Context context) {
            super(context);
        }
    }
}
