/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaScannerConnection;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/**
 * A save dated by its post (#792): the gallery sorts by the date taken it reads out of the file
 * and by the file's modified time, so both have to be the post's, and nothing else in the file
 * may move.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostDateTest {

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<File> temporaries = new ArrayList<>();
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ShadowMediaScannerConnection.reset();
    }

    @After
    public void tearDown() {
        Settings.SAVE_POST_DATE.resetToDefault();
        ShadowMediaScannerConnection.reset();
        for (File file : temporaries) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        temporaries.clear();
    }

    // ---- The switch ----------------------------------------------------------------------------

    @Test
    public void theSwitchStartsOffAndTurnsOn() {
        assertFalse("Date saves by when they were posted starts off", Settings.SAVE_POST_DATE.get());
        assertFalse(PostDate.on());
        Settings.SAVE_POST_DATE.save(true);
        assertTrue(PostDate.on());
    }

    // ---- The file ------------------------------------------------------------------------------

    /**
     * A JPEG gets the post time as its three EXIF dates, marked as UTC so Android doesn't read
     * them as the phone's own time, and as its modified time, and the gallery hears of it. The
     * account and the link are Add the source's to write, so they stay out.
     */
    @Test
    public void aPhotoTakesThePostTimeInItsExifAndAsItsModifiedTime() throws Exception {
        File photo = file(VideoMetadataTest.sample("post-metadata-sample.jpg"), ".jpg");

        PostDate.writeTo(context, androidPath(photo), "image/jpeg", posted());

        ExifInterface read = new ExifInterface(photo.getAbsolutePath());
        assertEquals("2026:09:01 12:00:00", read.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertEquals("2026:09:01 12:00:00", read.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED));
        assertEquals("2026:09:01 12:00:00", read.getAttribute(ExifInterface.TAG_DATETIME));
        assertEquals("+00:00", read.getAttribute("OffsetTimeOriginal"));
        assertEquals("+00:00", read.getAttribute("OffsetTimeDigitized"));
        assertEquals("+00:00", read.getAttribute("OffsetTime"));
        assertNull(read.getAttribute(ExifInterface.TAG_ARTIST));
        assertNull(read.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
        assertEquals(posted().getTime(), photo.lastModified());
        assertTrue(ShadowMediaScannerConnection.getSavedPaths().contains(androidPath(photo)));
    }

    /**
     * An MP4 at a {@code file://} address gets the post time as its movie's, each track's and each
     * track's media times, in place: the same length and every other byte where it was.
     */
    @Test
    public void aVideoTakesThePostTimeInItsMovieAndTrackHeaders() throws Exception {
        byte[] original = VideoMetadataTest.sample(VideoMetadataTest.MOOV_LAST);
        File video = file(original, ".mp4");

        String address = "file://" + androidPath(video);
        PostDate.writeTo(context, address, "video/mp4", posted());

        byte[] dated = Files.readAllBytes(video.toPath());
        assertEquals(original.length, dated.length);
        long want = posted().getTime() / 1000 + VideoMetadata.FROM_1904;
        List<long[]> headers = VideoMetadataTest.headerTimes(dated);
        assertTrue("a movie header and a track's two", headers.size() >= 3);
        for (long[] times : headers) {
            assertEquals("a creation time", want, times[0]);
            assertEquals("a modification time", want, times[1]);
        }
        VideoMetadataTest.assertOnlyHeaderTimesMoved(original, dated);
        assertEquals(posted().getTime(), video.lastModified());
        assertTrue(ShadowMediaScannerConnection.getSavedPaths().contains(Uri.parse(address).getPath()));
    }

    /** A file of another kind keeps every byte and takes the post time only as its modified time. */
    @Test
    public void aFileOfAnotherKindTakesOnlyTheModifiedTime() throws Exception {
        byte[] original = "RIFF....WEBPVP8 ".getBytes(StandardCharsets.US_ASCII);
        File picture = file(original, ".webp");

        PostDate.writeTo(context, androidPath(picture), "image/webp", posted());

        assertArrayEquals(original, Files.readAllBytes(picture.toPath()));
        assertEquals(posted().getTime(), picture.lastModified());
    }

    /** A save that doesn't know its post time, or a file that's gone, is left as it was. */
    @Test
    public void noPostTimeOrNoFileLeavesEverythingAlone() throws Exception {
        byte[] original = VideoMetadataTest.sample(VideoMetadataTest.MOOV_FIRST);
        File video = file(original, ".mp4");
        long modified = video.lastModified();

        PostDate.writeTo(context, androidPath(video), "video/mp4", null);
        PostDate.writeTo(context, androidPath(video), "video/mp4", new Date(0));
        File gone = new File(video.getParentFile(), "gone-" + video.getName());
        PostDate.writeTo(context, androidPath(gone), "video/mp4", posted());

        assertArrayEquals(original, Files.readAllBytes(video.toPath()));
        assertEquals(modified, video.lastModified());
        assertFalse("a missing save was made", gone.exists());
        assertTrue(ShadowMediaScannerConnection.getSavedPaths().isEmpty());
    }

    // ---- The gallery ---------------------------------------------------------------------------

    /** An entry the gallery already dates by the post is left alone: no second scan, no update. */
    @Test
    public void anEntryAlreadyDatedByThePostIsLeftAlone() {
        Gallery gallery = gallery();
        Uri entry = gallery.add(posted().getTime(), posted().getTime() / 1000);

        PostDate.checked(context, "/sdcard/Movies/a.mp4", "video/mp4", entry, posted(), true);

        assertTrue(ShadowMediaScannerConnection.getSavedPaths().isEmpty());
        assertEquals(0, gallery.updates);
    }

    /**
     * A scan Android started when the save closed its file can land after this one with the time of
     * that write, so a modified time read back wrong is scanned once more, and only once.
     */
    @Test
    public void aModifiedTimeReadBackWrongIsScannedOnceMore() {
        Gallery gallery = gallery();
        Uri entry = gallery.add(posted().getTime(), 1_790_000_000L);

        PostDate.checked(context, "/sdcard/Movies/a.mp4", "video/mp4", entry, posted(), true);
        assertTrue(ShadowMediaScannerConnection.getSavedPaths().contains("/sdcard/Movies/a.mp4"));

        ShadowMediaScannerConnection.reset();
        PostDate.checked(context, "/sdcard/Movies/a.mp4", "video/mp4", entry, posted(), false);
        assertTrue("a second scan was asked for", ShadowMediaScannerConnection.getSavedPaths().isEmpty());
    }

    /** With no date taken read out of the file, an MKV's, the gallery is asked for the post's. */
    @Test
    public void anEntryWithNoDateTakenIsGivenThePostTime() {
        Gallery gallery = gallery();
        Uri entry = gallery.add(null, posted().getTime() / 1000);

        PostDate.checked(context, "/sdcard/Movies/a.mkv", MkvJoin.MIME, entry, posted(), true);

        assertEquals(1, gallery.updates);
        assertEquals(posted().getTime(), (long) gallery.taken(entry));
    }

    /** A gallery that refuses an app the date taken, as Android 11 and newer do, is no failure. */
    @Test
    public void aGalleryThatRefusesTheDateTakenIsNoFailure() {
        Gallery gallery = gallery();
        gallery.refuse = true;
        Uri entry = gallery.add(null, posted().getTime() / 1000);

        PostDate.checked(context, "/sdcard/Movies/a.mkv", MkvJoin.MIME, entry, posted(), true);

        assertEquals(1, gallery.updates);
        assertNull(gallery.taken(entry));
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /** Noon on 2026-09-01 UTC. */
    private static Date posted() {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2026, Calendar.SEPTEMBER, 1, 12, 0, 0);
        return calendar.getTime();
    }

    /**
     * [file]'s path the way Android writes one, from the root with forward slashes: /C:/... on
     * Windows, whose own path would read as an address with the scheme C.
     */
    private static String androidPath(File file) {
        String path = file.getAbsolutePath().replace(File.separatorChar, '/');
        return path.startsWith("/") ? path : "/" + path;
    }

    private File file(byte[] bytes, String suffix) throws Exception {
        File file = File.createTempFile("hushgram-date", suffix);
        Files.write(file.toPath(), bytes);
        // Well away from the post time, so a test can tell the two apart.
        assertTrue(file.setLastModified(1_790_000_000_000L));
        temporaries.add(file);
        return file;
    }

    private static Gallery gallery() {
        return Robolectric.setupContentProvider(Gallery.class, MediaStore.AUTHORITY);
    }

    /** The gallery's date taken and modified time per entry, and Android 11's refusal when asked. */
    public static final class Gallery extends ContentProvider {
        private final List<Long> takens = new ArrayList<>();
        private final List<Long> modifieds = new ArrayList<>();
        boolean refuse;
        int updates;

        Uri add(Long taken, long modified) {
            takens.add(taken);
            modifieds.add(modified);
            return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), takens.size());
        }

        Long taken(Uri entry) {
            return takens.get((int) ContentUris.parseId(entry) - 1);
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            int row = (int) ContentUris.parseId(uri) - 1;
            MatrixCursor cursor = new MatrixCursor(projection);
            Object[] values = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                if (MediaStore.MediaColumns.DATE_TAKEN.equals(projection[i])) values[i] = takens.get(row);
                else if (MediaStore.MediaColumns.DATE_MODIFIED.equals(projection[i])) values[i] = modifieds.get(row);
            }
            cursor.addRow(values);
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            updates++;
            if (refuse) throw new IllegalArgumentException("Mutation of datetaken is not allowed.");
            takens.set((int) ContentUris.parseId(uri) - 1, values.getAsLong(MediaStore.MediaColumns.DATE_TAKEN));
            return 1;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override public String getType(Uri uri) {
            return null;
        }
    }
}
