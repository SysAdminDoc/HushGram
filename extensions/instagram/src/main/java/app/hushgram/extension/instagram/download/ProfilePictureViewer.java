/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
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
        private boolean closeQueued;

        @Override public void transferred(long done, long total) {
        }

        @Override public synchronized void reading(Runnable close) {
            this.close = close;
            closeQueued = false;
            if (cancelled) closeConnection();
        }

        @Override public boolean cancelled() {
            return cancelled;
        }

        /** Off the main thread, since closing a connection can touch the network. */
        synchronized void cancel() {
            cancelled = true;
            closeConnection();
        }

        private void closeConnection() {
            if (close == null || closeQueued) return;
            closeQueued = true;
            if (!ConnectionClose.closeAsync(close)) {
                closeQueued = false;
                HookStatus.threw(FamilyNames.PROFILE_PICTURE, "close profile picture connection",
                        new java.util.concurrent.RejectedExecutionException("Connection close queue is full"));
            }
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
        private final LinearLayout loading;

        Screen(Activity activity, List<MediaSave.Rendition> sizes, String owner, ProfilePicture.Save saver) {
            this.activity = activity;
            dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(ScreenColors.DEFAULT.background);
            // The picture fits above the actions rather than disappearing behind them when zoomed out.
            FrameLayout preview = new FrameLayout(activity);
            root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

            picture = new ZoomView(activity);
            picture.setContentDescription(L10n.t(activity, "Profile picture"));
            picture.setVisibility(View.INVISIBLE);
            preview.addView(picture, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            loading = new LinearLayout(activity);
            loading.setOrientation(LinearLayout.VERTICAL);
            loading.setGravity(Gravity.CENTER);
            loading.setPaddingRelative(Dim.dp(24), 0, Dim.dp(24), 0);
            preview.addView(loading, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            spinner = new ProgressBar(activity);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(ScreenColors.DEFAULT.heading));
            spinner.setContentDescription(L10n.t(activity, "Loading the picture..."));
            spinner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            loading.addView(spinner, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
            TextView caption = new TextView(activity);
            caption.setText(spinner.getContentDescription());
            caption.setTextColor(ScreenColors.DEFAULT.summary);
            caption.setTextSize(14);
            caption.setGravity(Gravity.CENTER);
            caption.setSingleLine(false);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams captionSize = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            captionSize.topMargin = Dim.dp(16);
            loading.addView(caption, captionSize);

            LinearLayout bar = new LinearLayout(activity);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(ScreenColors.DEFAULT.background);
            bar.setPaddingRelative(Dim.dp(16), Dim.dp(12), Dim.dp(16), Dim.dp(16));
            close = button(activity, L10n.t(activity, "Close"));
            ScreenColors.styleAction(close, false);
            close.setOnClickListener(view -> dialog.dismiss());
            save = button(activity, L10n.t(activity, "Save"));
            ScreenColors.styleAction(save, true);
            save.setEnabled(false);
            save.setOnClickListener(view -> {
                try {
                    if (!save.isEnabled() || !ProfilePicture.viewing()) return;
                    if (!saver.photo(activity, sizes, PostDetails.profilePicture(owner))) ProfilePicture.failed(activity);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.PROFILE_PICTURE, "save from viewer", failure);
                    ProfilePicture.failed(activity);
                }
            });
            LinearLayout.LayoutParams closeSize = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            closeSize.setMarginEnd(Dim.dp(8));
            bar.addView(close, closeSize);
            LinearLayout.LayoutParams saveSize = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            saveSize.setMarginStart(Dim.dp(8));
            bar.addView(save, saveSize);
            root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Rect safe = safeInsets(insets);
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.setFocusableInTouchMode(true);
            root.requestFocus();

            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(ScreenColors.DEFAULT.background));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setNavigationBarColor(ScreenColors.DEFAULT.background);
                window.setStatusBarColor(ScreenColors.DEFAULT.background);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false);
                // A light Instagram theme must not leave dark navigation icons on the black viewer.
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            }
            dialog.setOnDismissListener(shut -> watch.cancel());
            dialog.setOnShowListener(shown -> root.requestApplyInsets());
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
                loading.setVisibility(View.GONE);
                picture.setImageBitmap(loaded.bitmap);
                picture.setVisibility(View.VISIBLE);
                save.setEnabled(true);
                HookStatus.counted(FamilyNames.PROFILE_PICTURE, viewed(loaded.width));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PROFILE_PICTURE, "show profile picture", failure);
                dialog.dismiss();
                notOpened(activity);
            }
        }

        /** Keep actions clear of navigation, edge gestures, a cutout and any keyboard still closing. */
        @SuppressWarnings("deprecation")
        static Rect safeInsets(WindowInsets insets) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.systemGestures() | WindowInsets.Type.ime());
                return new Rect(safe.left, safe.top, safe.right, safe.bottom);
            }
            Rect safe = new Rect(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            android.view.DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                safe.left = Math.max(safe.left, cutout.getSafeInsetLeft());
                safe.top = Math.max(safe.top, cutout.getSafeInsetTop());
                safe.right = Math.max(safe.right, cutout.getSafeInsetRight());
                safe.bottom = Math.max(safe.bottom, cutout.getSafeInsetBottom());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.graphics.Insets gestures = insets.getSystemGestureInsets();
                safe.left = Math.max(safe.left, gestures.left);
                safe.right = Math.max(safe.right, gestures.right);
                safe.bottom = Math.max(safe.bottom, gestures.bottom);
            }
            return safe;
        }

        private static TextView button(Activity activity, String label) {
            TextView button = new Button(activity);
            button.setText(label);
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
