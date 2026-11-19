/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Hide the audio icon on posts patch (#464): keeps the small speaker circle Instagram
 * draws in the corner of a feed video off the video.
 *
 * <p>Instagram 453 builds one state object for each feed post it draws, and the state carries a flag
 * for hiding the audio icon, which Instagram's own code reads before it draws the icon or the icon's
 * slide-in view. The patch hands the flag to {@link #hides} as the state is built, and a yes sets it, so
 * both drawing paths take their own way past the icon. Nothing else about the video changes, and a
 * tap on it still turns the sound on or off. A post drawn before the switch changed keeps what it was
 * built with, until Instagram builds it again as the feed refreshes or the post scrolls back in.
 *
 * <p>The tagged people icon (#762) sits in the same corner row. Instagram binds it from a small state
 * that says which kind of tags the post has, and draws no icon when it gets no state at all. The patch
 * hands that state and its kind to {@link #tagIndicator} as the bind starts, and a people tag comes back
 * as no state while the switch is on, so Instagram's own empty path hides the icon. Product tags and
 * the other kinds keep their icon.
 *
 * <p>Both hooks fail open: with the switch off, HushGram paused, the settings not read yet or anything
 * thrown, Instagram keeps what it worked out.
 */
public final class FeedAudioIcon {
    /** The step a failed switch read is reported under. */
    static final String HOOK = "feed audio icon";
    static final String COUNT_HIDDEN = "feed audio icons hidden";
    static final String TAG_HOOK = "feed tag icon";
    static final String COUNT_TAG_HIDDEN = "feed tag icons hidden";

    private FeedAudioIcon() {
    }

    /**
     * Injected where a feed post's state stores whether the audio icon is hidden, handed over as an
     * int that reads any non-zero as yes (BooleanHookParametersTest). True when Instagram already
     * hides the icon or the switch is on. Never throws, and never waits for the settings.
     */
    public static boolean hides(int hidden) {
        if (hidden != 0) return true;
        try {
            HookStatus.invoked(FamilyNames.FEED_AUDIO_ICON);
            if (!Utils.settingsReady() || !Settings.HIDE_FEED_AUDIO_ICON.get()) return false;
            HookStatus.counted(FamilyNames.FEED_AUDIO_ICON, COUNT_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_AUDIO_ICON, HOOK, failure);
            return false;
        }
    }

    /**
     * Injected at the start of the bind that draws a post's tag indicator, handed the indicator's state
     * and the kind of tags it reads from it. Answers null for a people tag while the switch is on, which
     * Instagram's bind reads as no indicator, and the state itself otherwise. Never throws, and never
     * waits for the settings.
     */
    public static Object tagIndicator(Object state, Object kind) {
        if (state == null) return null;
        try {
            HookStatus.invoked(FamilyNames.FEED_AUDIO_ICON);
            if (!Utils.settingsReady() || !Settings.HIDE_FEED_TAG_ICON.get()) return state;
            if (!isPeopleTag(kind)) return state;
            HookStatus.counted(FamilyNames.FEED_AUDIO_ICON, COUNT_TAG_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_AUDIO_ICON, TAG_HOOK, failure);
            return state;
        }
    }

    /** People tagged by their Instagram or Facebook account, the two kinds the person icon stands for. */
    static boolean isPeopleTag(Object kind) {
        if (!(kind instanceof Enum)) return false;
        String name = ((Enum<?>) kind).name();
        return "PEOPLE".equals(name) || "FB_USER".equals(name);
    }
}
