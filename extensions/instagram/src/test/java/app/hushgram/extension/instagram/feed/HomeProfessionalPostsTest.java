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

import java.util.Map;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide posts from business and creator accounts (#579): which of Home's items go, Favorites, and the report. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HomeProfessionalPostsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A feed item carrying a photo whose author answers [professional] as the stubs do. */
    private static final class Item {
        final int professional;

        Item(int professional) {
            this.professional = professional;
        }
    }

    private int professionalReads;
    private final ToIntFunction<Object> professionalOf = item -> {
        professionalReads++;
        return ((Item) item).professional;
    };

    @Before
    public void start() {
        clear();
    }

    @After
    public void restore() {
        Settings.HIDE_FEED_PROFESSIONAL.resetToDefault();
        Settings.HIDE_FEED_PHOTOS.resetToDefault();
        clear();
    }

    private void clear() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        FeedSuggestions.typesTookOut = false;
        FeedSuggestions.tookOut = false;
        HiddenAccounts.resetForTests();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private Object home(Object item) {
        return home(item, professionalOf);
    }

    private Object home(Object item, ToIntFunction<Object> professional) {
        return FeedSuggestions.homeItem(item, ignored -> FeedSuggestions.PHOTO, ignored -> 0, ignored -> null,
                ignored -> null, professional);
    }

    @Test
    public void theSwitchStartsOffAndOffReadsNothing() {
        assertFalse(Settings.HIDE_FEED_PROFESSIONAL.get());
        Item business = new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT);
        assertSame(business, home(business));
        assertEquals(0, professionalReads);
        assertFalse(FeedSuggestions.typesTookOut);
    }

    /** On, a post by a business or creator account goes, and one by a personal account, or one that doesn't say, stays. */
    @Test
    public void onTakesOutOnlyProfessionalAccounts() {
        Settings.HIDE_FEED_PROFESSIONAL.save(true);
        assertNull(home(new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT)));
        for (int said : new int[] {FeedSuggestions.UNSAID, FeedSuggestions.PERSONAL, 3, -1}) {
            Item kept = new Item(said);
            assertSame("answer " + said, kept, home(kept));
        }
        assertNull(home(null));
        assertTrue(FeedSuggestions.typesTookOut);
    }

    /** Favorites only holds accounts you picked, so a page of it keeps them, and For you after it doesn't. */
    @Test
    public void favoritesKeepsThem() {
        Settings.HIDE_FEED_PROFESSIONAL.save(true);
        Item business = new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT);
        HiddenAccounts.homeRequest(Map.of(HiddenAccounts.PAGINATION_SOURCE, HiddenAccounts.FAVORITES));
        assertSame(business, home(business));
        assertEquals("Favorites reads no author", 0, professionalReads);
        HiddenAccounts.homeRequest(Map.of());
        assertNull(home(business));
    }

    /** Beside Hide photos, a photo goes for its type before its author is read. */
    @Test
    public void besideATypeSwitchTheTypeGoesFirst() {
        Settings.HIDE_FEED_PROFESSIONAL.save(true);
        Settings.HIDE_FEED_PHOTOS.save(true);
        assertNull(home(new Item(FeedSuggestions.PERSONAL)));
        assertEquals(0, professionalReads);
    }

    @Test
    public void pausedAndUnreadyKeepEveryPost() {
        Settings.HIDE_FEED_PROFESSIONAL.save(true);
        Item business = new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(business, home(business));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(business, home(business)));

        assertNull(home(business));
    }

    @Test
    public void aThrowingReadKeepsThePostAndIsReported() {
        Settings.HIDE_FEED_PROFESSIONAL.save(true);
        Item item = new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT);
        assertSame(item, home(item, ignored -> {
            throw new IllegalStateException("the author went away");
        }));
        assertFalse(FeedSuggestions.typesTookOut);
        String missing = HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'" + FeedSuggestions.PROFESSIONAL + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /**
     * is_professional_account answers first. Only when it doesn't say does account_type, where 2 is
     * a business and 3 a creator. No post, or neither saying, keeps it.
     */
    @Test
    public void theAuthorsFlagAnswersBeforeItsAccountType() {
        Object post = new Object();
        assertEquals(FeedSuggestions.PROFESSIONAL_ACCOUNT, FeedSuggestions.postProfessional(post, p -> 2, p -> 1));
        assertEquals(FeedSuggestions.PERSONAL, FeedSuggestions.postProfessional(post, p -> 1, p -> 2));
        assertEquals(FeedSuggestions.PROFESSIONAL_ACCOUNT, FeedSuggestions.postProfessional(post, p -> 0, p -> 2));
        assertEquals(FeedSuggestions.PROFESSIONAL_ACCOUNT, FeedSuggestions.postProfessional(post, p -> 0, p -> 3));
        assertEquals(FeedSuggestions.PERSONAL, FeedSuggestions.postProfessional(post, p -> 0, p -> 1));
        assertEquals(FeedSuggestions.UNSAID, FeedSuggestions.postProfessional(post, p -> 0, p -> 0));
        assertEquals(FeedSuggestions.UNSAID, FeedSuggestions.postProfessional(post, p -> 0, p -> 4));
        assertEquals(FeedSuggestions.UNSAID, FeedSuggestions.postProfessional(null, p -> 2, p -> 2));
    }

    /** The stubs the patch fills answer 0 as built, so an unpatched Home keeps every post. */
    @Test
    public void asBuiltNoAuthorSays() {
        Settings.HIDE_FEED_PROFESSIONAL.save(true);
        Object post = new Object();
        assertEquals(0, FeedSuggestions.professional(post));
        assertEquals(0, FeedSuggestions.accountType(post));
        Item item = new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT);
        assertEquals(FeedSuggestions.UNSAID, FeedSuggestions.itemProfessional(item));
        assertSame(item, FeedSuggestions.homeItem(item));
    }

    /** A Home emptied of business and creator posts ends while the switch is on. */
    @Test
    public void aHomeEmptiedOfThemEndsUntilTheSwitchGoesOff() {
        Settings.HIDE_FEED_PROFESSIONAL.save(true);
        assertEquals("nothing taken out yet", 0, FeedSuggestions.feedEnded(0));
        home(new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT));
        assertEquals(1, FeedSuggestions.feedEnded(0));
        Settings.HIDE_FEED_PROFESSIONAL.save(false);
        assertEquals(0, FeedSuggestions.feedEnded(0));
    }

    @Test
    public void theReportCountsWhatWent() {
        Settings.HIDE_FEED_PROFESSIONAL.save(true);
        home(new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT));
        home(new Item(FeedSuggestions.PROFESSIONAL_ACCOUNT));
        home(new Item(FeedSuggestions.PERSONAL));
        String routes = FeedFilterCounters.report().toString();
        assertTrue(routes, routes.contains(FeedSuggestions.TYPES_ROUTE));
        assertTrue(routes, routes.contains(FeedSuggestions.PROFESSIONAL));
        String family = HookStatus.report().toString();
        assertTrue(family, family.contains(FeedSuggestions.PROFESSIONAL_REMOVED + " 2"));
    }
}
