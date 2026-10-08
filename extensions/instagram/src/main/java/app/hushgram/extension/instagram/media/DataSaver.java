/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.Nullable;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Data saver" patch.
 *
 * <p>Data saver has no hooks of its own. It rides on two that other patches put in, which is why
 * it brings them along: Full resolution photos' hook at the start of Instagram's size picker, where
 * {@link #photoWidth} answers a smaller width for a photo shown across the screen, and Default
 * playback quality's hook in the video track choice, where {@link QualityChoice} plays the lowest
 * quality while {@link #saving} is true. Full resolution photos also stops swapping in the largest
 * size while it's true.
 *
 * <p>With the second switch on, which is how it starts, it saves only on mobile data. The network
 * is read at most every {@link #NETWORK_FRESH_MILLIS} so a feed full of photos doesn't ask for each
 * one. Anything that can't be read, with the switch off, HushGram paused or the settings not read
 * yet, means not saving, and Instagram loads what it would have.
 */
public final class DataSaver {
    /** The width a photo shown across the screen is asked for while saving, in pixels. */
    static final int PHOTO_WIDTH = 640;

    /** How long a read of the network is trusted, in milliseconds. */
    static final long NETWORK_FRESH_MILLIS = 5_000;

    /** What's counted for each photo asked for at {@link #PHOTO_WIDTH}. */
    static final String SMALLER_PHOTO = "asked for a smaller photo";

    /** What's counted for each video started at the lowest quality. */
    static final String LOWEST_VIDEO = "started a video at the lowest quality";

    /** The step a failure in {@link #photoWidth} is reported under. */
    static final String PHOTO = "photo width";

    /** The step a failure reading the switches or the network is reported under. */
    static final String CHECK = "data saver check";

    /** Whether the patch is in this build, when a test says so instead of {@link SettingsStatus}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    /** Whether the phone is on mobile data, when a test says so instead of the network. */
    @Nullable
    static volatile BooleanSupplier mobileDataForTests;

    private static volatile long checkedAt;
    private static volatile boolean onMobileData;

    private DataSaver() {
    }

    static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : SettingsStatus.dataSaver();
    }

    /**
     * Whether photos and videos should load small right now: the patch is in, the switch is on and,
     * when the second switch says so, the phone is on mobile data. Counts nothing. Never throws.
     */
    public static boolean saving() {
        try {
            if (!inBuild() || !Utils.settingsReady() || !Settings.DATA_SAVER.get()) return false;
            return !Settings.DATA_SAVER_MOBILE_DATA_ONLY.get() || mobileData();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DATA_SAVER, CHECK, failure);
            return false;
        }
    }

    /**
     * Called from Full resolution photos' hook at the start of the size picker, with the width it's
     * asked to pick for and the screen's shorter side. Answers {@link #PHOTO_WIDTH} for a photo
     * shown across the screen while {@link #saving}, and the width otherwise. Thumbnails and photos
     * already that small pass by. Never throws.
     */
    public static int photoWidth(int width, int shorter) {
        return photoWidth(width, shorter, DataSaver::saving);
    }

    static int photoWidth(int width, int shorter, BooleanSupplier saving) {
        if (width <= PHOTO_WIDTH || shorter <= 0 || width * 10L < shorter * 9L) return width;
        try {
            HookStatus.invoked(FamilyNames.DATA_SAVER);
            if (!saving.getAsBoolean()) return width;
            HookStatus.counted(FamilyNames.DATA_SAVER, SMALLER_PHOTO);
            return PHOTO_WIDTH;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DATA_SAVER, PHOTO, failure);
            return width;
        }
    }

    /** Called by {@link QualityChoice} after it starts a video at the lowest quality for this. */
    static void countVideo() {
        HookStatus.invoked(FamilyNames.DATA_SAVER);
        HookStatus.counted(FamilyNames.DATA_SAVER, LOWEST_VIDEO);
    }

    /** Whether the active network is mobile data, read at most every {@link #NETWORK_FRESH_MILLIS}. */
    private static boolean mobileData() {
        BooleanSupplier forced = mobileDataForTests;
        if (forced != null) return forced.getAsBoolean();
        long now = System.currentTimeMillis();
        long last = checkedAt;
        if (last != 0 && now - last >= 0 && now - last < NETWORK_FRESH_MILLIS) return onMobileData;
        boolean mobile = readMobileData(Utils.getContext());
        onMobileData = mobile;
        checkedAt = now;
        return mobile;
    }

    /** Whether the active network carries cellular. Instagram holds ACCESS_NETWORK_STATE. */
    static boolean readMobileData(@Nullable Context context) {
        if (context == null) return false;
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    /** Forgets the last network read. Tests only. */
    static void forgetForTests() {
        checkedAt = 0;
        onMobileData = false;
    }
}
