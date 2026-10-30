/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * A plain black cover over the Instagram screen the launcher shortcut lands on, from the moment
 * the shortcut's intent arrives until the settings have drawn over it. Without it the tab Instagram
 * starts on, often a playing reel, shows for about a second while the settings build.
 *
 * <p>The cover is a drawable in the window's overlay, the same black as the settings' window, so
 * nothing is laid out for it, it takes no touches, and neither Instagram nor the lock's cover sees
 * a new view. It comes off a moment after the settings' first frame, as soon as the screen under it
 * pauses, and after {@link #COVER_MAX_MS} without settings, or {@link #DRAW_WAIT_MS} after the
 * settings' window started without a frame, so a request that never opens can't leave Instagram
 * black. Main thread only.
 */
final class ShortcutCover {
    /** The longest the cover stays up while no settings window has started. */
    static final long COVER_MAX_MS = 4_000;
    /**
     * The longest the cover waits for the settings' first frame once their window has started.
     * During a cold start Instagram keeps the main thread busy, and on the emulator the settings
     * drew about 3 s after they were committed (2026-10-21).
     */
    static final long DRAW_WAIT_MS = 5_000;
    /** How long the cover stays after the settings' first frame is drawn, so that frame is on screen first. */
    static final long AFTER_DRAW_MS = 150;
    /** Bigger than any screen; the overlay clips it to the window. */
    private static final int SIDE = 1 << 14;

    private static Drawable cover;
    /** When the cover comes off if nothing took it off before, in uptime milliseconds. */
    private static long deadline;
    private static WeakReference<View> coveredWindow;
    /** A screen the shortcut started whose window isn't built yet; it's covered once it starts. */
    private static WeakReference<Activity> coverWhenStarted;

    private ShortcutCover() {
    }

    /**
     * Covers [activity]'s window, taking down a cover left on another. Settings already over it
     * are what shows, so they get none. During the screen's onCreate its window isn't built yet,
     * and building it there would fix its look before Instagram has set it, so the cover waits
     * for {@link #onStarted}, which is still before the screen's first frame.
     */
    @SuppressWarnings("deprecation") // Framework fragments, as the entry uses.
    static void show(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null || activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG) != null) return;
            View decor = window.peekDecorView();
            if (decor == null) {
                coverWhenStarted = new WeakReference<>(activity);
                return;
            }
            coverWhenStarted = null;
            take(cover, "a new request");
            final Drawable shown = new ColorDrawable(Color.BLACK);
            shown.setBounds(0, 0, SIDE, SIDE);
            decor.getOverlay().add(shown);
            cover = shown;
            coveredWindow = new WeakReference<>(decor);
            deadline = SystemClock.uptimeMillis() + COVER_MAX_MS;
            expireLater(shown, COVER_MAX_MS);
            Logger.printInfo(() -> "Settings entry: " + activity.getClass().getSimpleName() + " covered until the settings draw");
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not cover the screen under the settings", ex);
        }
    }

    /**
     * [activity] has started: covered now if the shortcut started it before its window was built.
     * Past onCreate, a window still not built is built here, as Android would at the first frame.
     */
    static void onStarted(Activity activity) {
        WeakReference<Activity> waiting = coverWhenStarted;
        if (waiting == null || waiting.get() != activity) return;
        try {
            if (activity.getWindow() != null) activity.getWindow().getDecorView();
        } catch (Exception ex) {
            coverWhenStarted = null;
            Logger.printException(() -> "Settings entry: could not build the screen's window for the cover", ex);
            return;
        }
        show(activity);
    }

    /** The settings window [settings] has started: the cover comes off a moment after its first frame. */
    static void offOnceDrawn(Dialog settings) {
        final Drawable shown = cover;
        if (shown == null) return;
        try {
            Window window = settings == null ? null : settings.getWindow();
            if (window == null) {
                take(shown, "the settings have no window");
                return;
            }
            final View decor = window.getDecorView();
            deadline = Math.max(deadline, SystemClock.uptimeMillis() + DRAW_WAIT_MS);
            decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver observer = decor.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    Utils.runOnMainThreadDelayed(() -> take(shown, "the settings drew"), AFTER_DRAW_MS);
                    return true;
                }
            });
        } catch (Exception ex) {
            take(shown, "the settings' frame can't be watched");
            Logger.printException(() -> "Settings entry: could not watch the settings' first frame", ex);
        }
    }

    /** [activity] is pausing: a cover on its window comes off now. */
    static void offIfOver(Activity activity) {
        if (coverWhenStarted != null && coverWhenStarted.get() == activity) coverWhenStarted = null;
        View decor = coveredWindow == null ? null : coveredWindow.get();
        if (decor == null || activity.getWindow() == null || activity.getWindow().peekDecorView() != decor) return;
        take(cover, "its screen paused");
    }

    /** The request the cover waited on was dropped: it comes off now. */
    static void off(String why) {
        coverWhenStarted = null;
        take(cover, why);
    }

    /** Whether [activity]'s window carries the cover. Package-visible for tests. */
    static boolean covers(Activity activity) {
        View decor = coveredWindow == null ? null : coveredWindow.get();
        return cover != null && cover.getCallback() != null && decor != null
                && activity.getWindow() != null && activity.getWindow().peekDecorView() == decor;
    }

    /** Takes [shown] off once {@link #deadline} has passed, looking again when it was moved later. */
    private static void expireLater(Drawable shown, long delay) {
        Utils.runOnMainThreadDelayed(() -> {
            if (shown != cover) return;
            long left = deadline - SystemClock.uptimeMillis();
            if (left > 0) expireLater(shown, left);
            else take(shown, "its time ran out");
        }, delay);
    }

    private static void take(Drawable which, String why) {
        if (which == null || which != cover) return;
        View decor = coveredWindow == null ? null : coveredWindow.get();
        cover = null;
        coveredWindow = null;
        try {
            if (decor != null) decor.getOverlay().remove(which);
            Logger.printInfo(() -> "Settings entry: cover off, " + why);
        } catch (Exception ex) {
            Logger.printException(() -> "Settings entry: could not take the cover off", ex);
        }
    }
}
