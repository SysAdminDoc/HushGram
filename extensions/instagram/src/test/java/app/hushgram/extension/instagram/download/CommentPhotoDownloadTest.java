/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.DiagnosticRedactor;
import app.hushgram.extension.shared.diagnostics.HookStatus;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentPhotoDownloadTest {
    private Context context;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        ShadowToast.reset();
    }

    private static CommentPhotoDownload.Images images(Object expected, List<?> candidates) {
        return new CommentPhotoDownload.Images() {
            public Object versions(Object media) { assertSame(expected, media); return media; }
            public List<?> candidates(Object versions) { return candidates; }
            public String url(Object candidate) { return (String) candidate; }
            public int width(Object candidate) { return 1440; }
            public int height(Object candidate) { return 1080; }
        };
    }

    @Test public void onlySuppliedMetaPhotoAddressesAreCopiedVerbatim() {
        List<String> urls = new ArrayList<>(Arrays.asList(
                "https://scontent.cdninstagram.com/p.jpg?se=keep%2Bexact",
                "file:///data/user/0/com.instagram.android/cache/pending.jpg",
                "content://media/external/images/pending",
                "https://media.giphy.com/p.gif",
                "http://scontent.fbcdn.net/p.jpg",
                "https://scontent.fbcdn.net/a.gif?still=1",
                "https://scontent.fbcdn.net/A.GIF",
                "https://scontent.fbcdn.net/a.mp4",
                "https://scontent.fbcdn.net/a.m4v",
                "https://scontent.fbcdn.net/a.webm",
                "https://scontent.fbcdn.net/a.m3u8",
                "https://scontent.fbcdn.net/a.mpd",
                "https://127.0.0.1/p.jpg",
                "https://user@scontent.fbcdn.net/p.jpg",
                "https://scontent.fbcdn.net:8443/p.jpg",
                "",
                null,
                "https://scontent-lax3-1.xx.fbcdn.net/v/p.webp"));
        List<MediaSave.Rendition> snapshot = CommentPhotoDownload.snapshot(urls, images(urls, urls));
        assertEquals(2, snapshot.size());
        assertEquals(urls.get(0), snapshot.get(0).url);
        assertEquals("https://scontent-lax3-1.xx.fbcdn.net/v/p.webp", snapshot.get(1).url);
        assertEquals(1440, snapshot.get(0).width);
        assertEquals(1080, snapshot.get(0).height);
        urls.clear();
        assertEquals(2, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test public void missingModelsGetNoImageFallback() {
        assertTrue(CommentPhotoDownload.snapshot(null).isEmpty());
        assertTrue("the unpatched bridges find nothing", CommentPhotoDownload.snapshot(new Object()).isEmpty());
        Object media = new Object();
        assertTrue(CommentPhotoDownload.snapshot(media, images(media, null)).isEmpty());
        assertTrue(CommentPhotoDownload.snapshot(media, images(media, Collections.emptyList())).isEmpty());
        CommentPhotoDownload.Images noVersions = new CommentPhotoDownload.Images() {
            public Object versions(Object m) { return null; }
            public List<?> candidates(Object versions) { throw new AssertionError("no versions to read"); }
            public String url(Object candidate) { throw new AssertionError(); }
            public int width(Object candidate) { throw new AssertionError(); }
            public int height(Object candidate) { throw new AssertionError(); }
        };
        assertTrue(CommentPhotoDownload.snapshot(media, noVersions).isEmpty());
    }

    private static List<String> counted(String counts) {
        return Collections.singletonList(FamilyNames.COMMENT_PHOTO + ": invoked 0, 0 found, 0 missing. Counted: " + counts);
    }

    @Test public void eachWayTheSizesComeBackEmptyCountsItsOwnReasonOnce() {
        Object media = new Object();
        HookStatus.clear();
        assertTrue(CommentPhotoDownload.snapshot(null, images(null, null)).isEmpty());
        assertTrue("the read that found no media already counted it", HookStatus.report().isEmpty());

        CommentPhotoDownload.Images noVersions = new CommentPhotoDownload.Images() {
            public Object versions(Object m) { return null; }
            public List<?> candidates(Object versions) { throw new AssertionError("no versions to read"); }
            public String url(Object candidate) { throw new AssertionError(); }
            public int width(Object candidate) { throw new AssertionError(); }
            public int height(Object candidate) { throw new AssertionError(); }
        };
        assertTrue(CommentPhotoDownload.snapshot(media, noVersions).isEmpty());
        assertEquals(counted("no image_versions2 1"), HookStatus.report());

        for (List<?> none : Arrays.asList(null, Collections.emptyList(), Arrays.asList(null, null))) {
            HookStatus.clear();
            assertTrue(CommentPhotoDownload.snapshot(media, images(media, none)).isEmpty());
            assertEquals(String.valueOf(none), counted("no candidates 1"), HookStatus.report());
        }

        // Every size refused: each reason once, in the order first seen, whatever the size count.
        HookStatus.clear();
        assertTrue(CommentPhotoDownload.snapshot(media, images(media, Arrays.asList(
                "https://media.giphy.com/large.jpg", "https://media.giphy.com/small.jpg",
                "http://scontent.fbcdn.net/p.jpg", null, "https://scontent.fbcdn.net/a.mp4", "https://scontent.fbcdn.net/b.gif"))).isEmpty());
        assertEquals(counted("size refused (its host is not one of Meta's media servers) 1, size refused (it is not HTTPS) 1, "
                + "size refused (animated or video) 1"), HookStatus.report());
        assertTrue(CommentPhotoDownload.snapshot(media, images(media, Collections.singletonList("https://media.giphy.com/p.jpg"))).isEmpty());
        assertEquals(counted("size refused (its host is not one of Meta's media servers) 2, size refused (it is not HTTPS) 1, "
                + "size refused (animated or video) 1"), HookStatus.report());
        HookStatus.clear();
    }

    @Test public void aKeptSizeCountsAFoundPhotoAndNoRefusal() {
        HookStatus.clear();
        Object media = new Object();
        List<MediaSave.Rendition> kept = CommentPhotoDownload.snapshot(media, images(media, Arrays.asList(
                "https://media.giphy.com/p.jpg", "https://scontent.cdninstagram.com/p.jpg")));
        assertEquals(1, kept.size());
        assertEquals(counted("photo found 1"), HookStatus.report());
        HookStatus.clear();
    }

    /** Each reason prints in the saved report as written: no address, host or id goes in, and the redactor keeps it. */
    @Test public void everyReasonIsFixedTextTheReportKeepsAsWritten() {
        Object media = new Object();
        List<String> refusedAlone = Arrays.asList("", "not an address", "http://scontent.fbcdn.net/p.jpg", "https:///p.jpg",
                "https://user@scontent.fbcdn.net/p.jpg", "https://scontent.fbcdn.net:8443/p.jpg", "https://127.0.0.1/p.jpg",
                "https://media.giphy.com/p.jpg", "https://scontent.fbcdn.net/a.webm");
        Set<String> reasons = new LinkedHashSet<>();
        for (String url : refusedAlone) {
            HookStatus.clear();
            assertTrue(CommentPhotoDownload.snapshot(media, images(media, Collections.singletonList(url))).isEmpty());
            List<String> report = HookStatus.report();
            assertEquals(url, 1, report.size());
            String line = report.get(0);
            assertEquals(url, line, DiagnosticRedactor.redact(line));
            String reason = line.substring(line.indexOf("Counted: ") + "Counted: ".length(), line.length() - " 1".length());
            assertTrue(reason, reason.startsWith("size refused ("));
            assertFalse(reason, reason.contains("fbcdn") || reason.contains("giphy") || reason.contains("127.0.0.1"));
            reasons.add(reason);
        }
        assertEquals("every refusal the policy gives has its own name", refusedAlone.size(), reasons.size());
        HookStatus.clear();
    }

    @Test public void copyIsDetachedAndUnmodifiable() {
        List<MediaSave.Rendition> source = new ArrayList<>(Collections.singletonList(
                new MediaSave.Rendition("https://scontent.cdninstagram.com/p.jpg", 640, 480, 0)));
        List<MediaSave.Rendition> copy = CommentPhotoDownload.copy(source);
        source.clear();
        assertEquals(1, copy.size());
        assertThrows(UnsupportedOperationException.class, () -> copy.add(null));
    }

    @Test public void aSaveThatCannotStartSaysSoAndNeverThrows() {
        CommentPhotoDownload.save(context, Collections.emptyList(), PostDetails.NONE);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));
        ShadowToast.reset();
        CommentPhotoDownload.save(null, Collections.emptyList(), null);
        CommentPhotoDownload.failed(null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowToast.getTextOfLatestToast());
    }
}
