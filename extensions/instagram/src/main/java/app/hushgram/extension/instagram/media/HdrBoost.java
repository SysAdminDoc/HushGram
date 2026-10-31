/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.os.Build;
import android.view.Display;
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
 * ratio is held the same way.
 *
 * <p>The frame itself is drawn for the HDR/SDR ratio the screen reports, the headroom it has to
 * spare, which runs near five at low brightness, and that ratio goes to Android as the frame's own,
 * so the frame came out bright even with the desired ratio held. The same reading decides whether
 * Instagram's AV1 decoder keeps its synthetic HDR on ordinary reels: it backs off only when the
 * screen has less headroom than it needs. With the switch on, every reading of the ratio Instagram
 * makes answers 1.0, a screen with no headroom, so frames are drawn at the screen's white and the
 * synthetic HDR backs off (#85).
 *
 * <p>Instagram also asks whether the screen shows HDR at all before it picks what to play: whether
 * the display is HDR, and which HDR types it and its current mode support. Those answers decide
 * whether the AV1 decoder's synthetic HDR is allowed, whether Dolby Vision streams and decoders are
 * picked, and what the video and image requests say the screen can show. With the switch on, each
 * of those reads answers no HDR, so Instagram chooses SDR. The HDR recording helper the camera uses
 * keeps the real answers. The layer above is also told its frames are plain sRGB rather than
 * extended range sRGB, so Android doesn't treat it as an HDR layer (#85).
 *
 * <p>Instagram's own server options light HDR content on top of all that: "SDR super white on HDR
 * content", which gives ordinary reels a white brighter than the screen's, the option 452 and later
 * read for that super white instead, and "Ultra HDR always on". With the switch on, Instagram's
 * boolean read of each answers no (#85).
 *
 * <p>452 and later give a reel that super white only on a screen that reports an HDR/SDR ratio, so
 * a phone like the Galaxy S25 FE lifts and one that reports none never does. With the switch on,
 * that check answers no as well (#85).
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

    /** What the diagnostic report counts each time a check of the screen's HDR support answers none. */
    static final String SCREEN_CHECKS = "HDR screen checks answered no";

    /** What the diagnostic report counts each time an extended range layer is sent as plain sRGB. */
    static final String SRGB_LAYERS = "HDR layers sent as sRGB";

    /** What the diagnostic report counts each time one of Instagram's own HDR options is read as off. */
    static final String OPTIONS_OFF = "Instagram HDR options read as off";

    /** A dataspace's range bits, and the full and extended ranges (Android's DataSpace.RANGE_*). */
    static final int RANGE_MASK = 7 << 27;
    static final int RANGE_FULL = 1 << 27;
    static final int RANGE_EXTENDED = 3 << 27;

    /** The HDR types of a screen that shows none. */
    private static final int[] NO_HDR_TYPES = new int[0];

    /** An empty slot of the held option indexes. Instagram's option table never runs this long. */
    static final int NO_OPTION = 0xffff;

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

    /** Stands in for {@code display.getHdrSdrRatio()}. */
    public static float hdrSdrRatio(Display display) {
        return screenRatio(Build.VERSION.SDK_INT >= 34 ? display.getHdrSdrRatio() : NO_HEADROOM);
    }

    /** Stands in for {@code display.isHdr()}. */
    public static boolean isHdr(Display display) {
        return screenIsHdr(display.isHdr());
    }

    /** Stands in for {@code display.isHdrSdrRatioAvailable()}, which Instagram reads from Android 14 on. */
    public static boolean hdrSdrRatioAvailable(Display display) {
        return screenIsHdr(Build.VERSION.SDK_INT >= 34 && display.isHdrSdrRatioAvailable());
    }

    /** Stands in for {@code capabilities.getSupportedHdrTypes()}. */
    @SuppressWarnings("deprecation")
    public static int[] hdrTypes(Display.HdrCapabilities capabilities) {
        return screenHdrTypes(capabilities.getSupportedHdrTypes());
    }

    /** Stands in for {@code mode.getSupportedHdrTypes()}, which Instagram reads from Android 14 on. */
    public static int[] modeHdrTypes(Display.Mode mode) {
        return screenHdrTypes(Build.VERSION.SDK_INT >= 34 ? mode.getSupportedHdrTypes() : NO_HDR_TYPES);
    }

    /** Stands in for {@code transaction.setDataSpace(control, dataSpace)}. */
    public static SurfaceControl.Transaction dataSpace(SurfaceControl.Transaction transaction,
            SurfaceControl control, int dataSpace) {
        if (Build.VERSION.SDK_INT >= 33) return transaction.setDataSpace(control, layerDataSpace(dataSpace));
        return transaction;
    }

    /**
     * Whether Instagram's boolean read of the option [specifier] names answers no in place of its
     * own (#85). The specifier's bits 32 to 47 are the option's index in Instagram's option table, and
     * [held] packs the indexes the patch found for the HDR options, 16 bits each from the low end,
     * with {@link #NO_OPTION} in a slot it didn't fill.
     */
    public static boolean optionOff(long specifier, long held) {
        int option = (int) ((specifier >>> 32) & 0xffff);
        if (!holds(held, option) || !on()) return false;
        HookStatus.counted(FamilyNames.HDR_BOOST, OPTIONS_OFF);
        return true;
    }

    /** Whether [held], four 16-bit option indexes, lists [option]. */
    static boolean holds(long held, int option) {
        if (option == NO_OPTION) return false;
        for (int shift = 0; shift < 64; shift += 16) {
            if ((int) ((held >>> shift) & 0xffff) == option) return true;
        }
        return false;
    }

    /** Whether to say the screen is HDR in place of [reported], the screen's answer. */
    static boolean screenIsHdr(boolean reported) {
        if (!reported || !on()) return reported;
        HookStatus.counted(FamilyNames.HDR_BOOST, SCREEN_CHECKS);
        return false;
    }

    /** The HDR types to report in place of [reported], the screen's. */
    static int[] screenHdrTypes(int[] reported) {
        if (reported == null || reported.length == 0 || !on()) return reported;
        HookStatus.counted(FamilyNames.HDR_BOOST, SCREEN_CHECKS);
        return NO_HDR_TYPES.clone();
    }

    /** The dataspace to give a layer in place of [asked]: an extended range one becomes full range. */
    static int layerDataSpace(int asked) {
        if ((asked & RANGE_MASK) != RANGE_EXTENDED || !on()) return asked;
        HookStatus.counted(FamilyNames.HDR_BOOST, SRGB_LAYERS);
        return (asked & ~RANGE_MASK) | RANGE_FULL;
    }

    /** The HDR/SDR ratio to report in place of [reported], the screen's. */
    static float screenRatio(float reported) {
        if (!(reported > NO_HEADROOM) || !on()) return reported;
        HookStatus.counted(FamilyNames.HDR_BOOST, HELD_BACK);
        return NO_HEADROOM;
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
