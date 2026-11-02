/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

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
 */
public final class StoryPriority {
    /** The count of stories put ahead of the rest, in the diagnostics report. */
    static final String MOVED = "stories put first";

    private static final String HOOK = "story priority";

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
