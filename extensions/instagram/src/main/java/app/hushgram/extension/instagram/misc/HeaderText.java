/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * Home header text (#454): words of your own, or a picture of your own ({@link HeaderPicture}), at
 * the top of Home in place of Instagram's logo. The settings patch hands Home's title view here each
 * time the header draws it from a new state, and the newer header's logo view once as the header is
 * built ({@code SettingsEntry.setLogoLongClickListener} and {@code watchLogo}). The text is drawn at
 * the logo's height and takes the color Instagram gives its logo, so it follows dark mode. A picture
 * keeps its own colors and takes the place of the words when both are saved.
 *
 * <p>Each logo gets one {@link Keeper}, which reads the two settings before every draw, so a change
 * shows the next time Home draws, with no restart, and blanking both puts Instagram's logo back.
 * Pause and every failure leave Instagram's logo alone.
 */
public final class HeaderText {
    /** The most characters the header takes. */
    public static final int MAX_LENGTH = 30;

    /** The text's height when the logo gave none, in dp. */
    private static final int FALLBACK_HEIGHT_DP = 24;

    /** The widest the text gets, as a share of the screen's width. Wider text gets smaller. */
    private static final float MAX_WIDTH_SHARE = 0.55f;

    /** The older header's logo, inside its title view. */
    static final String TITLE_LOGO = "title_logo";

    /** The keeper of each logo seen, so a header drawing again doesn't stack a second one. Main thread only. */
    private static final Map<ImageView, Keeper> KEEPERS = new WeakHashMap<>();

    /** Whether a test says Home header text is in this build, instead of asking {@link PatchFamily}. */
    @Nullable
    static volatile Boolean inBuildForTests;

    private HeaderText() {
    }

    private static boolean inBuild() {
        Boolean forced = inBuildForTests;
        return forced != null ? forced : PatchFamily.HEADER_TEXT.inBuild();
    }

    /**
     * Gives the logo in [view] (the logo itself, or the title view holding it) a keeper, which puts
     * the saved picture or text in its place now and before every later draw. Never throws.
     */
    public static void dress(@Nullable View view) {
        try {
            if (view == null || !Utils.settingsReady() || !inBuild()) return;
            ImageView logo = logoIn(view);
            if (logo == null || KEEPERS.containsKey(logo)) return;
            Keeper keeper = new Keeper(logo);
            KEEPERS.put(logo, keeper);
            keeper.refresh(logo);
            logo.getViewTreeObserver().addOnPreDrawListener(keeper);
        } catch (Throwable failure) {
            Logger.printException(() -> "Header text: could not watch the logo at the top of Home", failure);
        }
    }

    /** [view] when it's the logo, else the older header's logo inside it, else null. */
    @Nullable
    static ImageView logoIn(View view) {
        if (view instanceof ImageView) return (ImageView) view;
        Context context = view.getContext();
        int id = context.getResources().getIdentifier(TITLE_LOGO, "id", context.getPackageName());
        if (id == 0) return null;
        View logo = view.findViewById(id);
        return logo instanceof ImageView ? (ImageView) logo : null;
    }

    /**
     * What the header shows for [typed]: one line, without control or invisible formatting
     * characters, trimmed, and at most {@link #MAX_LENGTH} characters. Empty keeps the logo.
     */
    public static String clean(@Nullable String typed) {
        if (typed == null) return "";
        StringBuilder line = new StringBuilder();
        boolean space = false;
        int count = 0;
        for (int at = 0; at < typed.length() && count < MAX_LENGTH; ) {
            int point = typed.codePointAt(at);
            at += Character.charCount(point);
            int type = Character.getType(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = line.length() > 0;
                continue;
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            if (space) {
                // A space needs room for the character after it, so the text never ends on one.
                if (count + 1 >= MAX_LENGTH) break;
                line.append(' ');
                count++;
                space = false;
            }
            line.appendCodePoint(point);
            count++;
        }
        return line.toString();
    }

    /**
     * The height the header's logo takes: [logo]'s own, else [previous] (the height the last mark
     * was given, -1 for none), else the view's, else {@link #FALLBACK_HEIGHT_DP}.
     */
    static int height(ImageView image, @Nullable Drawable logo, int previous) {
        int height = logo == null ? -1 : logo.getIntrinsicHeight();
        if (height <= 0) height = previous;
        if (height <= 0) height = image.getMeasuredHeight() - image.getPaddingTop() - image.getPaddingBottom();
        if (height <= 0 && image.getLayoutParams() != null && image.getLayoutParams().height > 0) {
            height = image.getLayoutParams().height - image.getPaddingTop() - image.getPaddingBottom();
        }
        if (height <= 0) height = Math.round(FALLBACK_HEIGHT_DP * image.getResources().getDisplayMetrics().density);
        return height;
    }

    private static int maxWidth(ImageView image) {
        return Math.round(image.getResources().getDisplayMetrics().widthPixels * MAX_WIDTH_SHARE);
    }

    /**
     * The theme's main text color, which Instagram's logo takes, for text drawn before the header
     * colors it. Always opaque: the logo's own pixels are, so a color filter over the text gives the
     * same solid color it gives the logo.
     */
    static int primaryText(Context context) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
                if (value.resourceId != 0) {
                    ColorStateList colors = context.getColorStateList(value.resourceId);
                    return colors.getDefaultColor() | 0xFF000000;
                }
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data | 0xFF000000;
                }
            }
        } catch (RuntimeException failure) {
            // The day or night default below.
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? Color.WHITE : Color.BLACK;
    }

    /**
     * One logo's keeper. Before each draw it checks the two settings and the logo's drawable: when
     * either setting changed, or Instagram loaded its logo again over the mark, it puts the right
     * mark in (or Instagram's logo back) and skips that one draw so the next shows it. Holds the logo
     * weakly.
     */
    static final class Keeper implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<ImageView> header;
        /** Instagram's logo, as last seen before a mark went in. */
        @Nullable private Drawable original;
        @Nullable private ImageView.ScaleType originalScale;
        /** The mark shown now, or null while Instagram's logo shows. */
        @Nullable Drawable mark;
        @Nullable private String seenText;
        @Nullable private String seenPicture;
        private int seenPictures = -1;

        Keeper(ImageView image) {
            this.header = new WeakReference<>(image);
        }

        @Override
        public boolean onPreDraw() {
            ImageView image = header.get();
            if (image == null) return true;
            try {
                // This pass would draw what was there, so the next one draws the change.
                return !refresh(image);
            } catch (Throwable failure) {
                Logger.printException(() -> "Header text: could not keep the top of Home", failure);
                return true;
            }
        }

        /** Puts in what the settings ask for. Answers whether the logo's drawable changed. */
        boolean refresh(ImageView image) {
            Drawable current = image.getDrawable();
            String text = Settings.HEADER_TEXT.get();
            String picture = Settings.HEADER_PICTURE.get();
            int pictures = HeaderPicture.revision();
            // Identity checks only: a saved value is a new object, and this runs before every draw.
            boolean same = text == seenText && picture == seenPicture && pictures == seenPictures;
            if (same && current == mark) return false;
            if (same && mark == null) return false;
            int previous = mark == null ? -1 : mark.getIntrinsicHeight();
            if (current != mark) {
                // Instagram's logo, first seen or loaded again.
                original = current;
                originalScale = image.getScaleType();
            }
            seenText = text;
            seenPicture = picture;
            seenPictures = pictures;
            Drawable next = make(image, picture, text, previous);
            if (next == null) {
                if (mark == null) return false;
                mark = null;
                image.setImageDrawable(original);
                if (originalScale != null) image.setScaleType(originalScale);
                Logger.printDebug(() -> "Header text: Instagram's logo is back at the top of Home");
                return true;
            }
            mark = next;
            image.setImageDrawable(next);
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            return true;
        }

        /** The picture's mark, else the text's, else null for Instagram's logo. */
        @Nullable
        private Drawable make(ImageView image, String picture, String text, int previous) {
            int height = height(image, original, previous);
            if (!picture.isEmpty()) {
                Bitmap chosen = HeaderPicture.load(image.getContext());
                if (chosen != null) return new PictureMark(chosen, height, maxWidth(image));
            }
            String words = clean(text);
            if (words.isEmpty()) return null;
            return new TextMark(words, height, maxWidth(image), primaryText(image.getContext()));
        }
    }

    /**
     * The picture as a drawable at the logo's height, its shape kept. A picture wider than
     * {@link #MAX_WIDTH_SHARE} of the screen gets smaller. It keeps its own colors, so the color
     * filter and tint the header gives its logo don't reach it.
     */
    static final class PictureMark extends Drawable {
        final Bitmap picture;
        private final int width;
        private final int height;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF place = new RectF();

        PictureMark(Bitmap picture, int slot, int maxWidth) {
            this.picture = picture;
            int wide = Math.max(1, Math.round(picture.getWidth() * slot / (float) picture.getHeight()));
            int high = slot;
            if (maxWidth > 0 && wide > maxWidth) {
                high = Math.max(1, Math.round(high * maxWidth / (float) wide));
                wide = maxWidth;
            }
            width = wide;
            height = high;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float scale = Math.min(bounds.width() / (float) picture.getWidth(),
                    bounds.height() / (float) picture.getHeight());
            float halfWidth = picture.getWidth() * scale / 2f;
            float halfHeight = picture.getHeight() * scale / 2f;
            place.set(bounds.exactCenterX() - halfWidth, bounds.exactCenterY() - halfHeight,
                    bounds.exactCenterX() + halfWidth, bounds.exactCenterY() + halfHeight);
            canvas.drawBitmap(picture, null, place, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return paint.getAlpha();
        }

        /** The header's logo color would paint the whole picture one color, so it's left out. */
        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The text as a drawable at the logo's height. The header colors its logo through a color filter
     * or a tint, which this takes, so the text gets Instagram's own color for light and dark mode.
     */
    static final class TextMark extends Drawable {
        final String text;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Rect ink = new Rect();
        private final int height;
        private final int width;
        private final float inkTop;
        private final float inkBottom;
        private final int themeColor;
        @Nullable private ColorStateList tint;
        private int color;
        private int alpha = 255;

        TextMark(String text, int height, int maxWidth, int themeColor) {
            this.text = text;
            this.height = height;
            this.themeColor = themeColor;
            this.color = themeColor;
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setColor(themeColor);
            // The text and a capital letter together set the size, so short words without capitals
            // or with descenders still sit at the logo's height.
            paint.setTextSize(100f);
            Rect reach = reach();
            float size = reach.height() > 0 ? 100f * height / reach.height() : height;
            paint.setTextSize(size);
            paint.getTextBounds(text, 0, text.length(), ink);
            if (maxWidth > 0 && ink.width() > maxWidth) {
                paint.setTextSize(size * maxWidth / ink.width());
                paint.getTextBounds(text, 0, text.length(), ink);
            }
            Rect fitted = reach();
            inkTop = fitted.top;
            inkBottom = fitted.bottom;
            width = Math.max(1, ink.width());
        }

        /** The text's ink with a capital H's, at the paint's size. */
        private Rect reach() {
            Rect union = new Rect();
            paint.getTextBounds(text, 0, text.length(), union);
            Rect capital = new Rect();
            paint.getTextBounds("H", 0, 1, capital);
            union.union(capital);
            return union;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.left + (bounds.width() - width) / 2f - ink.left;
            float y = bounds.exactCenterY() - (inkTop + inkBottom) / 2f;
            canvas.drawText(text, x, y, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            paint();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Nullable
        @Override
        public ColorFilter getColorFilter() {
            return paint.getColorFilter();
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
            this.tint = tint;
            applyTint(getState());
        }

        @Override
        public boolean isStateful() {
            return tint != null && tint.isStateful();
        }

        @Override
        protected boolean onStateChange(@NonNull int[] state) {
            return applyTint(state);
        }

        private boolean applyTint(int[] state) {
            int tinted = tint == null ? themeColor : tint.getColorForState(state, tint.getDefaultColor());
            if (tinted == color) return false;
            color = tinted;
            paint();
            return true;
        }

        /** The paint takes the tint's color at the drawable's alpha. */
        private void paint() {
            paint.setColor(color);
            paint.setAlpha(Color.alpha(color) * alpha / 255);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
