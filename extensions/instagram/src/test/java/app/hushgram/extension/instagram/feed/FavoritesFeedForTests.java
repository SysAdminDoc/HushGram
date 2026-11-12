/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;

import java.util.List;

/** Lets tests outside this package stand in for the feed switch the patch writes calls into (#643). */
public final class FavoritesFeedForTests {
    private FavoritesFeedForTests() {
    }

    /** A feed type with Instagram's constant names, which {@link FavoritesFeed} looks FAVORITES up in. */
    public enum Feed { FOR_YOU, FOLLOWING, FAVORITES }

    /** A feed switch built for one activity, the shape Instagram's has. */
    public static final class Switch {
        private final Activity activity;

        public Switch(Activity activity) {
            this.activity = activity;
        }
    }

    /** Each feed the switch was handed lands in [shown], with the switch it was handed to. */
    public static void use(List<Object[]> shown) {
        FavoritesFeed.typesForTests = Feed.class;
        FavoritesFeed.showForTests = (owner, feed) -> shown.add(new Object[] {owner, feed});
    }

    /** Builds Home's feed switch for [activity], the way Instagram does when Home first shows. */
    public static void switchBuiltFor(Activity activity) {
        FavoritesFeed.switchReady(new Switch(activity));
    }

    public static void reset() {
        FavoritesFeed.typesForTests = null;
        FavoritesFeed.showForTests = null;
        FavoritesFeed.switchReady(null);
    }
}
