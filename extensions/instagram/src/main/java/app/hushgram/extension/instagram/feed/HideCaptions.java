/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Hide captions patch (#395): keeps the caption text out of feed posts and out of the
 * Reels viewer while the switch is on. The post, the reel and the comments sheet, which shows the
 * caption at its top, stay as Instagram draws them.
 *
 * <p>Instagram 453 draws a feed post's caption as a declarative component with no view of its own,
 * so the patch asks {@link #hideFeedCaption} at the start of each caption render, and a yes draws
 * nothing. {@link ViewHider} sets the caption hosts that are views gone before a frame shows them,
 * wherever Instagram puts them, so the rows around them close up. With the switch off or HushGram
 * paused the views are left alone and the next bind brings them back.
 */
public final class HideCaptions {
    /** The caption host of a feed post and of a reel in the Reels viewer, as Instagram 453 names them. */
    static final String POST_CAPTION = "media_caption_component";
    static final String REEL_CAPTION = "clips_caption_component";

    static final String HOOK = "captions";
    static final String COUNT_HIDDEN = "captions hidden";

    private static final ViewHider HIDER = new ViewHider(FamilyNames.HIDE_CAPTIONS, HOOK, COUNT_HIDDEN,
            () -> Settings.HIDE_CAPTIONS.get(), null, POST_CAPTION, REEL_CAPTION);

    private HideCaptions() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        HIDER.install(context, SettingsStatus.hideCaptions());
    }

    /**
     * True leaves a feed post's caption out: the patched render draws nothing. Fails open until the
     * settings are ready, with the switch off, or if anything throws. Never throws.
     */
    public static boolean hideFeedCaption() {
        try {
            HookStatus.invoked(FamilyNames.HIDE_CAPTIONS);
            if (!Utils.settingsReady() || !Settings.HIDE_CAPTIONS.get()) return false;
            HookStatus.counted(FamilyNames.HIDE_CAPTIONS, COUNT_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_CAPTIONS, "feed caption", failure);
            return false;
        }
    }

    /** The switch was flipped in HushGram settings, a dialog over [activity]: the screen under it decides now. */
    public static void switchChanged(@Nullable Activity activity) {
        HIDER.switchChanged(activity);
    }

    static ViewHider hider() {
        return HIDER;
    }

    public static boolean listeningForTests(Activity activity) {
        return HIDER.listeningForTests(activity);
    }

    public static void resetForTests() {
        HIDER.resetForTests();
    }
}
