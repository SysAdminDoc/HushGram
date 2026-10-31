/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.widget.EditText;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.misc.Fonts;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the emoji half of the "Story text fonts" patch: emoji typed in story text draw from an
 * emoji font file picked in HushGram settings, in Stories only (#275).
 *
 * <p>The story text editor draws its text in the chosen format's font through one span over the
 * whole text, and emoji fall back from that font to the phone's. The patch hands the editor's text
 * box to {@link #applied} each time a format goes on, which lays an {@link EmojiFont} span over each
 * emoji and keeps doing so as the text changes. Each one measures and draws its emoji in the picked
 * file, the way EmojiCompat draws its own, and the rest of the text keeps the format's font. It's a
 * replacement span because the editor sizes its text by running every font span over the whole
 * text, and a plain font span would size the letters in the emoji font. The finished sticker is a
 * drawable that keeps its own copy of the text without these spans, whichever editor made it, so the
 * patch also hands {@link #drawing} the text each time that drawable lays it out, for the whole text
 * and for each line it measures. Instagram's own emoji pass there is a plain font span, which the
 * replacement span draws over, so the sticker and the shared story show the same emoji.
 *
 * <p>Turning the switch off takes the spans back off as soon as the text changes or another font is
 * picked, so Instagram's emoji return without a restart. Every hook fails open: with the switch
 * off, HushGram paused, the settings not read yet, no file kept or anything thrown, the text keeps
 * Instagram's emoji.
 */
public final class StoryEmoji {
    /** The folder in Instagram's own files where the picked emoji font is kept, and its name there. */
    static final String DIRECTORY = "hushgram-story-emoji";
    static final String FILE = "emoji";

    /** What's counted for each run of emoji drawn in the picked file. */
    static final String DRAWN = "story text emoji drawn in the picked file";

    /** Where the hook is counted when it throws. */
    static final String APPLY = "story emoji";

    /** The text boxes already watched, so each gets one watcher. Main thread only. */
    private static final Map<EditText, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());

    /** The kept file's Typeface and the size and time it was read at, or null before. */
    @Nullable
    private static volatile Typeface typeface;
    @Nullable
    private static volatile String typefaceMark;

    private StoryEmoji() {
    }

    /**
     * Measures and draws the emoji it covers in the picked font, at the size and color the text has
     * there, keeping the line's height. It leaves the emoji variation selector out: Android hands a
     * character followed by one to the phone's color emoji font unless the first font lists that
     * pair, and most emoji font files don't, so a heart typed with one stayed red.
     */
    static final class EmojiFont extends ReplacementSpan {
        final Typeface font;
        private final TextPaint work = new TextPaint();

        EmojiFont(Typeface font) {
            this.font = font;
        }

        private TextPaint styled(Paint paint) {
            work.set(paint);
            work.setTypeface(font);
            return work;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt metrics) {
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return (int) Math.ceil(styled(paint).measureText(drawn(text, start, end)));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            canvas.drawText(drawn(text, start, end), x, y, styled(paint));
        }
    }

    /** The part of [text] from [start] to [end] the picked font draws: all of it but the emoji selectors. */
    public static String drawn(CharSequence text, int start, int end) {
        StringBuilder kept = new StringBuilder(end - start);
        for (int at = start; at < end; at++) {
            char unit = text.charAt(at);
            if (unit != (char) 0xfe0f) kept.append(unit);
        }
        return kept.toString();
    }

    /** Lays the spans again after each change to a watched text box's text. */
    private static final class Watcher implements TextWatcher {
        private boolean busy;

        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable text) {
            if (busy) return;
            busy = true;
            try {
                lay(text);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.STORY_FONTS, APPLY, failure);
            } finally {
                busy = false;
            }
        }
    }

    /**
     * Injected first where the story text editor applies a format to [edit], its text box. Lays the
     * picked emoji font over each emoji, or takes it off with the switch off, and watches the
     * text box so the emoji typed next get it too. Never throws.
     */
    public static void applied(EditText edit) {
        try {
            HookStatus.invoked(FamilyNames.STORY_FONTS);
            if (edit == null) return;
            if (on() && WATCHED.put(edit, Boolean.TRUE) == null) edit.addTextChangedListener(new Watcher());
            Editable text = edit.getText();
            if (text != null) lay(text);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_FONTS, APPLY, failure);
        }
    }

    /**
     * Injected where a story text sticker builds the layout it draws or measures [text] with,
     * answering the text to lay out in its place. Text that takes spans gets the picked emoji font
     * laid over its emoji the way {@link #applied} does, or taken back off with the switch off, and
     * comes back as it was handed in. Other text comes back as a copy carrying them when it holds an
     * emoji and the switch is on, and as it was otherwise. Never throws.
     */
    public static CharSequence drawing(CharSequence text) {
        try {
            HookStatus.invoked(FamilyNames.STORY_FONTS);
            if (text == null) return null;
            if (text instanceof Spannable) {
                lay((Spannable) text);
                return text;
            }
            if (!on()) return text;
            SpannableString copy = new SpannableString(text);
            return lay(copy) ? copy : text;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.STORY_FONTS, APPLY, failure);
            return text;
        }
    }

    /**
     * Takes this class's spans off [text], then lays one over each emoji while it's on. Answers
     * whether it laid any.
     */
    static boolean lay(Spannable text) {
        for (EmojiFont old : text.getSpans(0, text.length(), EmojiFont.class)) text.removeSpan(old);
        Typeface font = on() ? font() : null;
        if (font == null) return false;
        boolean drawn = false;
        int at = 0;
        while (at < text.length()) {
            int end = emojiEnd(text, at);
            if (end > at) {
                text.setSpan(new EmojiFont(font), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                drawn = true;
                at = end;
            } else {
                at += Character.charCount(Character.codePointAt(text, at));
            }
        }
        if (drawn) HookStatus.counted(FamilyNames.STORY_FONTS, DRAWN);
        return drawn;
    }

    /**
     * Where the emoji starting at [start] in [text] ends, or [start] when none starts there. One
     * emoji takes in the variation selectors, skin tones, keycap marks, tags and joined emoji that
     * make it out of several code points, and a flag is its two letters.
     */
    static int emojiEnd(CharSequence text, int start) {
        int length = text.length();
        int point = Character.codePointAt(text, start);
        int at = start + Character.charCount(point);
        int after = at < length ? Character.codePointAt(text, at) : -1;
        if (regional(point)) {
            if (regional(after)) at += Character.charCount(after);
        } else if (keycap(point)) {
            boolean marked = after == 0x20e3 || (after == 0xfe0f && at + 1 < length && text.charAt(at + 1) == 0x20e3);
            if (!marked) return start;
        } else if (!pictured(point) && !(after == 0xfe0f && emojiWhenAsked(point))) {
            return start;
        }
        while (at < length) {
            int next = Character.codePointAt(text, at);
            if (next == 0xfe0f || next == 0x20e3 || (next >= 0x1f3fb && next <= 0x1f3ff) || (next >= 0xe0020 && next <= 0xe007f)) {
                at += Character.charCount(next);
            } else if (next == 0x200d && at + 1 < length) {
                int joined = Character.codePointAt(text, at + 1);
                if (!pictured(joined) && !emojiWhenAsked(joined)) break;
                at += 1 + Character.charCount(joined);
            } else {
                break;
            }
        }
        return at;
    }

    /** The letters a flag is made of, two at a time. */
    static boolean regional(int point) {
        return point >= 0x1f1e6 && point <= 0x1f1ff;
    }

    /** Code points drawn as emoji with nothing after them: the emoji blocks and the BMP ones shown in color. */
    static boolean pictured(int point) {
        if (point >= 0x1f000 && point <= 0x1faff) return point < 0x1f100 || point > 0x1f1e5 || isEnclosedEmoji(point);
        switch (point) {
            case 0x231a: case 0x231b: case 0x23e9: case 0x23ea: case 0x23eb: case 0x23ec: case 0x23f0: case 0x23f3:
            case 0x25fd: case 0x25fe: case 0x2614: case 0x2615: case 0x267f: case 0x2693: case 0x26a1: case 0x26aa:
            case 0x26ab: case 0x26bd: case 0x26be: case 0x26c4: case 0x26c5: case 0x26ce: case 0x26d4: case 0x26ea:
            case 0x26f2: case 0x26f3: case 0x26f5: case 0x26fa: case 0x26fd: case 0x2705: case 0x270a: case 0x270b:
            case 0x2728: case 0x274c: case 0x274e: case 0x2753: case 0x2754: case 0x2755: case 0x2757: case 0x2795:
            case 0x2796: case 0x2797: case 0x27b0: case 0x27bf: case 0x2b1b: case 0x2b1c: case 0x2b50: case 0x2b55:
                return true;
            default:
                return point >= 0x2648 && point <= 0x2653;
        }
    }

    /** The emoji among the enclosed letters before the flags: the blood types, squared words and the like. */
    private static boolean isEnclosedEmoji(int point) {
        return point == 0x1f170 || point == 0x1f171 || point == 0x1f17e || point == 0x1f17f || point == 0x1f18e
                || (point >= 0x1f191 && point <= 0x1f19a);
    }

    /** Symbols that draw as emoji when a variation selector asks for it, such as a heart typed with one. */
    static boolean emojiWhenAsked(int point) {
        return point == 0x00a9 || point == 0x00ae || point == 0x203c || point == 0x2049 || point == 0x2122
                || point == 0x2139 || (point >= 0x2194 && point <= 0x21aa) || (point >= 0x2300 && point <= 0x23ff)
                || (point >= 0x24c2 && point <= 0x27bf) || (point >= 0x2934 && point <= 0x2b55)
                || point == 0x3030 || point == 0x303d || point == 0x3297 || point == 0x3299;
    }

    /** The digits, # and * that start a keycap emoji. */
    static boolean keycap(int point) {
        return (point >= '0' && point <= '9') || point == '#' || point == '*';
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.STORY_EMOJI.get();
    }

    /** The kept file as a Typeface, read again when it's replaced, or null without one that loads. */
    @Nullable
    static Typeface font() {
        Context context = Utils.getContext();
        if (context == null) return null;
        File file = kept(context);
        if (!Fonts.readyForRead(file.getParentFile()) || !file.isFile()) return null;
        String mark = file.length() + "x" + file.lastModified();
        Typeface known = typeface;
        if (known != null && mark.equals(typefaceMark)) return known;
        try {
            Typeface loaded = Fonts.load(file);
            if (loaded == null) return null;
            typeface = loaded;
            typefaceMark = mark;
            return loaded;
        } catch (Exception failure) {
            Logger.printInfo(() -> "Story emoji: the kept file didn't load", failure);
            return null;
        }
    }

    /** Where the picked emoji font is kept. */
    static File kept(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), FILE);
    }

    /**
     * Copies a picked file into Instagram's storage in place of the one kept before, if it's a font
     * that loads. Answers false, keeping what was there, otherwise. Off the main thread.
     */
    public static boolean store(Context context, InputStream input) {
        return Fonts.keep(input, kept(context));
    }

    /** A private stage that does not replace the currently selected emoji file. */
    @Nullable
    public static Fonts.Prepared prepare(Context context, InputStream input) {
        return Fonts.prepare(input, kept(context).getParentFile());
    }

    public static boolean prepareInstall(Context context, Fonts.Prepared prepared) {
        return prepared.prepareTarget(kept(context));
    }

    /** Takes back what this process read and watched, for a test. */
    static void resetForTests() {
        WATCHED.clear();
        typeface = null;
        typefaceMark = null;
    }
}
