/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.Locale;

import app.hushgram.extension.instagram.download.SeekSprites;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The card that floats above your finger while you drag a seek bar (#567, #592): the time you'd land
 * on and the video's length, its speed when that isn't normal, and a small picture of that moment when
 * the video has a {@link SeekSheet}. It sits in the window's top view, so nothing in the post can cut it off, takes no
 * touches, and goes away by itself once the bar it belongs to is let go. Anything thrown leaves the
 * drag as it was. Main thread only.
 */
public final class SeekPreview {
    /** How often a card checks its bar is still held. */
    static final long CHECK_MS = 120;
    /** The longer side of the picture, the gap above the bar and the margin from the screen's edges, in dp. */
    static final int PICTURE_DP = 128, GAP_DP = 12, MARGIN_DP = 8;
    /**
     * The card's fill. Solid, since near either end of a reel's bar the card covers the times Instagram
     * shows there during a drag, and a see-through fill let them show through its own (#567).
     */
    static final int FILL = 0xFF1C1C1E;
    /** The middle dot between the time and a speed. */
    static final char DOT = 0xB7;

    @Nullable private static Card card;

    private SeekPreview() {
    }

    /** Whether the card is wanted. The patch asks this before it remembers a reel, too. */
    public static boolean on() {
        return Utils.settingsReady() && Settings.SEEK_PREVIEW.get();
    }

    /** A drag moved [bar] to [position] milliseconds. [sheet] is the video's pictures, null when it has none. */
    public static void dragged(SeekBar bar, int position, @Nullable SeekSheet sheet, String family) {
        dragged(bar, position, sheet, 1f, family);
    }

    /** The same, on a video playing at [speed], which the card shows beside the time when it isn't normal (#592). */
    public static void dragged(SeekBar bar, int position, @Nullable SeekSheet sheet, float speed, String family) {
        try {
            if (!on() || bar.getMax() <= 0 || !(bar.getRootView() instanceof ViewGroup)) return;
            Card now = card;
            if (now == null || now.bar.get() != bar || !now.attached()) {
                if (now != null) now.remove();
                now = new Card(bar);
                card = now;
            }
            now.show(position, sheet, speed);
        } catch (Throwable failure) {
            HookStatus.threw(family, "seek preview", failure);
        }
    }

    /** "1:05 / 3:20", in the phone's digits. */
    static String text(int position, int length) {
        return time(Math.max(0, Math.min(position, length))) + " / " + time(length);
    }

    /** "1:05 / 3:20 · 1.5x", the speed left off when it's normal or unknown. */
    static String text(int position, int length, float speed) {
        String time = text(position, length);
        if (!(speed > 0f) || Math.abs(speed - 1f) < 0.01f) return time;
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(2);
        return time + " " + DOT + " " + format.format(speed) + "x";
    }

    private static String time(long milliseconds) {
        long seconds = milliseconds / 1000L;
        long hours = seconds / 3600L;
        long minutes = seconds / 60L % 60L;
        Locale locale = Locale.getDefault();
        return hours > 0
                ? String.format(locale, "%d:%02d:%02d", hours, minutes, seconds % 60L)
                : String.format(locale, "%d:%02d", minutes, seconds % 60L);
    }

    /** How big the picture shows for a tile: its shape, the longer side [longest]. */
    static int[] pictureSize(int tileWidth, int tileHeight, int longest) {
        if (tileWidth >= tileHeight) return new int[] {longest, Math.max(1, longest * tileHeight / tileWidth)};
        return new int[] {Math.max(1, longest * tileWidth / tileHeight), longest};
    }

    /** Where a card [width] wide goes in a window [space] wide, centered on [x] and kept [margin] inside its edges. */
    static int left(float x, int width, int space, int margin) {
        int wanted = Math.round(x - width / 2f);
        return Math.max(margin, Math.min(wanted, Math.max(margin, space - width - margin)));
    }

    @VisibleForTesting
    @Nullable
    static View cardForTests() {
        return card == null ? null : card.view;
    }

    @VisibleForTesting
    @Nullable
    static String timeForTests() {
        return card == null ? null : card.time.getText().toString();
    }

    @VisibleForTesting
    static void resetForTests() {
        if (card != null) card.remove();
        card = null;
    }

    private static final class Card implements Runnable {
        final WeakReference<SeekBar> bar;
        final LinearLayout view;
        final ImageView picture;
        final TextView time;
        final float density;
        int position;
        @Nullable SeekSheet sheet;
        @Nullable String shownTile;

        Card(SeekBar seek) {
            bar = new WeakReference<>(seek);
            Context context = seek.getContext();
            density = seek.getResources().getDisplayMetrics().density;
            int corner = Math.round(14 * density);
            int pad = Math.round(6 * density);

            GradientDrawable glass = new GradientDrawable();
            glass.setCornerRadius(corner);
            glass.setColor(FILL);
            glass.setStroke(Math.max(1, Math.round(density / 2f)), 0x40FFFFFF);

            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            view.setPadding(pad, pad, pad, pad);
            view.setBackground(glass);
            view.setElevation(8 * density);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            GradientDrawable rounded = new GradientDrawable();
            rounded.setCornerRadius(corner - pad / 2f);
            rounded.setColor(Color.BLACK);
            picture = new ImageView(context);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.setBackground(rounded);
            picture.setClipToOutline(true);
            picture.setVisibility(View.GONE);
            view.addView(picture, new LinearLayout.LayoutParams(0, 0));

            time = new TextView(context);
            time.setTextColor(Color.WHITE);
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            time.setTypeface(Typeface.DEFAULT_BOLD);
            time.setGravity(Gravity.CENTER);
            time.setPadding(pad, pad / 2, pad, pad / 2);
            view.addView(time, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ((ViewGroup) seek.getRootView()).addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            view.postDelayed(this, CHECK_MS);
        }

        boolean attached() {
            return view.getParent() != null;
        }

        void show(int at, @Nullable SeekSheet from, float speed) {
            SeekBar seek = bar.get();
            if (seek == null) return;
            position = at;
            sheet = from;
            time.setText(text(at, seek.getMax(), speed));
            showPicture(seek);
            place(seek);
        }

        private void showPicture(SeekBar seek) {
            SeekSheet from = sheet;
            if (from == null) {
                picture.setVisibility(View.GONE);
                shownTile = null;
                return;
            }
            SeekSheet.Tile tile = from.tileAt(position);
            Bitmap sprite = SeekSprites.cached(tile.url);
            if (sprite == null) {
                SeekSprites.load(seek.getContext().getApplicationContext(), tile.url, this::refresh);
                return;
            }
            if (tile.key().equals(shownTile)) return;
            if (tile.x + tile.width > sprite.getWidth() || tile.y + tile.height > sprite.getHeight()) return;
            shownTile = tile.key();
            int[] size = pictureSize(tile.width, tile.height, Math.round(PICTURE_DP * density));
            ViewGroup.LayoutParams params = picture.getLayoutParams();
            if (params.width != size[0] || params.height != size[1]) {
                params.width = size[0];
                params.height = size[1];
                picture.setLayoutParams(params);
            }
            picture.setImageBitmap(Bitmap.createBitmap(sprite, tile.x, tile.y, tile.width, tile.height));
            picture.setVisibility(View.VISIBLE);
        }

        /** The sprite this card asked for came in. */
        private void refresh() {
            SeekBar seek = bar.get();
            if (seek == null || !attached()) return;
            showPicture(seek);
            place(seek);
        }

        private void place(SeekBar seek) {
            View root = seek.getRootView();
            int[] at = new int[2];
            seek.getLocationInWindow(at);
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            float across = seek.getWidth() - seek.getPaddingLeft() - seek.getPaddingRight();
            float x = at[0] + seek.getPaddingLeft() + across * Math.min(position, seek.getMax()) / (float) seek.getMax();
            int margin = Math.round(MARGIN_DP * density);
            view.setX(left(x, view.getMeasuredWidth(), root.getWidth(), margin));
            view.setY(Math.max(margin, at[1] - view.getMeasuredHeight() - Math.round(GAP_DP * density)));
        }

        @Override
        public void run() {
            SeekBar seek = bar.get();
            if (seek == null || !seek.isPressed() || !seek.isAttachedToWindow() || !on()) {
                remove();
                if (card == this) card = null;
                return;
            }
            view.postDelayed(this, CHECK_MS);
        }

        void remove() {
            view.removeCallbacks(this);
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        }
    }
}
