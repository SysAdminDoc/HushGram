/*
 * Copyright 2026 HushPinterest contributors
 * Taken from HushPinterest's
 * extensions/shared/library/src/main/java/app/hushpinterest/extension/shared/settings/preference/ReopenSettingsAfterRestart.java
 * (https://github.com/SysAdminDoc/HushPinterest, commit 44fb93384a319d8e5d324f51b416efdc3d3147e6).
 * Modified for HushGram (Instagram), 2026.
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushgram.extension.shared.settings.preference;

import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.settings.Setting;

/**
 * The way back to the settings page Restart now was tapped on.
 *
 * <p>Before Instagram restarts, the page goes into the settings store with the time, as a one-shot
 * marker. When Instagram starts again it takes the marker: the marker is cleared first, so a page
 * that fails to open is never tried again on every start, and only a marker younger than
 * {@link #MAX_AGE_MILLIS} opens the screen. An old one, from a restart that never finished or a
 * phone that was switched off, is dropped. The time is the phone's time since it booted, which a
 * clock change can't move.
 *
 * <p>HushGram's restart is the status card's Restart now, which lives with the app's settings, so
 * the restart itself stays there and only calls {@link #remember(String)} and {@link #forget()}.
 */
public final class ReopenSettingsAfterRestart {
    static final String PAGE_KEY = "hush_reopen_settings_page";
    static final String TIME_KEY = "hush_reopen_settings_at";

    /** How long a marker stays good: a slow phone's cold start fits easily, and a later start doesn't count. */
    public static final long MAX_AGE_MILLIS = 90_000;

    /** What opens the settings screen on a page, at process start. */
    public interface Opener {
        void open(String page);
    }

    private ReopenSettingsAfterRestart() {
    }

    /** Writes the marker for [page] at [now]. Committed, since the process ends right after. */
    public static boolean remember(SharedPreferences store, String page, long now) {
        return store.edit()
                .putString(PAGE_KEY, page == null ? "" : page)
                .putLong(TIME_KEY, now)
                .commit();
    }

    /**
     * The restart's side: writes the marker for [page] in the settings store, now. Never throws:
     * the restart is what was asked for, and landing on Instagram's first screen is the fallback.
     */
    public static boolean remember(String page) {
        try {
            return remember(store(), page, SystemClock.elapsedRealtime());
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not remember the settings page before restarting", ex);
            return false;
        }
    }

    /** Drops the marker, for a restart that didn't happen after all. Never throws. */
    public static void forget() {
        try {
            store().edit().remove(PAGE_KEY).remove(TIME_KEY).commit();
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not drop the settings page marker", ex);
        }
    }

    /**
     * Takes the marker: clears it, then answers its page if it was written less than
     * {@link #MAX_AGE_MILLIS} before [now], and null otherwise. A second call answers null.
     */
    @Nullable
    public static String takeFresh(SharedPreferences store, long now) {
        if (!store.contains(PAGE_KEY) && !store.contains(TIME_KEY)) return null;
        String page;
        long at;
        try {
            page = store.getString(PAGE_KEY, null);
            at = store.getLong(TIME_KEY, -1);
        } catch (ClassCastException wrongType) {
            page = null;
            at = -1;
        }
        // Cleared before anything opens, so a screen that fails to open isn't retried on every start.
        store.edit().remove(PAGE_KEY).remove(TIME_KEY).commit();
        long age = now - at;
        if (page == null || at < 0 || age < 0 || age >= MAX_AGE_MILLIS) return null;
        return page;
    }

    /** At process start: takes the marker and, when it's fresh, hands its page to [opener]. */
    public static boolean reopenIfPending(SharedPreferences store, long now, Opener opener) {
        String page = takeFresh(store, now);
        if (page == null) return false;
        opener.open(page);
        return true;
    }

    /** {@link #reopenIfPending} over the settings store, now. Never throws: a start goes on whatever happens here. */
    public static boolean reopenIfPending(Opener opener) {
        try {
            return reopenIfPending(store(), SystemClock.elapsedRealtime(), opener);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read where settings were before the restart", ex);
            return false;
        }
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }
}
