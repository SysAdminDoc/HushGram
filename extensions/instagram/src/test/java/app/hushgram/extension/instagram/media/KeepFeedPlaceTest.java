/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

import app.hushgram.extension.instagram.media.ResumePlaybackForTests.Player;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests.ProductType;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests.Session;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests.Video;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Keep your place in feed videos (#874): a feed video scrolled away from partway picks up where it
 * was when it's scrolled back to, in the same visit, whatever its length. Reels in the Reels viewer,
 * live videos and ads start over, the end or a loop forgets the place, and Resume long videos keeps
 * working as it did.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class KeepFeedPlaceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int SECOND = 1_000;
    private static final int MINUTE = 60 * SECOND;

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        ResumePlaybackForTests.install();
        HookStatus.clear();
        FeedFilterCounters.clear();
        Settings.RESUME_FEED_VIDEOS.save(true);
    }

    @After
    public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        PauseForTests.resume();
        Settings.RESUME_FEED_VIDEOS.resetToDefault();
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
        ResumePlaybackForTests.forget();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    /** A 30-second clip in the feed, the kind #874 asks about. */
    private static Video clip(String id) {
        return new Video(id, 30 * SECOND);
    }

    /** A feed player of [video] that played from the start and was scrolled away from at [at]. */
    private static Player scrolledAwayAt(Video video, int at) {
        Player player = new Player(video);
        ResumePlayback.started(player);
        ResumePlaybackForTests.runLater();
        SystemClock.sleep(at);
        player.position = at;
        ResumePlayback.stopped(player, "scroll");
        return player;
    }

    /** [player] starting its video from 0, with the resume it posts run. */
    private static Player startedAgain(Player player) {
        player.position = 0;
        ResumePlayback.started(player);
        ResumePlaybackForTests.runLater();
        return player;
    }

    private static String feedLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(ResumePlayback.FEED_ROUTE + ":")) return line;
        }
        return "";
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.RESUME_FEED_VIDEOS.resetToDefault();
        assertFalse("nothing is kept or moved until it's turned on", Settings.RESUME_FEED_VIDEOS.get());
    }

    @Test
    public void aClipScrolledBackToInANewPlayerPicksUpWhereItWasLeft() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);

        Player back = startedAgain(new Player(video));

        assertEquals(Collections.singletonList(6_500), back.seeks);
        String line = feedLine();
        assertTrue(line, line.contains(ResumePlayback.PLACE_KEPT + " 1") && line.contains(ResumePlayback.RESUMED + " 1"));
    }

    /**
     * Instagram can hand the same player the same video again as it comes back on screen, with its
     * position already back at 0 by the bind. The place from the scroll away is still the one used.
     */
    @Test
    public void theSamePlayerGivenTheVideoAgainPicksUpToo() {
        Video video = clip("3712345678901234567");
        Player player = scrolledAwayAt(video, 6_500);
        player.position = 0;
        ResumePlayback.rebound(player);

        startedAgain(player);

        assertEquals(Collections.singletonList(6_500), player.seeks);
    }

    /** Its later starts, after a pause or a buffer, are Instagram's own, as with long videos. */
    @Test
    public void itResumesOncePerFirstStart() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        Player back = startedAgain(new Player(video));

        back.position = 9_000;
        ResumePlayback.started(back);
        ResumePlaybackForTests.runLater();

        assertEquals("it resumed a second time", 1, back.seeks.size());
    }

    @Test
    public void offItKeepsNothingAndMovesNothing() {
        Settings.RESUME_FEED_VIDEOS.save(false);
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        assertEquals("a place kept with the switch off", 0, ResumePlayback.placesKept());

        Settings.RESUME_FEED_VIDEOS.save(true);
        assertEquals("a start moved with no place", Collections.emptyList(), startedAgain(new Player(video)).seeks);

        scrolledAwayAt(video, 6_500);
        Settings.RESUME_FEED_VIDEOS.save(false);
        assertEquals("a start with the switch off moved", Collections.emptyList(), startedAgain(new Player(video)).seeks);
    }

    @Test
    public void pausedItMovesNothing() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("a paused start moved", Collections.emptyList(), startedAgain(new Player(video)).seeks);
    }

    /** A switch turned off between the start and its resume stops the seek. */
    @Test
    public void turnedOffBeforeTheResumeRunsItDoesntSeek() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        Player back = new Player(video);
        ResumePlayback.started(back);
        Settings.RESUME_FEED_VIDEOS.save(false);
        ResumePlaybackForTests.runLater();
        assertEquals(Collections.emptyList(), back.seeks);
    }

    /** A reel in the Reels viewer is a loop you swipe through: it starts over as Instagram starts it. */
    @Test
    public void aReelInTheReelsViewerStartsOver() {
        Video reel = clip("3712345678901234567");
        reel.product = ProductType.CLIPS;
        Player viewer = new Player(reel);
        viewer.reelsViewer = 1;
        ResumePlayback.started(viewer);
        viewer.position = 6_500;
        ResumePlayback.stopped(viewer, "scroll");
        assertEquals("a Reels viewer place was kept", 0, ResumePlayback.placesKept());

        Player again = new Player(reel);
        again.reelsViewer = 1;
        assertEquals(Collections.emptyList(), startedAgain(again).seeks);
        assertTrue(feedLine(), feedLine().contains(ResumePlayback.IN_REELS));
    }

    /** A reel shown in the feed plays in the feed's player, so it keeps its place like any feed video. */
    @Test
    public void aReelInTheFeedKeepsItsPlace() {
        Video reel = clip("3712345678901234567");
        reel.product = ProductType.CLIPS;
        scrolledAwayAt(reel, 6_500);
        assertEquals(Collections.singletonList(6_500), startedAgain(new Player(reel)).seeks);
    }

    /**
     * Where the patch couldn't find the Reels viewer's logger, the stub answers that it can't tell,
     * and nothing is kept: a Reels tab loop that picked up partway would be worse than none. Hook
     * status says what's missing.
     */
    @Test
    public void aPlayerThePatchCantPlaceIsLeftAloneAndReported() {
        Video video = clip("3712345678901234567");
        Player unknown = new Player(video);
        unknown.reelsViewer = ResumePlayback.NOT_PATCHED_FLAG;
        ResumePlayback.started(unknown);
        unknown.position = 6_500;
        ResumePlayback.stopped(unknown, "scroll");

        assertEquals(0, ResumePlayback.placesKept());
        String missing = HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).toString();
        assertTrue(missing, missing.contains("the video logger"));
    }

    @Test
    public void liveVideosAndAdsStartOver() {
        Video live = clip("111111111");
        live.product = ProductType.LIVE;
        scrolledAwayAt(live, 6_500);
        Video ad = clip("222222222");
        ad.sponsored = true;
        scrolledAwayAt(ad, 6_500);

        assertEquals(0, ResumePlayback.placesKept());
        assertEquals(Collections.emptyList(), startedAgain(new Player(live)).seeks);
        assertEquals(Collections.emptyList(), startedAgain(new Player(ad)).seeks);
    }

    @Test
    public void aVideoThatPlaysToItsEndOrLoopsForgetsItsPlace() {
        Video video = clip("3712345678901234567");
        Player player = scrolledAwayAt(video, 6_500);
        player.position = video.length;
        ResumePlayback.ended(player);

        assertEquals(0, ResumePlayback.placesKept());
        assertEquals(Collections.emptyList(), startedAgain(new Player(video)).seeks);
        assertTrue(feedLine(), feedLine().contains(ResumePlayback.PLACE_CLEARED + " 1"));
    }

    @Test
    public void aStopAtTheEndForgetsThePlace() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        scrolledAwayAt(video, video.length - 500);

        assertEquals(0, ResumePlayback.placesKept());
        assertEquals(Collections.emptyList(), startedAgain(new Player(video)).seeks);
    }

    /**
     * A stop in the first second says nothing about where the video was left: Instagram stops a
     * player it's about to give another video, and you may scroll past one you came back to. The
     * place it had stays.
     */
    @Test
    public void aStopInTheFirstSecondKeepsThePlaceItHad() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        Player passing = new Player(video);
        ResumePlayback.started(passing);
        passing.position = 400;
        ResumePlayback.stopped(passing, "scroll");

        assertEquals(Collections.singletonList(6_500), startedAgain(new Player(video)).seeks);
    }

    /** A later scroll away moves the place on. */
    @Test
    public void theLatestPlaceWins() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        Player back = startedAgain(new Player(video));
        back.position = 14_000;
        ResumePlayback.stopped(back, "scroll");

        assertEquals(Collections.singletonList(14_000), startedAgain(new Player(video)).seeks);
    }

    /** A seek made before the resume, your drag of the scrubber or Instagram's own, wins. */
    @Test
    public void aSeekBeforeTheResumeWins() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        Player back = new Player(video);
        ResumePlayback.started(back);
        ResumePlayback.seeking(back, 20_000);
        ResumePlaybackForTests.runLater();

        assertEquals(Collections.emptyList(), back.seeks);
    }

    /** A start Instagram already put past the start is where it wants the video. */
    @Test
    public void aStartAlreadyPastTheStartIsLeftAlone() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        Player back = new Player(video);
        back.position = 5 * SECOND;
        ResumePlayback.started(back);
        ResumePlaybackForTests.runLater();

        assertEquals(Collections.emptyList(), back.seeks);
    }

    @Test
    public void aDifferentVideoStartsAtZero() {
        scrolledAwayAt(clip("111111111"), 6_500);
        assertEquals(Collections.emptyList(), startedAgain(new Player(clip("222222222"))).seeks);
        assertTrue(feedLine(), feedLine().contains(ResumePlayback.NO_PLACE + " 2"));
    }

    /** A video whose length the player can't read yet keeps its place for a start that can. */
    @Test
    public void anUnknownLengthLeavesThePlaceForAStartThatKnowsIt() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        Video unknown = new Video(video.id, 0);
        assertEquals(Collections.emptyList(), startedAgain(new Player(unknown)).seeks);
        assertEquals(Collections.singletonList(6_500), startedAgain(new Player(video)).seeks);
    }

    /** Places are kept for this visit only: they never reach the saved points' file. */
    @Test
    public void nothingIsWrittenToTheSavedPoints() {
        scrolledAwayAt(clip("3712345678901234567"), 6_500);
        assertEquals(1, ResumePlayback.placesKept());
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE).getAll().isEmpty());
    }

    /** Signing out forgets that account's places; an account switch keeps them for its return. */
    @Test
    public void signingOutForgetsThatAccountsPlaces() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);

        ResumePlayback.sessionEnded(new Session(ResumePlaybackForTests.ACCOUNT, false));
        assertEquals("an account switch forgot a place", 1, ResumePlayback.placesKept());

        ResumePlayback.sessionEnded(new Session(ResumePlaybackForTests.ACCOUNT, true));
        assertEquals(0, ResumePlayback.placesKept());
    }

    /** One account's place never moves another account's player. */
    @Test
    public void anotherAccountsPlayerStartsAtZero() {
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        Player other = new Player(video);
        other.account = "17841400000000002";
        assertEquals(Collections.emptyList(), startedAgain(other).seeks);
    }

    /**
     * With Resume long videos on too, a long video resumes once, from the place this visit kept,
     * which is the newer of the two.
     */
    @Test
    public void withBothSwitchesALongVideoResumesOnceFromItsPlace() {
        Settings.RESUME_LONG_VIDEOS.save(true);
        Video film = new Video("3712345678901234567", 40 * MINUTE);
        scrolledAwayAt(film, 12 * MINUTE);

        Player back = startedAgain(new Player(film));

        assertEquals(Collections.singletonList(12 * MINUTE), back.seeks);
        assertTrue(feedLine(), feedLine().contains(ResumePlayback.RESUMED + " 1"));
    }

    /** With only Resume long videos on, a short clip still starts over, as before. */
    @Test
    public void resumeLongVideosAloneLeavesClipsAlone() {
        Settings.RESUME_FEED_VIDEOS.save(false);
        Settings.RESUME_LONG_VIDEOS.save(true);
        Video video = clip("3712345678901234567");
        scrolledAwayAt(video, 6_500);
        assertEquals(Collections.emptyList(), startedAgain(new Player(video)).seeks);
        assertEquals(0, ResumePlayback.placesKept());
    }

    /** Clear history forgets this visit's places with the saved points. */
    @Test
    public void clearingHistoryForgetsThePlaces() {
        scrolledAwayAt(clip("3712345678901234567"), 6_500);
        ResumePlayback.clearHistory();
        assertEquals(0, ResumePlayback.placesKept());
    }

    /** The places are bounded: past the cap, the one used longest ago goes. */
    @Test
    public void thePlacesAreBounded() {
        ResumePlayback.Places places = new ResumePlayback.Places();
        for (int i = 0; i <= ResumePlayback.Places.MAX_PLACES; i++) places.put("17841400000000001/" + i, 5_000);
        assertEquals(ResumePlayback.Places.MAX_PLACES, places.size());
        assertEquals("the oldest place stayed", null, places.get("17841400000000001/0"));
        assertEquals(Integer.valueOf(5_000), places.get("17841400000000001/" + ResumePlayback.Places.MAX_PLACES));
    }
}
