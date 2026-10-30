/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.ExifInterface;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
 * Where a saved photo came from, written into the photo's EXIF. The link has to be the real post
 * address, the dates the real post time, and the picture itself has to come out byte for byte the
 * same, so a gallery shows the same image it always did with the source attached.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostMetadataTest {

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A real 8x8 baseline JPEG beside this test, so the platform ExifInterface rewrites a genuine file. */
    private static final String SAMPLE = "post-metadata-sample.jpg";

    private final List<File> temporaries = new ArrayList<>();

    @After
    public void tearDown() {
        Settings.SAVE_PHOTO_METADATA.resetToDefault();
        for (File file : temporaries) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        temporaries.clear();
    }

    // ---- The link ------------------------------------------------------------------------------

    /** The shortcode is Instagram's own, checked against ids the ecosystem's tools map the same way. */
    @Test
    public void shortcodeMatchesKnownIds() {
        assertEquals("DFDjk1fRK43", PostMetadata.shortcode("3549837394260045367"));
        assertEquals("ybyPRoQWzX", PostMetadata.shortcode("908540701891980503"));
        assertEquals("Yb4Av", PostMetadata.shortcode("409960495"));
    }

    @Test
    public void shortcodeOfZeroIsTheFirstLetter() {
        assertEquals("A", PostMetadata.shortcode("0"));
    }

    @Test
    public void shortcodeOfSomethingThatIsntANumberIsNothing() {
        assertNull(PostMetadata.shortcode(null));
        assertNull(PostMetadata.shortcode(""));
        assertNull(PostMetadata.shortcode("DFDjk1fRK43"));
        assertNull(PostMetadata.shortcode("12x34"));
    }

    @Test
    public void permalinkIsThePostAddress() {
        assertEquals("https://www.instagram.com/p/DFDjk1fRK43/",
                PostMetadata.permalink(PostDetails.of("3549837394260045367")));
    }

    /** Instagram's {@code <pk>_<owner id>} id gives the pk's shortcode, so the owner id drops out. */
    @Test
    public void permalinkUsesThePkFromAFullMediaId() {
        assertEquals("https://www.instagram.com/p/Yb4Av/",
                PostMetadata.permalink(PostDetails.of("409960495_17841400000000000", "someone", null)));
    }

    @Test
    public void permalinkIsNothingWithoutAnId() {
        assertNull(PostMetadata.permalink(PostDetails.profilePicture("someone")));
        assertNull(PostMetadata.permalink(PostDetails.NONE));
    }

    // ---- The switch ----------------------------------------------------------------------------

    @Test
    public void theSwitchStartsOff() {
        assertFalse("Add the source to saved photos starts off", Settings.SAVE_PHOTO_METADATA.get());
        assertFalse(PostMetadata.on());
    }

    @Test
    public void theSwitchTurnsItOn() {
        Settings.SAVE_PHOTO_METADATA.save(true);
        assertTrue(PostMetadata.on());
        Settings.SAVE_PHOTO_METADATA.save(false);
        assertFalse(PostMetadata.on());
    }

    // ---- The write ------------------------------------------------------------------------------

    /**
     * A full save writes the account, the post time and the post link into a real JPEG through the
     * platform ExifInterface, and the picture's own bytes come out untouched.
     */
    @Test
    public void tagsTheAccountDateAndLinkAndLeavesThePictureAlone() throws Exception {
        byte[] original = sampleBytes();
        File photo = jpeg(original);

        PostDetails details = new PostDetails("3549837394260045367", "stevious", postedUtc());
        ExifInterface write = new ExifInterface(photo.getAbsolutePath());
        assertTrue("a full save has something to write", PostMetadata.apply(write, details));
        write.saveAttributes();

        ExifInterface read = new ExifInterface(photo.getAbsolutePath());
        assertEquals(details.owner, read.getAttribute(ExifInterface.TAG_ARTIST));
        assertEquals("2026:09:01 12:00:00", read.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertEquals("2026:09:01 12:00:00", read.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED));
        assertEquals("2026:09:01 12:00:00", read.getAttribute(ExifInterface.TAG_DATETIME));
        assertEquals("https://www.instagram.com/p/DFDjk1fRK43/",
                read.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));

        byte[] tagged = Files.readAllBytes(photo.toPath());
        assertTrue("the Exif was added", tagged.length > original.length);
        assertPictureBytesSurvive(original, tagged);
    }

    /** A profile picture knows only the account, so that's all it writes, with no link or date. */
    @Test
    public void aProfilePictureCarriesOnlyTheAccount() throws Exception {
        File photo = jpeg(sampleBytes());

        PostDetails details = PostDetails.profilePicture("stevious");
        ExifInterface write = new ExifInterface(photo.getAbsolutePath());
        assertTrue(PostMetadata.apply(write, details));
        write.saveAttributes();

        ExifInterface read = new ExifInterface(photo.getAbsolutePath());
        assertEquals(details.owner, read.getAttribute(ExifInterface.TAG_ARTIST));
        assertNull(read.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        assertNull(read.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
    }

    /** A save that knows nothing of its post writes nothing, so the file is never rewritten. */
    @Test
    public void aSaveThatKnowsNothingWritesNothing() throws Exception {
        File photo = jpeg(sampleBytes());
        ExifInterface write = new ExifInterface(photo.getAbsolutePath());
        assertFalse(PostMetadata.apply(write, PostDetails.NONE));
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private static byte[] sampleBytes() throws IOException {
        try (InputStream in = PostMetadataTest.class.getResourceAsStream(SAMPLE)) {
            assertNotNull("no " + SAMPLE + " beside the test", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private File jpeg(byte[] bytes) throws Exception {
        File file = File.createTempFile("hushgram-meta", ".jpg");
        Files.write(file.toPath(), bytes);
        temporaries.add(file);
        return file;
    }

    /** Noon on 2026-09-01 UTC, so the written EXIF date is one fixed value whatever the test zone. */
    private static Date postedUtc() {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(2026, Calendar.SEPTEMBER, 1, 12, 0, 0);
        return calendar.getTime();
    }

    /**
     * ExifInterface only rewrites the Exif segment at the front of a JPEG, so everything from the
     * start-of-scan marker to the end of the file, the picture's own compressed bytes, has to come
     * out exactly as it went in.
     */
    private static void assertPictureBytesSurvive(byte[] original, byte[] tagged) {
        int sos = markerIndex(original, (byte) 0xDA);
        assertTrue("the test JPEG has a scan", sos >= 0);
        byte[] picture = new byte[original.length - sos];
        System.arraycopy(original, sos, picture, 0, picture.length);
        assertTrue("the picture's own bytes are preserved", indexOf(tagged, picture) >= 0);
    }

    /** The offset of the {@code 0xFF <marker>} two-byte marker, or -1. */
    private static int markerIndex(byte[] data, byte marker) {
        for (int i = 0; i + 1 < data.length; i++) {
            if ((data[i] & 0xFF) == 0xFF && data[i + 1] == marker) return i;
        }
        return -1;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
