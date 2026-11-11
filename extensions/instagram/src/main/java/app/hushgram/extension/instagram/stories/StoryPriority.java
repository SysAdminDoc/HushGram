/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.ConfigurationBackup;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Stories priority" patch (#451).
 *
 * <p>The user keeps an ordered list of usernames. In the stories row at the top of Home, an account
 * on the list that has a story the user hasn't watched goes to the front, in the order of the list.
 * Once its story is watched, Instagram's own placement applies to it again, and a new story puts
 * it back at its place on the list. The row's "Your story" entry, and every special or ad entry,
 * keep the slot they were in. An account on the list that isn't in the row does nothing, and nothing
 * is fetched.
 *
 * <p>The patch hands the lists the reel store gives out for the tray to {@link #order}, the same
 * lists {@link StoryTrayOrder} sorts, after it. Off, paused, an empty list, settings that aren't
 * ready or anything that throws give the list back as it came.
 *
 * <p>A story's menu also gets Add to Stories priority, through
 * {@link app.hushgram.extension.instagram.download.StoryDownload}, which puts the story's account at
 * the end of the list and turns the switch on, or Remove from Stories priority once it's there (#641).
 */
public final class StoryPriority {
    /** The count of stories put ahead of the rest, in the diagnostics report. */
    static final String MOVED = "stories put first";

    private static final String HOOK = "story priority";

    /** The step a failure of the story menu's row is reported under. */
    private static final String ROW = "story priority row";

    /** What {@link StoryTrayOrder} calls a reel that stays where it is. */
    private static final int PINNED = StoryTrayOrder.PINNED;
    private static final int OWN = StoryTrayOrder.OWN;

    /** Reads what decides a reel; the patch-filled stubs outside tests. */
    interface Reader {
        /** 0 for a reel with a story not watched yet, 1 for one watched, {@link #PINNED} or {@link #OWN} otherwise. */
        int seen(Object reel);

        /** The username of the reel's account, or null. */
        String username(Object reel);
    }

    private StoryPriority() {
    }

    /**
     * Put by the patch on the list the reel [store] hands out for the tray, just before it returns.
     * Returns [reels] itself unless the tray needs reordering. Never throws.
     */
    public static List<Object> order(List<Object> reels, Object store) {
        return order(reels, StoryPriority::accountsWhenOn, new Reader() {
            @Override
            public int seen(Object reel) {
                return StoryPriority.seen(store, reel);
            }

            @Override
            public String username(Object reel) {
                return StoryPriority.username(reel);
            }
        });
    }

    static List<Object> order(List<Object> reels, Supplier<List<String>> accounts, Reader reader) {
        try {
            if (reels == null || reels.size() < 2) return reels;
            List<String> wanted = accounts.get();
            if (wanted.isEmpty()) return reels;
            HookStatus.invoked(FamilyNames.STORY_PRIORITY);

            int size = reels.size();
            int[] slots = new int[size];
            int count = 0;
            // The reels with an unwatched story from an account on the list, with the place on the list.
            List<Object> first = new ArrayList<>();
            List<Integer> places = new ArrayList<>();
            List<Object> rest = new ArrayList<>();
            List<Object> ordinary = new ArrayList<>();
            for (int index = 0; index < size; index++) {
                Object reel = reels.get(index);
                if (reel == null) continue;
                int state = reader.seen(reel);
                if (state != 0 && state != 1) continue;
                slots[count++] = index;
                ordinary.add(reel);
                int place = -1;
                if (state == 0) {
                    String name = normalize(reader.username(reel));
                    if (name != null) place = wanted.indexOf(name);
                }
                if (place < 0) {
                    rest.add(reel);
                } else {
                    // By the list's order; two reels of one account stay in the order they came.
                    int at = first.size();
                    while (at > 0 && places.get(at - 1) > place) at--;
                    first.add(at, reel);
                    places.add(at, place);
                }
            }
            if (first.isEmpty()) return reels;
            List<Object> sequence = new ArrayList<>(first);
            sequence.addAll(rest);
            if (sequence.equals(ordinary)) return reels;
            List<Object> ordered = new ArrayList<>(reels);
            for (int index = 0; index < count; index++) ordered.set(slots[index], sequence.get(index));
            for (int index = 0; index < first.size(); index++) HookStatus.counted(FamilyNames.STORY_PRIORITY, MOVED);
            int moved = first.size();
            Logger.printDebug(() -> "Story priority: " + moved + " stories put first");
            return ordered;
        } catch (Throwable failure) {
            try {
                HookStatus.threw(FamilyNames.STORY_PRIORITY, HOOK, failure);
            } catch (Throwable ignored) {
                // There is nowhere left to report a diagnostic failure.
            }
            return reels;
        }
    }

    /** The list while the switch is on, read only once the settings are; empty while off or paused. */
    static List<String> accountsWhenOn() {
        if (!Utils.settingsReady() || !Settings.STORY_PRIORITY.get()) return Collections.emptyList();
        return accounts(Settings.STORY_PRIORITY_ACCOUNTS.get());
    }

    /**
     * The usernames in a stored list: one per line (a comma works too), each trimmed of spaces and a
     * leading at sign, lower case, the empty ones and repeats left out, the first line first.
     */
    public static List<String> accounts(String stored) {
        if (stored == null || stored.trim().isEmpty()) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (String piece : stored.split("[,\\n\\r]")) {
            String name = normalize(piece);
            if (name != null && !names.contains(name)) names.add(name);
        }
        return Collections.unmodifiableList(names);
    }

    /** A username as the list holds it, or null when nothing is left of it. */
    static String normalize(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        while (trimmed.startsWith("@")) trimmed = trimmed.substring(1).trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ the row in a story's menu (#641)

    /**
     * Whether a story's menu gets Add to Stories priority: the patch is in this build, the settings
     * are ready and HushGram isn't paused. The switch can be off, since adding someone turns it on.
     * Never throws.
     */
    public static boolean offersRow() {
        try {
            return SettingsStatus.storyPriority() && Utils.settingsReady() && !HushgramPause.isPaused();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_PRIORITY, ROW, failure);
            return false;
        }
    }

    /**
     * The row for the menu of a story by [username]: Remove from Stories priority while the switch is
     * on and the account is on the list, Add to Stories priority otherwise, or null when the menu
     * doesn't get the row or the story's account can't be read.
     */
    @Nullable
    public static String menuLabel(@Nullable String username) {
        if (normalize(username) == null || !offersRow()) return null;
        Context context = Utils.getContext();
        return listed(username) ? L10n.t(context, "Remove from Stories priority") : L10n.t(context, "Add to Stories priority");
    }

    /** Whether [label], a tapped row, is Add to Stories priority or Remove from Stories priority. */
    public static boolean isMenuLabel(@Nullable CharSequence label) {
        if (label == null) return false;
        Context context = Utils.getContext();
        return L10n.t(context, "Add to Stories priority").contentEquals(label)
                || L10n.t(context, "Remove from Stories priority").contentEquals(label);
    }

    /**
     * Takes [username] off the list when {@link #menuLabel} offered Remove, or else puts it on the
     * end of the list and turns the switch on. Answers whether the account is on the list now, or
     * null when nothing changed: the row isn't offered, the name can't be read, the list would grow
     * past what its row holds, or the save failed. Never throws.
     */
    @Nullable
    public static Boolean toggle(@Nullable String username) {
        try {
            String name = normalize(username);
            if (name == null || !offersRow()) return null;
            String stored = Settings.STORY_PRIORITY_ACCOUNTS.savedValue();
            if (listed(name)) {
                return Settings.STORY_PRIORITY_ACCOUNTS.save(without(stored, name)) ? Boolean.FALSE : null;
            }
            String more = with(stored, name);
            if (more.length() > ConfigurationBackup.MAX_WORDS_TEXT) return null;
            if (!Settings.STORY_PRIORITY_ACCOUNTS.save(more)) return null;
            if (!Settings.STORY_PRIORITY.savedValue() && !Settings.STORY_PRIORITY.save(true)) return null;
            Logger.printDebug(() -> "Story priority: added an account from a story's menu");
            return Boolean.TRUE;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_PRIORITY, ROW, failure);
            return null;
        }
    }

    /** Whether the switch is on with [username] on its list. */
    private static boolean listed(String username) {
        return Settings.STORY_PRIORITY.savedValue()
                && accounts(Settings.STORY_PRIORITY_ACCOUNTS.savedValue()).contains(normalize(username));
    }

    /** [stored] with [name] on a line of its own at the end, or as it was when the name is on it already. */
    static String with(String stored, String name) {
        if (accounts(stored).contains(normalize(name))) return stored;
        String kept = stored == null ? "" : stored.trim();
        return kept.isEmpty() ? name : kept + "\n" + name;
    }

    /** [stored] without [name], the other entries one to a line, as they were typed. */
    static String without(String stored, String name) {
        if (stored == null) return "";
        String gone = normalize(name);
        StringBuilder kept = new StringBuilder();
        for (String piece : stored.split("[,\\n\\r]")) {
            String entry = normalize(piece);
            if (entry == null || entry.equals(gone)) continue;
            if (kept.length() > 0) kept.append('\n');
            kept.append(piece.trim());
        }
        return kept.toString();
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled by the patch: a native ordinary story's seen state, {@link #OWN} for the account's own reel, or {@link #PINNED} for special/unknown entries. */
    static int seen(Object store, Object reel) {
        return PINNED;
    }

    /** Filled by the patch: the username of the account a reel belongs to, or null. */
    static String username(Object reel) {
        return null;
    }
}
