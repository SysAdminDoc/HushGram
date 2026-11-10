/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Ask for larger photos: a narrower screen reports and asks for 1440, and nothing else changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LargerPhotosTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.ASK_FOR_LARGER_PHOTOS.save(true);
        LargerPhotos.forgetForTests();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.ASK_FOR_LARGER_PHOTOS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        LargerPhotos.forgetForTests();
        HookStatus.clear();
    }

    @Test
    public void aPhotoAcrossANarrowerScreenIsAskedForAt1440() {
        assertEquals(1440, LargerPhotos.wanted(1080, 1080, ON));
        assertEquals("a little under the screen is still across it", 1440, LargerPhotos.wanted(1000, 1080, ON));
        assertEquals(1440, LargerPhotos.wanted(720, 720, ON));
        String report = HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString();
        assertTrue(report, HookStatus.missing(FamilyNames.FULL_RESOLUTION).isEmpty());
    }

    @Test
    public void thumbnailsWiderScreensAndOffKeepTheirWidth() {
        assertEquals("a grid thumbnail", 360, LargerPhotos.wanted(360, 1080, ON));
        assertEquals("a screen already that wide", 1440, LargerPhotos.wanted(1440, 1440, ON));
        assertEquals("a wider ask", 2048, LargerPhotos.wanted(2048, 1080, ON));
        assertEquals("an unknown screen", 1080, LargerPhotos.wanted(1080, 0, ON));
        assertEquals("switch off", 1080, LargerPhotos.wanted(1080, 1080, OFF));
    }

    @Test
    public void theSwitchStartsOffAndIsReadEachTime() {
        Settings.ASK_FOR_LARGER_PHOTOS.resetToDefault();
        assertEquals(Boolean.FALSE, Settings.ASK_FOR_LARGER_PHOTOS.defaultValue);
        Object[] parts = {420, 1080, 2340};
        LargerPhotos.screen(parts);
        assertArrayEquals(new Object[] {420, 1080, 2340}, parts);
        Settings.ASK_FOR_LARGER_PHOTOS.save(true);
        LargerPhotos.screen(parts);
        assertArrayEquals(new Object[] {560, 1440, 3120}, parts);
    }

    @Test
    public void theReportedScreenKeepsItsShapeInEitherOrientation() {
        Object[] portrait = {420, 1080, 2340};
        LargerPhotos.screen(portrait, ON);
        assertArrayEquals(new Object[] {560, 1440, 3120}, portrait);
        Object[] landscape = {420, 2400, 1080};
        LargerPhotos.screen(landscape, ON);
        assertArrayEquals(new Object[] {560, 3200, 1440}, landscape);
    }

    @Test
    public void fullSizeCoversReportThreeTimesACoversWidthOnAnyPhone() {
        Object[] narrow = {320, 720, 1600};
        LargerPhotos.screen(narrow, OFF, ON);
        assertArrayEquals(new Object[] {1920, 4320, 9600}, narrow);
        Object[] wide = {560, 1440, 3088};
        LargerPhotos.screen(wide, ON, ON);
        assertArrayEquals("a 1440 phone, which Ask for larger photos leaves alone", new Object[] {1680, 4320, 9264}, wide);
        Object[] landscape = {420, 2400, 1080};
        LargerPhotos.screen(landscape, OFF, ON);
        assertArrayEquals(new Object[] {1680, 9600, 4320}, landscape);
        Object[] coverWider = {640, 3240, 7200};
        LargerPhotos.screen(coverWider, OFF, ON);
        assertArrayEquals("a 1290 cover (#214) needs more than 3240", new Object[] {853, 4320, 9600}, coverWider);
        Object[] huge = {640, 4320, 9600};
        LargerPhotos.screen(huge, OFF, ON);
        assertArrayEquals(new Object[] {640, 4320, 9600}, huge);
        assertEquals("a third of the reported width covers Instagram's widest photo", 1440, LargerPhotos.COVER_WIDTH / 3);
        assertEquals("the asked width stays Ask for larger photos'", 1080, LargerPhotos.wanted(1080, 1080, OFF));
    }

    @Test
    public void fullSizeCoversStartsOffAndIsReadEachTime() {
        assertEquals(Boolean.FALSE, Settings.FULL_SIZE_COVERS.defaultValue);
        Settings.ASK_FOR_LARGER_PHOTOS.resetToDefault();
        Object[] parts = {420, 1080, 2340};
        LargerPhotos.screen(parts);
        assertArrayEquals(new Object[] {420, 1080, 2340}, parts);
        Settings.FULL_SIZE_COVERS.save(true);
        try {
            LargerPhotos.screen(parts);
            assertArrayEquals(new Object[] {1680, 4320, 9360}, parts);
            Object[] throwing = {420, 1080, 2340};
            LargerPhotos.screen(throwing, ON, THROWS);
            assertArrayEquals(new Object[] {420, 1080, 2340}, throwing);
        } finally {
            Settings.FULL_SIZE_COVERS.resetToDefault();
        }
    }

    /**
     * Under Full size covers Explore's grid pictures come at 1440 for tiles about 360 wide, and a
     * full size decode of each made Explore stutter (#595). A picture several times its view decodes
     * at a power of two that keeps it at least the view's size on both sides.
     */
    @Test
    public void aPictureSeveralTimesItsViewDecodesSmallerButNeverUnderIt() {
        assertEquals("a grid picture in its tile", 4, LargerPhotos.fitting(1440, 1440, 360, 360));
        assertEquals("a tall cover in a tall tile", 2, LargerPhotos.fitting(1440, 2560, 360, 720));
        assertEquals("a wide picture cropped into a square tile keeps its height", 2, LargerPhotos.fitting(1440, 810, 360, 360));
        assertEquals("a photo across the screen", 1, LargerPhotos.fitting(1440, 1800, 1080, 1350));
        assertEquals("just under twice the view", 1, LargerPhotos.fitting(719, 1440, 360, 360));
        assertEquals("exactly twice the view", 2, LargerPhotos.fitting(720, 720, 360, 360));
        assertEquals("an unknown height", 1, LargerPhotos.fitting(1440, 0, 360, 360));
        assertEquals("a view not laid out", 1, LargerPhotos.fitting(1440, 1440, 0, 0));
        for (int picture : new int[] {1, 359, 360, 1080, 1440, 4320, Integer.MAX_VALUE}) {
            for (int view : new int[] {1, 120, 360, 1080}) {
                int sample = LargerPhotos.fitting(picture, picture, view, view);
                assertTrue(picture + " in " + view, sample == 1 || picture / sample >= view);
                assertTrue(picture + " in " + view + " could go further", picture / (sample * 2) < view);
                assertEquals(picture + " in " + view, 0, sample & (sample - 1));
            }
        }
    }

    @Test
    public void onlyASmallerDecodeThanInstagramsIsCounted() {
        assertEquals(4, LargerPhotos.sampleSize(360, 360, 1440, 1440, 1));
        assertEquals("Instagram's own larger sample stands", 8, LargerPhotos.sampleSize(360, 360, 1440, 1440, 8));
        assertEquals(1, LargerPhotos.sampleSize(1080, 1350, 1440, 1800, 1));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(LargerPhotos.SAMPLED + " 1"));
    }

    /** The view's hook reads Full size covers each time and keeps Instagram's sample with it off, a view not laid out, or nothing to read. */
    @Test
    public void theDecodeHookFollowsFullSizeCovers() {
        View tile = new View(RuntimeEnvironment.getApplication());
        tile.layout(0, 0, 360, 360);
        Object picture = new Object();
        assertEquals("covers off", 1, LargerPhotos.sampleSize(tile, picture, 1));
        Settings.FULL_SIZE_COVERS.save(true);
        try {
            // The bridges to the picture's size are written by the patch, so here it's unknown.
            assertEquals("a size the bridges don't know", 1, LargerPhotos.sampleSize(tile, picture, 1));
            assertEquals("no view", 2, LargerPhotos.sampleSize(null, picture, 2));
            assertEquals("no picture", 1, LargerPhotos.sampleSize(tile, null, 1));
            assertEquals("not laid out", 1, LargerPhotos.sampleSize(new View(RuntimeEnvironment.getApplication()), picture, 1));
        } finally {
            Settings.FULL_SIZE_COVERS.resetToDefault();
        }
        String missing = HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString();
        assertTrue(missing, HookStatus.missing(FamilyNames.FULL_RESOLUTION).isEmpty());
    }

    @Test
    public void aWideEnoughOrUnreadableScreenIsLeftAlone() {
        Object[] wide = {560, 1440, 3088};
        LargerPhotos.screen(wide, ON);
        assertArrayEquals(new Object[] {560, 1440, 3088}, wide);
        Object[] none = {420, 0, 0};
        LargerPhotos.screen(none, ON);
        assertArrayEquals(new Object[] {420, 0, 0}, none);
        Object[] strings = {"420", "1080", "2340"};
        LargerPhotos.screen(strings, ON);
        assertArrayEquals(new Object[] {"420", "1080", "2340"}, strings);
        // Without a density to scale, a bigger size is a bigger screen (#433), so none of it changes.
        Object[] textDensity = {"420", 1080, 2340};
        LargerPhotos.screen(textDensity, ON, ON);
        assertArrayEquals(new Object[] {"420", 1080, 2340}, textDensity);
        Object[] noDensity = {0, 1080, 2340};
        LargerPhotos.screen(noDensity, ON, ON);
        assertArrayEquals(new Object[] {0, 1080, 2340}, noDensity);
        Object[] nullDensity = {null, 1080, 2340};
        LargerPhotos.screen(nullDensity, ON, ON);
        assertArrayEquals(new Object[] {null, 1080, 2340}, nullDensity);
        Object[] short2 = {420, 1080};
        LargerPhotos.screen(short2, ON);
        assertArrayEquals(new Object[] {420, 1080}, short2);
        LargerPhotos.screen(null, ON);
    }

    @Test
    public void pausedAndUnreadyLeaveTheScreen() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Object[] paused = {420, 1080, 2340};
        LargerPhotos.screen(paused);
        assertArrayEquals(new Object[] {420, 1080, 2340}, paused);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        Object[] unready = {420, 1080, 2340};
        SettingsContextRule.withoutContext(() -> LargerPhotos.screen(unready));
        assertArrayEquals(new Object[] {420, 1080, 2340}, unready);
    }

    @Test
    public void aThrowingSwitchKeepsWhatInstagramHadAndIsReported() {
        assertEquals(1080, LargerPhotos.wanted(1080, 1080, THROWS));
        Object[] parts = {420, 1080, 2340};
        LargerPhotos.screen(parts, THROWS);
        assertArrayEquals(new Object[] {420, 1080, 2340}, parts);

        String missing = HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString();
        assertTrue(missing, missing.contains("'" + LargerPhotos.ASK + "'"));
        assertTrue(missing, missing.contains("'" + LargerPhotos.SCREEN + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** A start works out the same shorter side the screen hook writes later in the run, from either switch. */
    @Test
    public void theReportedSideIsWhatTheScreenHookWrites() {
        BooleanSupplier[] switches = {ON, OFF};
        for (int shorter : new int[] {0, 420, 720, 1080, 1439, 1440, 2000, 4319, 4320, 5000}) {
            for (BooleanSupplier on : switches) {
                for (BooleanSupplier covers : switches) {
                    Object[] parts = {420, shorter, shorter * 2};
                    LargerPhotos.screen(parts, on, covers);
                    int written = Math.min((Integer) parts[1], (Integer) parts[2]);
                    assertEquals(shorter + " " + on + " " + covers, written, LargerPhotos.reported(shorter, on, covers));
                }
            }
        }
        // A switch whose answer can't change the side isn't read.
        assertEquals(1440, LargerPhotos.reported(1440, THROWS, OFF));
        assertEquals(LargerPhotos.COVER_WIDTH, LargerPhotos.reported(LargerPhotos.COVER_WIDTH, THROWS, THROWS));
    }

    /**
     * The reported screen has more pixels but the phone's size in dp, under either switch. Reported
     * at its own density, a 1080 phone at 420 dpi under Full size covers was 1646 dp wide, a tablet,
     * and Explore's grid came in blocks that left two blank slots in every fourth row of three (#433). The emulator's 720 by 1600 at 320 dpi is the screen that showed it.
     */
    @Test
    public void theReportedScreenKeepsThePhonesSizeInDp() {
        BooleanSupplier[] switches = {ON, OFF};
        int[][] screens = {{320, 720, 1600}, {420, 1080, 2340}, {400, 1080, 2400}, {450, 1080, 2340},
                {480, 1080, 1920}, {560, 1440, 3088}, {600, 1440, 3200}, {640, 1440, 2560}, {420, 2400, 1080}};
        for (int[] screen : screens) {
            for (BooleanSupplier on : switches) {
                for (BooleanSupplier covers : switches) {
                    Object[] parts = {screen[0], screen[1], screen[2]};
                    LargerPhotos.screen(parts, on, covers);
                    String what = screen[1] + "x" + screen[2] + " at " + screen[0] + " " + on + " " + covers + " wrote "
                            + parts[1] + "x" + parts[2] + " at " + parts[0];
                    int density = (Integer) parts[0];
                    assertEquals(what, screen[1] * 160.0 / screen[0], (Integer) parts[1] * 160.0 / density, 1.0);
                    assertEquals(what, screen[2] * 160.0 / screen[0], (Integer) parts[2] * 160.0 / density, 1.0);
                    assertTrue(what, Math.min((Integer) parts[1], (Integer) parts[2]) * 160.0 / density < 600);
                }
            }
        }
    }

    /** The first start at the screen's own size has nothing to drop and leaves a note of it. */
    @Test
    public void aFirstStartAtTheScreensOwnSizeKeepsTheReplies() throws IOException {
        File cache = cache();
        assertFalse(LargerPhotos.forgetSizedReplies(cache, 1080, 1080));
        assertTrue(new File(cache, "http_responses/0a1b.0").isFile());
        assertEquals("1080", note(cache));
        assertFalse(LargerPhotos.forgetSizedReplies(null, 1080, 4320));
    }

    /**
     * Turning Full size covers on drops the replies sized for the old screen at the next start and
     * nothing else in the cache, the queued likes above all. The start after keeps the new replies,
     * and turning it off drops them again (#214).
     */
    @Test
    public void aNewReportedScreenDropsOnlyTheSizedReplies() throws IOException {
        File cache = cache();
        assertFalse(LargerPhotos.forgetSizedReplies(cache, 1080, 1080));

        assertTrue(LargerPhotos.forgetSizedReplies(cache, 1080, LargerPhotos.COVER_WIDTH));
        assertFalse(new File(cache, "http_responses").exists());
        assertTrue(new File(cache, "pending_likes/queued").isFile());
        assertTrue(new File(cache, "images.stash/x").isFile());
        assertEquals(Integer.toString(LargerPhotos.COVER_WIDTH), note(cache));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(LargerPhotos.FORGOT + " 1"));

        write(new File(cache, "http_responses/0c2d.0"), "reply at 4320");
        assertFalse(LargerPhotos.forgetSizedReplies(cache, 1080, LargerPhotos.COVER_WIDTH));
        assertTrue(new File(cache, "http_responses/0c2d.0").isFile());

        assertTrue(LargerPhotos.forgetSizedReplies(cache, 1080, 1080));
        assertFalse(new File(cache, "http_responses").exists());
        assertTrue(new File(cache, "pending_likes/queued").isFile());
        assertEquals("1080", note(cache));
    }

    /**
     * Replies kept from before this version, with no note, are taken as sized for the screen's own
     * width, and a note that can't be read as a width drops them too.
     */
    @Test
    public void noNoteOrAnUnreadableOneCountsAsAnotherScreen() throws IOException {
        File cache = cache();
        assertTrue(LargerPhotos.forgetSizedReplies(cache, 1080, 1440));
        assertFalse(new File(cache, "http_responses").exists());

        write(new File(cache, "http_responses/0a1b.0"), "reply");
        write(new File(cache, LargerPhotos.SIZED_FOR), "wide");
        assertTrue(LargerPhotos.forgetSizedReplies(cache, 1080, 1440));
        assertFalse(new File(cache, "http_responses").exists());
        assertEquals("1440", note(cache));
    }

    /**
     * Replies kept under the note from before #433 were sized for a screen reported at its own
     * density, so under either switch they're dropped once, and the old note goes. With both off
     * they were the screen's own and stay.
     */
    @Test
    public void repliesKeptUnderTheOldNoteAreDroppedOnceUnderEitherSwitch() throws IOException {
        File cache = cache();
        write(new File(cache, LargerPhotos.OLD_NOTE), Integer.toString(LargerPhotos.COVER_WIDTH));
        assertTrue(LargerPhotos.forgetSizedReplies(cache, 1080, LargerPhotos.COVER_WIDTH));
        assertFalse(new File(cache, "http_responses").exists());
        assertFalse(new File(cache, LargerPhotos.OLD_NOTE).exists());
        assertTrue(new File(cache, "pending_likes/queued").isFile());
        assertEquals(Integer.toString(LargerPhotos.COVER_WIDTH), note(cache));
        write(new File(cache, "http_responses/0c2d.0"), "reply at 4320, phone dp");
        assertFalse(LargerPhotos.forgetSizedReplies(cache, 1080, LargerPhotos.COVER_WIDTH));
        assertTrue(new File(cache, "http_responses/0c2d.0").isFile());

        File off = folder.newFolder("off");
        write(new File(off, "http_responses/0a1b.0"), "reply");
        write(new File(off, LargerPhotos.OLD_NOTE), "1080");
        assertFalse(LargerPhotos.forgetSizedReplies(off, 1080, 1080));
        assertTrue(new File(off, "http_responses/0a1b.0").isFile());
        assertFalse(new File(off, LargerPhotos.OLD_NOTE).exists());
        assertEquals("1080", note(off));
    }

    /** At Instagram's start the hook drops the replies in the application's own cache folder. */
    @Test
    public void theStartHookDropsTheRepliesInInstagramsCache() throws IOException {
        Context application = RuntimeEnvironment.getApplication();
        File cache = application.getCacheDir();
        File reply = new File(cache, "http_responses/0a1b.0");
        File note = new File(cache, LargerPhotos.SIZED_FOR);
        try {
            write(reply, "reply");
            write(note, "1");
            LargerPhotos.atStart(application);
            assertFalse(reply.exists());
            assertTrue(Integer.parseInt(note(cache)) > 1);
        } finally {
            Files.deleteIfExists(reply.toPath());
            Files.deleteIfExists(reply.getParentFile().toPath());
            Files.deleteIfExists(note.toPath());
        }
    }

    /** A cache folder that can't be read is reported under its own step, and Instagram starts as it would. */
    @Test
    public void aStartThatThrowsIsReportedNotThrown() {
        Context broken = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public File getCacheDir() {
                throw new IllegalStateException("no cache folder");
            }
        };
        LargerPhotos.atStart(broken);
        LargerPhotos.atStart(null);
        String missing = HookStatus.missing(FamilyNames.FULL_RESOLUTION).toString();
        assertTrue(missing, missing.contains("'" + LargerPhotos.START + "'"));
    }

    /** A cache folder holding sized replies, Instagram's queued likes and its image stash. */
    private File cache() throws IOException {
        File cache = folder.newFolder("cache");
        write(new File(cache, "http_responses/0a1b.0"), "reply");
        write(new File(cache, "pending_likes/queued"), "like");
        write(new File(cache, "images.stash/x"), "image");
        return cache;
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("couldn't make " + parent);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String note(File cache) throws IOException {
        return new String(Files.readAllBytes(new File(cache, LargerPhotos.SIZED_FOR).toPath()), StandardCharsets.UTF_8);
    }
}
