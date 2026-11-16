/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.media.ExifInterface;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/**
 * Remove hidden data from saved photos (#699). A photo saved from Instagram starts with an EXIF
 * block and a Photoshop IPTC block that holds Meta's FBMD tag; both come out, along with comments,
 * and every other byte of the file stays as it was, in order.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PhotoScrubTest {

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The real 8x8 JPEG PostMetadataTest tags: JFIF, tables, frame, scan, nothing hidden. */
    private static final String SAMPLE = "post-metadata-sample.jpg";

    private static final byte[] FBMD = ascii("FBMD");

    private final List<File> temporaries = new ArrayList<>();

    @After
    public void tearDown() {
        Settings.SAVE_PHOTO_SCRUB.resetToDefault();
        Settings.SAVE_PHOTO_METADATA.resetToDefault();
        for (File file : temporaries) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        temporaries.clear();
    }

    // ---- The switch ----------------------------------------------------------------------------

    @Test
    public void theSwitchStartsOff() {
        assertFalse("Remove hidden data from saved photos starts off", Settings.SAVE_PHOTO_SCRUB.get());
        assertFalse(PhotoScrub.on());
        Settings.SAVE_PHOTO_SCRUB.save(true);
        assertTrue(PhotoScrub.on());
    }

    /** Off, a save goes through untouched, and a save with no writer is no error either way. */
    @Test
    public void offTheSavedFileIsLeftAlone() throws Exception {
        PhotoScrub.scrub(context(), null);
        Settings.SAVE_PHOTO_SCRUB.save(true);
        PhotoScrub.scrub(context(), null);
    }

    // ---- The strip -----------------------------------------------------------------------------

    /**
     * Shaped like a real Instagram save: EXIF first, then JFIF, then the Photoshop block carrying
     * FBMD. EXIF, IPTC and the comment come out; JFIF, the color profile, Adobe's marker, the tables
     * and the whole scan stay byte for byte in their order.
     */
    @Test
    public void takesOutTheFbmdTagExifAndCommentsAndKeepsThePicture() throws Exception {
        Photo photo = instagramShaped();
        byte[] clean = PhotoScrub.strip(photo.saved);
        assertNotNull("something hidden was found", clean);
        assertArrayEquals(photo.expected, clean);
        assertEquals("no FBMD left", -1, indexOf(clean, FBMD));
        assertTrue("the original had FBMD", indexOf(photo.saved, FBMD) > 0);
    }

    @Test
    public void aPhotoWithNothingHiddenIsNotRewritten() throws Exception {
        assertNull(PhotoScrub.strip(sampleBytes()));
    }

    /** Fill bytes before a marker are legal JPEG; the segment after them is still read and dropped. */
    @Test
    public void fillBytesBeforeAMarkerAreRead() throws Exception {
        byte[] sample = sampleBytes();
        byte[] withFill = concat(soi(), new byte[] {(byte) 0xFF, (byte) 0xFF}, iptc(), rest(sample, 2));
        assertArrayEquals(sample, PhotoScrub.strip(withFill));
    }

    @Test
    public void whatIsntAJpegItCanReadIsLeftAlone() throws Exception {
        assertNull(PhotoScrub.strip(null));
        assertNull(PhotoScrub.strip(new byte[0]));
        assertNull(PhotoScrub.strip(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
        byte[] saved = instagramShaped().saved;
        // Cut part way through the IPTC block: its length runs past the end of the file.
        int iptcAt = indexOf(saved, ascii("Photoshop 3.0")) - 4;
        assertNull(PhotoScrub.strip(Arrays.copyOf(saved, iptcAt + 20)));
        // A segment length under two can't be real.
        byte[] broken = saved.clone();
        broken[iptcAt + 2] = 0;
        broken[iptcAt + 3] = 1;
        assertNull(PhotoScrub.strip(broken));
        // No marker where one has to be.
        byte[] lost = saved.clone();
        lost[2] = 0x00;
        assertNull(PhotoScrub.strip(lost));
    }

    // ---- The saved file ------------------------------------------------------------------------

    /**
     * The file the save published is rewritten in place, and Add the source can still tag it after,
     * the order a save runs them in.
     */
    @Test
    public void aSavedPhotoIsRewrittenAndAddTheSourceStillTagsIt() throws Exception {
        Photo photo = instagramShaped();
        File file = jpeg(photo.saved);
        PhotoScrub.scrubAt(context(), "file://" + file.getAbsolutePath(), "image/jpeg");
        assertArrayEquals(photo.expected, Files.readAllBytes(file.toPath()));

        Settings.SAVE_PHOTO_METADATA.save(true);
        PostDetails details = PostDetails.profilePicture("stevious");
        PostMetadata.writeTo(context(), "file://" + file.getAbsolutePath(), "image/jpeg", details);
        ExifInterface read = new ExifInterface(file.getAbsolutePath());
        assertEquals("stevious", read.getAttribute(ExifInterface.TAG_ARTIST));
        assertEquals(-1, indexOf(Files.readAllBytes(file.toPath()), FBMD));
    }

    /** A plain path, the way an Android 9 save can hand it over, works the same. */
    @Test
    public void aPlainPathIsRewrittenToo() throws Exception {
        Photo photo = instagramShaped();
        File file = jpeg(photo.saved);
        PhotoScrub.scrubAt(context(), file.getAbsolutePath(), "image/jpeg");
        assertArrayEquals(photo.expected, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void anythingButAJpegSaveIsLeftUntouched() throws Exception {
        Photo photo = instagramShaped();
        for (String mime : new String[] {"video/mp4", "image/png", "image/webp", null}) {
            File file = jpeg(photo.saved);
            PhotoScrub.scrubAt(context(), "file://" + file.getAbsolutePath(), mime);
            assertArrayEquals(String.valueOf(mime), photo.saved, Files.readAllBytes(file.toPath()));
        }
    }

    /** A file that can't be read is a logged miss, never a throw into the save. */
    @Test
    public void aFileThatIsGoneIsNoError() {
        PhotoScrub.scrubAt(context(), "file:///nowhere/hushgram-gone.jpg", "image/jpeg");
        PhotoScrub.scrubAt(context(), "content://media/external/images/media/404", "image/jpeg");
    }

    /** A write that breaks part way puts the original bytes back before the failure goes up. */
    @Test
    public void aWriteThatFailsPutsTheOriginalBack() throws Exception {
        byte[] original = ascii("original photo");
        byte[] clean = ascii("clean");
        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        IOException broke = new IOException("disk went away");
        int[] opened = {0};
        try {
            PhotoScrub.rewrite(() -> {
                opened[0]++;
                if (opened[0] == 1) {
                    return new OutputStream() {
                        @Override public void write(int b) throws IOException {
                            throw broke;
                        }
                    };
                }
                return restored;
            }, original, clean);
            fail("the failure went up");
        } catch (IOException failure) {
            assertSame(broke, failure);
        }
        assertEquals(2, opened[0]);
        assertArrayEquals(original, restored.toByteArray());
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /** A photo as Instagram serves it, and what the strip should leave of it. */
    private static final class Photo {
        final byte[] saved;
        final byte[] expected;

        Photo(byte[] saved, byte[] expected) {
            this.saved = saved;
            this.expected = expected;
        }
    }

    private static Photo instagramShaped() throws IOException {
        byte[] sample = sampleBytes();
        // The sample is SOI, then its 18-byte JFIF segment, then the tables and the scan.
        assertEquals(0xE0, sample[3] & 0xFF);
        int jfifEnd = 2 + 2 + (((sample[4] & 0xFF) << 8) | (sample[5] & 0xFF));
        byte[] jfif = Arrays.copyOfRange(sample, 2, jfifEnd);
        byte[] tablesAndScan = rest(sample, jfifEnd);
        byte[] icc = segment(0xE2, concat(ascii("ICC_PROFILE"), new byte[] {0, 1, 1}, ascii("color")));
        byte[] adobe = segment(0xEE, concat(ascii("Adobe"), new byte[] {0, 100, 0, 0, 0, 0, 1}));
        byte[] exif = segment(0xE1, concat(ascii("Exif"), new byte[] {0, 0}, ascii("MM camera place time")));
        byte[] comment = segment(0xFE, ascii("a comment"));
        byte[] saved = concat(soi(), exif, jfif, iptc(), icc, comment, adobe, tablesAndScan);
        byte[] expected = concat(soi(), jfif, icc, adobe, tablesAndScan);
        return new Photo(saved, expected);
    }

    /** A Photoshop IPTC block holding Original Transmission Reference set to an FBMD id. */
    private static byte[] iptc() {
        byte[] fbmd = ascii("FBMD0a000a6d0100002e0400003d080000");
        byte[] record = concat(new byte[] {0x1C, 0x02, 0x67, 0, (byte) fbmd.length}, fbmd);
        byte[] resource = concat(ascii("8BIM"), new byte[] {0x04, 0x04, 0, 0, 0, 0, 0, (byte) record.length}, record);
        return segment(0xED, concat(ascii("Photoshop 3.0"), new byte[] {0}, resource));
    }

    private static byte[] segment(int marker, byte[] body) {
        int length = body.length + 2;
        return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length}, body);
    }

    private static byte[] soi() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8};
    }

    private static byte[] rest(byte[] data, int from) {
        return Arrays.copyOfRange(data, from, data.length);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sampleBytes() throws IOException {
        try (InputStream in = PhotoScrubTest.class.getResourceAsStream(SAMPLE)) {
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
        File file = File.createTempFile("hushgram-scrub", ".jpg");
        Files.write(file.toPath(), bytes);
        temporaries.add(file);
        return file;
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
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
