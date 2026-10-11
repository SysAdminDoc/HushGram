/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.util.ArrayDeque;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Long press to zoom" patch.
 *
 * <p>Every photo and video in a feed of posts on Instagram 450 gets a touch helper of Instagram's,
 * made with the post and the frame its picture sits in. The patch hands each touch on that frame to
 * {@link #touch(MotionEvent)} before Instagram reads it, and hands the frame and the post to
 * {@link #press(View, Object)} when Instagram's own long press detector fires, which it only does
 * once a finger has stayed down for the long press time. With the switch on, a long press on a
 * photo opens the same picture the frame shows over a dimmed screen, {@link #ZOOM} times as big
 * around the finger. Sliding the finger moves the zoom across the photo, and lifting it, a cancel or
 * a second finger closes it, so a pinch still goes to Instagram's own zoom.
 *
 * <p>A yes from press means Instagram's own long press on that photo doesn't run, so the zoom takes
 * its place while the switch is on. A video, a press after the finger wandered further than a tap
 * may, a frame with no picture to show, the switch off, Pause, settings that aren't ready and
 * anything thrown all answer no and leave Instagram's long press as it was. Taps and double taps
 * never reach press, and nothing here takes a touch from Instagram.
 */
public final class LongPressZoom {
    /** How much bigger the photo shows than in the feed. */
    static final float ZOOM = 2f;

    /** The screen behind the zoomed photo: black at 90 percent. */
    static final int BACKDROP = 0xE6000000;

    /** How long the zoom takes to fade in, in milliseconds. */
    static final long FADE_MS = 120L;

    /** media_type of one photo and of a carousel. A video is 2. */
    static final int PHOTO = 1;
    static final int CAROUSEL = 8;

    /** What {@link HookStatus} counts. */
    static final String ZOOMED = "photos zoomed";
    static final String WANDERED = "long presses after the finger moved";
    static final String NOT_A_PHOTO = "long presses on a video";
    static final String NO_PICTURE = "long presses with no picture to show";

    /** The steps a failure is reported under. */
    static final String PRESS = "long press";
    static final String TOUCH = "touch on a photo";
    static final String DRAW = "zoomed photo";

    /** A picture this much of the frame or more is the photo. Anything smaller is an icon over it. */
    private static final float MOST_OF_THE_FRAME = 0.5f;

    /** How many views a frame is searched through before giving up on it. */
    private static final int MOST_VIEWS = 200;

    /** Whether the finger of the gesture under way went down with the switch on. Main thread only. */
    private static boolean down;

    /** Where that finger went down, on the screen. */
    private static float downX;
    private static float downY;

    /** The square of the furthest that finger has moved from where it went down. */
    private static float farthest;

    /** The zoom on screen, or null. */
    private static Zoom shown;

    private LongPressZoom() {
    }

    /**
     * Injected first in Instagram's feed media touch helper, with each touch on a photo or video
     * frame. Keeps where the finger went down and how far it moved, moves an open zoom with the
     * finger, and closes it when the finger lifts, the touch is cancelled or a second finger comes
     * down. Never consumes a touch and never throws.
     */
    public static void touch(MotionEvent event) {
        // Every touch on every feed photo passes here, so the switch off with nothing open costs one check.
        if (shown == null && !switchedOn()) return;
        try {
            follow(event);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, TOUCH, failure);
            close();
        }
    }

    static void follow(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                close();
                down = true;
                downX = event.getRawX();
                downY = event.getRawY();
                farthest = 0f;
                break;
            case MotionEvent.ACTION_MOVE:
                Zoom zoom = shown;
                if (zoom != null) {
                    zoom.aim(event.getRawX(), event.getRawY());
                } else if (down) {
                    float x = event.getRawX() - downX;
                    float y = event.getRawY() - downY;
                    farthest = Math.max(farthest, x * x + y * y);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Two fingers are a pinch, Instagram's own zoom, never a long press.
                farthest = Float.MAX_VALUE;
                close();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                close();
                break;
            default:
                break;
        }
    }

    /**
     * Injected in Instagram's feed media long press, right after it notes the time of the press,
     * with the frame the picture sits in and the post. Opens the zoom and answers 1, which skips
     * the rest of Instagram's long press, when the switch is on, the finger stayed within a tap's
     * distance of where it went down, the post is a photo or a carousel and the frame shows a
     * picture and no playing video. Answers 0 otherwise, and Instagram's long press goes on as it
     * would have. Never throws.
     */
    public static int press(View frame, Object media) {
        return press(frame, media, LongPressZoom::mediaType);
    }

    static int press(View frame, Object media, ToIntFunction<Object> typeOf) {
        try {
            HookStatus.invoked(FamilyNames.LONG_PRESS_ZOOM);
            if (frame == null || media == null || !down || !switchedOn()) return 0;
            float slop = ViewConfiguration.get(frame.getContext()).getScaledTouchSlop();
            if (farthest > slop * slop) {
                HookStatus.counted(FamilyNames.LONG_PRESS_ZOOM, WANDERED);
                return 0;
            }
            int type = typeOf.applyAsInt(media);
            if (type != PHOTO && type != CAROUSEL) {
                HookStatus.counted(FamilyNames.LONG_PRESS_ZOOM, NOT_A_PHOTO);
                return 0;
            }
            close();
            Zoom zoom = Zoom.open(frame, downX, downY);
            if (zoom == null) {
                HookStatus.counted(FamilyNames.LONG_PRESS_ZOOM, NO_PICTURE);
                return 0;
            }
            shown = zoom;
            // The feed doesn't scroll under a held zoom. Instagram lets go of this when the finger lifts.
            ViewParent parent = frame.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            HookStatus.counted(FamilyNames.LONG_PRESS_ZOOM, ZOOMED);
            Logger.printDebug(() -> "Long press zoom: zoomed a photo");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, PRESS, failure);
            close();
            return 0;
        }
    }

    /**
     * The media_type of a post: 1 for a photo, 2 for a video, 8 for a carousel, and 0 when it
     * doesn't say. The patch writes the body, which reads the post's media_type. 0 as built.
     */
    public static int mediaType(Object media) {
        return 0;
    }

    /** Whether a zoom is on screen. */
    static boolean isShown() {
        return shown != null;
    }

    /** The view drawing the zoom, or null. For tests. */
    static View shownView() {
        Zoom zoom = shown;
        return zoom == null ? null : zoom.view;
    }

    /** Closes the zoom, if one is open. */
    static void close() {
        Zoom zoom = shown;
        if (zoom == null) return;
        shown = null;
        zoom.remove();
    }

    /** Closes any zoom and forgets the gesture. For tests. */
    static void resetForTests() {
        close();
        down = false;
        farthest = 0f;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.LONG_PRESS_TO_ZOOM.get();
    }

    /** A zoom on screen: the view drawing it, added over the window the photo is in. */
    static final class Zoom {
        final ZoomView view;

        /** Where the window sits on the screen, to turn the finger's place on the screen into the window's. */
        private final int windowX;
        private final int windowY;

        private Zoom(ZoomView view, int windowX, int windowY) {
            this.view = view;
            this.windowX = windowX;
            this.windowY = windowY;
        }

        /**
         * Opens a zoom of the picture [frame] shows, centred on the finger at ([x], [y]) on the screen,
         * or answers null when the frame isn't on screen, shows a playing video or has no picture.
         */
        static Zoom open(View frame, float x, float y) {
            if (!frame.isAttachedToWindow() || !frame.isShown() || frame.getWidth() <= 0 || frame.getHeight() <= 0) return null;
            View root = frame.getRootView();
            if (!(root instanceof ViewGroup) || playing(frame, x, y)) return null;
            ImageView image = picture(frame, x, y);
            if (image == null) return null;
            ZoomView view = ZoomView.of(frame.getContext(), image);
            if (view == null) return null;
            int[] window = new int[2];
            root.getLocationOnScreen(window);
            Zoom zoom = new Zoom(view, window[0], window[1]);
            zoom.aim(x, y);
            view.setOnTouchListener((touched, event) -> {
                // Only a new touch lands here, and only if the one that opened the zoom was never seen to end.
                touched.performClick();
                close();
                return true;
            });
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View attached) {
                }

                @Override public void onViewDetachedFromWindow(View detached) {
                    if (shown == zoom) shown = null;
                }
            });
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.setTranslationZ(1000f);
            ((ViewGroup) root).addView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(FADE_MS);
            return zoom;
        }

        /** Zooms around the finger at ([x], [y]) on the screen. */
        void aim(float x, float y) {
            view.aim(x - windowX, y - windowY);
        }

        void remove() {
            view.animate().cancel();
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
        }

        /** Whether [frame] holds a video surface under the finger at ([x], [y]) on the screen: a video playing there, not a photo. */
        private static boolean playing(View frame, float x, float y) {
            ArrayDeque<View> pending = new ArrayDeque<>();
            pending.add(frame);
            int seen = 0;
            while (!pending.isEmpty() && seen++ < MOST_VIEWS) {
                View next = pending.removeFirst();
                if ((next instanceof TextureView || next instanceof SurfaceView) && next.isShown()
                        && next.getWidth() > 0 && next.getHeight() > 0 && under(next, x, y)) {
                    return true;
                }
                addChildren(next, pending);
            }
            return false;
        }

        /**
         * The image view on screen in [frame] covering most of it: the biggest one under the finger at
         * ([x], [y]) on the screen, so a carousel gives the page being pressed and not the one beside
         * it, or failing that the biggest one. Null when there is none.
         */
        private static ImageView picture(View frame, float x, float y) {
            long least = (long) (MOST_OF_THE_FRAME * frame.getWidth() * frame.getHeight());
            ImageView best = null;
            ImageView pressed = null;
            long bestArea = 0;
            long pressedArea = 0;
            ArrayDeque<View> pending = new ArrayDeque<>();
            pending.add(frame);
            int seen = 0;
            while (!pending.isEmpty() && seen++ < MOST_VIEWS) {
                View next = pending.removeFirst();
                if (next instanceof ImageView && next.isShown()) {
                    long area = (long) next.getWidth() * next.getHeight();
                    if (area >= least && area > bestArea) {
                        best = (ImageView) next;
                        bestArea = area;
                    }
                    if (area >= least && area > pressedArea && under(next, x, y)) {
                        pressed = (ImageView) next;
                        pressedArea = area;
                    }
                }
                addChildren(next, pending);
            }
            return pressed != null ? pressed : best;
        }

        /** Whether ([x], [y]) on the screen falls inside [view]. */
        private static boolean under(View view, float x, float y) {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            return x >= at[0] && x < at[0] + view.getWidth() && y >= at[1] && y < at[1] + view.getHeight();
        }

        private static void addChildren(View view, ArrayDeque<View> pending) {
            if (!(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
        }
    }

    /**
     * Draws the dimmed screen and the photo over it, mapped from the picture to where it shows in the
     * feed and then scaled by {@link #ZOOM} around the finger. Window coordinates throughout.
     */
    static final class ZoomView extends View {
        private final Drawable picture;
        private final Bitmap bitmap;
        private final Matrix placed = new Matrix();
        private final Matrix drawn = new Matrix();
        private final int[] spot = new int[2];

        private ZoomView(Context context, Drawable picture, Bitmap bitmap, RectF from, RectF to) {
            super(context);
            this.picture = picture;
            this.bitmap = bitmap;
            placed.setRectToRect(from, to, Matrix.ScaleToFit.FILL);
            drawn.set(placed);
        }

        /**
         * A view drawing [image]'s picture: a copy of its drawable that shares the bitmap, so nothing
         * is fetched, or failing that a snapshot of the view itself. Null when there is neither.
         */
        static ZoomView of(Context context, ImageView image) {
            int[] at = new int[2];
            image.getLocationInWindow(at);
            Resources resources = image.getResources();
            Drawable original = image.getDrawable();
            if (original != null && !original.getBounds().isEmpty()) {
                Bitmap bitmap = original instanceof BitmapDrawable ? ((BitmapDrawable) original).getBitmap() : null;
                Drawable copy = copyOf(original, bitmap, resources);
                int width = copy == null ? -1 : copy.getIntrinsicWidth();
                int height = copy == null ? -1 : copy.getIntrinsicHeight();
                if (width > 0 && height > 0) {
                    copy.setBounds(0, 0, width, height);
                    RectF shownAt = new RectF(original.getBounds());
                    image.getImageMatrix().mapRect(shownAt);
                    shownAt.offset(at[0] + image.getPaddingLeft(), at[1] + image.getPaddingTop());
                    return new ZoomView(context, copy, bitmap, new RectF(0, 0, width, height), shownAt);
                }
            }
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return null;
            Bitmap snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                image.draw(new Canvas(snapshot));
            } catch (IllegalStateException hardware) {
                // A hardware bitmap can't be drawn into a software canvas: there's no picture to copy.
                snapshot.recycle();
                return null;
            }
            BitmapDrawable copy = new BitmapDrawable(resources, snapshot);
            copy.setBounds(0, 0, width, height);
            return new ZoomView(context, copy, snapshot, new RectF(0, 0, width, height),
                    new RectF(at[0], at[1], at[0] + width, at[1] + height));
        }

        private static Drawable copyOf(Drawable original, Bitmap bitmap, Resources resources) {
            if (original instanceof BitmapDrawable) {
                return bitmap == null || bitmap.isRecycled() ? null : new BitmapDrawable(resources, bitmap);
            }
            Drawable.ConstantState state = original.getConstantState();
            return state == null ? null : state.newDrawable(resources).mutate();
        }

        /** Zooms around ([x], [y]) in the window. */
        void aim(float x, float y) {
            drawn.set(placed);
            drawn.postScale(ZOOM, ZOOM, x, y);
            invalidate();
        }

        /** Where a point of the picture lands in the window. For tests. */
        float[] mapped(float x, float y) {
            float[] point = {x, y};
            drawn.mapPoints(point);
            return point;
        }

        /** The picture drawn. For tests. */
        Drawable picture() {
            return picture;
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(BACKDROP);
            if (bitmap != null && bitmap.isRecycled()) {
                // Instagram let go of the photo under the zoom: nothing left to draw.
                post(LongPressZoom::close);
                return;
            }
            getLocationInWindow(spot);
            int saved = canvas.save();
            try {
                canvas.translate(-spot[0], -spot[1]);
                canvas.concat(drawn);
                picture.draw(canvas);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.LONG_PRESS_ZOOM, DRAW, failure);
                post(LongPressZoom::close);
            } finally {
                canvas.restoreToCount(saved);
            }
        }
    }
}
