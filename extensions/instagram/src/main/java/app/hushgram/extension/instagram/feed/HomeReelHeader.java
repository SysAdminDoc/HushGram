/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the Reels on Home below the name patch (#788). Instagram shows some reels on Home as a
 * tall card with the author's name, the three-dot menu and the line under the name drawn on top of
 * the video. It picks that layout in two places, both asked with the feed's module name: one test
 * that says the feed puts reels full height, and one that says this reel is tall enough for it.
 *
 * <p>The patch asks {@link #belowName} first thing in both. With the switch on, and only for Home's
 * own feed, the answer is no, so the reel gets the layout of every other post: the header row above
 * the video, nothing on top of it. Search, Explore, profiles and the Reels tab ask with other module
 * names and keep Instagram's answer.
 */
public final class HomeReelHeader {
    static final String HOOK = "home reel header";
    static final String COUNT_BELOW = "home reels laid out below the name";

    /** The module names of Home's feed, the first page and the older posts after it. */
    static final String HOME_MODULE = "feed_timeline";
    static final String OLDER_MODULE = "feed_timeline_older";

    private HomeReelHeader() {
    }

    /** True when a reel on [module] should get the plain post layout, its name above the video. */
    public static boolean belowName(@Nullable String module) {
        return belowName(module, HomeReelHeader::wanted);
    }

    static boolean belowName(@Nullable String module, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.HOME_REELS_BELOW_NAME);
            if (!HOME_MODULE.equals(module) && !OLDER_MODULE.equals(module)) return false;
            if (!on.getAsBoolean()) return false;
            HookStatus.counted(FamilyNames.HOME_REELS_BELOW_NAME, COUNT_BELOW);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_REELS_BELOW_NAME, HOOK, failure);
            return false;
        }
    }

    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HOME_REELS_BELOW_NAME.get();
    }
}
