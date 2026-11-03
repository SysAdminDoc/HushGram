/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import android.view.View;

import androidx.annotation.Nullable;

/**
 * What the Hide the reply box on stories patch fills in: the story viewer's view.
 *
 * <p>Apart from {@link StoryReplyBox} for the reason StorySeekReader is: a stub the phone's verifier
 * refused would take the class the viewer's resume calls down with it. Only StoryReplyBox reaches
 * this class, inside its try. The stub is handed the viewer, ReelViewerFragment, and nothing else.
 */
final class StoryReplyBoxReader {
    private StoryReplyBoxReader() { }

    /** Filled in by the patch: the viewer's view, or null before it has one. */
    @Nullable
    static View view(Object viewer) {
        return null;
    }
}
