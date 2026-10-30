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
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Details in a feed post's menu: the row, what it shows for a photo, a video and a carousel page, and Copy media link. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = PostInfoTest.Bridges.class)
public class PostInfoTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String META = "https://scontent.cdninstagram.com/v/t51.2885-15/";
    private static final long POSTED = 1_759_419_900L;

    private ActivityController<Activity> controller;
    private Activity activity;

    @Before
    public void setUp() {
        ShadowAlertDialog.reset();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Settings.DOWNLOAD_VIDEOS.save(true);
    }

    @After
    public void tearDown() {
        controller.close();
        Settings.POST_DETAILS.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        Settings.DOWNLOAD_PHOTOS.resetToDefault();
        Settings.OPEN_IN_PLAYER.resetToDefault();
        Settings.DOWNLOAD_FEED_COVER.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Bridges.label = null;
        ShadowToast.reset();
        HookStatus.clear();
    }

    private static Post photo() {
        Post post = new Post("3712345678901234567_51234567", "someone", POSTED);
        post.pictures = Arrays.asList(new MediaSave.Rendition(META + "640.jpg", 640, 800, 0),
                new MediaSave.Rendition(META + "1080.jpg", 1080, 1350, 0));
        return post;
    }

    private static Post video() {
        Post post = new Post("3712345678901234568_51234567", "someone", POSTED);
        post.videos = Arrays.asList(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0),
                new MediaSave.Rendition(META + "1080.mp4", 1080, 1920, 0));
        post.pictures = Collections.singletonList(new MediaSave.Rendition(META + "cover.jpg", 1080, 1920, 0));
        return post;
    }

    /** A 1080p and a 720p H.264 track with sound, as Meta's manifests list them. */
    private static final String MANIFEST = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
            + track(1080, 1920, 3_000_000, "1080p", "t1080")
            + track(720, 1280, 1_500_000, "720p", "t720")
            + "</AdaptationSet><AdaptationSet mimeType=\"audio/mp4\">"
            + "<Representation codecs=\"mp4a.40.5\" bandwidth=\"64000\">"
            + "<BaseURL>https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/sound.mp4?oh=1&amp;oe=2</BaseURL>"
            + "</Representation></AdaptationSet></Period></MPD>";

    private static String track(int width, int height, long bandwidth, String label, String name) {
        return "<Representation codecs=\"avc1.64001f\" width=\"" + width + "\" height=\"" + height
                + "\" bandwidth=\"" + bandwidth + "\" FBQualityLabel=\"" + label + "\">"
                + "<BaseURL>https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/" + name + ".mp4?oh=1&amp;oe=2</BaseURL>"
                + "</Representation>";
    }

    /** A feed video with a 720p single file and the manifest's 1080p track, as review found it. */
    private static Post streamed() {
        Post post = video();
        post.videos = Collections.singletonList(new MediaSave.Rendition(META + "720.mp4", 720, 1280, 0));
        post.manifest = MANIFEST;
        return post;
    }

    private String when() {
        return DateUtils.formatDateTime(activity, POSTED * 1000L, PostInfo.FORMAT);
    }

    /** The switch starts off; on, every post's menu gets Details, and off or paused it doesn't. */
    @Test
    public void startsOffAndOnlyOnOffersTheRow() {
        assertFalse(Settings.POST_DETAILS.get());
        ArrayList<Object> rows = new ArrayList<>();
        PostInfo.offer(photo(), rows);
        assertTrue("off", rows.isEmpty());

        Settings.POST_DETAILS.save(true);
        PostInfo.offer(photo(), rows);
        assertEquals(Collections.singletonList(PostInfo.option()), rows);
        assertEquals("Details", String.valueOf(Bridges.label));
        rows.clear();
        PostInfo.offer(null, rows);
        assertTrue("no menu", rows.isEmpty());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        PostInfo.offer(photo(), rows);
        assertTrue("paused", rows.isEmpty());
    }

    /** A photo shows its time, poster, size and ID, and its link is the largest picture's. */
    @Test
    public void aPhotoShowsItsFactsAndTheLargestPicturesLink() {
        Post post = photo();
        PostInfo.Facts facts = PostInfo.Facts.of(post, post);
        assertEquals(META + "1080.jpg", facts.link());
        assertEquals("Posted " + when() + "\nBy @⁨someone⁩\nSize 1080 × 1350\nMedia ID ⁨3712345678901234567_51234567⁩",
                facts.text(activity));
    }

    /** A video's link and size are the file a Download would fetch at the quality set, never its cover. */
    @Test
    public void aVideoShowsTheFileDownloadWouldFetch() {
        Post post = video();
        assertEquals(META + "1080.mp4", PostInfo.Facts.of(post, post).link());
        assertTrue(PostInfo.Facts.of(post, post).text(activity).contains("Size 1080 × 1920"));
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
        assertEquals(META + "720.mp4", PostInfo.Facts.of(post, post).link());
        assertTrue(PostInfo.Facts.of(post, post).text(activity).contains("Size 720 × 1280"));

        post.videos = Collections.singletonList(new MediaSave.Rendition("https://example.com/v/1080.mp4", 1080, 1920, 0));
        PostInfo.Facts foreign = PostInfo.Facts.of(post, post);
        assertNull("only Meta's media servers", foreign.link());
        assertTrue(foreign.text(activity).endsWith("No direct link for this one"));
    }

    /**
     * With a manifest, the size is what Download saves, the manifest's 1080p joined with its sound,
     * and the link, the 720p single file, says its own size. A quality the single file meets as
     * well saves the file, so the two agree; with no single file there's no link to copy.
     */
    @Test
    public void aVideoWithAManifestShowsWhatDownloadSaves() {
        Post post = streamed();
        PostInfo.Facts facts = PostInfo.Facts.of(post, post);
        assertEquals(META + "720.mp4", facts.link());
        assertEquals("Posted " + when() + "\nBy @⁨someone⁩\nSize 1080 × 1920\nMedia ID ⁨3712345678901234568_51234567⁩"
                + "\nMedia link is the 720 × 1280 file", facts.text(activity));

        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P720);
        facts = PostInfo.Facts.of(post, post);
        assertEquals(META + "720.mp4", facts.link());
        assertTrue(facts.text(activity), facts.text(activity).contains("Size 720 × 1280"));
        assertFalse(facts.text(activity), facts.text(activity).contains("Media link is"));

        Settings.DOWNLOAD_QUALITY.resetToDefault();
        post.videos = null;
        facts = PostInfo.Facts.of(post, post);
        assertNull(facts.link());
        assertTrue(facts.text(activity), facts.text(activity).contains("Size 1080 × 1920"));
        assertTrue(facts.text(activity).endsWith("No direct link for this one"));
    }

    /** The tap reads a manifest on a worker, then shows the details on the app's own thread. */
    @Test
    public void theTapReadsAManifestOffTheDrawingThread() throws Exception {
        Settings.POST_DETAILS.save(true);
        PostInfo.show(streamed(), null, activity);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog != null && dialog.isShowing());
        String shown = Shadows.shadowOf(dialog).getMessage().toString();
        assertTrue(shown, shown.contains("Size 1080 × 1920") && shown.endsWith("Media link is the 720 × 1280 file"));
        dialog.dismiss();

        // Switched off before the worker is done, nothing shows.
        ShadowAlertDialog.reset();
        PostInfo.show(streamed(), null, activity);
        Settings.POST_DETAILS.save(false);
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    /**
     * A carousel shows the page on screen: its own ID, size and link, its place among the pages,
     * and the post's poster and time when the page doesn't list them. A page that isn't known shows
     * the post's facts and no link.
     */
    @Test
    public void aCarouselShowsThePageOnScreen() {
        Post post = new Post("3712345678901234569_51234567", "someone", POSTED);
        Post first = photo();
        Post second = video();
        second.id = "3712345678901234570";
        second.owner = null;
        second.takenAt = null;
        post.pages = Arrays.asList(first, second);

        PostInfo.Facts page = PostInfo.Facts.of(VideoDownload.shown(post, 1), post);
        assertEquals(META + "1080.mp4", page.link());
        assertEquals("Posted " + when() + "\nBy @⁨someone⁩\nPage 2 of 2\nSize 1080 × 1920\nMedia ID ⁨3712345678901234570⁩",
                page.text(activity));

        PostInfo.Facts unknown = PostInfo.Facts.of(VideoDownload.shown(post, 5), post);
        assertNull(unknown.link());
        assertEquals("Posted " + when() + "\nBy @⁨someone⁩\nMedia ID ⁨3712345678901234569_51234567⁩\nNo direct link for this one",
                unknown.text(activity));
    }

    /** A tap shows the details over the menu's screen, and Copy media link puts the address on the clipboard, marked sensitive. */
    @Test
    public void theTapShowsTheDetailsAndCopiesTheLink() {
        Settings.POST_DETAILS.save(true);
        PostInfo.show(video(), null, activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog != null && dialog.isShowing());
        assertEquals("Details", Shadows.shadowOf(dialog).getTitle().toString());
        TextView message = dialog.findViewById(android.R.id.message);
        assertTrue("the ID can be selected", message.isTextSelectable());
        Button copy = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        assertEquals("Copy media link", copy.getText().toString());
        copy.performClick();
        ShadowLooper.idleMainLooper();

        ClipData clip = ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
        assertEquals(META + "1080.mp4", clip.getItemAt(0).getText().toString());
        assertTrue(clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
    }

    /** No link, no Copy button; switched off with the menu open, or with no screen, nothing shows. */
    @Test
    public void nothingToCopyOrNowhereToShow() {
        Settings.POST_DETAILS.save(true);
        Post post = photo();
        post.pictures = null;
        PostInfo.show(post, null, activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        Button copy = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        assertTrue("no Copy media link", copy == null || copy.getVisibility() != android.view.View.VISIBLE);
        dialog.dismiss();

        ShadowAlertDialog.reset();
        Settings.POST_DETAILS.save(false);
        PostInfo.show(photo(), null, activity);
        assertNull("off", ShadowAlertDialog.getLatestAlertDialog());
        Settings.POST_DETAILS.save(true);
        PostInfo.show(photo(), null, null);
        controller.pause().stop().destroy();
        PostInfo.show(photo(), null, activity);
        assertNull("no screen", ShadowAlertDialog.getLatestAlertDialog());
    }

    /**
     * Copy username and Copy caption sit under the facts. Each copies exactly what the post holds,
     * emoji and right-to-left words included, marked sensitive, says so and closes Details.
     */
    @Test
    public void theDetailsCopyTheUsernameAndTheCaption() {
        Settings.POST_DETAILS.save(true);
        Post post = photo();
        post.caption = "Sunset at the pier \uD83C\uDF05\n\u0645\u0631\u062D\u0628\u0627 #golden";
        PostInfo.show(post, null, activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        TextView username = labelled(dialog, "Copy username");
        assertTrue("the facts stay selectable", ((TextView) dialog.findViewById(android.R.id.message)).isTextSelectable());
        username.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("someone", clip().getItemAt(0).getText().toString());
        assertTrue(clip().getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
        assertEquals("Username copied", ShadowToast.getTextOfLatestToast());
        assertFalse("Details closes", dialog.isShowing());

        PostInfo.show(post, null, activity);
        dialog = ShadowAlertDialog.getLatestAlertDialog();
        labelled(dialog, "Copy caption").performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(post.caption, clip().getItemAt(0).getText().toString());
        assertEquals("Caption copied", ShadowToast.getTextOfLatestToast());
        assertFalse(dialog.isShowing());
    }

    /**
     * A carousel's caption is the post's, never the page's, and the username is the page's poster
     * or the post's. A post with no caption, or only blanks, gets no Copy caption, and one with
     * neither gets no rows at all.
     */
    @Test
    public void theCopiesReadThePostWhoseMenuOpened() {
        Post post = new Post("3712345678901234569_51234567", "someone", POSTED);
        post.caption = "the post's words";
        Post first = photo();
        first.caption = "a page's words";
        first.owner = null;
        post.pages = Arrays.asList(first, video());
        PostInfo.Facts page = PostInfo.Facts.of(VideoDownload.shown(post, 0), post);
        assertEquals("the post's words", page.caption);
        assertEquals("someone", page.owner);
        assertEquals("the post's words", PostInfo.Facts.of(VideoDownload.shown(post, 5), post).caption);

        Settings.POST_DETAILS.save(true);
        Post bare = photo();
        bare.caption = " \n ";
        PostInfo.show(bare, null, activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(labels(dialog).contains("Copy username"));
        assertFalse("a blank caption", labels(dialog).contains("Copy caption"));
        dialog.dismiss();

        bare.owner = null;
        bare.caption = null;
        PostInfo.Facts facts = PostInfo.Facts.of(bare, bare);
        assertNull(PostInfo.copies(activity, activity, facts, new AlertDialog.Builder(activity).create()));
    }

    /**
     * Copy caption without hashtags (#282) leaves out a closing tag block, the dot lines stacked
     * above it and a line of tags alone, and keeps a tag's word when the sentence goes on after it.
     * Numbers, C#, a link's #part and a tag in the middle of a word aren't hashtags.
     */
    @Test
    public void captionsLoseTheirHashtagsButKeepTheirWords() {
        String sunrise = new String(Character.toChars(0x1F305));
        String hello = new String(new int[]{0x645, 0x631, 0x62D, 0x628, 0x627}, 0, 5);
        String golden = new String(new int[]{0x630, 0x647, 0x628, 0x64A}, 0, 4);
        assertEquals("Golden hour at the pier", PostInfo.withoutHashtags("Golden hour at the pier\n.\n.\n.\n#sunset #beach #goldenhour"));
        assertEquals("Loving summer days, #1 fan of this", PostInfo.withoutHashtags("Loving #summer days, #1 fan of this #blessed"));
        assertEquals("A day at the beach " + sunrise, PostInfo.withoutHashtags("A day at the beach #sun #fun " + sunrise));
        assertEquals("Line one\n\nLine two", PostInfo.withoutHashtags("Line one\n\n#a #b\n\nLine two"));
        assertEquals("Price is #2024 sale.", PostInfo.withoutHashtags("Price is #2024 sale #deal."));
        assertEquals("tbt to last summer", PostInfo.withoutHashtags("#tbt to last summer #a#b"));
        assertEquals(hello, PostInfo.withoutHashtags(hello + " #" + golden));
        assertEquals("a b is fun", PostInfo.withoutHashtags("#a#b is fun"));
        assertEquals("Great day", PostInfo.withoutHashtags("Great day #fun, #sun"));
        assertEquals("x " + sunrise, PostInfo.withoutHashtags("x #a " + sunrise + " #b"));
        assertEquals("sale.", PostInfo.withoutHashtags("sale #a, #b."));
        assertEquals("Line one\nLine two", PostInfo.withoutHashtags("Line one\r\n#a #b\r\nLine two"));
        assertEquals("Hi", PostInfo.withoutHashtags("Hi #ab" + new String(Character.toChars(0x200C)) + "cd"));
        assertEquals("Hi", PostInfo.withoutHashtags("Hi" + new String(Character.toChars(0xA0)) + "#tag"));
        assertNull("no hashtags", PostInfo.withoutHashtags("C# and example.com/#top, see #1 and mid#word"));
        assertNull("a number's tag", PostInfo.withoutHashtags("Room #1#a and Tom&#39;s"));
        assertNull("only hashtags", PostInfo.withoutHashtags("#only #tags\n.\n#a#b"));
    }

    /** The row shows only for a caption with hashtags, and copies the words like Copy caption does. */
    @Test
    public void theDetailsCopyTheCaptionWithoutItsHashtags() {
        Settings.POST_DETAILS.save(true);
        Post post = photo();
        post.caption = "No tags on this one";
        PostInfo.show(post, null, activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(labels(dialog).contains("Copy caption"));
        assertFalse("nothing to leave out", labels(dialog).contains("Copy caption without hashtags"));
        dialog.dismiss();

        post.caption = "Sunset at the pier\n.\n#golden #hour";
        PostInfo.show(post, null, activity);
        dialog = ShadowAlertDialog.getLatestAlertDialog();
        labelled(dialog, "Copy caption without hashtags").performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("Sunset at the pier", clip().getItemAt(0).getText().toString());
        assertTrue(clip().getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
        assertEquals("Caption copied", ShadowToast.getTextOfLatestToast());
        assertFalse("Details closes", dialog.isShowing());
    }

    private ClipData clip() {
        return ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
    }

    /** The row reading [label] in [dialog]. */
    private static TextView labelled(AlertDialog dialog, String label) {
        List<TextView> found = new ArrayList<>();
        collect(dialog.getWindow().getDecorView(), found);
        for (TextView view : found) {
            if (label.equals(view.getText().toString())) return view;
        }
        throw new AssertionError("no row " + label + " in " + labels(dialog));
    }

    private static List<String> labels(AlertDialog dialog) {
        List<TextView> found = new ArrayList<>();
        collect(dialog.getWindow().getDecorView(), found);
        List<String> labels = new ArrayList<>();
        for (TextView view : found) labels.add(view.getText().toString());
        return labels;
    }

    private static void collect(View view, List<TextView> found) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE && view.isClickable()) found.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found);
        }
    }

    /** The short feed menu keeps Details after HushGram's other rows, or first when there are none. */
    @Test
    public void theShortMenuKeepsDetailsLast() {
        List<Object> options = Arrays.asList("WHY_AM_I_SEEING_THIS", "REPORT");
        Object all = VideoDownload.allOption();
        Settings.POST_DETAILS.save(true);
        Object details = PostInfo.option();
        assertEquals(Arrays.asList("DOWNLOAD", all, details, "WHY_AM_I_SEEING_THIS", "REPORT"), VideoDownload.allow(options, "DOWNLOAD"));
        Settings.OPEN_IN_PLAYER.save(true);
        assertEquals(Arrays.asList("DOWNLOAD", all, VideoDownload.playerOption(), details, "WHY_AM_I_SEEING_THIS", "REPORT"),
                VideoDownload.allow(options, "DOWNLOAD"));
        List<?> already = VideoDownload.allow(options, "DOWNLOAD");
        assertTrue("a list that has it comes back as it came", already == VideoDownload.allow(already, "DOWNLOAD"));
        Settings.DOWNLOAD_FEED_COVER.save(true);
        assertEquals("Download cover sits after Save all (#94)", Arrays.asList("DOWNLOAD", all, VideoDownload.coverOption(),
                VideoDownload.playerOption(), details, "WHY_AM_I_SEEING_THIS", "REPORT"), VideoDownload.allow(options, "DOWNLOAD"));
        Settings.DOWNLOAD_FEED_COVER.save(false);

        Settings.OPEN_IN_PLAYER.save(false);
        Settings.DOWNLOAD_VIDEOS.save(false);
        Settings.DOWNLOAD_PHOTOS.save(false);
        assertEquals(Arrays.asList(details, "WHY_AM_I_SEEING_THIS", "REPORT"), VideoDownload.allow(options, "DOWNLOAD"));
        Settings.POST_DETAILS.save(false);
        assertEquals("the list Instagram made", options, VideoDownload.allow(options, "DOWNLOAD"));
    }

    /** Our Details row in a reel's menu, an option the menu's handler is handed. */
    private enum ReelRow { HUSHGRAM_REEL_DETAILS }

    /**
     * A reel's menu gets Details with the switch on, whatever Download does, and a tap on it opens
     * the sheet a feed post's does, Copy caption included (#282). With the switch off the menu is
     * Instagram's.
     */
    @Test
    public void aReelGetsDetailsWithCopyCaption() {
        Post reel = video();
        reel.caption = "a reel's words";

        Bridges.reelRows.clear();
        Settings.POST_DETAILS.save(false);
        assertFalse(ReelDownload.rows(null, reel, null, null, null));
        assertTrue("off", Bridges.reelRows.isEmpty());

        Settings.POST_DETAILS.save(true);
        assertTrue("Download on reels keeps Instagram's row out of a video's", ReelDownload.rows(null, reel, null, null, null));
        assertEquals(Arrays.asList("HUSHGRAM_DOWNLOAD_REEL=Download", "HUSHGRAM_REEL_DETAILS=Details"), Bridges.reelRows);

        Bridges.reelRows.clear();
        Settings.DOWNLOAD_REELS.save(false);
        assertFalse("Download on reels off: Instagram's own row follows", ReelDownload.rows(null, reel, null, null, null));
        assertEquals(Arrays.asList("HUSHGRAM_REEL_DETAILS=Details"), Bridges.reelRows);

        assertTrue(ReelDownload.ours(ReelRow.HUSHGRAM_REEL_DETAILS));
        assertTrue(ReelDownload.save(ReelRow.HUSHGRAM_REEL_DETAILS, reel, activity));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        labelled(dialog, "Copy caption").performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("a reel's words", clip().getItemAt(0).getText().toString());
        Settings.DOWNLOAD_REELS.resetToDefault();
    }

    /** The reel menu's adder is let in for Details alone when Download is off and Instagram keeps its row out. */
    @Test
    public void detailsAloneLetsTheReelMenuIn() {
        Settings.DOWNLOAD_REELS.save(false);
        Settings.POST_DETAILS.save(false);
        assertFalse(ReelDownload.offerRow(0, new Post("1_2", "someone", POSTED)));

        Settings.POST_DETAILS.save(true);
        Post reel = video();
        assertTrue(ReelDownload.offerRow(0, reel));
        assertTrue("Instagram's flag that keeps the row out is let by", !ReelDownload.withholdRow(1));
        Bridges.reelRows.clear();
        assertTrue("Details alone takes Download's place", ReelDownload.rows(null, reel, null, null, null));
        assertEquals(Arrays.asList("HUSHGRAM_REEL_DETAILS=Details"), Bridges.reelRows);
        Settings.DOWNLOAD_REELS.resetToDefault();
    }

    /** A post or a carousel page as the bridges read it. */
    static final class Post {
        String id;
        String owner;
        Long takenAt;
        List<MediaSave.Rendition> videos;
        List<MediaSave.Rendition> pictures;
        List<Post> pages;
        String manifest;
        String caption;

        Post(String id, String owner, Long takenAt) {
            this.id = id;
            this.owner = owner;
            this.takenAt = takenAt;
        }
    }

    /** The bridges over [Post], with a menu that is its post and a feed state that is the page's index. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Bridges {
        static CharSequence label;

        @Implementation protected static List<?> videoVersions(Object media) { return ((Post) media).videos; }
        @Implementation protected static String dashManifest(Object media) { return ((Post) media).manifest; }
        @Implementation protected static String versionUrl(Object version) { return ((MediaSave.Rendition) version).url; }
        @Implementation protected static Integer versionWidth(Object version) { return ((MediaSave.Rendition) version).width; }
        @Implementation protected static Integer versionHeight(Object version) { return ((MediaSave.Rendition) version).height; }
        @Implementation protected static Object imageVersions(Object media) { return ((Post) media).pictures == null ? null : media; }
        @Implementation protected static List<?> imageCandidates(Object versions) { return ((Post) versions).pictures; }
        @Implementation protected static String candidateUrl(Object candidate) { return ((MediaSave.Rendition) candidate).url; }
        @Implementation protected static int candidateWidth(Object candidate) { return ((MediaSave.Rendition) candidate).width; }
        @Implementation protected static int candidateHeight(Object candidate) { return ((MediaSave.Rendition) candidate).height; }
        @Implementation protected static String mediaId(Object media) { return ((Post) media).id; }
        @Implementation protected static Object owner(Object media) { return ((Post) media).owner; }
        @Implementation protected static Long takenAt(Object media) { return ((Post) media).takenAt; }
        @Implementation protected static String username(Object user) { return (String) user; }
        @Implementation protected static List<?> carouselMedia(Object media) { return ((Post) media).pages; }
        @Implementation protected static Object caption(Object media) { return ((Post) media).caption == null ? null : media; }
        @Implementation protected static String captionText(Object caption) { return ((Post) caption).caption; }
        @Implementation protected static int carouselIndex(Object itemState) { return (Integer) itemState; }
        @Implementation protected static Object feedOption(String name) { return name; }
        @Implementation protected static Object reelOption(String name) { return name; }

        static final List<String> reelRows = new ArrayList<>();

        @Implementation
        protected static boolean addReelRow(Object menu, Object context, Object option, Object sheet, Object rowState, String text) {
            reelRows.add(option + "=" + text);
            return true;
        }
        @Implementation protected static Object saveAllOption() { return "SAVE_ALL"; }
        @Implementation protected static Object feedMenuMedia(Object menu) { return menu; }
        @Implementation protected static Object feedMenuItemState(Object menu) { return null; }

        @Implementation
        protected static void addSaveAllRow(Object menu, ArrayList<Object> rows, Object option, CharSequence title) {
            rows.add(option);
            label = title;
        }
    }
}
