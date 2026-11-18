/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Playback speed and Block in a feed post's menu (#743): when each row shows and what a tap does. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = PostInfoTest.Bridges.class)
public class FeedPostRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String THEIRS = "3712345678901234568_51234567";
    private static final String MINE = "3712345678901234569_1";

    /** Stands in for Instagram: each controller's manager, what each holds and each player's speeds. */
    private static class Feed implements FeedPostRows.Bridge {
        final Map<Object, Object> managers = new HashMap<>();
        final Map<Object, Object> playing = new HashMap<>();
        final Map<Object, Object> players = new HashMap<>();
        final Map<Object, Object> playbacks = new HashMap<>();
        final List<String> speeds = new ArrayList<>();
        final List<Object[]> sheets = new ArrayList<>();
        boolean canBlock = true;

        @Override public boolean canBlock() {
            return canBlock;
        }

        @Override public void block(Object helper, Object owner, String module, String ownerId) {
            sheets.add(new Object[] {helper, owner, module, ownerId});
        }

        @Override public Object managerOf(Object controller) {
            return managers.get(controller);
        }

        @Override public Object mediaOf(Object manager) {
            return playing.get(manager);
        }

        @Override public Object playerOf(Object manager) {
            return players.get(manager);
        }

        @Override public Object playbackOf(Object manager) {
            return playbacks.get(manager);
        }

        @Override public void setSpeed(Object player, float speed) {
            speeds.add(player + "=" + speed);
        }
    }

    private final FeedPostRows.Bridge originalBridge = FeedPostRows.bridge;
    private final ReelSpeedButton.Lists originalLists = ReelSpeedButton.lists;
    private final Supplier<String> originalAccounts = DownloadHistory.accounts;
    private final Feed feed = new Feed();
    private final Object controller = new Object();
    private final Object manager = new Object();
    private final Object player = "player";
    private ActivityController<Activity> screen;
    private Activity activity;
    private Float shownSpeed;
    private ReelSpeedButton.Chosen chosen;

    @Before
    public void setUp() {
        screen = Robolectric.buildActivity(Activity.class).setup();
        activity = screen.get();
        FeedPostRows.reset();
        FeedPostRows.bridge = feed;
        feed.managers.put(controller, manager);
        feed.players.put(manager, player);
        DownloadHistory.accounts = () -> "1";
        ReelSpeedButton.lists = (shownOver, playing, picked) -> {
            shownSpeed = playing;
            chosen = picked;
        };
    }

    @After
    public void tearDown() {
        screen.close();
        FeedPostRows.reset();
        FeedPostRows.bridge = originalBridge;
        ReelSpeedButton.lists = originalLists;
        DownloadHistory.accounts = originalAccounts;
        Settings.POST_MENU_SPEED.resetToDefault();
        Settings.POST_MENU_BLOCK.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        PostInfoTest.Bridges.label = null;
        ShadowToast.reset();
        HookStatus.clear();
    }

    private static PostInfoTest.Post post(String id) {
        return new PostInfoTest.Post(id, "someone", 1_759_419_900L);
    }

    private static PostInfoTest.Post video(String id) {
        PostInfoTest.Post post = post(id);
        post.videos = Collections.singletonList(new MediaSave.Rendition("https://scontent.cdninstagram.com/v.mp4", 720, 1280, 0));
        return post;
    }

    private static ArrayList<Object> offered(Object post) {
        ArrayList<Object> rows = new ArrayList<>();
        FeedPostRows.offer(post, rows);
        return rows;
    }

    /** The feed's player prepares [post], which makes a new playback record, as Instagram's does for every video. */
    private void loaded(Object post) {
        FeedPostRows.controllerMade(controller);
        feed.playing.put(manager, post);
        feed.playbacks.put(manager, new Object());
    }

    private float afterAHold() {
        return FeedPostRows.restingSpeed(controller, 1f);
    }

    private static String toast() {
        ShadowLooper.idleMainLooper();
        return ShadowToast.getTextOfLatestToast();
    }

    /** Both switches start off, and off or paused a post gets neither row. */
    @Test
    public void bothStartOffAndAPauseTakesThemAway() {
        assertFalse(Settings.POST_MENU_SPEED.get());
        assertFalse(Settings.POST_MENU_BLOCK.get());
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);
        assertTrue("off", offered(post).isEmpty());

        Settings.POST_MENU_SPEED.save(true);
        Settings.POST_MENU_BLOCK.save(true);
        assertEquals(Arrays.asList(FeedPostRows.speedOption(), FeedPostRows.blockOption()), offered(post));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", offered(post).isEmpty());
    }

    /** Playback speed needs a video that a feed's player has loaded. */
    @Test
    public void speedShowsOnlyForAVideoAPlayerHolds() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post playingNow = video(THEIRS);
        assertTrue("no feed player yet", offered(playingNow).isEmpty());

        loaded(playingNow);
        assertEquals(Collections.singletonList(FeedPostRows.speedOption()), offered(playingNow));
        assertEquals("Playback speed", String.valueOf(PostInfoTest.Bridges.label));

        assertTrue("another post's video", offered(video("9_51234567")).isEmpty());
        PostInfoTest.Post photo = post(THEIRS);
        assertTrue("a photo, though the id matches", offered(photo).isEmpty());
    }

    /** The player may hold its own copy of the post, which is the same post by id. */
    @Test
    public void aPlayersCopyOfThePostCounts() {
        Settings.POST_MENU_SPEED.save(true);
        loaded(video(THEIRS));
        assertEquals(1, offered(video(THEIRS)).size());
    }

    /** A carousel's video page is what's checked, whether the player holds the page or the post. */
    @Test
    public void aCarouselUsesThePageOnScreen() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post carousel = post("100_51234567");
        PostInfoTest.Post page = video("101_51234567");
        carousel.pages = Collections.singletonList(page);
        loaded(page);

        // The menu is its post and no feed state says which page shows, so no page is on screen.
        assertTrue(offered(carousel).isEmpty());
        FeedPostRows.speed(carousel, 0, activity);
        assertEquals("the page's player", Float.valueOf(1f), shownSpeed);
    }

    /** A pick goes to the player, and the list then shows it as the speed in use for that player. */
    @Test
    public void aPickSetsThePlayerAndIsRemembered() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);

        FeedPostRows.speed(post, null, activity);
        assertEquals(Float.valueOf(1f), shownSpeed);
        chosen.chose(1.5f);
        assertEquals(Collections.singletonList("player=1.5"), feed.speeds);

        FeedPostRows.speed(post, null, activity);
        assertEquals(Float.valueOf(1.5f), shownSpeed);
    }

    /** A feed reuses its player, so a pick lasts for that playback and whatever plays next starts at 1x. */
    @Test
    public void aPickLastsForItsPlaybackOnly() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post first = video(THEIRS);
        loaded(first);
        FeedPostRows.speed(first, null, activity);
        chosen.chose(0.5f);

        PostInfoTest.Post next = video("9_51234567");
        loaded(next);
        FeedPostRows.speed(next, null, activity);
        assertEquals("the next video", Float.valueOf(1f), shownSpeed);

        loaded(first);
        FeedPostRows.speed(first, null, activity);
        assertEquals("the first video, come back to", Float.valueOf(1f), shownSpeed);
    }

    /** Instagram's end of a hold for 2x sets 1x, and a picked speed goes back in its place. */
    @Test
    public void theEndOfAHoldGoesBackToThePick() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);
        assertEquals("nothing picked", 1f, afterAHold(), 0f);

        FeedPostRows.speed(post, null, activity);
        chosen.chose(0.5f);
        assertEquals(0.5f, afterAHold(), 0f);

        loaded(video("9_51234567"));
        assertEquals("the next video", 1f, afterAHold(), 0f);
    }

    /** Off, paused, or with nothing loaded, the end of a hold keeps the speed Instagram gave it. */
    @Test
    public void theEndOfAHoldIsInstagramsWithoutAPickInForce() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);
        FeedPostRows.speed(post, null, activity);
        chosen.chose(0.5f);

        Settings.POST_MENU_SPEED.save(false);
        assertEquals("off", 1f, afterAHold(), 0f);
        Settings.POST_MENU_SPEED.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1f, afterAHold(), 0f);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals("on again", 0.5f, afterAHold(), 0f);

        assertEquals("no controller", 1f, FeedPostRows.restingSpeed(null, 1f), 0f);
        assertEquals("a controller with no manager", 1f, FeedPostRows.restingSpeed(new Object(), 1f), 0f);
        feed.playbacks.remove(manager);
        assertEquals("nothing loaded", 1f, afterAHold(), 0f);
    }

    /** A pick made once the menu has unloaded the video stays with that video's playback, and no other. */
    @Test
    public void aPickAfterTheMenuUnloadedTheVideoStaysWithIt() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);
        Object playback = feed.playbacks.get(manager);
        offered(post);
        feed.playing.remove(manager);
        feed.playbacks.remove(manager);

        FeedPostRows.speed(post, null, activity);
        chosen.chose(2f);
        FeedPostRows.speed(post, null, activity);
        assertEquals("still marked", Float.valueOf(2f), shownSpeed);

        feed.playing.put(manager, post);
        feed.playbacks.put(manager, playback);
        assertEquals("its playback carries on", 2f, afterAHold(), 0f);
        feed.playbacks.put(manager, new Object());
        assertEquals("another playback took over", 1f, afterAHold(), 0f);
    }

    /** With no playback to tie it to, a pick still sets the speed and just isn't remembered. */
    @Test
    public void aPickWithNoPlaybackStillSetsTheSpeed() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);
        feed.playbacks.remove(manager);

        FeedPostRows.speed(post, null, activity);
        chosen.chose(1.5f);
        assertEquals(Collections.singletonList("player=1.5"), feed.speeds);
        FeedPostRows.speed(post, null, activity);
        assertEquals(Float.valueOf(1f), shownSpeed);
    }

    /** Opening the menu can unload the video, so the row's own player still answers its tap. */
    @Test
    public void aTapStillFindsThePlayerTheRowWasOfferedFor() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);
        offered(post);
        feed.playing.remove(manager);

        FeedPostRows.speed(post, null, activity);
        chosen.chose(2f);
        assertEquals(Collections.singletonList("player=2.0"), feed.speeds);
    }

    /** With the row's player gone, the tap and a late pick both say the speed didn't change. */
    @Test
    public void aMissingPlayerSaysTheSpeedDidntChange() {
        Settings.POST_MENU_SPEED.save(true);
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);
        FeedPostRows.speed(post, null, activity);
        feed.players.remove(manager);

        chosen.chose(2f);
        assertEquals("Speed didn't change", toast());
        assertTrue(feed.speeds.isEmpty());

        ShadowToast.reset();
        chosen = null;
        FeedPostRows.speed(post, null, activity);
        assertNull("no list without a player", chosen);
        assertEquals("Speed didn't change", toast());
    }

    /** The newest feed that holds the video is the one set, and an older feed is passed over. */
    @Test
    public void theFeedHoldingTheVideoIsTheOneSet() {
        Settings.POST_MENU_SPEED.save(true);
        Object older = new Object();
        Object olderManager = new Object();
        feed.managers.put(older, olderManager);
        feed.players.put(olderManager, "older");
        feed.playing.put(olderManager, video("9_51234567"));
        FeedPostRows.controllerMade(older);
        PostInfoTest.Post post = video(THEIRS);
        loaded(post);
        Object newest = new Object();
        feed.managers.put(newest, new Object());
        FeedPostRows.controllerMade(newest);

        FeedPostRows.speed(post, null, activity);
        chosen.chose(0.5f);
        assertEquals(Collections.singletonList("player=0.5"), feed.speeds);
    }

    /** Block is for someone else's post, and never shows on a build without Instagram's sheet. */
    @Test
    public void blockShowsOnlyOnSomeoneElsesPost() {
        Settings.POST_MENU_BLOCK.save(true);
        assertEquals(Collections.singletonList(FeedPostRows.blockOption()), offered(post(THEIRS)));
        assertEquals("Block", String.valueOf(PostInfoTest.Bridges.label));

        assertTrue("your own", offered(post(MINE)).isEmpty());
        assertTrue("no owner in the id", offered(post("3712345678901234568")).isEmpty());
        PostInfoTest.Post nobody = post(THEIRS);
        nobody.owner = null;
        assertTrue("no owner", offered(nobody).isEmpty());

        feed.canBlock = false;
        assertTrue("no sheet", offered(post(THEIRS)).isEmpty());
    }

    /** A tap opens Instagram's sheet for the post's owner, from the menu, with the feed's module. */
    @Test
    public void blockOpensInstagramsSheetForTheOwner() {
        Settings.POST_MENU_BLOCK.save(true);
        Object helper = new Object();

        FeedPostRows.block(post(THEIRS), helper);

        assertEquals(1, feed.sheets.size());
        assertEquals(Arrays.asList(helper, "someone", "feed_timeline", "51234567"), Arrays.asList(feed.sheets.get(0)));

        Settings.POST_MENU_BLOCK.save(false);
        FeedPostRows.block(post(THEIRS), helper);
        assertEquals("switch off", 1, feed.sheets.size());
    }

    /** The short menu keeps each row that's on, after HushGram's own rows, speed before Block. */
    @Test
    public void theShortMenuKeepsTheRowsAfterOurs() {
        List<Object> options = Arrays.asList("DOWNLOAD", "DETAILS", "WHY");
        assertEquals("both off", options, FeedPostRows.withRows(options, "DOWNLOAD", null, "DETAILS"));

        Settings.POST_MENU_SPEED.save(true);
        Settings.POST_MENU_BLOCK.save(true);
        List<?> allowed = FeedPostRows.withRows(options, "DOWNLOAD", null, "DETAILS");
        assertEquals(Arrays.asList("DOWNLOAD", "DETAILS", FeedPostRows.speedOption(), FeedPostRows.blockOption(), "WHY"), allowed);
        assertEquals("already there", allowed, FeedPostRows.withRows(allowed, "DOWNLOAD", null, "DETAILS"));

        feed.canBlock = false;
        assertEquals("no sheet", Arrays.asList("DOWNLOAD", "DETAILS", FeedPostRows.speedOption(), "WHY"),
                FeedPostRows.withRows(options, "DOWNLOAD", null, "DETAILS"));
    }

    /** VideoDownload's filter for the short menu puts the rows in. */
    @Test
    public void theFeedMenusFilterAddsThem() {
        Settings.POST_MENU_BLOCK.save(true);
        List<?> allowed = VideoDownload.allow(Collections.singletonList("WHY"), null);
        assertTrue(allowed.contains(FeedPostRows.blockOption()));
    }

    /** A bridge that throws leaves the menu as it was and is reported, never reaching Instagram. */
    @Test
    public void aFailingBridgeStaysOutOfInstagram() {
        Settings.POST_MENU_SPEED.save(true);
        Settings.POST_MENU_BLOCK.save(true);
        FeedPostRows.bridge = new Feed() {
            @Override public boolean canBlock() {
                throw new IllegalStateException("no sheet");
            }

            @Override public Object managerOf(Object controller) {
                throw new IllegalStateException("no manager");
            }
        };
        FeedPostRows.controllerMade(controller);
        PostInfoTest.Post post = video(THEIRS);

        assertTrue(offered(post).isEmpty());
        FeedPostRows.speed(post, null, activity);
        FeedPostRows.block(post, new Object());
        FeedPostRows.controllerMade(null);
        assertEquals("the end of a hold keeps Instagram's speed", 1f, afterAHold(), 0f);
    }
}
