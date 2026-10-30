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

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
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
    }

    @Before
    public void clean() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        feed = new Feed();
        FeedTapPause.videos = feed;
    }

    @After
    public void restore() {
        Settings.TAP_FEED_VIDEO_TO_PAUSE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedTapPause.resetForTests();
    }

    private static String report() {
        List<String> lines = HookStatus.report();
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(line).append('\n');
        return all.toString();
    }

    private boolean tap() {
        return FeedTapPause.tapped(handler, media, state);
    }

    private boolean press() {
        return FeedTapPause.pressed(handler, media, state);
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
        assertFalse(FeedTapPause.tapped(null, media, state));
        assertFalse(FeedTapPause.tapped(handler, null, state));
        assertFalse(FeedTapPause.tapped(handler, media, null));
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
        assertFalse(FeedTapPause.pressed(null, media, state));
        assertFalse(FeedTapPause.pressed(handler, null, state));
        assertFalse(FeedTapPause.pressed(handler, media, null));
        FeedTapPause.released(null, null, null, 1);
        assertEquals(0, feed.pauses + feed.starts);
        assertNull(feed.marked);
        String report = report();
        assertTrue(report, report.contains(FeedTapPause.PRESS_LEFT + " 2"));
        assertFalse(report, report.contains("taps left"));
        assertFalse(report, report.contains(FeedTapPause.PRESS_TAKEN));
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
