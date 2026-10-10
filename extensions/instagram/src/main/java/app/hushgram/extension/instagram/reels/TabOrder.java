/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * The Tab order choice: the order the tab bar shows its tabs in.
 *
 * <p>The choice is kept as the tab enum's names, comma separated, first tab first. The tab list hook
 * ({@link ReelsTab#tabs}) hands its list here after the hide switches have had it, and gets back a
 * copy with the named tabs first, in the order chosen, then the rest in Instagram's order. The tab
 * bar's buttons and the swipe between tabs are both built from that one list, and Instagram looks a
 * tab up in it by name, so the two stay in step. A tab the choice names that isn't on the bar is
 * skipped. Nothing changes which tab Instagram opens on: Home's place in the list doesn't make it
 * the start tab, the Start tab choice does.
 *
 * <p>An empty choice, HushGram paused, the settings not read yet, or a list holding anything but
 * tabs, and the list goes through as it came. The list Instagram built is never changed.
 */
public final class TabOrder {
    /** The tabs the choice can order, by their names in Instagram's tab enum, in a stable default order. */
    public static final List<String> TABS = Collections.unmodifiableList(
            Arrays.asList("FEED", "SEARCH", "CLIPS", "DIRECT", "CREATION", "PROFILE"));

    /** What a reordered bar is counted under. */
    static final String APPLIED = "Tab order applied";

    /** The names of the tabs on the bar in Instagram's order, after the hide switches, as last built. Null before. */
    @Nullable
    private static volatile List<String> instagramOrder;

    private TabOrder() {
    }

    /**
     * [tabs] in the saved order, as a copy, or [tabs] itself when there's no order to apply or it
     * leaves the list as it was. Never throws.
     */
    static List<?> apply(List<?> tabs) {
        try {
            remember(tabs);
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return tabs;
            List<?> shown = ordered(tabs, parse(Settings.TAB_ORDER.get()));
            if (shown != tabs) {
                HookStatus.counted(FamilyNames.REELS_TAB, APPLIED);
                Logger.printDebug(() -> "Reels tab: put the bar in the order " + Settings.TAB_ORDER.get());
            }
            return shown;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "tab order", failure);
            return tabs;
        }
    }

    /**
     * A copy of [tabs] with the tabs [order] names first, in that order, then the others in the order
     * they came, or [tabs] itself when that is the order they're in already, [order] is empty, or the
     * list holds something that isn't a tab.
     */
    static List<?> ordered(List<?> tabs, List<String> order) {
        if (order.isEmpty() || tabs.isEmpty()) return tabs;
        for (Object tab : tabs) {
            if (!(tab instanceof Enum)) return tabs;
        }
        List<Object> shown = new ArrayList<>(tabs.size());
        for (String name : order) {
            for (Object tab : tabs) {
                if (((Enum<?>) tab).name().equals(name) && !shown.contains(tab)) shown.add(tab);
            }
        }
        for (Object tab : tabs) {
            if (!shown.contains(tab)) shown.add(tab);
        }
        return shown.equals(tabs) ? tabs : shown;
    }

    /** The tab names in [saved], in order, without blanks, repeats or names the choice doesn't know. */
    public static List<String> parse(@Nullable String saved) {
        if (saved == null || saved.trim().isEmpty()) return Collections.emptyList();
        Set<String> names = new LinkedHashSet<>();
        for (String part : saved.split(",")) {
            String name = part.trim();
            if (TABS.contains(name)) names.add(name);
        }
        return new ArrayList<>(names);
    }

    /** [names] as the saved choice. */
    public static String join(List<String> names) {
        return String.join(",", names);
    }

    /**
     * The tabs for the settings list, in the order the bar shows them with [saved] applied: the tabs
     * on the bar as Instagram last built it, or every tab the choice knows before it has.
     */
    public static List<String> choices(@Nullable String saved) {
        List<String> built = instagramOrder;
        List<String> base = new ArrayList<>();
        if (built != null) {
            for (String name : built) {
                if (TABS.contains(name)) base.add(name);
            }
        }
        if (base.isEmpty()) base.addAll(TABS);
        List<String> chosen = new ArrayList<>();
        for (String name : parse(saved)) {
            if (base.contains(name)) chosen.add(name);
        }
        for (String name : base) {
            if (!chosen.contains(name)) chosen.add(name);
        }
        return chosen;
    }

    /** [names] with the one at [index] moved one place toward the start. The first stays where it is. */
    public static List<String> movedUp(List<String> names, int index) {
        List<String> moved = new ArrayList<>(names);
        if (index > 0 && index < moved.size()) Collections.swap(moved, index, index - 1);
        return moved;
    }

    private static void remember(List<?> tabs) {
        List<String> names = new ArrayList<>(tabs.size());
        for (Object tab : tabs) {
            if (tab instanceof Enum) names.add(((Enum<?>) tab).name());
        }
        instagramOrder = names;
    }

    /** Forgets the bar as last built, for tests, the settings page's included. */
    public static void forgetForTests() {
        instagramOrder = null;
    }
}
