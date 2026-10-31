/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;

/** Puts rows in the viewing history for tests in other packages, where the real hooks can't reach them. */
public final class ViewingHistoryRows {
    private ViewingHistoryRows() { }

    public static void add(Context context, ViewingHistory.Kind kind, String id, String owner, long time) {
        ViewingHistory.add(context, kind, id, owner, time);
    }
}
