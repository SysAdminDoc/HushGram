/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;

import androidx.annotation.Nullable;

import app.hushgram.extension.shared.Utils;

/**
 * Material You's shades for the "Accent color" patch (#187, #264): {@link AccentHue#WALLPAPER} takes
 * Android 12's wallpaper palette, its first accent family, in place of a fixed color.
 *
 * <p>Each part an accent blue plays takes the tone nearest the blue's own: tone 50 fills a button
 * (white text on it reads at about 4.5 to 1, better than on Instagram's blue), tone 40 is the fill
 * held down and the link on a light screen, tone 20 the dim fill on a dark one, tone 80 the link on a
 * dark screen and tone 95 the pale pill. The patch's theme styles point at the same system colors, so
 * both halves agree.
 *
 * <p>The tones are read again each time an activity's theme gets the accent, so a new wallpaper shows
 * once Instagram opens a screen after a restart, as every color change does. Before Android 12 there
 * is no palette, and the wallpaper choice leaves Instagram's blue.
 */
public final class MaterialYou {
    /** The system color each part takes, in {@link AccentColor.Role}'s order after NONE. */
    @TargetApi(Build.VERSION_CODES.S)
    private static final class Tones {
        static final int[] COLORS = {
                android.R.color.system_accent1_500, // fill
                android.R.color.system_accent1_600, // pressed
                android.R.color.system_accent1_800, // dim
                android.R.color.system_accent1_600, // link on a light screen
                android.R.color.system_accent1_200, // link on a dark screen
                android.R.color.system_accent1_50, // pale
        };
    }

    /** The shades last read, without alpha, or null before the first read. */
    @Nullable
    private static volatile int[] shades;

    private MaterialYou() {
    }

    /** Whether this Android has a wallpaper palette. */
    public static boolean available() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * The wallpaper's shade for each part, in {@link AccentColor.Role}'s order after NONE, or null before
     * Android 12 or when they can't be read. The first ask reads them from the app.
     */
    @Nullable
    static int[] shades() {
        int[] known = shades;
        if (known != null || !available()) return known;
        return read(Utils.getContext());
    }

    /** Reads the shades from [context]'s resources and keeps them. Null when they can't be read. */
    @Nullable
    static int[] read(@Nullable Context context) {
        if (context == null || !available()) return null;
        int[] read = new int[Tones.COLORS.length];
        for (int i = 0; i < read.length; i++) read[i] = context.getColor(Tones.COLORS[i]) & 0xffffff;
        shades = read;
        return read;
    }

    /** Forgets the shades read, for tests. */
    static void reset() {
        shades = null;
    }
}
