/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

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
 * A Block icon in the column beside a reel, for "Block button on reels" (#191).
 *
 * <p>It joins the column the way the Not interested icon does ({@link ReelNotInterestedButton}),
 * right in front of that one's block, so it only makes up the margin More takes back when neither
 * the Not interested icon nor the Download one shows.
 *
 * <p>A tap opens Instagram's own block sheet for the reel's owner, with Instagram's own confirm in
 * it, so nothing is blocked by the tap alone. Instagram's helper for that sheet wants the screen's
 * activity and the signed-in session, which the delegate that binds the Reels viewer's items holds.
 * The Not interested icon already finds that delegate by the viewer's config, and this one borrows it.
 *
 * <p>Every part fails open. With the switch off, while paused, on an ad or a card, on a reel of the
 * account in front, or when the delegate or the owner can't be found, nothing joins the column and
 * nothing throws into Instagram.
 */
public final class ReelBlockButton {
    private static final String SOURCE = "ReelBlockButton";
    static final String COUNT_REFUSED = "reel block button not placed";
    static final String COUNT_NO_HELPER = "reel block button skipped, Instagram's helper not found";
    static final String COUNT_NO_OWNER = "reel block button skipped, the reel's owner not found";
    /** Instagram's block glyphs, by name since the numbers change from build to build. */
    static final String[] ICON_NAMES = {"instagram_circle_block_outline_24", "instagram_circle_block_filled_24"};
    /** The Reels tab's module name, which Instagram's own block sheet is opened with. */
    static final String MODULE = "clips_viewer_clips_tab";
    private static final int ON_MEDIA = 0xFFFFFFFF;

    /** Instagram's glyph, looked up once: 0 until then, -1 when this build has none. */
    static volatile int glyph;
    private static volatile boolean logged;

    private ReelBlockButton() {
    }

    /** What the icon reads from a reel and runs. Replaced by tests. */
    interface Bridge {
        /** Who posted the reel [item] holds, Instagram's User, or null. */
        @Nullable
        Object owner(Object item);

        /** The id of that owner, or null. */
        @Nullable
        String ownerId(Object item);

        /** Opens Instagram's own block sheet for [owner] with the [delegate]'s activity and session. */
        void block(Object delegate, Object owner, String module, String ownerId);
    }

    static Bridge bridge = new Bridge() {
        @Override public Object owner(Object item) {
            Object media = ReelDownloadButton.bridge.media(item);
            return media == null ? null : InstagramMedia.owner(media);
        }

        @Override public String ownerId(Object item) {
            Object media = ReelDownloadButton.bridge.media(item);
            return media == null ? null : ownerOf(InstagramMedia.mediaId(media));
        }

        @Override public void block(Object delegate, Object owner, String module, String ownerId) {
            blockRun(delegate, owner, module, ownerId);
        }
    };

    /**
     * Instagram's own block sheet for [owner], opened with the [delegate]'s activity and session and
     * the Reels tab's [module]. The patch replaces this body.
     */
    public static Object blockRun(Object delegate, Object owner, String module, String ownerId) {
        return null;
    }

    /** The owner's part of a media id ({@code <media pk>_<owner's pk>}), or null when it has none. */
    @Nullable
    static String ownerOf(@Nullable String mediaId) {
        if (mediaId == null) return null;
        int at = mediaId.lastIndexOf('_');
        String id = at < 0 ? "" : mediaId.substring(at + 1).trim();
        return id.isEmpty() ? null : id;
    }

    /** Whether the icon's own switch is on. Paused answers no. Never throws. */
    public static boolean on() {
        try {
            return enabled();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel block button switch", failure);
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
            if (ReelNotInterestedButton.delegateFor(ReelNotInterestedButton.bridge.config(column)) == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NO_HELPER);
                return null;
            }
            String id = bridge.ownerId(item);
            if (id == null || bridge.owner(item) == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NO_OWNER);
                return null;
            }
            if (yours(id)) return null;
            Context context = Utils.getContext();
            Drawable drawable = context == null ? null : drawable(context, filter instanceof ColorFilter ? (ColorFilter) filter : null);
            Object icon = null;
            if (drawable != null) {
                // The last icon over More is the one that makes up the margin More takes back.
                long under = aboveMore && !ReelNotInterestedButton.shows(column) && !ReelDownloadButton.shows(column)
                        ? ReelDownloadButton.underAboveMore(column, context, margin) : margin;
                icon = ReelDownloadButton.bridge.icon(drawable, L10n.t(context, "Block"), new Click(column), size, under);
            }
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_REFUSED);
                return null;
            }
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel block button added to a reel's column");
            }
            return icon;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel block button", failure);
            return null;
        }
    }

    /**
     * Whether the icon shows on the reel [column] holds, which an icon over it asks to tell whether
     * it's the last one over More. Never throws.
     */
    static boolean shows(@Nullable Object column) {
        try {
            if (column == null || !enabled() || glyph < 0) return false;
            Object item = ReelDownloadButton.bridge.item(column);
            if (item == null || !ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item))) return false;
            if (ReelNotInterestedButton.delegateFor(ReelNotInterestedButton.bridge.config(column)) == null) return false;
            String id = bridge.ownerId(item);
            return id != null && bridge.owner(item) != null && !yours(id);
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Whether [ownerId] is the account in front, whose own reels get no Block icon. */
    private static boolean yours(String ownerId) {
        String mine = DownloadHistory.accounts.get();
        return mine != null && ownerId.equals(mine.trim());
    }

    /** Whether the icon shows: its switch. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.REEL_BLOCK_BUTTON.get();
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
     * Opens Instagram's block sheet for the owner of the reel [column] shows now. Off or paused, on an
     * ad or a card, on a reel of the account in front, or without the delegate or the owner, it does
     * nothing. Never throws.
     */
    static void tap(Object column) {
        try {
            if (!enabled()) return;
            Object item = ReelDownloadButton.bridge.item(column);
            if (item == null || !ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item))) return;
            Object delegate = ReelNotInterestedButton.delegateFor(ReelNotInterestedButton.bridge.config(column));
            if (delegate == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NO_HELPER);
                return;
            }
            Object owner = bridge.owner(item);
            String id = bridge.ownerId(item);
            if (owner == null || id == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NO_OWNER);
                return;
            }
            if (yours(id)) return;
            bridge.block(delegate, owner, MODULE, id);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel block button tap", failure);
        }
    }
}
