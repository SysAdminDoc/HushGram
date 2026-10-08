/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import kotlin.jvm.functions.Function0;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.download.MediaSave;
import app.hushgram.extension.instagram.download.PostDetails;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentPhotoTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<?> stock = Collections.singletonList("Report");
    private final FakeNative nativeRows = new FakeNative();
    private final List<List<MediaSave.Rendition>> queued = new ArrayList<>();
    private final List<PostDetails> named = new ArrayList<>();
    private final CommentPhoto.Save save = (context, snapshot, details) -> {
        queued.add(snapshot);
        named.add(details);
    };
    private Context context;

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 36;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.COPY_COMMENTS.save(true);
        Settings.SAVE_COMMENT_PHOTOS.save(true);
        ShadowToast.reset();
    }

    @After public void restore() {
        Settings.COPY_COMMENTS.resetToDefault();
        Settings.SAVE_COMMENT_PHOTOS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        HookStatus.clear();
    }

    private static List<MediaSave.Rendition> photo(String name) {
        return new ArrayList<>(Arrays.asList(
                new MediaSave.Rendition("https://scontent.cdninstagram.com/" + name + "-small.jpg", 640, 480, 0),
                new MediaSave.Rendition("https://scontent.cdninstagram.com/" + name + "-large.jpg", 1440, 1080, 0)));
    }

    @Test public void explicitTapSavesAnImmutableSnapshotAndReturnsForDismissal() {
        List<MediaSave.Rendition> source = photo("selected");
        List<?> rows = CommentPhoto.rows(stock, source, context, nativeRows, save);
        assertEquals(2, rows.size());
        assertSame(stock.get(0), rows.get(0));
        assertTrue("showing the menu saves nothing", queued.isEmpty());
        source.clear();
        assertNull(((Row) rows.get(1)).callback.invoke());
        assertEquals(1, queued.size());
        assertEquals(2, queued.get(0).size());
        assertEquals("https://scontent.cdninstagram.com/selected-large.jpg", queued.get(0).get(1).url);
        assertEquals(1440, queued.get(0).get(1).width);
        assertEquals(1080, queued.get(0).get(1).height);
        assertThrows(UnsupportedOperationException.class, () -> queued.get(0).clear());
        assertEquals(36, context.getApplicationInfo().targetSdkVersion);
    }

    @Test public void immutableRepeatedAndChangedMenusKeepStockRowsAndOneOwnedRow() {
        List<?> rows = CommentPhoto.rows(stock, photo("first"), context, nativeRows, save);
        assertEquals(1, stock.size());
        assertSame(rows, CommentPhoto.rows(rows, photo("first"), context, nativeRows, save));
        List<?> duplicated = Collections.unmodifiableList(Arrays.asList(stock.get(0), rows.get(1), rows.get(1)));
        List<?> repaired = CommentPhoto.rows(duplicated, photo("first"), context, nativeRows, save);
        assertEquals(2, repaired.size());
        assertSame(stock.get(0), repaired.get(0));
        assertEquals(3, duplicated.size());
        List<?> changed = CommentPhoto.rows(rows, photo("second"), context, nativeRows, save);
        assertEquals(2, changed.size());
        assertSame(stock.get(0), changed.get(0));
        ((Row) changed.get(1)).callback.invoke();
        assertTrue(queued.get(0).get(0).url.endsWith("second-small.jpg"));
        assertEquals(3, nativeRows.created);
    }

    @Test public void noPhotoNowRemovesOnlyStaleOwnedRows() {
        List<?> first = CommentPhoto.rows(stock, photo("first"), context, nativeRows, save);
        List<?> duplicated = Collections.unmodifiableList(Arrays.asList(first.get(1), stock.get(0), first.get(1)));
        for (Object comment : Arrays.asList(Collections.emptyList(), new Object(), "parent media only", "pending upload only")) {
            List<?> cleaned = CommentPhoto.rows(duplicated, comment, context, nativeRows, save);
            assertEquals(Collections.singletonList(stock.get(0)), cleaned);
            assertSame(cleaned, CommentPhoto.rows(cleaned, comment, context, nativeRows, save));
            assertEquals(3, duplicated.size());
        }
        assertSame(stock, CommentPhoto.rows(stock, new Object(), context, nativeRows, save));
        assertEquals(1, nativeRows.created);
        assertTrue(queued.isEmpty());
    }

    @Test public void eachFamilyAloneAndTogetherKeepDistinctOwnedRows() {
        CommentCopyTest.FakeNative copy = new CommentCopyTest.FakeNative();
        CommentActions.Action copies = list -> CommentCopy.rows(list, new String[]{"original"}, context, copy);
        CommentActions.Action photos = list -> CommentPhoto.rows(list, photo("selected"), context, nativeRows, save);
        CommentActions.Action never = list -> { throw new AssertionError("a family that isn't in the build ran"); };
        assertSame(stock, CommentActions.dispatch(stock, false, false, never, never));
        List<?> copyAlone = CommentActions.dispatch(stock, true, false, copies, never);
        assertEquals(2, copyAlone.size());
        assertTrue(((CommentCopyTest.Row) copyAlone.get(1)).callback instanceof CommentCopy.CopyAction);
        List<?> photoAlone = CommentActions.dispatch(stock, false, true, never, photos);
        assertEquals(2, photoAlone.size());
        assertTrue(((Row) photoAlone.get(1)).callback instanceof CommentPhoto.PhotoAction);
        List<?> together = CommentActions.dispatch(stock, true, true, copies, photos);
        assertEquals(3, together.size());
        assertSame(stock.get(0), together.get(0));
        assertTrue(((CommentCopyTest.Row) together.get(1)).callback instanceof CommentCopy.CopyAction);
        assertTrue(((Row) together.get(2)).callback instanceof CommentPhoto.PhotoAction);
        assertSame(together, CommentActions.dispatch(together, true, true, copies, photos));
        Settings.COPY_COMMENTS.save(false);
        List<?> copyOff = CommentActions.dispatch(together, true, true, copies, photos);
        assertSame("a switched-off family leaves its rows to Instagram's next menu", together, copyOff);
        assertTrue(queued.isEmpty());
    }

    @Test public void offPausedUnreadyAndMissingInputsLeaveStockUntouched() {
        Settings.SAVE_COMMENT_PHOTOS.save(false);
        assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save));
        Settings.SAVE_COMMENT_PHOTOS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save)));
        assertSame(stock, CommentPhoto.rows(stock, photo("selected"), null, nativeRows, save));
        assertSame(stock, CommentPhoto.rows(stock, null, context, nativeRows, save));
        assertNull(CommentPhoto.rows(null, photo("selected"), context, nativeRows, save));
        assertSame("the unpatched stubs find no photo", stock, CommentPhoto.rows(stock, new Object(), context));
        assertEquals(0, nativeRows.created);
        assertTrue(queued.isEmpty());
    }

    @Test public void getterRowAndQueueFailuresStayContainedAndRespectALateSwitch() {
        nativeRows.fail = true;
        assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save));
        nativeRows.fail = false;
        nativeRows.failRow = true;
        assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save));
        nativeRows.failRow = false;
        nativeRows.nullRow = true;
        assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save));
        nativeRows.nullRow = false;

        List<?> rows = CommentPhoto.rows(stock, photo("selected"), context, nativeRows,
                (ctx, snapshot, details) -> { throw new IllegalStateException("queue failed"); });
        assertNull(((Row) rows.get(1)).callback.invoke());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Download failed", String.valueOf(ShadowToast.getTextOfLatestToast()));

        rows = CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save);
        Settings.SAVE_COMMENT_PHOTOS.save(false);
        assertNull(((Row) rows.get(1)).callback.invoke());
        Settings.SAVE_COMMENT_PHOTOS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull(((Row) rows.get(1)).callback.invoke());
        assertTrue(queued.isEmpty());
    }

    @Test public void initialOffAndSafetyGatesNeverReadOrSaveNativeCommentMedia() {
        Settings.SAVE_COMMENT_PHOTOS.resetToDefault();
        assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save));
        assertSame(null, CommentPhoto.rows(null, photo("selected"), context, nativeRows, save));
        Settings.SAVE_COMMENT_PHOTOS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save)));
        SettingsContextRule.beforeThePauseIsDecided(() ->
                assertSame(stock, CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save)));
        assertEquals("no media getter or native row callback", 0, nativeRows.inspected);
        assertEquals(0, nativeRows.created);
        assertTrue(queued.isEmpty());
    }

    private static final List<String> READS = Arrays.asList(
            "selected", "raw", "gif", "info", "media", "kind", "photoKind", "mediaGif");

    private static List<String> counted(String counts) {
        return Collections.singletonList(FamilyNames.COMMENT_PHOTO + ": invoked 0, 0 found, 0 missing. Counted: " + counts);
    }

    /** One case per step that can come back without a photo: its reason, the reads it takes, and how it breaks. */
    private static final class Step {
        final String reason;
        final int reads;
        final Consumer<FakeReads> breaks;
        Step(String reason, int reads, Consumer<FakeReads> breaks) {
            this.reason = reason;
            this.reads = reads;
            this.breaks = breaks;
        }
    }

    @Test public void eachStepThatFindsNoPhotoCountsItsOwnReasonOnceAndReadsNoFurther() {
        List<Step> steps = Arrays.asList(
                new Step("not a selected comment", 1, reads -> reads.isSelected = false),
                new Step("no raw comment", 2, reads -> reads.rawAnswer = null),
                new Step("comment GIF", 3, reads -> reads.gifAnswer = new Object()),
                new Step("no media_comment_info", 4, reads -> reads.infoAnswer = null),
                new Step("no media in media_comment_info", 5, reads -> reads.mediaAnswer = null),
                new Step("media_type 2", 7, reads -> reads.kindAnswer = 2),
                new Step("media_type 8", 7, reads -> reads.kindAnswer = 8),
                new Step("media_type 0", 7, reads -> reads.kindAnswer = 0),
                new Step("media_type other", 7, reads -> reads.kindAnswer = 10),
                new Step("media_type other", 7, reads -> reads.kindAnswer = -1),
                new Step("media_type other", 7, reads -> reads.kindAnswer = "1"),
                new Step("media GIF", 8, reads -> reads.mediaGifAnswer = new Object()));
        for (Step step : steps) {
            HookStatus.clear();
            FakeReads reads = new FakeReads();
            step.breaks.accept(reads);
            assertNull(step.reason, CommentPhoto.photoMedia(reads.comment, reads));
            assertEquals(step.reason, READS.subList(0, step.reads), reads.made);
            assertEquals(step.reason, counted(step.reason + " 1"), HookStatus.report());
        }
    }

    /** Without media_type the reads go on to the GIF and the video, never to the photo kind. */
    private static final List<String> UNTYPED_READS = Arrays.asList(
            "selected", "raw", "gif", "info", "media", "kind", "mediaGif", "videoVersions", "videoDuration");

    /**
     * The server leaves media_type out of a comment's own media. That media passes as a still photo
     * with no GIF and no video, counted as such, and anything that could be a video is refused.
     */
    @Test public void withoutMediaTypeOnlyAMediaWithNoVideoPassesAsAStillPhoto() {
        List<Consumer<FakeReads>> stills = Arrays.asList(
                reads -> { },
                reads -> reads.videoVersionsAnswer = Collections.emptyList(),
                reads -> reads.videoDurationAnswer = 0.0);
        for (Consumer<FakeReads> still : stills) {
            HookStatus.clear();
            FakeReads reads = new FakeReads();
            reads.kindAnswer = null;
            still.accept(reads);
            assertSame(reads.media, CommentPhoto.photoMedia(reads.comment, reads));
            assertEquals(UNTYPED_READS, reads.made);
            assertEquals(counted(CommentPhoto.NO_KIND_STILL + " 1"), HookStatus.report());
        }
        List<Step> refused = Arrays.asList(
                new Step("media GIF", 7, reads -> reads.mediaGifAnswer = new Object()),
                new Step("no media_type, has video", 8, reads -> reads.videoVersionsAnswer = Collections.singletonList(new Object())),
                new Step("no media_type, has video", 8, reads -> reads.videoVersionsAnswer = new Object()),
                new Step("no media_type, has video", 9, reads -> reads.videoDurationAnswer = 12.5),
                new Step("no media_type, has video", 9, reads -> reads.videoDurationAnswer = Double.NaN),
                new Step("no media_type, has video", 9, reads -> reads.videoDurationAnswer = "12.5"));
        for (Step step : refused) {
            HookStatus.clear();
            FakeReads reads = new FakeReads();
            reads.kindAnswer = null;
            step.breaks.accept(reads);
            assertNull(step.reason, CommentPhoto.photoMedia(reads.comment, reads));
            assertEquals(step.reason, UNTYPED_READS.subList(0, step.reads), reads.made);
            assertEquals(step.reason, counted(step.reason + " 1"), HookStatus.report());
        }
        assertEquals("no media_type, has video", CommentPhoto.NO_KIND_VIDEO);
    }

    /** A media_type that isn't a photo's keeps the media out whatever its video reads would say. */
    @Test public void aMediaTypeThatIsntAPhotosNeverReadsTheVideo() {
        HookStatus.clear();
        FakeReads reads = new FakeReads();
        reads.kindAnswer = 2;
        assertNull(CommentPhoto.photoMedia(reads.comment, reads));
        assertFalse(reads.made.contains("videoVersions") || reads.made.contains("videoDuration"));
        assertEquals(counted("media_type 2 1"), HookStatus.report());
    }

    @Test public void aPhotoIsReadInTheBridgesOldOrderAndLeavesItsCountToTheSizes() {
        HookStatus.clear();
        FakeReads reads = new FakeReads();
        assertSame(reads.media, CommentPhoto.photoMedia(reads.comment, reads));
        assertEquals(READS, reads.made);
        assertTrue("the sizes read counts a found photo", HookStatus.report().isEmpty());
    }

    /** The row saves under the comment's author and time, read when the menu opened. */
    @Test public void theCommentsAuthorAndTimeGoWithItsSave() {
        Date written = new Date(1_788_000_000_000L);
        nativeRows.details = PostDetails.of(null, "stevi.ous", written);
        List<?> rows = CommentPhoto.rows(stock, photo("selected"), context, nativeRows, save);
        nativeRows.details = PostDetails.NONE;
        assertNull(((Row) rows.get(1)).callback.invoke());
        assertEquals("stevi.ous", named.get(0).owner);
        assertEquals(written, named.get(0).posted);

        // A failed read of the author still saves the photo, under its usual name.
        nativeRows.failDetails = true;
        rows = CommentPhoto.rows(stock, photo("other"), context, nativeRows, save);
        assertNull(((Row) rows.get(1)).callback.invoke());
        assertSame(PostDetails.NONE, named.get(1));
        assertEquals(2, queued.size());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains("comment author"));
    }

    @Test public void theAuthorAndTimeComeFromTheSelectedCommentOnly() {
        FakeReads reads = new FakeReads();
        reads.authorAnswer = "user";
        reads.createdAnswer = 1_788_000_000L;
        PostDetails details = CommentPhoto.details(reads.comment, reads);
        assertEquals("stevi.ous", details.owner);
        assertEquals(new Date(1_788_000_000_000L), details.posted);
        assertFalse("a comment's photo has no post id of its own here", details.hasVideoId());

        reads.createdAnswer = null;
        assertNull(CommentPhoto.details(reads.comment, reads).posted);
        reads.createdAnswer = 0L;
        assertNull(CommentPhoto.details(reads.comment, reads).posted);
        reads.createdAnswer = 1_788_000_000L;
        reads.authorAnswer = null;
        details = CommentPhoto.details(reads.comment, reads);
        assertNull(details.owner);
        assertNotNull(details.posted);

        reads.rawAnswer = null;
        assertSame(PostDetails.NONE, CommentPhoto.details(reads.comment, reads));
        reads.rawAnswer = reads.raw;
        reads.isSelected = false;
        assertSame(PostDetails.NONE, CommentPhoto.details(reads.comment, reads));
    }

    @Test public void theUnpatchedBridgesCountAnUnselectedComment() {
        HookStatus.clear();
        assertSame(stock, CommentPhoto.rows(stock, new Object(), context));
        assertEquals(Collections.singletonList(FamilyNames.COMMENT_PHOTO
                + ": invoked 1, 0 found, 0 missing. Counted: not a selected comment 1"), HookStatus.report());
    }

    /** A comment's native reads, answering as a photo comment would unless a test breaks one. */
    static final class FakeReads implements CommentPhoto.PhotoReads {
        final List<String> made = new ArrayList<>();
        final Object comment = new Object(), raw = new Object(), info = new Object(), media = new Object();
        boolean isSelected = true;
        Object rawAnswer = raw, gifAnswer, infoAnswer = info, mediaAnswer = media, kindAnswer = 1, mediaGifAnswer;
        Object videoVersionsAnswer, videoDurationAnswer, authorAnswer, createdAnswer;
        public boolean selected(Object c) { made.add("selected"); assertSame(comment, c); return isSelected; }
        public Object raw(Object c) { made.add("raw"); assertSame(comment, c); return rawAnswer; }
        public Object gif(Object r) { made.add("gif"); assertSame(raw, r); return gifAnswer; }
        public Object info(Object r) { made.add("info"); assertSame(raw, r); return infoAnswer; }
        public Object media(Object i) { made.add("media"); assertSame(info, i); return mediaAnswer; }
        public Object kind(Object m) { made.add("kind"); assertSame(media, m); return kindAnswer; }
        public int photoKind() { made.add("photoKind"); return 1; }
        public Object mediaGif(Object m) { made.add("mediaGif"); assertSame(media, m); return mediaGifAnswer; }
        public Object videoVersions(Object m) { made.add("videoVersions"); assertSame(media, m); return videoVersionsAnswer; }
        public Object videoDuration(Object m) { made.add("videoDuration"); assertSame(media, m); return videoDurationAnswer; }
        public Object author(Object r) { made.add("author"); assertSame(raw, r); return authorAnswer; }
        public Object createdAt(Object r) { made.add("createdAt"); assertSame(raw, r); return createdAnswer; }
        public String username(Object user) { made.add("username"); assertEquals("user", user); return "stevi.ous"; }
    }

    static final class Row {
        final Function0<?> callback;
        Row(Object callback) { this.callback = (Function0<?>) callback; }
    }

    static final class FakeNative implements CommentPhoto.NativeRows {
        int created;
        int inspected;
        boolean fail, failRow, nullRow, failDetails;
        PostDetails details = PostDetails.NONE;
        @SuppressWarnings("unchecked") public List<MediaSave.Rendition> photo(Object comment) {
            inspected++;
            if (fail) throw new IllegalStateException("native photo getter failed");
            return comment instanceof List ? (List<MediaSave.Rendition>) comment : null;
        }
        public PostDetails details(Object comment) {
            if (failDetails) throw new IllegalStateException("native author getter failed");
            return details;
        }
        public Object row(Object callback) {
            if (failRow) throw new IllegalStateException("row failed");
            if (nullRow) return null;
            created++;
            return new Row(callback);
        }
        public Object callback(Object row) { inspected++; return row instanceof Row ? ((Row) row).callback : null; }
    }
}
