/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * The Saved screen, for the "Saved on your profile" patch (#762).
 *
 * <p>Instagram has two Saved screens. The menu at the top right of your profile opens the newer one,
 * with All, Collections and the other tabs along the top, on All. Instagram's own Saved link opens the
 * older one, a grid of your collections, where All posts is one more tile to tap. The bookmark on your
 * profile starts that link's screen ({@link ProfileSaved#openSavedScreen}), marked as the bookmark's,
 * and the screen asks {@link #fromBookmark} as it picks what to show: the bookmark's gets what the menu
 * makes instead ({@link #pick}). An account Instagram hasn't given the newer screen gets the grid both
 * ways.
 *
 * <p>The menu's Saved opens over your profile, so its top bar has a back arrow. The link's screen
 * opens it with nothing under it, where Instagram leaves the arrow off, so the bookmark's Saved asks
 * {@link #showBack} and gets the arrow back. It closes the screen like Back does.
 *
 * <p>Both screens put a + at the top right that starts a new collection. Each asks
 * {@link #hideNewCollection} first and leaves the + out when the switch is on. Read each time a screen
 * sets up its top bar, so it takes no restart.
 *
 * <p>The tabbed screen hands the list of its tabs to {@link #tabs} as it makes it, and the Reels tab
 * comes out of it while that switch is on (#810). Saved reels still show under All. The Search tab
 * asks {@link #hideExploreSaved} before it puts the Saved bookmark at the top, and leaves it off while
 * that switch is on. That top bar is made once, so it takes a restart.
 */
public final class SavedHome {
    /** The key in the Saved link's bundle marking it as the bookmark's. */
    static final String FROM_BOOKMARK = "hushgram_saved_from_bookmark";

    /** What's counted each time the + is left off. */
    static final String NEW_COLLECTION_HIDDEN = "New collection buttons hidden";
    /** What's counted each time the bookmark's Saved opens on All. */
    static final String OPENED_ON_ALL = "Saved opened on All";
    /** What's counted each time Saved's Reels tab is left out. */
    static final String REELS_TAB_HIDDEN = "Saved Reels tabs hidden";
    /** What's counted each time the Search tab's Saved is left off. */
    static final String EXPLORE_SAVED_HIDDEN = "Saved on the Search tab hidden";

    /** The name of the Reels tab among Saved's tabs, an enum value Instagram keeps. */
    static final String REELS = "REELS";

    private static volatile boolean logged;
    private static volatile boolean loggedTabs;
    private static volatile boolean loggedExplore;

    /** The screens {@link #pick} gave the bookmark, held weakly: a closed Saved isn't kept for this. */
    private static final Map<Object, Boolean> bookmarks = Collections.synchronizedMap(new WeakHashMap<>());

    private SavedHome() {
    }

    /** Marks [bundle], the Saved link's, as the bookmark's. */
    static void markFromBookmark(Bundle bundle) {
        bundle.putBoolean(FROM_BOOKMARK, true);
    }

    /** Non-zero when [activity], the Saved link's screen, was started by the bookmark on your profile. Never throws. */
    public static int fromBookmark(Activity activity) {
        try {
            Intent intent = activity == null ? null : activity.getIntent();
            Bundle bundle = intent == null ? null : intent.getBundleExtra(ProfileSaved.LINK_BUNDLE);
            return bundle != null && bundle.getBoolean(FROM_BOOKMARK, false) ? 1 : 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "Saved screen", failure);
            return 0;
        }
    }

    /**
     * What the bookmark's Saved shows: [menus], the screen the menu makes, unless it's another of
     * [links] own kind, the grid. Instagram gives an account without the newer screen the grid both
     * ways, and the link's own grid is kept then, set up as the link sets it up.
     */
    public static Object pick(@Nullable Object menus, Object links) {
        try {
            if (menus == null || links == null || menus.getClass() == links.getClass()) return links;
            HookStatus.counted(FamilyNames.SAVED_ON_PROFILE, OPENED_ON_ALL);
            bookmarks.put(menus, Boolean.TRUE);
            return menus;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "Saved screen", failure);
            return links;
        }
    }

    /**
     * Whether [screen], setting up its top bar with nothing under it, shows the back arrow anyway: yes
     * for the bookmark's Saved. Asked only when Instagram would leave the arrow off. Never throws.
     */
    public static boolean showBack(@Nullable Object screen) {
        try {
            return screen != null && bookmarks.containsKey(screen);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "Saved back arrow", failure);
            return false;
        }
    }

    /** Non-zero when the + at the top of Saved is left off. Fails open: off, paused or not read yet, it stays. */
    public static int hideNewCollection() {
        return hideNewCollection(SavedHome::switchedOn);
    }

    static int hideNewCollection(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SAVED_ON_PROFILE);
            if (!on.getAsBoolean()) return 0;
            HookStatus.counted(FamilyNames.SAVED_ON_PROFILE, NEW_COLLECTION_HIDDEN);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Saved: left the New collection button off the top bar");
            }
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "New collection button", failure);
            return 0;
        }
    }

    /**
     * Takes the Reels tab out of [tabs], the tabbed Saved screen's list of its tabs, in place, while
     * the switch is on. A list with no other tab stays whole, so Saved always has a tab to open on.
     * Fails open: off, paused, not read yet or anything unexpected, the list stays. Never throws.
     */
    public static void tabs(@Nullable List<?> tabs) {
        tabs(tabs, () -> switchedOn(Settings.HIDE_SAVED_REELS_TAB));
    }

    /** How many tabs went from [tabs] while [on] says yes. */
    static int tabs(@Nullable List<?> tabs, BooleanSupplier on) {
        if (tabs == null) return 0;
        try {
            HookStatus.invoked(FamilyNames.SAVED_ON_PROFILE);
            if (!on.getAsBoolean()) return 0;
            int kept = 0;
            for (Object tab : tabs) {
                if (!isReels(tab)) kept++;
            }
            if (kept == 0 || kept == tabs.size()) return 0;
            int removed = 0;
            Iterator<?> each = tabs.iterator();
            while (each.hasNext()) {
                if (isReels(each.next())) {
                    each.remove();
                    removed++;
                }
            }
            HookStatus.counted(FamilyNames.SAVED_ON_PROFILE, REELS_TAB_HIDDEN);
            if (!loggedTabs) {
                loggedTabs = true;
                Logger.printDebug(() -> "Saved: left the Reels tab out");
            }
            return removed;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "Saved Reels tab", failure);
            return 0;
        }
    }

    private static boolean isReels(Object tab) {
        return tab instanceof Enum && REELS.equals(((Enum<?>) tab).name());
    }

    /** Non-zero when the Saved bookmark at the top of the Search tab is left off. Fails open: off, paused or not read yet, it stays. */
    public static int hideExploreSaved() {
        return hideExploreSaved(() -> switchedOn(Settings.HIDE_EXPLORE_SAVED));
    }

    static int hideExploreSaved(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SAVED_ON_PROFILE);
            if (!on.getAsBoolean()) return 0;
            HookStatus.counted(FamilyNames.SAVED_ON_PROFILE, EXPLORE_SAVED_HIDDEN);
            if (!loggedExplore) {
                loggedExplore = true;
                Logger.printDebug(() -> "Saved: left the Saved bookmark off the Search tab");
            }
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "Saved on the Search tab", failure);
            return 0;
        }
    }

    private static boolean switchedOn() {
        return switchedOn(Settings.HIDE_SAVED_NEW_COLLECTION);
    }

    private static boolean switchedOn(BooleanSetting setting) {
        return Utils.settingsReady() && !HushgramPause.isPaused() && setting.get();
    }
}
