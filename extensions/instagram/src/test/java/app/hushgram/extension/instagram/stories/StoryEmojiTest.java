/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.SpannedString;
import android.text.Spanned;
import android.widget.EditText;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.misc.FontsForTests;
import app.hushgram.extension.instagram.misc.Fonts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the story emoji hook does to the editor's text: with the switch on and a file kept, each
 * emoji (with its selectors, skin tone, joins, keycap or flag pair) gets the picked font and the
 * letters around it don't; off, the spans come back off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryEmojiTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final byte[] TRUE_TYPE = {0x00, 0x01, 0x00, 0x00, 0x00, 0x0c, 0x00, (byte) 0x80};

    private static final String SMILEY = "\uD83D\uDE00";
    private static final String THUMBS_UP = "\uD83D\uDC4D";
    private static final String HEART = "\u2764";
    private static final String SELECTOR = "\uFE0F";
    private static final String JOINER = "\u200D";
    private static final String FLAG = "\uD83C\uDDEE\uD83C\uDDE9";

    private Context context;

    @Before
    public void clean() {
        context = RuntimeEnvironment.getApplication();
        reset();
        FontsForTests.loadEveryFile(Typeface.MONOSPACE);
    }

    @After
    public void restore() {
        reset();
        FontsForTests.reset();
    }

    private void reset() {
        Settings.STORY_EMOJI.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        StoryEmoji.resetForTests();
        File kept = StoryEmoji.kept(RuntimeEnvironment.getApplication());
        File[] files = kept.getParentFile().listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
    }

    private void keep() {
        assertTrue(StoryEmoji.store(context, new ByteArrayInputStream(TRUE_TYPE)));
    }

    private static String report() {
        StringBuilder all = new StringBuilder();
        for (String line : HookStatus.report()) all.append(line).append('\n');
        return all.toString();
    }

    /** Where each picked-font span sits in [text], as start and end pairs. */
    private static List<Integer> spans(Spanned text) {
        List<Integer> at = new ArrayList<>();
        StoryEmoji.EmojiFont[] found = text.getSpans(0, text.length(), StoryEmoji.EmojiFont.class);
        Arrays.sort(found, (a, b) -> text.getSpanStart(a) - text.getSpanStart(b));
        for (StoryEmoji.EmojiFont span : found) {
            at.add(text.getSpanStart(span));
            at.add(text.getSpanEnd(span));
        }
        return at;
    }

    private static int end(String text, int start) {
        return StoryEmoji.emojiEnd(text, start);
    }

    /** One emoji takes in everything that makes it one picture, and nothing else starts one. */
    @Test
    public void eachEmojiIsOnePiece() {
        assertEquals("a smiley", 3, end("a" + SMILEY + "b", 1));
        assertEquals("a letter", 0, end("a" + SMILEY + "b", 0));
        assertEquals("a thumbs up with a skin tone", 4, end(THUMBS_UP + "\uD83C\uDFFDx", 0));
        String family = "\uD83D\uDC68" + JOINER + "\uD83D\uDC69" + JOINER + "\uD83D\uDC67";
        assertEquals("a joined family", family.length(), end(family + "!", 0));
        assertEquals("a woman shrugging", 5, end("\uD83E\uDD37" + JOINER + "\u2640" + SELECTOR + ".", 0));
        assertEquals("a keycap", 3, end("1" + SELECTOR + "\u20E3", 0));
        assertEquals("a keycap without the selector", 2, end("#\u20E3", 0));
        assertEquals("a digit alone", 0, end("12", 0));
        assertEquals("a heart asked for as emoji", 2, end(HEART + SELECTOR, 0));
        assertEquals("a heart left as text", 0, end(HEART, 0));
        assertEquals("a coffee, emoji with nothing after", 1, end("\u2615a", 0));
        assertEquals("a flag is its two letters", 4, end(FLAG + "\uD83C\uDDFA\uD83C\uDDF8", 0));
        assertEquals("a copyright sign left as text", 0, end("\u00A9 2026", 0));
        assertEquals("a joiner after a letter", 1, end("a" + JOINER, 1));
        assertEquals("a selector alone", 0, end(SELECTOR, 0));
    }

    /** Off to start: nothing is laid even with a file kept. */
    @Test
    public void offToStartLaysNothing() {
        keep();
        SpannableStringBuilder text = new SpannableStringBuilder("hi " + SMILEY);
        StoryEmoji.lay(text);
        assertEquals(new ArrayList<Integer>(), spans(text));
    }

    /** On with a file kept, each emoji gets the picked font and the letters keep the format's. */
    @Test
    public void onLaysThePickedFontOverEachEmoji() {
        keep();
        Settings.STORY_EMOJI.save(true);
        SpannableStringBuilder text = new SpannableStringBuilder("hi " + SMILEY + " " + HEART + SELECTOR + FLAG + " ok");
        StoryEmoji.lay(text);
        assertEquals(Arrays.asList(3, 5, 6, 8, 8, 12), spans(text));
        StoryEmoji.EmojiFont first = text.getSpans(3, 5, StoryEmoji.EmojiFont.class)[0];
        assertSame(Typeface.MONOSPACE, first.font);
        assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, text.getSpanFlags(first));

        StoryEmoji.lay(text);
        assertEquals("laying again doesn't stack them", Arrays.asList(3, 5, 6, 8, 8, 12), spans(text));
        assertTrue(report(), report().contains(StoryEmoji.DRAWN));
    }

    /** On without a file kept, nothing is laid. */
    @Test
    public void onWithoutAFileLaysNothing() {
        Settings.STORY_EMOJI.save(true);
        SpannableStringBuilder text = new SpannableStringBuilder(SMILEY);
        StoryEmoji.lay(text);
        assertEquals(new ArrayList<Integer>(), spans(text));
    }

    /** The editor's apply lays them, the text box's next emoji gets one, and off takes them back off. */
    @Test
    public void theTextBoxKeepsUpAndOffTakesThemOff() {
        keep();
        Settings.STORY_EMOJI.save(true);
        EditText edit = new EditText(context);
        edit.setText("a" + SMILEY);
        StoryEmoji.applied(edit);
        assertEquals(Arrays.asList(1, 3), spans(edit.getText()));

        edit.getText().append(THUMBS_UP);
        assertEquals("a typed emoji gets one too", Arrays.asList(1, 3, 3, 5), spans(edit.getText()));

        StoryEmoji.applied(edit);
        edit.getText().append("b");
        assertEquals("applying again lays them where they were", Arrays.asList(1, 3, 3, 5), spans(edit.getText()));

        Settings.STORY_EMOJI.save(false);
        edit.getText().append("c");
        assertEquals("off, the next change takes them off", new ArrayList<Integer>(), spans(edit.getText()));
        assertTrue(report(), report().contains(FamilyNames.STORY_FONTS + ": invoked 2"));
    }

    /**
     * A sticker's layout gets them on the text it's handed, or on a copy of text that takes no
     * spans, and nothing while off.
     */
    @Test
    public void theStickersLayoutGetsThemToo() {
        keep();
        SpannableStringBuilder off = new SpannableStringBuilder("a" + SMILEY);
        assertSame(off, StoryEmoji.drawing(off));
        assertEquals(new ArrayList<Integer>(), spans(off));
        String plain = "a" + SMILEY;
        assertSame("off, plain text comes back as it was", plain, StoryEmoji.drawing(plain));

        Settings.STORY_EMOJI.save(true);
        SpannableStringBuilder line = new SpannableStringBuilder("a" + SMILEY + HEART + SELECTOR);
        assertSame("the line it's handed is the one laid out", line, StoryEmoji.drawing(line));
        assertEquals(Arrays.asList(1, 3, 3, 5), spans(line));

        CharSequence copied = StoryEmoji.drawing(new SpannedString(plain));
        assertTrue("text that takes no spans comes back as a copy", copied instanceof Spanned);
        assertEquals(plain, copied.toString());
        assertEquals(Arrays.asList(1, 3), spans((Spanned) copied));
        assertSame("no emoji, no copy", "ok", StoryEmoji.drawing("ok"));
        assertEquals(null, StoryEmoji.drawing(null));

        Settings.STORY_EMOJI.save(false);
        assertSame(line, StoryEmoji.drawing(line));
        assertEquals("off, the next layout takes them off", new ArrayList<Integer>(), spans(line));
        assertTrue(report(), report().contains(FamilyNames.STORY_FONTS + ": invoked 7"));
    }

    /** Paused, Instagram's emoji stay. */
    @Test
    public void pausedLaysNothing() {
        keep();
        Settings.STORY_EMOJI.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        SpannableStringBuilder text = new SpannableStringBuilder(SMILEY);
        StoryEmoji.lay(text);
        assertEquals(new ArrayList<Integer>(), spans(text));
    }

    /** A file that isn't a font is turned down and the one kept before stays. */
    @Test
    public void aFileThatIsntAFontKeepsTheOldOne() throws IOException {
        keep();
        File kept = StoryEmoji.kept(context);
        byte[] before = Files.readAllBytes(kept.toPath());
        assertFalse(StoryEmoji.store(context, new ByteArrayInputStream("not a font".getBytes())));
        assertArrayEquals(before, Files.readAllBytes(kept.toPath()));
    }

    /** The first emoji hook after restart reads the last accepted bytes, without another import. */
    @Test public void theFirstEmojiReadRecoversAnUnacceptedReplacementWhenAlreadyEnabled() throws IOException {
        keep();
        abandonEmoji();
        SpannableStringBuilder text = new SpannableStringBuilder(SMILEY);
        StoryEmoji.drawing(text);
        assertEquals(Arrays.asList(0, 2), spans(text));
        assertArrayEquals(TRUE_TYPE, Files.readAllBytes(StoryEmoji.kept(context).toPath()));
        assertTrue("recovery preserves the saved switch", Settings.STORY_EMOJI.savedValue());
    }

    @Test public void anInterruptedFirstEmojiFileUsesNativeEmojiOnTheFirstRead() {
        abandonEmoji();
        SpannableStringBuilder text = new SpannableStringBuilder(SMILEY);
        StoryEmoji.drawing(text);
        assertEquals(new ArrayList<Integer>(), spans(text));
        assertFalse(StoryEmoji.kept(context).exists());
    }

    private void abandonEmoji() {
        Settings.STORY_EMOJI.save(true);
        Fonts.Prepared pending = StoryEmoji.prepare(context,
                new ByteArrayInputStream(new byte[]{'O', 'T', 'T', 'O', 1}));
        assertNotNull(pending);
        assertTrue(StoryEmoji.prepareInstall(context, pending));
        assertTrue(pending.recordSetting(Settings.STORY_EMOJI, true));
        assertTrue(pending.install());
        FontsForTests.reset();
        FontsForTests.loadEveryFile(Typeface.MONOSPACE);
        StoryEmoji.resetForTests();
    }

    /** The picked font draws each emoji without its selector, so the phone's color font doesn't take it back. */
    @Test
    public void theSelectorIsLeftOutOfWhatsDrawn() {
        assertEquals(HEART, StoryEmoji.drawn("a" + HEART + SELECTOR + "b", 1, 3));
        String shrug = "\uD83E\uDD37" + JOINER + "\u2640";
        assertEquals(shrug, StoryEmoji.drawn(shrug + SELECTOR, 0, 5));
        assertEquals(SMILEY, StoryEmoji.drawn(SMILEY, 0, 2));
    }

    /** The span measures in the picked font but keeps the line as tall as the text around it. */
    @Test
    public void theSpanKeepsTheLineHeight() {
        StoryEmoji.EmojiFont span = new StoryEmoji.EmojiFont(Typeface.MONOSPACE);
        Paint paint = new Paint();
        paint.setTextSize(40);
        paint.setTypeface(Typeface.SERIF);
        Paint.FontMetricsInt expected = paint.getFontMetricsInt();
        Paint.FontMetricsInt measured = new Paint.FontMetricsInt();
        span.getSize(paint, SMILEY, 0, 2, measured);
        assertEquals(expected.ascent, measured.ascent);
        assertEquals(expected.descent, measured.descent);
        assertSame("the text's own paint keeps its font", Typeface.SERIF, paint.getTypeface());
    }
}
