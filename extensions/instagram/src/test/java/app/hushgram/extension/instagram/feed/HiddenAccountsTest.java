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

import java.util.Arrays;
import java.util.Collections;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hidden accounts: the list, kept for each signed-in account, and the posts Home leaves out by it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HiddenAccountsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A feed item carrying a photo by [author], null for an item with no post. */
    private static final class Item {
        final String author;

        Item(String author) {
            this.author = author;
        }
    }

    /** Stands in for Instagram's UserSession: its account's id. */
    private static final class Session {
        final String id;

        Session(String id) {
            this.id = id;
        }
    }

    private int authorReads;
    private final ToIntFunction<Object> photo = item -> FeedSuggestions.PHOTO;
    private final ToIntFunction<Object> notLiked = item -> 0;
    private final Function<Object, String> authorOf = item -> {
        authorReads++;
        return ((Item) item).author;
    };
    private final Function<Object, String> idOf = session -> ((Session) session).id;

    @Before
    public void start() {
        clear();
    }

    @After
    public void restore() {
        clear();
        Settings.HIDE_FEED_VIDEOS.resetToDefault();
    }

    private void clear() {
        Settings.HIDDEN_ACCOUNTS.resetToDefault();
        Settings.FEED_ACCOUNT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HiddenAccounts.resetForTests();
        FeedSuggestions.typesTookOut = false;
        FeedSuggestions.tookOut = false;
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private Object home(Item item) {
        return FeedSuggestions.homeItem(item, photo, notLiked, authorOf);
    }

    private void signIn(String id) {
        HiddenAccounts.homeSession(new Session(id), idOf);
    }

    private static String xs(int count) {
        return new String(new char[count]).replace('\0', 'x');
    }

    @Test
    public void theListStartsEmptyAndHomeReadsNoAuthor() {
        signIn("1");
        assertTrue(HiddenAccounts.saved().isEmpty());
        Item item = new Item("someone");
        assertSame(item, home(item));
        assertEquals(0, authorReads);
        assertFalse(FeedSuggestions.typesTookOut);
    }

    /** A typed name is trimmed, loses its @ and goes to lower case; anything that isn't a username is refused. */
    @Test
    public void aTypedNameIsKeptTheWayInstagramWritesIt() {
        assertEquals("nasa", HiddenAccounts.username("  @NASA "));
        assertEquals("a.b_c9", HiddenAccounts.username("a.b_c9"));
        for (String typed : new String[] {null, "", "   ", "@", "two words", "émile", "a/b", xs(31)}) {
            assertNull(String.valueOf(typed), HiddenAccounts.username(typed));
        }
        assertEquals(30, HiddenAccounts.username(xs(30)).length());
        signIn("1");
        assertNull(HiddenAccounts.add("not a name"));
        assertTrue(HiddenAccounts.saved().isEmpty());
    }

    /** A hidden account's posts leave Home, everyone else's stay, and removing the name brings them back. */
    @Test
    public void addingHidesAndRemovingShowsAgain() {
        signIn("1");
        assertEquals("nasa", HiddenAccounts.add("@NASA"));
        assertEquals("nasa", HiddenAccounts.add("nasa"));
        assertEquals(Collections.singletonList("nasa"), HiddenAccounts.saved());

        assertNull(home(new Item("nasa")));
        assertNull("matched in any case", home(new Item("NASA")));
        Item other = new Item("esa");
        assertSame(other, home(other));
        Item unknown = new Item(null);
        assertSame("a post whose author can't be read stays", unknown, home(unknown));
        assertTrue(FeedSuggestions.typesTookOut);

        HiddenAccounts.remove("nasa");
        assertTrue(HiddenAccounts.saved().isEmpty());
        Item back = new Item("nasa");
        assertSame(back, home(back));
    }

    /** The list is saved, so it's there after a restart, before and after Home's session comes back. */
    @Test
    public void theListSurvivesARestart() {
        signIn("1");
        HiddenAccounts.add("nasa");
        HiddenAccounts.add("esa");

        HiddenAccounts.resetForTests();
        assertEquals("before Home is set up, the saved account's list", Arrays.asList("nasa", "esa"), HiddenAccounts.saved());
        assertNull(home(new Item("esa")));

        signIn("1");
        assertEquals(Arrays.asList("nasa", "esa"), HiddenAccounts.saved());
        assertEquals("1", Settings.FEED_ACCOUNT.savedValue());
    }

    /** Each signed-in account keeps its own list; a name kept before any account was seen counts for all. */
    @Test
    public void eachAccountKeepsItsOwnList() {
        HiddenAccounts.add("everyone");
        signIn("1");
        HiddenAccounts.add("nasa");
        signIn("2");
        assertEquals(Collections.singletonList("everyone"), HiddenAccounts.saved());
        Item nasa = new Item("nasa");
        assertSame("account 2 didn't hide nasa", nasa, home(nasa));
        assertNull(home(new Item("everyone")));
        HiddenAccounts.add("esa");

        signIn("1");
        assertEquals(Arrays.asList("everyone", "nasa"), HiddenAccounts.saved());
        assertNull(home(new Item("nasa")));
        Item esa = new Item("esa");
        assertSame("account 1 didn't hide esa", esa, home(esa));

        HiddenAccounts.remove("everyone");
        signIn("2");
        assertEquals("a name kept for all goes for all", Collections.singletonList("esa"), HiddenAccounts.saved());
    }

    /** Paused, every post shows, and the list keeps what you chose. */
    @Test
    public void pausedAndUnreadyShowEveryPost() {
        signIn("1");
        HiddenAccounts.add("nasa");
        Item item = new Item("nasa");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(item, home(item));
        assertEquals(Collections.singletonList("nasa"), HiddenAccounts.saved());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(item, home(item)));

        assertNull(home(item));
    }

    /** A session that can't say its account, or a throwing read, leaves the account as it was. */
    @Test
    public void aSessionThatCantSayLeavesTheAccount() {
        signIn("1");
        HiddenAccounts.homeSession(null, idOf);
        HiddenAccounts.homeSession(new Session(""), idOf);
        HiddenAccounts.homeSession(new Session("2"), session -> {
            throw new IllegalStateException("no user");
        });
        assertEquals("1", HiddenAccounts.current());
        assertNull("as built the stub can't say", HiddenAccounts.accountOf(new Session("3")));
        HiddenAccounts.homeSession(new Session("3"));
        assertEquals("1", HiddenAccounts.current());
        String missing = HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'home account'"));
    }

    @Test
    public void aThrowingAuthorReadKeepsThePostAndIsReported() {
        signIn("1");
        HiddenAccounts.add("nasa");
        Item item = new Item("nasa");
        assertSame(item, FeedSuggestions.homeItem(item, photo, notLiked, ignored -> {
            throw new IllegalStateException("the post went away");
        }));
        String missing = HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'hidden account'"));
    }

    /** The stub the patch fills answers null as built, so an unpatched Home keeps every post. */
    @Test
    public void asBuiltNoPostHasAnAuthor() {
        signIn("1");
        HiddenAccounts.add("nasa");
        Item item = new Item("nasa");
        assertNull(FeedSuggestions.author(item));
        assertSame(item, FeedSuggestions.homeItem(item));
    }

    /** A Home emptied by the list ends while a name is on it, and gets Instagram's answer back once it's empty. */
    @Test
    public void aHomeEmptiedByTheListEndsWhileANameIsOnIt() {
        signIn("1");
        HiddenAccounts.add("nasa");
        home(new Item("nasa"));
        assertEquals(1, FeedSuggestions.feedEnded(0));
        HiddenAccounts.remove("nasa");
        assertEquals(0, FeedSuggestions.feedEnded(0));
    }

    /** A post of a hidden type goes for its type; the list is asked only about what's left. */
    @Test
    public void besideATypeSwitchEachTakesOutItsOwn() {
        signIn("1");
        HiddenAccounts.add("nasa");
        Settings.HIDE_FEED_VIDEOS.save(true);
        assertNull(FeedSuggestions.homeItem(new Item("nasa"), item -> FeedSuggestions.VIDEO, notLiked, authorOf));
        assertEquals(0, authorReads);
        assertNull(home(new Item("nasa")));
        assertEquals(1, authorReads);
    }

    @Test
    public void theReportCountsTheHiddenAccountsPosts() {
        signIn("1");
        HiddenAccounts.add("nasa");
        home(new Item("nasa"));
        home(new Item("nasa"));
        home(new Item("esa"));
        String routes = FeedFilterCounters.report().toString();
        assertTrue(routes, routes.contains(HiddenAccounts.HIDDEN));
        String family = HookStatus.report().toString();
        assertTrue(family, family.contains(HiddenAccounts.REMOVED + " 2"));
    }
}
