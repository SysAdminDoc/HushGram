/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.media.ResumePlaybackForTests.Player;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests.ProductType;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests.Video;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.WorkerPoolForTests;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The rule Resume long videos holds every player to: a long video or reel stopped partway is saved,
 * its next first start in a player seeks back there once, and a seek Instagram makes first, a start
 * Instagram already placed, a new video and the end or loop of a video all start where Instagram
 * starts them. Off, paused, before the settings are ready, or when a stub isn't filled in, nothing
 * is saved and nothing seeks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ResumePlaybackTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int MINUTE = 60_000;

    @Before
    public void start() {
        SystemClock.sleep(60_000);
        ResumePlaybackForTests.install();
        HookStatus.clear();
        FeedFilterCounters.clear();
        Settings.RESUME_LONG_VIDEOS.save(true);
    }

    @After
    public void restore() throws Exception {
        // A process reset must not hand its queued aging work to the next test's store.
        Utils.awaitBackgroundTasksForTests();
        PauseForTests.resume();
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
        ResumePlaybackForTests.forget();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private static Video longVideo(String id) {
        return new Video(id, 40 * MINUTE);
    }

    /** A player of [video] stopped at [at]. */
    private static void leftAt(Video video, int at) {
        Player player = new Player(video);
        player.position = at;
        ResumePlayback.stopped(player, "scroll");
    }

    /** A new player of [video] started at 0, with the resume it posts run. */
    private static Player opened(Video video) {
        return openedWith(new Player(video));
    }

    private static Player openedWith(Player player) {
        ResumePlayback.started(player);
        ResumePlaybackForTests.runLater();
        return player;
    }

    private static String report() {
        return String.join("\n", FeedFilterCounters.report());
    }

    @Test
    public void theSwitchStartsOff() {
        Settings.RESUME_LONG_VIDEOS.resetToDefault();
        assertFalse("nothing is saved or moved until it's turned on", Settings.RESUME_LONG_VIDEOS.get());
    }

    @Test
    public void aLongVideoLeftPartwayResumesThereOnce() {
        Video video = longVideo("3712345678901234567");
        leftAt(video, 12 * MINUTE + 34_000);
        Player player = opened(video);
        assertEquals(Collections.singletonList(754_000), player.seeks);

        // Instagram's later starts of the same video in the same player, after a pause or a
        // buffer, are its own.
        player.position = 20 * MINUTE;
        openedWith(player);
        assertEquals("it resumed a second time", 1, player.seeks.size());
        String report = report();
        assertTrue(report, report.contains("point saved 1") && report.contains("resumed 1"));
    }

    @Test
    public void aNewVideoStartsAtZero() {
        leftAt(longVideo("111111111"), 10 * MINUTE);
        Player other = opened(longVideo("222222222"));
        assertEquals("a video nobody left partway moved", Collections.emptyList(), other.seeks);
        assertTrue(report(), report().contains("no point yet 1"));
    }

    @Test
    public void aStopNearTheStartSavesNothingAndKeepsAnEarlierPoint() {
        Video video = longVideo("111111111");
        leftAt(video, ResumePlayback.MIN_SAVED_MS - 1);
        assertTrue("a glance saved a point", opened(video).seeks.isEmpty());

        leftAt(video, 9 * MINUTE);
        leftAt(video, 2_000);
        assertEquals("a stop at the start wiped the point", Collections.singletonList(540_000), opened(video).seeks);
    }

    @Test
    public void theEndOfTheVideoForgetsItsPoint() {
        Video video = longVideo("111111111");
        leftAt(video, 30 * MINUTE);
        leftAt(video, video.length - ResumePlayback.END_MARGIN_MS);
        assertTrue("a finished video resumed", opened(video).seeks.isEmpty());

        // Instagram's own end of a video, or its loop back to the start, forgets it too.
        leftAt(video, 30 * MINUTE);
        ResumePlayback.ended(new Player(video));
        assertTrue("a video that played to its end resumed", opened(video).seeks.isEmpty());
        assertTrue(report(), report().contains("point cleared at the end 2"));
    }

    @Test
    public void shortVideosLiveVideosAndAdsAreLeftAloneButALongReelResumes() {
        Video clip = new Video("1", ResumePlayback.MIN_DURATION_MS - 1);
        leftAt(clip, 60_000);
        assertTrue(opened(clip).seeks.isEmpty());

        Video live = longVideo("2");
        live.product = ProductType.LIVE;
        Video ad = longVideo("3");
        ad.product = ProductType.AD;
        Video sponsored = longVideo("4");
        sponsored.product = ProductType.CLIPS;
        sponsored.sponsored = true;
        for (Video video : new Video[]{live, ad, sponsored}) {
            leftAt(video, 10 * MINUTE);
            assertTrue(video.id, opened(video).seeks.isEmpty());
        }
        String report = report();
        assertFalse("something was saved: " + report, report.contains("point saved"));
        for (String kind : new String[]{"under two minutes 1", "live 1", "ad 2"}) {
            assertTrue(kind + " in " + report, report.contains(kind));
        }

        // Instagram shares nearly every video as a reel, so a long one resumes like any video, and
        // so does the replay of a live video.
        Video reel = longVideo("5");
        reel.product = ProductType.CLIPS;
        Video replay = longVideo("6");
        replay.product = ProductType.LIVE_VOD;
        for (Video video : new Video[]{reel, replay}) {
            leftAt(video, 10 * MINUTE);
            assertEquals(video.id, Collections.singletonList(600_000), opened(video).seeks);
        }
    }

    @Test
    public void theProductTypeIsReadByItsName() {
        assertTrue(ResumePlayback.factsOf("1", ProductType.LIVE, false).live);
        assertTrue(ResumePlayback.factsOf("1", ProductType.AD, false).ad);
        assertTrue(ResumePlayback.factsOf("1", ProductType.FEED, true).ad);
        ResumePlayback.Facts reel = ResumePlayback.factsOf("1", ProductType.CLIPS, false);
        assertFalse(reel.live || reel.ad);
        ResumePlayback.Facts unknown = ResumePlayback.factsOf("1", "LIVE", false);
        assertFalse("a type that isn't an enum", unknown.live || unknown.ad);
        assertFalse(ResumePlayback.factsOf("1", null, false).live);
        assertNull(ResumePlayback.factsOf(null, ProductType.FEED, false));
        assertNull(ResumePlayback.factsOf("", ProductType.FEED, false));
    }

    @Test
    public void aSeekBeforeTheResumeWins() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);

        // Instagram carrying a position over, before the start.
        Player carried = new Player(video);
        ResumePlayback.seeking(carried, 95_000);
        assertTrue("the resume ran over Instagram's own position", openedWith(carried).seeks.isEmpty());

        // A drag of the scrubber after the start, before the resume runs.
        Player dragged = new Player(video);
        ResumePlayback.started(dragged);
        ResumePlayback.seeking(dragged, 5 * MINUTE);
        ResumePlaybackForTests.runLater();
        assertTrue("the resume ran over a drag", dragged.seeks.isEmpty());

        // A seek back to the start, which isn't someone placing the video, lets it resume.
        Player reset = new Player(video);
        ResumePlayback.seeking(reset, 0);
        assertEquals(Collections.singletonList(600_000), openedWith(reset).seeks);
        assertTrue(report(), report().contains("seek before the resume 2"));
    }

    @Test
    public void aStartInstagramPlacedStaysWhereItIs() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        Player carried = new Player(video);
        carried.position = ResumePlayback.START_WINDOW_MS + 1;
        assertTrue("a start past the start moved", openedWith(carried).seeks.isEmpty());
        assertTrue(report(), report().contains("started past the start 1"));
    }

    @Test
    public void thePlayerMovingOnBeforeTheResumeRunsStopsIt() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        Player player = new Player(video);
        ResumePlayback.started(player);
        player.video = longVideo("333333333");
        ResumePlaybackForTests.runLater();
        assertTrue("the next video was sought to the last one's point", player.seeks.isEmpty());
        assertTrue(report(), report().contains("moved on before the resume 1"));
    }

    @Test
    public void theResumesOwnSeekSavesNothingAndStopsNothing() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        Player player = new Player(video);
        player.position = 800;
        // A seek of a playing video can pause it first, which runs the stop hook at the old
        // position, and restart it, which runs the start hook.
        player.duringSeek = () -> {
            ResumePlayback.stopped(player, "scroll");
            ResumePlayback.started(player);
        };
        assertEquals(Collections.singletonList(600_000), openedWith(player).seeks);
        assertTrue(report(), report().contains("point saved 1") && report().contains("resumed 1"));
        // The point is still 10:00.
        assertEquals(Collections.singletonList(600_000), opened(video).seeks);
    }

    @Test
    public void aSeeksOwnPauseSavesNothing() {
        Video video = longVideo("111111111");
        Player player = new Player(video);
        openedWith(player);
        // A drag of the scrubber on a playing video: Instagram pauses it for the seek, at the place
        // it's leaving, and plays on from the new one.
        player.position = 5 * MINUTE;
        for (String reason : TapToPlay.MOMENTARY) ResumePlayback.stopped(player, reason);
        // The long video viewer's scrubber, by name, so it can't drop out of the set unnoticed.
        ResumePlayback.stopped(player, "Seek start");
        assertTrue("a seek's pause saved a point", opened(video).seeks.isEmpty());
        assertFalse(report(), report().contains("point saved"));

        // The control: a real pause at the same place saves it.
        ResumePlayback.stopped(player, "fragment_paused");
        assertEquals(Collections.singletonList(300_000), opened(video).seeks);
    }

    @Test
    public void aNewBindStartsThePlayerOverUnlessItJustStarted() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        Player player = new Player(video);
        assertEquals(1, openedWith(player).seeks.size());

        // A bind right after the start belongs to it, and isn't a new video.
        ResumePlayback.rebound(player);
        player.position = 0;
        assertEquals("a bind of the same start resumed again", 1, openedWith(player).seeks.size());

        // Later, the player given the same video again starts over. Its bind saves where it was.
        SystemClock.sleep(ResumePlayback.BIND_GRACE_MS + 1);
        player.position = 11 * MINUTE;
        ResumePlayback.rebound(player);
        player.position = 0;
        assertEquals(Collections.singletonList(660_000), openedWith(player).seeks.subList(1, 2));
    }

    @Test
    public void aPlayerThatLetGoOfItsVideoSavesNothingAndDoesntThrow() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        Player released = new Player(video);
        released.released = true;
        released.position = 20 * MINUTE;
        ResumePlayback.stopped(released, "fragment_stopped");
        ResumePlayback.rebound(released);
        assertEquals("the unreadable length moved the point", Collections.singletonList(600_000), opened(video).seeks);
        String missing = HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).toString();
        assertFalse(missing, missing.contains("threw"));
    }

    @Test
    public void thePointsOutliveTheProcess() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        // A new process: nothing cached, the file read again.
        ResumePlaybackForTests.install();
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(600_000), opened(video).seeks);
        assertEquals(1, RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE).getAll().size());
    }

    @Test
    public void offOrPausedNothingIsSavedOrMoved() {
        Video video = longVideo("111111111");
        Settings.RESUME_LONG_VIDEOS.save(false);
        leftAt(video, 10 * MINUTE);
        Settings.RESUME_LONG_VIDEOS.save(true);
        assertTrue("a stop saved with the switch off", opened(video).seeks.isEmpty());

        leftAt(video, 10 * MINUTE);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("a paused start moved", opened(video).seeks.isEmpty());
        PauseForTests.resume();

        Settings.RESUME_LONG_VIDEOS.save(false);
        assertTrue("a start with the switch off moved", opened(video).seeks.isEmpty());
        Player late = new Player(video);
        Settings.RESUME_LONG_VIDEOS.save(true);
        ResumePlayback.started(late);
        Settings.RESUME_LONG_VIDEOS.save(false);
        ResumePlaybackForTests.runLater();
        assertTrue("the switch turned off before the resume ran, and it moved", late.seeks.isEmpty());

        Settings.RESUME_LONG_VIDEOS.save(true);
        assertEquals("the control: on, the same start resumes", Collections.singletonList(600_000), opened(video).seeks);
    }

    @Test
    public void beforeTheSettingsAreReadyNothingIsSavedOrMoved() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        SettingsContextRule.withoutContext(() -> {
            leftAt(video, 20 * MINUTE);
            assertTrue(opened(video).seeks.isEmpty());
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> assertTrue(opened(video).seeks.isEmpty()));
        assertEquals("the control: ready, the saved point", Collections.singletonList(600_000), opened(video).seeks);
    }

    @Test
    public void unfilledStubsAndOddPlayersFailOpen() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);

        // The app's own stubs, as they are before the patch fills them.
        ResumePlayback.access = ResumePlayback.PATCHED;
        Player player = new Player(video);
        ResumePlayback.started(player);
        ResumePlayback.stopped(player, "scroll");
        ResumePlayback.ended(player);
        ResumePlaybackForTests.runLater();
        assertTrue(player.seeks.isEmpty());
        List<String> missing = HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS);
        assertTrue(missing.toString(), missing.toString().contains("the video reader"));
        assertEquals(ResumePlayback.NOT_PATCHED_TIME, ResumePlayback.position(player));
        assertEquals(ResumePlayback.NOT_PATCHED_TIME, ResumePlayback.duration(player));
        assertSame(ResumePlayback.NOT_PATCHED, ResumePlayback.videoSource(player));
        assertNull(ResumePlayback.videoId(video));
        assertNull(ResumePlayback.productType(video));
        assertFalse(ResumePlayback.sponsored(video));
        assertFalse(ResumePlayback.seekPlayer(player, 1, false, false));

        // A player with no video, and a null player.
        ResumePlaybackForTests.install();
        ResumePlayback.started(new Player(null));
        ResumePlayback.stopped(new Player(null), "scroll");
        ResumePlayback.started(null);
        ResumePlayback.stopped(null, null);
        ResumePlayback.seeking(null, 5);
        ResumePlayback.ended(null);
        ResumePlayback.rebound(null);
        ResumePlaybackForTests.runLater();
        assertEquals("the control: the saved point", Collections.singletonList(600_000), opened(video).seeks);
    }

    @Test
    public void aThrowInsideLeavesThePlayerAlone() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        ResumePlayback.access = new ResumePlayback.Player() {
            @Override public int position(Object p) { throw new IllegalArgumentException("position"); }
            @Override public int duration(Object p) { return 40 * MINUTE; }
            @Override public ResumePlayback.Facts facts(Object p) { return ResumePlayback.factsOf(
                    ResumePlayback.ownedKey(ResumePlaybackForTests.ACCOUNT, "111111111"), null, false); }
            @Override public boolean seek(Object p, int ms) { throw new IllegalArgumentException("seek"); }
        };
        Player player = new Player(video);
        ResumePlayback.started(player);
        ResumePlayback.stopped(player, "scroll");
        ResumePlayback.rebound(player);
        ResumePlaybackForTests.runLater();
        assertTrue(player.seeks.isEmpty());
        String missing = HookStatus.missing(FamilyNames.RESUME_LONG_VIDEOS).toString();
        for (String hook : new String[]{"player start", "player pause", "player bind"}) {
            assertTrue(missing, missing.contains("'" + hook + "' hook (it threw java.lang.IllegalArgumentException)"));
        }
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void rejectedAgingRetriesOnTheNextStart() throws Exception {
        Settings.RESUME_LONG_VIDEOS.save(false);
        SharedPreferences file = RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
        file.edit().putString("expired", ResumePoints.encode(90_000,
                System.currentTimeMillis() - ResumePoints.KEEP_MS - 60_000)).commit();
        try (WorkerPoolForTests ignored = WorkerPoolForTests.fill()) {
            ResumePlayback.started(new Object());
            assertTrue(file.contains("expired"));
        }
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertFalse("a refused cleanup was treated as completed", file.contains("expired"));
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void failedAgingReadRetriesWithoutLosingLivePoints() throws Exception {
        Settings.RESUME_LONG_VIDEOS.save(false);
        SharedPreferences file = RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        file.edit().putString("expired", ResumePoints.encode(90_000, now - ResumePoints.KEEP_MS - 60_000))
                .putString("live", ResumePoints.encode(120_000, now)).commit();
        AtomicInteger reads = new AtomicInteger();
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAll") && reads.incrementAndGet() == 1) {
                        throw new IllegalStateException("storage unavailable");
                    }
                    return method.invoke(file, args);
                });
        ResumePoints points = new ResumePoints(failing);
        ResumePlayback.pointsForTests = points;
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertTrue(file.contains("expired"));
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertFalse("a failed read was treated as completed", file.contains("expired"));
        assertEquals(120_000, points.get("live", now).positionMs);
        assertEquals(2, reads.get());
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void failedDiskAgingRetriesOnStartsUntilDurableThenStops() throws Exception {
        Settings.RESUME_LONG_VIDEOS.save(false);
        SharedPreferences file = RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        file.edit().putString("expired", ResumePoints.encode(90_000, now - ResumePoints.KEEP_MS - 60_000))
                .putString("live", ResumePoints.encode(120_000, now)).commit();
        Map<String, Object> durable = new HashMap<>(file.getAll());
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(file, args);
                    SharedPreferences.Editor editor = file.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editProxy, editMethod, editArgs) -> {
                                Object result = editMethod.invoke(editor, editArgs);
                                if (editMethod.getName().equals("commit")) {
                                    assertFalse("cleanup blocked the UI thread", Utils.isCurrentlyOnMainThread());
                                    if (commits.incrementAndGet() <= 2) return false;
                                    durable.clear();
                                    durable.putAll(file.getAll());
                                    return true;
                                }
                                return result == editor ? editProxy : result;
                            });
                });
        ResumePlayback.pointsForTests = new ResumePoints(failing);
        for (int attempt = 0; attempt < 2; attempt++) {
            ResumePlayback.started(new Object());
            Utils.awaitBackgroundTasksForTests();
            assertTrue(durable.containsKey("expired"));
        }
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertFalse("failed persistence was treated as completed aging", durable.containsKey("expired"));
        assertEquals(ResumePoints.encode(120_000, now), durable.get("live"));
        assertEquals(3, commits.get());
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertEquals("successful aging was repeated", 3, commits.get());
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void aQueuedCleanupCannotAgeAReplacementProcessStore() throws Exception {
        Settings.RESUME_LONG_VIDEOS.save(false);
        SharedPreferences file = RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
        Field field = ResumePlayback.class.getDeclaredField("POINTS_LOCK");
        field.setAccessible(true);
        synchronized (field.get(null)) {
            ResumePlayback.started(new Object());
            ResumePlaybackForTests.forget();
            file.edit().putString("replacement", ResumePoints.encode(90_000,
                    System.currentTimeMillis() - ResumePoints.KEEP_MS - 60_000)).commit();
        }
        Utils.awaitBackgroundTasksForTests();
        assertTrue("old queued work aged the replacement store", file.contains("replacement"));
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertFalse(file.contains("replacement"));
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void overlappingStartsShareOneCleanup() throws Exception {
        Settings.RESUME_LONG_VIDEOS.save(false);
        SharedPreferences file = RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        SharedPreferences slow = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAll")) {
                        reads.incrementAndGet();
                        reading.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("reader was not released");
                    }
                    return method.invoke(file, args);
                });
        ResumePlayback.pointsForTests = new ResumePoints(slow);
        try {
            ResumePlayback.started(new Object());
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) ResumePlayback.started(new Object());
        } finally {
            release.countDown();
        }
        Utils.awaitBackgroundTasksForTests();
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertEquals(1, reads.get());
    }

    @Test
    @Config(sdk = {28, 30, 37})
    public void onlyTheFirstVideoStartOfAProcessAgesThePoints() throws Exception {
        Settings.RESUME_LONG_VIDEOS.save(false);
        android.content.SharedPreferences file = RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
        String old = ResumePoints.encode(90_000, System.currentTimeMillis() - ResumePoints.KEEP_MS - 60_000);
        file.edit().putString("first", old).commit();
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertFalse("the first start left a point past its age", file.contains("first"));

        file.edit().putString("second", old).commit();
        ResumePlayback.started(new Object());
        Utils.awaitBackgroundTasksForTests();
        assertTrue("every start read the whole file again", file.contains("second"));
        file.edit().clear().commit();
    }

    @Test
    public void positionsReadAsAPlayerShowsThem() {
        assertEquals("0:00", ResumePlayback.clock(0));
        assertEquals("12:34", ResumePlayback.clock(754_000));
        assertEquals("125:09", ResumePlayback.clock(125 * MINUTE + 9_999));
        assertEquals("0:00", ResumePlayback.clock(-5));
    }

    @Test
    public void eachAccountKeepsItsOwnPointForTheSameVideo() {
        Video video = longVideo("3712345678901234567");
        leftAt(video, 5 * MINUTE);
        Player elsewhere = new Player(video);
        elsewhere.account = "17841400000000002";
        elsewhere.position = 9 * MINUTE;
        ResumePlayback.stopped(elsewhere, "scroll");

        assertEquals(Collections.singletonList(5 * MINUTE), opened(video).seeks);
        Player other = new Player(video);
        other.account = "17841400000000002";
        assertEquals(Collections.singletonList(9 * MINUTE), openedWith(other).seeks);
        Player third = new Player(video);
        third.account = "17841400000000003";
        assertEquals("another account's point moved it", Collections.emptyList(), openedWith(third).seeks);

        // The file holds a hash of each account, never its ID.
        Map<String, ?> saved = RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE).getAll();
        assertEquals(2, saved.size());
        for (String key : saved.keySet()) {
            assertTrue(key, key.endsWith("/3712345678901234567"));
            assertFalse(key, key.contains("17841400000000"));
        }
    }

    @Test
    public void aPlayerWithoutAnAccountSavesAndResumesNothing() {
        Video video = longVideo("111111111");
        leftAt(video, 10 * MINUTE);
        Player unknown = new Player(video);
        unknown.account = null;
        unknown.position = 20 * MINUTE;
        ResumePlayback.stopped(unknown, "scroll");
        Player again = new Player(video);
        again.account = null;
        assertEquals(Collections.emptyList(), openedWith(again).seeks);
        assertEquals("the known account's point", Collections.singletonList(600_000), opened(video).seeks);
    }

    // ---- an account's session ending -----------------------------------------------------------

    private static final String OTHER = "17841400000000002";

    /** [video]'s point for the other account, at [at]. */
    private static void leftByOther(Video video, int at) {
        Player other = new Player(video);
        other.account = OTHER;
        other.position = at;
        ResumePlayback.stopped(other, "scroll");
    }

    private static Player othersPlayer(Video video) {
        Player player = new Player(video);
        player.account = OTHER;
        return player;
    }

    private static SharedPreferences pointsFile() {
        return RuntimeEnvironment.getApplication().getSharedPreferences(ResumePoints.FILE, Context.MODE_PRIVATE);
    }

    /**
     * An account switch ends the old account's session. A resume its player still had waiting is
     * dropped and never seeks, the other account's player resumes as usual, and the old account's
     * point stays for when it's back.
     */
    @Test
    public void aSwitchDropsTheOldAccountsWaitingResume() {
        Video video = longVideo("3712345678901234567");
        leftAt(video, 5 * MINUTE);
        leftByOther(video, 9 * MINUTE);

        Player mine = new Player(video);
        ResumePlayback.started(mine);
        Player theirs = othersPlayer(video);
        ResumePlayback.started(theirs);
        ResumePlayback.sessionEnded(new ResumePlaybackForTests.Session(ResumePlaybackForTests.ACCOUNT, false));
        ResumePlaybackForTests.runLater();

        assertEquals("the ended account's player sought", Collections.emptyList(), mine.seeks);
        assertEquals(Collections.singletonList(9 * MINUTE), theirs.seeks);
        assertEquals(2, pointsFile().getAll().size());
        assertEquals("its point stays for when it's back", Collections.singletonList(5 * MINUTE), opened(video).seeks);
        String report = report();
        assertTrue(report, report.contains(ResumePlayback.SESSION_ENDED + " 1"));
        assertTrue(report, report.contains(ResumePlayback.MOVED_ON + " 1"));
        assertFalse(report, report.contains(ResumePlayback.ACCOUNT_FORGOTTEN));
    }

    /**
     * Signing out, or removing the account from the phone, forgets that account's points at once,
     * in the file too, with the switch off as well. Another account's points stay.
     */
    @Test
    public void signingOutForgetsThatAccountsPoints() throws Exception {
        Video video = longVideo("3712345678901234567");
        leftAt(video, 5 * MINUTE);
        leftAt(longVideo("3712345678901234568"), 6 * MINUTE);
        leftByOther(video, 9 * MINUTE);
        assertEquals(3, pointsFile().getAll().size());

        Settings.RESUME_LONG_VIDEOS.save(false);
        ResumePlayback.sessionEnded(new ResumePlaybackForTests.Session(ResumePlaybackForTests.ACCOUNT, true));
        Utils.awaitBackgroundTasksForTests();
        assertEquals("only the other account's point is left", 1, pointsFile().getAll().size());

        Settings.RESUME_LONG_VIDEOS.save(true);
        assertEquals("a signed-out account's point moved it", Collections.emptyList(), opened(video).seeks);
        assertEquals(Collections.singletonList(9 * MINUTE), openedWith(othersPlayer(video)).seeks);
        assertTrue(report(), report().contains(ResumePlayback.ACCOUNT_FORGOTTEN + " 1"));
    }

    /**
     * At a sign-out Instagram ends the session without waiting for its players, so they stop and
     * pause after the account's points are gone. Those save nothing, and a player still on that
     * session resumes nothing.
     */
    @Test
    public void aSignedOutAccountsPlayersSaveNothingAsTheyGo() throws Exception {
        Video video = longVideo("3712345678901234567");
        Player mine = new Player(video);
        mine.position = 5 * MINUTE;
        ResumePlayback.stopped(mine, "scroll");
        assertEquals("a stop before the sign-out saves", 1, pointsFile().getAll().size());

        mine.signedOut = true;
        ResumePlayback.sessionEnded(new ResumePlaybackForTests.Session(ResumePlaybackForTests.ACCOUNT, true));
        Utils.awaitBackgroundTasksForTests();
        assertTrue(pointsFile().getAll().isEmpty());
        mine.position = 7 * MINUTE;
        ResumePlayback.stopped(mine, "teardown");
        ResumePlayback.rebound(mine);
        assertTrue("the teardown saved a point again", pointsFile().getAll().isEmpty());

        leftAt(video, 9 * MINUTE);
        Player stale = new Player(video);
        stale.signedOut = true;
        assertEquals("a signed-out session's player resumed", Collections.emptyList(), openedWith(stale).seeks);
        assertEquals("signing back in finds the new point", Collections.singletonList(9 * MINUTE), opened(video).seeks);
    }

    @Test
    public void onlyASignedInSessionOwnsAPoint() {
        ResumePlayback.Session sessions = ResumePlaybackForTests.SESSIONS;
        String account = ResumePlaybackForTests.ACCOUNT;
        assertEquals(account, ResumePlayback.owner(new ResumePlaybackForTests.Session(account, false), sessions));
        assertNull(ResumePlayback.owner(new ResumePlaybackForTests.Session(account, true), sessions));
        assertNull(ResumePlayback.owner(null, sessions));
        assertNull("the unfilled stubs", ResumePlayback.owner(new Object(), ResumePlayback.SESSION_STUBS));
        assertNull(ResumePlayback.playerSession(new Object()));
    }

    /**
     * Instagram's session end runs inside its session manager's lock, at a sign-out on the main
     * thread. The hook lets go of the account's players there and leaves the points to a worker,
     * so it never waits on the points lock, which a resume holds across Instagram's own seek.
     */
    @Test
    public void theSessionEndNeverWaitsForThePoints() throws Exception {
        Video video = longVideo("3712345678901234567");
        leftAt(video, 5 * MINUTE);
        Player mine = new Player(video);
        ResumePlayback.started(mine);
        assertEquals(1, ResumePlayback.playersKnown());
        Field field = ResumePlayback.class.getDeclaredField("POINTS_LOCK");
        field.setAccessible(true);
        CountDownLatch ended = new CountDownLatch(1);
        Thread ending = new Thread(() -> {
            ResumePlayback.sessionEnded(new ResumePlaybackForTests.Session(ResumePlaybackForTests.ACCOUNT, true));
            ended.countDown();
        });
        synchronized (field.get(null)) {
            ending.start();
            assertTrue("the session end waited on the points lock", ended.await(10, TimeUnit.SECONDS));
            assertEquals("the players weren't let go at once", 0, ResumePlayback.playersKnown());
            assertEquals("the file changed under the lock", 1, pointsFile().getAll().size());
        }
        ending.join();
        Utils.awaitBackgroundTasksForTests();
        ResumePlaybackForTests.runLater();
        assertEquals(Collections.emptyList(), mine.seeks);
        assertTrue(pointsFile().getAll().isEmpty());
        assertTrue(report(), report().contains(ResumePlayback.ACCOUNT_FORGOTTEN + " 1"));
    }

    /** Undo of an earlier Clear brings back the other account's points, never a signed-out one's. */
    @Test
    public void undoDoesntBringBackASignedOutAccountsPoints() throws Exception {
        Video video = longVideo("3712345678901234567");
        leftAt(video, 5 * MINUTE);
        leftByOther(video, 9 * MINUTE);
        ResumePlayback.clearHistory();
        assertTrue(pointsFile().getAll().isEmpty());

        ResumePlayback.sessionEnded(new ResumePlaybackForTests.Session(ResumePlaybackForTests.ACCOUNT, true));
        Utils.awaitBackgroundTasksForTests();
        assertTrue(ResumePlayback.undoHistory());

        assertEquals(1, pointsFile().getAll().size());
        assertEquals(Collections.emptyList(), opened(video).seeks);
        assertEquals(Collections.singletonList(9 * MINUTE), openedWith(othersPlayer(video)).seeks);
    }

    /**
     * A session whose account the stub can't read can't be told from another, so every waiting
     * resume is dropped and no point is touched. No session at all does nothing.
     */
    @Test
    public void aSessionWithoutAnAccountDropsEveryWaitingResume() {
        Video video = longVideo("3712345678901234567");
        leftAt(video, 5 * MINUTE);
        leftByOther(video, 9 * MINUTE);
        ResumePlayback.sessionEnded(null);
        assertFalse(report(), report().contains(ResumePlayback.SESSION_ENDED));

        Player mine = new Player(video);
        ResumePlayback.started(mine);
        Player theirs = othersPlayer(video);
        ResumePlayback.started(theirs);
        ResumePlayback.sessionEnded(new ResumePlaybackForTests.Session(null, true));
        ResumePlaybackForTests.runLater();

        assertEquals(Collections.emptyList(), mine.seeks);
        assertEquals(Collections.emptyList(), theirs.seeks);
        assertEquals(2, pointsFile().getAll().size());
        assertFalse(report(), report().contains(ResumePlayback.ACCOUNT_FORGOTTEN));
    }

    /** Until the patch fills the stubs, a session reads as unknown and as a switch: points stay. */
    @Test
    public void theUnfilledStubsForgetNoPoint() {
        assertNull(ResumePlayback.sessionUserId(new Object()));
        assertFalse(ResumePlayback.sessionLoggedOut(new Object()));
        leftAt(longVideo("3712345678901234567"), 5 * MINUTE);
        ResumePlayback.sessions = ResumePlayback.SESSION_STUBS;
        ResumePlayback.sessionEnded(new Object());
        assertEquals(1, pointsFile().getAll().size());
    }

    @Test
    public void pointsFromBeforeAccountsAreDeleted() {
        android.content.SharedPreferences unowned = RuntimeEnvironment.getApplication()
                .getSharedPreferences(ResumePoints.UNOWNED_FILE, Context.MODE_PRIVATE);
        unowned.edit().putString("111111111", ResumePoints.encode(600_000, System.currentTimeMillis())).commit();
        ResumePlaybackForTests.install();

        assertEquals("an unowned point moved it", Collections.emptyList(), opened(longVideo("111111111")).seeks);
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences(ResumePoints.UNOWNED_FILE, Context.MODE_PRIVATE)
                .getAll().isEmpty());
    }
}
