/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.reels.ReelClearMode;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A Full screen icon in the column beside a reel, for "Full screen button on reels" (#480).
 *
 * <p>It joins the column the way the speed icon does ({@link ReelSpeedButton}), right in front of the
 * column's other icons, so it only makes up the margin More takes back when none of them shows.
 *
 * <p>A tap does what Full screen in the reel's menu does, which is Instagram's clear mode, through the
 * viewer's own handler ({@link ReelClearMode#fullScreen}). With Keep clear mode on, the next reels
 * stay that way, and Start every reel in clear mode opens every reel that way without a tap.
 *
 * <p>Every part fails open. With the switch off, while paused, on an ad or a card, or without a glyph,
 * nothing joins the column and nothing throws into Instagram.
 */
public final class ReelFullScreenButton {
    private static final String SOURCE = "ReelFullScreenButton";
    static final String COUNT_REFUSED = "reel full screen button not placed";
    /** Instagram's full screen glyphs, by name since the numbers change from build to build. */
    static final String[] ICON_NAMES = {"instagram_fullscreen_outline_24", "instagram_fullscreen_pano_outline_24"};

    /** Instagram's glyph, looked up once: 0 until then, -1 when this build has none. */
    static volatile int glyph;
    private static volatile boolean logged;

    private ReelFullScreenButton() {
    }

    /** Puts a reel in clear mode. {@link #PATCHED} goes to {@link ReelClearMode}; tests stand in. */
    interface Enter {
        boolean fullScreen(Object item);
    }

    static final Enter PATCHED = ReelClearMode::fullScreen;

    static volatile Enter enter = PATCHED;

    /** Whether the icon's own switch is on. Paused answers no. Never throws. */
    public static boolean on() {
        try {
            return enabled();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel full screen button switch", failure);
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
                long under = aboveMore && !ReelSpeedButton.shows(column) && !ReelTextButton.shows(column)
                        && !ReelSoundButton.shows(column) && !ReelBlockButton.shows(column)
                        && !ReelNotInterestedButton.shows(column) && !ReelDownloadButton.shows(column)
                        ? ReelDownloadButton.underAboveMore(column, context, margin) : margin;
                icon = ReelDownloadButton.bridge.icon(drawable, L10n.t(context, "Full screen"), new Click(column), size, under);
            }
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_REFUSED);
                return null;
            }
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel full screen button added to a reel's column");
            }
            return icon;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel full screen button", failure);
            return null;
        }
    }

    /** Whether the icon shows: its switch. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.FULL_SCREEN_BUTTON_ON_REELS.get();
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
        ReelNotInterestedButton.tint(drawable, filter);
        return ReelNotInterestedButton.centered(resources, drawable);
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        glyph = 0;
        logged = false;
        enter = PATCHED;
    }

    /** A tap on the icon: the reel the column shows now goes into clear mode. */
    static final class Click implements Function1<Object, Object> {
        final Object column;

        Click(Object column) {
            this.column = column;
        }

        @Override public Object invoke(Object event) {
            try {
                if (!enabled()) return null;
                Object item = ReelDownloadButton.bridge.item(column);
                if (item == null || !ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item))) return null;
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, "reel full screen button tapped");
                if (!enter.fullScreen(item)) Utils.showToastShort(L10n.t("Full screen didn't open"));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel full screen button tap", failure);
            }
            return null;
        }
    }
}
