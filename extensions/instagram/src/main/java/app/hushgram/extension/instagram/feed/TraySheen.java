/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.stories.StoryRing;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * Where Home's stories row ends under its see-through header, so the glass tab bar's sheen on the header runs on
 * down over the row to its bottom (#800). The sheen used to end just below the header, and the row under it read
 * as a dark band between the glass and the posts.
 */
final class TraySheen {
    /** How many of Home's list's first rows are looked in for the stories row. */
    private static final int ROWS = 2;
    /** How far up from the stories row its place in Home's list is looked for. */
    private static final int DEPTH = 4;

    /** The stories row's id once found, 0 until then, -1 when Instagram has none. Tests set it. */
    static volatile int trayId;

    @Nullable private WeakReference<View> tray;
    private final int[] at = new int[2];

    /**
     * How far below [header]'s top the stories row in Home's [list] ends, or 0 when no stories row shows there
     * reaching past the header's bottom.
     */
    int end(View header, @Nullable View list) {
        if (!(list instanceof ViewGroup) || !list.isShown()) {
            tray = null;
            return 0;
        }
        View row = row((ViewGroup) list);
        if (row == null) return 0;
        header.getLocationInWindow(at);
        int headerTop = at[1];
        row.getLocationInWindow(at);
        int bottom = at[1] + row.getHeight() - headerTop;
        return bottom > header.getHeight() ? bottom : 0;
    }

    /** The stories row in [list], the one found last while it's still there, or null when it isn't showing. */
    @Nullable
    private View row(ViewGroup list) {
        View known = tray == null ? null : tray.get();
        if (known != null && known.isShown() && inside(known, list)) return known;
        tray = null;
        int id = id(list);
        if (id <= 0) return null;
        // The row scrolls away with the posts, so only the list's top rows can hold it.
        for (int i = 0, n = Math.min(ROWS, list.getChildCount()); i < n; i++) {
            View found = list.getChildAt(i).findViewById(id);
            if (found != null && found.isShown()) {
                tray = new WeakReference<>(found);
                return found;
            }
        }
        return null;
    }

    private static boolean inside(View row, ViewGroup list) {
        ViewParent up = row.getParent();
        for (int level = 0; level < DEPTH && up instanceof View; level++) {
            if (up == list) return true;
            up = up.getParent();
        }
        return false;
    }

    private static int id(View list) {
        int known = trayId;
        if (known != 0) return known;
        Context context = list.getContext();
        known = context.getResources().getIdentifier(StoryRing.TRAY, "id", Utils.resourcePackage(context));
        if (known == 0) {
            Logger.printDebug(() -> "Top bar glass: no " + StoryRing.TRAY + " id, so the sheen ends under the header");
            known = -1;
        }
        trayId = known;
        return known;
    }
}
