/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.InputStream;

import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * A home screen icon from a picture of your own (#442). The settings row opens Android's picture picker,
 * which hands back a content address and needs no storage permission. The picture is decoded off the main
 * thread at a bounded size and shown in a dialog under a round guide, where one finger moves it and two
 * zoom it. Done pins a launcher shortcut that opens Instagram, with the framed square as its adaptive
 * icon. The launcher asks you to add it, and one that can't says so in a toast.
 */
public final class CustomIcon {
    /** The adaptive icon canvas, in dp. The launcher shows the middle 72 of it. */
    static final int ICON_DP = 108;
    static final int VISIBLE_DP = 72;
    /** The longest edge a decoded picture may have, whatever the picture's own size. */
    /** Each pinned icon is its own shortcut, so a second picture adds a second icon. */
    private static final String SHORTCUT_PREFIX = "hushgram.customicon.";
    static final int MAX_EDGE = 1536;
    /** How far in the framing may zoom, as a multiple of the zoom that just covers it. */
    static final float MAX_ZOOM = 5f;

    private CustomIcon() {
    }

    /** The power-of-two sample size that brings a picture of [width] by [height] to [maxEdge] or under. */
    static int sampleSize(int width, int height, int maxEdge) {
        int sample = 1;
        if (width <= 0 || height <= 0 || maxEdge <= 0) return sample;
        while (Math.max(width, height) / sample > maxEdge) sample *= 2;
        return sample;
    }

    /**
     * Where a picture sits under a square frame: its scale and the offset of its top left corner in the
     * frame. It always covers the frame, and zooms between just covering it and [MAX_ZOOM] times that.
     */
    static final class Frame {
        final int pictureWidth;
        final int pictureHeight;
        final float side;
        final float minScale;
        float scale;
        float x;
        float y;

        Frame(int pictureWidth, int pictureHeight, float side) {
            this.pictureWidth = Math.max(1, pictureWidth);
            this.pictureHeight = Math.max(1, pictureHeight);
            this.side = Math.max(1f, side);
            this.minScale = this.side / Math.min(this.pictureWidth, this.pictureHeight);
            this.scale = minScale;
            this.x = (this.side - this.pictureWidth * scale) / 2f;
            this.y = (this.side - this.pictureHeight * scale) / 2f;
        }

        /** Moves the picture by the finger's distance. */
        void pan(float dx, float dy) {
            x += dx;
            y += dy;
            keepCovering();
        }

        /** Zooms by [factor] around the frame point ([px], [py]), which stays under the fingers. */
        void zoom(float factor, float px, float py) {
            float next = Math.max(minScale, Math.min(minScale * MAX_ZOOM, scale * factor));
            float ratio = next / scale;
            x = px - (px - x) * ratio;
            y = py - (py - y) * ratio;
            scale = next;
            keepCovering();
        }

        private void keepCovering() {
            x = Math.max(side - pictureWidth * scale, Math.min(0f, x));
            y = Math.max(side - pictureHeight * scale, Math.min(0f, y));
        }

        /** The matrix that draws the picture into a square of [size] pixels the way the frame shows it. */
        Matrix matrix(float size) {
            float k = size / side;
            Matrix matrix = new Matrix();
            matrix.postScale(scale * k, scale * k);
            matrix.postTranslate(x * k, y * k);
            return matrix;
        }
    }

    /** The picture, framed, as a square bitmap of [size] pixels. */
    static Bitmap render(Bitmap picture, Frame frame, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        canvas.drawBitmap(picture, frame.matrix(size), paint);
        return out;
    }

    /** Decodes [uri] at a bounded size, or answers null. Runs on the caller's thread. */
    @Nullable
    static Bitmap decode(Context context, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Exception | OutOfMemoryError failure) {
            Logger.printException(() -> "Custom icon: could not read the picture", failure instanceof Exception
                    ? (Exception) failure : new RuntimeException(failure));
            return null;
        }
    }

    /** Reads the picture [uri] off the main thread, then opens the framing dialog on [activity]. */
    public static void edit(Activity activity, @Nullable Uri uri) {
        if (uri == null || activity == null) {
            unreadable();
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            Bitmap picture = decode(activity, uri);
            Utils.runOnMainThread(() -> {
                if (picture == null) {
                    unreadable();
                } else if (!activity.isFinishing() && !activity.isDestroyed()) {
                    frameDialog(activity, picture);
                }
            });
        });
    }

    private static void unreadable() {
        Utils.showToastLong(L10n.t("Couldn't read that picture."));
    }

    private static void frameDialog(Activity activity, Bitmap picture) {
        float density = activity.getResources().getDisplayMetrics().density;
        int side = Math.round(240 * density);
        FramingView framing = new FramingView(activity, picture, side);
        TextView hint = new TextView(activity);
        hint.setText(L10n.t("Drag to move, pinch to zoom."));
        hint.setTextColor(Color.LTGRAY);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Math.round(12 * density), 0, 0);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        body.setPadding(pad, pad, pad, 0);
        body.addView(framing, new LinearLayout.LayoutParams(side, side, Gravity.CENTER_HORIZONTAL));
        body.addView(hint);
        FrameLayout holder = new FrameLayout(activity);
        holder.addView(body, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(L10n.t("Custom app icon"))
                .setView(holder)
                .setNegativeButton(L10n.t("Cancel"), null)
                .setPositiveButton(L10n.t("Add to home screen"), (shown, which) -> {
                    int size = Math.round(ICON_DP * activity.getResources().getDisplayMetrics().density);
                    pin(activity, render(picture, framing.frame, size));
                })
                .show();
        ScreenColors.dialog(dialog);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /** Asks the launcher to pin a shortcut that opens Instagram with [icon], or says it can't. */
    static void pin(Context context, Bitmap icon) {
        try {
            ShortcutManager manager = context.getSystemService(ShortcutManager.class);
            Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (manager == null || open == null || !manager.isRequestPinShortcutSupported()) {
                Utils.showToastLong(L10n.t("This launcher can't add it."));
                return;
            }
            CharSequence label = context.getApplicationInfo().loadLabel(context.getPackageManager());
            ShortcutInfo shortcut = new ShortcutInfo.Builder(context, SHORTCUT_PREFIX + System.currentTimeMillis())
                    .setShortLabel(label)
                    .setIcon(Icon.createWithAdaptiveBitmap(icon))
                    .setIntent(open)
                    .build();
            if (!manager.requestPinShortcut(shortcut, null)) {
                Utils.showToastLong(L10n.t("This launcher can't add it."));
            }
        } catch (Exception failure) {
            Logger.printException(() -> "Custom icon: could not pin the shortcut", failure);
            Utils.showToastLong(L10n.t("This launcher can't add it."));
        }
    }

    /** The picture under a round guide the size of what the launcher shows. One finger moves it, two zoom it. */
    static final class FramingView extends View {
        final Frame frame;
        private final Bitmap picture;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outside = new Path();
        private final ScaleGestureDetector pinch;
        private float lastX;
        private float lastY;
        private int pointer = -1;

        FramingView(Context context, Bitmap picture, int side) {
            super(context);
            this.picture = picture;
            this.frame = new Frame(picture.getWidth(), picture.getHeight(), side);
            shade.setColor(0xB0000000);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.WHITE);
            ring.setStrokeWidth(2 * context.getResources().getDisplayMetrics().density);
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    frame.zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                    invalidate();
                    return true;
                }
            });
        }

        /** The guide circle's radius: the part of the canvas a launcher shows. */
        float guideRadius() {
            return frame.side * VISIBLE_DP / ICON_DP / 2f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawBitmap(picture, frame.matrix(frame.side), paint);
            float middle = frame.side / 2f;
            outside.reset();
            outside.addRect(0, 0, frame.side, frame.side, Path.Direction.CW);
            outside.addCircle(middle, middle, guideRadius(), Path.Direction.CCW);
            canvas.drawPath(outside, shade);
            canvas.drawCircle(middle, middle, guideRadius(), ring);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    pointer = event.getPointerId(0);
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!pinch.isInProgress() && event.getPointerCount() == 1 && pointer == event.getPointerId(0)) {
                        frame.pan(event.getX() - lastX, event.getY() - lastY);
                        invalidate();
                    }
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_POINTER_DOWN:
                    // The fingers changed: the next move starts from where the remaining one is.
                    int index = event.getActionIndex() == 0 ? 1 : 0;
                    if (index < event.getPointerCount()) {
                        pointer = event.getPointerId(index);
                        lastX = event.getX(index);
                        lastY = event.getY(index);
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
