/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Where a long press menu opens in your messages (#728). Instagram drops a chat's menu below the
 * view it anchors it to. On 456 that view is the whole Compose list rather than the chat, so the menu
 * never fits below it and Android pushes it down to the bottom of the screen, over Instagram's tab bar
 * and, with a bigger font, off the bottom with its last rows cut. Instagram's call is taken here
 * instead: the menu opens at the pressed chat, below it when it fits above the tab bar, else above it,
 * else slid in between the status bar and the tab bar, and it only scrolls when it's taller than all of
 * that. With the list as the anchor, the chat is found from the finger: the long press fires while the
 * finger is still down, and Compose keeps the last touch it saw on its view. Main thread.
 */
public final class ChatMenuPlacement {
    /** The id name of Instagram's tab bar, the menu's lower limit while it's on screen. */
    static final String TAB_BAR = "tab_bar";

    /** The view Compose draws a screen in, which keeps the last touch it handled. */
    static final String COMPOSE_VIEW = "AndroidComposeView";

    /** What the compose view's last touch is reported as when it can't be read. */
    static final String LAST_TOUCH = "last touch";

    /** A touch older than this isn't the long press that opened the menu. */
    static final long PRESS_MAX_AGE_MS = 3000;

    /** What the report counts each time a menu is moved. */
    static final String COUNT_MOVED = "chat menu kept clear of the tab bar";

    static final String WHERE = "chat menu placement";

    /** The compose view class last looked at, and its last-touch field (null when it has none). */
    @Nullable
    private static Class<?> touchOwner;
    @Nullable
    private static Field touchField;

    private ChatMenuPlacement() {
    }

    /**
     * Called in place of Instagram's {@code popup.showAsDropDown(anchor, x, y)} for its long press
     * menus, once it has set the menu's height. Always shows the menu.
     */
    public static void show(PopupWindow popup, View anchor, int x, int y) {
        int[] place = null;
        try {
            if (Utils.settingsReady() && !HushgramPause.isPaused()) place = place(popup, anchor, y);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PREVIEW_CHATS, WHERE, failure);
        }
        if (place == null) {
            popup.showAsDropDown(anchor, x, y);
            return;
        }
        if (place[1] > 0) popup.setHeight(place[1]);
        popup.showAsDropDown(anchor, x, place[0]);
        HookStatus.counted(FamilyNames.PREVIEW_CHATS, COUNT_MOVED);
    }

    /** The new offset and height for the menu (height 0 keeps it), or null to leave Instagram's. */
    @Nullable
    private static int[] place(PopupWindow popup, View anchor, int y) {
        int height = popup.getHeight();
        if (height <= 0 || !anchor.isAttachedToWindow()) return null;
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int anchorTop = at[1];
        int anchorBottom = anchorTop + anchor.getHeight();
        Rect frame = new Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        int room = frame.height();
        int finger = isList(anchorTop, anchorBottom, room) ? finger(anchor, SystemClock.uptimeMillis()) : -1;
        int[] press = press(anchorTop, anchorBottom, finger, room);
        int bottom = frame.bottom;
        int barTop = -1;
        View bar = tabBar(anchor);
        if (bar != null) {
            bar.getLocationOnScreen(at);
            barTop = at[1];
            // Only a bar below the pressed chat limits the menu, never one the list runs under.
            if (barTop >= press[1]) bottom = Math.min(bottom, barTop);
        }
        int base = popup.getOverlapAnchor() ? anchorTop : anchorBottom;
        int[] place = place(height, press[0], press[1], frame.top, bottom, base + y);
        int limit = bottom;
        int tabBar = barTop;
        Logger.printDebug(() -> "Chat menu: height " + height + ", anchor " + anchorTop + "-" + anchorBottom
                + ", pressed " + press[0] + "-" + press[1] + (finger < 0 ? "" : " (finger)")
                + ", room " + frame.top + "-" + limit + (tabBar < 0 ? " (no tab bar)" : " (tab bar " + tabBar + ")")
                + (place == null ? ", left where Instagram put it" : ", top " + place[0] + (place[1] > 0 ? ", height " + place[1] : "")));
        return place == null ? null : new int[]{place[0] - base, place[1]};
    }

    /** Whether an anchor this tall is the whole list rather than one chat. */
    static boolean isList(int anchorTop, int anchorBottom, int room) {
        return anchorBottom - anchorTop > room / 2;
    }

    /**
     * The pressed part of the screen, top and bottom: the chat when Instagram anchors the menu to
     * it, the finger's spot when it anchors it to the whole list, and the list's top when the finger
     * can't be found ([finger] -1), so the menu still opens at the top rather than the bottom.
     */
    static int[] press(int anchorTop, int anchorBottom, int finger, int room) {
        if (!isList(anchorTop, anchorBottom, room)) return new int[]{anchorTop, anchorBottom};
        if (finger >= anchorTop && finger <= anchorBottom) return new int[]{finger, finger};
        return new int[]{anchorTop, anchorTop};
    }

    /**
     * The top on screen and height (0 keeps it) for a menu [height] tall, pressed at
     * [pressTop]-[pressBottom], with room from [top] to [bottom]. [stockTop] is where Instagram's
     * own call puts the menu's top. Answers null when Instagram's spot already fits.
     */
    @Nullable
    static int[] place(int height, int pressTop, int pressBottom, int top, int bottom, int stockTop) {
        if (bottom <= top) return null;
        if (stockTop >= top && stockTop + height <= bottom) return null;
        if (height > bottom - top) return new int[]{top, bottom - top};
        if (pressBottom >= top && pressBottom + height <= bottom) return new int[]{pressBottom, 0};
        if (pressTop <= bottom && pressTop - height >= top) return new int[]{pressTop - height, 0};
        // It fits on screen but on neither side of the chat: slide it in over the chat's roomier edge.
        return bottom - pressBottom >= pressTop - top ? new int[]{bottom - height, 0} : new int[]{top, 0};
    }

    /** Where the finger is on screen during the long press, from the compose view's last touch, or -1. */
    private static int finger(View anchor, long now) {
        View compose = composeView(anchor, 2);
        if (compose == null) return -1;
        Field field = touchField(compose.getClass());
        if (field == null) return -1;
        try {
            return fingerY((MotionEvent) field.get(compose), now);
        } catch (IllegalAccessException failure) {
            return -1;
        }
    }

    /** The screen height of a touch that is still the long press (down, moving or just lifted), or -1. */
    static int fingerY(@Nullable MotionEvent touch, long now) {
        if (touch == null || now - touch.getEventTime() > PRESS_MAX_AGE_MS) return -1;
        switch (touch.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                return Math.round(touch.getRawY());
            default:
                return -1;
        }
    }

    /** The compose view at or under [view], at most [depth] levels down. */
    @Nullable
    static View composeView(View view, int depth) {
        if (COMPOSE_VIEW.equals(view.getClass().getSimpleName())) return view;
        if (depth <= 0 || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = composeView(group.getChildAt(i), depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    /** The compose view's one MotionEvent field, the last touch it handled; null when it has none or several. */
    @Nullable
    static Field touchField(Class<?> type) {
        if (type == touchOwner) return touchField;
        Field found = null;
        int count = 0;
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != MotionEvent.class || Modifier.isStatic(field.getModifiers())) continue;
            found = field;
            count++;
        }
        if (count == 1) {
            found.setAccessible(true);
        } else {
            found = null;
            if (count == 0) {
                HookStatus.missingMember(FamilyNames.PREVIEW_CHATS, "field", type.getName(), LAST_TOUCH);
            } else {
                HookStatus.ambiguous(FamilyNames.PREVIEW_CHATS, "field", type.getName(), LAST_TOUCH, count);
            }
        }
        touchOwner = type;
        touchField = found;
        return found;
    }

    /** Instagram's tab bar in the menu's window, while it's showing. */
    @Nullable
    private static View tabBar(View anchor) {
        Context context = anchor.getContext();
        int id = context.getResources().getIdentifier(TAB_BAR, "id", Utils.resourcePackage(context));
        if (id == 0) return null;
        View bar = anchor.getRootView().findViewById(id);
        return bar != null && bar.isShown() && bar.getHeight() > 0 ? bar : null;
    }
}
