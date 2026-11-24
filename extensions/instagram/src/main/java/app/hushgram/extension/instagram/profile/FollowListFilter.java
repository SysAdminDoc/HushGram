/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Hold a tab at the top of a Followers or Following list to show everyone, only public accounts or
 * only private ones (#847). Any account's lists, yours or someone else's.
 *
 * <p>Instagram rebuilds a follow list's rows from every account it has loaded so far, at each page and
 * each change, and the patch hands that list to {@link FollowingList#ordered}, which hands it on here
 * last ({@link #filtered}). The filter works on a copy and keeps an account Instagram hasn't said is
 * public or private, so nothing goes on a guess. The choice holds for every follow list until it's
 * changed or Instagram restarts, and picking one has the lists open now rebuilt at once.
 *
 * <p>The tabs are Instagram's own, found from a row the first time one is drawn in a list
 * ({@link #watch}): the strip holding them is an ancestor's, under its own id.
 */
public final class FollowListFilter {
    static final int EVERYONE = 0;
    static final int PUBLIC = 1;
    static final int PRIVATE = 2;

    static final String FILTER = "follow list filter";
    static final String HOLD = "follow list filter hold";

    /** Instagram's name for the strip of tabs at the top of a follow list. */
    static final String TAB_STRIP = "unified_follow_list_tab_layout";

    /** How far up from a row the strip is looked for. */
    static final int MOST_LEVELS = 16;

    /** Instagram's name for a Followers list. */
    static final String FOLLOWERS_KIND = Unfollowers.FOLLOWERS_KIND;

    /** Which accounts the lists show: {@link #EVERYONE}, {@link #PUBLIC} or {@link #PRIVATE}. */
    private static volatile int shown = EVERYONE;

    /** The lists filtered, weakly, so a pick can have them rebuilt. */
    private static final Map<Object, Boolean> lists = new WeakHashMap<>();

    /** The views holding rows whose tab strip has been looked for, weakly. */
    private static final Map<View, Boolean> looked = new WeakHashMap<>();

    /** The strip's id, looked up once: -1 until then, 0 when the build has none. */
    static int stripId = -1;

    private static final View.OnLongClickListener HOLD_TAB = FollowListFilter::held;

    /** Where {@link #filtered} gets what it needs from Instagram's objects. Tests hand in their own. */
    interface Parts {
        @Nullable
        Object binder(Object list);

        @Nullable
        Object kind(Object binder);

        /** Whether [user]'s account is private, or null when Instagram hasn't said. */
        @Nullable
        Boolean isPrivate(Object user);
    }

    static final Parts PATCHED = new Parts() {
        @Override
        public Object binder(Object list) {
            return FollowingList.orderBinder(list);
        }

        @Override
        public Object kind(Object binder) {
            return FollowingList.listKind(binder);
        }

        @Override
        public Boolean isPrivate(Object user) {
            return Unfollowers.isPrivate(user);
        }
    };

    /** Has a list's rows built again from its accounts. The patch-filled stub outside tests. */
    interface Rebuilder {
        void rebuild(Object list);
    }

    static volatile Rebuilder rebuilder = list -> FollowingList.rebuildRows(list, false, false);

    private FollowListFilter() {
    }

    /**
     * Called from {@link FollowingList#ordered} with the accounts a follow list is about to build its
     * rows from, after anything else there. Answers a copy without the accounts the choice leaves out
     * when it's a Followers or Following list and the switch is on, and [users] itself otherwise.
     * Never throws.
     */
    public static Object filtered(Object users, Object list) {
        return filtered(users, list, PATCHED, FollowListFilter::switchedOn);
    }

    static Object filtered(Object users, Object list, Parts parts, BooleanSupplier on) {
        try {
            if (!(users instanceof List) || list == null || !on.getAsBoolean()) return users;
            Object binder = parts.binder(list);
            if (binder == null || !followList(parts.kind(binder))) return users;
            synchronized (lists) {
                lists.put(list, Boolean.TRUE);
            }
            int choice = shown;
            if (choice == EVERYONE) return users;
            boolean wantPrivate = choice == PRIVATE;
            List<?> all = (List<?>) users;
            List<Object> kept = new ArrayList<>(all.size());
            for (Object user : all) {
                Boolean isPrivate = user == null ? null : parts.isPrivate(user);
                if (isPrivate == null || isPrivate == wantPrivate) kept.add(user);
            }
            if (kept.size() == all.size()) return users;
            int left = kept.size();
            Logger.printDebug(() -> "Follow list filter: " + left + " of " + all.size() + " accounts shown");
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, FILTER, failure);
            return users;
        }
    }

    /** Whether [kind] is a Followers or Following list, by Instagram's enum constant. */
    static boolean followList(@Nullable Object kind) {
        if (!(kind instanceof Enum)) return false;
        String name = ((Enum<?>) kind).name();
        return FOLLOWERS_KIND.equals(name) || FollowingList.FOLLOWING_KINDS.contains(name);
    }

    /**
     * Called from {@link FollowingList#row} with each row a follow list draws. The first time a row of
     * a list is on screen, has holding each tab of the strip above it open the choice. Never throws.
     */
    static void watch(@Nullable View row) {
        try {
            if (row == null || !switchedOn()) return;
            if (row.getParent() != null) {
                tabsAbove(row);
                return;
            }
            // A row being drawn isn't in its list yet.
            row.post(() -> {
                try {
                    tabsAbove(row);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, HOLD, failure);
                }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, HOLD, failure);
        }
    }

    private static void tabsAbove(View row) {
        ViewParent parent = row.getParent();
        if (!(parent instanceof View)) return;
        synchronized (looked) {
            if (looked.put((View) parent, Boolean.TRUE) != null) return;
        }
        if (stripId == -1) stripId = row.getResources().getIdentifier(TAB_STRIP, "id", Utils.resourcePackage(row.getContext()));
        if (stripId == 0) return;
        ViewParent at = parent;
        for (int level = 0; level < MOST_LEVELS && at instanceof View; level++, at = at.getParent()) {
            View strip = ((View) at).findViewById(stripId);
            if (strip != null) {
                holdTabs(strip);
                return;
            }
        }
    }

    /** Has holding each tab in [strip], every clickable view in it, open the choice. */
    static int holdTabs(View strip) {
        int tabs = 0;
        if (strip.isClickable()) {
            strip.setOnLongClickListener(HOLD_TAB);
            tabs++;
        }
        if (strip instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) strip;
            for (int child = 0; child < group.getChildCount(); child++) tabs += holdTabs(group.getChildAt(child));
        }
        return tabs;
    }

    /** A tab was held. True once the choice is open. False leaves the hold to Instagram. */
    static boolean held(View tab) {
        try {
            if (tab == null || !switchedOn()) return false;
            Activity activity = activityOf(tab.getContext());
            if (activity == null) activity = Utils.getActivity();
            if (activity == null) return false;
            ask(activity);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, HOLD, failure);
            return false;
        }
    }

    private static void ask(Activity activity) {
        CharSequence[] choices = {
                L10n.t(activity, "Everyone"), L10n.t(activity, "Public accounts"), L10n.t(activity, "Private accounts"),
        };
        new AlertDialog.Builder(activity)
                .setTitle(L10n.t(activity, "Show in follow lists"))
                .setSingleChoiceItems(choices, shown, (dialog, which) -> {
                    dialog.dismiss();
                    show(which);
                })
                .show();
    }

    /** Shows [choice] in every follow list, says so, and has the lists open rebuilt. */
    static void show(int choice) {
        if (choice < EVERYONE || choice > PRIVATE) return;
        shown = choice;
        Utils.showToastShort(choice == PUBLIC ? L10n.t("Showing public accounts")
                : choice == PRIVATE ? L10n.t("Showing private accounts") : L10n.t("Showing everyone"));
        List<Object> open;
        synchronized (lists) {
            open = new ArrayList<>(lists.keySet());
        }
        for (Object list : open) {
            try {
                rebuilder.rebuild(list);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.FRIENDSHIP_STATUS, FILTER, failure);
            }
        }
    }

    /** Which accounts the lists show. */
    static int shown() {
        return shown;
    }

    /** Back to everyone, with no list or row remembered, as a restart leaves it, for tests. */
    static void forget() {
        shown = EVERYONE;
        synchronized (lists) {
            lists.clear();
        }
        synchronized (looked) {
            looked.clear();
        }
        stripId = -1;
    }

    @Nullable
    private static Activity activityOf(@Nullable Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Whether the switch is on: off while paused and before the settings are read. */
    static boolean switchedOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.FOLLOW_LIST_FILTER.get();
    }
}
