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
 * Helper for the comment box switch of the "Hide comments" patch (#762): keeps the box at the
 * bottom of a post's or reel's comments off the screen, your picture, the text field and Post with it.
 *
 * <p>On 456 the comment sheet stacks its list, the area above the box and the box's row in one
 * column, and the row has an id of its own. {@link ViewHider} sets that row gone before the frame
 * that would show it, so the list takes the space. The newer box some sheets draw with Compose has
 * an id of its own too and goes the same way. No Instagram code changes, so the switch needs no
 * restart. With it on there is nowhere to type a comment; turning it off brings the box back the
 * next time comments are opened, since a sheet builds its box anew.
 */
public final class CommentBox {
    /** The row holding the box, and the Compose box newer sheets show in its place. */
    static final String ROW = "comment_composer_parent_updated";
    static final String COMPOSE = "comment_composer_compose_view";

    static final String HOOK = "comment box";
    static final String COUNT_HIDDEN = "comment boxes hidden";

    private static final ViewHider HIDER = new ViewHider(FamilyNames.HIDE_COMMENTS, HOOK, COUNT_HIDDEN,
            () -> Settings.HIDE_COMMENT_BOX.get(), null, ROW, COMPOSE);

    private CommentBox() {
    }

    /** Called as the application starts. Does nothing in a build without Hide comments. */
    public static void install(Context context) {
        HIDER.install(context, SettingsStatus.hideComments());
    }

    /** The switch was flipped in HushGram settings, a dialog over [activity]: the screen under it decides now. */
    public static void switchChanged(@Nullable Activity activity) {
        HIDER.switchChanged(activity);
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
