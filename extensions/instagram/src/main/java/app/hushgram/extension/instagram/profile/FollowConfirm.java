/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.Dialog;
import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.feed.ConfirmDialog;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Ask before a follow" patch (#631): a question before a Follow or Follow back
 * button follows someone or sends them a request, while the switch is on.
 *
 * <p>Every follow button Instagram draws, on a profile, in a Followers or Following list or
 * anywhere else, hands its taps to one method of the button's helper. The patch hands that method's
 * arguments to {@link #hold} first thing. The tap is held back while "Follow @username?" is up, and
 * Follow runs the method again, as Instagram would have run it, through {@link #followAgain}, which
 * the patch fills. That second run goes through unasked. Cancel, Back or a tap outside leave the
 * button as it was, since the method returns before it changes anything.
 *
 * <p>Only a tap that would follow is asked about, read through {@link #statusOf} before anything
 * changes: Following and Requested go to Unfollow and to cancelling the request, which Instagram
 * asks about itself. The status is read again when Follow is tapped, so an account followed some
 * other way while the question was up isn't unfollowed by the second run.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet, no
 * screen to ask on or anything thrown, the tap goes through as it always did.
 */
public final class FollowConfirm {
    /** The step a failure is reported under. */
    static final String ASK = "ask before a follow";

    /** What's counted each time a tap waits for the question. */
    static final String ASKED = "asked before a follow";

    /** Instagram's follow status for an account you neither follow nor asked to, as it writes it. */
    static final String NOT_FOLLOWING = "FollowStatusNotFollowing";

    /** The same status's constant name, for a build whose status writes itself differently. */
    static final String NOT_FOLLOWING_NAME = "FOLLOWSTATUSNOTFOLLOWING";

    /** The field of an account's model that holds its username, as the model looks fields up. */
    static final int USERNAME = "username".hashCode();

    /** Set while a tap the person said yes to runs again, so the hook lets it through. Main thread only. */
    private static boolean replaying;

    /** The question that's up, held weakly, since it holds its activity. Main thread only. */
    @Nullable
    private static WeakReference<Dialog> open;

    private FollowConfirm() {
    }

    /** Filled in by the patch: the follow button the helper belongs to, or null unpatched. */
    @Nullable
    static View viewOf(Object helper) {
        return null;
    }

    /** Filled in by the patch: the helper's follow status for [user], read as the tap would read it, or null unpatched. */
    @Nullable
    static Object statusOf(Object helper, Object session, Object user) {
        return null;
    }

    /** Filled in by the patch: the string field [field] of [user]'s model, or null unpatched. */
    @Nullable
    static String usernameOf(Object user, int field) {
        return null;
    }

    /** Filled in by the patch: hands the follow button's tap method what it was handed, again. */
    static void followAgain(Object helper, Object first, Object session, Object media, Object second, Object search,
            Object third, Object user, Object position, String module) {
    }

    /**
     * Injected first thing in the follow button's tap method, with its own arguments. True holds the
     * tap while the question is up, false lets Instagram go on as usual. Never throws.
     */
    public static boolean hold(Object helper, Object first, Object session, Object media, Object second, Object search,
            Object third, Object user, Object position, String module) {
        return hold(() -> screenOf(helper), () -> statusOf(helper, session, user), () -> usernameOf(user, USERNAME),
                () -> followAgain(helper, first, session, media, second, search, third, user, position, module));
    }

    static boolean hold(Supplier<Context> screen, Supplier<Object> status, Supplier<String> username, Runnable again) {
        try {
            HookStatus.invoked(FamilyNames.ASK_BEFORE_FOLLOW);
            if (replaying || !Utils.settingsReady() || !Settings.ASK_BEFORE_FOLLOW.get()) return false;
            if (!wouldFollow(status.get())) return false;
            Context context = screen.get();
            Dialog up = open();
            if (ConfirmDialog.up(up, context)) return true;
            // A question left on another screen, or one that went with its screen, can't be answered here.
            ConfirmDialog.drop(up);
            Dialog question = ConfirmDialog.ask(context, question(username.get()), L10n.t("Follow"),
                    () -> again(status, again), null, FollowConfirm::forget);
            if (question == null) return false;
            open = new WeakReference<>(question);
            HookStatus.counted(FamilyNames.ASK_BEFORE_FOLLOW, ASKED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_FOLLOW, ASK, failure);
            return false;
        }
    }

    /** Whether a tap with this follow status follows or sends a request. */
    static boolean wouldFollow(@Nullable Object status) {
        if (status == null) return false;
        if (NOT_FOLLOWING.equals(status.toString())) return true;
        return status instanceof Enum && NOT_FOLLOWING_NAME.equals(((Enum<?>) status).name());
    }

    /** The question for [username], or a plain one when the account's name couldn't be read. */
    static String question(@Nullable String username) {
        if (username == null || username.isEmpty()) return L10n.t("Follow this account?");
        return L10n.f("Follow @%1$s?", L10n.isolate(username));
    }

    /** The screen the button is on, or the one in front when there's no button to ask. */
    @Nullable
    private static Context screenOf(Object helper) {
        View button = viewOf(helper);
        return button != null ? button.getContext() : Utils.getActivity();
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

    private static void again(Supplier<Object> status, Runnable again) {
        replaying = true;
        try {
            if (wouldFollow(status.get())) again.run();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_FOLLOW, ASK, failure);
        } finally {
            replaying = false;
        }
    }
}
