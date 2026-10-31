/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Emoji packs (#166): with a pack or file chosen, each of EmojiCompat's emoji spans draws its emoji
 * in that font, a pack is kept only when it's exactly its maker's file, and Instagram's emoji draw
 * as before whenever that can't be done.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EmojiFontsTest {
    private static final byte[] COLOR = EmojiFontsForTests.font("cmap", "COLR", "CPAL");
    private static final byte[] BITMAPS = EmojiFontsForTests.font("cmap", "CBDT", "CBLC");
    private static final byte[] PLAIN = EmojiFontsForTests.font("cmap", "glyf");
    private static final String LINE = "a X b";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        restore();
    }

    @After
    public void restore() {
        Settings.EMOJI_FONT.resetToDefault();
        Settings.NOTO_EMOJI.resetToDefault();
        Settings.FONT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        EmojiFonts.resetForTests();
        Fonts.resetForTests();
        EmojiFontsForTests.removeKept();
    }

    /** The phone's emoji to start: the font kept isn't even read, and every span draws its own. */
    @Test
    public void thePhonesEmojiToStartDrawNothing() {
        assertEquals(EmojiChoice.PHONE, Settings.EMOJI_FONT.defaultValue);
        assertTrue("a font is read once per process, so a change takes a restart", Settings.EMOJI_FONT.rebootApp);
        keep(COLOR);
        Fonts.loader = file -> {
            throw new AssertionError("read with the phone's emoji chosen");
        };
        assertFalse(drawn(new Span(40), new Canvas(page())));
        assertNull(EmojiFonts.mark());
        assertEquals(EmojiStyle.REPLACE_ALL - 1, EmojiStyle.replaceStrategy(EmojiStyle.REPLACE_ALL - 1));
        assertEquals(List.of(FamilyNames.EMOJI_STYLE + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** A kept pack draws each emoji in the room its span measured, and nowhere else, and is counted. */
    @Test
    public void aKeptPackDrawsInTheSpansPlace() {
        Settings.EMOJI_FONT.save(EmojiChoice.TWEMOJI);
        keep(COLOR);
        Fonts.loader = file -> Typeface.SERIF;
        Bitmap page = page();
        assertTrue(drawn(new Span(40), new Canvas(page)));
        assertTrue("drawn in the span's room", inked(page, 10, 50));
        assertFalse("nothing left of it", inked(page, 0, 9));
        assertFalse("nothing right of it", inked(page, 51, page.getWidth()));
        assertNotNull(EmojiFonts.mark());
        assertEquals(List.of(FamilyNames.EMOJI_STYLE + ": invoked 1, 0 found, 0 missing. Counted: " + EmojiFonts.DRAWN + " 1"),
                HookStatus.report());
    }

    /** With a pack chosen, EmojiCompat is asked to replace every emoji so the pack draws them all, switch or no switch. */
    @Test
    public void aChosenPackReplacesEveryEmoji() {
        Settings.EMOJI_FONT.save(EmojiChoice.BLOBMOJI);
        keep(BITMAPS);
        Fonts.loader = file -> Typeface.SERIF;
        assertFalse(Settings.NOTO_EMOJI.get());
        assertEquals(EmojiStyle.REPLACE_ALL, EmojiStyle.replaceStrategy(0));
        assertEquals(List.of(FamilyNames.EMOJI_STYLE + ": invoked 1, 0 found, 0 missing. Counted: " + EmojiStyle.CHOSEN + " 1"),
                HookStatus.report());
    }

    /** What it can't draw, a pause and settings not read yet leave the span's own drawing. */
    @Test
    public void whatItCantDrawGoesToTheSpan() {
        Settings.EMOJI_FONT.save(EmojiChoice.OPENMOJI);
        keep(COLOR);
        Fonts.loader = file -> Typeface.SERIF;
        Canvas canvas = new Canvas(page());
        Paint paint = paint();
        Span span = new Span(40);
        assertFalse("no canvas", EmojiFonts.drawn(span, null, LINE, 2, 3, 10f, 0, 45, 60, paint));
        assertFalse("no paint", EmojiFonts.drawn(span, canvas, LINE, 2, 3, 10f, 0, 45, 60, null));
        assertFalse("not a span", EmojiFonts.drawn(new Object(), canvas, LINE, 2, 3, 10f, 0, 45, 60, paint));
        assertFalse("a range past the text", EmojiFonts.drawn(span, canvas, LINE, 2, 9, 10f, 0, 45, 60, paint));
        assertFalse("an empty range", EmojiFonts.drawn(span, canvas, LINE, 3, 3, 10f, 0, 45, 60, paint));
        assertFalse("no room", drawn(new Span(0), canvas));
        String selector = "a" + (char) 0xFE0F + " b";
        assertFalse("only a selector", EmojiFonts.drawn(span, canvas, selector, 1, 2, 10f, 0, 45, 60, paint));
        assertFalse("a span that throws", drawn(new Span(-1), canvas));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", drawn(span, canvas));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse("settings not read yet", drawn(span, canvas)));

        assertTrue(drawn(span, canvas));
    }

    /** A kept file without color emoji, or one that won't load, leaves the phone's emoji, and one toast says so. */
    @Test
    public void aFileThatWontLoadLeavesThePhonesEmoji() {
        Settings.EMOJI_FONT.save(EmojiChoice.FILE);
        keep(PLAIN);
        Fonts.loader = file -> Typeface.SERIF;
        assertNull(EmojiFonts.active());
        assertNull(EmojiFonts.active());
        assertEquals(0, EmojiStyle.replaceStrategy(0));
        ShadowLooper.idleMainLooper();
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals("Couldn't load your emoji, so the phone's show.", ShadowToast.getTextOfLatestToast());

        EmojiFonts.resetForTests();
        keep(COLOR);
        Fonts.loader = file -> null;
        assertNull("a file Android won't load", EmojiFonts.active());
        EmojiFonts.resetForTests();
        EmojiFontsForTests.removeKept();
        assertNull("nothing kept", EmojiFonts.active());
    }

    /** Instagram's emoji pictures take a kept pack ahead of a picked font file, under a name of their own. */
    @Test
    public void instagramsEmojiPicturesTakeThePack() {
        Settings.FONT.save(FontChoice.FILE);
        Settings.EMOJI_FONT.save(EmojiChoice.TWEMOJI);
        keep(COLOR);
        Fonts.loader = file -> Typeface.MONOSPACE;
        Typeface instagrams = Typeface.SERIF;
        assertSame(Typeface.MONOSPACE, Fonts.emojiTypeface(instagrams));
        String named = Fonts.emojiName("emoji_3//sheet");
        assertTrue(named, named.startsWith("emoji_3-hge") && named.endsWith("//sheet") && named.length() > "emoji_3-hge//sheet".length());
        assertEquals("the same for the life of the process", named, Fonts.emojiName("emoji_3//sheet"));

        Settings.EMOJI_FONT.save(EmojiChoice.PHONE);
        assertSame("no picked font file is kept", instagrams, Fonts.emojiTypeface(instagrams));
        assertEquals("emoji_3//sheet", Fonts.emojiName("emoji_3//sheet"));
    }

    /** A pick is kept only when it's a font with color emoji, so black emoji never replace them. */
    @Test
    public void aPickNeedsColorEmoji() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Fonts.loader = file -> Typeface.SERIF;
        assertNull(EmojiFonts.prepare(context, new ByteArrayInputStream(PLAIN)));
        assertNull("not a font", EmojiFonts.prepare(context, new ByteArrayInputStream("not a font".getBytes())));
        assertNoFiles();
        try (Fonts.Prepared prepared = EmojiFonts.prepare(context, new ByteArrayInputStream(BITMAPS))) {
            assertNotNull(prepared);
        }
        assertNoFiles();
    }

    /** The phone's and a file download nothing, and a pack that can't be reached or isn't a color font is refused. */
    @Test
    public void aPackDownloadsOnlyAsAColorFont() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Fonts.loader = file -> Typeface.SERIF;
        EmojiFonts.opener = choice -> {
            throw new AssertionError("opened " + choice);
        };
        assertNull(EmojiFonts.download(context, EmojiChoice.PHONE));
        assertNull(EmojiFonts.download(context, EmojiChoice.FILE));
        EmojiFontsForTests.unreachable();
        assertNull(EmojiFonts.download(context, EmojiChoice.TWEMOJI));
        EmojiFontsForTests.serve(PLAIN);
        assertNull(EmojiFonts.download(context, EmojiChoice.TWEMOJI));
        assertNoFiles();
        EmojiChoice[] asked = {null};
        EmojiFonts.opener = choice -> {
            asked[0] = choice;
            return new ByteArrayInputStream(COLOR);
        };
        try (Fonts.Prepared prepared = EmojiFonts.download(context, EmojiChoice.OPENMOJI)) {
            assertNotNull(prepared);
            assertEquals(EmojiChoice.OPENMOJI, asked[0]);
        }
        assertNoFiles();
    }

    /** A download is read through whole only when it's exactly the pack's size and SHA-256. */
    @Test
    public void aDownloadMustBeExactlyThePacksFile() throws Exception {
        String sha = EmojiFonts.hex(MessageDigest.getInstance("SHA-256").digest(COLOR));
        assertArrayEquals(COLOR, readAll(checked(COLOR, sha)));
        assertThrows("short", IOException.class, () -> readAll(checked(Arrays.copyOf(COLOR, COLOR.length - 1), sha)));
        assertThrows("long", IOException.class, () -> readAll(checked(Arrays.copyOf(COLOR, COLOR.length + 1), sha)));
        byte[] changed = COLOR.clone();
        changed[changed.length - 1] ^= 1;
        assertThrows("one byte changed", IOException.class, () -> readAll(checked(changed, sha)));
        assertThrows("skipped", IOException.class, () -> checked(COLOR, sha).skip(4));
    }

    /** Each pack names an exact file from its maker on GitHub, and the phone's and a file name none. */
    @Test
    public void eachPackNamesItsFileExactly() {
        for (EmojiChoice choice : EmojiChoice.values()) {
            if (choice == EmojiChoice.PHONE || choice == EmojiChoice.FILE) {
                assertFalse(choice.pack());
                assertNull(choice.sha256);
                continue;
            }
            assertTrue(choice.url, choice.url.startsWith("https://github.com/") || choice.url.startsWith("https://raw.githubusercontent.com/"));
            assertTrue(choice + " size", choice.bytes > 0 && choice.bytes <= Fonts.MOST_BYTES);
            assertTrue(choice + " SHA-256", choice.sha256.matches("[0-9a-f]{64}"));
        }
        assertTrue(EmojiChoice.kept("TWEMOJI"));
        assertTrue(EmojiChoice.kept("FILE"));
        assertFalse(EmojiChoice.kept("PHONE"));
        assertFalse(EmojiChoice.kept("twemoji"));
    }

    /** The pack kept and chosen isn't downloaded again, and a newly kept one takes a restart. */
    @Test
    public void aKeptPackIsntDownloadedAgainAndANewOneTakesARestart() {
        Context context = RuntimeEnvironment.getApplication();
        assertFalse(EmojiFonts.keeps(context, EmojiChoice.TWEMOJI));
        Settings.EMOJI_FONT.save(EmojiChoice.TWEMOJI);
        assertFalse("nothing kept yet", EmojiFonts.keeps(context, EmojiChoice.TWEMOJI));
        keep(COLOR);
        assertTrue(EmojiFonts.keeps(context, EmojiChoice.TWEMOJI));
        assertFalse(EmojiFonts.keeps(context, EmojiChoice.OPENMOJI));
        assertFalse(EmojiFonts.restartPending());
        EmojiFonts.installed();
        assertTrue(EmojiFonts.restartPending());
        Settings.EMOJI_FONT.save(EmojiChoice.PHONE);
        assertFalse(EmojiFonts.restartPending());
    }

    /** A pack cut off between its rename and its saved choice is rolled back before the first read. */
    @Test
    public void anInterruptedPackRollsBackBeforeTheFirstRead() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Settings.EMOJI_FONT.save(EmojiChoice.TWEMOJI);
        keep(COLOR);
        Fonts.loader = file -> Typeface.SERIF;
        Fonts.Prepared abandoned = EmojiFonts.prepare(context, new ByteArrayInputStream(BITMAPS));
        assertNotNull(abandoned);
        assertTrue(EmojiFonts.prepareInstall(context, abandoned));
        assertTrue(abandoned.recordSetting(Settings.EMOJI_FONT, EmojiChoice.OPENMOJI));
        assertTrue(abandoned.install());

        // The process ends there. The next one reads the file kept before.
        Fonts.resetForTests();
        EmojiFonts.resetForTests();
        byte[][] read = {null};
        Fonts.loader = file -> {
            try {
                read[0] = Files.readAllBytes(file.toPath());
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
            return Typeface.SERIF;
        };
        assertNotNull(EmojiFonts.active());
        assertArrayEquals(COLOR, read[0]);
        assertEquals(EmojiChoice.TWEMOJI, Settings.EMOJI_FONT.savedValue());
        File kept = EmojiFontsForTests.kept();
        File[] files = kept.getParentFile().listFiles();
        assertNotNull(files);
        for (File file : files) assertEquals("an abandoned import file survived recovery", kept, file);
    }

    private static boolean drawn(Span span, Canvas canvas) {
        return EmojiFonts.drawn(span, canvas, LINE, 2, 3, 10f, 0, 45, 60, paint());
    }

    private static Bitmap page() {
        return Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888);
    }

    private static Paint paint() {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(40);
        paint.setColor(Color.BLACK);
        return paint;
    }

    /** Whether any pixel from column [from] through [to] has been drawn on. */
    private static boolean inked(Bitmap page, int from, int to) {
        for (int x = Math.max(0, from); x < Math.min(page.getWidth(), to); x++) {
            for (int y = 0; y < page.getHeight(); y++) if (Color.alpha(page.getPixel(x, y)) != 0) return true;
        }
        return false;
    }

    private static EmojiFonts.Checked checked(byte[] bytes, String sha) throws Exception {
        return new EmojiFonts.Checked(new ByteArrayInputStream(bytes), COLOR.length, sha, "test");
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[5];
        int count;
        while ((count = input.read(buffer, 0, buffer.length)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    private static void keep(byte[] bytes) {
        File kept = EmojiFontsForTests.kept();
        if (!kept.getParentFile().mkdirs() && !kept.getParentFile().isDirectory()) throw new AssertionError("no folder");
        try {
            Files.write(kept.toPath(), bytes);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertNoFiles() {
        File[] files = EmojiFontsForTests.kept().getParentFile().listFiles();
        if (files != null) assertEquals(Arrays.toString(files), 0, files.length);
    }

    /** EmojiCompat's span: [width] wide, or throwing when it's below 0. */
    private static final class Span extends ReplacementSpan {
        private final int width;

        Span(int width) {
            this.width = width;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            if (width < 0) throw new IllegalStateException("measured on a broken layout");
            return width;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            throw new AssertionError("the span drew its own");
        }
    }
}
