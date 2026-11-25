/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keep HDR on photo uploads (#532).
 *
 * <p>An Ultra HDR photo is a JPEG with a gain map beside the picture, which tells an HDR screen how
 * much brighter each part may go. Instagram keeps it only on Android 14 and newer, for an Ultra HDR
 * JPEG, and only when one of two server flags is on for the account (457 {@code 0RjC.A00}). With the
 * flags off, the editor reads the photo through Instagram's own JPEG decoder, which has no gain map,
 * and the photo it renders and uploads is plain SDR. With them on, it reads the photo with Android's
 * decoder, renders the gain map beside the picture and saves both with Android's JPEG encoder.
 *
 * <p>The patch hands that check's answer to {@link #ultraHdr} where it returns, so with the switch
 * on the check answers yes on Android 14 and newer. A gain map in color, three channels rather than
 * one, needs a third flag of its own in the renderer, answered by {@link #colorGainmap} the same
 * way. Instagram's own checks that the photo is an Ultra HDR JPEG at all still decide, so an
 * ordinary photo, a HEIC or a phone older than Android 14 goes up as before.
 *
 * <p>{@link #saved} runs after each JPEG the renderer writes with Android's encoder and logs whether
 * the picture had a gain map and whether the file kept it, which is how the switch is checked on a
 * phone without posting anything.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own answers stand.
 */
public final class HdrPhotos {
    /** What's counted for each check answered yes for the switch. */
    static final String ANSWERED = "photo HDR checks answered yes";

    /** What's counted for each photo file written with its gain map. */
    static final String KEPT = "photos saved with their gain map";

    /** Android 14, the first with gain maps in Bitmap and its JPEG encoder. */
    static final int GAINMAP_SDK = 34;

    /** How far the saved file is scaled down to read whether it kept the gain map. Instagram's own check reads it this small. */
    static final int CHECK_SAMPLE = 64;

    private HdrPhotos() {
    }

    /**
     * Injected where Instagram's check for keeping Ultra HDR on photos returns, with its answer
     * (non-zero is yes). Answers yes when the switch is on and the phone runs Android 14 or newer,
     * and Instagram's answer otherwise. Never throws, and never waits for the settings.
     */
    public static int ultraHdr(int instagram) {
        return answer(instagram, "Ultra HDR check");
    }

    /**
     * Injected after the renderer reads its flag for gain maps in color, with that flag (non-zero
     * is yes). Answers like {@link #ultraHdr}.
     */
    public static int colorGainmap(int instagram) {
        return answer(instagram, "color gain map check");
    }

    /**
     * Injected right after the renderer writes a photo's JPEG with Android's encoder, with the
     * picture, the file's path and whether the write went through (non-zero is yes). Logs whether
     * the picture had a gain map and whether the file kept it. Never throws.
     */
    public static void saved(Bitmap picture, String path, int written) {
        try {
            if (Build.VERSION.SDK_INT < GAINMAP_SDK || picture == null) return;
            HookStatus.invoked(FamilyNames.HDR_PHOTOS);
            boolean rendered = picture.hasGainmap();
            boolean kept = rendered && written != 0 && fileHasGainmap(path);
            if (kept) HookStatus.counted(FamilyNames.HDR_PHOTOS, KEPT);
            Logger.printInfo(() -> "Keep HDR on photo uploads: saved a " + picture.getWidth() + "x" + picture.getHeight()
                    + " photo, gain map in the picture " + rendered + ", in the file " + kept
                    + ", switch " + switchOn());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HDR_PHOTOS, "photo save check", failure);
        }
    }

    private static int answer(int instagram, String check) {
        try {
            HookStatus.invoked(FamilyNames.HDR_PHOTOS);
            if (instagram != 0 || Build.VERSION.SDK_INT < GAINMAP_SDK || !switchOn()) {
                Logger.printInfo(() -> "Keep HDR on photo uploads: Instagram's " + check + " answered " + (instagram != 0));
                return instagram;
            }
            HookStatus.counted(FamilyNames.HDR_PHOTOS, ANSWERED);
            Logger.printInfo(() -> "Keep HDR on photo uploads: Instagram's " + check + " answered false, answering true");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HDR_PHOTOS, check, failure);
            return instagram;
        }
    }

    private static boolean switchOn() {
        return Utils.settingsReady() && Settings.KEEP_HDR_ON_PHOTO_UPLOADS.get();
    }

    /** Whether the JPEG at [path] carries a gain map, read at 1/{@link #CHECK_SAMPLE} of its size. */
    static boolean fileHasGainmap(String path) {
        if (path == null || Build.VERSION.SDK_INT < GAINMAP_SDK) return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = CHECK_SAMPLE;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap small = BitmapFactory.decodeFile(path, options);
        if (small == null) return false;
        try {
            return small.hasGainmap();
        } finally {
            small.recycle();
        }
    }
}
