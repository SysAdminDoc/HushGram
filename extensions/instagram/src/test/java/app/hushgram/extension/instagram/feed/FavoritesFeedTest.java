/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Favorites from the tab bar (#643): the tab hosts' root check answers yes while a Favorites feed
 * page is on top and the switch is on, and a long press hands FAVORITES to the feed switch built for
 * the activity it came from, and to nothing else.
 */
@RunWith(RobolectricTestRunner.class)
public class FavoritesFeedTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private Activity activity;
    private final List<Object[]> shown = new ArrayList<>();

    /** A feed page, as the tab host lists them: its arguments name the feed it shows. */
    public static final class Page {
        private final Bundle arguments = new Bundle();

        Page(String mode) {
            if (mode != null) arguments.putString(FavoritesFeed.MODE_KEY, mode);
        }

        public Bundle getArguments() {
            return arguments;
        }
    }

    @Before public void prepare() {
        HookStatus.clear();
        PauseForTests.resume();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Settings.FAVORITES_TAB_BAR.save(true);
    }

    @After public void restore() {
        FavoritesFeedForTests.reset();
        Settings.FAVORITES_TAB_BAR.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
        controller.close();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    private static FavoritesFeed.Screens showing(Object... pages) {
        return host -> Arrays.asList(pages);
    }

    @Test public void theBarStaysWhileFavoritesIsOnTop() {
        Page favorites = new Page(FavoritesFeed.FAVORITES_MODE);
        assertEquals(1, FavoritesFeed.keepTabRoot(0, new Object(), showing(new Page("Explore"), favorites)));
        assertTrue(report(), report().contains(FavoritesFeed.BAR_KEPT));
    }

    @Test public void anotherPageOnTopKeepsInstagramsAnswer() {
        Page favorites = new Page(FavoritesFeed.FAVORITES_MODE);
        assertEquals(0, FavoritesFeed.keepTabRoot(0, new Object(), showing(favorites, new Page("Explore"))));
        assertEquals("a page without arguments", 0, FavoritesFeed.keepTabRoot(0, new Object(), showing(new Page(null))));
        assertEquals("not a page at all", 0, FavoritesFeed.keepTabRoot(0, new Object(), showing("profile")));
        assertEquals("nothing pushed", 0, FavoritesFeed.keepTabRoot(0, new Object(), host -> Collections.emptyList()));
        assertEquals(0, FavoritesFeed.keepTabRoot(0, new Object(), host -> null));
        assertFalse(report().contains(FavoritesFeed.BAR_KEPT));
    }

    @Test public void aTabAlreadyAtItsRootIsLeftAlone() {
        assertEquals(1, FavoritesFeed.keepTabRoot(1, new Object(), host -> {
            throw new AssertionError("a tab at its root isn't looked into");
        }));
    }

    @Test public void offOrPausedTheBarHidesAsInstagramHidesIt() {
        Page favorites = new Page(FavoritesFeed.FAVORITES_MODE);
        Settings.FAVORITES_TAB_BAR.save(false);
        assertEquals(0, FavoritesFeed.keepTabRoot(0, new Object(), showing(favorites)));
        Settings.FAVORITES_TAB_BAR.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(0, FavoritesFeed.keepTabRoot(0, new Object(), showing(favorites)));
    }

    @Test public void aFailingLookupKeepsInstagramsAnswerAndIsReported() {
        assertEquals(0, FavoritesFeed.keepTabRoot(0, new Object(), host -> {
            throw new IllegalStateException("moved");
        }));
        assertTrue(report(), report().contains(FavoritesFeed.WHAT_ROOT));
    }

    @Test public void theStubsAnswerNothingUntilThePatchWritesThem() {
        assertNull(FavoritesFeedTargets.feedTypes());
        assertTrue(FavoritesFeedTargets.fragments(new Object()).isEmpty());
        assertEquals(0, FavoritesFeed.keepTabRoot(0, new Object()));
        assertFalse("no switch, no feed type", FavoritesFeed.open(activity));
    }

    @Test public void aLongPressHandsFavoritesToTheSwitchBuiltForThisActivity() {
        FavoritesFeedForTests.Switch owner = new FavoritesFeedForTests.Switch(activity);
        FavoritesFeedForTests.use(shown);
        FavoritesFeed.switchReady(owner);
        assertTrue(FavoritesFeed.open(activity));
        assertEquals(1, shown.size());
        assertSame(owner, shown.get(0)[0]);
        assertSame(FavoritesFeedForTests.Feed.FAVORITES, shown.get(0)[1]);
        assertTrue(report(), report().contains(FavoritesFeed.OPENED));
    }

    @Test public void aSwitchBuiltForAnotherActivityOpensNothing() {
        ActivityController<Activity> older = Robolectric.buildActivity(Activity.class).setup();
        try {
            FavoritesFeedForTests.use(shown);
            FavoritesFeed.switchReady(new FavoritesFeedForTests.Switch(older.get()));
            assertFalse(FavoritesFeed.open(activity));
            assertTrue(shown.isEmpty());
            assertTrue(report(), report().contains(FavoritesFeed.NO_SWITCH));
        } finally {
            older.close();
        }
    }

    @Test public void withoutASwitchOrAFeedTypeNothingOpens() {
        assertFalse(FavoritesFeed.open(activity, null, FavoritesFeedForTests.Feed.class, (owner, feed) -> shown.add(new Object[0])));
        Object owner = new FavoritesFeedForTests.Switch(activity);
        assertFalse(FavoritesFeed.open(activity, owner, null, (o, feed) -> shown.add(new Object[0])));
        assertFalse("not an enum", FavoritesFeed.open(activity, owner, String.class, (o, feed) -> shown.add(new Object[0])));
        assertFalse("a closed activity", FavoritesFeed.open(null, owner, FavoritesFeedForTests.Feed.class,
                (o, feed) -> shown.add(new Object[0])));
        assertTrue(shown.isEmpty());
    }

    @Test public void aSwitchWithTwoActivitiesIsNotTrusted() throws Exception {
        Object twoFields = new Object() {
            @SuppressWarnings("unused") final Activity first = activity;
            @SuppressWarnings("unused") final Activity second = activity;
        };
        assertFalse(FavoritesFeed.builtFor(twoFields, activity));
        assertFalse(FavoritesFeed.builtFor(new Object(), activity));
    }

    @Test public void aFailingSwitchIsReportedAndOpensNothing() {
        Object owner = new FavoritesFeedForTests.Switch(activity);
        assertFalse(FavoritesFeed.open(activity, owner, FavoritesFeedForTests.Feed.class, (o, feed) -> {
            throw new IllegalStateException("moved");
        }));
        assertTrue(report(), report().contains(FavoritesFeed.WHAT_OPEN));
    }
}
