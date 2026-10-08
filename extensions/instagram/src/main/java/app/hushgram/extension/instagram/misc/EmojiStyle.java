/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Emoji style" patch: every emoji draws in Google's style instead of the phone's.
 *
 * <p>Instagram draws emoji through AndroidX EmojiCompat, which loads Google's emoji font from
 * Google Play services when Instagram starts. Instagram's build leaves EmojiCompat at its default,
 * where it only draws the emoji the phone's own font lacks. Each piece of text goes through
 * EmojiCompat's process with a replace strategy, and the patch asks {@link #replaceStrategy} for it
 * first. With the switch on it answers {@link #REPLACE_ALL}, so EmojiCompat draws every emoji it
 * knows from Google's font. Text already on screen keeps how it was drawn, so a change shows fully
 * after a restart.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own strategy goes through.
 */
public final class EmojiStyle {
    /** EmojiCompat's REPLACE_STRATEGY_ALL: draw every emoji from its font. */
    static final int REPLACE_ALL = 1;

    /** What's counted when text is drawn with Google's emoji throughout. */
    static final String GOOGLE = "every emoji in Google's style";

    private EmojiStyle() {
    }

    /**
     * Injected first thing in EmojiCompat's process. Answers the replace strategy to use for
     * [strategy], the one Instagram asked for. Never throws.
     */
    public static int replaceStrategy(int strategy) {
        try {
            HookStatus.invoked(FamilyNames.EMOJI_STYLE);
            if (!Utils.settingsReady() || !Settings.NOTO_EMOJI.get()) return strategy;
            HookStatus.counted(FamilyNames.EMOJI_STYLE, GOOGLE);
            return REPLACE_ALL;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.EMOJI_STYLE, "emoji strategy", failure);
            return strategy;
        }
    }
}
