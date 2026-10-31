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
 * Helper for the switches of the "Hide the Threads button" patch that take buttons out of the row
 * of action buttons under a profile's bio: Hide Share profile on your own (#253), and Hide Subscribe
 * and the menu arrow at the row's end on creators' profiles (#248). The buttons left, Edit profile or
 * Following and Message, take the space.
 *
 * <p>Instagram builds each button of the row from a model, and each button's models are one class,
 * whether the row shows it as text or as an icon. The methods building those forms, and the one
 * building the arrow, hand each button here with its model right before giving it back, and the
 * patch writes those classes into
 * {@link #isShare}, {@link #isSubscribe} and {@link #isOverflow}. A button whose switch is on is
 * hidden and the row's other text buttons spread over its space ({@link ActionRow}). Instagram reuses
 * a row's buttons for other models, so a button hidden earlier gets its own visibility back whenever
 * it's built for anything else.
 *
 * <p>Fails open: with the switches off, HushGram paused, the settings not read yet or anything
 * thrown, the button shows as Instagram built it.
 */
public final class ShareProfileButton {
    /** What's counted for each button hidden. */
    static final String HIDDEN = "profile action button hidden";

    /** Where a failure is reported. */
    static final String BUTTON = "profile action button";

    private ShareProfileButton() {
    }

    /**
     * Injected right before Instagram hands back a button it built for a profile's action row, text,
     * icon or the arrow, with the button's model. Hides the button when it's one whose switch is on, and
     * otherwise shows one this hid before. Never throws.
     */
    public static void button(View button, Object model) {
        button(button, () -> hides(model));
    }

    static void button(View button, BooleanSupplier hides) {
        if (button == null) return;
        boolean hide = false;
        try {
            HookStatus.invoked(FamilyNames.THREADS_BUTTON);
            hide = hides.getAsBoolean();
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

    /** Whether [model]'s button goes: it's Share profile's, Subscribe's or the menu arrow's, with that switch on. */
    static boolean hides(Object model) {
        if (!Utils.settingsReady()) return false;
        if (isShare(model)) return Settings.HIDE_SHARE_PROFILE_BUTTON.get();
        if (isSubscribe(model)) return Settings.HIDE_SUBSCRIBE_PROFILE_BUTTON.get();
        return isOverflow(model) && Settings.HIDE_PROFILE_OVERFLOW_BUTTON.get();
    }

    /**
     * Whether [model] is a Share profile button's. A stub: the patch writes its body, which checks
     * the model's class. Answers false unpatched, so nothing is hidden.
     */
    public static boolean isShare(Object model) {
        return false;
    }

    /** Whether [model] is a creator's Subscribe button's. A stub the patch writes, as {@link #isShare} is. */
    public static boolean isSubscribe(Object model) {
        return false;
    }

    /**
     * Whether [model] is the arrow's at the end of the row, which opens a menu of the buttons that
     * didn't fit. A stub the patch writes, as {@link #isShare} is.
     */
    public static boolean isOverflow(Object model) {
        return false;
    }
}
