/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Pinch for clear mode (#744): two fingers on a reel turn Instagram's clear mode on, and two
 * fingers again turn it off.
 *
 * <p>Instagram 456 has half of this itself, behind a server flag. The Reels viewer hands its pinch
 * listener an action that runs each time two fingers lift off a reel, and with the flag on that
 * action writes the reel's clear mode state, on or off, and logs it. Nothing redraws for that write,
 * so the reel looks the same. The patch answers the flag through {@link #pinch} and hands the write
 * to {@link #turn} (or {@link #write}), which turns clear mode on or off through the handler Instagram's own menu row
 * uses, with the reason Instagram's pinch gives ({@link ReelClearMode#pinched}). The listener only
 * gets the pinch while Instagram's zoom for Reels is off, so with Pinch to zoom on, two fingers zoom
 * instead. The reel's touch hands each event to {@link #touch} first, and a second finger landing
 * keeps the list around the reel out of the gesture, so fingers spreading apart get there too.
 *
 * <p>A reel in clear mode draws a round exit button in its corner. Hide the exit button in clear
 * mode keeps that button where it is and as big as it was, but draws nothing there and takes no tap:
 * the patch hands its icon to {@link #exitIcon} and its circle and tap to {@link #exitPart}. The
 * button stays on the page, so Instagram still hears it leave the screen on a swipe and ends clear
 * mode as it always does. With Pinch to zoom on, the button stays, since a pinch can't leave clear
 * mode then.
 *
 * <p>Both switches are read each time, so a change shows on the next pinch or the next reel. Nothing
 * changes while they're off, HushGram is paused, the settings aren't ready, or anything here fails.
 */
public final class ReelPinchClearMode {
    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    private ReelPinchClearMode() {
    }

    /**
     * The pinch flag: Instagram's answer ([answer] nonzero for yes), or yes while Pinch for clear
     * mode is on. Instagram's answer otherwise, or when anything here fails.
     */
    public static boolean pinch(int answer) {
        boolean instagram = answer != 0;
        try {
            HookStatus.invoked(FAMILY);
            return instagram || on();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch for clear mode", failure);
            return instagram;
        }
    }

    /**
     * The hook, in place of the pinch action's write of the reel's clear mode state, with what it
     * writes to, the reel and the state asked for ([on] nonzero for in clear mode). While Pinch for
     * clear mode is on, the reel is put in clear mode or taken out of it the way Instagram's menu row
     * does, and the write, which shows nothing, is skipped. Otherwise, or when Instagram did neither,
     * the write goes through as it would.
     */
    public static void turn(@Nullable Object target, @Nullable Object item, int on) {
        if (!pinched(item)) state(target, item, on != 0);
    }

    /** Filled in by the patch: the pinch action's own write of the reel's clear mode state. Only what it writes to and a reel may be passed. */
    public static void state(Object target, Object item, boolean on) {
    }

    /**
     * {@link #turn} for builds whose pinch action writes the state through a static call instead
     * (456's 386214562 and 386214575), with what that call takes: the reel, the state's key, the
     * store it goes in, the value and whether the store tells anyone ([notify] nonzero for yes).
     */
    public static void write(@Nullable Object item, @Nullable Object key, @Nullable Object store, @Nullable Object value, int notify) {
        if (!pinched(item)) put(item, key, store, value, notify != 0);
    }

    /** Filled in by the patch: that static write. Only a reel, its state's key and its store may be passed. */
    public static void put(Object item, Object key, Object store, Object value, boolean notify) {
    }

    private static boolean pinched(@Nullable Object item) {
        try {
            if (!on()) return false;
            HookStatus.invoked(FAMILY);
            return ReelClearMode.pinched(item);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch for clear mode", failure);
            return false;
        }
    }

    /**
     * The exit button's icon: one that keeps the icon's size but draws nothing while the button is
     * hidden, or the icon itself.
     */
    @Nullable
    public static Drawable exitIcon(@Nullable Drawable icon) {
        try {
            if (icon == null || icon instanceof Unseen || !hidden()) return icon;
            HookStatus.invoked(FAMILY);
            return new Unseen(icon);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "clear mode exit button", failure);
            return icon;
        }
    }

    /**
     * The exit button's circle or tap: the modifier from before the part was added ([without]) while
     * the button is hidden, or the one with it ([with]).
     */
    @Nullable
    public static Object exitPart(@Nullable Object without, @Nullable Object with) {
        try {
            if (without == null || !hidden()) return with;
            HookStatus.invoked(FAMILY);
            return without;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "clear mode exit button", failure);
            return with;
        }
    }

    /** Whether the exit button is hidden: both switches on, and no zoom taking the pinch. */
    static boolean hidden() {
        return on() && Settings.HIDE_CLEAR_MODE_EXIT_BUTTON.get() && !zoomTakesPinch();
    }

    /** What the diagnostic report counts each time a second finger keeps the list out. */
    static final String KEPT_SPREAD = "pinch kept from the list";

    /**
     * The reel's touch, first thing, with the reel's view and the event. While Pinch for clear mode
     * is on and nothing else takes the pinch, a second finger landing on a reel asks the list around
     * it to leave the gesture alone. Without that, two fingers spreading out from close together
     * only start Instagram's pinch once they're about an inch apart, and by then the list has taken
     * them as a scroll or a pull to refresh, so only fingers closing in turned clear mode on or off.
     * Any other event, or the switch off, changes nothing.
     */
    public static void touch(@Nullable View view, @Nullable MotionEvent event) {
        try {
            if (view == null || event == null || event.getActionMasked() != MotionEvent.ACTION_POINTER_DOWN) return;
            if (!on() || zoomTakesPinch()) return;
            ViewParent parent = view.getParent();
            if (parent == null) return;
            HookStatus.invoked(FAMILY);
            parent.requestDisallowInterceptTouchEvent(true);
            HookStatus.counted(FAMILY, KEPT_SPREAD);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "pinch for clear mode", failure);
        }
    }

    /** Whether Pinch to zoom is on and in the build, so two fingers zoom the reel instead. */
    private static boolean zoomTakesPinch() {
        return Settings.PINCH_ZOOM_REELS.get() && PatchFamily.REEL_ZOOM.inBuild();
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.PINCH_CLEAR_MODE.get();
    }

    /** An icon's stand-in that keeps its size and draws nothing. */
    static final class Unseen extends Drawable {
        private final int width;
        private final int height;

        Unseen(Drawable icon) {
            width = icon.getIntrinsicWidth();
            height = icon.getIntrinsicHeight();
            setBounds(icon.getBounds());
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }
    }
}
