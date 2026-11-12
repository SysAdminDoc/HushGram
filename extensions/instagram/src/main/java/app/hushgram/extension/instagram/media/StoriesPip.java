/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.util.Rational;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Picture in picture for stories, the third switch of the Keep playing in the background patch
 * (#572). Instagram has no small window for stories, so this makes one: leaving Instagram while a
 * story plays puts the screen showing it into picture in picture, and the story keeps going there,
 * on to the next one as it would on the full screen.
 *
 * <p>The story viewer reports each pause ({@link #pausing}) and each resume ({@link #resumed}), so
 * the viewer last resumed and not paused since is the one playing, and the screen it resumed on is
 * the one it plays on. {@link #leaving} runs first in the leave hint, before Keep playing in the
 * background looks for a video, and asks for the window when that screen is the one being left. A
 * screen in picture in picture is paused, and the viewer pauses its story as its fragment pauses
 * ("fragment_paused"): that one pause is held, and nothing else in the fragment's pause stops the
 * story. The viewer starts each video story only while its screen is resumed, so {@link #startsVideo}
 * answers that check and lets the viewer in the window start the next one too. Expanding the window
 * back ends the session with the story still playing. Closing the window stops the screen, and the
 * session ends by pausing the story through the viewer's own pause ({@link #pause}), since the
 * fragment won't pause it again as it stops.
 *
 * <p>The patch writes the bodies of {@link #pause} and {@link #isResumed}. Every hook runs on the
 * main thread, and none of them ever throws into Instagram.
 */
public final class StoriesPip {
    /** How long after asking for the window the screen has to be in it before the session is dropped. */
    static final long WATCHDOG_MS = 1_500;

    /** The reason the viewer's fragment gives its pause, the one pause held while the window is up. */
    static final String FRAGMENT_PAUSED = "fragment_paused";

    /** The window's shape: a story's. */
    static final Rational ASPECT = new Rational(9, 16);

    private static final String HOOK = "stories picture in picture";
    private static final String KEPT = "story pauses kept playing";

    /** What the window and the stubs do, for tests that have no Instagram screen. */
    interface Window {
        boolean enter(Activity screen);

        boolean showing(Activity screen);

        void pause(Object viewer);

        boolean resumed(Object viewer);
    }

    @Nullable
    static volatile Window windowForTests;

    private static final Object LOCK = new Object();
    /** The viewer that resumed a story last, and the screen that was in front as it did. */
    @Nullable
    private static WeakReference<Object> viewer;
    @Nullable
    private static WeakReference<Activity> viewerScreen;
    /** Whether that viewer paused its story since. */
    private static boolean viewerPaused = true;
    @Nullable
    private static WeakReference<Activity> resumedScreen;
    /** The viewer and screen in the window, while a session is under way. */
    @Nullable
    private static WeakReference<Object> heldViewer;
    @Nullable
    private static WeakReference<Activity> heldScreen;

    private StoriesPip() {
    }

    // ---- Stub the patch fills ----

    /** Pauses [viewer], a story viewer, through its own pause with Instagram's [reason]. The patch writes the body. */
    public static void pause(Object viewer, String reason) {
    }

    /** Whether [viewer], a story viewer, is resumed, from its own isResumed(). The patch writes the body. */
    public static boolean isResumed(Object viewer) {
        return false;
    }

    // ---- Hooks ----

    /**
     * Called first in Keep playing in the background's leave hint. True when [screen] went to
     * picture in picture with the story playing on it, so nothing else is held.
     */
    public static boolean leaving(Activity screen) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (screen == null || !on()) return false;
            if (screen.isFinishing() || screen.isChangingConfigurations() || showing(screen)) return false;
            Object playing;
            synchronized (LOCK) {
                if (heldViewer != null) return false;
                playing = viewer != null && !viewerPaused && viewerScreen != null && viewerScreen.get() == screen ? viewer.get() : null;
            }
            if (playing == null) return false;
            if (!enter(screen)) return false;
            synchronized (LOCK) {
                heldViewer = new WeakReference<>(playing);
                heldScreen = new WeakReference<>(screen);
            }
            HookStatus.counted(FamilyNames.BACKGROUND_PLAY, "story windows");
            log(() -> "you left Instagram with a story playing, so it went to picture in picture");
            Utils.runOnMainThreadDelayed(() -> watchdog(screen), WATCHDOG_MS);
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return false;
        }
    }

    /**
     * At the top of the story viewer's pause, after the hooks other patches put first there, with
     * the viewer and Instagram's reason. True, and the pause returns at once, only for the fragment's
     * own pause of the viewer in the window.
     */
    public static boolean pausing(Object paused, @Nullable String reason) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            HookStatus.bound(FamilyNames.BACKGROUND_PLAY, HOOK);
            synchronized (LOCK) {
                Object held = heldViewer != null ? heldViewer.get() : null;
                if (held != null && held == paused && FRAGMENT_PAUSED.equals(reason)) {
                    HookStatus.counted(FamilyNames.BACKGROUND_PLAY, KEPT);
                    return true;
                }
                if (viewer != null && viewer.get() == paused) viewerPaused = true;
            }
            return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, HOOK, t);
            return false;
        }
    }

    /** At the top of the story viewer's resume, after the hooks other patches put first there. */
    public static void resumed(Object resuming) {
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            if (resuming == null) return;
            synchronized (LOCK) {
                viewer = new WeakReference<>(resuming);
                viewerScreen = resumedScreen;
                viewerPaused = false;
            }
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "story resume", t);
        }
    }

    /**
     * In place of the isResumed() check in the story viewer's video start, which returns before the
     * player binds while the screen isn't resumed. A screen in the window is paused, so without this
     * the next video story would sit on its first frame there. Yes when [viewer] is resumed, or is
     * the one playing in the window.
     */
    public static boolean startsVideo(Object viewer) {
        if (resumedThrough(viewer)) return true;
        try {
            HookStatus.invoked(FamilyNames.BACKGROUND_PLAY);
            synchronized (LOCK) {
                if (viewer == null || heldViewer == null || heldViewer.get() != viewer) return false;
            }
            HookStatus.counted(FamilyNames.BACKGROUND_PLAY, "story videos started in the window");
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "story video start", t);
            return false;
        }
    }

    // ---- Screens, from Keep playing in the background's activity watch ----

    static void screenResumed(Activity screen) {
        boolean back;
        synchronized (LOCK) {
            resumedScreen = new WeakReference<>(screen);
            back = heldScreen != null && heldScreen.get() == screen;
        }
        // A screen in the window can resume there; only the full screen ends it.
        if (back && !showing(screen)) end(false, "the story came back to the full screen");
    }

    static void screenPaused(Activity screen) {
        synchronized (LOCK) {
            if (resumedScreen != null && resumedScreen.get() == screen) resumedScreen = null;
        }
    }

    static void screenStopped(Activity screen) {
        boolean held;
        synchronized (LOCK) {
            held = heldScreen != null && heldScreen.get() == screen;
            // A viewer whose screen stopped plays nothing, whether or not its fragment said so.
            if (!held && viewerScreen != null && viewerScreen.get() == screen) viewerPaused = true;
        }
        if (held) end(true, "the window closed");
    }

    static void screenDestroyed(Activity screen) {
        boolean held;
        synchronized (LOCK) {
            held = heldScreen != null && heldScreen.get() == screen;
        }
        if (held) end(false, "the story's screen closed");
    }

    /** Drops a session whose screen never went to the window. */
    static void watchdog(Activity screen) {
        boolean held;
        synchronized (LOCK) {
            held = heldScreen != null && heldScreen.get() == screen;
        }
        if (held && !showing(screen)) end(true, "Instagram never went to picture in picture");
    }

    /** True while a story plays in the window. */
    static boolean active() {
        synchronized (LOCK) {
            return heldViewer != null;
        }
    }

    /** Ends the session, pausing the story first when [pauseStory]. */
    static void end(boolean pauseStory, String why) {
        Object held;
        synchronized (LOCK) {
            if (heldViewer == null) return;
            held = heldViewer.get();
            heldViewer = null;
            heldScreen = null;
        }
        if (pauseStory && held != null) pauseThrough(held);
        log(() -> "story picture in picture ended: " + why);
    }

    // ---- Helpers ----

    private static void pauseThrough(Object held) {
        try {
            Window window = windowForTests;
            if (window != null) window.pause(held);
            else pause(held, FRAGMENT_PAUSED);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.BACKGROUND_PLAY, "story pause", t);
        }
    }

    /** The viewer's own answer. Called outside every catch, so a throw stays Instagram's, as it would be unpatched. */
    private static boolean resumedThrough(Object viewer) {
        Window window = windowForTests;
        return window != null ? window.resumed(viewer) : isResumed(viewer);
    }

    private static boolean enter(Activity screen) {
        Window window = windowForTests;
        if (window != null) return window.enter(screen);
        if (!screen.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            log(() -> "this phone has no picture in picture");
            return false;
        }
        try {
            return screen.enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(ASPECT).build());
        } catch (IllegalStateException | IllegalArgumentException e) {
            // A screen that doesn't declare picture in picture, or Android saying no.
            Logger.printInfo(() -> "Android wouldn't put " + screen.getClass().getSimpleName() + " in picture in picture", e);
            return false;
        }
    }

    private static boolean showing(Activity screen) {
        try {
            Window window = windowForTests;
            return window != null ? window.showing(screen) : screen.isInPictureInPictureMode();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.STORIES_PICTURE_IN_PICTURE.get();
    }

    private static void log(Logger.LogMessage message) {
        Logger.printInfo(message);
    }

    /** Clears every session and record. For tests. */
    static void resetForTests() {
        synchronized (LOCK) {
            viewer = null;
            viewerScreen = null;
            viewerPaused = true;
            resumedScreen = null;
            heldViewer = null;
            heldScreen = null;
        }
        windowForTests = null;
    }
}
