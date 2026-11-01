/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Which of Instagram's two video views a video plays on, while a switch here needs the one it can read.
 *
 * <p>Instagram's player draws on a TextureView, or on a SurfaceView when a server option and a list of
 * screens say so. That choice is made per account and per screen, so one phone can meet both in one
 * feed. A SurfaceView's picture is a layer of its own behind the window: the glass bars, the reel bars'
 * blur and ambient light can't read it, and the video shows sharp through the glass (#430, #136, #144).
 * It's also the only view Instagram renders HDR on, so it's how a brighter video gets past Turn off HDR
 * brightness boosts (#85).
 *
 * <p>The patch puts {@link #textureOnly} first in the method making that choice. While one of those
 * switches is on the answer is TextureView, and with none on, or paused, the choice stays Instagram's.
 * Turning one on costs that account its HDR video, which is what it's asking for or can't be read past.
 */
public final class VideoSurface {
    /** How the report counts a choice this answered, under the switch that asked for it. */
    static final String KEPT = "Videos kept on a TextureView";

    private VideoSurface() {
    }

    /**
     * Injected first in Instagram's choice of a SurfaceView. Answers whether to skip that choice and
     * use a TextureView. Never throws, and never waits for the settings: before they're ready the
     * choice stays Instagram's.
     */
    public static boolean textureOnly() {
        try {
            if (!Utils.settingsReady()) return false;
            String family = asking();
            if (family == null) return false;
            HookStatus.invoked(family);
            HookStatus.counted(family, KEPT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HDR_BOOST, "video surface", failure);
            return false;
        }
    }

    /** The switch that needs a TextureView, the first one on, or null when none is. */
    @Nullable
    private static String asking() {
        if (Settings.TURN_OFF_HDR_BOOSTS.get()) return FamilyNames.HDR_BOOST;
        if (Settings.GLASS_TAB_BAR.get()) return FamilyNames.GLASS_TAB_BAR;
        if (Settings.SEE_THROUGH_HOME_TOP_BAR.get()) return FamilyNames.HOME_TOP_BAR;
        if (Settings.BLUR_REEL_BARS.get()) return FamilyNames.REEL_BLUR_BARS;
        if (Settings.AMBIENT_LIGHT.get() || Settings.AMBIENT_LIGHT_STORIES.get()) return FamilyNames.AMBIENT_LIGHT;
        return null;
    }
}
