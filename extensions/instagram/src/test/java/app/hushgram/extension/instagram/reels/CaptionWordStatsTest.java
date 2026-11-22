/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** How much Blocked words has hidden (#807): each thing once a run, the last 24 hours and in all. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CaptionWordStatsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Hour 500000 on the clock, in milliseconds, plus [minutes]. */
    private static long at(long hours, long minutes) {
        return (500_000L + hours) * CaptionWordStats.HOUR + minutes * 60_000L;
    }

    private long now;

    @Before
    public void prepare() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        CaptionWordStats.resetForTests();
        Settings.CAPTION_WORD_HIDDEN_TOTAL.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_HOURS.resetToDefault();
        now = at(0, 10);
        CaptionWordStats.clock = () -> now;
    }

    @After
    public void restore() {
        CaptionWordStats.resetForTests();
        Settings.CAPTION_WORD_HIDDEN_TOTAL.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_HOURS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_REELS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_COMMENTS.resetToDefault();
        Settings.REELS_BLOCKED_WORDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test
    public void nothingHiddenSaysNothing() {
        assertNull(CaptionWordStats.summary());
        assertEquals(0, CaptionWordStats.allTime());
    }

    /** A comment row built again as it scrolls back, or a post read twice, counts once. */
    @Test
    public void eachThingCountsOnceARun() {
        CaptionWordStats.hidden(null, "big spoiler ahead");
        CaptionWordStats.hidden(null, "big spoiler ahead");
        CaptionWordStats.hidden("someone", "big spoiler ahead");
        CaptionWordStats.hidden("someone", "another spoiler");

        assertEquals(3, CaptionWordStats.allTime());
        assertEquals(3, CaptionWordStats.lastDay());
        assertEquals("3 hidden in the last 24 hours, 3 in all", CaptionWordStats.summary());
    }

    /** The last day drops each hour once it's a full day old; the count in all keeps it. */
    @Test
    public void theLastDayLetsOldHoursGo() {
        CaptionWordStats.hidden(null, "one");
        now = at(23, 50);
        CaptionWordStats.hidden(null, "two");
        CaptionWordStats.hidden(null, "three");
        assertEquals(3, CaptionWordStats.lastDay());

        now = at(24, 0);
        assertEquals("hour 0 is a day old", 2, CaptionWordStats.lastDay());
        CaptionWordStats.hidden(null, "four");
        assertEquals("only the hours of the last day are kept", (500_023L) + ":2," + (500_024L) + ":1",
                Settings.CAPTION_WORD_HIDDEN_HOURS.get());
        now = at(60, 0);
        assertEquals(0, CaptionWordStats.lastDay());
        assertEquals(4, CaptionWordStats.allTime());
        assertEquals("0 hidden in the last 24 hours, 4 in all", CaptionWordStats.summary());
    }

    /** Pairs that aren't two numbers, hours from the future and empty counts are left out. */
    @Test
    public void aBrokenStoreKeepsWhatItCanRead() {
        TreeMap<Long, Long> hours = CaptionWordStats.hours("x:1, 7:y,3:2,,9,4:0,12:5,5:1,5:2", 10);
        assertEquals("{3=2, 5=3}", hours.toString());
        assertEquals("3:2,5:3", CaptionWordStats.text(hours));
        assertTrue(CaptionWordStats.hours(null, 10).isEmpty());
    }

    /** Before the settings are read nothing counts, and nothing throws. */
    @Test
    public void unreadySettingsCountNothing() {
        SettingsContextRule.withoutContext(() -> {
            CaptionWordStats.hidden(null, "spoiler");
            assertNull(CaptionWordStats.summary());
        });
        assertEquals(0, CaptionWordStats.allTime());
    }

    /** Pause HushGram keeps the counts showing as they were. */
    @Test
    public void pausedStillShowsTheCounts() {
        CaptionWordStats.hidden(null, "spoiler");
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        assertEquals(1, CaptionWordStats.allTime());
        assertEquals("1 hidden in the last 24 hours, 1 in all", CaptionWordStats.summary());
    }

    /** The Reels tab's page and a post's comments count what they take out, and nothing they keep. */
    @Test
    public void reelsAndCommentsCountWhatTheyTakeOut() {
        Settings.HIDE_CAPTION_WORD_REELS.save(true);
        Settings.HIDE_CAPTION_WORD_COMMENTS.save(true);
        Settings.REELS_BLOCKED_WORDS.save("spoiler");
        CaptionWordReelsTest.Reader reader = new CaptionWordReelsTest.Reader();
        List<Object> page = Arrays.asList(
                new CaptionWordReelsTest.Clip(new CaptionWordReelsTest.Post("a spoiler")),
                new CaptionWordReelsTest.Clip(new CaptionWordReelsTest.Post("a cat")),
                new CaptionWordReelsTest.Clip(new CaptionWordReelsTest.Post("SPOILER again")));

        assertEquals(1, CaptionWordReels.page(page, "clips_viewer_clips_tab", reader, CaptionWordReels::wordsWhenOn).size());
        assertEquals("the same page again counts nothing new", 1,
                CaptionWordReels.page(page, "clips_viewer_clips_tab", reader, CaptionWordReels::wordsWhenOn).size());
        assertEquals(2, CaptionWordStats.allTime());
        assertEquals("no @name in the list, no author read", 0, reader.authors.get());

        assertTrue(CaptionWordReels.dropsComment("no spoiler please", null));
        assertTrue(CaptionWordReels.dropsComment("no spoiler please", null));
        assertEquals(3, CaptionWordStats.allTime());

        List<Object> allMatch = Arrays.asList(
                new CaptionWordReelsTest.Clip(new CaptionWordReelsTest.Post("spoiler one")),
                new CaptionWordReelsTest.Clip(new CaptionWordReelsTest.Post("spoiler two")));
        assertEquals("a page kept whole hides nothing", 2,
                CaptionWordReels.page(allMatch, "clips_viewer_clips_tab", reader, CaptionWordReels::wordsWhenOn).size());
        assertEquals(3, CaptionWordStats.allTime());
    }
}
