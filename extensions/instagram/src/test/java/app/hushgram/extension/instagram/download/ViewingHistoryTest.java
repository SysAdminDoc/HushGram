/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

import app.hushgram.extension.instagram.download.ViewingHistory.Kind;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * The viewing history (#228): off until switched on, newest first, no repeats within a minute,
 * about 500 rows, kept in one file on the phone, and cleared on request.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ViewingHistoryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ViewingHistory.forgetForTests();
        ViewingHistory.clear(context);
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Settings.VIEWING_HISTORY.resetToDefault();
        ViewingHistory.clear(context);
        ViewingHistory.forgetForTests();
    }

    @Test public void itIsOffUntilSwitchedOnAndRecordsNothingMeanwhile() {
        assertFalse(Settings.VIEWING_HISTORY.get());
        assertFalse(ViewingHistory.on());
        ViewingHistory.profile(new Object());
        ViewingHistory.post(new Object());
        ViewingHistory.reel(new Object());
        ViewingHistory.story(new Object());
        assertEquals(0, ViewingHistory.count(context));
        Settings.VIEWING_HISTORY.save(true);
        assertTrue(ViewingHistory.on());
        Settings.VIEWING_HISTORY.save(false);
        assertFalse(ViewingHistory.on());
    }

    @Test public void rowsComeNewestFirstAndTheSameThingWithinAMinuteIsOneRow() {
        ViewingHistory.add(context, Kind.POST, "1_9", "ann", 1_000L);
        ViewingHistory.add(context, Kind.REEL, "2_9", "bob", 2_000L);
        ViewingHistory.add(context, Kind.REEL, "2_9", "bob", 3_000L);
        List<ViewingHistory.Row> rows = ViewingHistory.entries(context);
        assertEquals(2, rows.size());
        assertEquals("2_9", rows.get(0).id);
        assertEquals(Kind.REEL, rows.get(0).kind);
        assertEquals("1_9", rows.get(1).id);
    }

    @Test public void theSameThingSeenMuchLaterMovesUpInsteadOfRepeating() {
        ViewingHistory.add(context, Kind.STORY, "5_1", "cy", 1_000L);
        ViewingHistory.add(context, Kind.PROFILE, "dee", "dee", 2_000L);
        ViewingHistory.add(context, Kind.STORY, "5_1", "cy", 2_000L + 5 * 60_000L);
        List<ViewingHistory.Row> rows = ViewingHistory.entries(context);
        assertEquals(2, rows.size());
        assertEquals("5_1", rows.get(0).id);
        assertEquals("dee", rows.get(1).id);
    }

    @Test public void thePostAndTheReelWithTheSameIdAreDifferentRows() {
        ViewingHistory.add(context, Kind.POST, "7_7", "eve", 1_000L);
        ViewingHistory.add(context, Kind.REEL, "7_7", "eve", 1_500L);
        assertEquals(2, ViewingHistory.count(context));
    }

    @Test public void onlyTheNewestFiveHundredAreKept() {
        for (int i = 0; i < ViewingHistory.MAX_ENTRIES + 25; i++) {
            ViewingHistory.add(context, Kind.POST, i + "_1", "ann", 1_000L * (i + 1));
        }
        List<ViewingHistory.Row> rows = ViewingHistory.entries(context);
        assertEquals(ViewingHistory.MAX_ENTRIES, rows.size());
        assertEquals((ViewingHistory.MAX_ENTRIES + 24) + "_1", rows.get(0).id);
        assertEquals("25_1", rows.get(rows.size() - 1).id);
    }

    @Test public void theListSurvivesAProcessRestartAndClearWipesItAndTheFile() throws Exception {
        ViewingHistory.add(context, Kind.PROFILE, "fay", "fay", 1_000L);
        ViewingHistory.add(context, Kind.POST, "3_3", "gus\twith tab", 2_000L);
        Utils.awaitBackgroundTasksForTests();
        File file = new File(new File(context.getFilesDir(), "hushgram-viewing"), "history.tsv");
        assertTrue(file.isFile());

        ViewingHistory.forgetForTests();
        List<ViewingHistory.Row> back = ViewingHistory.entries(context);
        assertEquals(2, back.size());
        assertEquals("3_3", back.get(0).id);
        assertEquals("gus with tab", back.get(0).owner);

        assertTrue(ViewingHistory.clear(context));
        assertEquals(0, ViewingHistory.count(context));
        assertFalse(file.exists());
        ViewingHistory.forgetForTests();
        assertEquals(0, ViewingHistory.count(context));
    }

    /** One of a picture's sizes, as Instagram lists them. */
    private static final class Size {
        final String url;
        final int width;

        Size(String url, int width) {
            this.url = url;
            this.width = width;
        }
    }

    /** A Media with its sizes, or a carousel with its pages; and a User with its picture. */
    private static final class Media {
        final List<Size> sizes;
        final List<Media> pages;

        Media(List<Size> sizes, List<Media> pages) {
            this.sizes = sizes;
            this.pages = pages;
        }
    }

    private static final ViewingHistory.Sizes FAKE = new ViewingHistory.Sizes() {
        @Override public Object versions(Object media) {
            return ((Media) media).sizes;
        }

        @Override public List<?> pages(Object media) {
            return ((Media) media).pages;
        }

        @Override public List<?> candidates(Object versions) {
            return (List<?>) versions;
        }

        @Override public String url(Object candidate) {
            return ((Size) candidate).url;
        }

        @Override public int width(Object candidate) {
            return ((Size) candidate).width;
        }

        @Override public Object profilePicture(Object user) {
            return user instanceof Size ? user : null;
        }
    };

    /** #228: a row's picture is the smallest size Instagram lists that's still sharp in the row. */
    @Test public void thePictureIsTheSmallestSizeThatStillFills() {
        ViewingHistory.Sizes original = ViewingHistory.sizes;
        ViewingHistory.sizes = FAKE;
        try {
            Media reel = new Media(java.util.Arrays.asList(new Size("w1080", 1080), new Size("w240", 240),
                    new Size("w150", 150), new Size("w640", 640)), null);
            assertEquals("w240", ViewingHistory.picture(reel));

            Media small = new Media(java.util.Arrays.asList(new Size("w100", 100), new Size("w120", 120)), null);
            assertEquals("nothing big enough, so the biggest", "w120", ViewingHistory.picture(small));

            Media unsized = new Media(java.util.Arrays.asList(new Size("first", 0), new Size("second", 0)), null);
            assertEquals("no widths at all, so the last listed", "second", ViewingHistory.picture(unsized));

            Media carousel = new Media(null, java.util.Arrays.asList(
                    new Media(java.util.Collections.singletonList(new Size("page1", 320)), null),
                    new Media(java.util.Collections.singletonList(new Size("page2", 320)), null)));
            assertEquals("a carousel shows its first page", "page1", ViewingHistory.picture(carousel));

            assertEquals("", ViewingHistory.picture(new Media(null, null)));
            assertEquals("", ViewingHistory.picture(null));
            assertEquals("pic", ViewingHistory.profilePicture(new Size("pic", 150)));
            assertEquals("", ViewingHistory.profilePicture(new Object()));
        } finally {
            ViewingHistory.sizes = original;
        }
    }

    @Test public void picturesSurviveARestartAndAnOlderFileStillReads() throws Exception {
        File file = new File(new File(context.getFilesDir(), "hushgram-viewing"), "history.tsv");
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        java.nio.file.Files.write(file.toPath(), "1000\tPOST\t1_1\tann\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ViewingHistory.forgetForTests();
        List<ViewingHistory.Row> old = ViewingHistory.entries(context);
        assertEquals("a line from before the pictures came", 1, old.size());
        assertEquals("", old.get(0).picture);

        ViewingHistory.add(context, Kind.REEL, "2_2", "bob", "https://scontent.cdninstagram.com/r.jpg", 2_000L);
        ViewingHistory.add(context, Kind.POST, "1_1", "ann", "", 2_000L + 5 * 60_000L);
        Utils.awaitBackgroundTasksForTests();
        ViewingHistory.forgetForTests();
        List<ViewingHistory.Row> back = ViewingHistory.entries(context);
        assertEquals(2, back.size());
        assertEquals("1_1", back.get(0).id);
        assertEquals("", back.get(0).picture);
        assertEquals("https://scontent.cdninstagram.com/r.jpg", back.get(1).picture);

        ViewingHistory.add(context, Kind.REEL, "2_2", "bob", "", 3_000L + 10 * 60_000L);
        assertEquals("seen again with no picture keeps the one it had",
                "https://scontent.cdninstagram.com/r.jpg", ViewingHistory.entries(context).get(0).picture);
    }

    /** #228: a picture fetched once is kept on the phone, since Instagram's address stops working. */
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aFetchedPictureIsKeptUntilTheHistoryIsCleared() throws Exception {
        ViewingHistory.Fetch original = ViewingHistory.fetch;
        int[] fetches = {0};
        ViewingHistory.fetch = (ctx, url, size) -> {
            fetches[0]++;
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFF3366CC);
            return bitmap;
        };
        try {
            ViewingHistory.add(context, Kind.REEL, "4_4", "cy", "https://scontent.cdninstagram.com/c.jpg", 1_000L);
            ViewingHistory.Row row = ViewingHistory.entries(context).get(0);
            assertTrue(ViewingHistory.picture(context, row, 48) != null);
            File kept = ViewingHistory.pictureFile(context, row);
            assertTrue("kept in the app's cache", kept.isFile());
            assertTrue(kept.getPath().startsWith(context.getCacheDir().getPath()));

            android.graphics.Bitmap again = ViewingHistory.picture(context, row, 48);
            assertTrue(again != null);
            assertEquals("the second time comes from the phone", 1, fetches[0]);
            int pixel = again.getPixel(again.getWidth() / 2, again.getHeight() / 2);
            assertTrue("the kept copy is that picture, give or take JPEG's rounding",
                    android.graphics.Color.blue(pixel) > 0xB0 && android.graphics.Color.red(pixel) < 0x60);

            ViewingHistory.Row gone = ViewingHistoryRows.row(Kind.POST, "9_9", "x");
            ViewingHistory.Row withPicture = new ViewingHistory.Row(0L, Kind.POST, "9_9", "x", "https://scontent.cdninstagram.com/x.jpg");
            assertTrue(ViewingHistory.picture(context, withPicture, 48) != null);
            assertFalse("a row no longer listed isn't kept", ViewingHistory.pictureFile(context, gone).exists());

            ViewingHistory.add(context, Kind.POST, "6_6", "dee", "https://scontent.cdninstagram.com/d.jpg", 2_000L);
            ViewingHistory.Row post = ViewingHistory.entries(context).get(0);
            assertTrue(ViewingHistory.picture(context, post, 48) != null);
            File postPicture = ViewingHistory.pictureFile(context, post);
            assertTrue(postPicture.isFile());
            assertTrue(ViewingHistory.remove(context, Kind.POST, "6_6"));
            Utils.awaitBackgroundTasksForTests();
            assertFalse("Remove from history takes its picture too", postPicture.exists());
            assertTrue("and leaves the others'", kept.isFile());
            assertEquals(1, ViewingHistory.count(context));

            assertTrue(ViewingHistory.clear(context));
            assertFalse("Clear takes the pictures too", kept.exists());
        } finally {
            ViewingHistory.fetch = original;
        }
    }
}
