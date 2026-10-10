/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * "Hide posts from this account" in the menu of a post, under Hide suggested posts: a quick way to
 * put its author on {@link HiddenAccounts} without typing the name.
 *
 * <p>The row belongs to Hide suggested posts, not to Download any video. The patch gives it its own
 * hook in the same feed menu builder, handler and short menu list Download uses, and its own stubs
 * here, so it shows whether or not Download is in the build. It goes on someone else's post only,
 * while Hidden accounts is in the build and HushGram isn't paused. A tap adds the author to the
 * signed-in account's list and says so; Home leaves the post out from its next refresh.
 */
public final class HideAccountRow {
    /** The name of the row's option, made once, as Download's other rows are. */
    static final String OPTION = "HUSHGRAM_HIDE_ACCOUNT";

    /** The report's counts: rows put in a menu, and accounts a tap hid. */
    static final String OFFERED = "hide account rows added";
    static final String HID = "accounts hidden from a menu";

    private static Object option;

    /** Whether Hidden accounts is in the build, when a test says so instead of the build. */
    @Nullable
    static volatile Boolean inBuildForTests;

    private HideAccountRow() {
    }

    /** The post a feed menu's builder is building rows for, read off its state. The patch writes the body. */
    public static Object menuMedia(Object menu) {
        return null;
    }

    /** Adds a labeled row made from [option] to [rows] through the builder's own adder. The patch writes the body. */
    public static void addRow(Object menu, ArrayList<?> rows, Object option, CharSequence label) {
    }

    /** A new menu option named [name], drawn and handled like Download. The patch writes the body. Null as built. */
    public static Object newOption(String name) {
        return null;
    }

    /** The row's option, made once. Null when it can't be made. Never throws. */
    public static synchronized Object option() {
        return option(HideAccountRow::newOption);
    }

    /** Forgets the made option. Tests only. */
    static synchronized void resetForTests() {
        option = null;
        inBuildForTests = null;
    }

    static synchronized Object option(Function<String, Object> maker) {
        try {
            if (option == null) option = maker.apply(OPTION);
            return option;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "hide account option", failure);
            return null;
        }
    }

    /** Whether the row may show: Hidden accounts is in this build, settings are ready and HushGram isn't paused. */
    static boolean on() {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return false;
            Boolean forced = inBuildForTests;
            return forced != null ? forced : PatchFamily.feedTypesInBuild();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "hide account switch", failure);
            return false;
        }
    }

    /**
     * Injected where the feed menu's builder starts someone else's rows. Adds the row to [rows] when
     * it may show and the post's author can be read and isn't on the list already. Never throws.
     */
    public static void offer(Object menu, ArrayList<?> rows) {
        offer(menu, rows, HideAccountRow::menuMedia, HiddenAccounts::authorOfPost, HideAccountRow::addRow,
                HideAccountRow::option);
    }

    interface Adder {
        void add(Object menu, ArrayList<?> rows, Object option, CharSequence label);
    }

    static void offer(Object menu, ArrayList<?> rows, Function<Object, Object> postOf,
            Function<Object, String> authorOf, Adder adder, java.util.function.Supplier<Object> optionOf) {
        try {
            if (menu == null || rows == null || !on()) return;
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            Object post = postOf.apply(menu);
            String name = HiddenAccounts.username(post == null ? null : authorOf.apply(post));
            if (name == null || HiddenAccounts.saved().contains(name)) return;
            Object row = optionOf.get();
            if (row == null) return;
            adder.add(menu, rows, row, L10n.t("Hide posts from this account"));
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, OFFERED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "hide account row", failure);
        }
    }

    /**
     * Injected before each return of the short feed menu's list of kept options. Answers [options]
     * with the row on the end when it may show, and as it came otherwise or when it has it. The
     * menu keeps a row only when its option is on that list, so without this the row {@link #offer}
     * added never shows there. Never throws.
     */
    public static List<?> allow(List<?> options) {
        try {
            if (options == null || !on()) return options;
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            Object row = option();
            if (row == null || options.contains(row)) return options;
            List<Object> allowed = new ArrayList<>(options);
            allowed.add(row);
            return allowed;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "hide account short menu", failure);
            return options;
        }
    }

    /**
     * Injected in the menu's handler of a tapped option, with the post the menu is for and its
     * activity, when the row is tapped. Adds the post's author to the signed-in account's list and
     * says so. Never throws.
     */
    public static void hide(Object media, @Nullable Activity activity) {
        hide(media, HiddenAccounts::authorOfPost);
    }

    static String hide(Object media, Function<Object, String> authorOf) {
        try {
            if (media == null || !on()) return null;
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            String author = authorOf.apply(media);
            String name = HiddenAccounts.add(author);
            if (name == null) {
                Utils.showToastShort(L10n.t("Couldn't hide this account"));
                return null;
            }
            HookStatus.counted(FamilyNames.FEED_SUGGESTIONS, HID);
            Utils.showToastShort(String.format(L10n.t("Posts from @%1$s are hidden from Home and Following"), name));
            return name;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "hide account tap", failure);
            return null;
        }
    }
}
