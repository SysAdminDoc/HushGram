/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

/**
 * Reaches the feed's video controller and its player for {@link FeedTapPause}.
 *
 * <p>Every method here is a stub the "Tap a feed video to pause it" patch fills in with the build's
 * own fields and calls: the feed tap handler's and the Reels launcher's lazy fields for the feed video
 * module, the module's controller, the controller's player and the item it has playing, and the
 * controller's own pause and play. A stub works in its own parameters, since the build compiles its {@code return} into
 * its parameter's register and leaves it no local to lend, which is why the pause and the play take
 * spare ones.
 *
 * <p>The stubs live here and not in {@link FeedTapPause}, which Instagram's tap handler calls on
 * every tap: a stub the phone's verifier refused would take the whole class down with it. Only
 * {@link FeedTapPause}'s hooks reach this class, inside their tries.
 */
final class FeedPlayerReader {
    private FeedPlayerReader() { }

    /** Filled in by the patch: the feed video controller the tap handler's module holds, or null. */
    @Nullable
    static Object controllerOf(Object handler) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: the same controller, reached from the feed's Reels launcher, or null. */
    @Nullable
    static Object controllerOfLauncher(Object launcher) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: the post the controller's playing item shows, a carousel's page for a carousel, or null. */
    @Nullable
    static Object shownOf(Object controller) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: the page of [media] its [state] is on, the post itself when it has one page. */
    @Nullable
    static Object pageOf(Object media, Object state) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: the post's ad details, which only a sponsored post has. */
    @Nullable
    static Object adDetailsOf(Object media) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: the controller's player, or null while it has none. */
    @Nullable
    static Object playerOf(Object controller) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: a Boolean, whether the player says it's playing. */
    static Object playing(Object player) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: pauses through the controller with Instagram's own tap reason, loaded into [spare]. */
    static Object pause(Object controller, @Nullable Object spare) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: plays through the controller the way its own tap does, with its reason and flags loaded into the spares. */
    static Object start(Object controller, @Nullable Object spare1, @Nullable Object spare2, @Nullable Object spare3) {
        return FeedTapPause.NOT_PATCHED;
    }

    /** Filled in by the patch: tells the post's state whether the video was playing when it was tapped, a Boolean. */
    static Object mark(Object state, Object wasPlaying) {
        return FeedTapPause.NOT_PATCHED;
    }
}
