/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Hide the Join bar on channel posts, a switch of the "Hide suggested posts" patch (#625).
 *
 * <p>A post from an account with a broadcast channel can carry a bar under it asking you to join
 * that channel ("Join EXCLUSIVE CONTENT | CINEVERSE >"). Instagram 454 binds that bar with the
 * channel's details, and with none it sets the bar gone itself. The patch hands those details to
 * {@link #details} first thing in the binder, and a null answer takes that path. The bar is a row of
 * its own in the feed's list, which keeps a gone row's height, so the row also folds to no height and
 * the post closes up under it. The post, its likes and its comments stay. Each bind reads the switch,
 * so a post bound again after it's flipped shows the bar or doesn't, and a folded row gets its height
 * back.
 */
public final class ChannelJoinBar {
    static final String COUNT_HIDDEN = "channel join bars hidden";

    /** Reads the bar's row from its view holder, as {@link #row} does. Tests stand in. */
    static volatile Function<Object, View> rowOf = ChannelJoinBar::row;

    /** Each folded row's own height, to put back when the switch goes off. */
    private static final Map<View, Integer> heights = new WeakHashMap<>();

    private ChannelJoinBar() {
    }

    /**
     * Called first thing in the Join bar's binder with its parameters: the bar's view holder, the post
     * and the channel's details. Answers null, which leaves the bar gone, and folds its row while the
     * switch is on. Answers [details] and unfolds a row it folded otherwise: with the switch off,
     * HushGram paused, the settings not read yet, or if anything throws. Never throws.
     */
    public static Object details(Object holder, Object post, Object details) {
        if (details == null) return null;
        try {
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            boolean hide = Utils.settingsReady() && Settings.HIDE_CHANNEL_JOIN_BAR.get();
            View row = rowOf.apply(holder);
            if (row != null) fold(row, hide);
            if (!hide) return details;
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, COUNT_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "channel join bar", failure);
            return details;
        }
    }

    /** Folds [row] to no height, or puts back the height it had before this folded it. */
    static void fold(View row, boolean fold) {
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (fold) {
            if (!heights.containsKey(row)) {
                heights.put(row, params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height);
            } else if (params != null && params.height == 0) {
                return;
            }
            // A row not yet given its list's params gets plain ones, which the list copies the height from.
            if (params == null) params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
            params.height = 0;
            row.setLayoutParams(params);
            return;
        }
        Integer height = heights.remove(row);
        if (height == null || params == null) return;
        params.height = height;
        row.setLayoutParams(params);
    }

    /**
     * The bar's row, the view its holder keeps and the binder sets gone, or null. The patch writes the
     * body, which reads the holder's field. Null as built, so no row folds.
     */
    @Nullable
    public static View row(Object holder) {
        return null;
    }
}
