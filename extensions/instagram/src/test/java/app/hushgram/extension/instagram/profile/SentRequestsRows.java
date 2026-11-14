/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.graphics.Bitmap;

/** Puts requests on the list for tests in other packages, as a follow button showing Requested would. */
public final class SentRequestsRows {
    private SentRequestsRows() { }

    /** [owner]'s request to the account [id], as its button showed [username], [name] and the picture at [picture]. */
    public static void request(String owner, String id, String username, String name, String picture, long time) {
        SentRequests.Worker was = SentRequests.worker;
        SentRequests.worker = task -> {
            task.run();
            return true;
        };
        try {
            SentRequests.seen(new Object(), new Object(), SentRequests.REQUESTED, new SentRequests.Reads() {
                @Override public String owner(Object session) {
                    return owner;
                }

                @Override public Object tree(Object model) {
                    return model;
                }

                @Override public String field(Object model, int field) {
                    if (field == SentRequests.ID) return id;
                    if (field == SentRequests.USERNAME) return username;
                    if (field == SentRequests.FULL_NAME) return name;
                    return null;
                }

                @Override public boolean user(Object tree) {
                    return true;
                }

                @Override public String picture(Object user) {
                    return picture;
                }
            }, () -> true, time);
        } finally {
            SentRequests.worker = was;
        }
    }

    /** Has every picture fetch answer [bitmap]. Answers what puts the real fetch back. */
    public static Runnable fetchAnswering(Bitmap bitmap) {
        SentRequests.Fetch original = SentRequests.fetch;
        SentRequests.fetch = (context, url, size) -> bitmap;
        return () -> SentRequests.fetch = original;
    }

    /** Deletes the list and forgets what this process holds, as a new process would. */
    public static void clear() {
        SentRequests.clear();
        SentRequests.forgetForTests();
    }

    /** Forgets what this process holds, as a new process would. */
    public static void forget() {
        SentRequests.forgetForTests();
    }
}
