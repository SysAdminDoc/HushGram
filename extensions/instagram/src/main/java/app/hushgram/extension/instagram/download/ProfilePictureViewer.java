/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.ui.Dim;

/**
 * View profile picture's screen: the largest size of an account's picture Instagram has, full
 * screen on black, with pinch zoom, a drag to look around a zoomed picture and a double tap to zoom
 * in or back out. Save goes through the same save as Save profile picture. Back or Close shuts it.
 *
 * <p>The picture is fetched the way a save fetches it, by {@link Downloader} from Meta's media
 * servers only, into the saves' work folder, read into memory and deleted at once, so nothing is
 * left behind. Closing the screen stops a fetch that's still running. The diagnostic report counts
 * how wide the picture shown was, in pixels, never anything about the account.
 */
final class ProfilePictureViewer {
    private ProfilePictureViewer() {
    }

    /** The most a profile picture may hold. Instagram's largest is well under a megabyte. */
    static final long MAX_BYTES = 16L * 1024L * 1024L;

    /** The longest side drawn. A larger picture is read at a half, a quarter and so on. */
    static final int MAX_SIDE = 4096;

    /** How far past the size that fits the screen a pinch can zoom. */
    static final float MAX_ZOOM = 6f;

    /** What the diagnostic report counts when the picture couldn't be shown. Fixed text. */
    static final String NOT_OPENED = "picture not opened";

    /** The width of Instagram's largest profile pictures. */
    static final int FULL_WIDTH = 1080;

    /** Counted for a picture shown at [FULL_WIDTH] or wider. Fixed text, so the family's labels stay few. */
    static final String VIEWED_FULL = "viewed 1080 px or wider";

    /** Counted for a picture shown narrower than [FULL_WIDTH]. Fixed text. */
    static final String VIEWED_SMALLER = "viewed under 1080 px wide";

    /** The label counted for a picture shown [width] pixels wide: one of two, whatever the width. */
    static String viewed(int width) {
        return width >= FULL_WIDTH ? VIEWED_FULL : VIEWED_SMALLER;
    }

    /** A decoded picture and the width it has on the server, before any reduction to draw it. */
    static final class Picture {
        final Bitmap bitmap;
        final int width;

        Picture(Bitmap bitmap, int width) {
            this.bitmap = bitmap;
            this.width = width;
        }
    }

    /**
     * Opens the viewer for the largest of [sizes] over the screen [context] belongs to, and starts
     * fetching it. [owner] names a save from its Save button. Answers whether the viewer opened.
     * Never throws.
     */
    static boolean open(Context context, List<MediaSave.Rendition> sizes, String owner, ProfilePicture.Save save) {
        try {
            Activity activity = activityOf(context);
            if (activity == null) activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            MediaSave.Rendition chosen = largest(sizes);
            if (chosen == null) return false;
            Context application = activity.getApplicationContext();
            Screen screen = new Screen(activity, sizes, owner, save);
            screen.dialog.show();
            Watch watch = screen.watch;
            String url = chosen.url;
            boolean queued = Utils.runOnBackgroundThread(() -> {
                Picture picture = load(application, url, watch);
                Utils.runOnMainThread(() -> screen.shown(picture));
            });
            if (!queued) {
                screen.dialog.dismiss();
                return false;
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURE, "open profile picture", failure);
            return false;
        }
    }

    /** The largest of [sizes] by its stated pixels, the one a save would keep. Null when there's none. */
    @Nullable
    static MediaSave.Rendition largest(@Nullable List<MediaSave.Rendition> sizes) {
        if (sizes == null) return null;
        MediaSave.Rendition chosen = null;
        for (MediaSave.Rendition size : sizes) {
            if (size == null || size.url == null) continue;
            if (chosen == null || pixels(size) > pixels(chosen)) chosen = size;
        }
        return chosen;
    }

    private static long pixels(MediaSave.Rendition size) {
        return (long) size.width * size.height;
    }

    /**
     * Fetches [url] into a work file and reads it, at a power of two below its size when its
     * longer side is past {@link #MAX_SIDE}. Null when the address is refused, the fetch fails or
     * stops, or the answer isn't a picture. Blocking. Never throws. The work file is gone after.
     */
    @Nullable
    static Picture load(Context application, String url, Downloader.Progress progress) {
        File file = null;
        try {
            File folder = DashSave.workFolder(application);
            if (folder == null) return null;
            file = File.createTempFile("image", ".part", folder);
            Downloader.Result fetched = DashSave.fetchWork(url, Downloader.Kind.IMAGE, file,
                    MediaSave.policyFor(application), MAX_BYTES, progress);
            if (!fetched.ok()) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            PictureFetch.decodeFile(file, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE);
            Bitmap bitmap = PictureFetch.decodeFile(file, options);
            return bitmap == null ? null : new Picture(bitmap, bounds.outWidth);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURE, "load profile picture", failure);
            return null;
        } finally {
            DashSave.discard(file);
        }
    }

    /** The smallest power of two that brings a [width] by [height] picture's longer side to [most] or under. */
    static int sampleSize(int width, int height, int most) {
        int longer = Math.max(width, height);
        int sample = 1;
        while (most > 0 && longer / sample > most) sample *= 2;
        return sample;
    }

    /** Couldn't open the picture, in the phone's language. Never throws. */
    static void notOpened(Context context) {
        try {
            HookStatus.counted(FamilyNames.PROFILE_PICTURE, NOT_OPENED);
            Context application = context == null ? null : context.getApplicationContext();
            if (application != null) Feedback.show(application, L10n.t(application, "Couldn't open the picture"), false);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURE, "view feedback", t);
        }
    }

    /** The activity behind [context], or null when there's none. */
    @Nullable
    static Activity activityOf(@Nullable Context context) {
        Context at = context;
        for (int depth = 0; depth < 10 && at != null; depth++) {
            if (at instanceof Activity) return (Activity) at;
            if (!(at instanceof ContextWrapper)) return null;
            at = ((ContextWrapper) at).getBaseContext();
        }
        return null;
    }

    /** A fetch the screen can stop: closing it says so, and closes the connection being read. */
    static final class Watch implements Downloader.Progress {
        private volatile boolean cancelled;
        private Runnable close;

        @Override public void transferred(long done, long total) {
        }

        @Override public synchronized void reading(Runnable close) {
            this.close = close;
            if (cancelled) Utils.runOnBackgroundThread(close);
        }

        @Override public boolean cancelled() {
            return cancelled;
        }

        /** Off the main thread, since closing a connection can touch the network. */
        synchronized void cancel() {
            cancelled = true;
            if (close != null) Utils.runOnBackgroundThread(close);
        }
    }

    /** The full screen dialog and what it shows. Made and used on the main thread. */
    static final class Screen {
        final Dialog dialog;
        final ZoomView picture;
        final ProgressBar spinner;
        final TextView save;
        final TextView close;
        final Watch watch = new Watch();
        private final Activity activity;

        Screen(Activity activity, List<MediaSave.Rendition> sizes, String owner, ProfilePicture.Save saver) {
            this.activity = activity;
            dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            FrameLayout root = new FrameLayout(activity);
            root.setBackgroundColor(Color.BLACK);

            picture = new ZoomView(activity);
            picture.setContentDescription(L10n.t(activity, "Profile picture"));
            root.addView(picture, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            spinner = new ProgressBar(activity);
            root.addView(spinner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(0x99000000);
            bar.setPadding(Dim.dp(8), Dim.dp(4), Dim.dp(8), Dim.dp(4));
            close = button(activity, L10n.t(activity, "Close"));
            close.setOnClickListener(view -> dialog.dismiss());
            save = button(activity, L10n.t(activity, "Save"));
            save.setOnClickListener(view -> {
                try {
                    if (!ProfilePicture.viewing()) return;
                    if (!saver.photo(activity, sizes, PostDetails.profilePicture(owner))) ProfilePicture.failed(activity);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.PROFILE_PICTURE, "save from viewer", failure);
                    ProfilePicture.failed(activity);
                }
            });
            bar.addView(close);
            bar.addView(save);
            root.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            dialog.setContentView(root);
            dialog.setOnDismissListener(shut -> watch.cancel());
        }

        /** Shows [loaded], or closes and says the picture couldn't open when it's null. */
        void shown(@Nullable Picture loaded) {
            try {
                if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
                if (loaded == null) {
                    dialog.dismiss();
                    notOpened(activity);
                    return;
                }
                spinner.setVisibility(View.GONE);
                picture.setImageBitmap(loaded.bitmap);
                HookStatus.counted(FamilyNames.PROFILE_PICTURE, viewed(loaded.width));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PROFILE_PICTURE, "show profile picture", failure);
            }
        }

        private static TextView button(Activity activity, String label) {
            TextView button = new TextView(activity);
            button.setText(label);
            button.setTextColor(Color.WHITE);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            button.setGravity(Gravity.CENTER);
            button.setMinHeight(Dim.dp(48));
            button.setMinWidth(Dim.dp(64));
            button.setPadding(Dim.dp(16), 0, Dim.dp(16), 0);
            TypedArray theme = activity.obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
            try {
                Drawable touch = theme.getDrawable(0);
                if (touch != null) button.setBackground(touch);
            } finally {
                theme.recycle();
            }
            return button;
        }
    }

    /** A picture that fits the screen, pinches to zoom, drags when zoomed and double taps to zoom. */
    static final class ZoomView extends ImageView {
        private final Matrix matrix = new Matrix();
        private final float[] values = new float[9];
        private final ScaleGestureDetector pinch;
        private final GestureDetector touch;
        private float fit = 1f;

        ZoomView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                    return true;
                }
            });
            touch = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) {
                    return true;
                }

                @Override public boolean onScroll(MotionEvent first, MotionEvent now, float dx, float dy) {
                    matrix.postTranslate(-dx, -dy);
                    settle();
                    return true;
                }

                @Override public boolean onDoubleTap(MotionEvent event) {
                    if (scale() > fit * 1.05f) fitToView();
                    else zoomBy(2.5f, event.getX(), event.getY());
                    return true;
                }

                @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                    return performClick();
                }
            });
        }

        @Override public void setImageBitmap(Bitmap bitmap) {
            super.setImageBitmap(bitmap);
            fitToView();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            fitToView();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            touch.onTouchEvent(event);
            return true;
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        /** The scale the picture is drawn at now. */
        float scale() {
            matrix.getValues(values);
            return values[Matrix.MSCALE_X];
        }

        /** The scale at which the whole picture fits the view. */
        float fitScale() {
            return fit;
        }

        /** The whole picture, centered, as large as fits. */
        void fitToView() {
            Drawable drawable = getDrawable();
            int width = getWidth(), height = getHeight();
            if (drawable == null || width <= 0 || height <= 0
                    || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) return;
            matrix.setRectToRect(new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight()),
                    new RectF(0, 0, width, height), Matrix.ScaleToFit.CENTER);
            fit = scale();
            setImageMatrix(matrix);
        }

        /** Zooms by [factor] around [x], [y], between fitting the view and {@link #MAX_ZOOM} times that. */
        void zoomBy(float factor, float x, float y) {
            if (getDrawable() == null) return;
            float now = scale();
            float target = Math.max(fit, Math.min(fit * MAX_ZOOM, now * factor));
            if (now <= 0f || target == now) return;
            matrix.postScale(target / now, target / now, x, y);
            settle();
        }

        /** Keeps the picture on screen: centered on a side it doesn't fill, with no gap on one it does. */
        private void settle() {
            Drawable drawable = getDrawable();
            if (drawable == null) return;
            RectF shown = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            matrix.mapRect(shown);
            matrix.postTranslate(shift(shown.left, shown.right, getWidth()), shift(shown.top, shown.bottom, getHeight()));
            setImageMatrix(matrix);
        }

        private static float shift(float start, float end, int room) {
            float size = end - start;
            if (size <= room) return (room - size) / 2f - start;
            if (start > 0) return -start;
            if (end < room) return room - end;
            return 0f;
        }
    }
}
