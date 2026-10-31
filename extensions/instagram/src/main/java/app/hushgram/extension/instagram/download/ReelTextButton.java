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

import androidx.annotation.Nullable;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A Lens icon in the column beside a reel, for "Copy text button on reels" (#303).
 *
 * <p>It joins the column the way the sound icon does ({@link ReelSoundButton}), right in front of the
 * column's other icons, so it only makes up the margin More takes back when none of them shows.
 *
 * <p>A tap sends what the reel's page shows to Google Lens ({@link TextGrab}), where the words in the
 * video, its stickers and its caption can be selected and copied.
 *
 * <p>Every part fails open. With the switch off, while paused, on an ad or a card, or without a glyph,
 * nothing joins the column and nothing throws into Instagram.
 */
public final class ReelTextButton {
    private static final String SOURCE = "ReelTextButton";
    static final String COUNT_REFUSED = "reel text button not placed";
    /** Instagram's Lens and search glyphs, by name since the numbers change from build to build. */
    static final String[] ICON_NAMES = {"instagram_visual_search_outline_24", "instagram_visual_search_pano_outline_24",
            "instagram_text_bg_outline_24", "instagram_search_outline_24"};
    private static final int ON_MEDIA = 0xFFFFFFFF;

    /** Instagram's glyph, looked up once: 0 until then, -1 when this build has none. */
    static volatile int glyph;
    private static volatile boolean logged;

    private ReelTextButton() {
    }

    /** Hands what a tap found to {@link TextGrab}. Replaced by tests. */
    interface Grabber {
        void grab(@Nullable View page, @Nullable Activity activity);
    }

    static Grabber grabber = (page, activity) -> TextGrab.grab(page, activity, FamilyNames.REEL_DOWNLOAD);

    /** Whether the icon's own switch is on. Paused answers no. Never throws. */
    public static boolean on() {
        try {
            return enabled();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel text button switch", failure);
            return false;
        }
    }

    /**
     * Called as the column beside a reel is composed, in front of its other icons: [column] is the
     * column, [filter] the color filter Instagram gives its icons there or null, and [size] and
     * [margin] what Save was given. Answers the icon to add, or null to leave the column as it is.
     * Never throws.
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
            Context context = Utils.getContext();
            Drawable drawable = context == null ? null : drawable(context, filter instanceof ColorFilter ? (ColorFilter) filter : null);
            Object icon = null;
            if (drawable != null) {
                // The last icon over More is the one that makes up the margin More takes back.
                long under = aboveMore && !ReelSoundButton.shows(column) && !ReelBlockButton.shows(column)
                        && !ReelNotInterestedButton.shows(column) && !ReelDownloadButton.shows(column)
                        ? ReelDownloadButton.underAboveMore(column, context, margin) : margin;
                icon = ReelDownloadButton.bridge.icon(drawable, L10n.t(context, "Copy text"), new Click(), size, under);
            }
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_REFUSED);
                return null;
            }
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel text button added to a reel's column");
            }
            return icon;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel text button", failure);
            return null;
        }
    }

    /** Whether the icon shows: its switch. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.TEXT_BUTTON_ON_REELS.get();
    }

    /**
     * A fresh copy of Instagram's glyph, tinted with [filter] when Instagram tints the column's
     * icons, else white like them. Null when the build has no such glyph.
     */
    @Nullable
    public static Drawable drawable(Context context, @Nullable ColorFilter filter) {
        Resources resources = context.getResources();
        int known = glyph;
        if (known == 0) {
            known = ReelNotInterestedButton.glyphId(resources, context.getPackageName(), ICON_NAMES);
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
        return ReelNotInterestedButton.centered(resources, drawable);
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        glyph = 0;
        logged = false;
    }

    /** A tap on the icon: the page under it goes to Lens, as it shows when tapped. */
    static final class Click implements Function1<Object, Object> {
        @Override public Object invoke(Object event) {
            try {
                if (!enabled()) return null;
                View anchor = FeedDownloadButton.viewOf(event);
                Activity activity = FeedDownloadButton.activityOf(anchor);
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, "reel text button tapped");
                grabber.grab(TextGrab.pageOf(anchor), activity);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel text button tap", failure);
            }
            return null;
        }
    }
}
