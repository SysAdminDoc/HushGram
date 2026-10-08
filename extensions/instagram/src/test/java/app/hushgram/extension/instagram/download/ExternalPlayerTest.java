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
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Open in another player: the address a player gets, the chooser it comes in, and the menu rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = ExternalPlayerTest.Item.class)
public class ExternalPlayerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String META = "https://scontent.cdninstagram.com/v/t50.2886-16/";

    /** A player on the phone, one that views a video at a web address. */
    private static final ComponentName PLAYER = new ComponentName("org.example.player", "org.example.player.Play");

    @Before
    public void playerInstalled() throws IntentFilter.MalformedMimeTypeException {
        IntentFilter view = new IntentFilter(Intent.ACTION_VIEW);
        view.addCategory(Intent.CATEGORY_DEFAULT);
        view.addDataScheme("https");
        view.addDataType("video/*");
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addActivityIfNotPresent(PLAYER);
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).addIntentFilterForActivity(PLAYER, view);
    }

    @After
    public void tearDown() {
        Settings.OPEN_IN_PLAYER.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.save(true);
        Settings.DOWNLOAD_REELS.save(true);
        Settings.DOWNLOAD_REEL_COVER.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Item.videos = null;
        Item.manifest = null;
        Item.pictures = null;
        Item.rows.clear();
        HookStatus.clear();
    }

    private static void twoFiles() {
        Item.videos = Arrays.asList(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0),
                new MediaSave.Rendition(META + "1080.mp4", 1080, 1920, 0));
    }

    /** The switch starts off, and off or paused no menu offers the row. */
    @Test
    public void startsOffAndPauseTurnsItOff() {
        twoFiles();
        assertFalse(Settings.OPEN_IN_PLAYER.get());
        assertFalse(ExternalPlayer.offers(new Object()));
        Settings.OPEN_IN_PLAYER.save(true);
        assertTrue(ExternalPlayer.offers(new Object()));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", ExternalPlayer.offers(new Object()));
    }

    /**
     * The player gets the file a save would pick at the Download quality, and only a file on Meta's
     * media servers over HTTPS. A video with only a manifest has none to give.
     */
    @Test
    public void thePlayerGetsTheFileASaveWouldPick() {
        twoFiles();
        assertEquals(META + "1080.mp4", ExternalPlayer.address(new Object()));
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
        assertEquals(META + "720.mp4", ExternalPlayer.address(new Object()));

        Item.videos = Arrays.asList(new MediaSave.Rendition("https://example.com/v/1080.mp4", 1080, 1920, 0),
                new MediaSave.Rendition("http://scontent.cdninstagram.com/v/720.mp4", 720, 1280, 0));
        assertNull("not Meta's, or not HTTPS", ExternalPlayer.address(new Object()));
        Item.videos = null;
        Item.manifest = "<MPD/>";
        assertNull("a manifest alone", ExternalPlayer.address(new Object()));
        assertNull(ExternalPlayer.address(null));
    }

    /** A tap opens Android's chooser on a view of the file as an MP4, and counts. */
    @Test
    public void aTapOpensTheChooserOnTheFile() {
        twoFiles();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        assertTrue(ExternalPlayer.open(activity, new Object(), FamilyNames.REEL_DOWNLOAD));

        Intent chooser = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertEquals("an activity keeps its task", 0, chooser.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent view = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_VIEW, view.getAction());
        assertEquals(Uri.parse(META + "1080.mp4"), view.getData());
        assertEquals("video/mp4", view.getType());
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains(ExternalPlayer.OPENED + " 1"));

        Application application = RuntimeEnvironment.getApplication();
        assertTrue(ExternalPlayer.open(application, new Object(), FamilyNames.VIDEO_DOWNLOAD));
        Intent fromApplication = Shadows.shadowOf(application).getNextStartedActivity();
        assertTrue("an application context needs a new task", (fromApplication.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    /** With no file to hand over, nothing opens and a toast says why. */
    @Test
    public void noFileSaysSo() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        assertFalse(ExternalPlayer.open(activity, new Object(), FamilyNames.REEL_DOWNLOAD));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertEquals("This video has no file another player can open", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains(ExternalPlayer.NO_FILE + " 1"));
    }

    /**
     * With no app on the phone that views the video, the chooser, which would open empty, doesn't
     * open, and a toast says no app plays it.
     */
    @Test
    public void noPlayerSaysSo() {
        twoFiles();
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).removeActivity(PLAYER);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        assertFalse(ExternalPlayer.open(activity, new Object(), FamilyNames.REEL_DOWNLOAD));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull("no empty chooser", Shadows.shadowOf(activity).getNextStartedActivity());
        assertEquals("No app on this phone can play this video", String.valueOf(ShadowToast.getTextOfLatestToast()));
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains(ExternalPlayer.NO_PLAYER + " 1"));
    }

    /**
     * On, a reel with a video file gets Download and Open in another player in Download's place,
     * after Download cover when that's on too. A reel with only a manifest gets no player row.
     */
    @Test
    public void aReelGetsTheRowAfterDownload() {
        Settings.DOWNLOAD_REELS.save(true);
        twoFiles();
        Item.pictures = Collections.singletonList(new MediaSave.Rendition(META + "cover.jpg", 1080, 1920, 0));
        assertFalse("off", ReelDownload.rows(null, new Object(), null, null, null));

        Settings.OPEN_IN_PLAYER.save(true);
        assertTrue(ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(Arrays.asList("HUSHGRAM_DOWNLOAD_REEL=Download", "HUSHGRAM_OPEN_PLAYER=Open in another player"), Item.rows);

        Item.rows.clear();
        Settings.DOWNLOAD_REEL_COVER.save(true);
        assertTrue(ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(Arrays.asList("HUSHGRAM_DOWNLOAD_REEL=Download", "HUSHGRAM_DOWNLOAD_COVER=Download cover",
                "HUSHGRAM_OPEN_PLAYER=Open in another player"), Item.rows);

        Item.rows.clear();
        Settings.DOWNLOAD_REEL_COVER.save(false);
        Item.videos = null;
        Item.manifest = "<MPD/>";
        assertFalse("a manifest alone", ReelDownload.rows(null, new Object(), null, null, null));
        assertTrue(Item.rows.isEmpty());
    }

    /** A tap on the reel's row opens the chooser and never starts a save. */
    @Test
    public void aReelsRowOpensThePlayer() {
        Settings.DOWNLOAD_REELS.save(true);
        twoFiles();
        Settings.OPEN_IN_PLAYER.save(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

        assertTrue(ReelDownload.ours(Ours.HUSHGRAM_OPEN_PLAYER));
        assertTrue(ReelDownload.save(Ours.HUSHGRAM_OPEN_PLAYER, new Object(), activity));

        Intent chooser = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertEquals("nothing saved", 0, MediaSave.savesInFlight());
    }

    /**
     * With Download on reels off, a reel Instagram gives its own Download row gets Open in another
     * player above it, and Instagram's row stays, its tap left to Instagram. The player row still
     * opens the chooser, and does nothing once its own switch went off with the menu open.
     */
    @Test
    public void withDownloadOnReelsOffTheRowGoesAboveInstagramsDownload() {
        Settings.DOWNLOAD_REELS.save(false);
        twoFiles();
        assertFalse(ReelDownload.rows(null, new Object(), null, null, null));
        assertTrue("player off", Item.rows.isEmpty());

        Settings.OPEN_IN_PLAYER.save(true);
        assertFalse("Instagram's own Download row stays", ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(Collections.singletonList("HUSHGRAM_OPEN_PLAYER=Open in another player"), Item.rows);

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertTrue(ReelDownload.save(Ours.HUSHGRAM_OPEN_PLAYER, new Object(), activity));
        assertEquals(Intent.ACTION_CHOOSER, Shadows.shadowOf(activity).getNextStartedActivity().getAction());
        assertFalse("Instagram's own Download is Instagram's", ReelDownload.save(Native.DOWNLOAD, new Object(), activity));
        assertEquals("nothing saved", 0, MediaSave.savesInFlight());

        Settings.OPEN_IN_PLAYER.save(false);
        assertTrue("ours, so it never reaches Instagram", ReelDownload.save(Ours.HUSHGRAM_OPEN_PLAYER, new Object(), activity));
        assertNull("switched off with the menu open", Shadows.shadowOf(activity).getNextStartedActivity());

        Settings.OPEN_IN_PLAYER.save(true);
        Item.rows.clear();
        Item.videos = null;
        Item.manifest = "<MPD/>";
        assertFalse(ReelDownload.rows(null, new Object(), null, null, null));
        assertTrue("a manifest alone", Item.rows.isEmpty());
    }

    /**
     * With Download on reels off, a reel Instagram keeps Download off still gets the player row: the
     * builder that hands Download to the adder lets it in, and the adder puts the player row alone in
     * its place and leaves Instagram's out. A reel Instagram gives Download keeps its row with the
     * player row above it. A reel with no video file isn't let in, so the builder's menu, divider and
     * all, stays Instagram's. With the player off too, it's Instagram's menu.
     */
    @Test
    public void withDownloadOnReelsOffAReelWithoutInstagramsDownloadGetsTheRowAlone() {
        Settings.DOWNLOAD_REELS.save(false);
        twoFiles();
        assertFalse("both off, Instagram's answer", ReelDownload.offerRow(0, new Object()));
        assertTrue(ReelDownload.offerRow(1, new Object()));
        assertTrue("both off, Instagram's flag", ReelDownload.withholdRow(1));

        Settings.OPEN_IN_PLAYER.save(true);
        assertTrue("let in for the player row", ReelDownload.offerRow(0, new Object()));
        assertFalse(ReelDownload.withholdRow(0));
        assertTrue("Instagram's own Download row is left out", ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(Collections.singletonList("HUSHGRAM_OPEN_PLAYER=Open in another player"), Item.rows);
        assertTrue(String.valueOf(HookStatus.report()), String.valueOf(HookStatus.report()).contains(ReelDownload.PLAYER_ALONE + " 1"));

        Item.rows.clear();
        assertFalse("the adder took the answer, so the next row is Instagram's",
                ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(Collections.singletonList("HUSHGRAM_OPEN_PLAYER=Open in another player"), Item.rows);

        Item.rows.clear();
        assertTrue(ReelDownload.offerRow(1, new Object()));
        assertFalse(ReelDownload.withholdRow(0));
        assertFalse("Instagram's Download stays", ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(Collections.singletonList("HUSHGRAM_OPEN_PLAYER=Open in another player"), Item.rows);

        Item.rows.clear();
        assertTrue(ReelDownload.offerRow(1, new Object()));
        assertFalse("let in past Instagram's flag for the player row", ReelDownload.withholdRow(1));
        assertTrue(ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(Collections.singletonList("HUSHGRAM_OPEN_PLAYER=Open in another player"), Item.rows);

        Item.rows.clear();
        Item.videos = null;
        Item.manifest = "<MPD/>";
        assertFalse("no video file, so Instagram's answer", ReelDownload.offerRow(0, new Object()));
        assertTrue("and Instagram's flag", ReelDownload.withholdRow(1));
        assertTrue(ReelDownload.offerRow(1, new Object()));
        assertTrue(ReelDownload.withholdRow(1));
        assertTrue(Item.rows.isEmpty());
    }

    /**
     * The reduced reel menu, which never lists Download, gets it for the player row alone, and the
     * adder leaves Instagram's out. With the player off its list is Instagram's.
     */
    @Test
    public void theReducedMenuGetsThePlayerRowAlone() {
        Settings.DOWNLOAD_REELS.save(false);
        twoFiles();
        List<Object> reduced = new ArrayList<>(Arrays.asList("PLAYBACK_CONTROLS", "REPORT"));
        ReelDownload.addTo(reduced, Native.DOWNLOAD);
        assertEquals(Arrays.asList("PLAYBACK_CONTROLS", "REPORT"), reduced);

        Settings.OPEN_IN_PLAYER.save(true);
        ReelDownload.addTo(reduced, Native.DOWNLOAD);
        assertEquals(Arrays.asList(Native.DOWNLOAD, "PLAYBACK_CONTROLS", "REPORT"), reduced);
        assertTrue(ReelDownload.rows(null, new Object(), null, null, null));
        assertEquals(Collections.singletonList("HUSHGRAM_OPEN_PLAYER=Open in another player"), Item.rows);

        Item.rows.clear();
        ReelDownload.addTo(reduced, Native.DOWNLOAD);
        assertEquals("a list that has it is left alone", Arrays.asList(Native.DOWNLOAD, "PLAYBACK_CONTROLS", "REPORT"), reduced);
        assertFalse("Instagram listed it, so it stays", ReelDownload.rows(null, new Object(), null, null, null));
    }

    /** On, the feed menu gets the row for a post with a video file, and a tap opens the chooser. */
    @Test
    public void aFeedVideoGetsTheRowAndItsTap() {
        twoFiles();
        ArrayList<Object> rows = new ArrayList<>();
        VideoDownload.offerPlayer(new Object(), rows);
        assertTrue("off", rows.isEmpty());

        Settings.OPEN_IN_PLAYER.save(true);
        VideoDownload.offerPlayer(new Object(), rows);
        assertEquals(Collections.singletonList(VideoDownload.playerOption()), rows);
        assertEquals("Open in another player", String.valueOf(Item.label));

        Item.videos = null;
        rows.clear();
        VideoDownload.offerPlayer(new Object(), rows);
        assertTrue("no video file", rows.isEmpty());

        twoFiles();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        VideoDownload.play(new Object(), null, activity);
        assertEquals(Intent.ACTION_CHOOSER, Shadows.shadowOf(activity).getNextStartedActivity().getAction());
    }

    /**
     * The short feed menu keeps the row after Download and Save all, or first when Download's
     * switches are off. Off, the list is as before.
     */
    @Test
    public void theShortMenuKeepsTheRowAfterTheSaves() {
        List<Object> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");
        Object all = VideoDownload.allOption();
        assertEquals(Arrays.asList("DOWNLOAD", all, "WHY_AM_I_SEEING_THIS", "REPORT"), VideoDownload.allow(options, "DOWNLOAD"));

        Settings.OPEN_IN_PLAYER.save(true);
        Object player = VideoDownload.playerOption();
        assertEquals(Arrays.asList("DOWNLOAD", all, player, "WHY_AM_I_SEEING_THIS", "REPORT"),
                VideoDownload.allow(options, "DOWNLOAD"));
        List<?> already = VideoDownload.allow(options, "DOWNLOAD");
        assertTrue("a list that has it comes back as it came", already == VideoDownload.allow(already, "DOWNLOAD"));

        Settings.DOWNLOAD_VIDEOS.save(false);
        assertEquals(Arrays.asList(player, "WHY_AM_I_SEEING_THIS", "REPORT"), VideoDownload.allow(options, "DOWNLOAD"));
        assertEquals("the list Instagram made", Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT"), options);
    }

    /** Names of the rows ours, as the bridges make them. */
    private enum Ours { HUSHGRAM_DOWNLOAD_REEL, HUSHGRAM_DOWNLOAD_COVER, HUSHGRAM_OPEN_PLAYER }

    /** Instagram's own Download option, by the name Instagram keeps. */
    private enum Native { DOWNLOAD }

    /** A post or reel as the bridges read it, and the rows the menus' adders were handed. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Item {
        static List<MediaSave.Rendition> videos;
        static String manifest;
        static List<MediaSave.Rendition> pictures;
        static final List<String> rows = new ArrayList<>();
        static CharSequence label;
        static final Object ALL = "SAVE_ALL";

        @Implementation protected static List<?> videoVersions(Object media) { return videos; }
        @Implementation protected static String dashManifest(Object media) { return manifest; }
        @Implementation protected static String versionUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static Integer versionWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static Integer versionHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static Object imageVersions(Object media) { return pictures == null ? null : media; }
        @Implementation protected static List<?> imageCandidates(Object versions) { return pictures; }
        @Implementation protected static String candidateUrl(Object candidate) { return ((MediaSave.Rendition) candidate).url; }
        @Implementation protected static int candidateWidth(Object candidate) { return ((MediaSave.Rendition) candidate).width; }
        @Implementation protected static int candidateHeight(Object candidate) { return ((MediaSave.Rendition) candidate).height; }
        @Implementation protected static Object reelOption(String name) { return Ours.valueOf(name); }
        @Implementation protected static Object feedOption(String name) { return name; }
        @Implementation protected static Object saveAllOption() { return ALL; }
        @Implementation protected static Object feedMenuMedia(Object menu) { return menu; }
        @Implementation protected static Object feedMenuItemState(Object menu) { return null; }

        @Implementation
        protected static boolean addReelRow(Object menu, Object context, Object option, Object sheet, Object rowState, String label) {
            rows.add(option + "=" + label);
            return true;
        }

        @Implementation
        protected static void addSaveAllRow(Object menu, ArrayList<Object> rows, Object option, CharSequence title) {
            rows.add(option);
            label = title;
        }
    }
}
