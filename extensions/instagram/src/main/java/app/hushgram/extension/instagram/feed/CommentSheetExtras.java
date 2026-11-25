/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;

/**
 * Helper for two switches of the "Hide comments" patch (#869): the Meta AI search prompt some
 * comment sheets show above the comments (a "Search" pill naming a topic), and the gift button at the
 * right end of the comment box.
 *
 * <p>On 457 the prompt is a view stub in the sheet that inflates to a row with the stub's id, and
 * the gift button has an id of its own in the box's row. {@link ViewHider} sets each gone before the
 * frame that would show it, so the comments move up and the text field takes the button's room. No
 * Instagram code changes, so neither switch needs a restart; turning one off brings its view back
 * the next time comments are opened.
 */
public final class CommentSheetExtras {
    /** The row the prompt's stub inflates to, and the gift button in the comment box's row. */
    static final String PROMPT = "layout_meta_ai_scrollable_component";
    static final String GIFT = "comment_composer_appreciation_gift_button";

    static final String PROMPT_HOOK = "comment search prompt";
    static final String PROMPT_COUNT_HIDDEN = "comment search prompts hidden";
    static final String GIFT_HOOK = "comment gift button";
    static final String GIFT_COUNT_HIDDEN = "comment gift buttons hidden";

    private static final ViewHider PROMPT_HIDER = new ViewHider(FamilyNames.HIDE_COMMENTS, PROMPT_HOOK,
            PROMPT_COUNT_HIDDEN, () -> Settings.HIDE_COMMENT_SEARCH_PROMPT.get(), null, PROMPT);
    private static final ViewHider GIFT_HIDER = new ViewHider(FamilyNames.HIDE_COMMENTS, GIFT_HOOK,
            GIFT_COUNT_HIDDEN, () -> Settings.HIDE_COMMENT_GIFT_BUTTON.get(), null, GIFT);

    private CommentSheetExtras() {
    }

    /** Called as the application starts. Does nothing in a build without Hide comments. */
    public static void install(Context context) {
        boolean built = SettingsStatus.hideComments();
        PROMPT_HIDER.install(context, built);
        GIFT_HIDER.install(context, built);
    }

    /** The prompt's switch was flipped in HushGram settings, a dialog over [activity]. */
    public static void promptSwitchChanged(@Nullable Activity activity) {
        PROMPT_HIDER.switchChanged(activity);
    }

    /** The gift button's switch was flipped in HushGram settings, a dialog over [activity]. */
    public static void giftSwitchChanged(@Nullable Activity activity) {
        GIFT_HIDER.switchChanged(activity);
    }

    static ViewHider promptHider() {
        return PROMPT_HIDER;
    }

    static ViewHider giftHider() {
        return GIFT_HIDER;
    }

    public static void resetForTests() {
        PROMPT_HIDER.resetForTests();
        GIFT_HIDER.resetForTests();
    }
}
