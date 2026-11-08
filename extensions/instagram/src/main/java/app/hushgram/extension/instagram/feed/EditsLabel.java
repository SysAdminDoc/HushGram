/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the Hide Made with Edits patch (#570). The line under a feed post's author cycles
 * through the labels its header state lists: the song, the place, "Suggested for you", and "Made
 * with Edits" for a post made in Meta's Edits app. That last one is the list's Edits kind, an enum
 * named BASEL_ATTRIBUTION (Basel is Edits' code name). The patch hands that list over as the state
 * is built, and this takes the Edits kind out of it, so the other labels keep turning as before.
 */
public final class EditsLabel {
    static final String HOOK = "edits label";
    static final String COUNT_HIDDEN = "edits labels hidden";

    /** The start of the Edits kinds' names, which survive the build as the enum's own names. */
    static final String EDITS_KIND = "BASEL_";

    private EditsLabel() {
    }

    /** The label kinds a feed post's header cycles through: [types] without Edits while the switch is on. */
    @Nullable
    public static List<?> labels(@Nullable List<?> types) {
        return labels(types, EditsLabel::wanted);
    }

    static List<?> labels(@Nullable List<?> types, BooleanSupplier on) {
        if (types == null || types.isEmpty()) return types;
        try {
            HookStatus.invoked(FamilyNames.EDITS_LABEL);
            if (!on.getAsBoolean()) return types;
            List<Object> kept = null;
            for (int at = 0; at < types.size(); at++) {
                Object type = types.get(at);
                if (type instanceof Enum && ((Enum<?>) type).name().startsWith(EDITS_KIND)) {
                    if (kept == null) kept = new ArrayList<>(types.subList(0, at));
                    HookStatus.counted(FamilyNames.EDITS_LABEL, COUNT_HIDDEN);
                } else if (kept != null) {
                    kept.add(type);
                }
            }
            return kept == null ? types : kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EDITS_LABEL, HOOK, failure);
            return types;
        }
    }

    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HIDE_EDITS_LABEL.get();
    }
}
