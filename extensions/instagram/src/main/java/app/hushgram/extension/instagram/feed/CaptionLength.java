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
 * CLIPS_VIEWER, STORY_VIEWER and so on). Only the feeds in {@link #FEEDS} change. Reels, stories and
 * the full screen viewers keep Instagram's count, since their caption sits over the video. A count of
 * zero or less means Instagram doesn't cut there at all, and it stays that way. So does a count past
 * {@link #MOST_PICKED}: Instagram's own counts are one, two or five lines, and a bigger one is a
 * caption it has opened up, which a smaller pick must never close again.
 */
public final class CaptionLength {
    /** The surfaces that show posts in a scrolling feed, by Instagram's names. */
    static final Set<String> FEEDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "MAIN_FEED", "EXPLORE_FEED", "EXPLORE_VIDEO_FEED", "HASHTAG_PAGE", "LOCATION_PAGE", "SAVE_FEED",
            "SAVE_HOME", "SERP", "SINGLE_MEDIA_FEED", "LIKED_FEED", "MEDIA_CONTEXTUAL_FEED")));

    /** The most lines a numbered pick shows; Instagram counts above it are left alone. */
    static final int MOST_PICKED = CaptionLines.TEN.lines;

    static final String HOOK = "caption lines";
    static final String COUNT_CHANGED = "captions lengthened";

    private CaptionLength() {
    }

    /**
     * The line count to cut a caption on [surface] at, where Instagram picked [instagram]. With the
     * switch on and a feed surface it's the count picked in HushGram settings, and
     * {@link Integer#MAX_VALUE} for the whole caption. Anything else, or anything that throws, is
     * [instagram]. Never throws.
     */
    public static int maxLines(@Nullable Object surface, int instagram) {
        return maxLines(surface, instagram, CaptionLength::wanted, Settings.CAPTION_LINES::get);
    }

    static int maxLines(@Nullable Object surface, int instagram, BooleanSupplier on, Supplier<CaptionLines> choice) {
        try {
            HookStatus.invoked(FamilyNames.EXPAND_CAPTIONS);
            if (instagram < 1 || instagram > MOST_PICKED || !(surface instanceof Enum) || !FEEDS.contains(((Enum<?>) surface).name())) return instagram;
            if (!on.getAsBoolean()) return instagram;
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
}
