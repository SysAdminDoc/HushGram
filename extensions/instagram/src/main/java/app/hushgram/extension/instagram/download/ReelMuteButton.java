/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.reels.ReelTapAndVolume;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * A speaker icon in the column beside a reel, for "Mute button on reels" (#608), and the same speaker
 * beside clear mode's exit button with "Mute button in clear mode" on.
 *
 * <p>It joins the column the way the Full screen icon does ({@link ReelFullScreenButton}), right in
 * front of the column's other icons, so it only makes up the margin More takes back when none of them
 * shows. In clear mode the patch hands over the row that holds the exit button, and the speaker goes
 * in first, to the exit button's left, in a dark circle like it.
 *
 * <p>A tap turns the sound of the reel on screen off or on through the Reels controller's own audio
 * toggle ({@link ReelTapAndVolume#toggle}), so the reel keeps playing. The glyph is Instagram's sound
 * on or sound off one, read from that controller each time it's drawn, and every speaker is drawn
 * again when a reel starts or its sound is toggled ({@link #refresh}).
 *
 * <p>Every part fails open. With the switch off, while paused, without Control taps and volume on
 * Reels in the build, on an ad or a card, or without glyphs, nothing joins the column or clear mode
 * and nothing throws into Instagram.
 */
public final class ReelMuteButton {
    private static final String SOURCE = "ReelMuteButton";
    static final String COUNT_REFUSED = "reel mute button not placed";
    static final String COUNT_CLEAR_MODE_REFUSED = "reel mute button not placed in clear mode";
    /** Instagram's speaker glyphs, by name since the numbers change from build to build. */
    static final String[] SOUND_ON_NAMES = {"basel_icons_svg_sound_on", "instagram_volume_filled_24"};
    static final String[] SOUND_OFF_NAMES = {"basel_icons_svg_sound_off", "instagram_volume_off_filled_24"};
    /** Clear mode's exit button circle, which the speaker's circle matches, in dp. */
    static final int CIRCLE_DP = 22;
    /** The speaker's box in clear mode, wider than its circle so the two circles keep a gap, in dp. */
    static final int CLEAR_MODE_BOX_DP = 34;
    /** The glyph inside that circle, in dp. */
    static final int CLEAR_MODE_GLYPH_DP = 14;
    /** The dark see-through fill of the circle. */
    static final int CIRCLE_FILL = 0x66000000;
    /** How many speakers are remembered to draw again, the newest kept. */
    static final int REMEMBERED = 8;

    /** Instagram's glyphs, looked up once: 0 until then, -1 when this build has none. */
    static volatile int glyphOn;
    static volatile int glyphOff;
    /** Whether Control taps and volume on Reels is in the build: 0 until asked, 1 yes, -1 no. */
    static volatile int tapAndVolume;
    private static volatile boolean logged;
    private static volatile boolean clearModeLogged;
    private static volatile boolean redrawPosted;
    private static final ArrayDeque<WeakReference<Speaker>> speakers = new ArrayDeque<>();

    private ReelMuteButton() {
    }

    /** The reel on screen's sound. {@link #PATCHED} goes to {@link ReelTapAndVolume}; tests stand in. */
    interface Audio {
        /** 1 on, 0 off, -1 when it can't be told. */
        int soundOn();

        /** Turns it off or on. False when nothing could be toggled. */
        boolean toggle();
    }

    static final Audio PATCHED = new Audio() {
        @Override public int soundOn() {
            return ReelTapAndVolume.soundOn();
        }

        @Override public boolean toggle() {
            return ReelTapAndVolume.toggle();
        }
    };

    static volatile Audio audio = PATCHED;

    /** Whether the icon's own switch is on. Paused answers no. Never throws. */
    public static boolean on() {
        try {
            return enabled();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel mute button switch", failure);
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
            Speaker speaker = context == null ? null : speaker(context, filter instanceof ColorFilter ? (ColorFilter) filter : null);
            Object icon = null;
            if (speaker != null) {
                // The last icon over More is the one that makes up the margin More takes back.
                long under = aboveMore && !ReelSpeedButton.shows(column) && !ReelTextButton.shows(column)
                        && !ReelSoundButton.shows(column) && !ReelBlockButton.shows(column)
                        && !ReelNotInterestedButton.shows(column) && !ReelDownloadButton.shows(column)
                        ? ReelDownloadButton.underAboveMore(column, context, margin) : margin;
                icon = ReelDownloadButton.bridge.icon(ReelNotInterestedButton.centered(context.getResources(), speaker),
                        L10n.t(context, "Sound"), new Click(), size, under);
            }
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_REFUSED);
                return null;
            }
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel mute button added to a reel's column");
            }
            return icon;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel mute button", failure);
            return null;
        }
    }

    /**
     * Called as clear mode's exit button is composed, with [row] the row it goes in, holding just that
     * button. With both switches on the speaker goes in first, to the exit button's left. Never throws.
     */
    @SuppressWarnings("unchecked")
    public static void clearMode(@Nullable Object row) {
        try {
            if (!(row instanceof List) || !enabled() || !Settings.MUTE_BUTTON_IN_CLEAR_MODE.get()) return;
            HookStatus.invoked(FamilyNames.REEL_DOWNLOAD);
            Context context = Utils.getContext();
            Speaker speaker = context == null ? null : speaker(context, null);
            Object icon = null;
            if (speaker != null) {
                float density = context.getResources().getDisplayMetrics().density;
                Drawable circled = new Circled(speaker, Math.round(CIRCLE_DP * density), Math.round(CLEAR_MODE_GLYPH_DP * density));
                long box = ReelDownloadButton.PX_BITS | (Math.round(CLEAR_MODE_BOX_DP * density) & 0xFFFFFFFFL);
                icon = ReelDownloadButton.bridge.icon(circled, L10n.t(context, "Sound"), new Click(), box, 0);
            }
            if (icon == null) {
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, COUNT_CLEAR_MODE_REFUSED);
                return;
            }
            ((List<Object>) row).add(0, icon);
            if (!clearModeLogged) {
                clearModeLogged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel mute button added beside clear mode's exit button");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel mute button in clear mode", failure);
        }
    }

    /** Whether the icon shows: its switch, with Control taps and volume on Reels in the build. Paused answers no. */
    static boolean enabled() {
        return Utils.settingsReady() && Settings.MUTE_BUTTON_ON_REELS.get() && tapAndVolumeInBuild();
    }

    /** Asked once, since the answer comes through reflection and never changes. */
    private static boolean tapAndVolumeInBuild() {
        int known = tapAndVolume;
        if (known == 0) {
            known = PatchFamily.REEL_TAP_AND_VOLUME.inBuild() ? 1 : -1;
            tapAndVolume = known;
        }
        return known > 0;
    }

    /** Whether the icon shows in [column], so an icon in front of it leaves More's margin to it. Never throws. */
    static boolean shows(@Nullable Object column) {
        try {
            if (column == null || !enabled() || glyphOn < 0 || glyphOff < 0) return false;
            Object item = ReelDownloadButton.bridge.item(column);
            return item != null && ReelDownloadButton.ORGANIC.equals(ReelDownloadButton.bridge.kind(item));
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * A fresh speaker over copies of Instagram's two glyphs, tinted with [filter] when Instagram tints
     * the column's icons, else white like them, and remembered to draw again. Null when the build lacks
     * either glyph.
     */
    @Nullable
    static Speaker speaker(Context context, @Nullable ColorFilter filter) {
        Resources resources = context.getResources();
        int on = glyphOn;
        if (on == 0) {
            on = ReelNotInterestedButton.glyphId(resources, Utils.resourcePackage(context), SOUND_ON_NAMES);
            glyphOn = on;
        }
        int off = glyphOff;
        if (off == 0) {
            off = ReelNotInterestedButton.glyphId(resources, Utils.resourcePackage(context), SOUND_OFF_NAMES);
            glyphOff = off;
        }
        if (on <= 0 || off <= 0) return null;
        Drawable soundOn = fresh(context, on);
        Drawable soundOff = fresh(context, off);
        if (soundOn == null || soundOff == null) return null;
        ReelNotInterestedButton.tint(soundOn, filter);
        ReelNotInterestedButton.tint(soundOff, filter);
        Speaker speaker = new Speaker(soundOn, soundOff);
        synchronized (speakers) {
            speakers.addLast(new WeakReference<>(speaker));
            while (speakers.size() > REMEMBERED) speakers.removeFirst();
        }
        return speaker;
    }

    @Nullable
    private static Drawable fresh(Context context, int id) {
        try {
            Drawable found = context.getResources().getDrawable(id, context.getTheme());
            return found == null ? null : found.mutate();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Draws every remembered speaker again on the main thread, once however often it's asked before
     * then, so each shows the sound of the reel on screen. Never throws.
     */
    public static void refresh() {
        try {
            if (redrawPosted) return;
            synchronized (speakers) {
                if (speakers.isEmpty()) return;
            }
            redrawPosted = true;
            Utils.runOnMainThread(ReelMuteButton::redraw);
        } catch (Throwable failure) {
            redrawPosted = false;
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel mute button redraw", failure);
        }
    }

    static void redraw() {
        redrawPosted = false;
        List<Speaker> live = new ArrayList<>();
        synchronized (speakers) {
            for (Iterator<WeakReference<Speaker>> it = speakers.iterator(); it.hasNext(); ) {
                Speaker speaker = it.next().get();
                if (speaker == null) it.remove();
                else live.add(speaker);
            }
        }
        for (Speaker speaker : live) speaker.invalidateSelf();
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        glyphOn = 0;
        glyphOff = 0;
        tapAndVolume = 0;
        logged = false;
        clearModeLogged = false;
        redrawPosted = false;
        audio = PATCHED;
        synchronized (speakers) {
            speakers.clear();
        }
    }

    static int rememberedForTests() {
        synchronized (speakers) {
            return speakers.size();
        }
    }

    /** Instagram's sound on glyph, or its sound off one while the reel on screen is muted. */
    static final class Speaker extends Drawable implements Drawable.Callback {
        final Drawable soundOn;
        final Drawable soundOff;

        Speaker(Drawable soundOn, Drawable soundOff) {
            this.soundOn = soundOn;
            this.soundOff = soundOff;
            soundOn.setCallback(this);
            soundOff.setCallback(this);
        }

        /** The glyph drawn now: sound off only while the reel on screen is known to be muted. */
        Drawable showing() {
            int sound;
            try {
                sound = audio.soundOn();
            } catch (Throwable unreadable) {
                sound = -1;
            }
            return sound == 0 ? soundOff : soundOn;
        }

        @Override protected void onBoundsChange(@NonNull Rect bounds) {
            soundOn.setBounds(bounds);
            soundOff.setBounds(bounds);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            showing().draw(canvas);
        }

        @Override public int getIntrinsicWidth() {
            return soundOn.getIntrinsicWidth();
        }

        @Override public int getIntrinsicHeight() {
            return soundOn.getIntrinsicHeight();
        }

        @Override public void setAlpha(int alpha) {
            soundOn.setAlpha(alpha);
            soundOff.setAlpha(alpha);
        }

        @Override public void setColorFilter(@Nullable ColorFilter filter) {
            soundOn.setColorFilter(filter);
            soundOff.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
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

    /** The speaker in a dark circle like clear mode's exit button, in the middle of its box. */
    static final class Circled extends Drawable implements Drawable.Callback {
        final Speaker speaker;
        final int circle;
        final int glyph;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        Circled(Speaker speaker, int circle, int glyph) {
            this.speaker = speaker;
            this.circle = circle;
            this.glyph = glyph;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(CIRCLE_FILL);
            speaker.setCallback(this);
        }

        @Override protected void onBoundsChange(@NonNull Rect bounds) {
            int square = Math.max(0, Math.min(glyph, Math.min(bounds.width(), bounds.height())));
            int left = bounds.left + (bounds.width() - square) / 2;
            int top = bounds.top + (bounds.height() - square) / 2;
            speaker.setBounds(left, top, left + square, top + square);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float radius = Math.min(circle, Math.min(bounds.width(), bounds.height())) / 2f;
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, fill);
            speaker.draw(canvas);
        }

        @Override public int getIntrinsicWidth() {
            return circle;
        }

        @Override public int getIntrinsicHeight() {
            return circle;
        }

        @Override public void setAlpha(int alpha) {
            speaker.setAlpha(alpha);
            fill.setAlpha(Color.alpha(CIRCLE_FILL) * alpha / 255);
            invalidateSelf();
        }

        @Override public void setColorFilter(@Nullable ColorFilter filter) {
            speaker.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
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

    /** A tap on the speaker: the sound of the reel on screen goes off or on. */
    static final class Click implements Function1<Object, Object> {
        @Override public Object invoke(Object event) {
            try {
                if (!enabled()) return null;
                HookStatus.counted(FamilyNames.REEL_DOWNLOAD, "reel mute button tapped");
                if (!audio.toggle()) Utils.showToastShort(L10n.t("Sound didn't change"));
                refresh();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel mute button tap", failure);
            }
            return null;
        }
    }
}
