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
import android.view.Display;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

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
 * many frames a second the video playing in a reel, a story or the feed has, and asks Instagram's
 * window for a refresh rate that frame rate divides into, so a screen that picks its own refresh
 * rate has one the video fits into evenly to settle on.
 *
 * <p>Instagram's player draws a video on a TextureView, which Android composes into the app's own
 * window, so the screen only sees the window redrawn and has to guess the video's rate. Some phones
 * guess badly: a vivo X300 Ultra hops between 60, 40 and 30 Hz under a 30 fps reel, and 40 Hz can't
 * show 30 frames evenly, so the reel judders. A video on a SurfaceView has a layer of its own that the
 * screen measures, so it isn't asked about here.
 *
 * <p>Before each frame of a resumed activity this reads the timestamp of the newest frame on each
 * video it found, works out how many frames a second come from the last {@link #WINDOW}, rounds that
 * to the nearest usual video rate and asks for it two ways:
 *
 * <ul>
 *   <li>On the video's view ({@code View.setRequestedFrameRate}, Android 15), which the window passes
 *       on as the rate of a fixed-rate source where the phone's Android does (a Galaxy S25 on One UI
 *       keeps it and never hands it to the screen).
 *   <li>On the window ({@code preferredDisplayModeId}), the way Use the highest refresh rate asks: the
 *       slowest display mode at the current resolution whose rate is a whole multiple of the video's
 *       and at least {@link #LOWEST_REFRESH} Hz, so 60 Hz for a 30 fps reel and 120 Hz for a 24 fps
 *       film where the screen has it. With no such mode it asks for that multiple by rate
 *       ({@code preferredRefreshRate}) and leaves the phone to pick. It stands aside while Use the
 *       highest refresh rate is on, which asks the same window for the fastest mode.
 * </ul>
 *
 * <p>Once a video's frames stop (paused, scrolled away), the view goes, the activity pauses or
 * closes, the switch is turned off or HushGram is paused, the view's request goes back to the
 * default and the window gets back what it held before. Below Android 15 it does nothing. Main
 * thread only.
 */
public final class VideoFrameRate {
    /** How each rate asked for on a video is counted in Hook status. */
    static final String ASKED = "Video frame rates asked for";
    /** How each refresh rate asked of a video's window is counted in Hook status. */
    static final String WINDOW_ASKED = "Video refresh rates asked of the window";
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
    /** The slowest refresh rate a window is asked for, in Hz: below it, scrolling would stutter. */
    static final float LOWEST_REFRESH = 60f;
    /** How far apart, in Hz, two rates may be and still count as the same (displays report 60.000004). */
    static final float HERTZ_SLACK = 0.5f;

    /** Asks a view for a frame rate. Tests stand in. */
    interface Voter {
        void vote(View view, float rate);
    }

    /** Reads the timestamp of a video's newest frame, in nanoseconds, or 0 when it has none. Tests stand in. */
    interface Stamps {
        long newest(TextureView view);
    }

    /** What the screen showing [decor] can run, the current mode's id in currentId[0], or null when it can't be read. Tests stand in. */
    interface Modes {
        @Nullable RefreshRate.Mode[] of(View decor, int[] currentId);
    }

    static volatile Voter voter = VideoFrameRate::request;
    static volatile Stamps stamps = VideoFrameRate::stampOf;
    static volatile Modes modes = VideoFrameRate::modesOf;

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

    /** Whether Use the highest refresh rate asks for the window itself right now, so the window is left to it. */
    static boolean highestAsks() {
        return Settings.HIGHEST_REFRESH_RATE.get();
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

    /** Whether [hertz] is a whole multiple of [rate], within {@link #HERTZ_SLACK}. */
    static boolean multipleOf(float hertz, float rate) {
        if (rate <= 0) return false;
        long times = Math.round(hertz / rate);
        return times >= 1 && Math.abs(hertz - times * rate) <= HERTZ_SLACK;
    }

    /**
     * The id of the slowest mode with [current]'s pixel size whose rate is a whole multiple of [rate]
     * and at least {@link #LOWEST_REFRESH}, or 0 when there's none or [current] isn't in [modes]
     * (Android numbers display modes from 1). The first of equal rates wins, so the answer doesn't
     * move between calls.
     */
    static int modeFor(float rate, int current, @Nullable RefreshRate.Mode[] modes) {
        if (modes == null || rate <= 0) return 0;
        RefreshRate.Mode now = null;
        for (RefreshRate.Mode mode : modes) if (mode != null && mode.id == current) now = mode;
        if (now == null) return 0;
        RefreshRate.Mode best = null;
        for (RefreshRate.Mode mode : modes) {
            if (mode == null || mode.width != now.width || mode.height != now.height) continue;
            if (mode.hertz < LOWEST_REFRESH - HERTZ_SLACK || !multipleOf(mode.hertz, rate)) continue;
            if (best == null || mode.hertz < best.hertz - HERTZ_SLACK) best = mode;
        }
        return best == null ? 0 : best.id;
    }

    /** The smallest whole multiple of [rate] that is at least {@link #LOWEST_REFRESH}: what's asked by rate when no mode has it. */
    static float refreshFor(float rate) {
        long times = Math.max(1, (long) Math.ceil((LOWEST_REFRESH - HERTZ_SLACK) / rate));
        return times * rate;
    }

    /** Starts following [window]'s videos before each frame, once. */
    static void track(@Nullable Window window) {
        View decor = window == null ? null : window.getDecorView();
        if (decor == null || WATCHED.containsKey(decor)) return;
        Watched watched = new Watched(decor, window);
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
        modes = VideoFrameRate::modesOf;
    }

    private static void request(View view, float rate) {
        if (Build.VERSION.SDK_INT >= 35) view.setRequestedFrameRate(rate);
    }

    private static long stampOf(TextureView view) {
        SurfaceTexture texture = view.getSurfaceTexture();
        return texture == null || !view.isAvailable() ? 0 : texture.getTimestamp();
    }

    @Nullable
    private static RefreshRate.Mode[] modesOf(View decor, int[] currentId) {
        Display display = decor.getDisplay();
        return display == null ? null : RefreshRate.displayModes(display, currentId);
    }

    /**
     * What one window was asked for and what it held before, so it gets that back. Main thread only.
     * Every call is its own try: a failure leaves the window as it was and is reported, never thrown.
     */
    static final class WindowAsk {
        private final WeakReference<Window> window;
        /** Whether the window holds this class's request now. */
        private boolean holding;
        private int earlierMode;
        private float earlierRefresh;
        private int askedMode;
        private float askedRefresh;
        /** The video rate the window was asked about, or 0 when it holds nothing of this class's. */
        float rate;
        /** Set once a request threw: from then on this window is left as Instagram has it. */
        private boolean broken;

        WindowAsk(@Nullable Window window) {
            this.window = new WeakReference<>(window);
        }

        /** Asks the window for [video]'s refresh rate, or gives it back with 0 or while Use the highest refresh rate asks. */
        void ask(View decor, float video) {
            if (broken) return;
            try {
                Window target = window.get();
                if (target == null) return;
                if (video <= 0 || highestAsks()) {
                    giveBack();
                    return;
                }
                WindowManager.LayoutParams params = target.getAttributes();
                // Something else asked the window since: what it asked is what comes back later.
                if (holding && !holds(params)) holding = false;
                if (holding && video == rate) return;
                int[] current = new int[1];
                RefreshRate.Mode[] found = modes.of(decor, current);
                int id = modeFor(video, current[0], found);
                if (!holding) {
                    earlierMode = params.preferredDisplayModeId;
                    earlierRefresh = params.preferredRefreshRate;
                }
                if (id != 0) {
                    params.preferredDisplayModeId = id;
                    params.preferredRefreshRate = earlierRefresh;
                } else {
                    params.preferredDisplayModeId = earlierMode;
                    params.preferredRefreshRate = refreshFor(video);
                }
                askedMode = params.preferredDisplayModeId;
                askedRefresh = params.preferredRefreshRate;
                holding = true;
                rate = video;
                target.setAttributes(params);
                HookStatus.counted(FamilyNames.REFRESH_RATE, WINDOW_ASKED);
            } catch (Throwable failure) {
                broken = true;
                HookStatus.threw(FamilyNames.REFRESH_RATE, "video refresh rate", failure);
                giveBack();
            }
        }

        private boolean holds(WindowManager.LayoutParams params) {
            return params.preferredDisplayModeId == askedMode && Float.compare(params.preferredRefreshRate, askedRefresh) == 0;
        }

        /**
         * Puts back what the window held before this class asked, while it still holds this class's
         * request: a window something else asked since (Use the highest refresh rate) keeps that.
         */
        void giveBack() {
            if (!holding) return;
            holding = false;
            rate = 0;
            try {
                Window target = window.get();
                if (target == null) return;
                WindowManager.LayoutParams params = target.getAttributes();
                if (!holds(params)) return;
                params.preferredDisplayModeId = earlierMode;
                params.preferredRefreshRate = earlierRefresh;
                target.setAttributes(params);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REFRESH_RATE, "video refresh rate give back", failure);
            }
        }
    }

    /** One window's videos, looked at before each of its frames. */
    static final class Watched implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> decor;
        final List<Video> videos = new ArrayList<>();
        final WindowAsk window;
        private long scanned = Long.MIN_VALUE;

        Watched(View decor, @Nullable Window window) {
            this.decor = new WeakReference<>(decor);
            this.window = new WindowAsk(window);
        }

        @Override
        public boolean onPreDraw() {
            try {
                frame(SystemClock.uptimeMillis());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REFRESH_RATE, "video frame rate", failure);
                window.giveBack();
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
            // The window follows the first video found that has a rate: one window has one refresh rate.
            float playing = 0;
            for (int i = videos.size() - 1; i >= 0; i--) {
                Video video = videos.get(i);
                if (!video.frame(now)) {
                    videos.remove(i);
                } else if (video.asked > 0) {
                    playing = video.asked;
                }
            }
            window.ask(root, playing);
        }

        void stopAll() {
            for (Video video : videos) video.stop();
            videos.clear();
            scanned = Long.MIN_VALUE;
            window.giveBack();
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

    /** One video and the timestamps of its newest frames. */
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
                track(activity.getWindow());
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
