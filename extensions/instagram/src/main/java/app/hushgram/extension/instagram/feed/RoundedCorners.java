/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AbsListView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Rounded post corners" patch.
 *
 * <p>Instagram's feed makes one touch holder for each media frame of a post: the frame a photo, a video
 * preview or one page of a carousel sits in. The patch hands each new holder to {@link #made}, which
 * reads the holder's frame and, while the choice is on, gives that frame a rounded outline and turns on
 * clipping to it. That is one outline provider and one flag on a view that was just made, and the
 * view system cuts the picture and the video (a TextureView is drawn inside its parent, so it is cut
 * too) at draw time, so scrolling does no work of its own. A frame already rounded to the chosen size
 * is not touched again. Stories, the profile grid, Explore and the Reels tab don't use these holders and
 * stay as Instagram draws them.
 *
 * <p>A reel in the feed has its name row and a shadow laid over the top of the video, beside the frame
 * rather than in it. Each time a rounded frame is laid out, the view around it that's exactly its size
 * and holds those too ({@link #cover}) gets the same outline, so the shadow doesn't show square over
 * the rounded top corners (#571).
 *
 * <p>The outline provider Instagram had and its clipping flag are kept for each view touched. With
 * the choice off or HushGram paused, the next holder made puts every kept frame back as it was and
 * forgets them, and until then nothing runs. Frames are held weakly. The hook fails open: anything
 * thrown leaves the frame as Instagram made it.
 */
public final class RoundedCorners {
    /** The step a failure is reported under. */
    static final String MADE = "post frame";

    /** What {@link #made} counts for each frame it rounds. */
    static final String ROUNDED = "frames rounded";

    /** What {@link #laidOut} counts for each view around a frame it rounds. */
    static final String COVERS_ROUNDED = "views over a frame rounded";

    /** How many parents of a frame are asked whether they're its size. */
    static final int COVER_DEPTH = 8;

    /** The frames rounded now, and the views around them, with what they were. Main thread only. */
    private static final RoundedFrames kept = new RoundedFrames();

    /** The frames listened to for layouts, weakly. Main thread only. */
    private static final Map<View, Boolean> listened = Collections.synchronizedMap(new WeakHashMap<>());
    private static final View.OnLayoutChangeListener LAID_OUT =
            (frame, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> laidOut(frame);

    /** Reads the frame a holder keeps. Replaced by tests. */
    static Function<Object, Object> frames = RoundedTargets::frame;

    private static volatile boolean logged;

    private RoundedCorners() {
    }

    /**
     * Called last thing in the constructor of a feed post's touch holder, with the new [holder]. Never throws.
     */
    public static void made(Object holder) {
        try {
            if (!Utils.settingsReady() || !Settings.ROUNDED_POST_CORNERS.get()) {
                if (!kept.isEmpty()) kept.putBack();
                return;
            }
            HookStatus.invoked(FamilyNames.ROUNDED_POST_CORNERS);
            Object frame = frames.apply(holder);
            if (!(frame instanceof View)) return;
            View view = (View) frame;
            if (listened.put(view, Boolean.TRUE) == null) view.addOnLayoutChangeListener(LAID_OUT);
            boolean rounded = kept.round(view, radius(view));
            laidOut(view);
            if (!rounded) return;
            HookStatus.counted(FamilyNames.ROUNDED_POST_CORNERS, ROUNDED);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Rounded post corners: rounded a post's frame");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, MADE, failure);
        }
    }

    private static float radius(View view) {
        return Settings.ROUNDED_CORNER_SIZE.get().dp * view.getResources().getDisplayMetrics().density;
    }

    /**
     * A rounded [frame] was laid out. Rounds the view around it that's exactly its size too, so what
     * Instagram lays over the picture there is cut with it. Never throws.
     */
    static void laidOut(View frame) {
        try {
            if (!Utils.settingsReady() || !Settings.ROUNDED_POST_CORNERS.get()) return;
            View cover = cover(frame);
            if (cover != null && kept.round(cover, radius(cover))) {
                HookStatus.counted(FamilyNames.ROUNDED_POST_CORNERS, COVERS_ROUNDED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ROUNDED_POST_CORNERS, "post cover", failure);
        }
    }

    /**
     * The outermost view around [frame] that is exactly the frame, each one at the top left of the
     * next, and holds more than the frame. On a reel in the feed Instagram lays the name row and the
     * shadow behind it over the video in that view, beside the frame, so rounding the frame alone
     * left them square over its top corners (#571). Null when nothing around it matches, and a list
     * is never one.
     */
    static View cover(View frame) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (width <= 0 || height <= 0) return null;
        View found = null;
        View at = frame;
        for (int depth = 0; depth < COVER_DEPTH; depth++) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof ViewGroup) || parent instanceof AbsListView) break;
            ViewGroup group = (ViewGroup) parent;
            if (group.getClass().getName().endsWith("RecyclerView")) break;
            if (at.getLeft() != 0 || at.getTop() != 0 || group.getWidth() != width || group.getHeight() != height) break;
            if (group.getChildCount() > 1) found = group;
            at = group;
        }
        return found;
    }

    /** How many frames, and views around them, are rounded now. Tests only. */
    static int roundedForTests() {
        return kept.size();
    }

    /** Forgets what was logged and kept. Tests only. */
    static void forgetForTests() {
        logged = false;
        kept.clear();
        listened.clear();
        frames = RoundedTargets::frame;
    }
}
