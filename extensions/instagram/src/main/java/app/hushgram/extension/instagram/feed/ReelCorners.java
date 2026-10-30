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
 * Helper for Rounded corners on Reels (#362), part of the Rounded post corners patch.
 *
 * <p>Each page of the Reels viewer, in the Reels tab and wherever a reel opens full screen, keeps its
 * video in a frame of its own, {@link #FRAME}. The seek bar, captions and buttons are drawn in layers
 * beside it, so cutting that frame's corners rounds the video and leaves them whole.
 * {@link FrameCorners} finds and rounds it while the switch is on.
 */
public final class ReelCorners {
    /** The frame a reel's video sits in, alone, under the viewer's overlay. */
    static final String FRAME = "clips_viewer_video_layout";

    /** The most views looked at in one search, so a strange tree can't make a long one. */
    static final int MAX_VIEWS = FrameCorners.MAX_VIEWS;

    static final String HOOK = "reel frame";
    static final String COUNT_ROUNDED = "reel frames rounded";

    private static final FrameCorners corners = new FrameCorners(FRAME, Settings.ROUNDED_REEL_CORNERS, HOOK,
            COUNT_ROUNDED, "Rounded corners on Reels: rounded a reel's video frame");

    private ReelCorners() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        corners.install(context);
    }

    /** Whether reels are rounded now: the switch on, HushGram not paused, the settings read. */
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
