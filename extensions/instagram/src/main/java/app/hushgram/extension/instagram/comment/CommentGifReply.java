/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import kotlin.jvm.functions.Function0;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A GIF reply in the selected comment's menu (#402): the GIF the comment carries, sent back as a reply
 * to it through Instagram's own Reply and GIF send, so it goes up animated like one picked in
 * Instagram's GIF search. A saved GIF picked from the gallery goes up as a still. It sits after
 * Save, and has its own switch.
 */
public final class CommentGifReply {
    private CommentGifReply() {}

    interface NativeRows {
        /** The GIF [comment] carries of its own, or null. */
        Object gif(Object comment);
        Object row(Object callback);
        Object callback(Object row);
    }

    /** The comment screen's own steps, in the order a reply takes them. */
    interface Composer {
        Object info(Object gif);
        Object target(Object screen);
        void reply(Object screen, String id, String parent);
        void send(Object screen, Object activity, Object info);
    }

    private static final NativeRows NATIVE = new NativeRows() {
        public Object gif(Object comment) { return CommentPhoto.gif(comment); }
        public Object row(Object callback) { return CommentGifReplyNative.newRow(callback); }
        public Object callback(Object row) { return CommentGifReplyNative.callback(row); }
    };

    private static final Composer COMPOSER = new Composer() {
        public Object info(Object gif) { return CommentGifReplyNative.info(gif); }
        public Object target(Object screen) { return CommentGifReplyNative.target(screen); }
        public void reply(Object screen, String id, String parent) { CommentGifReplyNative.reply(screen, id, parent); }
        public void send(Object screen, Object activity, Object info) { CommentGifReplyNative.send(screen, activity, info); }
    };

    // What the diagnostic report counts for a reply, fixed text only.
    static final String SENT = "GIF reply sent";
    static final String NO_TARGET = "GIF reply didn't take the comment";

    /** The comment last selected, the screen it was selected on and the two IDs Instagram resolved it from. */
    static final class Selection {
        final WeakReference<Object> screen;
        final WeakReference<Object> comment;
        final String id;
        final String parent;

        Selection(Object screen, Object comment, String id, String parent) {
            this.screen = new WeakReference<>(screen);
            this.comment = new WeakReference<>(comment);
            this.id = id;
            this.parent = parent;
        }
    }

    private static volatile Selection latest;

    /**
     * Called by the comment screen right after it resolves the comment a long press selected, before
     * that comment's menu is built. The menu's rows are only handed the comment, so this keeps the
     * screen and the IDs Instagram's Reply takes.
     */
    public static void selecting(Object screen, Object comment, String id, String parent) {
        try {
            latest = screen == null || comment == null ? null : new Selection(screen, comment, id, parent);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "gif reply selection", failure);
        }
    }

    public static List<?> rows(List<?> rows, Object comment, Context context) {
        return rows(rows, comment, context, NATIVE, COMPOSER);
    }

    static List<?> rows(List<?> rows, Object comment, Context context, NativeRows nativeRows, Composer composer) {
        try {
            if (rows == null || context == null || comment == null || !enabled()) return rows;
            // Only the comment this menu is for: an older selection would reply to someone else.
            Selection selection = latest;
            Object gif = selection != null && selection.comment.get() == comment ? nativeRows.gif(comment) : null;
            int owned = 0;
            ReplyAction existing = null;
            for (Object row : rows) {
                Object callback = nativeRows.callback(row);
                if (callback instanceof ReplyAction) {
                    owned++;
                    existing = (ReplyAction) callback;
                }
            }
            if (gif == null) {
                if (owned == 0) return rows;
                List<Object> cleaned = new ArrayList<>(rows.size() - owned);
                for (Object row : rows) {
                    if (!(nativeRows.callback(row) instanceof ReplyAction)) cleaned.add(row);
                }
                return cleaned;
            }
            if (owned == 1 && existing.selection == selection && existing.gif == gif) return rows;
            Object reply = nativeRows.row(new ReplyAction(selection, gif, context, composer));
            if (reply == null) return rows;
            List<Object> augmented = new ArrayList<>(rows.size() + 1);
            for (Object row : rows) {
                if (!(nativeRows.callback(row) instanceof ReplyAction)) augmented.add(row);
            }
            augmented.add(reply);
            return augmented;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "gif reply row", failure);
            return rows;
        }
    }

    /** The Activity [context] belongs to, through any wrappers around it, or null. */
    static Activity activity(Context context) {
        for (int depth = 0; context != null && depth < 16; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static boolean enabled() {
        return Utils.settingsReady() && Settings.REPLY_WITH_COMMENT_GIFS.get();
    }

    /** Holds the selection, the comment's GIF and the short-lived menu's context. */
    public static final class ReplyAction implements Function0<Object> {
        final Selection selection;
        final Object gif;
        final Context context;
        final Composer composer;

        ReplyAction(Selection selection, Object gif, Context context, Composer composer) {
            this.selection = selection;
            this.gif = gif;
            this.context = context;
            this.composer = composer;
        }

        /**
         * Instagram's Reply on the comment first, then its GIF send, which answers whatever the comment
         * box replies to. Reply makes a new target each time it takes a comment, so the same target as
         * before means it didn't, and nothing is sent. Instagram ignores this result, then dismisses
         * its menu using the stock callback.
         */
        @Override public Object invoke() {
            try {
                if (!enabled()) return null;
                Object screen = selection.screen.get();
                Activity activity = activity(context);
                Object info = composer.info(gif);
                if (screen == null || activity == null || info == null) {
                    failed();
                    return null;
                }
                Object before = composer.target(screen);
                composer.reply(screen, selection.id, selection.parent);
                Object target = composer.target(screen);
                if (target == null || target == before) {
                    HookStatus.counted(FamilyNames.COMMENT_PHOTO, NO_TARGET);
                    failed();
                    return null;
                }
                composer.send(screen, activity, info);
                HookStatus.counted(FamilyNames.COMMENT_PHOTO, SENT);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_PHOTO, "gif reply", failure);
                failed();
            }
            return null;
        }

        private void failed() {
            try {
                Utils.showToastShort(L10n.t(context, "Couldn't reply with this GIF"));
            } catch (Throwable feedbackFailure) {
                HookStatus.threw(FamilyNames.COMMENT_PHOTO, "gif reply feedback", feedbackFailure);
            }
        }
    }
}
