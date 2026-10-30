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

import java.util.function.Function;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide reels by caption words' Also on Home (#192): a post on Home whose caption holds a word from
 * the list goes, through the same per-item filter as the post type switches, so a page it empties
 * ends the way theirs does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CaptionWordHomeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** A feed item whose post has [caption], null for a post without one. */
    private static final class Item {
        final String caption;

        Item(String caption) {
            this.caption = caption;
        }
    }

    private int captionReads;
    private final ToIntFunction<Object> photo = item -> FeedSuggestions.PHOTO;
    private final ToIntFunction<Object> notLiked = item -> 0;
    private final Function<Object, String> nobody = item -> "someone";
    private final Function<Object, String> captionOf = item -> {
        captionReads++;
        return ((Item) item).caption;
    };

    @Before
    public void start() {
        clear();
    }

    @After
    public void restore() {
        clear();
    }

    private void clear() {
        Settings.HIDE_CAPTION_WORD_REELS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_HOME.resetToDefault();
        Settings.REELS_BLOCKED_WORDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HiddenAccounts.resetForTests();
        FeedSuggestions.typesTookOut = false;
        FeedSuggestions.tookOut = false;
        FeedSuggestions.homePageLost = false;
        FeedSuggestions.pageLostEverything = false;
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private Object home(Item item) {
        return FeedSuggestions.homeItem(item, photo, notLiked, nobody, captionOf);
    }

    private static void wordsOn(String words) {
        Settings.HIDE_CAPTION_WORD_REELS.save(true);
        Settings.HIDE_CAPTION_WORD_HOME.save(true);
        Settings.REELS_BLOCKED_WORDS.save(words);
    }

    @Test
    public void aPostWhoseCaptionHoldsABlockedWordLeavesHome() {
        wordsOn("spoiler, #ad");
        Item match = new Item("Big SPOILER for the finale");
        Item tagged = new Item("new drop #ad");
        Item clean = new Item("a quiet walk");
        Item bare = new Item(null);

        assertNull(home(match));
        assertNull(home(tagged));
        assertSame(clean, home(clean));
        assertSame("a post with no caption stays", bare, home(bare));
        assertTrue(FeedSuggestions.typesTookOut);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FeedSuggestions.CAPTION_WORD_REMOVED));
    }

    /** Off, with Reels on alone, with an empty list or paused, Home keeps everything and reads no caption. */
    @Test
    public void homeWaitsForBothSwitchesAndAList() {
        Item match = new Item("spoiler");
        assertSame("everything off", match, home(match));

        Settings.HIDE_CAPTION_WORD_REELS.save(true);
        Settings.REELS_BLOCKED_WORDS.save("spoiler");
        assertSame("Reels alone leaves Home", match, home(match));

        Settings.HIDE_CAPTION_WORD_HOME.save(true);
        Settings.HIDE_CAPTION_WORD_REELS.save(false);
        assertSame("Also on Home waits for the Reels switch", match, home(match));

        wordsOn(" , ");
        assertSame("no words", match, home(match));

        wordsOn("spoiler");
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", match, home(match));
        assertEquals(0, captionReads);
        assertFalse(FeedSuggestions.typesTookOut);

        PauseForTests.resume();
        assertNull(home(match));
        assertEquals(1, captionReads);
    }

    /** A page of Home's response that loses every post to a caption word counts as emptied. */
    @Test
    public void aPageItEmptiesCountsAsLost() {
        wordsOn("spoiler");
        FeedSuggestions.homePageStarts();
        assertNull(home(new Item("spoiler one")));
        assertNull(home(new Item("spoiler two")));
        FeedSuggestions.homePageParsed();
        assertTrue(FeedSuggestions.homePageLost);
        assertTrue(FeedSuggestions.pageLostEverything);
    }

    /** A caption that can't be read leaves the post, and the report says what threw. */
    @Test
    public void aCaptionThatThrowsKeepsThePost() {
        wordsOn("spoiler");
        Item item = new Item("spoiler");
        assertSame(item, FeedSuggestions.homeItem(item, photo, notLiked, nobody, ignored -> {
            throw new IllegalStateException("moved");
        }));
        assertFalse(FeedSuggestions.typesTookOut);
    }
}
