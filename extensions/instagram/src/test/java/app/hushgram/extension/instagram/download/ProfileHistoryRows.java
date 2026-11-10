/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;

/** Puts visits in the profile history for tests in other packages, where the real hooks can't reach them. */
public final class ProfileHistoryRows {
    private ProfileHistoryRows() { }

    /** One visit to the profile [id] at [time], showing [username], [bio] and the picture at [picture]. */
    public static void visit(Context context, String id, String username, String bio, String picture, long time) {
        ProfileHistory.record(context, id, username, bio, picture, time);
    }

    /** Has every picture fetch answer [bitmap]. Answers what puts the real fetch back. */
    public static Runnable fetchAnswering(Bitmap bitmap) {
        ProfileHistory.Fetch original = ProfileHistory.fetch;
        ProfileHistory.fetch = (context, url, size) -> bitmap;
        return () -> ProfileHistory.fetch = original;
    }

    /** Forgets what this process holds, as a new process would. */
    public static void forget() {
        ProfileHistory.forgetForTests();
    }
}
