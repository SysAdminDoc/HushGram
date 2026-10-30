/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Don't save recent searches" and "Show all recent searches" patches.
 *
 * <p>When you open a result from search, Instagram does two things to remember it: it puts it at
 * the top of its recent searches cache, which the Recent list shows, and it tells its servers, which
 * keep Recent for your account. The patch asks {@link #keep} first in both, and a false skips the
 * method, so nothing new reaches Recent while the switch is on. Searches already there stay until
 * you clear them, and searching itself works as before.
 *
 * <p>With the switch off, HushGram paused, the settings not read yet or anything thrown, the answer
 * is true and Instagram saves the search as it always has.
 *
 * <p>Show all recent searches (#257) works on the other end: search's empty screen keeps the whole
 * list on the phone and trims it to the first few before drawing it, and {@link #shown} lifts that
 * trim. Nothing here changes what is saved, so Don't save recent searches still keeps the list from
 * growing.
 */
public final class RecentSearches {
    /** The step a failure is reported under. */
    static final String SAVE = "save a recent search";

    /** What's counted for each search kept out of Recent. */
    static final String SKIPPED = "kept out of Recent";

    /** The step a failure is reported under when the list is built. */
    static final String LIST = "list the recent searches";

    /** What's counted for each time the screen listed every recent search. */
    static final String LISTED = "listed every recent search";

    private RecentSearches() {
    }

    /** Injected first in the cache's add and the call that tells the servers. False skips the method. */
    public static boolean keep() {
        return keep(RecentSearches::switchedOn);
    }

    static boolean keep(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.RECENT_SEARCHES);
            if (!on.getAsBoolean()) return true;
            HookStatus.counted(FamilyNames.RECENT_SEARCHES, SKIPPED);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.RECENT_SEARCHES, SAVE, failure);
            return true;
        }
    }

    /**
     * Injected where the search screen works out how many recent searches to list. Answers no limit
     * while Show all recent searches is on, or [cap] as Instagram worked it out. Instagram keeps the
     * whole list on the phone and only trims it to the first few for this screen, so nothing is
     * fetched and nothing is faked. Never throws.
     */
    public static int shown(int cap) {
        return shown(cap, RecentSearches::showAllOn);
    }

    static int shown(int cap, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.ALL_RECENT_SEARCHES);
            if (!on.getAsBoolean()) return cap;
            HookStatus.counted(FamilyNames.ALL_RECENT_SEARCHES, LISTED);
            return Integer.MAX_VALUE;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ALL_RECENT_SEARCHES, LIST, failure);
            return cap;
        }
    }

    private static boolean showAllOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SHOW_ALL_RECENT_SEARCHES.get();
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.DONT_SAVE_RECENT_SEARCHES.get();
    }
}
