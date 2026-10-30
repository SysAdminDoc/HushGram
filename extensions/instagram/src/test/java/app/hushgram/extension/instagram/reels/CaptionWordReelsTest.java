/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

/** Which reels a page keeps by the words in their captions, and that the page comes back as it came whenever the hook can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CaptionWordReelsTest {
    /** A reel's post, with its caption text, which can be null. */
    static final class Post {
        final String caption;

        Post(String caption) {
            this.caption = caption;
        }
    }

    /** A Reels item carrying a post, which can be null. */
    static final class Clip {
        final Post post;

        Clip(Post post) {
            this.post = post;
        }
    }

    /** Reads a Clip the way the patch-filled stub and the caption bridges read Instagram's item, counting each read. */
    static class Reader implements CaptionWordReels.Reader {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Object media(Object item) {
            reads.incrementAndGet();
            return item instanceof Clip ? ((Clip) item).post : null;
        }

        @Override
        public String caption(Object media) {
            return ((Post) media).caption;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Reader reader = new Reader();

    /** The For you lane's module name, as the Reels tab's viewer hands it over. */
    private static final String REELS_TAB = "clips_viewer_clips_tab";

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_CAPTION_WORD_REELS.save(true);
        Settings.REELS_BLOCKED_WORDS.save("spoiler, #ad\ngiveaway");
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_CAPTION_WORD_REELS.resetToDefault();
        Settings.REELS_BLOCKED_WORDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private List<?> page(List<?> items) {
        return page(items, REELS_TAB);
    }

    private List<?> page(List<?> items, String module) {
        return CaptionWordReels.page(items, module, reader, CaptionWordReels::wordsWhenOn);
    }

    private static Clip clip(String caption) {
        return new Clip(new Post(caption));
    }

    /** On, a reel whose caption holds a word from the list goes, whatever its case, and is counted. */
    @Test
    public void reelsWhoseCaptionHoldsABlockedWordAreTakenOut() {
        Clip plain = clip("Sunset at the lake");
        Clip spoiler = clip("Big SPOILER for the finale");
        Clip tagged = clip("New drop #Ad #summer");
        Clip giveaway = clip("Giveaway time, tag a friend");
        Clip noCaption = clip(null);
        List<Clip> items = Arrays.asList(plain, spoiler, tagged, giveaway, noCaption);

        List<?> kept = page(items);

        assertEquals(Arrays.asList(plain, noCaption), kept);
        assertNotSame(items, kept);
        assertEquals("the page handed in is left as it was", 5, items.size());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Caption word reels: 1 lists, 5 items, 3 removed"));
        assertTrue(report, report.contains("blocked word 3"));
        assertTrue(HookStatus.missing(FamilyNames.CAPTION_WORD_REELS).toString(),
                HookStatus.missing(FamilyNames.CAPTION_WORD_REELS).isEmpty());
    }

    /**
     * The list splits at commas and new lines, as Hushfeed's does, so a list from there pastes in.
     * Pieces are trimmed and lower case, the empty ones and repeats are left out, and a phrase stays
     * one piece.
     */
    @Test
    public void theListSplitsAtCommasAndNewLines() {
        assertEquals(Arrays.asList("spoiler", "#ad", "giveaway"), CaptionWordReels.words("spoiler, #ad\ngiveaway"));
        assertEquals(Arrays.asList("link in bio", "crypto"),
                CaptionWordReels.words("  Link in Bio ,\r\n\r\nCRYPTO,, crypto ,\n"));
        assertEquals(Collections.emptyList(), CaptionWordReels.words(""));
        assertEquals(Collections.emptyList(), CaptionWordReels.words(" ,\n, "));
        assertEquals(Collections.emptyList(), CaptionWordReels.words(null));

        assertTrue(CaptionWordReels.holdsAny("Check the LINK IN BIO", CaptionWordReels.words("link in bio")));
        assertFalse(CaptionWordReels.holdsAny("link, in bio", CaptionWordReels.words("link in bio")));
        assertFalse(CaptionWordReels.holdsAny(null, CaptionWordReels.words("crypto")));
        assertFalse(CaptionWordReels.holdsAny("", CaptionWordReels.words("crypto")));
    }

    /** A changed list is split again on the next page, and the same list isn't. */
    @Test
    public void aChangedListIsReadOnTheNextPage() {
        Clip cat = clip("my cat");
        Clip dog = clip("my dog");
        List<Clip> items = Arrays.asList(cat, dog);

        Settings.REELS_BLOCKED_WORDS.save("cat");
        assertEquals(Collections.singletonList(dog), page(items));
        assertSame(CaptionWordReels.wordsWhenOn(), CaptionWordReels.wordsWhenOn());

        Settings.REELS_BLOCKED_WORDS.save("dog");
        assertEquals(Collections.singletonList(cat), page(items));
    }

    /** No post, anything that isn't a reel and a null item all stay, and a page with no match comes back as it came. */
    @Test
    public void itemsWithoutACaptionStay() {
        Clip noPost = new Clip(null);
        Object card = "a spoiler card";
        Clip spoiler = clip("spoiler");
        Clip plain = clip("lake");
        List<Object> items = Arrays.asList(noPost, card, null, spoiler, plain);

        assertEquals(Arrays.asList(noPost, card, null, plain), page(items));

        List<Object> nothingMatches = Arrays.asList(noPost, card, plain);
        assertSame(nothingMatches, page(nothingMatches));
    }

    /** A page where every reel would go comes back whole, so the viewer has a reel to show, and that's counted. */
    @Test
    public void aPageWhereEveryReelMatchesIsKeptWhole() {
        List<Clip> items = Arrays.asList(clip("spoiler"), clip("giveaway"));
        assertSame(items, page(items));
        List<Object> withCard = Arrays.asList("a suggestion card", clip("#ad"), new Clip(null));
        assertSame(withCard, page(withCard));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Caption word reels: 2 lists, 5 items, 0 removed"));
        assertTrue(report, report.contains("page kept whole 2"));
    }

    /** Off, paused, with an empty list or before the settings are read, the page comes back as it came, read by nothing. */
    @Test
    public void offPausedEmptyAndUnreadyKeepEveryReel() {
        List<Clip> items = Arrays.asList(clip("spoiler"), clip("lake"));

        Settings.HIDE_CAPTION_WORD_REELS.save(false);
        assertSame(items, page(items));
        Settings.HIDE_CAPTION_WORD_REELS.save(true);

        Settings.REELS_BLOCKED_WORDS.save(" , ");
        assertSame(items, page(items));
        Settings.REELS_BLOCKED_WORDS.save("spoiler");

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(items, page(items));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(items, page(items)));

        assertEquals("a reel was read while the switch was off", 0, reader.reads.get());
        assertEquals(Collections.singletonList(items.get(1)), page(items));
    }

    /** A reader or a list that throws leaves the page as it came and says which hook threw. */
    @Test
    public void aThrowingReaderOrListKeepsThePageAndIsReported() {
        List<Clip> items = Arrays.asList(clip("spoiler"), clip("lake"));
        CaptionWordReels.Reader broken = new Reader() {
            @Override
            public String caption(Object media) {
                throw new ClassCastException("Post cannot be cast to Media");
            }
        };

        assertSame(items, CaptionWordReels.page(items, REELS_TAB, broken, () -> Collections.singletonList("spoiler")));
        assertSame(items, CaptionWordReels.page(items, REELS_TAB, reader, () -> {
            throw new IllegalStateException("settings went away");
        }));

        String missing = HookStatus.missing(FamilyNames.CAPTION_WORD_REELS).toString();
        assertTrue(missing, missing.contains("'reels page'"));
        assertTrue(missing, missing.contains(ClassCastException.class.getName()));
        assertEquals(0, reader.reads.get());
    }

    /** Every Reels tab lane is filtered. A viewer the user opened on something they chose gets its page as it came. */
    @Test
    public void onlyTheReelsTabIsFiltered() {
        for (String lane : PaidPartnershipReels.PICKED_BY_INSTAGRAM) {
            Clip plain = clip("lake");
            assertEquals(lane, Collections.singletonList(plain), page(Arrays.asList(plain, clip("spoiler")), lane));
        }
        int reads = reader.reads.get();
        List<Clip> items = Arrays.asList(clip("spoiler"), clip("lake"));
        for (String module : new String[]{"clips_viewer_serp_top_clips_unit", "clips_viewer_clips_profile",
                "clips_viewer_audio_page", "clips_viewer_feed_saved_collections_clips", "clips_viewer_direct", "", null}) {
            assertSame(String.valueOf(module), items, page(items, module));
        }
        assertEquals("a reel the user went to was read", reads, reader.reads.get());
    }

    /** A null page stays null, an empty one comes back as it came, and the stub as built keeps every reel. */
    @Test
    public void nullEmptyAndUnpatchedPagesStay() {
        assertNull(CaptionWordReels.page(null, REELS_TAB));
        List<Clip> empty = Collections.emptyList();
        assertSame(empty, page(empty));

        List<Clip> items = Arrays.asList(clip("spoiler"), clip("lake"));
        assertSame(items, CaptionWordReels.page(items, REELS_TAB));
        assertNull(CaptionWordReels.media(items.get(0)));
    }
}
