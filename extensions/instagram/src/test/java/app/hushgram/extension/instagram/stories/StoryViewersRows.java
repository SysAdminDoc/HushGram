/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;

import java.util.LinkedHashMap;

/** Keeps pages of story viewers for tests in other packages, where the hook can't reach them. */
public final class StoryViewersRows {
    private StoryViewersRows() { }

    /** One page of [story]'s viewers, posted at [posted] out of [total], given as id then username pairs, kept at [now]. */
    public static void keep(Context context, String story, long posted, int total, long now, String... idThenName) {
        LinkedHashMap<String, String> viewers = new LinkedHashMap<>();
        for (int at = 0; at < idThenName.length; at += 2) viewers.put(idThenName[at], idThenName[at + 1]);
        StoryViewers.keep(context, new StoryViewers.Page(story, posted, total, viewers), now);
    }

    /** Forgets what this process holds, as a new process would. */
    public static void forget() {
        StoryViewers.forgetForTests();
    }
}
