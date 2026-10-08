/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;

/**
 * What the Turn off double tap to like patch asks before a double tap likes a post or a reel.
 *
 * <p>Instagram 449 has two double-tap likes. Each kind of post in the feed (a photo, a carousel, a
 * video and more) has its own gesture delegate, and each hands a double tap to one shared method
 * that plays the big heart and likes the post; the patch asks {@link #holdBackPost} first thing
 * there. The Reels viewer's gesture handler first offers a double
 * tap to its skip and forward gestures, then reads its "on like media" action and likes through it
 * when there is one; the patch puts {@link #likeAction} after that read, and a null answer takes the
 * handler's own path for a viewer with no like action. Double tap to skip and to go forward work as
 * before, and so does a single tap.
 *
 * <p>A comment row's double tap likes or unlikes the comment, and the patch asks
 * {@link #holdBackComment} first thing there.
 *
 * <p>The switch has one under it for posts, one for reels and one for comments, so a double tap can
 * keep liking in some places and not others. The one for comments starts off. The Like button likes through other code, so it goes through, and so does
 * every double tap while the switch or the one for its place is off, HushGram is paused or the
 * settings aren't ready, or when anything in here fails.
 */
public final class DoubleTapLike {
    /** The diagnostic counter route: each double tap that would have liked, and the ones held back. */
    static final String ROUTE = "Double tap likes";

    /** What a double tap held back is counted under. */
    static final String HELD_BACK = "double taps";

    private DoubleTapLike() {
    }

    /**
     * Asked first thing in the feed's double-tap like, which every kind of post calls. True makes it
     * return before the heart or the like. Never throws.
     */
    public static boolean holdBackPost() {
        return holdingBack("post", Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS);
    }

    /**
     * Asked first thing in a comment row's double tap, which likes or unlikes the comment. True makes
     * it return before the like, as it does itself for a comment it can't like. Never throws.
     */
    public static boolean holdBackComment() {
        return holdingBack("comment", Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS);
    }

    /**
     * Handed the Reels gesture handler's "on like media" action as it's read. Null while the switch
     * holds the double tap back, so the handler skips the like and its heart, otherwise the action as
     * it was read. Never throws.
     */
    @Nullable
    public static Object likeAction(@Nullable Object action) {
        return action != null && holdingBack("reel", Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS) ? null : action;
    }

    /** Whether the switch and the one under it for this kind of double tap hold it back. */
    private static boolean holdingBack(String what, BooleanSetting here) {
        try {
            HookStatus.invoked(FamilyNames.DOUBLE_TAP_LIKE);
            FeedFilterCounters.sawList(ROUTE, 1);
            if (!Utils.settingsReady() || !Settings.TURN_OFF_DOUBLE_TAP_LIKE.get() || !here.get()) return false;
            FeedFilterCounters.removed(ROUTE, 1, HELD_BACK);
            Logger.printDebug(() -> "Double tap likes: held back a double tap on a " + what);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DOUBLE_TAP_LIKE, what + " double tap", failure);
            return false;
        }
    }
}
