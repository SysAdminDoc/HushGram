/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.lang.ref.WeakReference;

/**
 * The check mark in a chat's header while the seen receipt is held (#458). It sits first in the row
 * of call buttons, the way the chat history's clock does, and shows only while a receipt of the chat
 * on screen is held ({@link ThreadSeen#hasHeld}). A tap lets that receipt go
 * ({@link ThreadSeen#markOpenChatRead}), so the other person sees Seen, and the check mark goes until
 * the next receipt of the chat is held.
 *
 * <p>The chat on screen is the one the chat lock read when it came to the front. Without it, the
 * newest receipt held since the chat came to the front counts, since Instagram sends a chat's
 * receipts only while that chat is open.
 */
public final class MarkReadButton {
    static final String TAG = "hushgram_mark_read";
    static final String[] ICONS = {"instagram_check_pano_outline_24", "instagram_check_outline_24",
            "instagram_circle_check_pano_outline_24", "instagram_circle_check_outline_24"};
    static final String STEP = "mark as read button";

    /** Looks for the header's row this many times, this far apart, since Instagram builds it after the chat comes up. */
    private static final int TRIES = 24;
    private static final long EVERY_MS = 600;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile WeakReference<Object> screen = new WeakReference<>(null);
    private static volatile long openedAt;
    private static volatile WeakReference<ImageView> button = new WeakReference<>(null);
    private static Watch watch;

    private static final View.OnClickListener TAP = new View.OnClickListener() {
        @Override public void onClick(View v) {
            try {
                ThreadSeen.markOpenChatRead(ChatLocks.currentAccount(), ChatLocks.current(), openedAt);
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
            }
            refresh();
        }
    };

    /** Puts the check mark back when Instagram empties the header's row while the chat is still up. */
    private static final View.OnAttachStateChangeListener KEEP = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) {
        }

        @Override public void onViewDetachedFromWindow(final View v) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (v.getParent() != null || !headerWanted()) return;
                        Object fragment = screen.get();
                        View root = fragment == null ? null : ChatPager.viewOf(fragment);
                        if (root != null && placeAll(root) != null) log("check mark put back, the header's row was emptied");
                    } catch (Throwable t) {
                        HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
                    }
                }
            });
        }
    };

    private MarkReadButton() {
    }

    /** Asked first when a chat comes to the front. */
    public static void opened(Object fragment) {
        try {
            HookStatus.invoked(FamilyNames.THREAD_SEEN);
            screen = new WeakReference<>(fragment);
            openedAt = System.currentTimeMillis();
            stopWatching();
            if (!headerWanted()) return;
            watch = new Watch(fragment);
            watch.run();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
        }
    }

    /** Asked first when a chat leaves the front. A chat that already gave way to another is left alone. */
    public static void closed(Object fragment) {
        try {
            Object shown = screen.get();
            if (shown != null && shown != fragment) return;
            screen = new WeakReference<>(null);
            stopWatching();
            View ours = button.get();
            button = new WeakReference<>(null);
            ViewParent parent = ours == null ? null : ours.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(ours);
            SeenChoiceButton.remove();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
        }
    }

    /** Told when a receipt is held or let go, from any thread. Shows or hides the check mark on the main thread. */
    static void held() {
        MAIN.post(MarkReadButton::refresh);
    }

    /** Shows the check mark while a receipt of the chat on screen is held, and hides it otherwise. */
    static void refresh() {
        try {
            ImageView ours = button.get();
            if (ours != null) ours.setVisibility(wanted() ? View.VISIBLE : View.GONE);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
        }
    }

    private static boolean wanted() {
        String account = ChatLocks.currentAccount();
        String thread = ChatLocks.current();
        return screen.get() != null && ThreadSeen.showsButton(account, thread)
                && ThreadSeen.hasHeld(account, thread, openedAt);
    }

    /** Whether the header gets anything of ours: the check mark, or the eye of Choose Seen in each chat. */
    private static boolean headerWanted() {
        return ThreadSeen.anyShowsButton() || ThreadSeen.choosesPerChat();
    }

    /**
     * Puts what the header gets in its row of call buttons: the eye while Choose Seen in each chat is
     * on, and the check mark first while a chat can show it. The check mark, else the eye, else null.
     */
    static View placeAll(View root) {
        View eye = ThreadSeen.choosesPerChat() ? SeenChoiceButton.place(root, KEEP) : null;
        View check = ThreadSeen.anyShowsButton() ? place(root) : null;
        return check != null ? check : eye;
    }

    /**
     * Puts the check mark first in the header's row of call buttons, or finds the one there, and shows
     * it while there's a receipt to let go. Null when the row or Instagram's glyph isn't there.
     */
    static ImageView place(View root) {
        View found = ChatPager.nearest(root, ChatPager.BUTTON_ROW);
        if (!(found instanceof LinearLayout)) return null;
        LinearLayout row = (LinearLayout) found;
        View had = row.findViewWithTag(TAG);
        ImageView ours;
        if (had instanceof ImageView) {
            ours = (ImageView) had;
        } else {
            Drawable glyph = ChatPager.icon(row.getContext(), ICONS);
            if (glyph == null) return null;
            ours = ChatPager.addHeaderButton(row, glyph, L10n.t("Mark as read"), TAG, TAP, KEEP);
        }
        button = new WeakReference<>(ours);
        ours.setVisibility(wanted() ? View.VISIBLE : View.GONE);
        return ours;
    }

    private static void stopWatching() {
        Watch old = watch;
        if (old != null) MAIN.removeCallbacks(old);
        watch = null;
    }

    private static void log(final String line) {
        Logger.diagnosticDebug(DiagnosticCategory.OTHER, STEP, () -> line);
    }

    /** Looks at the chat's header through its first seconds, since Instagram can build it again. */
    private static final class Watch implements Runnable {
        private final Object fragment;
        private int tries;
        private View placed;

        Watch(Object fragment) {
            this.fragment = fragment;
        }

        @Override public void run() {
            if (watch != this || screen.get() != fragment) return;
            try {
                View root = ChatPager.viewOf(fragment);
                View shown = root == null ? null : placeAll(root);
                if (shown != null && shown != placed) {
                    log("check mark set in the header, try " + tries + (placed == null ? "" : ", header was rebuilt"));
                    placed = shown;
                }
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.THREAD_SEEN, STEP, t);
                return;
            }
            if (++tries < TRIES) {
                MAIN.postDelayed(this, EVERY_MS);
            } else if (placed == null) {
                log("no check mark: the header's call buttons row was never found");
            }
        }
    }

    /** Forgets the chat on screen and its check mark. For tests. */
    static void resetForTests() {
        stopWatching();
        screen = new WeakReference<>(null);
        button = new WeakReference<>(null);
        openedAt = 0;
        SeenChoiceButton.resetForTests();
    }
}
