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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide posts you've liked: which of Home's items go, how it sits beside the type switches, and the report. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HomeLikedPostsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSetting[] TYPES =
            {Settings.HIDE_FEED_VIDEOS, Settings.HIDE_FEED_PHOTOS, Settings.HIDE_FEED_CAROUSELS};

    /** A feed item carrying a post of [type] that you've liked when [liked] is 1. */
    private static final class Item {
        final int type;
        final int liked;

        Item(int type, int liked) {
            this.type = type;
            this.liked = liked;
        }
    }

    private int typeReads;
    private int likedReads;
    private final ToIntFunction<Object> typeOf = item -> {
        typeReads++;
        return ((Item) item).type;
    };
    private final ToIntFunction<Object> likedOf = item -> {
        likedReads++;
        return ((Item) item).liked;
    };

    @Before
    public void start() {
        clear();
    }

    @After
    public void restore() {
        Settings.HIDE_FEED_LIKED.resetToDefault();
        for (BooleanSetting setting : TYPES) setting.resetToDefault();
        clear();
    }

    private void clear() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        FeedSuggestions.typesTookOut = false;
        FeedSuggestions.tookOut = false;
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private Object home(Item item) {
        return FeedSuggestions.homeItem(item, typeOf, likedOf);
    }

    @Test
    public void theSwitchStartsOffAndOffReadsNothing() {
        Settings.HIDE_FEED_LIKED.resetToDefault();
        assertFalse(Settings.HIDE_FEED_LIKED.get());
        Item liked = new Item(FeedSuggestions.PHOTO, 1);
        assertSame(liked, home(liked));
        assertEquals(0, likedReads);
        assertFalse(FeedSuggestions.typesTookOut);
    }

    /** On, a post you've liked goes whatever its type, and one you haven't, or one that doesn't say, stays. */
    @Test
    public void onTakesOutOnlyLikedPosts() {
        Settings.HIDE_FEED_LIKED.save(true);
        for (int type : new int[] {0, FeedSuggestions.PHOTO, FeedSuggestions.VIDEO, FeedSuggestions.CAROUSEL}) {
            Item kept = new Item(type, 0);
            assertSame("type " + type + " not liked", kept, home(kept));
            assertNull("type " + type + " liked", home(new Item(type, 1)));
        }
        Item odd = new Item(FeedSuggestions.PHOTO, 7);
        assertSame("an answer that isn't 1 keeps the post", odd, home(odd));
        assertNull(home(null));
        assertTrue(FeedSuggestions.typesTookOut);
        assertEquals("the type switches are off, so no type was read", 0, typeReads);
    }

    /**
     * Beside a type switch, a post of that type goes for its type and its liked state isn't read,
     * and a liked post of another type goes as liked. Each filter takes out only what it's for.
     */
    @Test
    public void besideATypeSwitchEachTakesOutItsOwn() {
        Settings.HIDE_FEED_LIKED.save(true);
        Settings.HIDE_FEED_VIDEOS.save(true);
        assertNull(home(new Item(FeedSuggestions.VIDEO, 1)));
        assertEquals("a video goes before its liked state is read", 0, likedReads);
        assertNull(home(new Item(FeedSuggestions.PHOTO, 1)));
        Item photo = new Item(FeedSuggestions.PHOTO, 0);
        assertSame(photo, home(photo));

        Settings.HIDE_FEED_LIKED.save(false);
        Item likedPhoto = new Item(FeedSuggestions.PHOTO, 1);
        assertSame("off, a liked photo stays", likedPhoto, home(likedPhoto));
        assertNull(home(new Item(FeedSuggestions.VIDEO, 0)));
    }

    @Test
    public void pausedAndUnreadyKeepEveryPost() {
        Settings.HIDE_FEED_LIKED.save(true);
        Item liked = new Item(FeedSuggestions.PHOTO, 1);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(liked, home(liked));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(liked, home(liked)));

        assertNull(home(liked));
    }

    @Test
    public void aThrowingReadKeepsThePostAndIsReported() {
        Settings.HIDE_FEED_LIKED.save(true);
        Item item = new Item(FeedSuggestions.PHOTO, 1);
        assertSame(item, FeedSuggestions.homeItem(item, typeOf, ignored -> {
            throw new IllegalStateException("the post went away");
        }));
        assertFalse(FeedSuggestions.typesTookOut);
        String missing = HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'liked post'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** The stub the patch fills answers 0 as built, so an unpatched Home keeps every post. */
    @Test
    public void asBuiltNoPostIsLiked() {
        Settings.HIDE_FEED_LIKED.save(true);
        Item item = new Item(FeedSuggestions.PHOTO, 1);
        assertEquals(0, FeedSuggestions.liked(item));
        assertSame(item, FeedSuggestions.homeItem(item));
    }

    /** A Home emptied of liked posts ends while the switch is on, as one emptied by a type does. */
    @Test
    public void aHomeEmptiedOfLikedPostsEndsUntilTheSwitchGoesOff() {
        Settings.HIDE_FEED_LIKED.save(true);
        assertEquals("nothing taken out yet", 0, FeedSuggestions.feedEnded(0));
        home(new Item(FeedSuggestions.PHOTO, 1));
        assertEquals(1, FeedSuggestions.feedEnded(0));
        Settings.HIDE_FEED_LIKED.save(false);
        assertEquals(0, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));
    }

    /**
     * The report counts each filter's posts: on Home's post types route by reason, and on the
     * family's line, so a device report says what each one took out.
     */
    @Test
    public void theReportCountsEachFilter() {
        Settings.HIDE_FEED_LIKED.save(true);
        Settings.HIDE_FEED_CAROUSELS.save(true);
        home(new Item(FeedSuggestions.PHOTO, 1));
        home(new Item(FeedSuggestions.VIDEO, 1));
        home(new Item(FeedSuggestions.CAROUSEL, 0));
        home(new Item(FeedSuggestions.PHOTO, 0));
        String routes = FeedFilterCounters.report().toString();
        assertTrue(routes, routes.contains(FeedSuggestions.TYPES_ROUTE));
        assertTrue(routes, routes.contains(FeedSuggestions.LIKED));
        assertTrue(routes, routes.contains("carousel"));
        String family = HookStatus.report().toString();
        assertTrue(family, family.contains(FeedSuggestions.LIKED_REMOVED + " 2"));
        assertTrue(family, family.contains("carousel posts removed 1"));
        assertFalse(family, family.contains("photo posts removed"));
    }
}
