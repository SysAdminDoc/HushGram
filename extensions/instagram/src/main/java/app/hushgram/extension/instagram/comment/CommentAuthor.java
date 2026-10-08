/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import kotlin.jvm.functions.Function0;
import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Copy username in the selected comment's menu (#35): the username of the account that wrote the
 * comment, copied exactly. It sits after Copy comment, and has its own switch.
 */
public final class CommentAuthor {
    private CommentAuthor() {}

    interface NativeRows {
        String username(Object comment);
        Object row(Object callback);
        Object callback(Object row);
    }

    private static final NativeRows NATIVE = new NativeRows() {
        public String username(Object comment) {
            Object author = CommentAuthorNative.author(comment);
            return author == null ? null : InstagramMedia.username(author);
        }
        public Object row(Object callback) { return CommentAuthorNative.newRow(callback); }
        public Object callback(Object row) { return CommentAuthorNative.callback(row); }
    };

    public static List<?> rows(List<?> rows, Object comment, Context context) {
        return rows(rows, comment, context, NATIVE);
    }

    static List<?> rows(List<?> rows, Object comment, Context context, NativeRows nativeRows) {
        try {
            if (rows == null || context == null || comment == null || !enabled()) return rows;
            String username = nativeRows.username(comment);
            int owned = 0;
            CopyAction existing = null;
            for (Object row : rows) {
                Object callback = nativeRows.callback(row);
                if (callback instanceof CopyAction) {
                    owned++;
                    existing = (CopyAction) callback;
                }
            }
            if (username == null || username.isEmpty()) {
                if (owned == 0) return rows;
                List<Object> cleaned = new ArrayList<>(rows.size() - owned);
                for (Object row : rows) {
                    if (!(nativeRows.callback(row) instanceof CopyAction)) cleaned.add(row);
                }
                return cleaned;
            }
            if (owned == 1 && username.equals(existing.username)) return rows;
            Object copy = nativeRows.row(new CopyAction(username, context));
            if (copy == null) return rows;
            List<Object> augmented = new ArrayList<>(rows.size() + 1);
            for (Object row : rows) {
                if (!(nativeRows.callback(row) instanceof CopyAction)) augmented.add(row);
            }
            augmented.add(copy);
            return augmented;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_COPY, "comment author", failure);
            return rows;
        }
    }

    /**
     * Stands in for the menu renderer's Context.getString call on a row's label. Instagram's rows
     * name their label by a string id, and HushGram adds no strings to Instagram, so the Copy
     * username row borrows Copy's id and gets its own text here. Every other row reads its label
     * exactly as before, exceptions included.
     */
    public static String label(Context context, int id, Object row) {
        if (row instanceof AuthorRow) {
            try {
                return L10n.t(context, "Copy username");
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_COPY, "copy username label", failure);
            }
        }
        return context.getString(id);
    }

    private static boolean enabled() {
        return Utils.settingsReady() && Settings.COPY_COMMENT_AUTHORS.get();
    }

    /** Holds only the username and the short-lived menu's context. */
    public static final class CopyAction implements Function0<Object> {
        final String username;
        final Context context;

        CopyAction(String username, Context context) {
            this.username = username;
            this.context = context;
        }

        /** Instagram ignores this result, then dismisses its menu using the stock callback. */
        @Override public Object invoke() {
            try {
                if (!enabled()) return null;
                Utils.setClipboard(context, L10n.t(context, "Username"), username);
                Utils.showToastShort(L10n.t(context, "Username copied"));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_COPY, "copy username", failure);
                try {
                    Utils.showToastShort(L10n.t(context, "Couldn't copy the username"));
                } catch (Throwable feedbackFailure) {
                    HookStatus.threw(FamilyNames.COMMENT_COPY, "copy username feedback", feedbackFailure);
                }
            }
            return null;
        }
    }
}
