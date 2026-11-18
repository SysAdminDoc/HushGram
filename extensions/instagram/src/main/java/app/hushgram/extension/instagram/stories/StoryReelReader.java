/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * What the Download any story patch fills in for the story Download button's lists (#745): the
 * stories of the reel on screen, and the viewer's own pause and resume that hold the story still
 * under a list.
 *
 * <p>Apart from {@link StoryDownloadButton} for the reason StorySeekReader is: a stub the phone's
 * verifier refused would take the class every story's bind calls down with it. Only the button's
 * lists reach this class, inside their try.
 */
final class StoryReelReader {
    private StoryReelReader() { }

    /**
     * Filled in by the patch: the stories of [reel], the viewer's model of the reel on screen, as
     * [session] sees them, in the order the viewer plays them.
     */
    @Nullable
    static List<?> items(Object reel, Object session) {
        return null;
    }

    /** Filled in by the patch: pauses the story in [viewer]. [spare] is the stub's register. */
    static void pause(Object viewer, @Nullable Object spare) { }

    /** Filled in by the patch: plays the story in [viewer] again. The spares are the stub's registers. */
    static void play(Object viewer, @Nullable Object spare1, @Nullable Object spare2) { }
}
