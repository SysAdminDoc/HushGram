/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * Helper for the "Hide suggested stories" patch.
 *
 * <p>The row of stories at the top of Home is one row of Home's list, added by its story tray
 * binder group. The patch asks {@link #hideTray} as that group adds its row, and while Hide the
 * Stories tray is on the group adds none.
 *
 * <p>The tray's items come from its own request, each marked with a reel type, and a story from an
 * account you don't follow is a suggested one. The patch passes each item the tray's parser reads
 * through {@link #filter}, which answers null for a suggested one while Hide suggested stories is
 * on, a rewind card while Hide story rewinds is on, and a card Instagram made from what's been
 * posted before while Hide memories and recaps is on. The parser skips it the way it skips an item
 * that didn't parse. With Stop loading stories on, every item goes, and {@link #remaining} empties
 * the list of reels the tray would fetch after them, so nothing in the row loads.
 */
public final class StoriesTray {
    /**
     * The reel types of suggested tray items, by the constant names Instagram 449 gives them: an
     * account to follow, its story, and a creator's story.
     */
    static final Set<String> SUGGESTED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "SUGGESTED_USER", "SUGGESTED_USER_REEL", "SUGGESTED_CREATOR_REEL")));

    /** Rewind cards, which bring back old highlights. */
    static final Set<String> REWINDS = Collections.singleton("HIGHLIGHT_REWIND_REEL");

    /**
     * Cards Instagram makes from what's been posted before, by Instagram 450's names: memories, your
     * week, the year in review, follow anniversaries and birthdays.
     */
    static final Set<String> RECAPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MEMORY_REEL", "MY_WEEK_REEL", "END_OF_YEAR", "FOLLOW_VERSARIES", "BIRTHDAY_HIGHLIGHTS")));

    /** Every reel type a switch can take out. */
    static final Set<String> FILTERED;

    static {
        Set<String> all = new HashSet<>(SUGGESTED);
        all.addAll(REWINDS);
        all.addAll(RECAPS);
        FILTERED = Collections.unmodifiableSet(all);
    }

    /** The diagnostic counter routes for the tray's suggested items, rewinds and recaps. */
    static final String ROUTE = "Suggested stories";
    static final String REWIND_ROUTE = "Story rewinds";
    static final String RECAP_ROUTE = "Memories and recaps";
    static final String STOP_ROUTE = "Stop loading stories";

    private static volatile boolean loggedTray;

    private StoriesTray() {
    }

    /**
     * Injected first thing where Home's story tray adds its row. Answers true, and the tray adds
     * nothing, while Hide the Stories tray is on, and false otherwise, or when anything goes wrong.
     * Never throws, and never waits for the settings: before they're ready the tray stays.
     */
    public static boolean hideTray() {
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (!Utils.settingsReady() || !Settings.HIDE_STORIES_TRAY.get()) return false;
            if (!loggedTray) {
                loggedTray = true;
                Logger.printDebug(() -> "Stories tray: left the tray out of Home");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "tray row", failure);
            return false;
        }
    }

    /**
     * Injected where the tray's parser reads each item. Answers null for a suggested item, a rewind
     * or a recap while its switch is on, and [item] itself otherwise, or when anything goes wrong.
     * Never throws.
     */
    public static Object filter(Object item) {
        if (item == null) return null;
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (stopLoading()) {
                FeedFilterCounters.removed(STOP_ROUTE, 1, "any");
                return null;
            }
            String kind = FeedItemKinds.kindIn(item, FILTERED, FamilyNames.STORIES_TRAY);
            if (kind == null) return item;
            String route = SUGGESTED.contains(kind) ? ROUTE : REWINDS.contains(kind) ? REWIND_ROUTE : RECAP_ROUTE;
            FeedFilterCounters.sawKind(route, kind);
            if (!Utils.settingsReady() || !switchFor(kind).get()) return item;
            FeedFilterCounters.removed(route, 1, kind);
            Logger.printDebug(() -> "Stories tray: took out a " + kind + " item");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "tray item", failure);
            return item;
        }
    }

    /**
     * Injected where the tray's parser reads the ids of the reels it fetches after the tray's items.
     * Answers an empty list while Stop loading stories is on, and [ids] itself otherwise, or when
     * anything goes wrong. Never throws.
     */
    public static ArrayList<?> remaining(ArrayList<?> ids) {
        if (ids == null || ids.isEmpty()) return ids;
        try {
            HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (!stopLoading()) return ids;
            FeedFilterCounters.removed(STOP_ROUTE, ids.size(), "reel left to fetch");
            return new ArrayList<>();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORIES_TRAY, "reels left to fetch", failure);
            return ids;
        }
    }

    private static boolean stopLoading() {
        return Utils.settingsReady() && Settings.STOP_LOADING_STORIES.get();
    }

    private static BooleanSetting switchFor(String kind) {
        if (SUGGESTED.contains(kind)) return Settings.HIDE_SUGGESTED_STORIES;
        return REWINDS.contains(kind) ? Settings.HIDE_STORY_REWINDS : Settings.HIDE_STORY_RECAPS;
    }
}
