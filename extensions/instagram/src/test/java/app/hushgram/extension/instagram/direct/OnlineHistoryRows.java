/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.content.Context;

import java.util.Collections;

/** Puts active status updates in the online history for tests in other packages, where the hook can't reach them. */
public final class OnlineHistoryRows {
    private OnlineHistoryRows() { }

    /** One update at [now] for the account [id], [username], active or not, last active at [last] (0 when unknown). */
    public static void note(Context context, String id, String username, boolean active, long last, long now) {
        OnlineHistory.record(context, Collections.singletonList(new OnlineHistory.Seen(id, username, active, last)), now);
    }

    /** Forgets what this process holds, as a new process would. */
    public static void forget() {
        OnlineHistory.forgetForTests();
    }
}
