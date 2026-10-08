/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.os.Build;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.Window;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Turn off HDR brightness boosts (#26).
 *
 * <p>An HDR photo or reel asks Android for headroom, room to light its highlights above the
 * screen's white, and the screen brightens to give it. Instagram asks in three places: a video's
 * SurfaceView, a SurfaceControl transaction and the window, each with a headroom of its own (1.5 or
 * a value from the server). It also puts some windows in HDR color mode. The patch sends each of
 * those calls here. With the switch on, every headroom asked becomes 1.0, which Android reads as no
 * HDR at all, and HDR color mode becomes the default mode, so nothing outshines the rest of the
 * screen. Off, paused or unready, each call goes through with Instagram's own value.
 *
 * <p>On Android 14 and newer Instagram also draws some videos a frame at a time into an extended
 * range layer of its own (450 {@code LX/0201;->A02}), which shows them brighter than the rest of the
 * screen, and asks Android for three times the screen's white for it (#85). That request's desired
 * ratio is held the same way. The frame's own ratio stays Instagram's: it follows the ratio the
 * screen reports, which stays at 1.0 when no boost is granted.
 */
public final class HdrBoost {
    private HdrBoost() {
    }

    /** The headroom that means no HDR: the content's white is the screen's white. */
    static final float NO_HEADROOM = 1.0f;

    /** Window color modes that light HDR content: HDR, and HDR10 from Android 14. */
    static final int COLOR_MODE_HDR = 2;
    static final int COLOR_MODE_HDR10 = 3;

    /** The window color mode Android uses when nothing asks for more. */
    static final int COLOR_MODE_DEFAULT = 0;

    /** What the diagnostic report counts each time a boost is held back. */
    static final String HELD_BACK = "boosts held back";

    /** Stands in for {@code view.setDesiredHdrHeadroom(headroom)}. */
    public static void surfaceViewHeadroom(SurfaceView view, float headroom) {
        if (Build.VERSION.SDK_INT >= 35) view.setDesiredHdrHeadroom(headroom(headroom));
    }

    /** Stands in for {@code transaction.setDesiredHdrHeadroom(control, headroom)}. */
    public static SurfaceControl.Transaction transactionHeadroom(SurfaceControl.Transaction transaction,
            SurfaceControl control, float headroom) {
        if (Build.VERSION.SDK_INT >= 35) return transaction.setDesiredHdrHeadroom(control, headroom(headroom));
        return transaction;
    }

    /** Stands in for {@code transaction.setExtendedRangeBrightness(control, currentRatio, desiredRatio)}. */
    public static SurfaceControl.Transaction extendedRangeBrightness(SurfaceControl.Transaction transaction,
            SurfaceControl control, float currentRatio, float desiredRatio) {
        if (Build.VERSION.SDK_INT >= 34) {
            return transaction.setExtendedRangeBrightness(control, currentRatio, headroom(desiredRatio));
        }
        return transaction;
    }

    /** Stands in for {@code window.setDesiredHdrHeadroom(headroom)}. */
    public static void windowHeadroom(Window window, float headroom) {
        if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(headroom(headroom));
    }

    /** Stands in for {@code window.setColorMode(mode)}. */
    public static void colorMode(Window window, int mode) {
        window.setColorMode(colorMode(mode));
    }

    /** The headroom to ask for in place of [asked]. */
    static float headroom(float asked) {
        if (!on() || asked == NO_HEADROOM) return asked;
        HookStatus.counted(FamilyNames.HDR_BOOST, HELD_BACK);
        return NO_HEADROOM;
    }

    /** The color mode to ask for in place of [asked]. */
    static int colorMode(int asked) {
        if ((asked != COLOR_MODE_HDR && asked != COLOR_MODE_HDR10) || !on()) return asked;
        HookStatus.counted(FamilyNames.HDR_BOOST, HELD_BACK);
        return COLOR_MODE_DEFAULT;
    }

    private static boolean on() {
        try {
            HookStatus.invoked(FamilyNames.HDR_BOOST);
            return Utils.settingsReady() && Settings.TURN_OFF_HDR_BOOSTS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.HDR_BOOST, "switch", t);
            return false;
        }
    }
}
