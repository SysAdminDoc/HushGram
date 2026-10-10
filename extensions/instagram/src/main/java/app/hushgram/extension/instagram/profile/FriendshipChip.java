/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.profile.FriendshipStatus.Relation;
import app.hushgram.extension.shared.L10n;

/**
 * The chip Show it as a chip puts under the posts, followers and following counts on someone's
 * profile: an outlined pill with a person icon that says Follows you, Following each other or
 * Doesn't follow you.
 *
 * <p>The counts are a block of their own in the profile header. The chip isn't a view put in among
 * Instagram's: the block gets room for it as padding at its bottom, and the chip is drawn there in
 * the block's overlay. Whatever Instagram lays out under the counts then moves down with them,
 * whichever layout holds the block. The block is the one holding the followers count, under the
 * nearest view that holds both it and the pronouns slot the hooks are handed.
 *
 * <p>Runs on the main thread, where Instagram binds the header.
 */
final class FriendshipChip {
    /** The number of followers, inside the counts block. */
    static final String FOLLOWERS_COUNT = "profile_header_familiar_followers_value";

    /** The icons, Instagram's own, by name. A build without them gets a chip without an icon. */
    static final String FOLLOWING_ICON = "instagram_user_following_outline_24";
    static final String NOT_FOLLOWING_ICON = "instagram_user_unfollow_outline_24";

    /** How far up from the pronouns slot the counts are looked for. */
    private static final int MAX_DEPTH = 8;

    /** Set by tests that have no Instagram resources: the followers count's id. */
    static int followersCountIdForTests;

    /**
     * The chips showing, by the counts block they're under. Blocks go when their screen does: a
     * chip holds its block only weakly, so the entry doesn't keep the key, the header or the
     * Activity alive, and goes with them.
     */
    private static final Map<View, Chip> SHOWN = new WeakHashMap<>();

    private FriendshipChip() {
    }

    /** Whether any header has a chip, so one may need taking away. */
    static boolean anyShown() {
        return !SHOWN.isEmpty();
    }

    /**
     * Shows [relation]'s chip under the counts of the profile header [inside] is part of, in its
     * own color and the typeface of [style] when there is one. False when the counts can't be found.
     */
    static boolean show(View inside, Relation relation, @Nullable TextView style) {
        View block = counts(inside);
        if (block == null) return false;
        Chip chip = SHOWN.get(block);
        if (chip == null) {
            chip = new Chip(block);
            SHOWN.put(block, chip);
        }
        chip.show(relation, style);
        return true;
    }

    /** Takes the chip off the header [inside] is part of, giving its counts their room back. */
    static void clear(View inside) {
        if (SHOWN.isEmpty()) return;
        View block = counts(inside);
        if (block == null) return;
        Chip chip = SHOWN.remove(block);
        if (chip != null) chip.remove();
    }

    /** The chip under [block], or null. */
    @Nullable
    static Chip shownUnder(View block) {
        return SHOWN.get(block);
    }

    /**
     * The counts block of the header [inside] is part of: going up from [inside], the first view
     * holding the followers count, and in it the child the count is in. Null when there's none, or
     * it isn't on screen.
     */
    @Nullable
    static View counts(View inside) {
        int id = followersCountId(inside.getContext());
        if (id == 0) return null;
        View at = inside;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) parent;
            View count = group.findViewById(id);
            if (count != null && count != group) {
                View block = childHolding(group, count);
                return block != null && block.getVisibility() == View.VISIBLE ? block : null;
            }
            at = group;
        }
        return null;
    }

    /** The child of [group] that [view] is, or is inside. */
    @Nullable
    private static View childHolding(ViewGroup group, View view) {
        View at = view;
        while (at.getParent() != group) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return null;
            at = (View) parent;
        }
        return at;
    }

    private static int followersCountId(Context context) {
        if (followersCountIdForTests != 0) return followersCountIdForTests;
        return context.getResources().getIdentifier(FOLLOWERS_COUNT, "id", context.getPackageName());
    }

    /**
     * Blue for Follows you, green for Following each other and red for Doesn't follow you. A light
     * profile gets darker shades, since the bright ones are hard to read on white.
     */
    static int color(Relation relation, boolean light) {
        switch (relation) {
            case FOLLOWING_EACH_OTHER:
                return light ? Color.rgb(0x18, 0x80, 0x38) : Color.rgb(0x2E, 0xCC, 0x71);
            case DOESNT_FOLLOW_YOU:
                return light ? Color.rgb(0xD9, 0x30, 0x25) : Color.rgb(0xED, 0x49, 0x56);
            default:
                return light ? Color.rgb(0x1A, 0x73, 0xE8) : Color.rgb(0x37, 0x97, 0xEF);
        }
    }

    /**
     * Whether the profile is light: the pronouns line's words are dark (Instagram's gray there is
     * well under a quarter luminance on light and well over on dark), or with no line to go by, the
     * phone isn't in dark mode.
     */
    static boolean light(Context context, @Nullable TextView style) {
        if (style != null) return Color.luminance(style.getCurrentTextColor()) < 0.25f;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_YES;
    }

    /** The chip's words for [relation]. */
    static String text(Relation relation) {
        switch (relation) {
            case FOLLOWING_EACH_OTHER:
                return L10n.t("Following each other");
            case DOESNT_FOLLOW_YOU:
                return L10n.t("Doesn't follow you");
            default:
                return L10n.t("Follows you");
        }
    }

    /**
     * One counts block's chip: the room it took and the drawing in the block's overlay. It's the
     * value [SHOWN] keeps for the block, so it holds the block weakly: a strong hold would keep the
     * key alive and the entry forever.
     */
    static final class Chip implements View.OnLayoutChangeListener {
        final WeakReference<View> block;
        final Pill pill;
        /** The block's own bottom padding, which the chip's room goes on top of. */
        int ownBottom;
        final int room;

        Chip(View block) {
            this.block = new WeakReference<>(block);
            pill = new Pill(block.getContext());
            room = pill.getIntrinsicHeight() + pill.dp(8);
            ownBottom = block.getPaddingBottom();
            block.getOverlay().add(pill);
            block.addOnLayoutChangeListener(this);
        }

        void show(Relation relation, @Nullable TextView style) {
            View block = this.block.get();
            if (block == null) return;
            Context context = block.getContext();
            boolean follows = relation != Relation.DOESNT_FOLLOW_YOU;
            Drawable icon = icon(context, follows ? FOLLOWING_ICON : NOT_FOLLOWING_ICON);
            int color = color(relation, light(context, style));
            pill.set(text(relation), icon, color, style != null ? style.getTypeface() : null);
            // Instagram may have set the block's padding again since; that becomes its own.
            int bottom = block.getPaddingBottom();
            if (bottom != ownBottom + room) {
                ownBottom = bottom;
                block.setPaddingRelative(block.getPaddingStart(), block.getPaddingTop(), block.getPaddingEnd(), ownBottom + room);
            }
            place(block);
        }

        void remove() {
            View block = this.block.get();
            if (block == null) return;
            block.getOverlay().remove(pill);
            block.removeOnLayoutChangeListener(this);
            if (block.getPaddingBottom() == ownBottom + room) {
                block.setPaddingRelative(block.getPaddingStart(), block.getPaddingTop(), block.getPaddingEnd(), ownBottom);
            }
        }

        @Override
        public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            place(view);
        }

        /** Puts the pill at the start of the room under the counts of [block], lined up with them. */
        void place(View block) {
            int width = pill.getIntrinsicWidth();
            int height = pill.getIntrinsicHeight();
            boolean rtl = block.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            pill.setLayoutDirection(block.getLayoutDirection());
            int left = rtl ? block.getWidth() - block.getPaddingEnd() - width : block.getPaddingStart();
            int bottom = block.getHeight() - ownBottom;
            pill.setBounds(left, bottom - height, left + width, bottom);
        }

        @Nullable
        private static Drawable icon(Context context, String name) {
            int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (id == 0) return null;
            try {
                Drawable icon = context.getDrawable(id);
                return icon == null ? null : icon.mutate();
            } catch (RuntimeException missing) {
                return null;
            }
        }
    }

    /** The outlined pill: an icon at the start, then the words, in the relation's color. */
    static final class Pill extends Drawable {
        private final Paint words = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final DisplayMetrics metrics;
        private final RectF shape = new RectF();
        private final int height;
        private final int padding;
        private final int iconSize;
        private final int iconGap;
        String text = "";
        @Nullable Drawable icon;

        Pill(Context context) {
            metrics = context.getResources().getDisplayMetrics();
            words.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, metrics));
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(Math.max(1, dp(1)));
            height = dp(28);
            padding = dp(12);
            iconSize = dp(16);
            iconGap = dp(6);
        }

        int dp(float value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics));
        }

        void set(String text, @Nullable Drawable icon, int color, @Nullable Typeface typeface) {
            this.text = text;
            this.icon = icon;
            words.setColor(color);
            words.setTypeface(typeface);
            outline.setColor(Color.argb(Color.alpha(color) * 2 / 5, Color.red(color), Color.green(color), Color.blue(color)));
            if (icon != null) icon.setTint(color);
            invalidateSelf();
        }

        int color() {
            return words.getColor();
        }

        @Override
        public int getIntrinsicWidth() {
            int iconWidth = icon != null ? iconSize + iconGap : 0;
            return padding * 2 + iconWidth + (int) Math.ceil(words.measureText(text));
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            float inset = outline.getStrokeWidth() / 2;
            shape.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
            float radius = shape.height() / 2;
            canvas.drawRoundRect(shape, radius, radius, outline);

            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int middle = bounds.centerY();
            float textWidth = words.measureText(text);
            int iconStart = rtl ? bounds.right - padding - iconSize : bounds.left + padding;
            if (icon != null) {
                icon.setBounds(iconStart, middle - iconSize / 2, iconStart + iconSize, middle + iconSize / 2);
                icon.draw(canvas);
            }
            int iconWidth = icon != null ? iconSize + iconGap : 0;
            float textStart = rtl ? bounds.right - padding - iconWidth - textWidth : bounds.left + padding + iconWidth;
            Paint.FontMetrics font = words.getFontMetrics();
            canvas.drawText(text, textStart, middle - (font.ascent + font.descent) / 2, words);
        }

        @Override
        public void setAlpha(int alpha) {
            words.setAlpha(alpha);
            outline.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            words.setColorFilter(filter);
            outline.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
