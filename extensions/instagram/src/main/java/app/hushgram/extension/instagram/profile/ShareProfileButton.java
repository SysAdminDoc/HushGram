/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.view.View;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for Hide Share profile, a switch of the "Hide the Threads button" patch: takes the Share
 * profile button out of the row of action buttons on your profile, and the buttons left, Edit
 * profile among them, take its space (#253).
 *
 * <p>Instagram builds each button of the row from a model, and Share profile's models are one class,
 * whether the row shows it as text or as an icon. The two methods building those forms hand each
 * button here with its model right before giving it back, and the patch writes that class into
 * {@link #isShare}. With the switch on, the button is hidden and the row's other text buttons spread
 * over its space ({@link ActionRow}). Instagram reuses a row's buttons for other models, so a button
 * hidden earlier gets its own visibility back whenever it's built for anything else.
 *
 * <p>Fails open: with the switch off, HushGram paused, the settings not read yet or anything thrown,
 * the button shows as Instagram built it.
 */
public final class ShareProfileButton {
    /** What's counted for each Share profile button hidden. */
    static final String HIDDEN = "Share profile button hidden";

    /** Where a failure is reported. */
    static final String BUTTON = "profile Share profile button";

    private ShareProfileButton() {
    }

    /**
     * Injected right before Instagram hands back a button it built for a profile's action row, text
     * or icon, with the button's model. Hides the button when it's Share profile's and the switch is
     * on, and otherwise shows one this hid before. Never throws.
     */
    public static void button(View button, Object model) {
        button(button, isShare(model), ShareProfileButton::switchedOn);
    }

    static void button(View button, boolean share, BooleanSupplier on) {
        if (button == null) return;
        boolean hide = false;
        try {
            HookStatus.invoked(FamilyNames.THREADS_BUTTON);
            hide = share && on.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.THREADS_BUTTON, BUTTON, failure);
        }
        try {
            ActionRow.hide(button, hide, FamilyNames.THREADS_BUTTON);
            if (hide) HookStatus.counted(FamilyNames.THREADS_BUTTON, HIDDEN);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.THREADS_BUTTON, BUTTON, failure);
        }
    }

    /**
     * Whether [model] is a Share profile button's. A stub: the patch writes its body, which checks
     * the model's class. Answers false unpatched, so nothing is hidden.
     */
    public static boolean isShare(Object model) {
        return false;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_SHARE_PROFILE_BUTTON.get();
    }
}
