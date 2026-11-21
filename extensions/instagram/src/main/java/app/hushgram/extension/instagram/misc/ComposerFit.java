/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * Keeps a chat's message box the height it has at the phone's own text size while Text size makes
 * text bigger (#806, #729). The bigger line used to push the box taller, and with a tall font it
 * grew already at 110%.
 *
 * <p>The box's height is its padding plus one line. Once that comes to more than the box's height at
 * the phone's own size, the padding above and below the text gives way first, down to
 * {@link #FLOOR_DP} on each side. Only when that isn't enough is the box's text made smaller, to the
 * largest size that fits, which is still bigger than the phone's own. A message of several lines
 * grows the box as it always did.
 *
 * <p>The size of one line comes from the line the box has laid out, so it holds for whatever font
 * Instagram draws in. What Instagram set on the box is kept, and put back when nothing needs to
 * give; a value Instagram sets again is taken as its own.
 */
final class ComposerFit {
    static final String MESSAGE_BOX = "row_thread_composer_edittext";
    /** The least padding left above and below the text. */
    static final int FLOOR_DP = 4;
    /** Changes one frame may make before it's drawn as it is, so a box that won't settle can't stall drawing. */
    private static final int MOST_CHANGES = 3;

    /** The message box's id in tests, which have no Instagram resources. */
    @Nullable
    static volatile Integer idForTests;

    private static int id;
    private static WeakReference<TextView> known = new WeakReference<>(null);
    private static WeakReference<Activity> watched = new WeakReference<>(null);
    @Nullable
    private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    @Nullable
    private static ViewTreeObserver.OnPreDrawListener drawListener;
    private static boolean laidOut;
    private static int changes;

    /** What Instagram gave a message box, and what this class set on it since, -1 for nothing. */
    private static final class Fit {
        int top;
        int bottom;
        float size;
        int setTop = -1;
        int setBottom = -1;
        float setSize = -1f;
        /** One line's height for each pixel of text size, read at Instagram's size. */
        float perPixel;
    }

    private static final Map<TextView, Fit> FITS = new WeakHashMap<>();

    private ComposerFit() {
    }

    /** Watches [activity]'s window for a chat's message box while it's at the front. Never throws. */
    static void follow(Activity activity, float scale) {
        unfollow();
        if (scale <= 1f) return;
        try {
            watched = new WeakReference<>(activity);
            laidOut = true;
            ViewTreeObserver.OnGlobalLayoutListener layout = () -> laidOut = true;
            ViewTreeObserver.OnPreDrawListener draw = () -> check(activity, scale);
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            observer.addOnGlobalLayoutListener(layout);
            observer.addOnPreDrawListener(draw);
            layoutListener = layout;
            drawListener = draw;
        } catch (Throwable failure) {
            Logger.printException(() -> "Text size: could not watch for the message box", failure);
        }
    }

    /** Stops watching [activity]'s window, when it's the one watched. Never throws. */
    static void unfollow(Activity activity) {
        if (watched.get() == activity) unfollow();
    }

    /** Stops watching the window [follow] last watched. Never throws. */
    static void unfollow() {
        Activity activity = watched.get();
        ViewTreeObserver.OnGlobalLayoutListener layout = layoutListener;
        ViewTreeObserver.OnPreDrawListener draw = drawListener;
        watched = new WeakReference<>(null);
        layoutListener = null;
        drawListener = null;
        changes = 0;
        if (activity == null) return;
        try {
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (!observer.isAlive()) return;
            if (layout != null) observer.removeOnGlobalLayoutListener(layout);
            if (draw != null) observer.removeOnPreDrawListener(draw);
        } catch (Throwable failure) {
            Logger.printException(() -> "Text size: could not stop watching for the message box", failure);
        }
    }

    /**
     * Before a frame that followed a layout, fits the message box on screen. Answers whether the frame
     * may draw: not when the box changed, so it lays out again first.
     */
    static boolean check(Activity activity, float scale) {
        if (!laidOut) return true;
        laidOut = false;
        try {
            TextView box = box(activity);
            if (box == null || !fit(box, scale)) {
                changes = 0;
                return true;
            }
            if (++changes > MOST_CHANGES) {
                changes = 0;
                return true;
            }
            return false;
        } catch (Throwable failure) {
            changes = 0;
            Logger.printException(() -> "Text size: could not fit the message box", failure);
            return true;
        }
    }

    /** The chat's message box in [activity]'s window, or null when none shows. */
    @Nullable
    private static TextView box(Activity activity) {
        TextView had = known.get();
        if (had != null && had.isAttachedToWindow()) return had;
        int boxId = boxId(activity);
        if (boxId == 0) return null;
        View found = activity.getWindow().getDecorView().findViewById(boxId);
        if (!(found instanceof TextView)) return null;
        known = new WeakReference<>((TextView) found);
        return (TextView) found;
    }

    private static int boxId(Activity activity) {
        Integer forTests = idForTests;
        if (forTests != null) return forTests;
        if (id == 0) id = activity.getResources().getIdentifier(MESSAGE_BOX, "id", Utils.resourcePackage(activity));
        return id;
    }

    /**
     * Gives [box] the padding and text size that keep it its height at the phone's own size, with
     * text [scale] times that, or Instagram's own when those fit. Answers whether anything changed.
     * Does nothing while the box isn't laid out or holds more than one line.
     */
    static boolean fit(TextView box, float scale) {
        Layout layout = box.getLayout();
        if (layout == null || layout.getLineCount() != 1 || scale <= 0f) return false;
        Fit fit = FITS.get(box);
        if (fit == null) {
            fit = new Fit();
            FITS.put(box, fit);
        }
        if (box.getPaddingTop() != fit.setTop || box.getPaddingBottom() != fit.setBottom) {
            fit.top = box.getPaddingTop();
            fit.bottom = box.getPaddingBottom();
            fit.setTop = -1;
            fit.setBottom = -1;
        }
        if (box.getTextSize() != fit.setSize) {
            fit.size = box.getTextSize();
            fit.setSize = -1f;
        }
        float now = box.getTextSize();
        if (now <= 0f) return false;
        // One line's height for each pixel of text size, from the line the box laid out at Instagram's size.
        // Read again from a smaller size, its rounding could move the fitted size a pixel each layout.
        if (fit.setSize < 0f || fit.perPixel <= 0f) fit.perPixel = (layout.getLineBottom(0) - layout.getLineTop(0)) / now;
        float perPixel = fit.perPixel;
        if (perPixel <= 0f) return false;
        int drawables = box.getCompoundPaddingTop() - box.getPaddingTop()
                + box.getCompoundPaddingBottom() - box.getPaddingBottom();
        int least = Math.max(box.getMinimumHeight(), Math.max(0, box.getMinHeight()));
        int around = fit.top + fit.bottom + drawables;
        int own = Math.max(least, around + Math.round(perPixel * fit.size / scale));
        int grown = around + Math.round(perPixel * fit.size);
        int top = fit.top;
        int bottom = fit.bottom;
        float size = fit.size;
        if (grown > own) {
            float density = box.getResources().getDisplayMetrics().density;
            int floor = Math.round(FLOOR_DP * density);
            int floorTop = Math.min(fit.top, floor);
            int floorBottom = Math.min(fit.bottom, floor);
            int padding = own - drawables - Math.round(perPixel * fit.size);
            if (padding < floorTop + floorBottom) {
                padding = floorTop + floorBottom;
                // A pixel spare: a line's own rounding can come out a pixel over the estimate.
                float fits = (own - drawables - padding - 1) / perPixel;
                size = Math.max(fit.size / scale, Math.min(fit.size, (float) Math.floor(fits)));
            }
            int both = fit.top + fit.bottom;
            top = both == 0 ? padding / 2 : Math.round(padding * (float) fit.top / both);
            top = Math.max(floorTop, Math.min(top, padding - floorBottom));
            bottom = padding - top;
        }
        boolean changed = false;
        if (box.getPaddingTop() != top || box.getPaddingBottom() != bottom) {
            box.setPadding(box.getPaddingLeft(), top, box.getPaddingRight(), bottom);
            changed = true;
        }
        if (box.getTextSize() != size) {
            box.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            changed = true;
        }
        fit.setTop = top == fit.top && bottom == fit.bottom ? -1 : top;
        fit.setBottom = fit.setTop == -1 ? -1 : bottom;
        fit.setSize = size == fit.size ? -1f : box.getTextSize();
        if (changed) {
            final int keptHeight = own;
            final float drawn = box.getTextSize();
            final int keptTop = top;
            final int keptBottom = bottom;
            Logger.printDebug(() -> "Text size: message box kept at " + keptHeight + " px, padding "
                    + keptTop + "/" + keptBottom + ", text " + drawn + " px, least height " + least);
        }
        return changed;
    }

    /** Forgets every box and window. For tests. */
    static void resetForTests() {
        unfollow();
        FITS.clear();
        known = new WeakReference<>(null);
        id = 0;
        laidOut = false;
        idForTests = null;
    }
}
