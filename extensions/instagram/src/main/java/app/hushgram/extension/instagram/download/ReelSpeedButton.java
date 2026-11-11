/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

import androidx.annotation.Nullable;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.reels.ReelSpeed;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A speed icon in the column beside a reel, for "Speed button on reels" (#480).
 *
 * <p>It joins the column the way the Lens icon does ({@link ReelTextButton}), right in front of the
 * column's other icons, so it only makes up the margin More takes back when none of them shows.
 *
 * <p>A tap opens a short menu of speeds, the one playing checked, and a pick goes on the reel on screen
 * through Keep the reel speed's player setter ({@link ReelSpeed#choose}). With that switch on the
 * next reels play at it too, as after Instagram's own 2x lock.
 *
 * <p>Every part fails open. With the switch off, while paused, on an ad or a card, or without a glyph,
 * nothing joins the column and nothing throws into Instagram.
 */
public final class ReelSpeedButton {
    private static final String SOURCE = "ReelSpeedButton";
    static final String COUNT_REFUSED = "reel speed button not placed";
    /** Instagram's speed glyphs, by name since the numbers change from build to build, then its timer. */
    static final String[] ICON_NAMES = {"basel_icons_svg_speed", "exo_styled_controls_speed",
            "instagram_timer_outline_24", "instagram_timer_pano_outline_24"};
    /** The speeds offered, in the order the menu lists them. 2.5x is for speech still clear past 2x (#551). */
    static final float[] SPEEDS = {0.5f, 1f, 1.5f, 1.75f, 2f, 2.5f, 3f};

    /** Instagram's glyph, looked up once: 0 until then, -1 when this build has none. */
    static volatile int glyph;
    private static volatile boolean logged;

    private ReelSpeedButton() {
    }

    /** What a tap reads and sets. {@link #PATCHED} goes to {@link ReelSpeed}; tests stand in. */
    interface Speed {
        float playing();

        boolean choose(float speed);
    }

    static final Speed PATCHED = new Speed() {
        @Override public float playing() {
            return ReelSpeed.playing();
        }

        @Override public boolean choose(float speed) {
            return ReelSpeed.choose(speed);
        }
    };

    static volatile Speed speed = PATCHED;

    /** Shows the speeds by [anchor], [playing] checked, and hands a pick to [chosen]. Replaced by tests. */
    interface Menus {
        void show(View anchor, float playing, Chosen chosen);
    }

    interface Chosen {
        void chose(float speed);
    }

    static Menus menus = (anchor, playing, chosen) -> {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        Menu items = menu.getMenu();
        for (int i = 0; i < SPEEDS.length; i++) {
            items.add(0, i, i, label(SPEEDS[i])).setChecked(same(SPEEDS[i], playing));
        }
        items.setGroupCheckable(0, true, true);
        menu.setOnMenuItemClickListener(item -> {
            int at = item.getItemId();
            if (at >= 0 && at < SPEEDS.length) chosen.chose(SPEEDS[at]);
            return true;
        });
        menu.show();
    };

    /** Shows the speeds as a list over [activity], [playing] checked, and hands a pick to [chosen]. Replaced by tests. */
    interface Lists {
        void show(Activity activity, float playing, Chosen chosen);
    }

    static final Lists LISTS = (activity, playing, chosen) -> {
        CharSequence[] labels = new CharSequence[SPEEDS.length];
        int checked = -1;
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = label(SPEEDS[i]);
            if (checked < 0 && same(SPEEDS[i], playing)) checked = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(L10n.t(activity, "Playback speed"))
                .setSingleChoiceItems(labels, checked, (shown, which) -> {
                    shown.dismiss();
                    if (which >= 0 && which < SPEEDS.length) chosen.chose(SPEEDS[which]);
                })
                .setNegativeButton(L10n.t(activity, "Cancel"), null)
                .create();
        dialog.show();
        ScreenColors.dialog(dialog);
    };

    static volatile Lists lists = LISTS;

    /**
     * Whether a reel's three-dot menu gets Playback speed (#553): its own switch, with Keep the reel
     * speed in the build, since a pick goes through that patch's player setter. Paused answers no.
     * Never throws.
     */
    public static boolean inMenu() {
        try {
            return Utils.settingsReady() && Settings.SPEED_IN_REEL_MENU.get() && PatchFamily.KEEP_REEL_SPEED.inBuild();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu speed switch", failure);
            return false;
        }
    }

    /**
     * A tap on Playback speed in a reel's three-dot menu: the speeds as a list over [activity], the
     * one playing checked, and a pick goes on the reel on screen as the icon's does. Never throws.
     */
    static void fromMenu(@Nullable Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || !inMenu()) return;
            HookStatus.counted(FamilyNames.REEL_DOWNLOAD, "reel menu speed tapped");
            lists.show(activity, speed.playing(), picked -> pickFromMenu(activity, picked));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu speed", failure);
        }
    }

    /** A speed picked from the menu's list: on the reel on screen, or a toast when it didn't take. */
    private static void pickFromMenu(Context context, float picked) {
        try {
            if (!inMenu()) return;
            if (!speed.choose(picked)) Utils.showToastShort(L10n.t(context, "Speed didn't change"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel menu speed pick", failure);
        }
    }

    /** Whether the icon's own switch is on. Paused answers no. Never throws. */
    public static boolean on() {
        try {
            return enabled();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel speed button switch", failure);
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
                long under = aboveMore && !ReelTextButton.shows(column) && !ReelSoundButton.shows(column)
                        && !ReelBlockButton.shows(column) && !ReelNotInterestedButton.shows(column)
                        && !ReelDownloadButton.shows(column)
                        ? ReelDownloadButton.underAboveMore(column, context, margin) : margin;
                icon = ReelDownloadButton.bridge.icon(drawable, L10n.t(context, "Playback speed"), new Click(), size, under);
            }
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_REFUSED);
                return null;
            }
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel speed button added to a reel's column");
            }
            return icon;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel speed button", failure);
            return null;
        }
    }

    /** Whether the icon shows: its switch. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.SPEED_BUTTON_ON_REELS.get();
    }

    /** Whether the icon shows in [column], so an icon in front of it leaves More's margin to it. Never throws. */
    static boolean shows(@Nullable Object column) {
        try {
            if (column == null || !enabled() || glyph < 0) return false;
            Object item = ReelDownloadButton.bridge.item(column);
            return item != null && ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item));
        } catch (Throwable failure) {
            return false;
        }
    }

    /** A speed as the menu shows it: 0.5x, 1x, 1.75x. */
    static String label(float speed) {
        return (speed == (int) speed ? String.valueOf((int) speed) : String.valueOf(speed)) + "x";
    }

    private static boolean same(float a, float b) {
        return Math.abs(a - b) < 0.01f;
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
            known = ReelNotInterestedButton.glyphId(resources, Utils.resourcePackage(context), ICON_NAMES);
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
        speed = PATCHED;
        lists = LISTS;
    }

    /** A tap on the icon: the speeds, and a pick goes on the reel on screen. */
    static final class Click implements Function1<Object, Object> {
        @Override public Object invoke(Object event) {
            try {
                if (!enabled()) return null;
                View anchor = FeedDownloadButton.viewOf(event);
                if (anchor == null) return null;
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, "reel speed button tapped");
                menus.show(anchor, speed.playing(), picked -> pick(anchor, picked));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel speed button tap", failure);
            }
            return null;
        }
    }

    /** A speed picked from the list: on the reel on screen, or a toast when it didn't take. */
    private static void pick(View anchor, float picked) {
        try {
            if (!enabled()) return;
            if (!speed.choose(picked)) Utils.showToastShort(L10n.t(anchor.getContext(), "Speed didn't change"));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel speed pick", failure);
        }
    }
}
