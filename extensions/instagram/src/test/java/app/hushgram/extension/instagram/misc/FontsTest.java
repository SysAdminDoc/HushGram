/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.graphics.Typeface;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.Setting;

/**
 * Font: Instagram's Prism Sans and its text views take the phone's font or a picked file at the
 * weight and slant they had, and Instagram's own font stays whenever that can't be done.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FontsTest {
    /** The first four bytes of a TrueType font, then a little of the rest. */
    private static final byte[] TRUE_TYPE = {0x00, 0x01, 0x00, 0x00, 0x00, 0x0c, 0x00, (byte) 0x80};

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Fonts.resetForTests();
        removeKept();
    }

    @After
    public void restore() {
        Settings.FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Fonts.resetForTests();
        removeKept();
    }

    /** Instagram's font to start, with a restart owed for a change, and every font goes through as it was. */
    @Test
    public void instagramsFontToStartChangesNothing() {
        assertEquals(FontChoice.INSTAGRAM, Settings.FONT.defaultValue);
        assertTrue("fonts are kept once loaded, so a change takes a restart", Settings.FONT.rebootApp);
        Typeface bold = Typeface.create(Typeface.SERIF, 700, false);
        assertSame(bold, Fonts.prismTypeface(bold));
        TextView view = view(bold);
        Fonts.textView(view);
        assertSame(bold, view.getTypeface());
        assertEquals(List.of(FamilyNames.FONT + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** The phone's font, at the weight and slant of each Prism Sans it replaces. */
    @Test
    public void thePhonesFontKeepsWeightAndSlant() {
        Settings.FONT.save(FontChoice.SYSTEM);
        Typeface semibold = Fonts.prismTypeface(Typeface.create(Typeface.SERIF, 600, false));
        assertSame(Typeface.create(Typeface.DEFAULT, 600, false), semibold);
        assertEquals(600, semibold.getWeight());
        Typeface boldItalic = Fonts.prismTypeface(Typeface.create(Typeface.SERIF, 700, true));
        assertEquals(700, boldItalic.getWeight());
        assertTrue(boldItalic.isItalic());
        assertSame(Typeface.create(Typeface.DEFAULT, 700, true), boldItalic);
        assertEquals(List.of(FamilyNames.FONT + ": invoked 2, 0 found, 0 missing. Counted: " + Fonts.PHONE + " 2"),
                HookStatus.report());
    }

    /** A text view takes the chosen font once, and a view already in it is left alone. */
    @Test
    public void aTextViewTakesTheChosenFontOnce() {
        Settings.FONT.save(FontChoice.SYSTEM);
        TextView view = view(Typeface.create(Typeface.MONOSPACE, 700, false));
        Fonts.textView(view);
        assertSame(Typeface.create(Typeface.DEFAULT, 700, false), view.getTypeface());
        Fonts.textView(view);
        TextView plain = view(null);
        Fonts.textView(plain);
        assertEquals(400, plain.getTypeface().getWeight());
        assertFalse(plain.getTypeface().isItalic());
        assertEquals(List.of(FamilyNames.FONT + ": invoked 3, 0 found, 0 missing. Counted: " + Fonts.PHONE + " 2"),
                HookStatus.report());
    }

    /** A picked file that loads is the font, read once for the process. */
    @Test
    public void aPickedFileIsTheFont() throws Exception {
        keep(TRUE_TYPE);
        int[] loads = {0};
        Fonts.loader = file -> {
            loads[0]++;
            return file.isFile() ? Typeface.SERIF : null;
        };
        Settings.FONT.save(FontChoice.FILE);
        assertSame(Typeface.create(Typeface.SERIF, 700, false), Fonts.prismTypeface(Typeface.create(Typeface.DEFAULT, 700, false)));
        assertSame(Typeface.create(Typeface.SERIF, 400, false), Fonts.prismTypeface(Typeface.DEFAULT));
        assertEquals("the file loads once", 1, loads[0]);
        assertEquals(List.of(FamilyNames.FONT + ": invoked 2, 0 found, 0 missing. Counted: " + Fonts.PICKED + " 2"),
                HookStatus.report());
    }

    /** A file that's gone or won't load leaves Instagram's font, with one toast however often it's asked. */
    @Test
    public void aFileThatWontLoadLeavesInstagramsFontWithOneToast() {
        Settings.FONT.save(FontChoice.FILE);
        Typeface bold = Typeface.create(Typeface.SERIF, 700, false);
        assertSame(bold, Fonts.prismTypeface(bold));
        assertSame(bold, Fonts.prismTypeface(bold));
        assertEquals(1, ShadowToast.shownToastCount());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().startsWith("Couldn't load your font file"));

        Fonts.resetForTests();
        keep(TRUE_TYPE);
        Fonts.loader = file -> null;
        assertSame(bold, Fonts.prismTypeface(bold));
        assertEquals(2, ShadowToast.shownToastCount());
    }

    /** Paused, before the settings are read, or with no font loaded yet, Instagram's own goes through. */
    @Test
    public void pausedUnreadyAndEmptyLeaveInstagramsFont() {
        Settings.FONT.save(FontChoice.SYSTEM);
        Typeface bold = Typeface.create(Typeface.SERIF, 700, false);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(bold, Fonts.prismTypeface(bold));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Fonts.resetForTests();

        SettingsContextRule.withoutContext(() -> assertSame(bold, Fonts.prismTypeface(bold)));
        assertNull(Fonts.prismTypeface(null));
        Fonts.textView(null);

        assertSame(Typeface.create(Typeface.DEFAULT, 700, false), Fonts.prismTypeface(bold));
    }

    /** The weight each of Instagram's font resources draws Prism Sans in, and 0 for every font that isn't it. */
    @Test
    public void prismSansIsKnownByItsResourceNames() {
        assertEquals(400, Fonts.prismWeight("IGUIBetav9_Regular"));
        assertEquals(600, Fonts.prismWeight("IGUIBetav9_SemiBold"));
        assertEquals(700, Fonts.prismWeight("IGUIBetav9_Bold"));
        assertEquals("the family of the three", 400, Fonts.prismWeight("prism_sans"));
        for (String other : new String[] {"Optimistic_Display_A_Bd", "bcn_icon_font", "ig_logo_font", "instagram_signature",
                "roboto_medium_numbers", "IGUIBetav9_Condensed", "IGUIBetav9_", "prism_sans_mono", ""}) {
            assertEquals(other, 0, Fonts.prismWeight(other));
        }
        assertEquals(0, Fonts.prismWeight((String) null));
    }

    /**
     * #166: Compose text (a profile's bio on 451) loads Prism Sans from the font resources, so that
     * load answers the chosen font at the resource's weight. Any other font resource, Instagram's
     * font chosen, or Pause, and the resource loads as before.
     */
    @Test
    public void prismSansFromTheFontResourcesTakesTheChosenFont() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        int bold = 0x7f090003;
        Fonts.WEIGHTS.put(bold, 700);
        assertNull("Instagram's font", Fonts.resourceFont(context, bold));

        Settings.FONT.save(FontChoice.SYSTEM);
        Fonts.resetForTests();
        Fonts.WEIGHTS.put(bold, 700);
        Typeface swapped = Fonts.resourceFont(context, bold);
        assertSame(Typeface.create(Typeface.DEFAULT, 700, false), swapped);
        assertSame("Instagram's own loader hands it on unchanged", swapped, Fonts.prismTypeface(swapped));
        assertNull("not a font Instagram draws its text in", Fonts.resourceFont(context, android.R.string.ok));
        assertEquals("looked up once", Integer.valueOf(0), Fonts.WEIGHTS.get(android.R.string.ok));
        assertNull("no such resource", Fonts.resourceFont(context, 0x7f09ffff));

        // Fonts are read once a process, so Pause shows from the next start.
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Fonts.resetForTests();
        Fonts.WEIGHTS.put(bold, 700);
        assertNull("paused", Fonts.resourceFont(context, bold));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertNull(Fonts.resourceFont(null, bold));
        assertEquals(List.of(FamilyNames.FONT + ": invoked 7, 0 found, 0 missing. Counted: " + Fonts.PHONE + " 1"),
                HookStatus.report());
    }

    /**
     * #166: Instagram draws the emoji above the comment box as pictures, in the Typeface its emoji lookup
     * answers. A picked file answers it, the same Typeface every time, so an emoji font picked as the Font
     * reaches them. Instagram's font, the phone's font or Pause, and Instagram's own answer goes through.
     */
    @Test
    public void emojiPicturesTakeAPickedFile() throws Exception {
        Typeface instagrams = Typeface.MONOSPACE;
        assertNull("Instagram's font leaves the phone's emoji", Fonts.emojiTypeface(null));
        assertSame("and Instagram's own answer", instagrams, Fonts.emojiTypeface(instagrams));
        Settings.FONT.save(FontChoice.SYSTEM);
        assertNull("the phone's font leaves the phone's emoji", Fonts.emojiTypeface(null));

        keep(TRUE_TYPE);
        Settings.FONT.save(FontChoice.FILE);
        Fonts.resetForTests();
        Fonts.loader = file -> Typeface.SERIF;
        assertSame("a file without color emoji, which would draw them black", instagrams, Fonts.emojiTypeface(instagrams));

        keep(font(false, "cmap", "CBDT", "glyf"));
        Fonts.resetForTests();
        Fonts.loader = file -> Typeface.SERIF;
        Typeface heart = Fonts.emojiTypeface(null);
        assertSame(Typeface.SERIF, heart);
        assertSame("the same one each time, whatever Instagram answered", heart, Fonts.emojiTypeface(instagrams));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", instagrams, Fonts.emojiTypeface(instagrams));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(List.of(FamilyNames.FONT + ": invoked 7, 0 found, 0 missing. Counted: " + Fonts.EMOJI + " 2"),
                HookStatus.report());
    }

    /** Color emoji are a CBDT or COLR table, in a font or the first font of a collection. Anything else, or a short file, isn't. */
    @Test
    public void colorEmojiAreKnownByTheirTables() throws Exception {
        File file = Fonts.kept(RuntimeEnvironment.getApplication());
        for (Object[] each : new Object[][] {
                {"Google's color bitmaps", font(false, "cmap", "CBDT", "CBLC"), true},
                {"layered color glyphs", font(false, "COLR", "CPAL"), true},
                {"a collection whose first font has them", font(true, "CBDT"), true},
                {"a text font", font(false, "cmap", "glyf", "head"), false},
                {"Apple's color bitmaps, which Android doesn't draw", font(false, "sbix"), false},
                {"a file cut short", TRUE_TYPE, false},
        }) {
            keep((byte[]) each[1]);
            assertEquals((String) each[0], each[2], Fonts.drawsInColor(file));
        }
    }

    /**
     * #166: Instagram keeps each emoji picture it draws, across restarts, by the part of its name before
     * the first "//". With a file picked, that part carries the file's size and time, so pictures drawn
     * before it was picked, or in another file, aren't shown in its place, and the part Instagram reads to
     * draw stays. Any other choice, a file that won't load, or Pause, and the name stays.
     */
    @Test
    public void emojiNamesCarryThePickedFile() throws Exception {
        String single = "emoji:/v13-api35//🔥";
        String sheet = "emoji-sprite-sheet:/emoji-sprite-sheet-api35-1234-dpi-420//6//🙌,🔥";
        assertEquals("Instagram's font", single, Fonts.emojiName(single));
        Settings.FONT.save(FontChoice.SYSTEM);
        Fonts.resetForTests();
        assertEquals("the phone's font", sheet, Fonts.emojiName(sheet));

        keep(TRUE_TYPE);
        Settings.FONT.save(FontChoice.FILE);
        Fonts.resetForTests();
        Fonts.loader = file -> Typeface.SERIF;
        assertEquals("a file without color emoji leaves the pictures alone", single, Fonts.emojiName(single));

        keep(font(false, "cmap", "CBDT"));
        File kept = Fonts.kept(RuntimeEnvironment.getApplication());
        assertTrue(kept.setLastModified(1_700_000_000_000L));
        Fonts.resetForTests();
        Fonts.loader = file -> Typeface.SERIF;
        String mark = "-hg" + Long.toString(kept.length(), 36) + "x" + Long.toString(kept.lastModified(), 36);
        String named = Fonts.emojiName(single);
        assertEquals("emoji:/v13-api35" + mark + "//🔥", named);
        assertEquals("a sheet", "emoji-sprite-sheet:/emoji-sprite-sheet-api35-1234-dpi-420" + mark + "//6//🙌,🔥",
                Fonts.emojiName(sheet));
        assertEquals("a name with no // takes it at the end", "v13" + mark, Fonts.emojiName("v13"));

        keep(font(false, "COLR", "CPAL", "glyf"));
        assertTrue(kept.setLastModified(1_800_000_000_000L));
        assertEquals("the same for the life of the process, like the font", named, Fonts.emojiName(single));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", single, Fonts.emojiName(single));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        Fonts.resetForTests();
        Fonts.loader = file -> Typeface.SERIF;
        String again = Fonts.emojiName(single);
        assertTrue("another file after a restart: " + again, again.startsWith("emoji:/v13-api35-hg") && !again.equals(named));
        Fonts.resetForTests();
        Fonts.loader = file -> null;
        assertEquals("a file that won't load", single, Fonts.emojiName(single));
        assertNull(Fonts.emojiName(null));
        assertEquals(List.of(FamilyNames.FONT + ": invoked 11, 0 found, 0 missing. Counted: " + Fonts.EMOJI_NAMED + " 5"),
                HookStatus.report());
    }

    /** A font file is copied in once it loads, and anything else keeps the font kept before. */
    @Test
    public void onlyAFontThatLoadsIsKept() throws Exception {
        Fonts.loader = file -> Typeface.SERIF;
        assertTrue(Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(TRUE_TYPE)));
        File kept = Fonts.kept(RuntimeEnvironment.getApplication());
        assertArrayEquals(TRUE_TYPE, Files.readAllBytes(kept.toPath()));

        assertFalse("not a font", Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream("hello there".getBytes())));
        assertFalse("too short to be one", Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(new byte[] {0, 1})));
        Fonts.loader = file -> null;
        byte[] open = {'O', 'T', 'T', 'O', 0, 1};
        assertFalse("Android won't load it", Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(open)));
        assertArrayEquals("the font kept before stays", TRUE_TYPE, Files.readAllBytes(kept.toPath()));
        assertFalse("no partial copy is left", new File(kept.getParentFile(), Fonts.FILE + ".tmp").exists());

        Fonts.loader = file -> Typeface.SERIF;
        assertTrue(Fonts.store(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(open)));
        assertArrayEquals(open, Files.readAllBytes(kept.toPath()));
    }

    @Test
    public void theFourWaysAFontFileStarts() {
        assertTrue(Fonts.startsLikeAFont(new byte[] {0x00, 0x01, 0x00, 0x00}));
        assertTrue(Fonts.startsLikeAFont(new byte[] {'O', 'T', 'T', 'O'}));
        assertTrue(Fonts.startsLikeAFont(new byte[] {'t', 'r', 'u', 'e'}));
        assertTrue(Fonts.startsLikeAFont(new byte[] {'t', 't', 'c', 'f'}));
        assertFalse(Fonts.startsLikeAFont(new byte[] {'w', 'O', 'F', 'F'}));
        assertFalse(Fonts.startsLikeAFont(new byte[] {'P', 'K', 3, 4}));
    }

    @Test public void theNextProcessRemovesOnlyAbandonedStages() throws Exception {
        keep(TRUE_TYPE);
        Fonts.loader = file -> Typeface.SERIF;
        assertNotNull(Fonts.prepare(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(TRUE_TYPE)));
        assertNotNull(Fonts.prepare(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(TRUE_TYPE)));
        restartFontProcess();
        try (Fonts.Prepared next = Fonts.prepare(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(TRUE_TYPE))) {
            assertNotNull(next);
            assertArrayEquals(TRUE_TYPE, Files.readAllBytes(Fonts.kept(RuntimeEnvironment.getApplication()).toPath()));
        }
        assertOnlyLiveFont();
    }

    @Test public void anInterruptedRenameRollsBackWhenTheSettingDidNotLand() throws Exception {
        Settings.FONT.save(FontChoice.SYSTEM);
        keep(TRUE_TYPE);
        byte[] replacement = {'O', 'T', 'T', 'O', 1};
        abandonedInstall(replacement, Settings.FONT);
        restartFontProcess();
        try (Fonts.Prepared next = Fonts.prepare(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(TRUE_TYPE))) {
            assertNotNull(next);
            assertArrayEquals(TRUE_TYPE, Files.readAllBytes(Fonts.kept(RuntimeEnvironment.getApplication()).toPath()));
        }
        assertEquals(FontChoice.SYSTEM, Settings.FONT.savedValue());
        assertOnlyLiveFont();
    }

    @Test public void anUnacceptedReplacementRollsBackWithoutRevertingTheSavedChoice() throws Exception {
        Settings.FONT.save(FontChoice.SYSTEM);
        keep(TRUE_TYPE);
        byte[] replacement = {'O', 'T', 'T', 'O', 1};
        abandonedInstall(replacement, Settings.FONT);
        assertTrue(Settings.FONT.save(FontChoice.FILE));
        restartFontProcess();
        firstReadUses(TRUE_TYPE);
        assertEquals("recovery preserves saved preferences", FontChoice.FILE, Settings.FONT.savedValue());
        assertOnlyLiveFont();
    }

    @Test public void aSameChoiceInterruptedReplacementIsRecoveredBeforeTheFirstRead() throws Exception {
        Settings.FONT.save(FontChoice.FILE);
        keep(TRUE_TYPE);
        abandonedInstall(new byte[]{'O', 'T', 'T', 'O', 1}, Settings.FONT);
        restartFontProcess();
        firstReadUses(TRUE_TYPE);
        assertOnlyLiveFont();
    }

    @Test public void anInterruptedFirstFileUsesTheNativeFontOnTheFirstRead() throws Exception {
        Settings.FONT.save(FontChoice.FILE);
        abandonedInstall(TRUE_TYPE, Settings.FONT);
        restartFontProcess();
        Typeface nativeFont = Typeface.MONOSPACE;
        assertSame(nativeFont, Fonts.prismTypeface(nativeFont));
        assertFalse(Fonts.kept(RuntimeEnvironment.getApplication()).exists());
        assertOnlyLiveFont();
    }

    @Test public void anAcceptedSameChoiceReplacementSurvivesTheFirstRead() throws Exception {
        Settings.FONT.save(FontChoice.FILE);
        keep(TRUE_TYPE);
        byte[] replacement = {'O', 'T', 'T', 'O', 1};
        Fonts.Prepared accepted = abandonedInstall(replacement, Settings.FONT);
        assertTrue(Settings.FONT.save(FontChoice.FILE));
        accepted.accept();
        accepted.close();
        restartFontProcess();
        firstReadUses(replacement);
        assertOnlyLiveFont();
    }

    @Test public void aSecondaryFontReadDoesNotRecoverOrExposeAPendingReplacement() throws Exception {
        Settings.FONT.save(FontChoice.FILE);
        keep(TRUE_TYPE);
        byte[] replacement = {'O', 'T', 'T', 'O', 1};
        abandonedInstall(replacement, Settings.FONT);
        restartFontProcess();
        var info = Utils.getContext().getApplicationInfo();
        String process = info.processName;
        try {
            info.processName = Utils.getContext().getPackageName() + ":worker";
            Typeface nativeFont = Typeface.MONOSPACE;
            assertSame(nativeFont, Fonts.prismTypeface(nativeFont));
            File live = Fonts.kept(RuntimeEnvironment.getApplication());
            assertArrayEquals(replacement, Files.readAllBytes(live.toPath()));
            assertTrue(new File(live.getParentFile(), live.getName() + ".font-previous.tmp").isFile());
            assertTrue(new File(live.getParentFile(), live.getName() + ".font-transaction.tmp").isFile());
        } finally {
            info.processName = process;
        }
    }

    @Test public void aJournalThatCannotBeRemovedStillRollsBackAfterAcceptIsCalled() throws Exception {
        Settings.FONT.save(FontChoice.FILE);
        keep(TRUE_TYPE);
        Fonts.Prepared pending = abandonedInstall(new byte[]{'O', 'T', 'T', 'O', 1}, Settings.FONT);
        File live = Fonts.kept(RuntimeEnvironment.getApplication());
        File journal = new File(live.getParentFile(), live.getName() + ".font-transaction.tmp");
        try (FileChannel held = holdJournal(journal)) {
            assertFalse("the real file handle must refuse deletion", journal.delete());
            pending.accept();
            pending.close();
            assertArrayEquals("a refused receipt must keep the last accepted bytes", TRUE_TYPE, Files.readAllBytes(live.toPath()));
            assertTrue(journal.isFile());
            assertTrue(new File(live.getParentFile(), live.getName() + ".font-previous.tmp").isFile());
        }
        pending.close();
        restartFontProcess();
        firstReadUses(TRUE_TYPE);
        assertOnlyLiveFont();
    }

    @Test public void aReleasedJournalCanBeAcceptedAndKept() throws Exception {
        Settings.FONT.save(FontChoice.FILE);
        keep(TRUE_TYPE);
        byte[] replacement = {'O', 'T', 'T', 'O', 1};
        Fonts.Prepared pending = abandonedInstall(replacement, Settings.FONT);
        File live = Fonts.kept(RuntimeEnvironment.getApplication());
        File journal = new File(live.getParentFile(), live.getName() + ".font-transaction.tmp");
        try (FileChannel held = holdJournal(journal)) {
            assertFalse("the control holds the same deletion barrier", journal.delete());
        }
        pending.accept();
        pending.close();
        restartFontProcess();
        firstReadUses(replacement);
        assertOnlyLiveFont();
    }

    /** Windows provides a real deletion failure without replacing the filesystem or production IO. */
    private static FileChannel holdJournal(File journal) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        OpenOption denyDelete = (OpenOption) Class.forName("com.sun.nio.file.ExtendedOpenOption")
                .getField("NOSHARE_DELETE").get(null);
        return FileChannel.open(journal.toPath(), StandardOpenOption.READ, denyDelete);
    }

    @Test public void anUnknownTransactionKeepsItsRollbackAndRefusesReplacement() throws Exception {
        keep(TRUE_TYPE);
        byte[] replacement = {'O', 'T', 'T', 'O', 1};
        abandonedInstall(replacement, BaseSettings.DEBUG);
        restartFontProcess();
        try (Fonts.Prepared next = Fonts.prepare(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(TRUE_TYPE))) {
            assertNotNull(next);
            assertFalse(Fonts.prepareInstall(RuntimeEnvironment.getApplication(), next));
            assertArrayEquals(replacement, Files.readAllBytes(Fonts.kept(RuntimeEnvironment.getApplication()).toPath()));
        }
        File live = Fonts.kept(RuntimeEnvironment.getApplication());
        assertTrue(new File(live.getParentFile(), live.getName() + ".font-transaction.tmp").isFile());
        assertTrue(new File(live.getParentFile(), live.getName() + ".font-previous.tmp").isFile());
    }

    /** Leaves the prepared operation without closing it, as process death does. */
    private static Fonts.Prepared abandonedInstall(byte[] bytes, Setting<?> setting) {
        Fonts.loader = file -> Typeface.SERIF;
        Fonts.Prepared abandoned = Fonts.prepare(RuntimeEnvironment.getApplication(), new ByteArrayInputStream(bytes));
        assertNotNull(abandoned);
        assertTrue(Fonts.prepareInstall(RuntimeEnvironment.getApplication(), abandoned));
        assertTrue(abandoned.recordSetting(setting, setting == Settings.FONT ? FontChoice.FILE : true));
        assertTrue(abandoned.install());
        return abandoned;
    }

    private static void firstReadUses(byte[] expected) {
        byte[][] read = {null};
        Fonts.loader = file -> {
            try {
                read[0] = Files.readAllBytes(file.toPath());
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
            return Typeface.SERIF;
        };
        Fonts.prismTypeface(Typeface.MONOSPACE);
        assertArrayEquals("the first hook reads the recovered file without another import", expected, read[0]);
    }

    private static void restartFontProcess() {
        Fonts.resetForTests();
        Fonts.loader = file -> Typeface.SERIF;
    }

    private static void assertOnlyLiveFont() {
        File live = Fonts.kept(RuntimeEnvironment.getApplication());
        File[] files = live.getParentFile().listFiles();
        assertNotNull(files);
        for (File file : files) assertEquals("an abandoned import file survived recovery", live, file);
    }

    private static TextView view(Typeface typeface) {
        TextView view = new TextView(RuntimeEnvironment.getApplication());
        if (typeface != null) view.setTypeface(typeface);
        return view;
    }

    /** The start of a TrueType font, or of a collection holding one, listing [tags] as its tables. */
    private static byte[] font(boolean collection, String... tags) {
        ByteBuffer out = ByteBuffer.allocate((collection ? 16 : 0) + 12 + 16 * tags.length);
        if (collection) out.putInt(0x74746366).putInt(0x00010000).putInt(1).putInt(16);
        out.putInt(0x00010000).putShort((short) tags.length).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String tag : tags) out.put(tag.getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(0).putInt(0);
        return out.array();
    }

    private static void keep(byte[] bytes) {
        File kept = Fonts.kept(RuntimeEnvironment.getApplication());
        kept.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(kept)) {
            output.write(bytes);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void removeKept() {
        File kept = Fonts.kept(RuntimeEnvironment.getApplication());
        if (kept.exists()) kept.delete();
        File[] files = kept.getParentFile().listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
    }
}
