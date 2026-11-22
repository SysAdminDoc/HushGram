/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Also in search (#807): the Explore grid's and search results' rows holding a blocked post leave
 * the page's own section list, found by walking each row the way Instagram's section, layout and
 * item classes hold their posts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CaptionWordSearchTest {
    /** A post, as Instagram's Media is to the walk. */
    static final class Post {
        final String caption;
        final String author;

        Post(String caption, String author) {
            this.caption = caption;
            this.author = author;
        }
    }

    /** A grid item holding its post, and a second one the way 02EO holds two Media. */
    static final class Item {
        final Post post;
        final Post other;
        final int width = 1;

        Item(Post post) {
            this(post, null);
        }

        Item(Post post, Post other) {
            this.post = post;
            this.other = other;
        }
    }

    /** A row's layout: one big item, a list of small ones, and a way back to its section. */
    static final class Layout {
        final Item big;
        final List<Item> small;
        Section back;

        Layout(Item big, Item... small) {
            this.big = big;
            this.small = new ArrayList<>(Arrays.asList(small));
        }
    }

    /** One row of the grid, the way Instagram's section reader makes it. */
    static final class Section {
        final String type = "media_grid";
        final Integer index = 3;
        final Layout layout;

        Section(Layout layout) {
            this.layout = layout;
            layout.back = this;
        }
    }

    /** A user, which the walk never goes into, holding a post of their own. */
    static final class User {
        final Post pinned;

        User(Post pinned) {
            this.pinned = pinned;
        }
    }

    /** A row whose item holds its post a step further down, behind its user. */
    static final class Profiled {
        final User user;

        Profiled(User user) {
            this.user = user;
        }
    }

    /** One link of a chain, to go deeper than the walk does. */
    static final class Link {
        final Object next;

        Link(Object next) {
            this.next = next;
        }
    }

    private static final Predicate<Class<?>> POSTS = type -> type == Post.class;
    private static final Predicate<Class<?>> OURS =
            type -> type.getName().startsWith(CaptionWordSearchTest.class.getName() + "$") && type != User.class;

    /** Reads a Post the way the caption bridges read Media, counting each read. */
    static class Reader implements CaptionWordReels.Reader {
        final AtomicInteger captions = new AtomicInteger();
        final AtomicInteger authors = new AtomicInteger();

        @Override
        public Object media(Object item) {
            throw new AssertionError("search rows are walked, not asked for their post");
        }

        @Override
        public String caption(Object media) {
            captions.incrementAndGet();
            return ((Post) media).caption;
        }

        @Override
        public String author(Object media) {
            authors.incrementAndGet();
            return ((Post) media).author;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Reader reader = new Reader();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_CAPTION_WORD_REELS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_SEARCH.resetToDefault();
        Settings.REELS_BLOCKED_WORDS.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_TOTAL.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_HOURS.resetToDefault();
        CaptionWordStats.resetForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private int take(List<?> sections, String words) {
        List<String> list = CaptionWordReels.words(words);
        return CaptionWordSearch.sections(sections, reader, () -> list, POSTS, OURS);
    }

    /**
     * A row with a post holding a word from the list, anywhere in its layout, or one posted by an
     * account the list names with @, leaves the page's own list, the posts beside it going with it.
     * The other rows stay in their order.
     */
    @Test
    public void rowsWithABlockedPostLeaveThePagesOwnList() {
        Section plain = new Section(new Layout(new Item(new Post("sunset", "anna")), new Item(new Post("beach", "ben"))));
        Section spoiler = new Section(new Layout(new Item(new Post("cats", "cara")),
                new Item(new Post("dogs", "dan")), new Item(new Post("Huge SPOILER ahead", "eve"))));
        Section news = new Section(new Layout(new Item(new Post("weather", "lee"), new Post("breaking", "CNN"))));
        Section quiet = new Section(new Layout(new Item(new Post(null, "mia"))));
        List<Section> sections = new ArrayList<>(Arrays.asList(plain, spoiler, news, quiet));

        assertEquals(2, take(sections, "spoiler, @cnn"));

        assertEquals(Arrays.asList(plain, quiet), sections);
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Caption word search: 1 lists, 4 items, 2 removed"));
        assertTrue(report, report.contains("blocked word 2"));
        assertTrue(HookStatus.missing(FamilyNames.CAPTION_WORD_REELS).toString(),
                HookStatus.missing(FamilyNames.CAPTION_WORD_REELS).isEmpty());
    }

    /** Without an @name on the list no author is read, and a list of only words matches captions. */
    @Test
    public void withoutAnAtNameNoAuthorIsRead() {
        List<Section> sections = new ArrayList<>(Arrays.asList(
                new Section(new Layout(new Item(new Post("giveaway time", "cnn")))),
                new Section(new Layout(new Item(new Post("lunch", "cnn"))))));

        assertEquals(1, take(sections, "giveaway"));

        assertEquals(1, sections.size());
        assertEquals(0, reader.authors.get());
    }

    /**
     * A page where every row would go keeps every one, so a search for a word on the list still shows
     * its results, and the report says so.
     */
    @Test
    public void aPageWhereEveryRowMatchesStaysWhole() {
        Section one = new Section(new Layout(new Item(new Post("spoiler one", "a"))));
        Section two = new Section(new Layout(new Item(new Post("spoiler two", "b"))));
        List<Section> sections = new ArrayList<>(Arrays.asList(one, two));

        assertEquals(0, take(sections, "spoiler"));

        assertEquals(Arrays.asList(one, two), sections);
        assertTrue(FeedFilterCounters.report().toString().contains("page kept whole 1"));
    }

    /** With no words, the switch off or before the settings are read, no row is walked or read. */
    @Test
    public void noWordsLeavesThePageUnread() {
        List<Section> sections = new ArrayList<>(Collections.singletonList(
                new Section(new Layout(new Item(new Post("spoiler", "a"))))));

        assertEquals(0, take(sections, ""));
        assertEquals(0, take(null, "spoiler"));
        assertEquals(0, take(new ArrayList<>(), "spoiler"));

        assertEquals(1, sections.size());
        assertEquals(0, reader.captions.get());
    }

    /**
     * The words come from the list only while the switch and Also in search are both on, and not
     * while paused.
     */
    @Test
    public void theWordsWaitForTheSwitchAndAlsoInSearch() {
        Settings.REELS_BLOCKED_WORDS.save("spoiler, @CNN");
        assertTrue(CaptionWordReels.searchWordsWhenOn().isEmpty());
        Settings.HIDE_CAPTION_WORD_SEARCH.save(true);
        assertTrue("the switch is still off", CaptionWordReels.searchWordsWhenOn().isEmpty());
        Settings.HIDE_CAPTION_WORD_REELS.save(true);
        assertEquals(Arrays.asList("spoiler", "@cnn"), CaptionWordReels.searchWordsWhenOn());
        Settings.HIDE_CAPTION_WORD_SEARCH.save(false);
        assertTrue(CaptionWordReels.searchWordsWhenOn().isEmpty());
        Settings.HIDE_CAPTION_WORD_SEARCH.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue("paused", CaptionWordReels.searchWordsWhenOn().isEmpty());
    }

    /**
     * The walk finds a row's posts in item fields and lists, comes back from a loop to the section
     * without going round again, and doesn't go into a user.
     */
    @Test
    public void theWalkFindsARowsPostsAndStopsAtUsers() {
        Post big = new Post("a", "x");
        Post second = new Post("b", "y");
        Post small = new Post("c", "z");
        Post pinned = new Post("spoiler", "w");
        Section section = new Section(new Layout(new Item(big, second), new Item(small)));
        section.layout.small.add(null);

        List<Object> found = CaptionWordSearch.medias(section, POSTS, OURS);
        assertEquals(3, found.size());
        assertEquals(new HashSet<>(Arrays.asList(big, second, small)), new HashSet<>(found));

        assertTrue(CaptionWordSearch.medias(new Profiled(new User(pinned)), POSTS, OURS).isEmpty());
        List<Section> sections = new ArrayList<>(Arrays.asList(
                new Section(new Layout(new Item(new Post("lunch", "a")))),
                new Section(new Layout(new Item(new Post("dinner", "b"))))));
        @SuppressWarnings("unchecked")
        List<Object> row = (List<Object>) (List<?>) sections.get(0).layout.small;
        row.add(new Item(null));
        row.add(new Profiled(new User(pinned)));
        assertEquals("a user's own post doesn't take the row", 0, take(sections, "spoiler"));
        assertEquals(2, sections.size());
    }

    /**
     * The walk goes [CaptionWordSearch.DEPTH] steps down at most and looks at
     * [CaptionWordSearch.VISITS] objects at most, so a row that leads somewhere big ends.
     */
    @Test
    public void theWalkStaysShallowAndSmall() {
        Post deep = new Post("spoiler", "a");
        Object chain = deep;
        for (int i = 0; i < CaptionWordSearch.DEPTH; i++) chain = new Link(chain);
        assertEquals(Collections.singletonList(deep), CaptionWordSearch.medias(chain, POSTS, OURS));
        assertTrue("one more step is too far", CaptionWordSearch.medias(new Link(chain), POSTS, OURS).isEmpty());

        List<Item> many = new ArrayList<>();
        for (int i = 0; i < 3 * CaptionWordSearch.VISITS; i++) many.add(new Item(new Post("p" + i, "a")));
        int found = CaptionWordSearch.medias(many, POSTS, OURS).size();
        assertTrue("found " + found, found > 0 && found < CaptionWordSearch.VISITS);
    }

    /** A failure leaves the page as it came and is reported. */
    @Test
    public void aFailureLeavesThePage() {
        List<Section> sections = new ArrayList<>(Arrays.asList(
                new Section(new Layout(new Item(new Post("spoiler", "a")))),
                new Section(new Layout(new Item(new Post("lunch", "b"))))));
        CaptionWordReels.Reader broken = new Reader() {
            @Override
            public String caption(Object media) {
                throw new IllegalStateException("caption went away");
            }
        };
        List<String> words = CaptionWordReels.words("spoiler");

        assertEquals(0, CaptionWordSearch.sections(sections, broken, () -> words, POSTS, OURS));

        assertEquals(2, sections.size());
        String missing = HookStatus.missing(FamilyNames.CAPTION_WORD_REELS).toString();
        assertTrue(missing, missing.contains("'search sections'"));
    }

    /** The hook the patch calls reads Instagram's own post class by its kept name and never throws. */
    @Test
    public void theHookReadsInstagramsPostClassByName() {
        assertEquals("com.instagram.feed.media.Media", CaptionWordSearch.MEDIA);
        List<Section> sections = new ArrayList<>(Collections.singletonList(
                new Section(new Layout(new Item(new Post("spoiler", "a"))))));
        Settings.HIDE_CAPTION_WORD_REELS.save(true);
        Settings.HIDE_CAPTION_WORD_SEARCH.save(true);
        Settings.REELS_BLOCKED_WORDS.save("spoiler");

        CaptionWordSearch.sections(sections);
        CaptionWordSearch.sections(null);

        assertEquals("these test rows hold no Instagram post", 1, sections.size());
        assertTrue(HookStatus.missing(FamilyNames.CAPTION_WORD_REELS).toString(),
                HookStatus.missing(FamilyNames.CAPTION_WORD_REELS).isEmpty());
    }
}
