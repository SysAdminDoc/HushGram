/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StoryEmoji;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the emoji half of the "Emoji style" patch: Instagram's emoji draw from an emoji pack
 * or an emoji font file chosen in HushGram settings instead of the phone's (#166).
 *
 * <p>Instagram hands its text to EmojiCompat, which swaps each emoji for a span that draws it from
 * the font EmojiCompat loaded. With a pack or file chosen, {@link EmojiStyle} has EmojiCompat swap
 * every emoji, and the patch hands each of those spans to {@link #drawn} where it would draw. That
 * draws the emoji's own characters in the chosen font, fitted to the room EmojiCompat measured, and
 * the span's own drawing is skipped. Instagram's emoji pictures (the row above the comment box,
 * reactions) take the chosen font through the Font patch's hooks in {@link Fonts}.
 *
 * <p>A pack is downloaded once from its maker's own release and kept only when its size and SHA-256
 * match the ones {@link EmojiChoice} names. A picked file is kept only when it has color emoji. The
 * font is read once per process, so a change shows fully after a restart. Every hook fails open:
 * with the phone's emoji chosen, HushGram paused, the settings not read yet, a file that won't load
 * (one toast says so) or anything thrown, Instagram's emoji draw as before.
 */
public final class EmojiFonts {
    /** The folder in Instagram's own files where the chosen emoji font is kept, and its name there. */
    static final String DIRECTORY = "hushgram-emoji";
    static final String FILE = "emoji";

    /** What's counted for each emoji drawn in the chosen font. */
    static final String DRAWN = "emoji drawn in the chosen pack or file";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** Opens a pack's download, checked to be exactly the pack's file. Swapped in tests. */
    interface Opener {
        InputStream open(EmojiChoice choice) throws Exception;
    }

    static final Opener MAKERS = choice -> new Checked(connect(choice.url), choice.bytes, choice.sha256, choice.name());
    static volatile Opener opener = MAKERS;

    private static final Object UNREAD = new Object();
    /** The kept font as read for this process, null when it didn't load, or UNREAD before. */
    private static volatile Object read = UNREAD;
    @Nullable
    private static volatile String mark;
    private static volatile boolean installedNow;

    /** The paint each draw fits its emoji with, one per thread. */
    private static final ThreadLocal<TextPaint> PEN = ThreadLocal.withInitial(TextPaint::new);
    private static final ThreadLocal<Paint.FontMetrics> LINE = ThreadLocal.withInitial(Paint.FontMetrics::new);
    private static final ThreadLocal<Paint.FontMetrics> GLYPH = ThreadLocal.withInitial(Paint.FontMetrics::new);

    private EmojiFonts() {
    }

    /**
     * Injected where EmojiCompat's span is about to draw its emoji from EmojiCompat's own font:
     * [span] is the span and the rest is what its draw was handed, with [paint] the paint it draws
     * with. Draws the emoji in the chosen font and answers true, so the span's own drawing is
     * skipped, or answers false to leave it. Never throws.
     */
    public static boolean drawn(Object span, Canvas canvas, CharSequence text, int start, int end, float x,
                                int top, int y, int bottom, Paint paint) {
        try {
            HookStatus.invoked(FamilyNames.EMOJI_STYLE);
            if (canvas == null || text == null || paint == null || !(span instanceof ReplacementSpan)) return false;
            if (start < 0 || end > text.length() || start >= end) return false;
            Typeface emoji = active();
            if (emoji == null) return false;
            int width = ((ReplacementSpan) span).getSize(paint, text, start, end, null);
            if (!draw(canvas, StoryEmoji.drawn(text, start, end), x, y, width, paint, emoji)) return false;
            HookStatus.counted(FamilyNames.EMOJI_STYLE, DRAWN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EMOJI_STYLE, "emoji span", failure);
            return false;
        }
    }

    /**
     * Draws [emoji] in [font] within [width] at the line [paint] draws on, as large as fits both
     * that width and the line's height, centered both ways. Answers false when there's nothing to draw.
     */
    static boolean draw(Canvas canvas, String emoji, float x, int y, int width, Paint paint, Typeface font) {
        if (emoji.isEmpty() || width <= 0) return false;
        TextPaint pen = PEN.get();
        pen.set(paint);
        pen.setTypeface(font);
        float advance = pen.measureText(emoji);
        if (!(advance > 0)) return false;
        Paint.FontMetrics line = LINE.get();
        paint.getFontMetrics(line);
        Paint.FontMetrics glyph = GLYPH.get();
        pen.getFontMetrics(glyph);
        float lineHeight = line.descent - line.ascent;
        float glyphHeight = glyph.descent - glyph.ascent;
        float scale = width / advance;
        if (lineHeight > 0 && glyphHeight > 0) scale = Math.min(scale, lineHeight / glyphHeight);
        pen.setTextSize(paint.getTextSize() * scale);
        pen.getFontMetrics(glyph);
        float left = x + (width - pen.measureText(emoji)) / 2;
        float baseline = y + (line.ascent + line.descent) / 2 - (glyph.ascent + glyph.descent) / 2;
        canvas.drawText(emoji, left, baseline, pen);
        return true;
    }

    /** The chosen emoji font, or null with the phone's emoji chosen, the settings not ready or no font that loads. */
    @Nullable
    static Typeface active() {
        if (!Utils.settingsReady() || Settings.EMOJI_FONT.get() == EmojiChoice.PHONE) return null;
        return typeface();
    }

    /** The mark the kept font was read with, for names Instagram keeps its emoji pictures by, or null. */
    @Nullable
    static String mark() {
        return active() == null ? null : mark;
    }

    /** The kept font, read once per process. A file that won't load answers null and says so once. */
    @Nullable
    private static Typeface typeface() {
        Object known = read;
        if (known != UNREAD) return (Typeface) known;
        synchronized (EmojiFonts.class) {
            if (read != UNREAD) return (Typeface) read;
            Typeface loaded = loadKept();
            if (loaded == null) Utils.showToastLong(L10n.t("Couldn't load your emoji, so the phone's show."));
            read = loaded;
            return loaded;
        }
    }

    @Nullable
    private static Typeface loadKept() {
        Context context = Utils.getContext();
        if (context == null) return null;
        File file = kept(context);
        if (!Fonts.readyForRead(file.getParentFile()) || !file.isFile() || !Fonts.drawsInColor(file)) return null;
        try {
            String seen = Long.toString(file.length(), 36) + "x" + Long.toString(file.lastModified(), 36);
            Typeface loaded = Fonts.load(file);
            if (loaded != null) mark = seen;
            return loaded;
        } catch (Exception failure) {
            Logger.printInfo(() -> "Emoji: the kept file didn't load", failure);
            return null;
        }
    }

    /** Where the chosen emoji font is kept. */
    static File kept(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), FILE);
    }

    /**
     * Stages a picked file the way {@link Fonts#prepare} does, if it's a font with color emoji.
     * Answers null otherwise. The caller still owns [input]. Off the main thread.
     */
    @Nullable
    public static Fonts.Prepared prepare(Context context, InputStream input) {
        Fonts.Prepared prepared = Fonts.prepare(input, kept(context).getParentFile());
        if (prepared != null && !Fonts.drawsInColor(prepared.staged())) {
            prepared.close();
            return null;
        }
        return prepared;
    }

    /**
     * Downloads [choice]'s pack and stages it, or answers null when it couldn't be downloaded or
     * isn't exactly the file the pack names. Off the main thread.
     */
    @Nullable
    public static Fonts.Prepared download(Context context, EmojiChoice choice) {
        if (!choice.pack()) return null;
        try (InputStream input = opener.open(choice)) {
            return prepare(context, input);
        } catch (Exception failure) {
            Logger.printInfo(() -> "Emoji: the " + choice + " pack didn't download", failure);
            return null;
        }
    }

    /** Prepares the rollback copy without changing the kept font. Off the main thread. */
    public static boolean prepareInstall(Context context, Fonts.Prepared prepared) {
        return prepared.prepareTarget(kept(context));
    }

    /** Whether [choice]'s font is the one kept now, so choosing it again downloads nothing. */
    public static boolean keeps(Context context, EmojiChoice choice) {
        return choice == Settings.EMOJI_FONT.savedValue() && kept(context).isFile();
    }

    /** A new file was kept: the change shows after a restart, even when the choice reads the same. */
    public static void installed() {
        installedNow = true;
    }

    public static boolean restartPending() {
        return installedNow && Settings.EMOJI_FONT.savedValue() != EmojiChoice.PHONE;
    }

    private static InputStream connect(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        return connection.getInputStream();
    }

    /**
     * Passes a pack's download through, failing the read past [bytes], and at the end unless the
     * size is [bytes] and the SHA-256 is [sha256]. Nothing reads the font until the end passes.
     */
    static final class Checked extends FilterInputStream {
        private final long bytes;
        private final String sha256;
        private final String name;
        private final MessageDigest digest;
        private long total;
        private boolean ended;

        Checked(InputStream input, long bytes, String sha256, String name) throws NoSuchAlgorithmException {
            super(input);
            this.bytes = bytes;
            this.sha256 = sha256;
            this.name = name;
            this.digest = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value < 0) ended();
            else took(new byte[]{(byte) value}, 0, 1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count < 0) ended();
            else took(buffer, offset, count);
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            throw new IOException("A pack is read whole");
        }

        private void took(byte[] buffer, int offset, int count) throws IOException {
            total += count;
            if (total > bytes) throw new IOException("Bigger than the " + name + " pack");
            digest.update(buffer, offset, count);
        }

        private void ended() throws IOException {
            if (ended) return;
            ended = true;
            if (total != bytes || !hex(digest.digest()).equals(sha256)) {
                throw new IOException("Not the " + name + " pack's file");
            }
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    /** Takes back what this process read, for a test. */
    static void resetForTests() {
        read = UNREAD;
        mark = null;
        installedNow = false;
        opener = MAKERS;
    }
}
