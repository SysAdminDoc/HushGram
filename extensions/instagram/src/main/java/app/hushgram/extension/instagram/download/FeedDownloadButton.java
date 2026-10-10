/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A Download icon in the action row of a feed post, just left of Save, for "Download button on feed
 * posts" (#97).
 *
 * <p>Instagram binds each post's action row in one method that holds the row's holder, the post's
 * feed state and the post. The patch calls {@link #bind} first thing in it with the holder's Save
 * button, the post, the feed state that knows which carousel page is showing, and the activity.
 * Save's parent is the row that holds it, so the icon is added there beside it: before it in a
 * linear row, to its start in a relative one, and shifted in by Save's width in any other. It takes
 * Save's size, padding and scale type, and the row's primary icon color, so it reads as one of
 * Instagram's own. Nothing sits in the way of the row's other buttons, so hiding Repost or Comments
 * moves nothing of it.
 *
 * <p>A tap saves through {@link VideoDownload#save}, so the file name, the account folder, the
 * quality and the Send downloads to another app switch are the ones the post's menu uses. A carousel
 * asks first: Download saves the page on screen, as its menu row does, and Save all saves every
 * page, as its other row does.
 *
 * <p>Every part fails open. With the switch off, or while paused, the icon is hidden, and a row
 * that can't take it is left as it was.
 */
public final class FeedDownloadButton {
    private static final String SOURCE = "FeedDownloadButton";
    private static final String COUNT_PLACED = "feed button placed";
    private static final String COUNT_REFUSED = "feed button not placed";
    /** The theme attribute Instagram colors its action-row icons with; looked up by name since the numbers change. */
    private static final String ICON_COLOR_ATTR = "igds_color_primary_icon";
    private static final int ICON_DP = 24;
    private static final int GAP_DP = 4;

    private static volatile boolean logged;

    /** What a tap saves, and what shows the carousel choice. Replaced by tests. */
    interface Actions {
        List<?> pages(Object post);

        boolean save(Object post, Object itemState, Activity activity);

        void saveAll(Object post, Activity activity);

        void choose(View anchor, Runnable page, Runnable all);

        void nothing(Context context);
    }

    static Actions actions = new Actions() {
        @Override public List<?> pages(Object post) {
            return post == null ? null : InstagramMedia.carouselMedia(post);
        }

        @Override public boolean save(Object post, Object itemState, Activity activity) {
            return VideoDownload.save(post, itemState, activity);
        }

        @Override public void saveAll(Object post, Activity activity) {
            VideoDownload.saveAll(post, activity);
        }

        @Override public void choose(View anchor, Runnable page, Runnable all) {
            PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
            menu.getMenu().add(0, 0, 0, L10n.t("Download"));
            menu.getMenu().add(0, 1, 1, L10n.t("Save all"));
            menu.setOnMenuItemClickListener(item -> {
                (item.getItemId() == 0 ? page : all).run();
                return true;
            });
            menu.show();
        }

        @Override public void nothing(Context context) {
            Feedback.show(context.getApplicationContext(), L10n.t(context, "Nothing to download on this post"), false);
        }
    };

    private FeedDownloadButton() {
    }

    /** The button's tag, and what a tap saves: replaced on every bind, since Instagram reuses a row for another post. */
    private static final class Target {
        Object post;
        Object itemState;
        WeakReference<Activity> activity = new WeakReference<>(null);
    }

    /**
     * Called first thing where Instagram binds a post's action row, with [save], the row's Save
     * button, [post], [itemState], the feed state that holds the carousel page on screen, and the
     * [activity] the row belongs to. Never throws.
     */
    public static void bind(@Nullable View save, @Nullable Object post, @Nullable Object itemState, @Nullable Activity activity) {
        try {
            if (save == null) return;
            ViewParent parent = save.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup row = (ViewGroup) parent;
            ImageView button = existing(row);
            if (!enabled()) {
                if (button != null && button.getVisibility() != View.GONE) button.setVisibility(View.GONE);
                return;
            }
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);
            if (button == null) {
                button = make(save);
                if (!place(row, save, button)) {
                    HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, COUNT_REFUSED);
                    return;
                }
                HookStatus.counted(FamilyNames.VIDEO_DOWNLOAD, COUNT_PLACED);
                if (!logged) {
                    logged = true;
                    final String where = row.getClass().getName();
                    Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "feed download button placed in " + where);
                }
            }
            Target target = (Target) button.getTag();
            target.post = post;
            target.itemState = itemState;
            target.activity = new WeakReference<>(activity);
            ((Glyph) button.getDrawable()).color = iconColor(button.getContext());
            button.setVisibility(View.VISIBLE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed download button", failure);
        }
    }

    /** Whether the button shows: its switch, and something a tap could save. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.FEED_DOWNLOAD_BUTTON.get()
                && (Settings.DOWNLOAD_VIDEOS.get() || Settings.DOWNLOAD_PHOTOS.get());
    }

    @Nullable
    static ImageView existing(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && child.getTag() instanceof Target) return (ImageView) child;
        }
        return null;
    }

    private static ImageView make(View save) {
        Context context = save.getContext();
        ImageView button = new ImageView(context);
        button.setImageDrawable(new Glyph(dp(context, ICON_DP)));
        button.setScaleType(save instanceof ImageView ? ((ImageView) save).getScaleType() : ImageView.ScaleType.CENTER);
        button.setPaddingRelative(save.getPaddingStart(), save.getPaddingTop(), save.getPaddingEnd(), save.getPaddingBottom());
        button.setContentDescription(L10n.t(context, "Download"));
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setTag(new Target());
        button.setOnClickListener(FeedDownloadButton::tap);
        return button;
    }

    /**
     * Adds [button] to [row] just before [save], in the way the row lays its children out. Answers
     * whether it went in; a row of a kind that isn't known gets the icon shifted in from the end by
     * Save's width, which holds for a frame or a constraint row anchored at its end.
     */
    static boolean place(ViewGroup row, View save, ImageView button) {
        Context context = save.getContext();
        ViewGroup.LayoutParams base = save.getLayoutParams();
        int width = sized(base == null ? 0 : base.width);
        int height = sized(base == null ? 0 : base.height);
        if (row instanceof LinearLayout) {
            LinearLayout.LayoutParams made = new LinearLayout.LayoutParams(width, height);
            if (base instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams from = (LinearLayout.LayoutParams) base;
                made.gravity = from.gravity;
                made.topMargin = from.topMargin;
                made.bottomMargin = from.bottomMargin;
            } else {
                made.gravity = Gravity.CENTER_VERTICAL;
            }
            row.addView(button, row.indexOfChild(save), made);
            return true;
        }
        if (row instanceof RelativeLayout) {
            RelativeLayout.LayoutParams made = new RelativeLayout.LayoutParams(width, height);
            if (base instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams from = (RelativeLayout.LayoutParams) base;
                // Only the rules that place it up and down: the horizontal ones are Save's own.
                int[] rules = from.getRules();
                for (int verb : new int[]{RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.ALIGN_PARENT_BOTTOM,
                        RelativeLayout.CENTER_VERTICAL, RelativeLayout.ALIGN_TOP, RelativeLayout.ALIGN_BOTTOM,
                        RelativeLayout.ALIGN_BASELINE, RelativeLayout.ABOVE, RelativeLayout.BELOW}) {
                    if (rules[verb] != 0) made.addRule(verb, rules[verb]);
                }
                made.topMargin = from.topMargin;
                made.bottomMargin = from.bottomMargin;
            } else {
                made.addRule(RelativeLayout.CENTER_VERTICAL);
            }
            if (save.getId() == View.NO_ID) save.setId(View.generateViewId());
            made.addRule(RelativeLayout.START_OF, save.getId());
            row.addView(button, made);
            return true;
        }
        if (row instanceof FrameLayout || base instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams from = base instanceof ViewGroup.MarginLayoutParams
                    ? (ViewGroup.MarginLayoutParams) base : null;
            ViewGroup.LayoutParams made = shifted(row, base, width, height, from, dp(context, GAP_DP));
            if (made == null) return false;
            row.addView(button, made);
            return true;
        }
        return false;
    }

    /** A copy of Save's params, moved in from the end by Save's own width, its end margin and a gap. */
    @Nullable
    private static ViewGroup.LayoutParams shifted(ViewGroup row, ViewGroup.LayoutParams base, int width, int height,
                                                  @Nullable ViewGroup.MarginLayoutParams from, int gap) {
        try {
            ViewGroup.LayoutParams made;
            try {
                made = base.getClass().getConstructor(base.getClass()).newInstance(base);
            } catch (ReflectiveOperationException copyless) {
                made = new ViewGroup.MarginLayoutParams(width, height);
            }
            made.width = width;
            made.height = height;
            if (!(made instanceof ViewGroup.MarginLayoutParams)) return null;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) made;
            int saved = width == ViewGroup.LayoutParams.WRAP_CONTENT ? dp(row.getContext(), ICON_DP) : width;
            margins.setMarginEnd((from == null ? 0 : from.getMarginEnd()) + saved + gap);
            return made;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** A size Save asked for, or wrap content for a stretch or a weight, which this icon doesn't take. */
    private static int sized(int size) {
        return size > 0 ? size : ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private static void tap(View button) {
        try {
            if (!(button.getTag() instanceof Target)) return;
            Target target = (Target) button.getTag();
            tap(button, target.post, target.itemState, target.activity.get());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed download button tap", failure);
        }
    }

    /** What a tap does with [post]: save it, or on a carousel ask which. Never throws. */
    static void tap(View anchor, Object post, Object itemState, @Nullable Activity activity) {
        try {
            if (!enabled()) return;
            List<?> pages = actions.pages(post);
            if (pages != null && pages.size() > 1) {
                actions.choose(anchor, () -> saveOne(anchor, post, itemState, activity), () -> saveEvery(anchor, post, activity));
                return;
            }
            saveOne(anchor, post, itemState, activity);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "feed download button tap", failure);
        }
    }

    private static void saveOne(View anchor, Object post, Object itemState, @Nullable Activity activity) {
        if (post == null || !actions.save(post, itemState, activity)) actions.nothing(contextOf(anchor, activity));
    }

    private static void saveEvery(View anchor, Object post, @Nullable Activity activity) {
        if (post == null) {
            actions.nothing(contextOf(anchor, activity));
            return;
        }
        actions.saveAll(post, activity);
    }

    private static Context contextOf(View anchor, @Nullable Activity activity) {
        return activity != null ? activity : anchor.getContext();
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /** Instagram's primary icon color for [context]'s theme, then the theme's own text color, then black or white by mode. */
    static int iconColor(Context context) {
        Resources resources = context.getResources();
        int attr = resources.getIdentifier(ICON_COLOR_ATTR, "attr", context.getPackageName());
        Integer found = attr == 0 ? null : themeColor(context, attr);
        if (found == null) found = themeColor(context, android.R.attr.textColorPrimary);
        if (found != null) return found;
        boolean night = (resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xFFFFFFFF : 0xFF262626;
    }

    @Nullable
    private static Integer themeColor(Context context, int attr) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return null;
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) return value.data;
        if (value.resourceId == 0) return null;
        try {
            return context.getResources().getColorStateList(value.resourceId, context.getTheme()).getDefaultColor();
        } catch (Resources.NotFoundException notAColor) {
            return null;
        }
    }

    /** A line-style download glyph, an arrow into a tray, drawn on a 24 unit grid like Instagram's own icons. */
    static final class Glyph extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        int color = 0xFF262626;

        Glyph(int size) {
            this.size = size;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float unit = Math.min(bounds.width(), bounds.height()) / 24f;
            if (unit <= 0) return;
            paint.setColor(color);
            paint.setStrokeWidth(2f * unit);
            path.rewind();
            path.moveTo(12f * unit, 4f * unit);
            path.lineTo(12f * unit, 15f * unit);
            path.moveTo(7.5f * unit, 10.5f * unit);
            path.lineTo(12f * unit, 15f * unit);
            path.lineTo(16.5f * unit, 10.5f * unit);
            path.moveTo(4.5f * unit, 15.5f * unit);
            path.lineTo(4.5f * unit, 19.5f * unit);
            path.lineTo(19.5f * unit, 19.5f * unit);
            path.lineTo(19.5f * unit, 15.5f * unit);
            int save = canvas.save();
            canvas.translate(bounds.left + (bounds.width() - 24f * unit) / 2f, bounds.top + (bounds.height() - 24f * unit) / 2f);
            canvas.drawPath(path, paint);
            canvas.restoreToCount(save);
        }

        @Override public int getIntrinsicWidth() {
            return size;
        }

        @Override public int getIntrinsicHeight() {
            return size;
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
