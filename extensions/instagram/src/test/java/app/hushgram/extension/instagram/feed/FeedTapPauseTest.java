/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.util.List;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the feed tap hooks answer: a tap on the playing video pauses it and the next one plays it, a press held past
 * Instagram's hold delay does the same on its lift, and off, paused, not ready, an ad, any other post and a failure are
 * all Instagram's own tap or press.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FeedTapPauseTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object handler = new Object();
    private final Object state = new Object();
    private final Object media = "post";
    private Feed feed;

    /** A feed with one video playing: the post under the tap, on the page the controller shows. */
    private static final class Feed implements FeedTapPause.Videos {
        Object controller = new Object();
        Object shown = "post";
        Object page = "post";
        Object ad;
        Object player = new Object();
        Object playing = Boolean.TRUE;
        int pauses;
        int starts;
        Boolean marked;
        RuntimeException failReading;
        RuntimeException failMarking;

        @Override public Object controllerOf(Object handler) {
            if (failReading != null) throw failReading;
            return controller;
        }

        int launcherAsked;

        @Override public Object controllerOfLauncher(Object launcher) {
            if (failReading != null) throw failReading;
            launcherAsked++;
            return controller;
        }

        @Override public Object shownOf(Object controller) {
            return shown;
        }

        @Override public Object pageOf(Object media, Object state) {
            return page;
        }

        @Override public Object adDetailsOf(Object media) {
            return ad;
        }

        @Override public Object playerOf(Object controller) {
            return player;
        }

        @Override public Object playing(Object player) {
            return playing;
        }

        @Override public Object pause(Object controller) {
            pauses++;
            playing = Boolean.FALSE;
            return controller;
        }

        @Override public Object start(Object controller) {
            starts++;
            playing = Boolean.TRUE;
            return controller;
        }

        @Override public Object mark(Object state, boolean wasPlaying) {
            if (failMarking != null) throw failMarking;
            marked = wasPlaying;
            return state;
        }

        /** Where the next tap lands: a 1080 by 1350 video, tapped in its middle unless a test moves it. */
        Object spot = new float[] {1080f, 1350f, 540f, 675f};

        @Override public Object spotOf(Object point) {
            return spot;
        }
    }

    /** Instagram's point for a tap, which the fake reads [Feed#spot] for. */
    private final Object point = new Object();

    @Before
    public void clean() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.resetToDefault();
        Settings.FEED_TAPS_STAY_IN_FEED.resetToDefault();
        Settings.FEED_TAP_MIDDLE_ONLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        feed = new Feed();
        FeedTapPause.videos = feed;
    }

    @After
    public void restore() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.resetToDefault();
        Settings.FEED_TAPS_STAY_IN_FEED.resetToDefault();
        Settings.FEED_TAP_MIDDLE_ONLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedTapPause.resetForTests();
        Settings.HOLD_FEED_VIDEO_FAST.resetToDefault();
        FeedHoldSpeed.inBuildForTests = null;
    }

    private static String report() {
        List<String> lines = HookStatus.report();
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(line).append('\n');
        return all.toString();
    }

    private boolean tap() {
        return FeedTapPause.tapped(handler, media, state, point);
    }

    private boolean press() {
        return FeedTapPause.pressed(handler, media, state, point);
    }

    private void lift() {
        FeedTapPause.released(handler, media, state, 1);
    }

    /** Stand-ins for Instagram's entry points, an enum whose names its build keeps. */
    private enum EntryPoint { VIDEO_TAP, BACKGROUND_TAP, FEED_PREVIEW, WATCH_AGAIN }

    private final Object launcher = new Object();

    private boolean open(EntryPoint from) {
        return FeedTapPause.opening(launcher, from, media, state);
    }

    /** Off to start: the tap is Instagram's, and only the call itself is counted. */
    @Test
    public void offItIsInstagramsTap() {
        assertFalse(Settings.TAP_FEED_VIDEO_TO_PAUSE.get());
        assertFalse(tap());
        assertEquals(0, feed.pauses + feed.starts);
        assertNull(feed.marked);
        String report = report();
        assertTrue(report, report.contains(FamilyNames.FEED_TAP_PAUSE + ": invoked 1"));
        assertFalse(report, report.contains(FeedTapPause.PAUSED));
        assertFalse(report, report.contains("taps left"));
    }

    /** On, a tap on the playing video pauses it through the controller, tells the post's state and ends the tap. */
    @Test
    public void aTapOnThePlayingVideoPausesIt() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue(tap());
        assertEquals("pauses", 1, feed.pauses);
        assertEquals("plays", 0, feed.starts);
        assertEquals("the state hears it was playing", Boolean.TRUE, feed.marked);
        assertTrue(report(), report().contains(FeedTapPause.PAUSED + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString(), HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).isEmpty());
    }

    /** The next tap plays it again, and the one after pauses it. */
    @Test
    public void theNextTapPlaysItAgain() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue("first tap", tap());
        assertTrue("second tap", tap());
        assertEquals("pauses", 1, feed.pauses);
        assertEquals("plays", 1, feed.starts);
        assertEquals("the state hears it wasn't playing", Boolean.FALSE, feed.marked);
        assertTrue("third tap", tap());
        assertEquals("pauses", 2, feed.pauses);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.PAUSED + " 2"));
        assertTrue(report, report.contains(FeedTapPause.PLAYED + " 1"));
    }

    /** A post that isn't the one playing, a photo say, keeps Instagram's tap and is counted under "another post". */
    @Test
    public void aPostThatIsNotPlayingIsInstagrams() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.page = "another post";
        assertFalse(tap());
        feed.page = null;
        assertFalse("a post with no page", tap());
        assertEquals(0, feed.pauses + feed.starts);
        assertNull(feed.marked);
        assertTrue(report(), report().contains(FeedTapPause.LEFT_ANOTHER_POST + " 2"));
    }

    /** With nothing playing, no controller, no player or a player that doesn't say, each check counts on its own. */
    @Test
    public void withNothingToPauseItIsInstagrams() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.shown = null;
        assertFalse("nothing playing", tap());
        feed.shown = "post";
        feed.controller = null;
        assertFalse("no controller", tap());
        feed.controller = new Object();
        feed.player = null;
        assertFalse("no player", tap());
        feed.player = new Object();
        feed.playing = null;
        assertFalse("a player that doesn't say", tap());
        assertEquals(0, feed.pauses + feed.starts);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.LEFT_NOTHING_PLAYING + " 1"));
        assertTrue(report, report.contains(FeedTapPause.LEFT_NO_CONTROLLER + " 1"));
        assertTrue(report, report.contains(FeedTapPause.LEFT_NO_PLAYER + " 1"));
        assertTrue(report, report.contains(FeedTapPause.LEFT_NOT_A_BOOLEAN + " 1"));
    }

    /** A sponsored post keeps Instagram's tap even while its video plays, counted under "an ad". */
    @Test
    public void anAdIsInstagrams() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.ad = new Object();
        assertFalse(tap());
        assertEquals(0, feed.pauses + feed.starts);
        assertTrue(report(), report().contains(FeedTapPause.LEFT_AN_AD + " 1"));
    }

    /** Paused, a saved switch leaves the tap alone and reads nothing. */
    @Test
    public void pausedHushGramLeavesTheTap() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(tap());
        assertTrue("the saved choice is kept", Settings.TAP_FEED_VIDEO_TO_PAUSE.savedValue());
        assertEquals(0, feed.pauses + feed.starts);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(tap());
        assertEquals(1, feed.pauses);
    }

    /** Before the settings are ready the hook can't read the switch, so the tap is Instagram's. */
    @Test
    public void beforeTheSettingsAreReadyItIsInstagramsTap() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        SettingsContextRule.withoutContext(() -> assertFalse(tap()));
        assertEquals(0, feed.pauses + feed.starts);
    }

    /** A tap that comes without its handler, post or state is Instagram's. */
    @Test
    public void withoutItsArgumentsItSaysNo() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertFalse(FeedTapPause.tapped(null, media, state, point));
        assertFalse(FeedTapPause.tapped(handler, null, state, point));
        assertFalse(FeedTapPause.tapped(handler, media, null, point));
        assertEquals(0, feed.pauses + feed.starts);
    }

    /** The stubs as the build ships them, unfilled: the tap is Instagram's and the miss is reported once. */
    @Test
    public void unfilledStubsLeaveTheTapAndAreReported() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        FeedTapPause.resetForTests();
        assertFalse(tap());
        assertFalse(tap());
        List<String> missing = HookStatus.missing(FamilyNames.FEED_TAP_PAUSE);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("FeedPlayerReader"));
    }

    /** A failure before anything was paused leaves the tap to Instagram and is reported. */
    @Test
    public void aFailureBeforeThePauseLeavesTheTap() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.failReading = new IllegalStateException("gone");
        assertFalse(tap());
        assertEquals(0, feed.pauses + feed.starts);
        String missing = HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString();
        assertTrue(missing, missing.contains(FeedTapPause.TAP) && missing.contains("IllegalStateException"));
    }

    /** A failure after the video was paused doesn't hand the same tap to Instagram as well. */
    @Test
    public void aFailureAfterThePauseStillEndsTheTap() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.failMarking = new IllegalStateException("gone");
        assertTrue(tap());
        assertEquals(1, feed.pauses);
        assertFalse(HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).isEmpty());
    }

    /**
     * A press held on the playing video is taken without pausing anything, so Reels doesn't open, and its lift pauses the
     * video the way a tap does. Neither counts as invoked, which stays the taps that reached the tap handler.
     */
    @Test
    public void aHeldPressIsTakenAndItsLiftPausesTheVideo() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue("the press entry returns", press());
        assertEquals("nothing paused while it's held", 0, feed.pauses + feed.starts);
        lift();
        assertEquals("pauses", 1, feed.pauses);
        assertEquals("plays", 0, feed.starts);
        assertEquals("the state hears it was playing", Boolean.TRUE, feed.marked);
        String report = report();
        assertTrue(report, report.contains(FamilyNames.FEED_TAP_PAUSE + ": invoked 0"));
        assertTrue(report, report.contains(FeedTapPause.PRESS_TAKEN + " 1"));
        assertTrue(report, report.contains(FeedTapPause.PAUSED + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString(), HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).isEmpty());
    }

    /** Taps and held presses keep alternating pause and play, however they come. */
    @Test
    public void tapsAndHeldPressesKeepAlternating() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue("tap 1", tap());
        assertTrue("tap 2", tap());
        assertTrue("tap 3", tap());
        assertEquals("three taps: pause, play, pause", 2, feed.pauses);
        assertEquals(1, feed.starts);
        assertTrue("held tap 4", press());
        lift();
        assertEquals("the held one plays it", 2, feed.starts);
        assertTrue("held tap 5", press());
        lift();
        assertTrue("tap 6", tap());
        assertEquals("pauses", 3, feed.pauses);
        assertEquals("plays", 3, feed.starts);
        assertEquals("playing again", Boolean.TRUE, feed.playing);
    }

    /** When the tap handler gets a held press as a tap too, the tap pauses the video and the lift adds nothing. */
    @Test
    public void aPressTheTapHandlerAlsoGetsPausesOnce() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue(press());
        assertTrue("the press's own tap", tap());
        lift();
        assertEquals("pauses", 1, feed.pauses);
        assertEquals("plays", 0, feed.starts);
        assertTrue("a later tap plays it", tap());
        assertEquals(1, feed.starts);
    }

    /**
     * A press a scroll takes over ends without a lift and leaves the video playing. A lift with no press taken, or one
     * for another post's press, does nothing either, and a new press forgets the last one.
     */
    @Test
    public void onlyTheLiftOfATakenPressPausesOrPlays() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue(press());
        FeedTapPause.released(handler, media, state, 0);
        lift();
        assertEquals("a scroll took it", 0, feed.pauses + feed.starts);

        assertTrue(press());
        FeedTapPause.released(handler, media, new Object(), 1);
        assertEquals("another post's lift", 0, feed.pauses + feed.starts);
        feed.page = "another post";
        assertFalse("a press on another post", press());
        feed.page = "post";
        lift();
        assertEquals("the new press forgot the old one", 0, feed.pauses + feed.starts);
        assertNull(feed.marked);
    }

    /**
     * A press on a post that isn't the one playing, on an ad, or with the switch off, paused or the settings not ready
     * stays Instagram's, and so does its lift. A press with the switch on is counted as left, not as a tap.
     */
    @Test
    public void aPressThatIsNotTheirsIsInstagrams() {
        assertFalse("off", press());
        lift();
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.page = "another post";
        assertFalse("another post", press());
        lift();
        feed.page = "post";
        feed.ad = new Object();
        assertFalse("an ad", press());
        lift();
        feed.ad = null;
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", press());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("not ready", press()));
        assertFalse(FeedTapPause.pressed(null, media, state, point));
        assertFalse(FeedTapPause.pressed(handler, null, state, point));
        assertFalse(FeedTapPause.pressed(handler, media, null, point));
        FeedTapPause.released(null, null, null, 1);
        assertEquals(0, feed.pauses + feed.starts);
        assertNull(feed.marked);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.PRESS_LEFT + " 2"));
        assertFalse(report, report.contains("taps left"));
        assertFalse(report, report.contains(FeedTapPause.PRESS_TAKEN));
    }

    /**
     * With Hold for 2x on feed videos in and on, a held press is left to Instagram, which speeds the video up, and its
     * lift pauses nothing (#552). Taps still pause, and the switch off takes the press again.
     */
    @Test
    public void aHeldPressIsLeftToHoldFor2x() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        FeedHoldSpeed.inBuildForTests = true;
        assertFalse("left to Instagram", press());
        lift();
        assertEquals("nothing paused", 0, feed.pauses + feed.starts);
        assertNull(feed.marked);
        assertTrue("a tap still pauses", tap());
        assertEquals(1, feed.pauses);
        FeedHoldSpeed.inBuildForTests = false;
        assertTrue("without the patch the press is taken", press());
        FeedHoldSpeed.inBuildForTests = true;
        lift();
        Settings.HOLD_FEED_VIDEO_FAST.save(false);
        assertTrue("with the switch off the press is taken", press());
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.PRESS_TAKEN + " 2"));
    }

    private void holdFor2xOn() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        FeedHoldSpeed.inBuildForTests = true;
    }

    /**
     * Android sends a held press's tap before its release, so lifting a finger from Hold for 2x reaches the tap handler
     * first. That tap belongs to the hold: the video stays as it was, the handler stops there, and the next tap pauses.
     */
    @Test
    public void theLiftOfAHoldFor2xLeavesTheVideoAlone() {
        holdFor2xOn();
        assertFalse("the hold is Instagram's", press());
        assertTrue("its lift goes no further", tap());
        lift();
        assertEquals("still playing", 0, feed.pauses + feed.starts);
        assertNull("nothing to tell the post", feed.marked);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.HOLD_LIFT_LEFT + " 1"));

        assertTrue("the next tap is a tap", tap());
        assertEquals(1, feed.pauses);
    }

    /** The same lift can reach the Reels launcher instead of the tap handler, which leaves the video alone too. */
    @Test
    public void theLiftOfAHoldFor2xAtTheLauncherLeavesTheVideoAlone() {
        holdFor2xOn();
        assertFalse(press());
        assertTrue("Reels stays shut", open(EntryPoint.VIDEO_TAP));
        lift();
        assertEquals("still playing", 0, feed.pauses + feed.starts);

        assertTrue("the next tap is a tap", open(EntryPoint.VIDEO_TAP));
        assertEquals(1, feed.pauses);
    }

    /** A hold that ends without its tap, as when a scroll takes it over, can't swallow the next real tap. */
    @Test
    public void aHoldThatEndedAlreadyLeavesTheNextTapAlone() {
        holdFor2xOn();
        assertFalse(press());
        FeedTapPause.released(handler, media, state, 0);
        assertTrue(tap());
        assertEquals("a scroll ended the hold", 1, feed.pauses);

        assertFalse("a new hold", press());
        lift();
        assertTrue(tap());
        assertEquals("a finger lifted with no tap first", 1, feed.starts);
    }

    /** Only the held post's tap is the hold's. A tap on another post's state still pauses or plays. */
    @Test
    public void aTapOnAnotherPostDuringAHoldIsATap() {
        holdFor2xOn();
        assertFalse(press());
        assertTrue(FeedTapPause.tapped(handler, media, new Object(), point));
        assertEquals(1, feed.pauses);
    }

    /** The lift of a hold on an ad or a post that isn't playing goes on to Instagram, as any tap there does. */
    @Test
    public void theLiftOfAHoldOnAnotherPostIsInstagrams() {
        holdFor2xOn();
        feed.ad = new Object();
        assertFalse(press());
        assertFalse("an ad", tap());
        feed.ad = null;
        feed.page = "another post";
        assertFalse(press());
        assertFalse("another post", tap());
        assertEquals(0, feed.pauses + feed.starts);
    }

    /** A press that throws before it's taken stays Instagram's and is reported, and its lift does nothing. */
    @Test
    public void aFailureInAPressLeavesItToInstagram() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.failReading = new IllegalStateException("gone");
        assertFalse(press());
        feed.failReading = null;
        lift();
        assertEquals(0, feed.pauses + feed.starts);
        String missing = HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString();
        assertTrue(missing, missing.contains(FeedTapPause.PRESS) && missing.contains("IllegalStateException"));
    }

    /**
     * On 451 a tap can reach Reels without the tap handler. A tap on the playing video stops at the Reels launcher, which
     * reaches the controller its own way: the first pauses, the next plays, and each opening is counted by where it came
     * from. Not counted as invoked.
     */
    @Test
    public void aTapThatReachesTheReelsLauncherPausesAndPlays() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue("first tap", open(EntryPoint.VIDEO_TAP));
        assertEquals("pauses", 1, feed.pauses);
        assertEquals("the state hears it was playing", Boolean.TRUE, feed.marked);
        assertTrue("second tap", open(EntryPoint.VIDEO_TAP));
        assertEquals("plays", 1, feed.starts);
        assertEquals("the controller came through the launcher", 2, feed.launcherAsked);
        String report = report();
        assertTrue(report, report.contains(FamilyNames.FEED_TAP_PAUSE + ": invoked 0"));
        assertTrue(report, report.contains(FeedTapPause.OPENED_FROM + "VIDEO_TAP 2"));
        assertTrue(report, report.contains(FeedTapPause.PAUSED + " 1"));
        assertTrue(report, report.contains(FeedTapPause.PLAYED + " 1"));
        assertTrue(HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString(), HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).isEmpty());
    }

    /**
     * A finished feed reel shows Instagram's end screen. A tap on its backdrop reaches the launcher as a background
     * tap (the way a 451 phone's did on 2026-10-15), and opens Reels even on the post the controller still holds.
     */
    @Test
    public void aTapOnTheEndScreenOpensReels() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertFalse(open(EntryPoint.BACKGROUND_TAP));
        assertEquals(0, feed.pauses + feed.starts);
        assertEquals("nothing read for it", 0, feed.launcherAsked);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.OPENED_FROM + "BACKGROUND_TAP 1"));
    }

    /**
     * #706: Taps on feed videos stay in the feed ends the launcher for every tap on a video and the end screen's backdrop,
     * with the pause switch off, without pausing or reading anything. The end screen's buttons and a preview still open
     * Reels, and the switch off leaves every opening to Instagram.
     */
    @Test
    public void tapsStayInTheFeedWithThePauseOff() {
        assertFalse(Settings.FEED_TAPS_STAY_IN_FEED.defaultValue);
        assertTrue(PatchFamily.FEED_TAP_PAUSE.switches.contains(Settings.FEED_TAPS_STAY_IN_FEED));
        assertFalse("off, Reels opens", open(EntryPoint.VIDEO_TAP));

        Settings.FEED_TAPS_STAY_IN_FEED.save(true);
        assertTrue("a tap on the video", open(EntryPoint.VIDEO_TAP));
        feed.page = "another post";
        feed.ad = new Object();
        assertTrue("any video, an ad's too", open(EntryPoint.VIDEO_TAP));
        assertTrue("the end screen's backdrop", open(EntryPoint.BACKGROUND_TAP));
        assertFalse("the end screen's button", open(EntryPoint.WATCH_AGAIN));
        assertFalse("a preview", open(EntryPoint.FEED_PREVIEW));
        assertFalse(FeedTapPause.opening(launcher, EntryPoint.VIDEO_TAP, null, state));
        assertEquals("nothing paused or played", 0, feed.pauses + feed.starts);
        assertEquals("nothing read", 0, feed.launcherAsked);
        assertNull(feed.marked);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.KEPT_FROM + "VIDEO_TAP 2"));
        assertTrue(report, report.contains(FeedTapPause.KEPT_FROM + "BACKGROUND_TAP 1"));
        assertFalse(report, report.contains(FeedTapPause.KEPT_FROM + "WATCH_AGAIN"));
        assertTrue(report, report.contains(FeedTapPause.OPENED_FROM + "WATCH_AGAIN 1"));
    }

    /** #706: with both switches on, a tap on the playing video still pauses and plays it, and one it can't pause stays put. */
    @Test
    public void tapsStayInTheFeedAndThePlayingVideoStillPauses() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        Settings.FEED_TAPS_STAY_IN_FEED.save(true);
        assertTrue(open(EntryPoint.VIDEO_TAP));
        assertEquals("pauses", 1, feed.pauses);
        assertTrue(open(EntryPoint.VIDEO_TAP));
        assertEquals("plays", 1, feed.starts);
        assertFalse("not kept, paused", report().contains(FeedTapPause.KEPT_FROM));

        feed.page = "another post";
        assertTrue("another post stays in the feed", open(EntryPoint.VIDEO_TAP));
        feed.page = "post";
        feed.ad = new Object();
        assertTrue("an ad too", open(EntryPoint.VIDEO_TAP));
        assertEquals(1, feed.pauses);
        assertEquals(1, feed.starts);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.LEFT_ANOTHER_POST + " 1"));
        assertTrue(report, report.contains(FeedTapPause.LEFT_AN_AD + " 1"));
        assertTrue(report, report.contains(FeedTapPause.KEPT_FROM + "VIDEO_TAP 2"));
    }

    /** #706: paused, not ready or failing, the switch leaves Reels to open. */
    @Test
    public void tapsStayInTheFeedOnlyWhileItCan() {
        Settings.FEED_TAPS_STAY_IN_FEED.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", open(EntryPoint.VIDEO_TAP));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("not ready", open(EntryPoint.VIDEO_TAP)));
        assertFalse(report(), report().contains(FeedTapPause.KEPT_FROM));
        assertTrue("and back", open(EntryPoint.VIDEO_TAP));
    }

    /**
     * #706: Instagram's hand-off of the playing video to Reels, which stops the feed's player before the launcher runs,
     * is skipped while either switch is on, so a tap the launcher keeps leaves the video playing. With both off,
     * HushGram paused or the settings not ready, it's left to Instagram, and nothing is paused, played or read.
     */
    @Test
    public void theHandoffToReelsIsSkippedWhileATapMayStay() {
        assertFalse("both off", FeedTapPause.skipsHandoff());
        Settings.FEED_TAPS_STAY_IN_FEED.save(true);
        assertTrue("taps stay in the feed", FeedTapPause.skipsHandoff());
        Settings.FEED_TAPS_STAY_IN_FEED.save(false);
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue("the pause", FeedTapPause.skipsHandoff());
        Settings.FEED_TAPS_STAY_IN_FEED.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", FeedTapPause.skipsHandoff());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("not ready", FeedTapPause.skipsHandoff()));
        assertEquals("nothing paused or played", 0, feed.pauses + feed.starts);
        assertEquals("nothing read", 0, feed.launcherAsked);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.HANDOFF_SKIPPED + " 2"));
    }

    private void middleOnlyOn() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        Settings.FEED_TAP_MIDDLE_ONLY.save(true);
    }

    /** A tap on the 1080 by 1350 video's top left corner, far from its play button. */
    private void tapInTheCorner() {
        feed.spot = new float[] {1080f, 1350f, 100f, 120f};
    }

    /**
     * #706: with Only the middle pauses it on, a tap off the middle is Instagram's, and the Reels opening it leads to
     * goes ahead without a pause. Nothing is paused, played or read for it, and it's counted as left off the middle.
     */
    @Test
    public void offTheMiddleATapOpensReels() {
        assertFalse(Settings.FEED_TAP_MIDDLE_ONLY.defaultValue);
        assertTrue(PatchFamily.FEED_TAP_PAUSE.switches.contains(Settings.FEED_TAP_MIDDLE_ONLY));
        middleOnlyOn();
        tapInTheCorner();
        assertFalse("the tap is Instagram's", tap());
        assertFalse("Reels opens", open(EntryPoint.VIDEO_TAP));
        assertEquals("nothing paused or played", 0, feed.pauses + feed.starts);
        assertEquals("nothing read at the launcher", 0, feed.launcherAsked);
        assertNull(feed.marked);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.LEFT_OFF_MIDDLE + " 1"));
        assertFalse(report, report.contains(FeedTapPause.KEPT_FROM));
    }

    /** #706: with Taps on feed videos stay in the feed on as well, the tap off the middle stays in the feed, still unpaused. */
    @Test
    public void offTheMiddleWithTapsStayingInTheFeedItIsKept() {
        middleOnlyOn();
        Settings.FEED_TAPS_STAY_IN_FEED.save(true);
        tapInTheCorner();
        assertFalse(tap());
        assertTrue("kept in the feed", open(EntryPoint.VIDEO_TAP));
        assertEquals("nothing paused or played", 0, feed.pauses + feed.starts);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.KEPT_FROM + "VIDEO_TAP 1"));
    }

    /** #706: a tap in the middle still pauses and plays with the switch on, and with it off a tap anywhere does. */
    @Test
    public void inTheMiddleATapStillPauses() {
        middleOnlyOn();
        assertTrue("the middle pauses", tap());
        feed.spot = new float[] {1080f, 1350f, 560f, 650f};
        assertTrue("near it plays", tap());
        assertEquals(1, feed.pauses);
        assertEquals(1, feed.starts);
        Settings.FEED_TAP_MIDDLE_ONLY.save(false);
        tapInTheCorner();
        assertTrue("switch off, the corner pauses", tap());
        assertEquals(2, feed.pauses);
        assertFalse(report(), report().contains(FeedTapPause.LEFT_OFF_MIDDLE));
    }

    /** #706: a press held off the middle goes to Instagram and its lift does nothing; one in the middle is taken. */
    @Test
    public void aHeldPressOffTheMiddleIsInstagrams() {
        middleOnlyOn();
        tapInTheCorner();
        assertFalse(press());
        lift();
        assertEquals(0, feed.pauses + feed.starts);
        feed.spot = new float[] {1080f, 1350f, 540f, 675f};
        assertTrue(press());
        lift();
        assertEquals(1, feed.pauses);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.PRESS_OFF_MIDDLE + " 1"));
        assertTrue(report, report.contains(FeedTapPause.PRESS_TAKEN + " 1"));
    }

    /** #706: a spot that can't be read, unfilled included, counts as the middle, so the tap pauses as it did before. */
    @Test
    public void aSpotItCannotReadCountsAsTheMiddle() {
        middleOnlyOn();
        feed.spot = null;
        assertTrue("no spot", tap());
        feed.spot = new float[] {100f, 120f};
        assertTrue("two numbers", tap());
        feed.spot = "a spot";
        assertTrue("not numbers", tap());
        assertTrue("no point", FeedTapPause.tapped(handler, media, state, null));
        feed.spot = FeedTapPause.NOT_PATCHED;
        assertTrue("unfilled", tap());
        assertEquals(5, feed.pauses + feed.starts);
        String missing = HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString();
        assertTrue(missing, missing.contains("the tap's spot"));
    }

    /**
     * #706: the mark a tap off the middle leaves is for its own post's opening only, used once and only for a moment;
     * any other tap at the launcher pauses as before.
     */
    @Test
    public void theMarkOffTheMiddleIsOnlyForItsOwnOpening() {
        middleOnlyOn();
        tapInTheCorner();
        assertFalse(tap());
        assertTrue("another post's state", FeedTapPause.opening(launcher, EntryPoint.VIDEO_TAP, media, new Object()));
        assertEquals(1, feed.pauses);
        assertFalse("its own opening", open(EntryPoint.VIDEO_TAP));
        assertTrue("used once", open(EntryPoint.VIDEO_TAP));
        assertEquals(1, feed.starts);

        assertFalse(tap());
        ShadowSystemClock.advanceBy(FeedTapPause.OFF_MIDDLE_MS + 1, TimeUnit.MILLISECONDS);
        assertTrue("too late to be its own", open(EntryPoint.VIDEO_TAP));
        assertEquals(2, feed.pauses);
    }

    /** #706: the tap that ends a hold for 2x leaves the video alone wherever it lands. */
    @Test
    public void theLiftOfAHoldFor2xOffTheMiddleIsStillTheHolds() {
        holdFor2xOn();
        Settings.FEED_TAP_MIDDLE_ONLY.save(true);
        tapInTheCorner();
        assertFalse(press());
        assertTrue("its lift goes no further", tap());
        assertEquals(0, feed.pauses + feed.starts);
        assertFalse(report(), report().contains(FeedTapPause.LEFT_OFF_MIDDLE));
    }

    /** #706: the middle is 40dp around the center, 110 pixels on a 2.75 density screen, and a view with no size is all middle. */
    @Test
    public void theMiddleReachesFortyDp() {
        float density = 2.75f;
        assertTrue(FeedTapPause.inMiddle(1080f, 1350f, 540f, 675f, density));
        assertTrue(FeedTapPause.inMiddle(1080f, 1350f, 650f, 675f, density));
        assertFalse(FeedTapPause.inMiddle(1080f, 1350f, 651f, 675f, density));
        assertTrue(FeedTapPause.inMiddle(1080f, 1350f, 540f, 565f, density));
        assertTrue(FeedTapPause.inMiddle(1080f, 1350f, 617f, 752f, density));
        assertFalse(FeedTapPause.inMiddle(1080f, 1350f, 618f, 753f, density));
        assertTrue(FeedTapPause.inMiddle(0f, 0f, 500f, 500f, density));
        assertFalse(FeedTapPause.inMiddle(1080f, 1350f, 540f, 675f + 41f, 1f));
    }

    /** Any other opening goes on to Reels and is counted under its name; one with no enum is counted as it prints. */
    @Test
    public void otherOpeningsGoToReels() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertFalse(open(EntryPoint.FEED_PREVIEW));
        assertFalse("the end screen's button", open(EntryPoint.WATCH_AGAIN));
        assertFalse(FeedTapPause.opening(launcher, null, media, state));
        assertEquals(0, feed.pauses + feed.starts);
        assertEquals("nothing read for them", 0, feed.launcherAsked);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.OPENED_FROM + "FEED_PREVIEW 1"));
        assertTrue(report, report.contains(FeedTapPause.OPENED_FROM + "WATCH_AGAIN 1"));
        assertTrue(report, report.contains(FeedTapPause.OPENED_FROM + "null 1"));
    }

    /**
     * Off, paused, not ready, missing an argument, on another post or an ad, a tap at the launcher opens Reels. Off
     * counts nothing; with the switch on, the post's checks count as a tap's do.
     */
    @Test
    public void aLauncherTapThatIsNotTheirsOpensReels() {
        assertFalse("off", open(EntryPoint.VIDEO_TAP));
        assertFalse(report(), report().contains(FeedTapPause.OPENED_FROM));
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", open(EntryPoint.VIDEO_TAP));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("not ready", open(EntryPoint.VIDEO_TAP)));
        assertFalse(FeedTapPause.opening(null, EntryPoint.VIDEO_TAP, media, state));
        assertFalse(FeedTapPause.opening(launcher, EntryPoint.VIDEO_TAP, null, state));
        assertFalse(FeedTapPause.opening(launcher, EntryPoint.VIDEO_TAP, media, null));
        feed.page = "another post";
        assertFalse("another post", open(EntryPoint.VIDEO_TAP));
        feed.page = "post";
        feed.ad = new Object();
        assertFalse("an ad", open(EntryPoint.VIDEO_TAP));
        assertEquals(0, feed.pauses + feed.starts);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.LEFT_ANOTHER_POST + " 1"));
        assertTrue(report, report.contains(FeedTapPause.LEFT_AN_AD + " 1"));
    }

    /** A held press that also reaches the launcher as a tap is paused there once, and its lift adds nothing. */
    @Test
    public void aHeldPressThatReachesTheLauncherPausesOnce() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        assertTrue(press());
        assertTrue("the press's tap at the launcher", open(EntryPoint.VIDEO_TAP));
        lift();
        assertEquals("pauses", 1, feed.pauses);
        assertEquals("plays", 0, feed.starts);
    }

    /** A failure reaching the controller from the launcher lets Reels open and is reported under the launcher's name. */
    @Test
    public void aFailureAtTheLauncherOpensReels() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.save(true);
        feed.failReading = new IllegalStateException("gone");
        assertFalse(open(EntryPoint.VIDEO_TAP));
        assertEquals(0, feed.pauses + feed.starts);
        String missing = HookStatus.missing(FamilyNames.FEED_TAP_PAUSE).toString();
        assertTrue(missing, missing.contains(FeedTapPause.OPEN) && missing.contains("IllegalStateException"));
    }
}
