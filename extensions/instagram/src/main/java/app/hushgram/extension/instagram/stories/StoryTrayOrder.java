/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Sends the stories you've watched to the back of Home's story tray while "View stories
 * anonymously" and "Gray out stories you've watched" are both on (#241).
 *
 * <p>With both on, a watched story's ring turns gray on the phone ({@link StorySeenRings}), but
 * Instagram only moves a gray ring to the end of the tray when one of its own server flags says to.
 * Home's feed adapter asks the reel store for the tray's reels and the store, with that flag off,
 * hands them back in the order the server sent. So the patch hands the store's answer to
 * {@link #order}, which sorts only ordinary story slots, unseen first and watched after, each
 * half in its original order. Create and special entries keep their native slots, and the signed-in
 * account's own `Your story` reel goes first, ahead of the watched ones pushed back (#241).
 * Nothing is added or dropped.
 *
 * <p>A story you've just watched stays where it was (#377). Home asks the store for the tray again
 * the moment the viewer closes and the store sorts itself on the way, and after a longer watch
 * Instagram fetches a new tray too, which ranks the reel you watched further back. Any of those sent
 * the new gray ring to the back with the row's scroll following it, leaving you among watched
 * stories. So a reel gets a place the first time it goes out unseen and holds it, whatever order the
 * store has it in and whether it's been watched since. A reel new to the row goes in beside the reel
 * the store has it behind, or after every reel with a place when nothing behind it has one, so a page
 * of reels loaded later never lands among watched ones held in place. Watched reels that never had a
 * place stay behind the rest. The places last only through a tray fetched after a watch or one adding
 * the row's next page ({@link #KEEPS_PLACES}). A tray fetched for any other reason starts the row
 * over, and so does a restart. The patch tells of a tray before the store takes it in, since the store
 * hands Home the new tray on the way: told after, Home drew a reloaded row from the old places, with
 * the stories you'd watched still ahead of the new ones (#241).
 *
 * <p>Either switch off, a pause or settings that aren't ready give the list back as it came and
 * forget the places, and any failure deciding gives the list back as it came.
 */
public final class StoryTrayOrder {
    /** The hook's name in the diagnostics, and what it counts. */
    static final String HOOK = "story tray order";
    static final String RESPONSE_HOOK = "story tray response";
    static final String MOVED = "watched stories moved to the back";
    static final String KEPT = "stories just watched kept in place";
    static final int PINNED = -1;
    /** The signed-in account's own reel: kept out of the sort and put first. */
    static final int OWN = -2;
    static final String OWN_FIRST = "own story put first";

    /**
     * The reasons for fetching a tray that keep the row's places, by Instagram's names for them: the
     * fetches after a watch, and the row's next page. Any other reason, or one that can't be read,
     * starts the row over (#241).
     */
    static final List<String> KEEPS_PLACES =
            Arrays.asList("consumption_based_refresh", "reset_to_home_after_story_consumption", "pagination");

    /** What comes before the reason in a request's text: Request{mReason=name, mInstanceNumber=n}. */
    private static final String REASON = "mReason=";

    /** Native state: 0 unseen, 1 watched, {@link #PINNED} for entries that must stay put, or {@link #OWN}. */
    interface Seen {
        int of(Object reel);
    }

    /**
     * Each reel's place in the row, lowest first (#377), by the very reel, since the store keeps one
     * for each id across tray responses. The store holds them all anyway. Guarded by itself.
     */
    private static final Map<Object, Double> places = new IdentityHashMap<>();

    private static final BooleanSupplier ON = StoryTrayOrder::grayedOut;

    private StoryTrayOrder() {
    }

    /** Whether the tray is ordered: views held back and watched stories grayed. */
    static boolean grayedOut() {
        return Utils.settingsReady() && StorySeen.anonymous() && Settings.GRAY_OUT_WATCHED_STORIES.get();
    }

    /**
     * Put by the patch on the list the reel [store] hands Home's feed adapter, just before it
     * returns. Returns [reels] itself unless the tray needs reordering. Never throws.
     */
    public static List<Object> order(List<Object> reels, Object store) {
        return order(reels, ON, reel -> seen(store, reel));
    }

    /**
     * Put by the patch just before the store takes in a tray response, which hands Home the new tray
     * on the way. The [request] it was fetched for says why in its text. Never throws.
     */
    public static void responded(Object request) {
        try {
            String reason = reason(String.valueOf(request));
            Logger.printDebug(() -> "Story tray: a tray came for " + reason);
            if (!KEEPS_PLACES.contains(reason)) refreshed();
        } catch (Throwable failure) {
            refreshed();
            report(RESPONSE_HOOK, failure);
        }
    }

    /** The reason in a request's [text], or nothing when it doesn't read as one. */
    static String reason(String text) {
        int start = text.indexOf(REASON);
        if (start < 0) return "";
        start += REASON.length();
        int end = text.indexOf(',', start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    /** Forgets every reel's place, so the next tray is sorted afresh. */
    static void refreshed() {
        synchronized (places) {
            places.clear();
        }
    }

    static List<Object> order(List<Object> reels, BooleanSupplier on, Seen seen) {
        try {
            if (reels == null || reels.size() < 2) return reels;
            if (!on.getAsBoolean()) {
                refreshed();
                return reels;
            }
            List<Object> unseen = new ArrayList<>(reels.size());
            List<Object> watched = new ArrayList<>();
            int[] slots = new int[reels.size()];
            int count = 0;
            int own = -1;
            for (int index = 0; index < reels.size(); index++) {
                Object reel = reels.get(index);
                if (reel == null) continue;
                int state = seen.of(reel);
                if (state == PINNED) continue;
                if (state == OWN) {
                    if (own < 0) own = index;
                    continue;
                }
                if (state != 0 && state != 1) throw new IllegalStateException("Unknown story tray state");
                slots[count++] = index;
                if (state == 1) watched.add(reel);
                else unseen.add(reel);
            }

            List<Object> sequence = new ArrayList<>(count);
            List<Object> kept = new ArrayList<>();
            List<Object> behind = new ArrayList<>();
            synchronized (places) {
                givePlaces(unseen);
                for (Object reel : watched) (places.containsKey(reel) ? kept : behind).add(reel);
                Comparator<Object> byPlace = Comparator.comparingDouble(places::get);
                unseen.sort(byPlace);
                sequence.addAll(unseen);
                sequence.addAll(kept);
                sequence.sort(byPlace);
            }
            // A reel just watched is only kept if it sits ahead of a reel still unseen.
            boolean keptAhead = !sequence.subList(0, unseen.size()).equals(unseen);
            sequence.addAll(behind);

            List<Object> sorted = new ArrayList<>(reels);
            for (int index = 0; index < count; index++) sorted.set(slots[index], sequence.get(index));
            boolean changed = !sorted.equals(reels);
            List<Object> ordered = changed ? sorted : reels;
            boolean ownFirst = own > 0;
            if (ownFirst) {
                List<Object> moved = new ArrayList<>(ordered);
                moved.add(0, moved.remove(own));
                ordered = moved;
            }

            if (keptAhead) {
                HookStatus.counted(FamilyNames.STORY_SEEN, KEPT);
                Logger.printDebug(() -> "Story tray: kept " + kept.size() + " stories just watched in place");
            }
            if (ordered == reels) return reels;
            if (ownFirst) HookStatus.counted(FamilyNames.STORY_SEEN, OWN_FIRST);
            if (changed && !behind.isEmpty() && !unseen.isEmpty()) {
                HookStatus.counted(FamilyNames.STORY_SEEN, MOVED);
                Logger.printDebug(() -> "Story tray: moved " + behind.size() + " watched stories to the back");
            }
            return ordered;
        } catch (Throwable failure) {
            report(HOOK, failure);
            return reels;
        }
    }

    /**
     * Gives each of [unseen], in the store's order, a place when it has none: between the reel the
     * store has it behind and the next reel with a place, so a tray with reels new to it reads as the
     * store has it. With no placed reel behind it, it goes after every place in use, watched reels'
     * included: one place past the reel ahead of it sat level with a watched reel held there, and the
     * next page of the row came out shuffled in among the gray rings (#241). Call holding
     * {@link #places}.
     */
    private static void givePlaces(List<Object> unseen) {
        for (int index = 0; index < unseen.size(); index++) {
            Object reel = unseen.get(index);
            if (places.containsKey(reel)) continue;
            Double before = index == 0 ? null : places.get(unseen.get(index - 1));
            Double after = null;
            for (int next = index + 1; next < unseen.size() && after == null; next++) {
                after = places.get(unseen.get(next));
            }
            double place;
            if (after == null) place = last() + 1;
            else place = before == null ? after - 1 : (before + after) / 2;
            places.put(reel, place);
        }
    }

    /** The highest place in use, or -1 with none. Call holding {@link #places}. */
    private static double last() {
        double last = -1;
        for (double place : places.values()) last = Math.max(last, place);
        return last;
    }

    private static void report(String hook, Throwable failure) {
        try {
            HookStatus.threw(FamilyNames.STORY_SEEN, hook, failure);
        } catch (Throwable ignored) {
            // There is nowhere left to report a diagnostic failure.
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled by the patch: a native ordinary story's seen state, {@link #OWN} for the account's own reel, or {@link #PINNED} for special/unknown entries. */
    static int seen(Object store, Object reel) {
        return PINNED;
    }
}
