/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Don't send feed view history" patch (#664).
 *
 * <p>Each Home page Instagram asks {@code feed/timeline/} for carries {@code feed_view_info}: the
 * posts you looked at in Home since the last page, and for how long. It ranks Home with that, and
 * nobody else sees it. Instagram only adds the parameter when its request holds that record, so
 * the patch hands the record here right after it's read, and a null leaves the parameter out.
 */
public final class FeedViewInfo {
    /** The diagnostic counter route: each Home request that held the record, and the ones left out. */
    static final String ROUTE = "Feed view history";

    /** What a request left out is counted under. */
    static final String LEFT_OUT = "posts you looked at";

    private FeedViewInfo() {
    }

    /**
     * Called with the record of what you looked at in Home as Instagram builds a {@code feed/timeline/}
     * request. Null with the switch on, which leaves {@code feed_view_info} out, and the record itself
     * while the switch is off, HushGram is paused or the settings aren't ready. Never throws.
     */
    public static Object held(Object viewInfo) {
        try {
            HookStatus.invoked(FamilyNames.FEED_VIEW_HISTORY);
            if (viewInfo == null) return null;
            FeedFilterCounters.sawList(ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.DONT_SEND_FEED_VIEW_HISTORY.get()) return viewInfo;
            FeedFilterCounters.removed(ROUTE, 1, LEFT_OUT);
            Logger.printDebug(() -> "Feed view history: left the posts you looked at out of a Home request");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_VIEW_HISTORY, "feed view history request", failure);
            return viewInfo;
        }
    }
}
