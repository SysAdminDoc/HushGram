/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the Save in the post menu patch (#569): Save, or Remove from saved on a post you saved
 * already, in the menu behind a feed post's three dots, and a switch that takes the Save button off
 * the bottom of feed posts so a stray tap can't save one.
 *
 * <p>The row is Instagram's own Save or Unsave option, added where the feed menu's builder starts
 * someone else's rows and kept by the short menu's list of options, the way Hide posts from this
 * account is. Instagram's menu handles a tap on either itself, so it saves to the same place the
 * button does and shows its usual confirmation. Whether the post is saved is read the way
 * Instagram's older menu reads it. The button is hidden by {@link ViewHider}, only inside a feed
 * post's row of buttons, and so is the Like button with its own switch (#668). The like count goes
 * through {@link #likeCountState} as the row's state is built, so Instagram leaves it out.
 *
 * <p>Instagram's builder adds its own Save or Unsave row to some posts' menus, suggested posts
 * among them, after the spot the row goes in. The short menu used to drop that one, but it keeps
 * Save now, so {@link #settle} takes the added row back out wherever Instagram built its own.
 */
public final class FeedSaveRow {
    /** The row of buttons under a feed post, and its Save button. */
    static final String BUTTONS = "row_feed_view_group_buttons";
    static final String SAVE_BUTTON = "row_feed_button_save";
    /** The Like button in that row and the count beside it (#668). */
    static final String LIKE_BUTTON = "row_feed_button_like";
    static final String LIKE_COUNT = "row_feed_like_count";

    static final String HOOK = "feed save row";
    static final String COUNT_OFFERED = "save rows added";
    static final String COUNT_HIDDEN = "feed save buttons hidden";
    static final String COUNT_LIKE_HIDDEN = "feed like buttons hidden";
    static final String COUNT_SETTLED = "save rows left to Instagram's own";

    /** The row {@link #offer} added on this thread, until the builder hands its rows back. */
    private static final ThreadLocal<Object> OFFERED = new ThreadLocal<>();
    /** A row class and option type's field holding the option, or no field where it has none. */
    private static final Map<List<Class<?>>, Field[]> OPTION_FIELDS = new ConcurrentHashMap<>();

    private static final ViewHider HIDER = new ViewHider(FamilyNames.FEED_SAVE, "feed save button", COUNT_HIDDEN,
            () -> Settings.HIDE_FEED_SAVE_BUTTON.get(), BUTTONS, SAVE_BUTTON);

    /**
     * Hide the Like button (#668): the heart and its count, only inside a feed post's row of buttons.
     * Instagram's ids sit on views inside the tappable one, so the row's child holding them goes,
     * and the heart can't be tapped where it was.
     */
    private static final ViewHider LIKE_HIDER = new ViewHider(FamilyNames.FEED_SAVE, "feed like button",
            COUNT_LIKE_HIDDEN, () -> Settings.HIDE_FEED_LIKE_BUTTON.get(), BUTTONS, LIKE_BUTTON, LIKE_COUNT)
            .holders();
    static final String COUNT_LIKE_COUNT_OFF = "feed like counts left out";

    private FeedSaveRow() {
    }

    /** The post a feed menu's builder is building rows for, read off its state. The patch writes the body. */
    public static Object menuMedia(Object menu) {
        return null;
    }

    /** Adds a labeled row made from [option] to [rows] through the builder's own adder. The patch writes the body. */
    public static void addRow(Object menu, ArrayList<?> rows, Object option, CharSequence label) {
    }

    /** Instagram's Save option. The patch writes the body. Null as built. */
    public static Object saveOption() {
        return null;
    }

    /** Instagram's Unsave option. The patch writes the body. Null as built. */
    public static Object unsaveOption() {
        return null;
    }

    /** Non-zero when [media] is saved, read as Instagram reads it. The patch writes the body. */
    public static int saved(Object media) {
        return 0;
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        HIDER.install(context, SettingsStatus.feedSave());
        LIKE_HIDER.install(context, SettingsStatus.feedSave());
    }

    /** A switch was flipped in HushGram settings, a dialog over [activity]: the screen under it decides now. */
    public static void switchChanged(@Nullable Activity activity) {
        HIDER.switchChanged(activity);
        LIKE_HIDER.switchChanged(activity);
    }

    /**
     * Injected right before Feed's action-row state stores whether its row shows the like count,
     * handed over as an int that reads any non-zero as yes (#668). Answers no while Hide the Like
     * button is on, so the row is laid out without the count, and Instagram's own answer otherwise.
     * Never throws.
     */
    public static boolean likeCountState(int shown) {
        return likeCountState(shown != 0, () -> Utils.settingsReady() && Settings.HIDE_FEED_LIKE_BUTTON.get());
    }

    static boolean likeCountState(boolean shown, BooleanSupplier hide) {
        try {
            HookStatus.invoked(FamilyNames.FEED_SAVE);
            if (!shown || !hide.getAsBoolean()) return shown;
            HookStatus.counted(FamilyNames.FEED_SAVE, COUNT_LIKE_COUNT_OFF);
            return false;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SAVE, HOOK, failure);
            return shown;
        }
    }

    /** Whether the row may show: its switch is on, settings are ready and HushGram isn't paused. */
    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SAVE_IN_POST_MENU.get();
    }

    /**
     * Injected where the feed menu's builder starts someone else's rows. Adds Save, or Remove from
     * saved when the post is saved already, to [rows] when the row may show. Never throws.
     */
    public static void offer(Object menu, ArrayList<?> rows) {
        offer(menu, rows, FeedSaveRow::wanted, FeedSaveRow::menuMedia, media -> saved(media) != 0,
                FeedSaveRow::addRow, FeedSaveRow::saveOption, FeedSaveRow::unsaveOption);
    }

    interface Adder {
        void add(Object menu, ArrayList<?> rows, Object option, CharSequence label);
    }

    static void offer(Object menu, ArrayList<?> rows, BooleanSupplier on, Function<Object, Object> postOf,
            Function<Object, Boolean> savedOf, Adder adder, Supplier<Object> save, Supplier<Object> unsave) {
        OFFERED.remove();
        try {
            if (menu == null || rows == null) return;
            HookStatus.invoked(FamilyNames.FEED_SAVE);
            if (!on.getAsBoolean()) return;
            Object post = postOf.apply(menu);
            if (post == null) return;
            boolean saved = Boolean.TRUE.equals(savedOf.apply(post));
            Object option = saved ? unsave.get() : save.get();
            if (option == null) return;
            int before = rows.size();
            adder.add(menu, rows, option, saved ? L10n.t("Remove from saved") : L10n.t("Save"));
            if (rows.size() == before + 1) OFFERED.set(rows.get(before));
            HookStatus.counted(FamilyNames.FEED_SAVE, COUNT_OFFERED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SAVE, HOOK, failure);
        }
    }

    /**
     * Injected before the feed menu's builder hands back [rows]. Takes the row {@link #offer} added
     * out again when another row carries Save or Unsave, Instagram's own, so the menu shows one.
     * Never throws.
     */
    public static void settle(Object rows) {
        settle(rows, FeedSaveRow::saveOption, FeedSaveRow::unsaveOption, FeedSaveRow::optionOf);
    }

    interface OptionReader {
        Object optionOf(Object row, Object sample) throws ReflectiveOperationException;
    }

    static void settle(Object rows, Supplier<Object> save, Supplier<Object> unsave, OptionReader reader) {
        Object ours = OFFERED.get();
        OFFERED.remove();
        if (ours == null || !(rows instanceof List)) return;
        try {
            List<?> list = (List<?>) rows;
            Object saveOption = save.get();
            Object unsaveOption = unsave.get();
            int at = -1;
            boolean theirs = false;
            for (int index = 0; index < list.size(); index++) {
                Object row = list.get(index);
                // By identity: a row class can count two rows with the same option as equal.
                if (row == ours) {
                    at = index;
                    continue;
                }
                Object option = reader.optionOf(row, saveOption);
                if (option != null && (option == saveOption || option == unsaveOption)) theirs = true;
            }
            if (at < 0 || !theirs) return;
            list.remove(at);
            HookStatus.counted(FamilyNames.FEED_SAVE, COUNT_SETTLED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SAVE, HOOK, failure);
        }
    }

    /**
     * The option [row] carries: the first field of its class, or a superclass, of [sample]'s type,
     * the way Instagram's menu rows hold theirs. Null when it has none.
     */
    static Object optionOf(Object row, Object sample) throws IllegalAccessException {
        if (row == null || sample == null) return null;
        Class<?> type = sample instanceof Enum ? ((Enum<?>) sample).getDeclaringClass() : sample.getClass();
        List<Class<?>> key = Arrays.asList(row.getClass(), type);
        Field[] found = OPTION_FIELDS.get(key);
        if (found == null) {
            found = optionField(row.getClass(), type);
            OPTION_FIELDS.put(key, found);
        }
        return found.length == 0 ? null : found[0].get(row);
    }

    private static Field[] optionField(Class<?> rowType, Class<?> optionType) {
        for (Class<?> at = rowType; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != optionType) continue;
                field.setAccessible(true);
                return new Field[] {field};
            }
        }
        return new Field[0];
    }

    /**
     * Injected before each return of the short feed menu's list of kept options. Answers [options]
     * with Save and Unsave on the end when the row may show, so the short menu keeps the row
     * {@link #offer} added, and as it came otherwise. Never throws.
     */
    public static List<?> allow(List<?> options) {
        return allow(options, FeedSaveRow::wanted, FeedSaveRow::saveOption, FeedSaveRow::unsaveOption);
    }

    static List<?> allow(List<?> options, BooleanSupplier on, Supplier<Object> save, Supplier<Object> unsave) {
        try {
            if (options == null) return null;
            HookStatus.invoked(FamilyNames.FEED_SAVE);
            if (!on.getAsBoolean()) return options;
            List<Object> allowed = null;
            for (Object option : new Object[] {save.get(), unsave.get()}) {
                if (option == null || options.contains(option)) continue;
                if (allowed == null) allowed = new ArrayList<>(options);
                allowed.add(option);
            }
            return allowed == null ? options : allowed;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SAVE, HOOK, failure);
            return options;
        }
    }

    static ViewHider hider() {
        return HIDER;
    }

    static ViewHider likeHider() {
        return LIKE_HIDER;
    }

    public static boolean listeningForTests(Activity activity) {
        return HIDER.listeningForTests(activity) || LIKE_HIDER.listeningForTests(activity);
    }

    public static void resetForTests() {
        HIDER.resetForTests();
        LIKE_HIDER.resetForTests();
        OFFERED.remove();
    }
}
