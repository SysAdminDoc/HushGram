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

    public static void add(Context context, ViewingHistory.Kind kind, String id, String owner, String picture, long time) {
        ViewingHistory.add(context, kind, id, owner, picture, time);
    }

    public static ViewingHistory.Row row(ViewingHistory.Kind kind, String id, String owner) {
        return new ViewingHistory.Row(0L, kind, id, owner);
    }

    /** Has every picture fetch answer [bitmap] and put its address in [asked]. Answers what puts the real fetch back. */
    public static Runnable fetchAnswering(android.graphics.Bitmap bitmap, java.util.Set<String> asked) {
        ViewingHistory.Fetch original = ViewingHistory.fetch;
        ViewingHistory.fetch = (context, url, size) -> {
            asked.add(url);
            return bitmap;
        };
        return () -> ViewingHistory.fetch = original;
    }
}
