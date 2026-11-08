/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Hold the Friends tab on Reels to pick friends, and the tab keeps the reels they're on (#503).
 * Nothing new is asked of Instagram: the list holds the friends named on the pages the tab has
 * already loaded this run, and the filter works over those same pages.
 *
 * A friend is on a reel when the reel's social context names them, either among its faces or as its
 * source, or when its top likers do. That's the same data the bubble on a Friends tab reel is drawn
 * from.
 *
 * An empty page isn't safe to hand back: Instagram stores an empty state for the Friends tab when
 * it gets one, and nothing scrolls to ask for the next page. So a page with none of the picks keeps
 * its first reel. A page that names nobody at all comes back as it was, since that means there's
 * nothing here to judge by.
 */
public final class FriendLanePicks {
    static final String ROUTE = "Friends tab picks";

    /** The module name of the Friends tab's viewer, the only pages this filters. */
    static final String FRIENDS_LANE = "clips_viewer_friends_lane";

    /** The kind counted for an item that isn't a reel, which always stays. */
    static final String NOT_A_REEL = "not a reel";

    /** The kind counted for a reel that names no friend. */
    static final String NO_NAMES = "no friends named";

    /** The kind counted for a reel taken out because none of the picks are on it. */
    static final String NOT_PICKED = "not picked";

    /** The kind counted for a page kept down to one reel because none of the picks are on it. */
    static final String ONE_KEPT = "one reel kept";

    /** The kind counted for a page left whole because no reel on it names anyone. */
    static final String PAGE_KEPT = "page kept whole";

    /** Status count for each reel taken out. */
    static final String REMOVED = "Friends tab reels left out";

    /** How many names the list remembers in one run. */
    static final int MOST_SEEN = 500;

    /** The friends the tab has named this run. */
    private static final Set<String> SEEN = Collections.synchronizedSet(new LinkedHashSet<>());

    /** Reads what decides an item; the patch-filled stubs below outside tests. */
    interface Reader {
        /** The item's post, or null for anything that isn't a Reels item carrying one. */
        Object media(Object item);

        /** The usernames the post names as friends. Empty when it names none. */
        Collection<String> friends(Object media);
    }

    private static final Reader PATCHED = new Reader() {
        @Override
        public Object media(Object item) {
            return FriendLanePicks.media(item);
        }

        @Override
        public Collection<String> friends(Object media) {
            Set<String> names = new LinkedHashSet<>();
            List<?> contexts = socialContexts(media);
            if (contexts != null) {
                for (Object context : contexts) {
                    if (context == null) continue;
                    List<?> faces = faces(context);
                    if (faces != null) {
                        for (Object face : faces) addUser(names, face);
                    }
                    addUser(names, sourceUser(context));
                }
            }
            List<?> likers = topLikers(media);
            if (likers != null) {
                for (Object liker : likers) {
                    if (liker instanceof String) names.add((String) liker);
                }
            }
            return names;
        }

        private void addUser(Set<String> names, Object user) {
            if (user == null) return;
            String name = username(user);
            if (name != null) names.add(name);
        }
    };

    private FriendLanePicks() {
    }

    /**
     * Injected where the Reels viewer's network callback takes a page, with the viewer's module name.
     * Answers a new list holding only the reels with a picked friend on them while the switch is on
     * and the page is the Friends tab's, and [items] itself for any other viewer, when nothing's been
     * picked, when nothing goes, when no reel names anyone, when the switch is off or paused, or when
     * anything goes wrong. Never throws.
     */
    public static List<?> page(List<?> items, String module) {
        return page(items, module, PATCHED);
    }

    static List<?> page(List<?> items, String module, Reader reader) {
        if (items == null) return null;
        try {
            HookStatus.invoked(FamilyNames.REEL_DECLUTTER);
            if (!FRIENDS_LANE.equals(module) || !on()) return items;
            int size = items.size();
            FeedFilterCounters.sawList(ROUTE, size);
            if (size == 0) return items;
            Set<String> picks = picks();

            boolean[] goes = new boolean[size];
            int reels = 0;
            int named = 0;
            int going = 0;
            int firstReel = -1;
            int at = 0;
            for (Object item : items) {
                if (at >= size) return items;
                Object media = item == null ? null : reader.media(item);
                if (media == null) {
                    FeedFilterCounters.sawKind(ROUTE, NOT_A_REEL);
                } else {
                    if (firstReel < 0) firstReel = at;
                    reels++;
                    boolean picked = false;
                    boolean anyone = false;
                    Collection<String> friends = reader.friends(media);
                    if (friends != null) {
                        for (String friend : friends) {
                            String name = clean(friend);
                            if (name == null) continue;
                            anyone = true;
                            remember(name);
                            picked |= picks.contains(name);
                        }
                    }
                    if (anyone) named++;
                    else FeedFilterCounters.sawKind(ROUTE, NO_NAMES);
                    if (!picked) {
                        goes[at] = true;
                        going++;
                    }
                }
                at++;
            }
            // A list that changed size while it was read isn't one this can rebuild.
            if (at != size || picks.isEmpty() || going == 0) return items;
            if (named == 0) {
                FeedFilterCounters.sawKind(ROUTE, PAGE_KEPT);
                return items;
            }
            if (going == reels) {
                goes[firstReel] = false;
                going--;
                FeedFilterCounters.sawKind(ROUTE, ONE_KEPT);
                if (going == 0) return items;
            }

            List<Object> kept = new ArrayList<>(size - going);
            at = 0;
            for (Object item : items) {
                if (!goes[at++]) kept.add(item);
            }
            FeedFilterCounters.removed(ROUTE, going, NOT_PICKED);
            for (int count = 0; count < going; count++) HookStatus.counted(FamilyNames.REEL_DECLUTTER, REMOVED);
            int left = kept.size();
            int removed = going;
            Logger.printDebug(() -> "Friends tab picks: took " + removed + " of " + size + " out, " + left + " left");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "Friends tab picks", failure);
            return items;
        }
    }

    private static final View.OnLongClickListener HOLD = FriendLanePicks::held;

    /**
     * Has holding the Friends tab, as Instagram builds it, open the list. Some builds of the strip
     * give each tab a hold of Instagram's own once it's built, which only clears the tab's tooltip,
     * so a press on the tab puts this one back while the switch is on (#571).
     */
    static void watch(View tab) {
        tab.setOnLongClickListener(HOLD);
        tab.setOnTouchListener(FriendLanePicks::pressed);
    }

    /** A touch on the tab. A press puts the hold back. Never takes the touch. */
    static boolean pressed(View tab, MotionEvent event) {
        try {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && on()) tab.setOnLongClickListener(HOLD);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "Friends tab press", failure);
        }
        return false;
    }

    /**
     * The tab was held. True once it's been answered with the list or a toast. False leaves the hold
     * to Instagram, which does nothing with it, when the switch is off or paused or on any trouble.
     */
    static boolean held(View tab) {
        try {
            if (tab == null || !on()) return false;
            Activity activity = SearchHeaderButton.activityOf(tab.getContext());
            if (activity == null) return false;
            List<String> roster = roster();
            if (roster.isEmpty()) {
                Utils.showToastShort(L10n.t("Open the Friends tab first."));
                return true;
            }
            ask(activity, roster);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DECLUTTER, "Friends tab hold", failure);
            return false;
        }
    }

    private static void ask(Activity activity, List<String> roster) {
        Set<String> picks = picks();
        CharSequence[] names = roster.toArray(new CharSequence[0]);
        boolean[] checked = new boolean[names.length];
        for (int i = 0; i < names.length; i++) checked[i] = picks.contains(roster.get(i));
        boolean[] changed = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(L10n.t(activity, "Pick friends"))
                .setMultiChoiceItems(names, checked, (shown, which, on) -> {
                    if (on) picks.add(roster.get(which));
                    else picks.remove(roster.get(which));
                    Settings.FRIENDS_TAB_PICKS.save(join(picks));
                    changed[0] = true;
                })
                .setPositiveButton(L10n.t(activity, "OK"), null)
                .create();
        dialog.setOnDismissListener(gone -> {
            if (changed[0]) Utils.showToastShort(L10n.t("Applies to the next reels loaded."));
        });
        dialog.show();
    }

    /** Whether the switch is on and HushGram isn't paused. False before the settings are read. */
    private static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.PICK_FRIENDS_TAB.get();
    }

    /** The picks first, then everyone else the tab has named, each alphabetically. */
    static List<String> roster() {
        Set<String> picks = new TreeSet<>(picks());
        Set<String> others = new TreeSet<>();
        synchronized (SEEN) {
            others.addAll(SEEN);
        }
        others.removeAll(picks);
        List<String> roster = new ArrayList<>(picks);
        roster.addAll(others);
        return roster;
    }

    /** The saved picks, as a set the caller can change. */
    static Set<String> picks() {
        return parse(Settings.FRIENDS_TAB_PICKS.get());
    }

    /** The usernames in [text], one per line, lower case, in order, without blanks or repeats. */
    static Set<String> parse(String text) {
        Set<String> names = new LinkedHashSet<>();
        if (text == null) return names;
        for (String line : text.split("\n")) {
            String name = clean(line);
            if (name != null) names.add(name);
        }
        return names;
    }

    static String join(Collection<String> names) {
        return String.join("\n", names);
    }

    /** Forgets the friends named so far, for tests. */
    static void forgetSeen() {
        SEEN.clear();
    }

    private static void remember(String name) {
        synchronized (SEEN) {
            if (SEEN.size() < MOST_SEEN) SEEN.add(name);
        }
    }

    private static String clean(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** Filled in by the patch: the item's post, or null for anything that isn't a Reels item. */
    public static Object media(Object item) {
        return null;
    }

    /** Filled in by the patch: the post's social_context, or null. */
    public static List<?> socialContexts(Object media) {
        return null;
    }

    /** Filled in by the patch: the social context's social_context_facepile_users, or null. */
    public static List<?> faces(Object context) {
        return null;
    }

    /** Filled in by the patch: the social context's social_context_source_user, or null. */
    public static Object sourceUser(Object context) {
        return null;
    }

    /** Filled in by the patch: the post's top_likers, usernames, or null. */
    public static List<?> topLikers(Object media) {
        return null;
    }

    /** Filled in by the patch: the user's username, or null. */
    public static String username(Object user) {
        return null;
    }
}
