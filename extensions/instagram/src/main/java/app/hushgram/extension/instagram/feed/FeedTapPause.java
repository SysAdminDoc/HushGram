/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Tap a feed video to pause it" patch.
 *
 * <p>Instagram 450 sends every tap on a feed post through one handler, which decides between
 * opening the post in the Reels viewer, showing its tags, turning its sound on and a few more. Only
 * with TalkBack on does the feed video controller it ends in pause and play the video. The patch
 * asks {@link #tapped} first thing in that handler, and a yes ends the handler there.
 *
 * <p>The answer is yes when the post that was tapped is the one the feed video controller has
 * playing, which is the test Instagram's own tap makes: the page of the tapped post its state is
 * on equals the post the controller's playing item shows. Then this runs what Instagram's TalkBack
 * tap runs. A playing video is paused through the controller with Instagram's own tap reason, one
 * that isn't playing is played through the controller with "start", and the post's state is told
 * which it was. The pause leaves the controller's playing item where it is, so the next tap finds
 * the same post and plays it.
 *
 * <p>A press held past Instagram's hold delay never reaches that handler. The delay comes from
 * Instagram's server, so a tap that's a little slow can be one. The feed's gesture listener hands
 * such a press to the handler class's press entry, which opens the post in Reels (or plays it
 * faster, where Instagram has that on), and the lift that ends it to the class's release. So the
 * patch asks {@link #pressed} first thing in the press entry. It makes the same checks without
 * pausing or playing anything, and a yes ends the press entry there and holds on to the post's
 * state. The press's lift is then its tap: {@link #released}, asked first thing in the release,
 * pauses or plays the video the way a tap does. When Instagram's tap handler gets that press as a
 * tap as well, {@link #tapped} pauses or plays it and lets go of the state, so the lift doesn't do
 * it twice. A press a scroll takes over ends without a lift and leaves the video as it was.
 *
 * <p>On Instagram 451 a tap can open Reels without reaching that handler: the video's overlays open
 * Reels on their own, the end screen a finished feed reel shows among them, and Instagram's own tap
 * can be sent to Reels by a server setting. All of them end in the feed's one Reels launcher, which
 * asks {@link #opening} first thing with where it was asked from. A tap on a video (Instagram's
 * {@code VIDEO_TAP}) on the playing post pauses or plays it under the same checks, and a yes ends the
 * launcher there. Every other opening goes on to Reels, the end screen a finished feed reel shows
 * included: its backdrop ({@code BACKGROUND_TAP}), Watch again and Watch more reels. Each opening with
 * the switch on is counted under its entry point's name, so one report says which way a phone's taps
 * come.
 *
 * <p>Everything else is left to Instagram: a sponsored post, a photo, a carousel on a photo, a video
 * the controller doesn't have playing, a feed with no video controller. So is every tap or press while
 * the switch is off, HushGram is paused or the settings aren't ready, and any one this throws on
 * before it has paused or played anything.
 */
public final class FeedTapPause {
    /** What {@link HookStatus} counts: taps that paused the playing video, and taps that played it again. */
    static final String PAUSED = "feed videos paused by a tap";
    static final String PLAYED = "feed videos played by a tap";

    /**
     * What {@link HookStatus} counts: taps with the switch on that went to Instagram, split by the check that sent them
     * there, so one report from a phone names which check a tap failed on.
     */
    static final String LEFT_NO_CONTROLLER = "taps left, no controller";
    static final String LEFT_NOTHING_PLAYING = "taps left, nothing playing";
    static final String LEFT_ANOTHER_POST = "taps left, another post";
    static final String LEFT_AN_AD = "taps left, an ad";
    static final String LEFT_NO_PLAYER = "taps left, no player";
    static final String LEFT_NOT_A_BOOLEAN = "taps left, not a Boolean";

    /**
     * What {@link HookStatus} counts: presses held past Instagram's hold delay that this took, whose tap or lift then
     * paused or played the video, and those with the switch on that went to Instagram.
     */
    static final String PRESS_TAKEN = "held presses taken as taps";
    static final String PRESS_LEFT = "held presses left to Instagram";

    /** What {@link HookStatus} counts for each Reels opening with the switch on, followed by its entry point's name. */
    static final String OPENED_FROM = "Reels openings from ";

    /** The entry point of a tap on a feed video, the one opening {@link #opening} pauses or plays in place of. */
    static final String VIDEO_TAP = "VIDEO_TAP";

    /** The hooks' names in a failure report. */
    static final String TAP = "feed tap";
    static final String PRESS = "feed press";
    static final String RELEASE = "feed press lift";
    static final String OPEN = "feed Reels opening";

    /** What {@link FeedPlayerReader}'s stubs return until the patch fills them in. */
    static final Object NOT_PATCHED = new Object();

    /** The feed's video controller and player, as the patch's stubs reach them. Tests put their own in. */
    interface Videos {
        /** The feed video controller behind the tap [handler], or null. */
        @Nullable
        Object controllerOf(Object handler);

        /** The same controller, behind the feed's Reels [launcher], or null. */
        @Nullable
        Object controllerOfLauncher(Object launcher);

        /** The post the controller's playing item shows, or null while nothing is playing. */
        @Nullable
        Object shownOf(Object controller);

        /** The page of [media] its [state] is on. */
        @Nullable
        Object pageOf(Object media, Object state);

        /** The post's ad details, null for a post that isn't sponsored. */
        @Nullable
        Object adDetailsOf(Object media);

        /** The controller's player, or null. */
        @Nullable
        Object playerOf(Object controller);

        /** A Boolean, whether the player is playing. */
        @Nullable
        Object playing(Object player);

        /** Pauses the controller's video the way Instagram's own tap does. */
        @Nullable
        Object pause(Object controller);

        /** Plays the controller's video the way Instagram's own tap does. */
        @Nullable
        Object start(Object controller);

        /** Tells the post's state whether its video was playing when it was tapped. */
        @Nullable
        Object mark(Object state, boolean wasPlaying);
    }

    /** {@link FeedPlayerReader}'s stubs, which the patch fills in. */
    private static final class Patched implements Videos {
        @Override public Object controllerOf(Object handler) {
            return FeedPlayerReader.controllerOf(handler);
        }

        @Override public Object controllerOfLauncher(Object launcher) {
            return FeedPlayerReader.controllerOfLauncher(launcher);
        }

        @Override public Object shownOf(Object controller) {
            return FeedPlayerReader.shownOf(controller);
        }

        @Override public Object pageOf(Object media, Object state) {
            return FeedPlayerReader.pageOf(media, state);
        }

        @Override public Object adDetailsOf(Object media) {
            return FeedPlayerReader.adDetailsOf(media);
        }

        @Override public Object playerOf(Object controller) {
            return FeedPlayerReader.playerOf(controller);
        }

        @Override public Object playing(Object player) {
            return FeedPlayerReader.playing(player);
        }

        @Override public Object pause(Object controller) {
            return FeedPlayerReader.pause(controller, null);
        }

        @Override public Object start(Object controller) {
            return FeedPlayerReader.start(controller, null, null, null);
        }

        @Override public Object mark(Object state, boolean wasPlaying) {
            return FeedPlayerReader.mark(state, Boolean.valueOf(wasPlaying));
        }
    }

    /** The controller a gesture pauses or plays through, and whether its player was playing. */
    private static final class Target {
        final Object controller;
        final boolean wasPlaying;

        Target(Object controller, boolean wasPlaying) {
            this.controller = controller;
            this.wasPlaying = wasPlaying;
        }
    }

    /**
     * Reaches the reader only through this, from inside a hook's try, so a reader the phone refuses
     * to load costs this gesture its pause and never the gesture itself.
     */
    static Videos videos = new Patched();

    /** The post state of the held press {@link #pressed} took, until the tap or the lift that ends it. */
    @Nullable
    private static volatile WeakReference<Object> held;

    private FeedTapPause() {
    }

    /**
     * Asked first thing in the feed's tap handler, with the handler, the post that was tapped and
     * that post's state. True means the tap paused or played the video and the handler is done.
     * False leaves the tap to Instagram: the switch is off, HushGram is paused, the settings aren't
     * ready, the post is sponsored or isn't the video the controller has playing, the patch's stubs
     * are unfilled, or something in here threw before the video was paused or played. Never throws.
     */
    public static boolean tapped(@Nullable Object handler, @Nullable Object media, @Nullable Object state) {
        try {
            HookStatus.invoked(FamilyNames.FEED_TAP_PAUSE);
            if (handler == null || media == null || state == null) return false;
            if (!switchedOn()) return false;
            HookStatus.bound(FamilyNames.FEED_TAP_PAUSE, TAP);
            // A tap that ends a held press this took is that press's own, which leaves its lift nothing to do.
            letGo(state);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_TAP_PAUSE, TAP, failure);
            return false;
        }
        return toggle(feed -> feed.controllerOf(handler), media, state, TAP);
    }

    /**
     * Asked first thing in the press entry of the feed's tap handler class, which a press held past
     * Instagram's hold delay reaches in place of the tap handler, with the handler, the post and its
     * state. True takes the press: nothing is paused or played yet, the press entry returns, and the
     * post's state is held for the tap or the lift that ends the press. False leaves the press to
     * Instagram, on the same grounds as {@link #tapped}. Not counted as invoked, so a report's
     * invoked stays the taps that reached the tap handler. Never throws.
     */
    public static boolean pressed(@Nullable Object handler, @Nullable Object media, @Nullable Object state) {
        held = null;
        try {
            if (handler == null || media == null || state == null) return false;
            if (!switchedOn()) return false;
            HookStatus.bound(FamilyNames.FEED_TAP_PAUSE, PRESS);
            Videos feed = videos;
            if (target(feed, feed.controllerOf(handler), media, state, true) == null) return false;
            held = new WeakReference<>(state);
            HookStatus.counted(FamilyNames.FEED_TAP_PAUSE, PRESS_TAKEN);
            Logger.printDebug(() -> "Feed video: took a held press, its lift pauses or plays it");
            return true;
        } catch (Throwable failure) {
            held = null;
            HookStatus.threw(FamilyNames.FEED_TAP_PAUSE, PRESS, failure);
            return false;
        }
    }

    /**
     * Asked first thing in the release of the feed's tap handler class, with the handler, the post,
     * its state and whether the press ended in a lift rather than a scroll taking it over (Instagram's
     * boolean, passed as an int: 0 for a scroll). For the press {@link #pressed} took, a lift pauses
     * or plays the video the way a tap does, unless the tap handler got that press too and already
     * did. Either way the release then goes on as it was, which only resets Instagram's hold.
     * Anything else is left alone. Never throws.
     */
    public static void released(@Nullable Object handler, @Nullable Object media, @Nullable Object state, int lifted) {
        try {
            if (state == null || !letGo(state) || lifted == 0) return;
            if (handler == null || media == null || !switchedOn()) return;
            HookStatus.bound(FamilyNames.FEED_TAP_PAUSE, RELEASE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_TAP_PAUSE, RELEASE, failure);
            return;
        }
        toggle(feed -> feed.controllerOf(handler), media, state, RELEASE);
    }

    /**
     * Asked first thing in the feed's Reels launcher, which every way into Reels from a feed post ends
     * in, with the launcher, where it was asked from (an enum of Instagram's), the post and its state.
     * True means a tap on the playing video paused or played it and the launcher is done. False lets
     * Reels open: the switch is off, HushGram is paused, the settings aren't ready, the opening isn't a
     * tap on a video ({@link #VIDEO_TAP}), or the tap fails a check {@link #tapped} makes. A tap that
     * ends a held press {@link #pressed} took is that press's own, so its lift does nothing more. Not
     * counted as invoked, like the press. Never throws.
     */
    public static boolean opening(@Nullable Object launcher, @Nullable Object entryPoint, @Nullable Object media,
                                  @Nullable Object state) {
        try {
            if (launcher == null || media == null || state == null) return false;
            if (!switchedOn()) return false;
            HookStatus.bound(FamilyNames.FEED_TAP_PAUSE, OPEN);
            String from = entryPoint instanceof Enum ? ((Enum<?>) entryPoint).name() : String.valueOf(entryPoint);
            HookStatus.counted(FamilyNames.FEED_TAP_PAUSE, OPENED_FROM + from);
            if (!VIDEO_TAP.equals(from)) return false;
            letGo(state);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_TAP_PAUSE, OPEN, failure);
            return false;
        }
        return toggle(feed -> feed.controllerOfLauncher(launcher), media, state, OPEN);
    }

    /**
     * Pauses the video the controller [reach] finds has playing when [media] is that post, or plays it
     * when it's paused, and tells the post's [state] which it was. True when it did. False leaves the
     * gesture to Instagram, counted under the check it failed. A failure after the pause or the play
     * still answers true, under the [hook]'s name in the report. Never throws.
     */
    private static boolean toggle(Function<Videos, Object> reach, Object media, Object state, String hook) {
        boolean handled = false;
        try {
            Videos feed = videos;
            Target target = target(feed, reach.apply(feed), media, state, false);
            if (target == null) return false;
            boolean wasPlaying = target.wasPlaying;
            Object done = wasPlaying ? feed.pause(target.controller) : feed.start(target.controller);
            if (unfilled(done, wasPlaying ? "the controller's pause" : "the controller's play")) return false;
            handled = true;
            HookStatus.counted(FamilyNames.FEED_TAP_PAUSE, wasPlaying ? PAUSED : PLAYED);
            String gesture = TAP.equals(hook) ? "a tap" : OPEN.equals(hook) ? "a tap opening Reels" : "a held press";
            Logger.printDebug(() -> "Feed video: " + gesture + (wasPlaying ? " paused it" : " played it"));
            unfilled(feed.mark(state, wasPlaying), "the post state's note");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_TAP_PAUSE, hook, failure);
            return handled;
        }
    }

    /**
     * The [controller] to pause or play [media] through, and whether its player is playing, when [media]
     * is the post the controller has playing, isn't sponsored, and the player says. Null otherwise,
     * counted under the check it failed ({@link #PRESS_LEFT} for a [press]), or reported as the
     * member the build is missing.
     */
    @Nullable
    private static Target target(Videos feed, @Nullable Object controller, Object media, Object state, boolean press) {
        if (unfilled(controller, "the feed video controller")) return null;
        if (controller == null) return left(LEFT_NO_CONTROLLER, press);
        Object shown = feed.shownOf(controller);
        if (unfilled(shown, "the playing post")) return null;
        if (shown == null) return left(LEFT_NOTHING_PLAYING, press);
        Object page = feed.pageOf(media, state);
        if (unfilled(page, "the tapped post's page")) return null;
        if (page == null || !page.equals(shown)) return left(LEFT_ANOTHER_POST, press);
        Object ad = feed.adDetailsOf(media);
        if (unfilled(ad, "the post's ad details")) return null;
        if (ad != null) return left(LEFT_AN_AD, press);
        Object player = feed.playerOf(controller);
        if (unfilled(player, "the player")) return null;
        if (player == null) return left(LEFT_NO_PLAYER, press);
        Object playing = feed.playing(player);
        if (unfilled(playing, "the player's state")) return null;
        if (!(playing instanceof Boolean)) return left(LEFT_NOT_A_BOOLEAN, press);
        return new Target(controller, (Boolean) playing);
    }

    /** Whether the switch is on and the settings are there to say so. */
    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.TAP_FEED_VIDEO_TO_PAUSE.get();
    }

    /** Whether [state] is the held press's, which this then lets go of. */
    private static boolean letGo(Object state) {
        WeakReference<Object> press = held;
        if (press == null || press.get() != state) return false;
        held = null;
        return true;
    }

    /** Counts a gesture that goes to Instagram with the switch on, under the check [reason] it failed or as a press, and answers none. */
    @Nullable
    private static Target left(String reason, boolean press) {
        HookStatus.counted(FamilyNames.FEED_TAP_PAUSE, press ? PRESS_LEFT : reason);
        return null;
    }

    /** Whether [value] is a stub's unfilled answer, which is reported as the member the build is missing. */
    private static boolean unfilled(@Nullable Object value, String what) {
        if (value != NOT_PATCHED) return false;
        HookStatus.missingMember(FamilyNames.FEED_TAP_PAUSE, "method", "FeedPlayerReader", what);
        return true;
    }

    /** Puts the patched reader back and forgets a held press. For tests. */
    static void resetForTests() {
        videos = new Patched();
        held = null;
    }
}
