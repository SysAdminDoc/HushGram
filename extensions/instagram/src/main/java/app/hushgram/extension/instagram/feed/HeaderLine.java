/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the Hide the line under names patch (#771). The line under a feed post's author shows
 * the song, the place, "Made with Edits" and Instagram's other labels, sliding from one to the next
 * when there are two or more. Instagram decides that from a list of label kinds (an enum whose
 * constants keep their own names, LOCATION, CLIPS_AUDIO_ATTRIBUTION, MUSIC_ATTRIBUTION and the rest)
 * while it builds the post's header: two or more slide, one stays put, none gives the header with the
 * name alone, centered beside the picture.
 *
 * <p>The patch hands that list over right where Instagram counts it, and with the switch on this
 * keeps only the disclosure labels, Sponsored and Paid partnership. So most posts get Instagram's own
 * name-only header, with no empty line left behind.
 */
public final class HeaderLine {
    static final String HOOK = "header line";
    static final String COUNT_HIDDEN = "header labels hidden";

    /** The kinds that stay: an ad's Sponsored and a paid partnership's label. */
    static final Set<String> KEPT = new HashSet<>(Arrays.asList("SPONSORED", "ORGANIC_BRANDED_CONTENT"));

    private HeaderLine() {
    }

    /** The label kinds of a feed post's header: [kinds] with only the disclosure labels while the switch is on. */
    @Nullable
    public static List<?> kinds(@Nullable List<?> kinds) {
        return kinds(kinds, HeaderLine::wanted);
    }

    static List<?> kinds(@Nullable List<?> kinds, BooleanSupplier on) {
        if (kinds == null || kinds.isEmpty()) return kinds;
        try {
            HookStatus.invoked(FamilyNames.FEED_HEADER_LINE);
            if (!on.getAsBoolean()) return kinds;
            List<Object> kept = new ArrayList<>(kinds.size());
            for (Object kind : kinds) {
                // Anything that isn't one of Instagram's kinds stays, so a changed list is left as it came.
                if (!(kind instanceof Enum) || KEPT.contains(((Enum<?>) kind).name())) kept.add(kind);
                else HookStatus.counted(FamilyNames.FEED_HEADER_LINE, COUNT_HIDDEN);
            }
            return kept.size() == kinds.size() ? kinds : kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HEADER_LINE, HOOK, failure);
            return kinds;
        }
    }

    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HIDE_FEED_HEADER_LINE.get();
    }
}
