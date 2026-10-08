/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.content.Context;
import java.util.List;
import app.hushgram.extension.instagram.settings.SettingsStatus;

/**
 * The common comment menu's one call. Each comment family patched in adds its own row, with its own
 * switch and its own row type, in turn.
 */
public final class CommentActions {
    private CommentActions() {}

    public static List<?> rows(List<?> rows, Object comment, Context context) {
        return dispatch(rows, SettingsStatus.commentCopy(), SettingsStatus.commentPhoto(),
                list -> CommentAuthor.rows(CommentCopy.rows(list, comment, context), comment, context),
                list -> CommentPhoto.rows(list, comment, context));
    }

    interface Action {
        List<?> rows(List<?> rows);
    }

    static List<?> dispatch(List<?> rows, boolean copy, boolean photo, Action copyRows, Action photoRows) {
        if (copy) rows = copyRows.rows(rows);
        if (photo) rows = photoRows.rows(rows);
        return rows;
    }
}
