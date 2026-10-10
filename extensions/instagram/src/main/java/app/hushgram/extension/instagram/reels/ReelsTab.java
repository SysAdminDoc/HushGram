/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * What the Hide the Reels tab patch asks as Instagram builds its tab bar and picks a tab.
 *
 * <p>Instagram 449 keeps its tabs in one enum, which its build still calls IgTab, and Reels is the
 * one named CLIPS. The tab host gets its list of tabs from one method, and the patch hands that list
 * to {@link #tabs} as it's returned, so the tab bar, the swipe between tabs and everything else that
 * reads the list sees it without Reels. Some accounts open on Reels, and Instagram treats Reels as
 * their home tab; the patch hands that answer to {@link #tab}, and every switch to a tab goes
 * through {@link #tab} too, so a start, a notification or a link meant for the Reels tab lands on
 * Home instead.
 *
 * <p>With Show the Reels tab on, a list Instagram built without Reels gets it back right after
 * Home, from the same enum, and Hide the Reels tab wins when both are on.
 *
 * <p>Reels themselves aren't touched. A reel in the feed, a shared reel and the Reels viewer open as
 * before. The list is built as Instagram starts, so a change to the switch shows after a restart.
 * Every tab goes through as it came while the switch is off, HushGram is paused or the settings
 * aren't ready, or when anything in here fails.
 */
public final class ReelsTab {
    /** The Reels tab's name in Instagram's tab enum. */
    static final String REELS = "CLIPS";

    /** The name of the tab a start or a switch meant for Reels goes to instead. */
    static final String HOME = "FEED";

    /** The diagnostic counter route: each tab list Instagram builds, and the Reels tabs taken out of it. */
    static final String ROUTE = "Reels tab";

    /** What a tab taken off the bar is counted under. */
    static final String HIDDEN = "tabs";

    private ReelsTab() {
    }

    /**
     * Handed each tab list Instagram builds, as it's returned. A copy without Reels while the switch
     * hides it, otherwise the list as it came. Never throws, and never answers with an empty list.
     */
    @Nullable
    public static List<?> tabs(@Nullable List<?> tabs) {
        try {
            HookStatus.invoked(FamilyNames.REELS_TAB);
            if (tabs == null) return null;
            FeedFilterCounters.sawList(ROUTE, tabs.size());
            if (!hiding()) return showing() ? withReels(tabs) : tabs;
            List<Object> shown = new ArrayList<>(tabs.size());
            for (Object tab : tabs) {
                if (!isReels(tab)) shown.add(tab);
            }
            int hidden = tabs.size() - shown.size();
            if (hidden == 0 || shown.isEmpty()) return tabs;
            FeedFilterCounters.removed(ROUTE, hidden, HIDDEN);
            Logger.printDebug(() -> "Reels tab: took Reels off a list of " + tabs.size() + " tabs");
            return shown;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "tab list", failure);
            return tabs;
        }
    }

    /**
     * A copy of [tabs] with Reels after Home when the list has no Reels, or [tabs] as it came: when
     * it has Reels already, is empty, holds something that isn't a tab, or its enum has no Reels.
     */
    @Nullable
    static List<?> withReels(List<?> tabs) {
        if (tabs.isEmpty()) return tabs;
        Object first = tabs.get(0);
        if (!(first instanceof Enum)) return tabs;
        int home = -1;
        for (int index = 0; index < tabs.size(); index++) {
            Object tab = tabs.get(index);
            if (!(tab instanceof Enum)) return tabs;
            if (isReels(tab)) return tabs;
            if (HOME.equals(((Enum<?>) tab).name())) home = index;
        }
        Object reels;
        try {
            reels = reelsOf((Enum<?>) first);
        } catch (IllegalArgumentException noReels) {
            return tabs;
        }
        List<Object> shown = new ArrayList<>(tabs);
        shown.add(home + 1, reels);
        Logger.printDebug(() -> "Reels tab: put Reels on a list of " + tabs.size() + " tabs");
        return shown;
    }

    /**
     * Handed a tab Instagram is about to open, or the one it treats as home. Home in place of Reels
     * while the switch hides it, otherwise the tab as it came. Never throws.
     */
    @Nullable
    public static Object tab(@Nullable Object tab) {
        try {
            HookStatus.invoked(FamilyNames.REELS_TAB);
            if (!isReels(tab) || !hiding()) return tab;
            Object home = home((Enum<?>) tab);
            Logger.printDebug(() -> "Reels tab: sent Home in place of Reels");
            return home;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "tab switch", failure);
            return tab;
        }
    }

    static boolean isReels(@Nullable Object tab) {
        return tab instanceof Enum && REELS.equals(((Enum<?>) tab).name());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object home(Enum<?> reels) {
        return Enum.valueOf((Class) reels.getDeclaringClass(), HOME);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object reelsOf(Enum<?> any) {
        return Enum.valueOf((Class) any.getDeclaringClass(), REELS);
    }

    private static boolean showing() {
        return Utils.settingsReady() && Settings.SHOW_REELS_TAB.get();
    }

    private static boolean hiding() {
        return Utils.settingsReady() && Settings.HIDE_REELS_TAB.get();
    }
}
