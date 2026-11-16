/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Phone layout on big screens" patch.
 *
 * <p>Instagram sorts every window by its width into compact (under 600 dp), medium and expanded,
 * the way Android's window size classes do, and a window that isn't compact gets its big screen
 * layout: the tab bar moves to a rail down the side and the feed sits in a narrow column. On a
 * foldable it also checks for a hinge sensor and treats the phone as a foldable from then on. The
 * patch hands the width to {@link #width} first thing in the sorting, and the hinge check's answer
 * to {@link #foldable}, so while the switch is on every window sorts as compact and the phone as no
 * foldable, and Instagram keeps its phone layout across the whole screen. Most builds have two copies
 * of the sorting, the width class factory and the big screen helper's own "isn't this compact" check,
 * and {@link #width} is first thing in both: the main activity and the rail ask the second (#515).
 *
 * <p>Instagram also picks each screen's orientation from that sorting: a phone stays in portrait and a
 * big screen turns with the device. With every window sorted as compact a tablet stayed in portrait
 * (#700, #714), so where the sorting picks an orientation its answer goes to {@link #rotates}, which
 * gives back what the screen's own size sorts as.
 *
 * <p>Every hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answer stands.
 */
public final class PhoneLayout {
    /** The widest window, in dp, Instagram still sorts as compact. */
    static final int PHONE_WIDTH = 599;

    /** The lowest window, in dp, Instagram sorts as tall enough for a big screen. */
    static final int BIG_HEIGHT = 480;

    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    /** The step a failed read of the screen's size is reported under. */
    static final String SCREEN = "screen read";

    /** The feature Instagram checks for to decide the phone is a foldable. */
    static final String HINGE_FEATURE = "android.hardware.sensor.hinge_angle";

    /** Whether the phone has {@link #HINGE_FEATURE}, once read. */
    private static volatile Boolean hinge;

    private static volatile boolean loggedWidth;
    private static volatile boolean loggedFoldable;

    private PhoneLayout() {
    }

    /**
     * Asked with the window width in dp Instagram is about to sort. At most {@link #PHONE_WIDTH}
     * while the switch is on, so the window sorts as compact. The width itself while the switch is
     * off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static int width(int dp) {
        return width(dp, PhoneLayout::switchedOn);
    }

    static int width(int dp, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.PHONE_LAYOUT);
            if (dp <= PHONE_WIDTH || !on.getAsBoolean()) return dp;
            if (!loggedWidth) {
                loggedWidth = true;
                Logger.printDebug(() -> "Phone layout: a " + dp + " dp window sorted as compact");
            }
            return PHONE_WIDTH;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PHONE_LAYOUT, SWITCH, t);
            return dp;
        }
    }

    /**
     * Asked with whether the phone has a hinge sensor (non-zero for yes), as Instagram checks it to
     * decide the phone is a foldable. False while the switch is on. Instagram's own answer while the
     * switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean foldable(int instagram) {
        return foldable(instagram, PhoneLayout::switchedOn);
    }

    static boolean foldable(int instagram, BooleanSupplier on) {
        boolean read = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.PHONE_LAYOUT);
            if (!read || !on.getAsBoolean()) return read;
            if (!loggedFoldable) {
                loggedFoldable = true;
                Logger.printDebug(() -> "Phone layout: the hinge sensor left out, so no foldable layout");
            }
            return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PHONE_LAYOUT, SWITCH, t);
            return read;
        }
    }

    /**
     * Asked with whether Instagram sorts the screen as big (non-zero for yes), where that answer picks the
     * orientation: no keeps the screen in portrait, yes lets it turn with the device. While the switch is on
     * Instagram's answer is always no, so the screen's own size answers instead, sorted the way Instagram
     * sorts it without the switch: a tablet turns, a phone stays in portrait. Instagram's own answer while
     * the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static boolean rotates(int instagram) {
        return rotates(instagram, PhoneLayout::switchedOn, PhoneLayout::screenIsBig);
    }

    static boolean rotates(int instagram, BooleanSupplier on, BooleanSupplier big) {
        boolean read = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.PHONE_LAYOUT);
            if (read || !on.getAsBoolean()) return read;
            return big.getAsBoolean();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PHONE_LAYOUT, SCREEN, t);
            return read;
        }
    }

    /**
     * Instagram's own test of a big screen, for a screen {@code widthPx} by {@code heightPx} pixels at
     * {@code density}: its width past compact and its height at least {@link #BIG_HEIGHT}, each in dp
     * rounded up.
     */
    static boolean big(float density, int widthPx, int heightPx) {
        if (!(density > 0)) return false;
        int width = (int) Math.ceil(widthPx / density);
        int height = (int) Math.ceil(heightPx / density);
        return width > PHONE_WIDTH && height >= BIG_HEIGHT;
    }

    /** The whole display as it's turned now, the way Instagram measures the largest window it could get. */
    private static boolean screenIsBig() {
        DisplayMetrics display = Resources.getSystem().getDisplayMetrics();
        return big(display.density, display.widthPixels, display.heightPixels);
    }

    /**
     * The switch, or the Glass tab bar on a tablet: the glass is drawn on the tab bar along the bottom, and
     * the big screen layout has only the rail down the side, so with the rail the glass never showed on a
     * tablet (#582). A foldable keeps Instagram's big screen layout opened up, glass or not, since that's what
     * it's unfolded for and the stretched phone layout looked broken there (#695). The switch still keeps the
     * phone layout on a foldable too.
     */
    private static boolean switchedOn() {
        if (!Utils.settingsReady()) return false;
        if (Settings.PHONE_LAYOUT.get()) return true;
        return Settings.GLASS_TAB_BAR.get() && !hinged();
    }

    /** Whether the phone has a hinge sensor, the way Instagram itself tells a foldable. Read once. */
    private static boolean hinged() {
        Boolean known = hinge;
        if (known != null) return known;
        Context context = Utils.getContext();
        if (context == null) return false;
        boolean read = context.getPackageManager().hasSystemFeature(HINGE_FEATURE);
        hinge = read;
        return read;
    }

    /** Lets a test give the phone a hinge, or take it away, after the first read. */
    static void forgetHingeForTests() {
        hinge = null;
    }
}
