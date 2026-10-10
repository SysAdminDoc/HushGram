/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The shopping bag on a post with products tagged in it, for the "Hide suggested posts" patch's
 * Hide shopping switch.
 *
 * <p>Instagram gives a post one indicator in its corner, picked in a fixed order: a fundraiser, a
 * reel, the products tagged in it, the people tagged in it and so on. The patch hands
 * {@link #indicator} Instagram's answer to whether the post's products get it, right before
 * Instagram acts on that answer. While Hide shopping is on the answer comes back as no, and
 * Instagram goes on down its order, to the people tagged in the post or to no indicator at all.
 */
public final class ProductTags {
    /** The name Instagram 450 gives the indicator of a post's products. */
    static final String PRODUCTS = "PRODUCTS";

    /**
     * The diagnostic counter route: each post asked about as a list of one, the ones whose products
     * would get the indicator as that kind, and the ones whose indicator went.
     */
    static final String ROUTE = "Product tag indicator";

    /** Whether Hide shopping takes the indicator away right now. Tests stand in. */
    static volatile BooleanSupplier hides = ProductTags::hiding;

    private ProductTags() {
    }

    static boolean hiding() {
        return Utils.settingsReady() && Settings.HIDE_FEED_SHOPPING.get();
    }

    /**
     * Injected between Instagram's check of a post's products and the branch on it, with the check's
     * answer (a boolean, passed as an int). Answers 0 for a yes while Hide shopping is on, and the
     * answer itself otherwise, or when anything goes wrong. Never throws, and never waits for the
     * settings: before they're ready the indicator stays.
     */
    public static int indicator(int products) {
        try {
            HookStatus.invoked(FamilyNames.FEED_SUGGESTIONS);
            FeedFilterCounters.sawList(ROUTE, 1);
            if (products == 0) return products;
            FeedFilterCounters.sawKind(ROUTE, PRODUCTS);
            if (!hides.getAsBoolean()) return products;
            FeedFilterCounters.removed(ROUTE, 1, PRODUCTS);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_SUGGESTIONS, "Product tag indicator", failure);
            return products;
        }
    }
}
