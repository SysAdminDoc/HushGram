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

import app.hushgram.extension.instagram.reels.HiddenSounds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A sound icon in the column beside a reel, for "Hide reels by sound" (#191).
 *
 * <p>It joins the column the way the Block icon does ({@link ReelBlockButton}), right in front of
 * the column's other icons, so it only makes up the margin More takes back when none of them shows.
 *
 * <p>A tap puts the reel's sound on {@link HiddenSounds}, and the Reels tab's next pages leave out the
 * reels using it. A tap on a reel whose sound is already there takes it off again. Either way a toast
 * says which sound it was.
 *
 * <p>Every part fails open. With the switch off, while paused, on an ad or a card, or on a reel whose
 * sound can't be read, nothing joins the column and nothing throws into Instagram.
 */
public final class ReelSoundButton {
    private static final String SOURCE = "ReelSoundButton";
    static final String COUNT_REFUSED = "reel sound button not placed";
    static final String COUNT_NO_SOUND = "reel sound button skipped, the reel's sound not found";
    /** Instagram's music glyphs, by name since the numbers change from build to build. */
    static final String[] ICON_NAMES = {"instagram_music_outline_24", "instagram_music_pano_outline_24", "instagram_music_filled_24"};
    private static final int ON_MEDIA = 0xFFFFFFFF;

    /** Instagram's glyph, looked up once: 0 until then, -1 when this build has none. */
    static volatile int glyph;
    private static volatile boolean logged;

    private ReelSoundButton() {
    }

    /** What the icon reads from a reel. Replaced by tests. */
    interface Bridge {
        /** The sound of the reel [item] holds, or null when it has none or it can't be read. */
        @Nullable
        HiddenSounds.Sound sound(Object item);
    }

    static Bridge bridge = item -> {
        Object media = ReelDownloadButton.bridge.media(item);
        if (media == null) return null;
        Object owner = InstagramMedia.owner(media);
        return HiddenSounds.soundOf(media, owner == null ? null : InstagramMedia.username(owner));
    };

    /** Whether the icon's own switch is on. Paused answers no. Never throws. */
    public static boolean on() {
        try {
            return enabled();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel sound button switch", failure);
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
            if (bridge.sound(item) == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NO_SOUND);
                return null;
            }
            Context context = Utils.getContext();
            Drawable drawable = context == null ? null : drawable(context, filter instanceof ColorFilter ? (ColorFilter) filter : null);
            Object icon = null;
            if (drawable != null) {
                // The last icon over More is the one that makes up the margin More takes back.
                long under = aboveMore && !ReelBlockButton.shows(column) && !ReelNotInterestedButton.shows(column)
                        && !ReelDownloadButton.shows(column) ? ReelDownloadButton.underAboveMore(column, context, margin) : margin;
                icon = ReelDownloadButton.bridge.icon(drawable, L10n.t(context, "Hide this sound"), new Click(column), size, under);
            }
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_REFUSED);
                return null;
            }
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel sound button added to a reel's column");
            }
            return icon;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel sound button", failure);
            return null;
        }
    }

    /** Whether the icon shows: its switch. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.HIDE_SOUND_REELS.get();
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
     * Hides the sound of the reel [column] shows now, or shows it again when it's hidden already, and
     * says so in a toast. Off or paused, on an ad or a card, or without the sound, it does nothing.
     * Never throws.
     */
    static void tap(Object column) {
        try {
            if (!enabled()) return;
            Object item = ReelDownloadButton.bridge.item(column);
            if (item == null || !ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item))) return;
            HiddenSounds.Sound sound = bridge.sound(item);
            if (sound == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_NO_SOUND);
                return;
            }
            Context context = Utils.getContext();
            String name = L10n.isolate(label(context, sound));
            if (HiddenSounds.hides(sound.id)) {
                HiddenSounds.remove(sound.id);
                Utils.showToastShort(L10n.f(context, "Showing reels with %1$s again", name));
            } else if (HiddenSounds.add(sound)) {
                Utils.showToastShort(L10n.f(context, "Hiding reels with %1$s", name));
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel sound button tap", failure);
        }
    }

    /** What a sound is called in a toast and in settings' list: its title and who made it, as far as known. */
    public static String label(@Nullable Context context, HiddenSounds.Sound sound) {
        boolean title = !sound.title.isEmpty();
        boolean artist = !sound.artist.isEmpty();
        if (title && artist) return L10n.f(context, "%1$s by %2$s", sound.title, sound.artist);
        if (title) return sound.title;
        if (artist) return sound.artist;
        return L10n.t(context, "this sound");
    }
}
