/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.explore;

/**
 * What the Hide the Explore grid patch fills in: Instagram's own check of whether a unit of search
 * results is the You may also like block at their end.
 *
 * <p>Apart from {@link SearchRecommendations} for the reason StoryButtonsRoomReader is: a stub the
 * phone's verifier refused would take the class the results builders call down with it. Only
 * SearchRecommendations reaches this class, inside its try.
 */
final class SearchRecommendationReader {
    private SearchRecommendationReader() { }

    /** Filled in by the patch: whether Instagram marks [unit] as its end of results recommendations. */
    static boolean isRecommendation(Object unit) {
        return false;
    }
}
