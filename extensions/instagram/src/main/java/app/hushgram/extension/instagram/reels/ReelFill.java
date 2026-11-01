/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Fill the screen, under Full screen Reels (#288).
 *
 * <p>Instagram decides for each reel whether its video fills the viewer, cut at the sides, or fits
 * inside it with bars above and below. It works out how far the viewer's shape is from the video's,
 * as a percentage, and crops only below thresholds it gets from its server. With the status bar and
 * the navigation bar hidden the viewer is taller, so a 9:16 reel on a 20:9 or 21:9 screen is about
 * 18 to 24 percent off and often lands just past the threshold: black bars on some reels and not
 * others. The patch asks here right after Instagram works the percentage out, and true makes the
 * reel fill the screen.
 *
 * <p>Hiding the comment bar makes the viewer taller the same way, with neither of those switches on:
 * its height goes to the viewer, so on a 1080x2400 screen a 9:16 reel is about 13 percent off and gets
 * a black band above and below (#371). So a reel whose bar is hidden fills too: every reel with the
 * switch for all reels, only reposted ones with the switch for those.
 *
 * <p>Show the whole reel (piko #2183) goes the other way and asks first, before Instagram's early
 * crop for media it lets crop: true makes every reel fit inside the viewer with bars where its shape
 * differs, so nothing is cut at the sides. It doesn't need Full screen Reels and wins over Fill.
 */
public final class ReelFill {
    /**
     * The most a reel is cut to fill the screen, as a percentage of its shape. A 9:16 video on a 21:9
     * screen is just under it, and a square or 4:5 video, which would lose a third or more of its
     * picture, still fits with bars.
     */
    static final float MOST_CROP = 25f;

    /** What a reel cut to fill the screen is counted under. */
    static final String COUNT_FILLED = "reels filled to the screen";

    /** What a reel kept whole inside the screen is counted under. */
    static final String COUNT_FITTED = "reels shown whole";

    private ReelFill() {
    }

    /**
     * Asked first as Instagram decides whether a reel fills the viewer. True when Show the whole reel
     * is on, which makes the decision answer no: the reel fits with bars instead of being cut. False
     * hands the decision on to Fill the screen and Instagram, and so does anything that throws.
     */
    public static boolean fit() {
        try {
            if (!Utils.settingsReady() || !Settings.FIT_REEL_SCREEN.get()) return false;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_FITTED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "reel fit", failure);
            return false;
        }
    }

    /**
     * Asked as Instagram decides whether a reel fills the viewer, with the viewer's ClipsViewerSource
     * and how far the viewer's shape is from the reel's in percent. True when Full screen Reels and
     * Fill the screen are both on, or this reel's comment bar is hidden, and the reel would lose no
     * more than {@link #MOST_CROP}, which crops it to fill the screen. False hands the decision back
     * to Instagram, and so does anything that throws.
     */
    public static boolean fill(Object source, float cropPercent) {
        try {
            if (!Utils.settingsReady() || !wanted(source)) return false;
            if (!(cropPercent >= 0f && cropPercent <= MOST_CROP)) return false;
            HookStatus.counted(FamilyNames.FULL_SCREEN_BARS, COUNT_FILLED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_SCREEN_BARS, "reel fill", failure);
            return false;
        }
    }

    private static boolean wanted(Object source) {
        return Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE.get()
                || (Settings.HIDE_REEL_COMMENT_BAR.get() && ReelDeclutter.opensRepost(source))
                || (Settings.FILL_REEL_SCREEN.get() && Settings.FULL_SCREEN_REELS.get());
    }
}
