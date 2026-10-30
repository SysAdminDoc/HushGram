/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.res.Resources;
import android.util.DisplayMetrics;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.media.DataSaver;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Ask for larger photos" switch of the "Full resolution photos" patch.
 *
 * <p>Instagram's server lists the sizes of a photo it sends for the screen Instagram reports, and
 * Instagram then picks the size nearest the width it shows the photo at. On a 1080-wide phone both
 * stop at 1080, so Full resolution photos has nothing larger to pick. With this switch on, the
 * screen Instagram reports in its user agent ({@link #screen}) is scaled up so its shorter side is
 * {@link #WIDTH} pixels, and a photo shown across the whole screen is asked for at that width
 * ({@link #wanted}). A phone that's already that wide is left alone, and so are thumbnails.
 *
 * <p>Full size covers reports a screen {@link #COVER_WIDTH} pixels on its shorter side instead, for
 * the reel covers and grid pictures the server sends at a third of the reported width (#214). It
 * changes only the reported screen, not the asked width.
 *
 * <p>Instagram builds its user agent once per run, so the screen it reports changes after a
 * restart. The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram reports and asks for what it would have.
 */
public final class LargerPhotos {
    /** The shorter side of the screen reported and the width asked for, in pixels. */
    static final int WIDTH = 1440;

    /**
     * The shorter side reported under Full size covers. The server sizes a reel cover or grid
     * picture to a third of the reported width (#214: 240 on a 720 screen, 480 on 1440), and reel
     * covers are 1080 wide at most, so three times that gets them whole.
     */
    static final int COVER_WIDTH = 3240;

    /** The step a failure in {@link #wanted} is reported under. */
    static final String ASK = "asked width";

    /** The step a failure in {@link #screen} is reported under. */
    static final String SCREEN = "reported screen";

    /** What {@link #wanted} counts each time it asks for a larger size. */
    static final String ASKED = "asked for a larger size";

    /** The screen's shorter side, once read. */
    private static volatile int shorter;

    private static volatile boolean loggedScreen;
    private static volatile boolean loggedAsk;

    private LargerPhotos() {
    }

    /**
     * Injected at the start of Instagram's size picker, with the width it's asked to pick for.
     * Answers {@link #WIDTH} for a photo shown across a screen narrower than that, while the switch
     * is on, and the width otherwise. Data saver answers first: while it's saving, a photo shown
     * across the screen is asked for at its smaller width instead. Never throws.
     */
    public static int wanted(int width) {
        int shorter = shorterSide();
        int saved = DataSaver.photoWidth(width, shorter);
        if (saved != width) return saved;
        return wanted(width, shorter, LargerPhotos::switchedOn);
    }

    static int wanted(int width, int shorter, BooleanSupplier on) {
        // Thumbnails and anything already that wide pass by without a settings read.
        if (width >= WIDTH || shorter <= 0 || shorter >= WIDTH || width * 10L < shorter * 9L) return width;
        try {
            HookStatus.invoked(FamilyNames.FULL_RESOLUTION);
            if (!on.getAsBoolean()) return width;
            HookStatus.counted(FamilyNames.FULL_RESOLUTION, ASKED);
            if (!loggedAsk) {
                loggedAsk = true;
                Logger.printDebug(() -> "Larger photos: asked for " + WIDTH + " in place of " + width);
            }
            return WIDTH;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_RESOLUTION, ASK, failure);
            return width;
        }
    }

    /**
     * Injected where Instagram's user agent puts the screen in, with the density, width and height
     * it's about to write. While Full size covers is on and the shorter side is under
     * {@link #COVER_WIDTH}, or Ask for larger photos is on and it's under {@link #WIDTH}, scales the
     * width and height so the shorter side is that, keeping the screen's shape. Leaves the parts as
     * they were otherwise. Never throws.
     */
    public static void screen(Object[] parts) {
        screen(parts, LargerPhotos::switchedOn, LargerPhotos::coversOn);
    }

    static void screen(Object[] parts, BooleanSupplier on) {
        screen(parts, on, () -> false);
    }

    static void screen(Object[] parts, BooleanSupplier on, BooleanSupplier covers) {
        try {
            HookStatus.invoked(FamilyNames.FULL_RESOLUTION);
            if (parts == null || parts.length != 3 || !(parts[1] instanceof Integer) || !(parts[2] instanceof Integer)) {
                return;
            }
            int width = (Integer) parts[1];
            int height = (Integer) parts[2];
            int shorter = Math.min(width, height);
            if (shorter <= 0 || shorter >= COVER_WIDTH) return;
            int target = covers.getAsBoolean() ? COVER_WIDTH : shorter < WIDTH && on.getAsBoolean() ? WIDTH : 0;
            if (target == 0) return;
            parts[1] = scaled(width, shorter, target);
            parts[2] = scaled(height, shorter, target);
            if (!loggedScreen) {
                loggedScreen = true;
                Logger.printDebug(() -> "Larger photos: reported the screen as " + parts[1] + "x" + parts[2]
                        + " in place of " + width + "x" + height);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_RESOLUTION, SCREEN, failure);
        }
    }

    private static Integer scaled(int side, int shorter, int target) {
        return (int) ((long) side * target / shorter);
    }

    /** The screen's shorter side in pixels, read once, or 0 when it can't be read. */
    private static int shorterSide() {
        int known = shorter;
        if (known > 0) return known;
        try {
            DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
            known = Math.min(metrics.widthPixels, metrics.heightPixels);
        } catch (Throwable failure) {
            return 0;
        }
        shorter = known;
        return known;
    }

    /** Forgets what was logged. Tests only. */
    static void forgetForTests() {
        loggedScreen = false;
        loggedAsk = false;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.ASK_FOR_LARGER_PHOTOS.get();
    }

    private static boolean coversOn() {
        return Utils.settingsReady() && Settings.FULL_SIZE_COVERS.get();
    }
}
