/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.view.View;

import java.util.List;
import java.util.Set;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Ghost mode from inside Instagram: a long press on the New message button in the inbox's top bar
 * turns every ghost switch in the build on or off, and a toast says which way it went. The button
 * has no long press of its own, so this takes nothing from Instagram, and a tap still starts a new
 * message.
 *
 * <p>The patch hands {@link #longPress()}'s answer to the button's configuration while the bar is
 * built. It's null unless the build carries a ghost patch, and the listener checks Pause when the
 * press lands, so a paused HushGram does nothing there, as stock Instagram does.
 */
public final class GhostModeEntry {
    private GhostModeEntry() { }

    private static final View.OnLongClickListener PRESS = GhostModeEntry::press;

    /** The listener for the New message button, or null when this build has no ghost patch to turn. */
    public static View.OnLongClickListener longPress() {
        try {
            return offered(PatchFamily.inThisBuild()) ? PRESS : null;
        } catch (Throwable t) {
            Logger.printException(() -> "Ghost mode: could not decide whether to offer the long press", t);
            return null;
        }
    }

    /** Whether any ghost patch is in [build], so there is something for the long press to turn. */
    static boolean offered(Set<PatchFamily> build) {
        return !GhostMode.switches(build).isEmpty();
    }

    /** The press itself: false, so the view acts as stock, whenever HushGram is paused or has nothing to turn. */
    static boolean press(View view) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return false;
            List<BooleanSetting> switches = GhostMode.switches(PatchFamily.inThisBuild());
            if (switches.isEmpty()) return false;
            GhostMode.flip(switches);
            return true;
        } catch (Throwable t) {
            Logger.printException(() -> "Ghost mode: the long press failed", t);
            return false;
        }
    }
}
