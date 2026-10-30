/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.Settings;

/**
 * Helper for Rounded corners on stories (#363, #368), part of the Rounded post corners patch.
 *
 * <p>Each page of the story viewer, from the tray, a profile or a highlight, keeps its photo or video in
 * {@link #FRAME}. Instagram's own rounded layout around it, {@code reel_viewer_media_layout}, also holds
 * the progress bar, the header and the stickers, so cutting the media frame alone rounds the picture at
 * the size Corner size picks and leaves those whole. {@link FrameCorners} finds and rounds it while the
 * switch is on.
 */
public final class StoryCorners {
    /** The frame a story's photo or video sits in, inside the viewer's rounded media layout. */
    static final String FRAME = "reel_viewer_media_container";

    static final String HOOK = "story frame";
    static final String COUNT_ROUNDED = "story frames rounded";

    private static final FrameCorners corners = new FrameCorners(FRAME, Settings.ROUNDED_STORY_CORNERS, HOOK,
            COUNT_ROUNDED, "Rounded corners on stories: rounded a story's media frame");

    private StoryCorners() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        corners.install(context);
    }

    /** Whether stories are rounded now: the switch on, HushGram not paused, the settings read. */
    static boolean wanted() {
        return corners.wanted();
    }

    /** Starts watching [activity] if the switch is on, or puts back what was rounded if it's off. */
    static void watch(Activity activity) {
        corners.watch(activity);
    }

    /** The switch was flipped in HushGram settings, over [activity]. */
    public static void switchChanged(@Nullable Activity activity) {
        corners.switchChanged(activity);
    }

    static void unwatch(Activity activity) {
        corners.unwatch(activity);
    }

    /** Tests give the frame an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, int id) {
        corners.idForTests(context, id);
    }

    static int roundedForTests() {
        return corners.roundedForTests();
    }

    public static boolean listeningForTests(Activity activity) {
        return corners.listeningForTests(activity);
    }

    public static void resetForTests() {
        corners.resetForTests();
    }
}
