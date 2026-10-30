/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import java.util.ArrayList;
import java.util.List;
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
 * half in its original order. Your own story, Create and special entries keep their native slots.
 * Nothing is added or dropped.
 *
 * <p>A story you've just watched stays where it was (#377). Home asks the store for the tray again
 * the moment the viewer closes, and sorting it then sent the new gray ring to the back with the
 * row's scroll following it, leaving you among watched stories. So the order a tray went out in is
 * remembered, by the very reels the store held, and goes out again while the store hands over
 * those same reels in that same order. A tray with a reel more, fewer or elsewhere, which is what a
 * refresh with new stories brings, is sorted afresh, and so is every tray after {@link #refreshed}
 * or a restart. Home's top row and its feed adapter each ask for their own tray, so the last few
 * are remembered.
 *
 * <p>Either switch off, a pause or settings that aren't ready give the list back as it came and
 * forget the remembered orders, and any failure deciding gives the list back as it came.
 */
public final class StoryTrayOrder {
    /** The hook's name in the diagnostics, and what it counts. */
    static final String HOOK = "story tray order";
    static final String MOVED = "watched stories moved to the back";
    static final String KEPT = "stories just watched kept in place";
    static final int PINNED = -1;

    /** How many trays are remembered: Home's top row and its feed adapter each ask for their own. */
    static final int REMEMBERED = 4;

    /** Native state: 0 unseen, 1 watched, or {@link #PINNED} for entries that must stay put. */
    interface Seen {
        int of(Object reel);
    }

    /** A tray as the store held it, and the order it went out in. */
    private static final class Tray {
        final Object[] held;
        final List<Object> wentOut;

        Tray(List<Object> held, List<Object> wentOut) {
            this.held = held.toArray();
            this.wentOut = new ArrayList<>(wentOut);
        }

        /** Whether the store holds the very reels this tray was, in the same order. */
        boolean holds(List<Object> reels) {
            if (reels.size() != held.length) return false;
            for (int index = 0; index < held.length; index++) {
                if (reels.get(index) != held[index]) return false;
            }
            return true;
        }
    }

    /** The trays that went out last, oldest first (#377). Guarded by itself. */
    private static final List<Tray> remembered = new ArrayList<>();

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

    /** Forgets every order that went out, so the next tray is sorted afresh. Never throws. */
    public static void refreshed() {
        synchronized (remembered) {
            remembered.clear();
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
            for (int index = 0; index < reels.size(); index++) {
                Object reel = reels.get(index);
                if (reel == null) continue;
                int state = seen.of(reel);
                if (state == PINNED) continue;
                if (state != 0 && state != 1) throw new IllegalStateException("Unknown story tray state");
                slots[count++] = index;
                if (state == 1) watched.add(reel);
                else unseen.add(reel);
            }
            List<Object> ordered = reels;
            if (!watched.isEmpty() && !unseen.isEmpty()) {
                unseen.addAll(watched);
                List<Object> sorted = new ArrayList<>(reels);
                for (int index = 0; index < count; index++) sorted.set(slots[index], unseen.get(index));
                if (!sorted.equals(reels)) ordered = sorted;
            }
            List<Object> kept = kept(reels, ordered);
            if (kept != null) {
                HookStatus.counted(FamilyNames.STORY_SEEN, KEPT);
                Logger.printDebug(() -> "Story tray: kept the order its " + reels.size() + " reels went out in");
                return kept;
            }
            if (ordered == reels) return reels;
            HookStatus.counted(FamilyNames.STORY_SEEN, MOVED);
            Logger.printDebug(() -> "Story tray: moved " + watched.size() + " watched stories to the back");
            return ordered;
        } catch (Throwable failure) {
            try {
                HookStatus.threw(FamilyNames.STORY_SEEN, HOOK, failure);
            } catch (Throwable ignored) {
                // There is nowhere left to report a diagnostic failure.
            }
            return reels;
        }
    }

    /**
     * The order [reels] went out in before, when the store held these very reels in this same order
     * and that order isn't [ordered]. Null otherwise, with [ordered] remembered as the order these
     * reels go out in from now.
     */
    private static List<Object> kept(List<Object> reels, List<Object> ordered) {
        synchronized (remembered) {
            for (int index = 0; index < remembered.size(); index++) {
                Tray tray = remembered.get(index);
                if (!tray.holds(reels)) continue;
                if (index < remembered.size() - 1) {
                    remembered.remove(index);
                    remembered.add(tray);
                }
                return tray.wentOut.equals(ordered) ? null : new ArrayList<>(tray.wentOut);
            }
            if (remembered.size() == REMEMBERED) remembered.remove(0);
            remembered.add(new Tray(reels, ordered));
            return null;
        }
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled by the patch: a native ordinary story's seen state, or {@link #PINNED} for own/special/unknown entries. */
    static int seen(Object store, Object reel) {
        return PINNED;
    }
}
