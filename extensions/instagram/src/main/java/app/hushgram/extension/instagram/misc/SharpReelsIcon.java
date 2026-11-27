/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Instagram's Reels tab icon drawn as a path, so it stays sharp at any size (#826).
 *
 * <p>The Reels tab shows {@link #OUTLINE}, or {@link #FILLED} while it's the tab you're on. Instagram
 * ships both only as 72 pixel pictures for 480 dpi, where its other tab icons are vector drawings, so
 * drawn any bigger they're stretched and go soft. While HushGram draws the Reels icon at another size,
 * this stands in for Instagram's picture and draws the same shape as a path. State, tint, color filter,
 * alpha and bounds all still go to Instagram's picture, and the path takes the color that picture draws
 * in, so it follows the theme and the selected tab as before. When Instagram's picture is anything
 * else, it's drawn as it is.
 */
final class SharpReelsIcon extends DrawableWrapper {
    static final String OUTLINE = "instagram_reels_tall_ufi_outline_24";
    static final String FILLED = "instagram_reels_tall_ufi_filled_24";
    /** Instagram's picture is neither of the two. */
    static final int NONE = 0;
    static final int OUTLINED = 1;
    static final int SOLID = 2;

    /** The pictures' grid: 72 units across their 24 dp square, three to a dp. */
    static final float GRID = 72f;
    /** The outline's line, 2 dp. */
    static final float LINE = 6f;
    /** The box's outer edge and corner radius. */
    static final RectF BOX = new RectF(6f, 1.5f, 63f, 70.5f);
    static final float BOX_RADIUS = 22f;
    /**
     * The play triangle, an equilateral one pointing right with round corners: where its corners' circles
     * sit and their radius. Its left side is upright at {@link #PLAY_LEFT} less the radius.
     */
    static final float PLAY_LEFT = 28.5f;
    static final float PLAY_TOP = 28.48f;
    static final float PLAY_BOTTOM = 43.52f;
    static final float PLAY_TIP = 41.53f;
    static final float PLAY_MIDDLE = 36f;
    static final float PLAY_RADIUS = 4.5f;
    /** The most drawables looked through for the picture. */
    private static final int MAX_DEPTH = 8;
    /** The largest square the color is sampled in. */
    private static final int MAX_SAMPLE = 256;

    /** Instagram's two pictures for each set of resources, outline first; a missing one is null. */
    private static final WeakHashMap<Resources, Bitmap[]> known = new WeakHashMap<>();
    /** Pictures compared with the known ones by their pixels, and what they turned out to be. */
    private static final WeakHashMap<Bitmap, Integer> compared = new WeakHashMap<>();

    private final Resources resources;
    private final String packageName;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path solid = new Path();
    /** The picture the color was taken from, so a new one is sampled again. */
    @Nullable
    private BitmapDrawable sampled;
    private int color;
    private boolean stale = true;
    private boolean failed;

    SharpReelsIcon(Drawable picture, Resources resources, String packageName) {
        super(picture);
        this.resources = resources;
        this.packageName = packageName;
        float half = LINE / 2f;
        outline.addRoundRect(new RectF(BOX.left + half, BOX.top + half, BOX.right - half, BOX.bottom - half),
                BOX_RADIUS - half, BOX_RADIUS - half, Path.Direction.CW);
        play(outline);
        solid.setFillType(Path.FillType.EVEN_ODD);
        solid.addRoundRect(BOX, BOX_RADIUS, BOX_RADIUS, Path.Direction.CW);
        play(solid);
    }

    /** Adds the play triangle to [path]: the outline's middle line, and the hole in the filled box. */
    static void play(Path path) {
        Path play = new Path();
        play.arcTo(circle(PLAY_TIP, PLAY_MIDDLE), -60f, 120f);
        play.arcTo(circle(PLAY_LEFT, PLAY_BOTTOM), 60f, 120f);
        play.arcTo(circle(PLAY_LEFT, PLAY_TOP), 180f, 120f);
        play.close();
        path.addPath(play);
    }

    private static RectF circle(float x, float y) {
        return new RectF(x - PLAY_RADIUS, y - PLAY_RADIUS, x + PLAY_RADIUS, y + PLAY_RADIUS);
    }

    /** Which of Instagram's two pictures [picture] shows now: {@link #OUTLINED}, {@link #SOLID} or {@link #NONE}. */
    static int kind(@Nullable Drawable picture, Resources resources, String packageName) {
        BitmapDrawable shown = shown(picture, 0);
        return shown == null ? NONE : kind(shown.getBitmap(), resources, packageName);
    }

    private static int kind(@Nullable Bitmap bitmap, Resources resources, String packageName) {
        if (bitmap == null) return NONE;
        Bitmap[] pictures = pictures(resources, packageName);
        if (bitmap == pictures[0]) return OUTLINED;
        if (bitmap == pictures[1]) return SOLID;
        // Drawn from Instagram's own cache, a picture is the same bitmap. A copy is compared once by its pixels.
        Integer before = compared.get(bitmap);
        if (before != null) return before;
        int found = NONE;
        for (int i = 0; i < 2 && found == NONE; i++) {
            Bitmap each = pictures[i];
            if (each != null && each.getWidth() == bitmap.getWidth() && each.getHeight() == bitmap.getHeight()
                    && each.sameAs(bitmap)) {
                found = i == 0 ? OUTLINED : SOLID;
            }
        }
        compared.put(bitmap, found);
        return found;
    }

    /**
     * The single picture [picture] draws now, through the picture for its state, its layers and anything
     * wrapping it, or null when it draws something else or more than one.
     */
    @Nullable
    static BitmapDrawable shown(@Nullable Drawable picture, int depth) {
        if (picture == null || depth > MAX_DEPTH) return null;
        if (picture instanceof BitmapDrawable) return (BitmapDrawable) picture;
        if (picture instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) picture;
            BitmapDrawable found = null;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                BitmapDrawable each = shown(layers.getDrawable(i), depth + 1);
                if (each == null) continue;
                if (found != null) return null;
                found = each;
            }
            return found;
        }
        if (picture instanceof DrawableWrapper) return shown(((DrawableWrapper) picture).getDrawable(), depth + 1);
        Drawable current = picture.getCurrent();
        return current == picture ? null : shown(current, depth + 1);
    }

    private static Bitmap[] pictures(Resources resources, String packageName) {
        synchronized (known) {
            Bitmap[] pictures = known.get(resources);
            if (pictures == null) {
                pictures = new Bitmap[]{load(resources, packageName, OUTLINE), load(resources, packageName, FILLED)};
                known.put(resources, pictures);
            }
            return pictures;
        }
    }

    @Nullable
    private static Bitmap load(Resources resources, String packageName, String name) {
        try {
            int id = resources.getIdentifier(name, "drawable", packageName);
            if (id == 0) return null;
            Drawable picture = resources.getDrawable(id, null);
            return picture instanceof BitmapDrawable ? ((BitmapDrawable) picture).getBitmap() : null;
        } catch (Throwable missing) {
            return null;
        }
    }

    /** Puts Instagram's own picture back on [icon]. */
    void giveBack(ImageView icon) {
        icon.setImageDrawable(getDrawable());
    }

    @Override
    public void draw(Canvas canvas) {
        Drawable picture = getDrawable();
        if (picture == null) return;
        if (!failed) {
            try {
                if (drawPath(canvas, picture)) return;
            } catch (Throwable failure) {
                failed = true;
                HookStatus.threw(FamilyNames.REELS_TAB, TabIcons.HOOK + " path", failure);
            }
        }
        picture.draw(canvas);
    }

    /** Draws the path for the picture [picture] shows, where it would draw it. Answers false when it's neither. */
    private boolean drawPath(Canvas canvas, Drawable picture) {
        BitmapDrawable shown = shown(picture, 0);
        int kind = shown == null ? NONE : kind(shown.getBitmap(), resources, packageName);
        if (kind == NONE) return false;
        Rect at = shown.getBounds().isEmpty() ? getBounds() : shown.getBounds();
        if (at.isEmpty()) return false;
        if (stale || shown != sampled) {
            color = sample(picture);
            sampled = shown;
            stale = false;
        }
        if ((color >>> 24) == 0) return true;
        paint.setColor(color);
        int saved = canvas.save();
        canvas.translate(at.left, at.top);
        canvas.scale(at.width() / GRID, at.height() / GRID);
        if (kind == OUTLINED) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(LINE);
            canvas.drawPath(outline, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(solid, paint);
        }
        canvas.restoreToCount(saved);
        return true;
    }

    /**
     * The color [picture] draws in, tint, color filter and alpha included: its most opaque pixel, drawn
     * on its own at up to {@link #MAX_SAMPLE} pixels.
     */
    private int sample(Drawable picture) {
        Rect bounds = getBounds();
        int width = Math.max(1, Math.min(bounds.width(), MAX_SAMPLE));
        int height = Math.max(1, Math.min(bounds.height(), MAX_SAMPLE));
        Bitmap probe = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(probe);
            canvas.scale(width / (float) Math.max(1, bounds.width()), height / (float) Math.max(1, bounds.height()));
            canvas.translate(-bounds.left, -bounds.top);
            picture.draw(canvas);
            int[] pixels = new int[width * height];
            probe.getPixels(pixels, 0, width, 0, 0, width, height);
            int best = 0;
            for (int pixel : pixels) {
                if ((pixel >>> 24) > (best >>> 24)) best = pixel;
            }
            return best;
        } finally {
            probe.recycle();
        }
    }

    @Override
    protected boolean onStateChange(int[] state) {
        stale = true;
        return super.onStateChange(state);
    }

    @Override
    public void setAlpha(int alpha) {
        stale = true;
        super.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter filter) {
        stale = true;
        super.setColorFilter(filter);
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        stale = true;
        super.setTintList(tint);
    }

    @Override
    public void setTintMode(@Nullable PorterDuff.Mode mode) {
        stale = true;
        super.setTintMode(mode);
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        stale = true;
        super.invalidateDrawable(who);
    }

    /** Tests stand in Instagram's two pictures, since a test app has none of them. */
    static void picturesForTests(Resources resources, @Nullable Bitmap outline, @Nullable Bitmap filled) {
        synchronized (known) {
            known.put(resources, new Bitmap[]{outline, filled});
        }
        compared.clear();
    }

    static void resetForTests() {
        synchronized (known) {
            known.clear();
        }
        compared.clear();
    }
}
