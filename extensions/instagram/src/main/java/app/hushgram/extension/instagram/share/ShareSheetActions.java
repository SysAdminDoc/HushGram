/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * The Buttons on the share sheet choice (#252): which of the round buttons under the people the
 * share sheet shows.
 *
 * <p>The sheet asks one method for the names of the buttons it should show, in order, and builds the
 * row from that list, one button per name it knows. The patch hands the list here the moment it
 * comes back, and gets a copy without the names the user unticked. The row is built from what is
 * left, so a hidden button leaves no gap and the row scrolls as it always did. The names are
 * Instagram's own action names, not its labels, so they don't change with the phone's language.
 * The list Instagram returns is sometimes one it keeps, so it's never changed, only copied.
 *
 * <p>Nothing unticked, HushGram paused, the settings not read yet, or anything unexpected, and the
 * list goes through as it came.
 */
public final class ShareSheetActions {
    /**
     * The buttons the choice can hide, by the name the share sheet gives each, in the order the
     * settings list shows them. A name not here is never hidden.
     */
    public static final List<String> ACTIONS = Collections.unmodifiableList(Arrays.asList(
            "add_to_your_story", "system_share_sheet", "whatsapp", "copy_link", "download_media", "user_sms",
            "messenger", "facebook", "snapchat", "twitter", "barcelona", "add_to_channel", "save", "qr_code",
            "whatsapp_status", "reply_to_author", "watch_on_tv", "profile_card_download", "line", "kakaotalk",
            "discord", "band"));

    /**
     * Names a button goes by on some sheets, and the choice in [ACTIONS] that hides it. A reel the
     * poster lets people download gets its Download as clips_download, on a reel sent in a chat too,
     * and download_media elsewhere. Both are Download in the list.
     */
    private static final Map<String, String> ALSO_CALLED = Collections.singletonMap("clips_download", "download_media");

    /** What a row with buttons taken off is counted under. */
    static final String APPLIED = "Share sheet buttons hidden";

    private static volatile boolean logged;

    private ShareSheetActions() {
    }

    /**
     * [names], the share sheet's button names, without the ones the user hid, as a copy, or [names]
     * itself when none of them is hidden. Never throws.
     */
    public static List<?> filter(@Nullable List<?> names) {
        try {
            HookStatus.invoked(FamilyNames.SHARE_SHEET);
            if (names == null || names.isEmpty() || !Utils.settingsReady() || HushgramPause.isPaused()) return names;
            Set<String> hidden = hidden(Settings.HIDDEN_SHARE_SHEET_BUTTONS.get());
            if (hidden.isEmpty()) return names;
            List<Object> shown = new ArrayList<>(names.size());
            for (Object name : names) {
                if (!(name instanceof String) || !hidden.contains(choiceOf((String) name))) shown.add(name);
            }
            if (shown.size() == names.size()) return names;
            HookStatus.counted(FamilyNames.SHARE_SHEET, APPLIED);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Share sheet: left out " + (names.size() - shown.size()) + " button(s)");
            }
            return shown;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, "buttons", failure);
            return names;
        }
    }

    /** The name in [ACTIONS] whose tick decides the button the sheet calls [name]. */
    static String choiceOf(String name) {
        String choice = ALSO_CALLED.get(name);
        return choice != null ? choice : name;
    }

    /** The button names in [saved], without blanks, repeats or names the choice doesn't know. */
    public static Set<String> hidden(@Nullable String saved) {
        Set<String> names = new LinkedHashSet<>();
        if (saved == null || saved.trim().isEmpty()) return names;
        for (String part : saved.split(",")) {
            String name = part.trim();
            if (ACTIONS.contains(name)) names.add(name);
        }
        return names;
    }

    /** [names] as the saved choice, in the list's order. */
    public static String join(Set<String> names) {
        List<String> ordered = new ArrayList<>();
        for (String action : ACTIONS) {
            if (names.contains(action)) ordered.add(action);
        }
        return String.join(",", ordered);
    }
}
