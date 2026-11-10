/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * What the Tab bar layout patch asks (#356).
 *
 * <p>Instagram 453 decides in one method whether an account gets the newer tab bar, the one with
 * Messages on it and a sideways swipe between every tab. It asks a stored override first, then a
 * server config, then a second gate. The tab host, Reels, Messages and the other screens that lay
 * themselves out for one bar or the other all ask that method, so a server change can flip the bar
 * back to the older one between two launches. The patch hands every answer of that method to
 * {@link #newTabBar}, which keeps it for Instagram's choice and replaces it for the other two.
 *
 * <p>The classic bar has no swipe pager, and 453's long press on Search waits for the pager's pages
 * before it opens search, so the patch also hands that wait to {@link #quickSearchPages} (#383).
 *
 * <p>Each tab sits in a wrapper that builds the tab's page only while it's marked as not loaded
 * yet. With the classic bar picked on an account Instagram gives the swipe bar, Android ending the
 * app in the background brings Home and Messages back marked loaded with no page in them, a blank
 * screen until the app is closed (#535). The patch asks {@link #reloadEmptyTab} when a tab comes
 * on screen that way.
 *
 * <p>Every answer is Instagram's own while the choice is Instagram's, HushGram is paused, the
 * settings aren't ready, or anything in here throws.
 */
public final class TabBarLayout {
    /** What {@link HookStatus} counts: an answer turned to the newer bar, and one turned to the older. */
    static final String SWIPE = "answers turned to the swipe bar";
    static final String CLASSIC = "answers turned to the classic bar";
    /** What {@link HookStatus} counts: a quick search that opened without waiting for swipe pages. */
    static final String QUICK_SEARCH = "quick searches opened on the classic bar";
    /** What {@link HookStatus} counts: a tab that came back empty and had its page built again. */
    static final String RELOADED = "empty tabs built again";

    /** The hooks' names in a failure report. */
    static final String GATE = "tab bar gate";
    static final String QUICK_SEARCH_WAIT = "quick search wait";
    static final String EMPTY_TAB = "empty tab";

    private static volatile boolean logged;

    private TabBarLayout() {
    }

    /**
     * Asked at each return of Instagram's new tab bar gate with the answer it was about to give, 1
     * for the newer bar and 0 for the older. Returns the answer to give instead. Never throws.
     */
    public static boolean newTabBar(int instagrams) {
        boolean stock = instagrams != 0;
        try {
            HookStatus.invoked(FamilyNames.TAB_BAR_LAYOUT);
            if (!Utils.settingsReady()) return stock;
            TabBarLayoutChoice choice = Settings.TAB_BAR_LAYOUT.get();
            if (choice == TabBarLayoutChoice.SWIPE || choice == TabBarLayoutChoice.CLASSIC) {
                boolean answer = choice == TabBarLayoutChoice.SWIPE;
                if (answer != stock) {
                    HookStatus.counted(FamilyNames.TAB_BAR_LAYOUT, answer ? SWIPE : CLASSIC);
                    if (!logged) {
                        logged = true;
                        Logger.printDebug(() -> "Tab bar layout: Instagram picked the "
                                + (stock ? "swipe" : "classic") + " bar, showing the " + (answer ? "swipe" : "classic") + " one");
                    }
                }
                return answer;
            }
            return stock;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAB_BAR_LAYOUT, GATE, failure);
            return stock;
        }
    }

    /**
     * Asked by the long press on the Search tab when the tab bar has no swipe pager, with the number
     * of swipe pages Instagram would wait to see built before it opens search. The classic bar builds
     * none, so that wait never ends and the press only switches to Explore. Returns 0, so search opens
     * right away. Never throws.
     */
    public static int quickSearchPages(int instagrams) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return instagrams;
            HookStatus.counted(FamilyNames.TAB_BAR_LAYOUT, QUICK_SEARCH);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAB_BAR_LAYOUT, QUICK_SEARCH_WAIT, failure);
            return instagrams;
        }
    }

    /**
     * Asked when a tab comes on screen marked as loaded with no page in it. Returns true, so the tab
     * builds its page again, while the user picked a bar, and false, leaving the tab as Instagram
     * has it, on Instagram's choice, paused or before the settings are ready. Never throws.
     */
    public static boolean reloadEmptyTab() {
        try {
            if (!Utils.settingsReady()) return false;
            TabBarLayoutChoice choice = Settings.TAB_BAR_LAYOUT.get();
            if (choice != TabBarLayoutChoice.SWIPE && choice != TabBarLayoutChoice.CLASSIC) return false;
            HookStatus.counted(FamilyNames.TAB_BAR_LAYOUT, RELOADED);
            Logger.printDebug(() -> "Tab bar layout: a tab came back empty, building its page again");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TAB_BAR_LAYOUT, EMPTY_TAB, failure);
            return false;
        }
    }
}
