/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;

import android.app.Dialog;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Ask before a like" patch: a question before the Like button under a post likes
 * or unlikes it, while the switch is on.
 *
 * <p>The patch hands the button's handler and what it was handed to {@link #hold} first thing. The
 * tap is held back while the question is up, and Continue runs the handler again, as Instagram would
 * have run it, through {@link #likeAgain}, which the patch fills. That second run goes through
 * unasked. Cancel, Back or a tap outside leave the post as it was. The handler doesn't know yet
 * whether the post is liked when it starts, so the same question covers an unlike. A double tap
 * goes through other code and isn't asked about. One question is up at a time: a tap that comes
 * before it goes away, a quick second tap on Like, is held without a second question, so one
 * Continue never likes and then unlikes.
 *
 * <p>The question shows over the screen the handler belongs to, found through {@link #contextOf},
 * which the patch fills with its fragment's context.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet, no
 * screen to ask on or anything thrown, the tap goes through as it always did.
 */
public final class LikeConfirm {
    /** The step a failure is reported under. */
    static final String ASK = "ask before a like";

    /** What's counted each time a tap waits for the question. */
    static final String ASKED = "asked before a like";

    /** Set while a tap the person said yes to runs again, so the hook lets it through. Main thread only. */
    private static boolean replaying;

    /** The question that's up, held weakly, since it holds its activity. Main thread only. */
    @Nullable
    private static WeakReference<Dialog> open;

    private LikeConfirm() {
    }

    /** Filled in by the patch: the context of the screen the Like button's handler belongs to, or null unpatched. */
    @Nullable
    static Context contextOf(Object handler) {
        return null;
    }

    /** Filled in by the patch: hands the Like button's handler what it was handed, again. */
    static void likeAgain(Object handler, Object media, Object position, String module, Object done, int index) {
    }

    /**
     * Injected first thing in the Like button's handler, with its own arguments. True holds the tap
     * while the question is up, false lets Instagram go on as usual. Never throws.
     */
    public static boolean hold(Object handler, Object media, Object position, String module, Object done, int index) {
        return hold(() -> contextOf(handler), () -> likeAgain(handler, media, position, module, done, index));
    }

    static boolean hold(Supplier<Context> screen, Runnable again) {
        try {
            HookStatus.invoked(FamilyNames.ASK_BEFORE_LIKE);
            if (replaying || !Utils.settingsReady() || !Settings.ASK_BEFORE_LIKE.get()) return false;
            Context context = screen.get();
            Dialog up = open();
            if (ConfirmDialog.up(up, context)) return true;
            // A question left on another screen, or one that went with its screen, can't be answered here.
            ConfirmDialog.drop(up);
            Dialog question = ConfirmDialog.ask(context, L10n.t("Like or unlike this post?"), L10n.t("Continue"),
                    () -> again(again), null, LikeConfirm::forget);
            if (question == null) return false;
            open = new WeakReference<>(question);
            HookStatus.counted(FamilyNames.ASK_BEFORE_LIKE, ASKED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_LIKE, ASK, failure);
            return false;
        }
    }

    /** The question that's up, or null. */
    @Nullable
    static Dialog open() {
        WeakReference<Dialog> held = open;
        return held == null ? null : held.get();
    }

    /** Lets go of [gone] once it's gone, unless a newer question has taken its place. */
    private static void forget(Dialog gone) {
        Dialog up = open();
        if (up == null || up == gone) open = null;
    }

    private static void again(Runnable again) {
        replaying = true;
        try {
            again.run();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_LIKE, ASK, failure);
        } finally {
            replaying = false;
        }
    }
}
