/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>Reels have a switch of their own. There the line is already built when the props of the reel's
 * author row are made: one label, a sliding line of turns, or Instagram's empty line. The patch
 * hands it to {@link #reelLine} first thing in that constructor, past Sponsored and Paid partnership.
 */
public final class HeaderLine {
    static final String HOOK = "header line";
    static final String COUNT_HIDDEN = "header labels hidden";

    /** The kinds that stay: an ad's Sponsored and a paid partnership's label. */
    static final Set<String> KEPT = new HashSet<>(Arrays.asList("SPONSORED", "ORGANIC_BRANDED_CONTENT"));

    static final String REEL_HOOK = "reel header line";
    static final String COUNT_REEL_HIDDEN = "reel header lines hidden";
    static final String COUNT_REEL_KEPT = "reel header lines cut to a disclosure";

    /** The same two on a reel, as the reel line's own kinds name them on 456. */
    static final Set<String> REEL_KEPT = new HashSet<>(Arrays.asList("SPONSORED_LABEL", "PAID_PARTNERSHIP"));

    /** The instance fields of each class a reel line's parts have been read from. */
    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();

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

    /**
     * The line under a reel's author, as the props of the reel's author row are built: [line] with the
     * switch on is [none], Instagram's own empty line, or for a sliding line the turn of a disclosure
     * label in it. The patch leaves Sponsored and Paid partnership lines alone before asking. Any other
     * answer, or anything that throws, is [line] as it came.
     */
    public static Object reelLine(@Nullable Object line, @Nullable Object none) {
        return reelLine(line, none, HeaderLine::reelWanted);
    }

    static Object reelLine(@Nullable Object line, @Nullable Object none, BooleanSupplier on) {
        if (line == null || none == null || line == none) return line;
        try {
            HookStatus.invoked(FamilyNames.FEED_HEADER_LINE);
            if (!on.getAsBoolean()) return line;
            Object kept = disclosureTurn(line, none);
            HookStatus.counted(FamilyNames.FEED_HEADER_LINE, kept == none ? COUNT_REEL_HIDDEN : COUNT_REEL_KEPT);
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_HEADER_LINE, REEL_HOOK, failure);
            return line;
        }
    }

    /**
     * A sliding line holds its turns in a list, each turn a label kind (an enum keeping Instagram's
     * names) beside the line it shows. The first turn of a {@link #REEL_KEPT} kind whose line is the
     * same kind of thing as [none] is what stays, and with none of those it's [none].
     */
    static Object disclosureTurn(Object line, Object none) throws IllegalAccessException {
        List<?> turns = null;
        for (Object value : values(line)) {
            if (value instanceof List) turns = (List<?>) value;
        }
        if (turns == null) return none;
        for (Object turn : turns) {
            if (turn == null) continue;
            Enum<?> kind = null;
            Object shown = null;
            for (Object value : values(turn)) {
                if (value instanceof Enum) kind = (Enum<?>) value;
                else if (value != null) shown = value;
            }
            if (kind != null && REEL_KEPT.contains(kind.name()) && sameKind(shown, none)) return shown;
        }
        return none;
    }

    /** Whether [shown] is everything [none] is: it implements each of the interfaces [none]'s class does. */
    static boolean sameKind(@Nullable Object shown, Object none) {
        Class<?>[] kinds = none.getClass().getInterfaces();
        if (shown == null || kinds.length == 0) return false;
        for (Class<?> kind : kinds) {
            if (!kind.isInstance(shown)) return false;
        }
        return true;
    }

    /** The values of [owner]'s own instance fields, read once per class for their fields. */
    private static List<Object> values(Object owner) throws IllegalAccessException {
        Field[] fields = FIELDS.get(owner.getClass());
        if (fields == null) {
            List<Field> own = new ArrayList<>();
            for (Field field : owner.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                own.add(field);
            }
            fields = own.toArray(new Field[0]);
            FIELDS.put(owner.getClass(), fields);
        }
        List<Object> values = new ArrayList<>(fields.length);
        for (Field field : fields) values.add(field.get(owner));
        return values;
    }

    static boolean reelWanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HIDE_REEL_HEADER_LINE.get();
    }
}
