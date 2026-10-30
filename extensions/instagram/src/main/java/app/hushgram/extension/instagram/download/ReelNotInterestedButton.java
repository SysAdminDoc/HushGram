/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A Not interested icon in the column beside a reel, for "Not interested button on reels" (#191).
 *
 * <p>It joins the column the way the Download icon does ({@link ReelDownloadButton}): the patch asks
 * {@link #on} right after Save and its count join the column, and with the switch on hands
 * {@link #litho} the column, the color filter Instagram puts on the column's icons and the size and
 * margin Save was given. The icon goes in before the Download one, so only when that one won't show
 * does this one make up the margin More takes back.
 *
 * <p>A tap runs Instagram's own Not interested. That is a method of the object that binds the
 * Reels viewer's items (the delegate), which the column doesn't hold. The patch has the delegate's
 * constructor report itself with the viewer's config ({@link #remember}), and the column's state
 * hands the same config back, so a tap finds the delegate by that config and calls it with the reel's
 * item and state, as the menu's row does.
 *
 * <p>Every part fails open. With the switch off, while paused, on an ad or a card, or when the
 * delegate can't be found, nothing joins the column and nothing throws into Instagram.
 */
public final class ReelNotInterestedButton {
    private static final String SOURCE = "ReelNotInterestedButton";
    static final String COUNT_REFUSED = "reel not interested button not placed";
    static final String COUNT_NO_HELPER = "reel not interested button skipped, Instagram's helper not found";
    /** Instagram's eye-off glyphs, by name since the numbers change from build to build. */
    static final String[] ICON_NAMES = {"instagram_eye_off_outline_24", "instagram_eye_off_outline_32"};
    /** The square a column glyph is drawn in, which shows it about as big as Comment, Save and Download. */
    static final int GLYPH_DP = 26;
    private static final int ON_MEDIA = 0xFFFFFFFF;
    /** How many viewers' delegates are remembered at once. */
    private static final int REMEMBERED = 8;

    /** Instagram's glyph, looked up once: 0 until then, -1 when this build has none. */
    static volatile int glyph;
    private static volatile boolean logged;

    private ReelNotInterestedButton() {
    }

    /** What a tap reads from the column and runs. Replaced by tests. */
    interface Bridge {
        /** The viewer config the column's state holds. */
        @Nullable
        Object config(Object column);

        /** The state of the reel the column shows. */
        @Nullable
        Object state(Object column);

        /** Runs Instagram's Not interested on the [delegate] for [item] and [state]. */
        void run(Object delegate, Object item, Object state);
    }

    static Bridge bridge = new Bridge() {
        @Override public Object config(Object column) {
            return notInterestedConfig(column);
        }

        @Override public Object state(Object column) {
            return notInterestedState(column);
        }

        @Override public void run(Object delegate, Object item, Object state) {
            notInterestedRun(delegate, item, state);
        }
    };

    /** The viewer config the column's state holds. The patch replaces this body. */
    public static Object notInterestedConfig(Object column) {
        return null;
    }

    /** The state of the reel the column shows. The patch replaces this body. */
    public static Object notInterestedState(Object column) {
        return null;
    }

    /** Instagram's Not interested, called on the [delegate] with the reel's [item] and [state]. The patch replaces this body. */
    public static Object notInterestedRun(Object delegate, Object item, Object state) {
        return null;
    }

    private static final class Pair {
        final WeakReference<Object> config;
        final WeakReference<Object> delegate;

        Pair(Object config, Object delegate) {
            this.config = new WeakReference<>(config);
            this.delegate = new WeakReference<>(delegate);
        }
    }

    private static final List<Pair> remembered = new ArrayList<>();

    /**
     * Called as the delegate that binds the Reels viewer's items is made: remembers it by the viewer
     * [config] it was made with, weakly, by identity. Answers whether it was kept. Never throws.
     */
    public static boolean remember(@Nullable Object config, @Nullable Object delegate) {
        try {
            if (config == null || delegate == null) return false;
            synchronized (remembered) {
                for (Iterator<Pair> each = remembered.iterator(); each.hasNext(); ) {
                    Pair pair = each.next();
                    Object known = pair.config.get();
                    if (known == null || pair.delegate.get() == null || known == config) each.remove();
                }
                remembered.add(new Pair(config, delegate));
                while (remembered.size() > REMEMBERED) remembered.remove(0);
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel not interested remember", failure);
            return false;
        }
    }

    @Nullable
    static Object delegateFor(@Nullable Object config) {
        if (config == null) return null;
        synchronized (remembered) {
            for (Pair pair : remembered) {
                if (pair.config.get() == config) return pair.delegate.get();
            }
        }
        return null;
    }

    /** Whether the icon's own switch is on. Paused answers no. Never throws. */
    public static boolean on() {
        try {
            return enabled();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel not interested button switch", failure);
            return false;
        }
    }

    /**
     * Called as the column beside a reel is composed, right after Save and its count join it: [column]
     * is the column, [filter] the color filter Instagram gives its icons there or null, and [size] and
     * [margin] what Save was given. Answers the icon to add after them, or null to leave the column
     * as it is. Never throws.
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
            Object item = ReelDownloadButton.bridge.item(column);
            if (item == null || !ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item))) return null;
            if (delegateFor(bridge.config(column)) == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NO_HELPER);
                return null;
            }
            Context context = Utils.getContext();
            Drawable drawable = context == null ? null : drawable(context, filter instanceof ColorFilter ? (ColorFilter) filter : null);
            Object icon = null;
            if (drawable != null) {
                // The last icon over More is the one that makes up the margin More takes back.
                long under = aboveMore && !ReelDownloadButton.shows(column)
                        ? ReelDownloadButton.underAboveMore(column, context, margin) : margin;
                icon = ReelDownloadButton.bridge.icon(drawable, L10n.t(context, "Not interested"), new Click(column), size, under);
            }
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_REFUSED);
                return null;
            }
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel not interested button added to a reel's column");
            }
            return icon;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel not interested button", failure);
            return null;
        }
    }

    /**
     * Whether {@link #litho} would add the icon to [column] now: its switch, an ordinary reel and the
     * viewer's delegate. The Block icon (#191) goes in before this one and asks, since only the last
     * icon over More needs the margin More takes back. Never throws.
     */
    static boolean shows(@Nullable Object column) {
        try {
            if (column == null || !enabled() || glyph < 0) return false;
            Object item = ReelDownloadButton.bridge.item(column);
            if (item == null || !ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item))) return false;
            return delegateFor(bridge.config(column)) != null;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Whether the icon shows: its switch. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.REEL_NOT_INTERESTED_BUTTON.get();
    }

    /**
     * A fresh copy of Instagram's glyph, tinted with [filter] when Instagram tints the column's
     * icons, else white like them. Null when the build has no such glyph.
     */
    @Nullable
    static Drawable drawable(Context context, @Nullable ColorFilter filter) {
        Resources resources = context.getResources();
        int known = glyph;
        if (known == 0) {
            known = glyphId(resources, context.getPackageName());
            glyph = known;
        }
        if (known <= 0) return null;
        Drawable drawable;
        try {
            Drawable found = resources.getDrawable(known, context.getTheme());
            drawable = found == null ? null : found.mutate();
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (drawable == null) return null;
        drawable.setColorFilter(filter != null ? filter : new PorterDuffColorFilter(ON_MEDIA, PorterDuff.Mode.SRC_IN));
        return centered(resources, drawable);
    }

    /** [glyph] drawn {@link #GLYPH_DP} square in the middle of the box the column gives it. */
    static Drawable centered(Resources resources, Drawable glyph) {
        return new Centered(glyph, Math.round(GLYPH_DP * resources.getDisplayMetrics().density));
    }

    /**
     * A glyph drawn [side] pixels square in the middle of its bounds. Handed over as they are, Instagram's
     * glyphs fill the whole 44dp box the column gives each icon, about twice the size of Save's. With no
     * size of its own the column hands this the whole box unscaled, and the glyph takes its middle.
     */
    static final class Centered extends Drawable implements Drawable.Callback {
        final Drawable glyph;
        final int side;

        Centered(Drawable glyph, int side) {
            this.glyph = glyph;
            this.side = side;
            glyph.setCallback(this);
        }

        @Override protected void onBoundsChange(@NonNull Rect bounds) {
            int square = Math.max(0, Math.min(side, Math.min(bounds.width(), bounds.height())));
            int left = bounds.left + (bounds.width() - square) / 2;
            int top = bounds.top + (bounds.height() - square) / 2;
            glyph.setBounds(left, top, left + square, top + square);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            glyph.draw(canvas);
        }

        @Override public void setAlpha(int alpha) {
            glyph.setAlpha(alpha);
        }

        @Override public void setColorFilter(@Nullable ColorFilter filter) {
            glyph.setColorFilter(filter);
        }

        @Nullable
        @Override public ColorFilter getColorFilter() {
            return glyph.getColorFilter();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override protected boolean onStateChange(@NonNull int[] state) {
            return glyph.setState(state);
        }

        @Override public boolean isStateful() {
            return glyph.isStateful();
        }

        @Override public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    }

    /** The first of {@link #ICON_NAMES} that [packageName] has, or -1 when it has none of them. */
    static int glyphId(Resources resources, String packageName) {
        return glyphId(resources, packageName, ICON_NAMES);
    }

    /** The first of [names] that [packageName] has, or -1 when it has none of them. */
    static int glyphId(Resources resources, String packageName, String[] names) {
        for (String name : names) {
            int id = resources.getIdentifier(name, "drawable", packageName);
            if (id != 0) return id;
        }
        return -1;
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        glyph = 0;
        logged = false;
        synchronized (remembered) {
            remembered.clear();
        }
    }

    /** A tap on the icon. It keeps the column it was made for and reads the reel from it when tapped. */
    static final class Click implements Function1<Object, Object> {
        final Object column;

        Click(Object column) {
            this.column = column;
        }

        @Override public Object invoke(Object event) {
            tap(column);
            return null;
        }
    }

    /**
     * Runs Instagram's Not interested for the reel [column] shows now. Off or paused, on an ad or a
     * card, or without the delegate, it does nothing. Never throws.
     */
    static void tap(Object column) {
        try {
            if (!enabled()) return;
            Object item = ReelDownloadButton.bridge.item(column);
            if (item == null || !ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item))) return;
            Object delegate = delegateFor(bridge.config(column));
            Object state = delegate == null ? null : bridge.state(column);
            if (delegate == null || state == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NO_HELPER);
                return;
            }
            bridge.run(delegate, item, state);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel not interested button tap", failure);
        }
    }
}
