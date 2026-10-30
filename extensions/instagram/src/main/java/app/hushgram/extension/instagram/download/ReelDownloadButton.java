/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.PopupMenu;

import androidx.annotation.Nullable;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.feed.FeedItemKinds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A Download icon in the column beside a reel, under Save, for "Download button on reels" (#178).
 *
 * <p>The column is a component that collects its buttons in a list each time it's composed. Right
 * after Save and its count join that list, the patch asks {@link #on}, and with the switch on hands
 * {@link #litho} the column, the color filter Instagram puts on the column's icons when it skips
 * dark mode, and the size and bottom margin Save was given. What it answers joins the list, so
 * Instagram lays the icon out, mounts it and recycles it like its own buttons. Hide the Save button
 * empties Save's place without moving that call, so the icon then sits where Save would.
 *
 * <p>Save goes over Share or, past the share count, over More. More sets its own top margin to minus
 * 8dp unless the share count shows, which pulls it up into the margin of the button over it, so in
 * that place the patch asks {@link #lithoAboveMore}, which gives the icon that much more margin and
 * keeps the gap under it the column's usual one.
 *
 * <p>The icon goes only on a reel, never on an ad or a card, and only when a tap would find
 * something to save. A tap reads the reel from the column at that moment and starts the save
 * Download in its menu starts ({@link ReelDownload#saveRow}), so the quality, the file name, the
 * toasts and the Send downloads to another app switch are the menu's. A photo with music asks first
 * whether to save a video or the photo, as the menu does with its two rows.
 *
 * <p>Every part fails open. With the switch or Download on reels off, or while paused, nothing
 * joins the column, and a column whose parts can't be read is left as it was.
 */
public final class ReelDownloadButton {
    private static final String SOURCE = "ReelDownloadButton";
    static final String COUNT_REFUSED = "reel button not placed";
    /** The icon over More placed with Save's margin, since More's top margin couldn't be read. */
    static final String COUNT_MORE_UNREAD = "reel button over More kept Save's margin, More's unread";
    /** The icon over More placed with Save's margin, since that margin wasn't in pixels. */
    static final String COUNT_NOT_PIXELS = "reel button over More kept Save's margin, not in pixels";
    /**
     * Instagram's download glyphs, by name since the numbers change from build to build. The 44dp
     * one is drawn for a column like this one, as Save's own 44dp glyph is, so it has Save's weight
     * and room around it. The 24dp one, stretched to Save's size, comes out thicker and larger
     * (#198), so it only stands in when the build lacks the 44dp one.
     */
    static final String[] ICON_NAMES = {"instagram_download_outline_44", "instagram_download_outline_24"};
    /** The kind of an ordinary reel, as the reel's item names it. Ads and cards have other kinds. */
    static final String ORGANIC = "ORGANIC";
    /** The column's icons are white over the video unless Instagram skips dark mode there. */
    private static final int ON_MEDIA = 0xFFFFFFFF;
    private static final int ICON_DP = 24;
    /**
     * How Litho packs a dimension in a long: a plain double is in dp, and a NaN carrying these bits
     * holds pixels in its low 32 bits. Instagram hands the column's margins over in pixels.
     */
    private static final long NAN_BITS = 0x7ff8000000000000L;
    static final long PX_BITS = 0x7ff9000000000000L;

    /** Instagram's download glyph, looked up once: 0 until then, -1 when this build has none. */
    static volatile int glyph;
    private static volatile boolean logged;
    private static volatile boolean moreUnreadLogged;

    private ReelDownloadButton() {
    }

    /** What the column is read through, and how the icon is made, which the patch writes as bridges. Replaced by tests. */
    interface Bridge {
        @Nullable
        Object item(Object column);

        @Nullable
        Object media(Object item);

        /** The item's kind by name, or null when it has no kind of the reel kinds. */
        @Nullable
        String kind(Object item) throws ReflectiveOperationException;

        /** More's top margin in pixels as its builder works it out for this column, 0 when it takes none. */
        int moreTop(Object column, Context context);

        @Nullable
        Object icon(Drawable drawable, CharSequence description, Function1<Object, Object> click, long size, long margin);
    }

    static Bridge bridge = new Bridge() {
        @Override public Object item(Object column) {
            return reelItem(column);
        }

        @Override public Object media(Object item) {
            return itemMedia(item);
        }

        @Override public String kind(Object item) throws ReflectiveOperationException {
            return FeedItemKinds.kindOf(item, ORGANIC, FamilyNames.REEL_DOWNLOAD);
        }

        @Override public int moreTop(Object column, Context context) {
            int dimension = ReelDownloadButton.moreTop(column);
            // Litho reads a dimension resource the same way.
            return dimension == 0 ? 0 : context.getResources().getDimensionPixelSize(dimension);
        }

        @Override public Object icon(Drawable drawable, CharSequence description, Function1<Object, Object> click, long size,
                                     long margin) {
            return lithoIcon(drawable, description, click, size, margin);
        }
    };

    /** What a tap saves, and what shows the choice a photo with music gets. Replaced by tests. */
    interface Actions {
        boolean savable(Object media);

        boolean music(Object media);

        boolean save(@Nullable String row, Object media, @Nullable Activity activity);

        void choose(View anchor, Runnable video, Runnable photo);

        void nothing(Context context);
    }

    static Actions actions = new Actions() {
        @Override public boolean savable(Object media) {
            return ReelDownload.savable(media);
        }

        @Override public boolean music(Object media) {
            return ReelDownload.musicRows(media);
        }

        @Override public boolean save(String row, Object media, Activity activity) {
            return ReelDownload.saveRow(row, media, activity);
        }

        @Override public void choose(View anchor, Runnable video, Runnable photo) {
            PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
            menu.getMenu().add(0, 0, 0, StoryDownload.label(StoryDownload.Choice.VIDEO));
            menu.getMenu().add(0, 1, 1, StoryDownload.label(StoryDownload.Choice.PHOTO));
            menu.setOnMenuItemClickListener(item -> {
                (item.getItemId() == 0 ? video : photo).run();
                return true;
            });
            menu.show();
        }

        @Override public void nothing(Context context) {
            Feedback.show(context.getApplicationContext(), L10n.t(context, "Nothing to download on this post"), false);
        }
    };

    /** The reel's item the column's state holds. The patch replaces this body. */
    public static Object reelItem(Object column) {
        return null;
    }

    /** The Media a reel's item holds. The patch replaces this body. */
    public static Object itemMedia(Object item) {
        return null;
    }

    /**
     * The dimension resource More's builder gives More as its top margin for [column], or 0 while
     * the share count shows and More takes none. The patch replaces this body.
     */
    public static int moreTop(Object column) {
        return 0;
    }

    /**
     * An icon component showing [drawable], [size] wide and tall with [margin] under it as Save has,
     * centered in the column, described as [description], and running [click] on a tap. The patch
     * replaces this body.
     */
    public static Object lithoIcon(Object drawable, CharSequence description, Object click, long size, long margin) {
        return null;
    }

    /** Whether the column gets the icon at all: the switch and Download on reels. Paused answers no. Never throws. */
    public static boolean on() {
        try {
            return enabled();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel download button switch", failure);
            return false;
        }
    }

    /**
     * Called as the column beside a reel is composed, right after Save and its count join it, where
     * Share comes next: [column] is the column, [filter] the color filter Instagram gives its icons
     * there or null, and [size] and [margin] what Save was given. Answers the icon to add after them,
     * or null to leave the column as it is. Never throws.
     */
    @Nullable
    public static Object litho(@Nullable Object column, @Nullable Object filter, long size, long margin) {
        return make(column, filter, size, margin, false);
    }

    /** {@link #litho} where More comes next, making up the margin More takes back. Never throws. */
    @Nullable
    public static Object lithoAboveMore(@Nullable Object column, @Nullable Object filter, long size, long margin) {
        return make(column, filter, size, margin, true);
    }

    @Nullable
    private static Object make(@Nullable Object column, @Nullable Object filter, long size, long margin, boolean aboveMore) {
        try {
            if (column == null || !enabled()) return null;
            HookStatus.invoked(FamilyNames.REEL_DOWNLOAD);
            Object item = bridge.item(column);
            if (item == null || !ORGANIC.equals(bridge.kind(item))) return null;
            Object media = bridge.media(item);
            if (media == null || !actions.savable(media)) return null;
            Context context = Utils.getContext();
            Drawable drawable = context == null ? null : drawable(context, filter instanceof ColorFilter ? (ColorFilter) filter : null);
            long under = aboveMore && drawable != null ? underAboveMore(column, context, margin) : margin;
            Object icon = drawable == null ? null
                    : bridge.icon(drawable, FeedDownloadButton.description(context), new Click(column), size, under);
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_REFUSED);
                return null;
            }
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel download button added to a reel's column");
            }
            return icon;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel download button", failure);
            return null;
        }
    }

    /**
     * Whether {@link #litho} would add the icon to [column] now: the switches, an ordinary reel, and
     * something to save. The Not interested icon (#191) goes in before this one and asks, since only
     * the last icon over More needs the margin More takes back. Never throws.
     */
    static boolean shows(@Nullable Object column) {
        try {
            if (column == null || !enabled()) return false;
            Object item = bridge.item(column);
            if (item == null || !ORGANIC.equals(bridge.kind(item))) return false;
            Object media = bridge.media(item);
            return media != null && actions.savable(media);
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * The icon's bottom margin over More. When More's top can't be read, an Error from the bridge
     * included, or Save's [margin] isn't in pixels, the icon still goes in with Save's margin and
     * the case is counted under its own name, since the button itself works.
     */
    static long underAboveMore(Object column, Context context, long margin) {
        int moreTop;
        try {
            moreTop = bridge.moreTop(column, context);
        } catch (Throwable unreadable) {
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_MORE_UNREAD);
            if (!moreUnreadLogged) {
                moreUnreadLogged = true;
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "More's top margin couldn't be read, so the reel button kept Save's", unreadable);
            }
            return margin;
        }
        if (moreTop != 0 && !inPixels(margin)) {
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NOT_PIXELS);
            return margin;
        }
        return marginAboveMore(margin, moreTop);
    }

    /**
     * The bottom margin that keeps the column's gap between the icon and More under it, when More's
     * top margin is [moreTop] pixels: Save's [margin] less More's top, never below none. A margin
     * that isn't in pixels is left as it is.
     */
    static long marginAboveMore(long margin, int moreTop) {
        if (moreTop == 0 || !inPixels(margin)) return margin;
        int pixels = margin == 0 ? 0 : (int) margin;
        return PX_BITS | (Math.max(0, pixels - moreTop) & 0xFFFFFFFFL);
    }

    /** Whether Litho reads [margin] as pixels: none at all, or a NaN carrying the pixel bits. */
    static boolean inPixels(long margin) {
        return margin == 0 || ((margin & NAN_BITS) == NAN_BITS && (margin & PX_BITS) == PX_BITS);
    }

    /** Whether the icon shows: its switch and Download on reels. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.REEL_DOWNLOAD_BUTTON.get() && Settings.DOWNLOAD_REELS.get();
    }

    /** Whether the icon's own switch is on, whatever Download on reels says. Paused answers no. */
    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.REEL_DOWNLOAD_BUTTON.get();
    }

    /**
     * A fresh copy of Instagram's download glyph, tinted with [filter] when Instagram tints the
     * column's icons, else white like them. HushGram's own glyph stands in when the build has none.
     */
    @Nullable
    static Drawable drawable(Context context, @Nullable ColorFilter filter) {
        Resources resources = context.getResources();
        int known = glyph;
        if (known == 0) {
            known = glyphId(resources, context.getPackageName());
            glyph = known;
        }
        Drawable drawable = null;
        if (known > 0) {
            try {
                Drawable found = resources.getDrawable(known, context.getTheme());
                drawable = found == null ? null : found.mutate();
            } catch (RuntimeException unreadable) {
                // Not found, or it can't be drawn outside Instagram's own views: HushGram's glyph then.
                drawable = null;
            }
        }
        if (drawable == null) {
            drawable = new FeedDownloadButton.Glyph(Math.round(ICON_DP * resources.getDisplayMetrics().density));
        }
        drawable.setColorFilter(filter != null ? filter : new PorterDuffColorFilter(ON_MEDIA, PorterDuff.Mode.SRC_IN));
        return drawable;
    }

    /** The first of {@link #ICON_NAMES} that [packageName] has, or -1 when it has none of them. */
    static int glyphId(Resources resources, String packageName) {
        for (String name : ICON_NAMES) {
            int id = resources.getIdentifier(name, "drawable", packageName);
            if (id != 0) return id;
        }
        return -1;
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        glyph = 0;
        logged = false;
        moreUnreadLogged = false;
    }

    /**
     * A tap on the icon. It keeps the column it was made for and reads the reel from it when tapped,
     * so it never holds a reel's Media itself.
     */
    static final class Click implements Function1<Object, Object> {
        final Object column;

        Click(Object column) {
            this.column = column;
        }

        @Override public Object invoke(Object event) {
            try {
                View anchor = FeedDownloadButton.viewOf(event);
                Activity activity = FeedDownloadButton.activityOf(anchor);
                if (anchor == null && activity != null) anchor = activity.getWindow().getDecorView();
                if (anchor == null) return null;
                tap(anchor, column, activity);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel download button tap", failure);
            }
            return null;
        }
    }

    /**
     * What a tap does with the reel [column] shows now: the menu's Download, or for a photo with
     * music a choice of video or photo first. A reel with nothing to save, or one tapped after
     * Download on reels went off, gets a short note. Off or paused, nothing. Never throws.
     */
    static void tap(View anchor, Object column, @Nullable Activity activity) {
        try {
            Object item = enabled() ? bridge.item(column) : null;
            Object media = item == null ? null : bridge.media(item);
            if (media == null || !actions.savable(media)) {
                if (switchedOn()) actions.nothing(contextOf(anchor, activity));
                return;
            }
            if (actions.music(media)) {
                actions.choose(anchor, () -> save(anchor, ReelDownload.VIDEO_OPTION, media, activity),
                        () -> save(anchor, ReelDownload.PHOTO_OPTION, media, activity));
                return;
            }
            save(anchor, null, media, activity);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel download button tap", failure);
        }
    }

    private static void save(View anchor, @Nullable String row, Object media, @Nullable Activity activity) {
        try {
            if (!actions.save(row, media, activity)) actions.nothing(contextOf(anchor, activity));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel download button save", failure);
        }
    }

    private static Context contextOf(View anchor, @Nullable Activity activity) {
        return activity != null ? activity : anchor.getContext();
    }
}
