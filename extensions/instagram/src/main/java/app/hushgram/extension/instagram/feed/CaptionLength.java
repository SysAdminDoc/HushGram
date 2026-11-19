/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the Expand captions patch (#771). Instagram cuts a feed post's caption after one or two
 * lines and puts "more" at the end. It builds the caption's text in one place for every surface, the
 * feeds, Reels, stories and shopping, and hands a helper the line count to cut at, which it picks by
 * the surface the post shows on. The patch asks {@link #maxLines} for that count just before the cut,
 * with the surface beside it.
 *
 * <p>The surface is an enum whose constants keep Instagram's own names (MAIN_FEED, EXPLORE_FEED,
 * CLIPS_VIEWER, STORY_VIEWER and so on). The feeds in {@link #FEEDS} get the pick. Reels
 * ({@link #REELS}) go by a switch of their own (#752) and show the whole caption, since a reel's
 * caption sits over the video and a line count there would mean little. Stories and the full screen
 * viewers keep Instagram's count. A count of zero or less means Instagram doesn't cut there at all,
 * and it stays that way. So does a count past {@link #MOST_PICKED}: Instagram's own counts are one,
 * two or five lines, and a bigger one is a caption it has opened up, which a smaller pick must never
 * close again.
 */
public final class CaptionLength {
    /** The surfaces that show posts in a scrolling feed, by Instagram's names. */
    static final Set<String> FEEDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MAIN_FEED", "EXPLORE_FEED", "EXPLORE_VIDEO_FEED", "HASHTAG_PAGE", "LOCATION_PAGE", "SAVE_FEED",
            "SAVE_HOME", "SERP", "SINGLE_MEDIA_FEED", "LIKED_FEED", "MEDIA_CONTEXTUAL_FEED")));

    /** The surfaces that play reels, the Reels tab and a reel opened anywhere else, by Instagram's names. */
    static final Set<String> REELS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CLIPS_VIEWER", "CLIPS_REMIX_ATTRIBUTION_FEED")));

    /** The most lines a numbered pick shows; Instagram counts above it are left alone. */
    static final int MOST_PICKED = CaptionLines.TEN.lines;

    static final String HOOK = "caption lines";
    static final String COUNT_CHANGED = "captions lengthened";
    static final String REEL_COUNT_CHANGED = "reel captions lengthened";

    private CaptionLength() {
    }

    /**
     * The line count to cut a caption on [surface] at, where Instagram picked [instagram]. With the
     * feed switch on and a feed surface it's the count picked in HushGram settings, and
     * {@link Integer#MAX_VALUE} for the whole caption. With the reels switch on and a reel surface
     * it's the whole caption. Anything else, or anything that throws, is [instagram]. Never throws.
     */
    public static int maxLines(@Nullable Object surface, int instagram) {
        return maxLines(surface, instagram, CaptionLength::wanted, Settings.CAPTION_LINES::get, CaptionLength::reelsWanted);
    }

    static int maxLines(@Nullable Object surface, int instagram, BooleanSupplier on, Supplier<CaptionLines> choice) {
        return maxLines(surface, instagram, on, choice, CaptionLength::reelsWanted);
    }

    static int maxLines(@Nullable Object surface, int instagram, BooleanSupplier on, Supplier<CaptionLines> choice,
                        BooleanSupplier reelsOn) {
        try {
            HookStatus.invoked(FamilyNames.EXPAND_CAPTIONS);
            if (instagram < 1 || instagram > MOST_PICKED || !(surface instanceof Enum)) return instagram;
            String name = ((Enum<?>) surface).name();
            if (REELS.contains(name)) {
                if (!reelsOn.getAsBoolean()) return instagram;
                HookStatus.counted(FamilyNames.EXPAND_CAPTIONS, REEL_COUNT_CHANGED);
                return CaptionLines.WHOLE.lines;
            }
            if (!FEEDS.contains(name) || !on.getAsBoolean()) return instagram;
            CaptionLines picked = choice.get();
            int lines = picked == null ? CaptionLines.WHOLE.lines : picked.lines;
            if (lines != instagram) HookStatus.counted(FamilyNames.EXPAND_CAPTIONS, COUNT_CHANGED);
            return lines;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EXPAND_CAPTIONS, HOOK, failure);
            return instagram;
        }
    }

    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.EXPAND_CAPTIONS.get();
    }

    static boolean reelsWanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.EXPAND_REEL_CAPTIONS.get();
    }
}
