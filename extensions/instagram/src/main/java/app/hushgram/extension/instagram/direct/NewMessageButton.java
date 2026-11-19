/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.view.View;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.GhostModeEntry;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Takes the New message button off the top bar of your messages (#762).
 *
 * <p>Instagram draws that bar two ways. Each bar's binder hands every button here with what it's
 * bound to: the newer bar its action model, the older one the button's tap listener. The ghost
 * entry patch marks New message's model and listener as Instagram makes them
 * ({@link GhostModeEntry#isNewMessage}), so only that button goes, and a button the bar reuses for
 * another action comes back.
 *
 * <p>Fails open: with the switch off, HushGram paused, the settings not read yet or anything
 * thrown, the button stays as Instagram has it.
 */
public final class NewMessageButton {
    /** What's counted each time the button is left off the bar. */
    static final String HIDDEN_FROM_BAR = "New message buttons hidden";

    /** The buttons this hid, held weakly, so one the bar hands another action shows again. */
    private static final Map<View, Boolean> HIDDEN = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean logged;

    private NewMessageButton() {
    }

    /** Called as either bar binds [button] to [action]. */
    public static void bind(View button, Object action) {
        bind(button, action, NewMessageButton::switchedOn);
    }

    static void bind(View button, Object action, BooleanSupplier on) {
        if (button == null) return;
        try {
            if (GhostModeEntry.isNewMessage(action)) {
                HookStatus.invoked(FamilyNames.NEW_MESSAGE);
                if (on.getAsBoolean()) {
                    button.setVisibility(View.GONE);
                    HIDDEN.put(button, Boolean.TRUE);
                    HookStatus.counted(FamilyNames.NEW_MESSAGE, HIDDEN_FROM_BAR);
                    if (!logged) {
                        logged = true;
                        Logger.printDebug(() -> "New message: left the button off the top bar of your messages");
                    }
                    return;
                }
            }
            if (HIDDEN.remove(button) != null) button.setVisibility(View.VISIBLE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.NEW_MESSAGE, "New message button", failure);
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HIDE_NEW_MESSAGE_BUTTON.get();
    }
}
