/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Three-dot menu items" patch (#697, #707): leaves the items the user unticks out of
 * the menus behind the three dots on feed posts, reels, feed videos and stories.
 *
 * <p>A feed post's menu is built as a list of rows, each holding one of Instagram's options (an
 * enum: {@code REPORT}, {@code MUTE_USER}, {@code UNFOLLOW_USER} and the rest). The patch hands
 * the list to {@link #feedRows} before the builder returns it, and the rows the setting names come
 * out. Reels and feed videos add each row through one method that takes the option, which asks
 * {@link #reelRow} first and adds nothing when the answer is yes.
 *
 * <p>A story's menu is a list of labels with no option behind them, and Instagram's strings live in
 * its own string packs, so a label can't be told by its text. Instagram gives each label its icon
 * through one method, though, and the icons keep their resource names. {@link #storyLabels} asks
 * that method, through {@link #storyIcon}, for each label's icon and drops Report and Mute by the
 * icon's name. The tap listener builds the labels again and picks by position, so it sees the same
 * list.
 *
 * <p>Fails open: with nothing saved, HushGram paused, the settings not read yet or anything thrown,
 * the menu shows as Instagram built it.
 */
public final class MenuItems {
    /** The items the setting can leave out, in the order the list shows them. */
    public static final List<String> OFFERED = Collections.unmodifiableList(Arrays.asList(
            "REPORT", "MUTE", "UNFOLLOW", "NOT_INTERESTED", "INTERESTED", "WHY", "PLAYBACK_SPEED", "PLAYBACK"));

    /** What's counted for each row left out of a menu. */
    static final String LEFT_OFF = "Three-dot menu items left off";
    static final String HOOK = "three-dot menu items";

    /** The story menu the builder on this thread is building labels for. */
    private static final ThreadLocal<WeakReference<Object>> STORY_MENU = new ThreadLocal<>();

    /** The icon names of the story labels this can tell, Instagram's report and mute icons. */
    static final String REPORT_ICON = "instagram_report_outline_24";
    static final String MUTE_ICON = "instagram_alert_off_outline_24";

    private MenuItems() {
    }

    /** Instagram's Report option, the sample a row's option is read against. The patch writes the body. Null as built. */
    public static Object reportOption() {
        return null;
    }

    /** The drawable Instagram's story menu [menu] shows beside [label], or 0. The patch writes the body. */
    public static int storyIcon(Object menu, CharSequence label) {
        return 0;
    }

    /** The item Instagram's option named [option] is, or null when the setting doesn't offer it. */
    @Nullable
    static String itemOf(@Nullable String option) {
        if (option == null) return null;
        switch (option) {
            case "REPORT":
            case "REPORT_AD":
                return "REPORT";
            case "MUTE_USER":
                return "MUTE";
            case "UNFOLLOW_USER":
                return "UNFOLLOW";
            case "NOT_INTERESTED":
                return "NOT_INTERESTED";
            case "INTERESTED":
                return "INTERESTED";
            case "WHY_AM_I_SEEING_THIS":
                return "WHY";
            case "PLAYBACK_SPEED":
                return "PLAYBACK_SPEED";
            case "PLAYBACK_CONTROLS":
                return "PLAYBACK";
            default:
                return null;
        }
    }

    /** The item a story label with the icon named [icon] is, or null. Unmute has an icon of its own and stays. */
    @Nullable
    static String itemOfIcon(@Nullable String icon) {
        if (REPORT_ICON.equals(icon)) return "REPORT";
        if (MUTE_ICON.equals(icon)) return "MUTE";
        return null;
    }

    /**
     * Injected before each return of the feed menu's builder with the rows it built. Takes out the
     * rows whose option the setting names. Never throws.
     */
    public static void feedRows(Object rows) {
        feedRows(rows, MenuItems::hidden, MenuItems::reportOption, FeedSaveRow::optionOf);
    }

    static void feedRows(Object rows, Supplier<Set<String>> hiddenNow, Supplier<Object> sample, FeedSaveRow.OptionReader reader) {
        try {
            if (!(rows instanceof List)) return;
            HookStatus.invoked(FamilyNames.MENU_ITEMS);
            Set<String> hidden = hiddenNow.get();
            if (hidden.isEmpty()) return;
            Object report = sample.get();
            if (report == null) return;
            List<?> list = (List<?>) rows;
            for (int index = list.size() - 1; index >= 0; index--) {
                Object option = reader.optionOf(list.get(index), report);
                if (!(option instanceof Enum<?>)) continue;
                String item = itemOf(((Enum<?>) option).name());
                if (item == null || !hidden.contains(item)) continue;
                list.remove(index);
                HookStatus.counted(FamilyNames.MENU_ITEMS, LEFT_OFF);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_ITEMS, HOOK, failure);
        }
    }

    /**
     * Injected at the start of the method that adds one row to a reel's or feed video's menu, with
     * the row's option. Answers true when the setting names it, and the row isn't added. Never throws.
     */
    public static boolean reelRow(Object option) {
        return reelRow(option, MenuItems::hidden);
    }

    static boolean reelRow(Object option, Supplier<Set<String>> hiddenNow) {
        try {
            if (!(option instanceof Enum<?>)) return false;
            String item = itemOf(((Enum<?>) option).name());
            if (item == null) return false;
            HookStatus.invoked(FamilyNames.MENU_ITEMS);
            if (!hiddenNow.get().contains(item)) return false;
            HookStatus.counted(FamilyNames.MENU_ITEMS, LEFT_OFF);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_ITEMS, HOOK, failure);
            return false;
        }
    }

    /** Injected at the start of each builder of a story's menu labels, with the menu. Never throws. */
    public static void storyMenu(Object menu) {
        STORY_MENU.set(menu == null ? null : new WeakReference<>(menu));
    }

    /**
     * Injected before each return of a story menu's builder with its labels. Answers them without
     * Report and Mute where the setting names them, told by the icon Instagram gives each label.
     * Never throws.
     */
    public static CharSequence[] storyLabels(CharSequence[] labels) {
        WeakReference<Object> held = STORY_MENU.get();
        return storyLabels(labels, held == null ? null : held.get(), MenuItems::hidden, MenuItems::storyIcon,
                icon -> Utils.getContext().getResources().getResourceEntryName(icon));
    }

    interface IconReader {
        int iconOf(Object menu, CharSequence label);
    }

    static CharSequence[] storyLabels(CharSequence[] labels, @Nullable Object menu, Supplier<Set<String>> hiddenNow,
            IconReader icons, IntFunction<String> names) {
        try {
            if (labels == null || menu == null) return labels;
            HookStatus.invoked(FamilyNames.MENU_ITEMS);
            Set<String> hidden = hiddenNow.get();
            if (!hidden.contains("REPORT") && !hidden.contains("MUTE")) return labels;
            List<CharSequence> kept = new ArrayList<>(labels.length);
            for (CharSequence label : labels) {
                String item = label == null ? null : storyItem(menu, label, icons, names);
                if (item != null && hidden.contains(item)) {
                    HookStatus.counted(FamilyNames.MENU_ITEMS, LEFT_OFF);
                    continue;
                }
                kept.add(label);
            }
            return kept.size() == labels.length ? labels : kept.toArray(new CharSequence[0]);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MENU_ITEMS, HOOK, failure);
            return labels;
        }
    }

    /** The item [label] is by its icon, or null when it has none or one this doesn't know. */
    @Nullable
    private static String storyItem(Object menu, CharSequence label, IconReader icons, IntFunction<String> names) {
        int icon = icons.iconOf(menu, label);
        if (icon == 0) return null;
        try {
            return itemOfIcon(names.apply(icon));
        } catch (RuntimeException notAResource) {
            // Resources.NotFoundException: an icon id the app's resources don't hold is none of ours.
            return null;
        }
    }

    /** The items the setting names now, or none while HushGram is paused or the settings aren't read. */
    static Set<String> hidden() {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return Collections.emptySet();
        return parse(Settings.HIDDEN_MENU_ITEMS.get());
    }

    /** The offered names in [saved], without blanks, repeats or anything this doesn't offer. */
    public static Set<String> parse(@Nullable String saved) {
        Set<String> names = new LinkedHashSet<>();
        if (saved == null) return names;
        for (String part : saved.split(",")) {
            String name = part.trim();
            if (OFFERED.contains(name)) names.add(name);
        }
        return names;
    }

    /** [hidden] as the saved value, in the order the items are offered. */
    public static String join(Set<String> hidden) {
        List<String> names = new ArrayList<>();
        for (String name : OFFERED) {
            if (hidden.contains(name)) names.add(name);
        }
        return String.join(",", names);
    }

    public static void resetForTests() {
        STORY_MENU.remove();
    }
}
