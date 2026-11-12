/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the Watch again in the feed patch (#568). When a reel in the feed reaches the end of
 * its preview, Instagram covers it with "Watch more reels" and "Watch again", and both open the
 * Reels viewer. Instagram also has a way to replay the reel right there in the feed, behind a server
 * flag its end card controller takes as it's built: on, "Watch again" hides the card and starts the
 * reel over in place. The patch hands that flag over as the controller stores it, and this turns it
 * on while the switch is on. "Watch more reels" still opens Reels.
 *
 * <p>The second switch (#652) takes "Watch more reels" off the card. The patch hands the button over
 * once the controller has shown it and wired its tap, and this hides it. The card's own setup shows
 * it again each time, so turning the switch off brings it back on the next reel that ends.
 */
public final class WatchAgain {
    static final String HOOK = "watch again";
    static final String MORE_HOOK = "watch more reels";

    private WatchAgain() {
    }

    /** Whether "Watch again" replays the reel in the feed: Instagram's own flag, or the switch. */
    public static boolean inFeed(int instagramReplaysInFeed) {
        return inFeed(instagramReplaysInFeed, WatchAgain::wanted);
    }

    static boolean inFeed(int instagramReplaysInFeed, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.WATCH_AGAIN);
            return instagramReplaysInFeed != 0 || on.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.WATCH_AGAIN, HOOK, failure);
            return instagramReplaysInFeed != 0;
        }
    }

    static boolean wanted() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.WATCH_AGAIN_IN_FEED.get();
    }

    /** Hides the end card's "Watch more reels" button while its switch is on. */
    public static void moreReels(View button) {
        moreReels(button, WatchAgain::hidesMoreReels);
    }

    static void moreReels(View button, BooleanSupplier hide) {
        try {
            HookStatus.invoked(FamilyNames.WATCH_AGAIN);
            if (button != null && hide.getAsBoolean()) button.setVisibility(View.GONE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.WATCH_AGAIN, MORE_HOOK, failure);
        }
    }

    static boolean hidesMoreReels() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.HIDE_WATCH_MORE_REELS.get();
    }
}
