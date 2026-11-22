/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.download.TextGrab;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Copy text in the menu a story's three dots open, for "Copy text in the story menu" (#789).
 *
 * <p>The row rides on Download any story's menu hooks: {@link
 * app.hushgram.extension.instagram.download.StoryDownload#labels} adds it and {@link
 * app.hushgram.extension.instagram.download.StoryDownload#save} hands its tap here.
 *
 * <p>Instagram describes a photo for screen readers in its {@code accessibility_caption}, and the
 * description quotes the words it read in the picture: "May be an image of text that says 'Sale
 * today'". A tap copies those words. When the description quotes none, as for most videos, Google
 * Lens opens on the story once the menu has closed ({@link TextGrab}), where its words can be
 * selected and copied, as the Copy text button on stories does.
 *
 * <p>Nothing here throws into Instagram.
 */
public final class StoryCopyText {
    private StoryCopyText() {
    }

    static final String FAMILY = FamilyNames.STORY_DOWNLOAD;
    static final String HOOK = "story copy text";
    static final String COUNT_COPIED = "story text copied from Instagram's description";
    static final String COUNT_TO_LENS = "story text sent to Lens, no words in Instagram's description";

    /** How long the menu gets to close before Lens copies the screen, so the menu isn't in the picture. */
    static final long LENS_DELAY_MS = 400;

    /** Sends the story [activity] shows to Lens. Replaced by tests. */
    static Consumer<Activity> lens = activity -> TextGrab.grab(null, activity, FAMILY);

    /** Runs a task on the main thread after a delay. Replaced by tests. */
    static BiConsumer<Runnable, Long> later = Utils::runOnMainThreadDelayed;

    /**
     * Each kind of quote Instagram's descriptions use, as an opening mark and the mark that closes
     * it: straight single and double, curly single and double, German low-high in both closing
     * styles, guillemets, and the corner brackets of Chinese and Japanese.
     */
    private static final char[][] QUOTES = {
            {'\'', '\''},
            {'"', '"'},
            {(char) 0x2018, (char) 0x2019},
            {(char) 0x201C, (char) 0x201D},
            {(char) 0x201E, (char) 0x201C},
            {(char) 0x201E, (char) 0x201D},
            {(char) 0x00AB, (char) 0x00BB},
            {(char) 0x300C, (char) 0x300D},
            {(char) 0x300E, (char) 0x300F},
    };

    /**
     * Whether a story's menu gets Copy text: the switch is on, HushGram isn't paused, and Download any
     * story, whose menu hooks carry the row, is in the build. Never throws.
     */
    public static boolean offersRow() {
        try {
            return Utils.settingsReady() && Settings.STORY_COPY_TEXT_IN_MENU.get() && SettingsStatus.storyDownload();
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
            return false;
        }
    }

    /** The menu's row, Copy text, in the app's language. */
    public static String menuLabel() {
        return L10n.t(Utils.getContext(), "Copy text");
    }

    /** Whether [label], a tapped row, is Copy text, however the menu styled it. */
    public static boolean isMenuLabel(@Nullable CharSequence label) {
        return label != null && menuLabel().contentEquals(label);
    }

    /**
     * A tap on Copy text in the menu of the story whose Media is [media]: the words Instagram read in
     * it go to the clipboard, or, when it read none, Lens opens on the story after the menu closes.
     * Never throws.
     */
    public static void copy(@Nullable Object media) {
        try {
            HookStatus.invoked(FAMILY);
            Context context = Utils.getContext();
            String words = media == null ? null : words(InstagramMedia.accessibilityCaption(media));
            if (words != null) {
                Utils.setClipboard(context, L10n.t(context, "Copy text"), words);
                HookStatus.counted(FAMILY, COUNT_COPIED);
                Utils.showToastShort(L10n.t(context, "Copied"));
                return;
            }
            HookStatus.counted(FAMILY, COUNT_TO_LENS);
            Activity activity = Utils.getActivity();
            later.accept(() -> toLens(activity), LENS_DELAY_MS);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    private static void toLens(@Nullable Activity activity) {
        try {
            lens.accept(activity);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, HOOK, failure);
        }
    }

    /**
     * The words [description], Instagram's description of a photo, quotes: from its first opening
     * quote to the last mark that closes that kind of quote, so a quote inside the words stays. A
     * straight quote opens only at the start or after a space or bracket, and closes only at the end
     * or before a space or punctuation, so the apostrophe in a name or in "don't" does neither. Null
     * when it quotes nothing.
     */
    @Nullable
    static String words(@Nullable String description) {
        if (description == null) return null;
        for (int open = 0; open < description.length(); open++) {
            char mark = description.charAt(open);
            for (char[] quote : QUOTES) {
                if (quote[0] != mark || !opens(description, open)) continue;
                for (int close = description.length() - 1; close > open; close--) {
                    if (description.charAt(close) != quote[1] || !closes(description, close)) continue;
                    String words = description.substring(open + 1, close).trim();
                    if (!words.isEmpty()) return words;
                    break;
                }
            }
        }
        return null;
    }

    private static boolean opens(String text, int at) {
        char mark = text.charAt(at);
        if (mark != '\'' && mark != '"') return true;
        if (at == 0) return true;
        char before = text.charAt(at - 1);
        return Character.isWhitespace(before) || before == '(' || before == '[' || before == ':';
    }

    private static boolean closes(String text, int at) {
        char mark = text.charAt(at);
        if (mark != '\'' && mark != '"') return true;
        if (at == text.length() - 1) return true;
        char after = text.charAt(at + 1);
        return Character.isWhitespace(after) || ".,;:!?)]".indexOf(after) >= 0;
    }

    static void resetForTests() {
        lens = activity -> TextGrab.grab(null, activity, FAMILY);
        later = Utils::runOnMainThreadDelayed;
    }
}
