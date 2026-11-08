/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
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
 * post's row of buttons.
 */
public final class FeedSaveRow {
    /** The row of buttons under a feed post, and its Save button. */
    static final String BUTTONS = "row_feed_view_group_buttons";
    static final String SAVE_BUTTON = "row_feed_button_save";

    static final String HOOK = "feed save row";
    static final String COUNT_OFFERED = "save rows added";
    static final String COUNT_HIDDEN = "feed save buttons hidden";

    private static final ViewHider HIDER = new ViewHider(FamilyNames.FEED_SAVE, "feed save button", COUNT_HIDDEN,
            () -> Settings.HIDE_FEED_SAVE_BUTTON.get(), BUTTONS, SAVE_BUTTON);

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
    }

    /** The switch was flipped in HushGram settings, a dialog over [activity]: the screen under it decides now. */
    public static void switchChanged(@Nullable Activity activity) {
        HIDER.switchChanged(activity);
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
        try {
            if (menu == null || rows == null) return;
            HookStatus.invoked(FamilyNames.FEED_SAVE);
            if (!on.getAsBoolean()) return;
            Object post = postOf.apply(menu);
            if (post == null) return;
            boolean saved = Boolean.TRUE.equals(savedOf.apply(post));
            Object option = saved ? unsave.get() : save.get();
            if (option == null) return;
            adder.add(menu, rows, option, saved ? L10n.t("Remove from saved") : L10n.t("Save"));
            HookStatus.counted(FamilyNames.FEED_SAVE, COUNT_OFFERED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SAVE, HOOK, failure);
        }
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

    public static boolean listeningForTests(Activity activity) {
        return HIDER.listeningForTests(activity);
    }

    public static void resetForTests() {
        HIDER.resetForTests();
    }
}
