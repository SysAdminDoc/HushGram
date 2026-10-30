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
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Hide the caught up card (#307): folds the row of Home's end of feed card, "You've seen the latest
 * posts from accounts you follow" with its Older posts link, to no height.
 *
 * <p>The card stays an item of the feed, so Instagram's rules that need it there still find it: the
 * load more row under it and {@link FeedSuggestions#endCardRule}. Only its row folds. A card of a
 * top of feed kind ({@link #TOP_OF_FEED} or one starting {@link #TOP_OF_FEED_PREFIX}), the one
 * Instagram puts at the top of Home when there are no new posts at all, keeps its row, since Home
 * would show nothing under the stories otherwise. A card whose kind can't be read folds. Main thread.
 */
public final class CaughtUpCard {
    /** The kind of the card at the top of Home, which also marks the enum the card's kinds are in. */
    static final String TOP_OF_FEED = "TOP_OF_FEED";
    /** The start of the other top of feed kinds' names: TOF_HOMECOMING_ALL, TOF_CA_SB_976 and so on on 452. */
    static final String TOP_OF_FEED_PREFIX = "TOF_";

    /** The report's counts of how each card went. */
    static final String COUNT_FOLDED = "caught up cards folded";
    static final String COUNT_KEPT_TOP = "caught up cards kept at the top of Home";

    /** Reads the details a card's kind is in, as {@link #details} does. Tests stand in. */
    static volatile Function<Object, Object> detailsOf = CaughtUpCard::details;

    /** Each folded row's own height, to put back when the switch goes off. */
    private static final Map<View, Integer> heights = new WeakHashMap<>();

    private CaughtUpCard() {
    }

    /**
     * Injected first thing in the end of feed card's binder, with the card's row and the card. Folds
     * the row while the switch is on and the card isn't a top of feed one, and unfolds a row it folded
     * otherwise. Never throws.
     */
    public static void bind(View row, Object card) {
        if (row == null) return;
        try {
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            boolean fold = card != null && Utils.settingsReady() && Settings.HIDE_CAUGHT_UP_CARD.get();
            if (fold && atTop(card)) {
                fold = false;
                HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, COUNT_KEPT_TOP);
            }
            if (fold) HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, COUNT_FOLDED);
            fold(row, fold);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "caught up card", failure);
        }
    }

    /** Whether [card] is the card at the top of Home, by its kind. */
    static boolean atTop(Object card) throws IllegalAccessException {
        Object details = detailsOf.apply(card);
        if (details == null) return false;
        String kind = FeedItemKinds.kindOf(details, TOP_OF_FEED, FamilyNames.FEED_SUGGESTIONS);
        return kind != null && (kind.equals(TOP_OF_FEED) || kind.startsWith(TOP_OF_FEED_PREFIX));
    }

    /** Folds [row] to no height, or puts back the height it had before this folded it. */
    static void fold(View row, boolean fold) {
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (fold) {
            if (!heights.containsKey(row)) {
                heights.put(row, params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height);
            } else if (params != null && params.height == 0 && row.getVisibility() == View.GONE) {
                return;
            }
            // A row not yet given its list's params gets plain ones, which the list copies the height from.
            if (params == null) params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
            params.height = 0;
            row.setLayoutParams(params);
            row.setVisibility(View.GONE);
            Logger.printDebug(() -> "Caught up card: folded its row");
            return;
        }
        Integer height = heights.remove(row);
        if (height == null) return;
        if (params != null) {
            params.height = height;
            row.setLayoutParams(params);
        }
        row.setVisibility(View.VISIBLE);
    }

    /**
     * The details of an end of feed card, the object whose enum field holds its kind, or null. The
     * patch writes the body, which reads the card's details field. Null as built, so every card folds
     * while the switch is on.
     */
    @Nullable
    public static Object details(Object card) {
        return null;
    }
}
