/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.media.DataSaver;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Ask for larger photos" switch of the "Full resolution photos" patch.
 *
 * <p>Instagram's server lists the sizes of a photo it sends for the screen Instagram reports, and
 * Instagram then picks the size nearest the width it shows the photo at. On a 1080-wide phone both
 * stop at 1080, so Full resolution photos has nothing larger to pick. With this switch on, the
 * screen Instagram reports in its user agent ({@link #screen}) is scaled up so its shorter side is
 * {@link #WIDTH} pixels, and a photo shown across the whole screen is asked for at that width
 * ({@link #wanted}). A phone that's already that wide is left alone, and so are thumbnails.
 *
 * <p>Full size covers reports a screen {@link #COVER_WIDTH} pixels on its shorter side instead, for
 * the reel covers and grid pictures the server sends at a third of the reported width (#214). It
 * changes only the reported screen, not the asked width.
 *
 * <p>Instagram builds its user agent once per run, so the screen it reports changes after a
 * restart. It also keeps the server's media replies in its cache folder across restarts, sized for
 * the screen it reported when it got them, so a reel opened before the switch came on kept its old
 * cover sizes until the cache was cleared (#214). {@link #atStart} drops those replies at the first
 * start that reports another screen. The hooks fail open: with the switch off, HushGram paused, the
 * settings not read yet or anything thrown, Instagram reports and asks for what it would have.
 */
public final class LargerPhotos {
    /** The shorter side of the screen reported and the width asked for, in pixels. */
    static final int WIDTH = 1440;

    /**
     * The shorter side reported under Full size covers. The server sizes a reel cover or grid
     * picture to a third of the reported width (#214: 240 on a 720 screen, 480 on 1440). A picked
     * cover can be wider than 1080 (piko saved De1JGCNTkzo's at 1290), and Instagram keeps nothing
     * wider than 1440, so three times that gets every cover whole.
     */
    static final int COVER_WIDTH = 4320;

    /** The step a failure in {@link #wanted} is reported under. */
    static final String ASK = "asked width";

    /** The step a failure in {@link #screen} is reported under. */
    static final String SCREEN = "reported screen";

    /** The step a failure in {@link #atStart} is reported under. */
    static final String START = "kept replies";

    /** What {@link #wanted} counts each time it asks for a larger size. */
    static final String ASKED = "asked for a larger size";

    /** What {@link #atStart} counts each start that drops the kept replies for a new screen. */
    static final String FORGOT = "kept replies dropped for a new screen";

    /**
     * The folder in Instagram's cache folder that keeps the server's media replies, sized for the
     * screen reported when they came. Only this one: the cache folder also holds Instagram's queues
     * of likes, comments, follows and saves still to be sent (pending_likes and the rest), which
     * must never go. Seen on the S25 (#214, 2026-10-31): with it kept, a 720 report still got
     * De1JGCNTkzo's 1080 cover from an earlier 4320 run, and with it gone, the 240 one.
     */
    static final List<String> SIZED_REPLIES = Collections.unmodifiableList(Arrays.asList("http_responses"));

    /** HushGram's note in the cache folder: the shorter side the kept replies were sized for. */
    static final String SIZED_FOR = "hushgram_replies_sized_for";

    /** The screen's shorter side, once read. */
    private static volatile int shorter;

    private static volatile boolean loggedScreen;
    private static volatile boolean loggedAsk;

    private LargerPhotos() {
    }

    /**
     * Injected at the start of Instagram's size picker, with the width it's asked to pick for.
     * Answers {@link #WIDTH} for a photo shown across a screen narrower than that, while the switch
     * is on, and the width otherwise. Data saver answers first: while it's saving, a photo shown
     * across the screen is asked for at its smaller width instead. Never throws.
     */
    public static int wanted(int width) {
        int shorter = shorterSide();
        int saved = DataSaver.photoWidth(width, shorter);
        if (saved != width) return saved;
        return wanted(width, shorter, LargerPhotos::switchedOn);
    }

    static int wanted(int width, int shorter, BooleanSupplier on) {
        // Thumbnails and anything already that wide pass by without a settings read.
        if (width >= WIDTH || shorter <= 0 || shorter >= WIDTH || width * 10L < shorter * 9L) return width;
        try {
            HookStatus.invoked(FamilyNames.FULL_RESOLUTION);
            if (!on.getAsBoolean()) return width;
            HookStatus.counted(FamilyNames.FULL_RESOLUTION, ASKED);
            if (!loggedAsk) {
                loggedAsk = true;
                Logger.printDebug(() -> "Larger photos: asked for " + WIDTH + " in place of " + width);
            }
            return WIDTH;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_RESOLUTION, ASK, failure);
            return width;
        }
    }

    /**
     * Injected where Instagram's user agent puts the screen in, with the density, width and height
     * it's about to write. While Full size covers is on and the shorter side is under
     * {@link #COVER_WIDTH}, or Ask for larger photos is on and it's under {@link #WIDTH}, scales the
     * width and height so the shorter side is that, keeping the screen's shape. Leaves the parts as
     * they were otherwise. Never throws.
     */
    public static void screen(Object[] parts) {
        screen(parts, LargerPhotos::switchedOn, LargerPhotos::coversOn);
    }

    static void screen(Object[] parts, BooleanSupplier on) {
        screen(parts, on, () -> false);
    }

    static void screen(Object[] parts, BooleanSupplier on, BooleanSupplier covers) {
        try {
            HookStatus.invoked(FamilyNames.FULL_RESOLUTION);
            if (parts == null || parts.length != 3 || !(parts[1] instanceof Integer) || !(parts[2] instanceof Integer)) {
                return;
            }
            int width = (Integer) parts[1];
            int height = (Integer) parts[2];
            int shorter = Math.min(width, height);
            int target = reported(shorter, on, covers);
            if (target == shorter) return;
            parts[1] = scaled(width, shorter, target);
            parts[2] = scaled(height, shorter, target);
            if (!loggedScreen) {
                loggedScreen = true;
                Logger.printDebug(() -> "Larger photos: reported the screen as " + parts[1] + "x" + parts[2]
                        + " in place of " + width + "x" + height);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_RESOLUTION, SCREEN, failure);
        }
    }

    /**
     * The shorter side reported for a screen whose own is [shorter]: {@link #COVER_WIDTH} under Full
     * size covers, {@link #WIDTH} under Ask for larger photos, and the screen's own when it's already
     * that wide or both are off. A switch is read only when its answer matters.
     */
    static int reported(int shorter, BooleanSupplier on, BooleanSupplier covers) {
        if (shorter <= 0 || shorter >= COVER_WIDTH) return shorter;
        if (covers.getAsBoolean()) return COVER_WIDTH;
        return shorter < WIDTH && on.getAsBoolean() ? WIDTH : shorter;
    }

    /**
     * Injected right after HushGram gets its context in Instagram's application, before Instagram's
     * own startup opens its cache. In Instagram's own process, drops the kept media replies when this
     * run reports another screen than the one they were sized for, so the next reel is asked for at
     * the new size. Never throws.
     */
    public static void atStart(Context context) {
        try {
            HookStatus.invoked(FamilyNames.FULL_RESOLUTION);
            if (context == null || !Utils.isMainProcess()) return;
            int natural = shorterSide();
            if (natural <= 0) return;
            forgetSizedReplies(context.getCacheDir(), natural, reported(natural, LargerPhotos::switchedOn, LargerPhotos::coversOn));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FULL_RESOLUTION, START, failure);
        }
    }

    /**
     * Deletes {@link #SIZED_REPLIES} under [cache] when its note says they were sized for another
     * width than [reported], or it can't be read, or there's none and [reported] isn't the screen's
     * own [natural] width, then writes the note. Answers whether it deleted them. A link is deleted
     * without what it points to.
     */
    static boolean forgetSizedReplies(File cache, int natural, int reported) throws IOException {
        if (cache == null) return false;
        File note = new File(cache, SIZED_FOR);
        int sizedFor = note.isFile() ? readNote(note) : natural;
        boolean forget = sizedFor != reported;
        if (forget) {
            for (String name : SIZED_REPLIES) delete(new File(cache, name));
            HookStatus.counted(FamilyNames.FULL_RESOLUTION, FORGOT);
            Logger.printDebug(() -> "Larger photos: dropped the kept media replies, sized for " + sizedFor
                    + " where this run reports " + reported);
        }
        if (forget || !note.isFile()) {
            Files.write(note.toPath(), Integer.toString(reported).getBytes(StandardCharsets.UTF_8));
        }
        return forget;
    }

    /** The width in the note, or -1 when it can't be read. */
    private static int readNote(File note) {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(note.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException unreadable) {
            return -1;
        }
    }

    /** Deletes [file] and everything under it, a link without what it points to. */
    private static void delete(File file) {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        if (file.exists() && !file.delete()) Logger.printDebug(() -> "Larger photos: couldn't delete " + file);
    }

    private static Integer scaled(int side, int shorter, int target) {
        return (int) ((long) side * target / shorter);
    }

    /** The screen's shorter side in pixels, read once, or 0 when it can't be read. */
    private static int shorterSide() {
        int known = shorter;
        if (known > 0) return known;
        try {
            DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
            known = Math.min(metrics.widthPixels, metrics.heightPixels);
        } catch (Throwable failure) {
            return 0;
        }
        shorter = known;
        return known;
    }

    /** Forgets what was logged. Tests only. */
    static void forgetForTests() {
        loggedScreen = false;
        loggedAsk = false;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.ASK_FOR_LARGER_PHOTOS.get();
    }

    private static boolean coversOn() {
        return Utils.settingsReady() && Settings.FULL_SIZE_COVERS.get();
    }
}
