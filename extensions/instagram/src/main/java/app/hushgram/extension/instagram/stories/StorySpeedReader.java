/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * What the Speed button on stories patch fills in: the story viewer's player speed setter, whether a
 * view holder is one of the viewer's pages, a page's media view, and whether a story is an ad.
 *
 * <p>Apart from {@link StorySpeed} for the reason StorySeekReader is: a stub the phone's verifier
 * refused would take the class the viewer's hooks call down with it. Only StorySpeed reaches this
 * class, inside its tries.
 */
final class StorySpeedReader {
    private StorySpeedReader() { }

    /**
     * Filled in by the patch: hands [speed] to the player in the viewer's video player field, which
     * takes only a speed between a quarter and four times. True once it's called the player.
     */
    static boolean setSpeed(Object viewer, float speed) {
        return false;
    }

    /** Filled in by the patch: whether [holder] is one of the story viewer's pages. */
    static boolean isPage(Object holder) {
        return false;
    }

    /** Filled in by the patch: the view a page shows its story's media in. Only a page may be passed. */
    @Nullable
    static View pageView(Object page) {
        return null;
    }

    /** Filled in by the patch: whether the story [item] is an ad, from its Media's ad details. */
    static boolean ad(Object item) {
        return false;
    }
}
