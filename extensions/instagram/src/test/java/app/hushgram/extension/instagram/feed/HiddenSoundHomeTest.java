/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

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

import app.hushgram.extension.instagram.reels.HiddenSounds;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide reels by sound's Also on Home (#822): a post on Home whose sound is on the Hidden sounds list
 * goes through the same per-item filter as the post type switches, while the switch and Also on Home
 * are on, and stays otherwise.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HiddenSoundHomeTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String HIDDEN = "1234567890";
    private static final String OTHER = "555";

    /** A feed item whose post uses the sound [sound], or none when null. */
    private static final class Item {
        final String sound;

        Item(String sound) {
            this.sound = sound;
        }
    }

    private final ToIntFunction<Object> video = item -> FeedSuggestions.VIDEO;
    private final ToIntFunction<Object> notLiked = item -> 0;
    private final ToIntFunction<Object> personal = item -> 0;
    private final Function<Object, String> nobody = item -> "someone";
    private final Function<Object, String> noCaption = item -> null;
    private final Function<Object, String> inside = item -> null;
    private final Function<Object, String> soundOf = item -> ((Item) item).sound;

    @Before
    public void start() {
        clear();
        HiddenSounds.add(new HiddenSounds.Sound(HIDDEN, "A song", "Someone"));
    }

    @After
    public void restore() {
        clear();
    }

    private void clear() {
        Settings.HIDE_SOUND_REELS.resetToDefault();
        Settings.HIDE_SOUND_HOME.resetToDefault();
        Settings.HIDDEN_SOUNDS.resetToDefault();
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

    private Object home(Object item) {
        return FeedSuggestions.homeItem(item, video, notLiked, nobody, noCaption, personal, inside, soundOf);
    }

    private static void homeOn() {
        Settings.HIDE_SOUND_REELS.save(true);
        Settings.HIDE_SOUND_HOME.save(true);
    }

    @Test
    public void aPostWithAHiddenSoundLeavesHomeAndIsCounted() {
        homeOn();
        Item hidden = new Item(HIDDEN);
        Item other = new Item(OTHER);
        Item silent = new Item(null);

        assertNull(home(hidden));
        assertSame(other, home(other));
        assertSame(silent, home(silent));
        assertTrue(FeedSuggestions.typesTookOut);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FeedSuggestions.HIDDEN_SOUND_REMOVED + " 1"));
        String counters = FeedFilterCounters.report().toString();
        assertTrue(counters, counters.contains(FeedSuggestions.HIDDEN_SOUND + " 1"));
    }

    /** Without Also on Home, or with Hide reels by sound off, a post with a hidden sound stays on Home. */
    @Test
    public void bothSwitchesHaveToBeOn() {
        Item hidden = new Item(HIDDEN);
        assertSame("both off", hidden, home(hidden));
        Settings.HIDE_SOUND_REELS.save(true);
        assertSame("Also on Home off", hidden, home(hidden));
        Settings.HIDE_SOUND_REELS.save(false);
        Settings.HIDE_SOUND_HOME.save(true);
        assertSame("Hide reels by sound off", hidden, home(hidden));
        assertFalse(FeedSuggestions.typesTookOut);
    }

    /** Paused, Home keeps every post. */
    @Test
    public void pausedKeepsThePost() {
        homeOn();
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Item hidden = new Item(HIDDEN);
        assertSame(hidden, home(hidden));
    }

    /** A page of Home's response that loses every post to a hidden sound counts as emptied. */
    @Test
    public void aPageItEmptiesCountsAsLost() {
        homeOn();
        FeedSuggestions.homePageStarts();
        assertNull(home(new Item(HIDDEN)));
        assertNull(home(new Item(HIDDEN)));
        FeedSuggestions.homePageParsed();
        assertTrue(FeedSuggestions.homePageLost);
        assertTrue(FeedSuggestions.pageLostEverything);
    }

    /** A post whose sound can't be read stays, and the report says what threw. */
    @Test
    public void aReadThatThrowsKeepsThePost() {
        homeOn();
        Item item = new Item(HIDDEN);
        assertSame(item, FeedSuggestions.homeItem(item, video, notLiked, nobody, noCaption, personal, inside, ignored -> {
            throw new IllegalStateException("moved");
        }));
        assertFalse(FeedSuggestions.typesTookOut);
        assertTrue(HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString().contains(FeedSuggestions.HIDDEN_SOUND));
    }

    /** The real reader finds no sound in an item that carries no post. */
    @Test
    public void theRealReaderAnswersNothingForAnItemWithoutAPost() {
        homeOn();
        assertNull(FeedSuggestions.soundOf(new Object()));
    }
}
