/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;

import androidx.annotation.Nullable;

import java.util.function.Consumer;

import app.hushgram.extension.shared.L10n;

/** The question Ask before a like and Ask before a refresh put up, over the screen they act on. */
final class ConfirmDialog {
    private ConfirmDialog() {
    }

    /**
     * Shows [question] over the activity behind [context], with [yes] and Cancel. [onYes] runs when
     * yes is tapped, and [onNo], when there is one, when the question goes away any other way:
     * Cancel, Back or a tap outside. [onGone] is handed the question once it's gone, whichever way it
     * went, so a caller holding on to it can let go. Answers null, showing nothing, when there's no
     * activity behind [context] or it's going away.
     */
    @Nullable
    static AlertDialog ask(@Nullable Context context, String question, String yes, Runnable onYes, @Nullable Runnable onNo,
            Consumer<AlertDialog> onGone) {
        Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        boolean[] answered = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(question)
                .setPositiveButton(yes, (shown, which) -> {
                    answered[0] = true;
                    onYes.run();
                })
                .setNegativeButton(L10n.t("Cancel"), null)
                .setOnDismissListener(shown -> {
                    try {
                        if (!answered[0] && onNo != null) onNo.run();
                    } finally {
                        onGone.accept((AlertDialog) shown);
                    }
                })
                .create();
        dialog.show();
        return dialog;
    }

    /**
     * Whether [question] is still up over the screen [from] belongs to: showing, over that same
     * activity, which isn't going away. A question whose activity went without dismissing it still
     * says it's showing, and so does one left behind a screen opened over its own, a notification's
     * for one. Neither must hold a tap, since nothing is on screen to answer.
     */
    static boolean up(@Nullable Dialog question, @Nullable Context from) {
        if (question == null || !question.isShowing()) return false;
        Activity activity = activityOf(question.getContext());
        return activity != null && activity == activityOf(from) && !activity.isFinishing() && !activity.isDestroyed();
    }

    /**
     * Takes [question] down when it still says it's showing, so a tap on another screen gets a
     * question of its own. One whose activity went has no window left to take down.
     */
    static void drop(@Nullable Dialog question) {
        if (question == null || !question.isShowing()) return;
        try {
            question.dismiss();
        } catch (IllegalArgumentException detached) {
            // Its window went with its activity.
        }
    }

    /** The activity behind [context], or null when there's none. */
    @Nullable
    static Activity activityOf(@Nullable Context context) {
        Context at = context;
        for (int depth = 0; depth < 10 && at != null; depth++) {
            if (at instanceof Activity) return (Activity) at;
            if (!(at instanceof ContextWrapper)) return null;
            at = ((ContextWrapper) at).getBaseContext();
        }
        return null;
    }
}
