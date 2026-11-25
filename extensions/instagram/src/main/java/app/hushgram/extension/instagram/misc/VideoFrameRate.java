/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Match the video's frame rate (#868), a switch of Use the highest refresh rate: tells Android how
 * many frames a second the video playing in a reel, a story or the feed has, so a screen that picks
 * its own refresh rate can settle on one the video fits into evenly.
 *
 * <p>Instagram's player draws a video on a TextureView, which Android composes into the app's own
 * window, so the screen only sees the window redrawn and has to guess the video's rate. Some phones
 * guess badly: a vivo X300 Ultra hops between 60, 40 and 30 Hz under a 30 fps reel, and 40 Hz can't
 * show 30 frames evenly, so the reel judders. A video on a SurfaceView has a layer of its own that the
 * screen measures, so it isn't asked about here.
 *
 * <p>From Android 15 a view can ask for a frame rate ({@code View.setRequestedFrameRate}), which its
 * window passes on as the rate of a fixed-rate source. Before each frame of a resumed activity this
 * reads the timestamp of the newest frame on each video it found, works out how many frames a second
 * come from the last {@link #WINDOW}, rounds that to the nearest usual video rate and asks for it on
 * that video's view. Once a video's frames stop (paused, scrolled away), the view goes, the switch is
 * turned off or HushGram is paused, the view's request goes back to the default. Below Android 15 it
 * does nothing. Main thread only.
 */
public final class VideoFrameRate {
    /** How each rate asked for on a video is counted in Hook status. */
    static final String ASKED = "Video frame rates asked for";
    /** How often a watched window is searched for videos. */
    static final long SCAN_EVERY_MS = 500;
    /** A video with no new frame for this long has stopped, and its request is taken back. */
    static final long STOPPED_AFTER_MS = 300;
    /** A gap this long between two frames (a seek, the next clip) starts the count again. */
    static final long GAP_NS = 250_000_000L;
    /** How many of the newest frames a rate is worked out from. */
    static final int WINDOW = 31;
    /** The fewest frames a rate is worked out from. */
    static final int MIN_FRAMES = 12;
    /** The rates videos come in. A measured rate counts as the nearest. */
    static final float[] RATES = {24f, 25f, 30f, 48f, 50f, 60f};
    /** How far, as a share of it, a measured rate may be from the nearest usual one. */
    static final float TOLERANCE = 0.08f;
    /** The most views one search looks at, and the most videos one window follows. */
    static final int SCAN_LIMIT = 5000;
    static final int VIDEO_LIMIT = 8;
    /** What a view asks for when it asks for nothing: Android's default, {@code REQUESTED_FRAME_RATE_CATEGORY_DEFAULT}. */
    static final float DEFAULT = Float.NaN;

    /** Asks a view for a frame rate. Tests stand in. */
    interface Voter {
        void vote(View view, float rate);
    }

    /** Reads the timestamp of a video's newest frame, in nanoseconds, or 0 when it has none. Tests stand in. */
    interface Stamps {
        long newest(TextureView view);
    }

    static volatile Voter voter = VideoFrameRate::request;
    static volatile Stamps stamps = VideoFrameRate::stampOf;

    /** The watched windows. Main thread only. */
    private static final Map<View, Watched> WATCHED = new WeakHashMap<>();
    private static volatile boolean registered;

    private VideoFrameRate() {
    }

    /** Called as the application starts. Does nothing in a build without Use the highest refresh rate. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.refreshRate()) return;
            if (Build.VERSION.SDK_INT < 35) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REFRESH_RATE, "video frame rate start", failure);
        }
    }

    /** Whether the switch answers yes right now. Off while paused or before the settings are read. */
    static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.MATCH_VIDEO_FRAME_RATE.get();
    }

    /**
     * The usual video rate [count] frame timestamps (nanoseconds, oldest first) come at, or 0 when
     * there are too few or they don't come near one. The rate is read over the whole span, so frames
     * Android showed late or early still add up to the video's own rate.
     */
    static float rateOf(long[] stamps, int count) {
        if (count < MIN_FRAMES) return 0;
        long span = stamps[count - 1] - stamps[0];
        if (span <= 0) return 0;
        double measured = (count - 1) * 1e9 / span;
        float best = 0;
        double off = Double.MAX_VALUE;
        for (float rate : RATES) {
            double distance = Math.abs(measured - rate) / rate;
            if (distance < off) {
                off = distance;
                best = rate;
            }
        }
        return off <= TOLERANCE ? best : 0;
    }

    /** Starts following [decor]'s videos before each frame, once. */
    static void track(@Nullable View decor) {
        if (decor == null || WATCHED.containsKey(decor)) return;
        Watched watched = new Watched(decor);
        WATCHED.put(decor, watched);
        decor.getViewTreeObserver().addOnPreDrawListener(watched);
    }

    /** Stops following [decor], taking back what it asked for. */
    static void release(@Nullable View decor) {
        Watched watched = decor == null ? null : WATCHED.remove(decor);
        if (watched == null) return;
        if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnPreDrawListener(watched);
        watched.stopAll();
    }

    /** Takes back what [decor]'s videos asked for, and keeps following it. */
    static void quiet(@Nullable View decor) {
        Watched watched = decor == null ? null : WATCHED.get(decor);
        if (watched != null) watched.stopAll();
    }

    @Nullable
    static Watched watchedForTests(View decor) {
        return WATCHED.get(decor);
    }

    static void resetForTests() {
        WATCHED.clear();
        voter = VideoFrameRate::request;
        stamps = VideoFrameRate::stampOf;
    }

    private static void request(View view, float rate) {
        if (Build.VERSION.SDK_INT >= 35) view.setRequestedFrameRate(rate);
    }

    private static long stampOf(TextureView view) {
        SurfaceTexture texture = view.getSurfaceTexture();
        return texture == null || !view.isAvailable() ? 0 : texture.getTimestamp();
    }

    /** One window's videos, looked at before each of its frames. */
    static final class Watched implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> decor;
        final List<Video> videos = new ArrayList<>();
        private long scanned = Long.MIN_VALUE;

        Watched(View decor) {
            this.decor = new WeakReference<>(decor);
        }

        @Override
        public boolean onPreDraw() {
            try {
                frame(SystemClock.uptimeMillis());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REFRESH_RATE, "video frame rate", failure);
            }
            return true;
        }

        /** What happens before a frame drawn at [now] (uptime, milliseconds). */
        void frame(long now) {
            View root = decor.get();
            if (root == null) return;
            if (!on()) {
                stopAll();
                return;
            }
            if (scanned == Long.MIN_VALUE || now - scanned >= SCAN_EVERY_MS) {
                scanned = now;
                find(root, new int[]{SCAN_LIMIT});
            }
            for (int i = videos.size() - 1; i >= 0; i--) {
                if (!videos.get(i).frame(now)) videos.remove(i);
            }
        }

        void stopAll() {
            for (Video video : videos) video.stop();
            videos.clear();
            scanned = Long.MIN_VALUE;
        }

        private void find(View view, int[] budget) {
            if (budget[0]-- <= 0 || view.getVisibility() != View.VISIBLE) return;
            if (view instanceof TextureView) {
                follow((TextureView) view);
                return;
            }
            if (!(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) find(group.getChildAt(i), budget);
        }

        private void follow(TextureView view) {
            for (Video video : videos) if (video.view.get() == view) return;
            if (videos.size() < VIDEO_LIMIT) videos.add(new Video(view));
        }
    }

    /** One video's newest frames and the rate asked for on its view. */
    static final class Video {
        final WeakReference<TextureView> view;
        /** The newest frames' timestamps, a ring of {@link #WINDOW}. */
        private final long[] ring = new long[WINDOW];
        private int start;
        private int count;
        private long newest;
        private long changedAt;
        /** The rate asked for on the view, or 0 when it asks for nothing. */
        float asked;

        Video(TextureView view) {
            this.view = new WeakReference<>(view);
        }

        /** Looks at the video before a frame drawn at [now]. False once the view is gone for good. */
        boolean frame(long now) {
            TextureView video = view.get();
            if (video == null) return false;
            if (!video.isAttachedToWindow() || !video.isShown()) {
                stop();
                return false;
            }
            long stamp = stamps.newest(video);
            if (stamp > 0 && stamp != newest) {
                if (newest != 0 && (stamp < newest || stamp - newest > GAP_NS)) count = 0;
                ring[(start + count) % WINDOW] = stamp;
                if (count < WINDOW) count++;
                else start = (start + 1) % WINDOW;
                newest = stamp;
                changedAt = now;
            } else if (now - changedAt > STOPPED_AFTER_MS) {
                stop();
                return true;
            }
            long[] ordered = new long[count];
            for (int i = 0; i < count; i++) ordered[i] = ring[(start + i) % WINDOW];
            float rate = rateOf(ordered, count);
            if (rate > 0 && rate != asked) {
                HookStatus.invoked(FamilyNames.REFRESH_RATE);
                voter.vote(video, rate);
                asked = rate;
                HookStatus.counted(FamilyNames.REFRESH_RATE, ASKED);
            }
            return true;
        }

        /** Takes back the rate asked for, and forgets the frames counted so far. */
        void stop() {
            count = 0;
            start = 0;
            if (asked == 0) return;
            asked = 0;
            TextureView video = view.get();
            if (video != null) voter.vote(video, DEFAULT);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window != null) track(window.getDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REFRESH_RATE, "video frame rate watch", failure);
            }
        }

        @Override public void onActivityPaused(@NonNull Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window != null) quiet(window.peekDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REFRESH_RATE, "video frame rate pause", failure);
            }
        }

        @Override public void onActivityDestroyed(@NonNull Activity activity) {
            try {
                Window window = activity.getWindow();
                if (window != null) release(window.peekDecorView());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REFRESH_RATE, "video frame rate release", failure);
            }
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
    }
}
