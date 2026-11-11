/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.graphics.Bitmap;

/** Saves profiles for tests in other packages, where the menu row can't reach them. */
public final class SavedProfilesRows {
    private SavedProfilesRows() { }

    /** Saves the account [id] at [time], as its profile showed [username], [name] and the picture at [picture]. */
    public static void save(Context context, String id, String username, String name, String picture, long time) {
        SavedProfiles.save(context, new SavedProfiles.Profile(id, username, name, picture, 0), time);
    }

    /** Has every picture fetch answer [bitmap]. Answers what puts the real fetch back. */
    public static Runnable fetchAnswering(Bitmap bitmap) {
        SavedProfiles.Fetch original = SavedProfiles.fetch;
        SavedProfiles.fetch = (context, url, size) -> bitmap;
        return () -> SavedProfiles.fetch = original;
    }

    /** Forgets what this process holds, as a new process would. */
    public static void forget() {
        SavedProfiles.forgetForTests();
    }

    /** Deletes the list's file and pictures. */
    public static void clear(Context context) {
        SavedProfiles.clearForTests(context);
    }
}
