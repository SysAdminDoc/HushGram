/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.os.Build;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keep HDR on video uploads (#532).
 *
 * <p>Before Instagram uploads a video it encodes it again, and for an HDR source (HLG, or PQ on
 * Android 10 and newer) it first asks for a 10-bit HEVC encoder that keeps HLG. Its encoder search
 * (457 {@code 0KEq.A00}) marks a candidate as keeping HLG only on Android 13 and newer and only when
 * the encoder lists P010 among its input color formats. Many phones' hardware encoders take 10-bit
 * HEVC from a surface, which is how Instagram feeds them, yet don't list P010, so the candidate is
 * marked SDR and the video is encoded with BT.709 colors. The S22's {@code c2.qti.hevc.encoder} is
 * one: it took Instagram's Main10 candidate and wrote SDR.
 *
 * <p>The patch hands each candidate the search marks SDR to {@link #hlg}, with whether HLG was asked
 * for at all. With the switch on, on Android 13 and newer, a candidate asked for HLG is marked as
 * keeping it, and Instagram's own HLG path does the rest: a 10-bit render surface, HLG transfer and
 * BT.2020 colors on the encoder. Everything else the search checks still decides: the codec type,
 * the Main10 profile and level, and whether HLG is asked for, which Instagram decides from the source
 * video and the edits on it.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or anything
 * thrown, the candidate stays SDR as Instagram marked it.
 */
public final class HdrVideos {
    /** What's counted for each encoder candidate marked as keeping HLG for the switch. */
    static final String MARKED = "video encoders marked as keeping HLG";

    /** Android 13, the first where Instagram's search marks an encoder as keeping HLG at all. */
    static final int HLG_SDK = 33;

    private HdrVideos() {
    }

    /**
     * Injected where Instagram's encoder search marks a candidate as not keeping HLG, with whether
     * HLG was asked for (non-zero is yes). Answers 1, keeping HLG, when it was asked for, the switch
     * is on and the phone runs Android 13 or newer, and 0, Instagram's answer, otherwise. Never
     * throws, and never waits for the settings.
     */
    public static int hlg(int wanted) {
        try {
            HookStatus.invoked(FamilyNames.HDR_VIDEOS);
            if (wanted == 0) return 0;
            if (Build.VERSION.SDK_INT < HLG_SDK || !switchOn()) {
                Logger.printInfo(() -> "Keep HDR on video uploads: an encoder for HLG was marked SDR, switch " + switchOn());
                return 0;
            }
            HookStatus.counted(FamilyNames.HDR_VIDEOS, MARKED);
            Logger.printInfo(() -> "Keep HDR on video uploads: an encoder for HLG was marked SDR, marking it HLG");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HDR_VIDEOS, "encoder HLG check", failure);
            return 0;
        }
    }

    private static boolean switchOn() {
        return Utils.settingsReady() && Settings.KEEP_HDR_ON_VIDEO_UPLOADS.get();
    }
}
