/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Ghost mode from inside Instagram: a long press on the New message button in the inbox's top bar
 * turns every ghost switch in the build on or off, and a toast says which way it went. The button
 * has no long press of its own, so this takes nothing from Instagram, and a tap still starts a new
 * message.
 *
 * <p>Instagram 450 draws that bar two ways, picked by a server flag. The older bar is built from a
 * button configuration, and the patch hands {@link #longPress()}'s answer to its long press field.
 * The newer one (IgdsActionBar) is built from action models with no long press of their own, so the
 * patch marks the New message model as Instagram makes it ({@link #newMessageAction}) and the bar's
 * binder hands each button here with its model ({@link #bindAction}).
 *
 * <p>The listener is null unless the build carries a ghost patch, and it checks Pause when the
 * press lands, so a paused HushGram does nothing there, as stock Instagram does.
 */
public final class GhostModeEntry {
    private GhostModeEntry() { }

    /** The name the diagnostic report counts these hooks under. */
    static final String ROUTE = "Ghost mode entry";
    static final String GIVEN = "New message long press given";
    static final String LEFT_OUT = "New message long press left out, no ghost patch";

    private static final View.OnLongClickListener PRESS = GhostModeEntry::press;

    /** Whether longPress() has logged its first answer. */
    private static final AtomicBoolean ANSWER_LOGGED = new AtomicBoolean();

    /** The newer bar's New message models Instagram made, held weakly so a dropped bar lets them go. */
    private static final Map<Object, Boolean> NEW_MESSAGE = Collections.synchronizedMap(new WeakHashMap<>());

    /** The newer bar's buttons given the long press, so a button the bar reuses for another action loses it. */
    private static final Map<View, Boolean> GIVEN_TO = Collections.synchronizedMap(new WeakHashMap<>());

    /** The listener for the New message button, or null when this build has no ghost patch to turn. */
    public static View.OnLongClickListener longPress() {
        try {
            View.OnLongClickListener answer = offered(PatchFamily.inThisBuild()) ? PRESS : null;
            if (ANSWER_LOGGED.compareAndSet(false, true)) {
                Logger.printDebug(() -> answer != null
                        ? "Ghost mode: the New message button gets the long press"
                        : "Ghost mode: no ghost patch in this build, so New message gets no long press");
            }
            return answer;
        } catch (Throwable t) {
            HookStatus.threw(ROUTE, "long press", t);
            Logger.printException(() -> "Ghost mode: could not decide whether to offer the long press", t);
            return null;
        }
    }

    /** Called with each New message model the newer bar's maker answers. Null, when it has none, is left alone. */
    public static void newMessageAction(Object action) {
        try {
            if (action != null) NEW_MESSAGE.put(action, Boolean.TRUE);
        } catch (Throwable t) {
            HookStatus.threw(ROUTE, "New message model", t);
        }
    }

    /**
     * Called first thing as the newer bar binds [button] to [action]. The New message button gets
     * the long press. A button this gave it before and the bar now hands another action loses it,
     * and every other button is left as Instagram has it.
     */
    public static void bindAction(View button, Object action) {
        try {
            if (button == null) return;
            if (action != null && NEW_MESSAGE.containsKey(action)) {
                View.OnLongClickListener press = longPress();
                HookStatus.counted(ROUTE, press != null ? GIVEN : LEFT_OUT);
                if (press != null) {
                    button.setOnLongClickListener(press);
                    GIVEN_TO.put(button, Boolean.TRUE);
                    return;
                }
            }
            if (GIVEN_TO.remove(button) != null) {
                button.setOnLongClickListener(null);
                button.setLongClickable(false);
            }
        } catch (Throwable t) {
            HookStatus.threw(ROUTE, "New message button", t);
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
            HookStatus.threw(ROUTE, "long press", t);
            Logger.printException(() -> "Ghost mode: the long press failed", t);
            return false;
        }
    }
}
