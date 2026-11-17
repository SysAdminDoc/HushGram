/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Where a long press menu opens in your messages (#728). Instagram drops a chat's menu below the
 * chat it was pressed on whenever it fits above the phone's own navigation bar, so a chat low in the
 * list gets a menu over Instagram's tab bar, and a bigger font or a menu with more rows runs it off
 * the bottom with its last rows cut. Instagram's call is taken here instead: a menu that fits between
 * the chat and the tab bar opens there as before, one that doesn't opens above the chat, and one that
 * fits on neither side takes the roomier side at that height and scrolls. Main thread.
 */
public final class ChatMenuPlacement {
    /** The id name of Instagram's tab bar, the menu's lower limit while it's on screen. */
    static final String TAB_BAR = "tab_bar";

    /** What the report counts each time a menu is moved. */
    static final String COUNT_MOVED = "chat menu kept clear of the tab bar";

    static final String WHERE = "chat menu placement";

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
        int bottom = frame.bottom;
        View bar = tabBar(anchor);
        if (bar != null) {
            bar.getLocationOnScreen(at);
            // Only a bar below the chat limits the menu, never one the list runs under.
            if (at[1] >= anchorBottom) bottom = Math.min(bottom, at[1]);
        }
        int[] place = place(height, anchorTop, anchorBottom, frame.top, bottom, popup.getOverlapAnchor(), y);
        int limit = bottom;
        Logger.printDebug(() -> "Chat menu: height " + height + ", chat " + anchorTop + "-" + anchorBottom
                + ", room " + frame.top + "-" + limit + (bar == null ? " (no tab bar)" : "")
                + (place == null ? ", left below" : ", offset " + place[0] + (place[1] > 0 ? ", height " + place[1] : "")));
        return place;
    }

    /**
     * Where a menu [height] tall goes for a chat spanning [anchorTop]-[anchorBottom] on screen, with
     * room from [top] to [bottom]. [overlap] is the menu's overlap-anchor flag and [y] Instagram's own
     * offset. Answers null when Instagram's placement already fits, else the offset to pass in its
     * place and the height to set first, 0 to keep the height.
     */
    @Nullable
    static int[] place(int height, int anchorTop, int anchorBottom, int top, int bottom, boolean overlap, int y) {
        // The offset counts from the chat's bottom, or its top while the menu overlaps it.
        int base = overlap ? anchorTop : anchorBottom;
        int below = bottom - (base + y);
        if (height <= below) return null;
        int above = anchorTop - top;
        if (height <= above) return new int[]{anchorTop - height - base, 0};
        if (below >= above) return below > 0 ? new int[]{y, below} : null;
        if (above <= 0) return null;
        return new int[]{top - base, above};
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
