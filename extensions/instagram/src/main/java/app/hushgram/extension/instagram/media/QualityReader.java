/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import androidx.annotation.Nullable;

/**
 * Reads and sets what Default playback quality needs of Instagram's DASH format evaluator and its
 * tracks. Each method is a stub the patch fills in, working in its own parameters, cast to
 * Instagram's own type; unfilled, each answers nothing and changes nothing, so no video's quality
 * changes.
 *
 * <p>The stubs live here and not in {@link QualityChoice}, whose hook the player reaches as a video
 * starts: a stub the phone's verifier refused would take the whole class down with it. Only
 * {@link QualityChoice#firstChoice} and {@link QualityChoice#eachChoice} reach this class, inside
 * their tries.
 */
final class QualityReader {
    private QualityReader() { }

    /**
     * Filled in by the patch: the id of the track the evaluator keeps to, which its custom-quality
     * setter leaves, or null while it chooses by bandwidth. Only an evaluator may be passed.
     */
    @Nullable
    static String customTrack(Object evaluator) {
        return null;
    }

    /** Filled in by the patch: the tracks the evaluator chooses among, or null before it has them. Only an evaluator may be passed. */
    @Nullable
    static Object[] trackFormats(Object evaluator) {
        return null;
    }

    /** Filled in by the patch: a track's quality label, read as the custom-quality setter reads it. Only a track may be passed. */
    @Nullable
    static String formatLabel(Object format) {
        return null;
    }

    /**
     * Filled in by the patch: hands [label] to the evaluator's own custom-quality setter, which keeps
     * to the track with that label from then on, or to none. Only an evaluator may be passed.
     */
    static void setCustomQuality(Object evaluator, @Nullable String label) {
    }
}
