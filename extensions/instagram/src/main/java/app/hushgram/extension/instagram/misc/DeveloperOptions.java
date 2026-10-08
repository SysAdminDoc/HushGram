/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Open developer options" patch.
 *
 * <p>A long press on the Home tab asks {@link #open} first. While the switch is on, the patch opens
 * Instagram's own developer options instead, through the opener its settings link and debug button
 * use. Those options hold Instagram's server flag screens (MetaConfig and quick experiments), where
 * a flag can be looked at and overridden on this phone.
 */
public final class DeveloperOptions {
    private DeveloperOptions() {
    }

    /** A deliberate settings action; it doesn't enable the Home long-press switch or any flag. */
    public static boolean openOverrides(Activity activity) {
        if (!canHost(activity)) return false;
        try {
            HookStatus.invoked(FamilyNames.DEVELOPER_OPTIONS);
            return openOverridesNative(activity) == 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "override editor", failure);
            return false;
        }
    }

    /**
     * A deliberate settings action that opens Instagram's own Whitehat settings screen. Nothing
     * changes until the switch on that screen is turned on there, and the trust it gives the
     * phone's installed certificates lasts the 24 hours Instagram gives it.
     */
    public static boolean openWhitehat(Activity activity) {
        if (!canHost(activity)) return false;
        try {
            HookStatus.invoked(FamilyNames.DEVELOPER_OPTIONS);
            return openWhitehatNative(activity) == 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "whitehat settings", failure);
            return false;
        }
    }

    /** A host that can still take a new screen. */
    private static boolean canHost(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && !activity.getFragmentManager().isStateSaved();
    }

    /** Filled by the patch with verified host/session checks and Instagram's native navigation. */
    static int openOverridesNative(Object activity) {
        return 0;
    }

    /** Filled by the patch with the same checks and navigation, to Instagram's Whitehat screen. */
    static int openWhitehatNative(Object activity) {
        return 0;
    }

    /** Read-only boundaries filled from the current host's signed-in manager and typed schema. */
    static Object getOverrideStoreNative(Object activity) { return null; }
    static java.io.File getOverrideFileNative(Object manager) { return null; }
    static java.util.List<?> getOverrideSchemaNative(Object manager) { return null; }
    static OverrideExchange.Parameter getOverrideParameterNative(Object parameter) { return null; }

    /**
     * Typed writer boundaries filled from Instagram's own override editor. The table is the signed-in
     * manager's native table or null. Each setter answers 1 only after the typed native call it
     * makes, and 0 while unfilled or for anything that isn't the native table. Nothing here reaches
     * a string import, a whole-table wipe or a reload.
     */
    static Object getOverrideTableNative(Object manager) { return null; }
    static int setOverrideBooleanNative(Object table, long id, int value) { return 0; }
    static int setOverrideLongNative(Object table, long id, long value) { return 0; }
    static int setOverrideDoubleNative(Object table, long id, double value) { return 0; }
    static int setOverrideStringNative(Object table, long id, String value) { return 0; }
    static int removeOverrideNative(Object table, long id) { return 0; }
    /** The value type Instagram's own decoder reads from a parameter ID, or 0 while unfilled. */
    static int getOverrideTypeNative(long id) { return 0; }

    /**
     * Injected first thing in the Home tab's long press. Answers 1 while the switch is on, and the
     * patch opens the developer options and ends the press. Otherwise 0, and the long press does
     * what it did. Never throws, and never waits for the settings: before they're ready it's 0.
     */
    public static int open() {
        try {
            HookStatus.invoked(FamilyNames.DEVELOPER_OPTIONS);
            if (!Utils.settingsReady() || !Settings.OPEN_DEVELOPER_OPTIONS.get()) return 0;
            Logger.printDebug(() -> "Developer options: opened from a long press of Home");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DEVELOPER_OPTIONS, "long press", failure);
            return 0;
        }
    }
}
