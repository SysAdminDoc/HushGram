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

/**
 * Helper for the "Don't save recent searches" patch.
 *
 * <p>When you open a result from search, Instagram does two things to remember it: it puts it at
 * the top of its recent searches cache, which the Recent list shows, and it tells its servers, which
 * keep Recent for your account. The patch asks {@link #keep} first in both, and a false skips the
 * method, so nothing new reaches Recent while the switch is on. Searches already there stay until
 * you clear them, and searching itself works as before.
 *
 * <p>With the switch off, HushGram paused, the settings not read yet or anything thrown, the answer
 * is true and Instagram saves the search as it always has.
 */
public final class RecentSearches {
    /** The step a failure is reported under. */
    static final String SAVE = "save a recent search";

    /** What's counted for each search kept out of Recent. */
    static final String SKIPPED = "kept out of Recent";

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

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.DONT_SAVE_RECENT_SEARCHES.get();
    }
}
