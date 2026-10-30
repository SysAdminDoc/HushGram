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
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Hide the comment emoji row patch (#248): keeps the row of quick emoji Instagram
 * shows above the comment box out of the comment sheet.
 *
 * <p>On 451 the comment sheet draws the strip as one part of the area above its box, beside the
 * "Replying to" bar, and draws it only when it has emoji to show. {@link #hidesRow} answers that
 * check as empty while the switch is on, so the strip isn't drawn and the box closes up under the
 * comments. The other parts of that area are not touched.
 *
 * <p>The older picker some sheets inflate from a ViewStub has its own ids, as does the strip of
 * emoji inside it. {@link ViewHider} sets whichever is on screen gone before the frame that would
 * show it and never touches the stub. The comment box, its Post button and the sheet's other parts
 * are not named and stay.
 */
public final class CommentEmojiRow {
    /** The row the composer inflates from its stub, and the strip of emoji in it. */
    static final String PARENT = "comment_emoji_picker_v1_parent";
    static final String CONTAINER = "comment_emoji_picker_v1_emoji_container";

    static final String HOOK = "comment emoji row";
    static final String COUNT_HIDDEN = "comment emoji rows hidden";

    private static final ViewHider HIDER = new ViewHider(FamilyNames.COMMENT_EMOJI_ROW, HOOK, COUNT_HIDDEN,
            () -> Settings.HIDE_COMMENT_EMOJI_ROW.get(), null, PARENT, CONTAINER);

    private CommentEmojiRow() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        HIDER.install(context, SettingsStatus.commentEmojiRow());
    }

    /** The switch was flipped in HushGram settings, a dialog over [activity]: the screen under it decides now. */
    public static void switchChanged(@Nullable Activity activity) {
        HIDER.switchChanged(activity);
    }

    /**
     * Injected where the comment sheet checks whether it has emoji for the strip above the box, with
     * that check's answer as an int, non-zero for no emoji (BooleanHookParametersTest). True, draw no strip, when there are none or the switch is on. False, as
     * Instagram found, with the switch off, HushGram paused, the settings not read, or anything
     * going wrong. Read each time the sheet lays out, so the switch needs no restart. Never throws.
     */
    public static boolean hidesRow(int empty) {
        if (empty != 0) return true;
        try {
            HookStatus.invoked(FamilyNames.COMMENT_EMOJI_ROW);
            if (!HIDER.wanted()) return false;
            HookStatus.counted(FamilyNames.COMMENT_EMOJI_ROW, COUNT_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_EMOJI_ROW, HOOK, failure);
            return false;
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
