/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import app.hushgram.extension.shared.Logger;
import java.util.List;

/**
 * Home's "New posts for you" header, which Instagram puts right after a page's first post. The
 * patches that take posts out of Home can leave that page with none.
 */
public final class FeedPageHeader {
    private FeedPageHeader() {
    }

    /** Injected in place of Instagram's insert of the header into [page]. */
    public static void add(List<Object> page, int at, Object header) {
        if (at < 0 || at > page.size()) {
            Logger.printDebug(() -> "Feed page header: left out of a page with no room for it");
            return;
        }
        page.add(at, header);
    }
}
