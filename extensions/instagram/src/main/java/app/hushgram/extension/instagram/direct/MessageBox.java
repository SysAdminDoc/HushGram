/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Message box buttons" patch (#217): leaves the gallery, sticker and voice message
 * buttons off the message box of a chat, as the user picks.
 *
 * <p>Instagram 452's message box shows or hides each of its buttons through one method of the
 * composer's view holder, which takes the button (an enum: {@code GALLERY}, {@code STICKER},
 * {@code VOICE_MESSAGE} and the rest) and whether to show it. The patch hands that pair to
 * {@link #button} first, and a show for a button the user unticked turns into a hide, so Instagram
 * keeps all its own hiding, spacing and animation. The holder also finds the buttons once when the
 * composer is made, without being asked to show them, so the patch hands the three views to
 * {@link #composerCreated} at the end of its constructor, and an unticked one goes GONE from the start.
 *
 * <p>The text box and Send are never offered and never touched. A button Instagram adds later isn't
 * named here, so it shows. Buttons the patch couldn't tie to a view (the camera, the plus and GIF
 * buttons) aren't offered either.
 *
 * <p>Fails open: with nothing saved, HushGram paused, the settings not read yet or anything thrown,
 * the button shows as Instagram asked.
 */
public final class MessageBox {
    /** The buttons the setting can leave off, as the names of Instagram's button enum, in the order the list shows them. */
    public static final List<String> OFFERED = Collections.unmodifiableList(Arrays.asList(
            "GALLERY", "STICKER", "VOICE_MESSAGE"));

    /** What's counted for each button kept off a chat's message box. */
    static final String LEFT_OFF = "Message box buttons left off";

    private MessageBox() {
    }

    /**
     * Injected at the start of the composer's show-or-hide method. [visible] is 1 to show and 0 to
     * hide. Answers false for a hide, or for a show of a button the setting names, and true for any
     * other show. Never throws, and never waits for the settings.
     */
    public static boolean button(Object button, int visible) {
        if (visible == 0) return false;
        try {
            if (!(button instanceof Enum<?>)) return true;
            String name = ((Enum<?>) button).name();
            if (!OFFERED.contains(name)) return true;
            HookStatus.invoked(FamilyNames.MESSAGE_BOX);
            if (!hidden().contains(name)) return true;
            HookStatus.counted(FamilyNames.MESSAGE_BOX, LEFT_OFF);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSAGE_BOX, "message box button", failure);
            return true;
        }
    }

    /**
     * Injected at the end of the composer's view holder constructor with the gallery, sticker and
     * voice message buttons it found, each null when the layout has none. Sends the ones the setting
     * names to GONE. Never throws.
     */
    public static void composerCreated(View gallery, View sticker, View voice) {
        try {
            HookStatus.invoked(FamilyNames.MESSAGE_BOX);
            Set<String> hidden = hidden();
            if (hidden.isEmpty()) return;
            leaveOff(gallery, hidden.contains("GALLERY"));
            leaveOff(sticker, hidden.contains("STICKER"));
            leaveOff(voice, hidden.contains("VOICE_MESSAGE"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MESSAGE_BOX, "message box buttons", failure);
        }
    }

    private static void leaveOff(@Nullable View view, boolean hide) {
        if (view == null || !hide) return;
        view.setVisibility(View.GONE);
        HookStatus.counted(FamilyNames.MESSAGE_BOX, LEFT_OFF);
    }

    /** The buttons the setting names now, or none while HushGram is paused or the settings aren't read. */
    private static Set<String> hidden() {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return Collections.emptySet();
        return parse(Settings.HIDDEN_MESSAGE_BOX_BUTTONS.get());
    }

    /** The offered names in [saved], without blanks, repeats or anything this doesn't offer. */
    public static Set<String> parse(@Nullable String saved) {
        Set<String> names = new LinkedHashSet<>();
        if (saved == null) return names;
        for (String part : saved.split(",")) {
            String name = part.trim();
            if (OFFERED.contains(name)) names.add(name);
        }
        return names;
    }

    /** [hidden] as the saved value, in the order the buttons are offered. */
    public static String join(Set<String> hidden) {
        List<String> names = new ArrayList<>();
        for (String name : OFFERED) {
            if (hidden.contains(name)) names.add(name);
        }
        return String.join(",", names);
    }
}
