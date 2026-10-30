/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Add Yours cards between stories, midcards and the trending prompts netego, by Instagram 451's type names (#111). */
@RunWith(RobolectricTestRunner.class)
public class AddYoursCardsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String PROMPTS = "story_interstitial_add_yours_midcard";
    private static final String TEMPLATES = "story_interstitial_ayu_midcard";
    private static final String TRENDING = "story_interstitial_trending_midcard";
    private static final String TRENDING_NETEGO = "trending_prompts_in_story";
    private static final String SUGGESTED = "story_interstitial_suggested_users_midcard";

    @Before
    public void start() {
        reset();
    }

    @After
    public void restore() {
        reset();
    }

    private static void reset() {
        Settings.HIDE_ADD_YOURS_CARDS.resetToDefault();
        Settings.HIDE_STORY_SUGGESTIONS.resetToDefault();
        Settings.HIDE_MUSIC_CARD.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.snapshotAndClear();
    }

    /** Off, every Add Yours card stays, and each is still counted under the switch's route. */
    @Test
    public void offEveryAddYoursCardStays() {
        assertFalse(Settings.HIDE_ADD_YOURS_CARDS.get());
        assertFalse(StoriesTray.midcard(PROMPTS));
        assertFalse(StoriesTray.midcard(TEMPLATES));
        assertFalse(StoriesTray.midcard(TRENDING));
        assertFalse(StoriesTray.netego(TRENDING_NETEGO));

        String route = lineOf(StoriesTray.ADD_YOURS_ROUTE);
        assertTrue(FeedFilterCounters.report().toString(), route != null);
        assertTrue(route, route.contains(TRENDING + " 1"));
        assertTrue(route, route.contains(TRENDING_NETEGO + " 1"));
    }

    /** On, the Add Yours midcards and the trending prompts netego go; the Suggested for you page and the music card stay. */
    @Test
    public void onTheAddYoursCardsGo() {
        Settings.HIDE_ADD_YOURS_CARDS.save(true);
        assertTrue(StoriesTray.midcard(PROMPTS));
        assertTrue(StoriesTray.midcard(TEMPLATES));
        assertTrue(StoriesTray.midcard(TRENDING));
        assertTrue(StoriesTray.netego(TRENDING_NETEGO));
        assertFalse("the Suggested for you page has its own switch", StoriesTray.midcard(SUGGESTED));
        assertFalse("the music card has its own flag", StoriesTray.midcard("story_interstitial_music_midcard"));
        assertFalse(StoriesTray.netego("suggested_users_in_story"));
        assertFalse("an ad", StoriesTray.netego(null));
    }

    /** Hide Suggested for you between stories alone leaves every Add Yours card. */
    @Test
    public void theSuggestionsSwitchLeavesTheAddYoursCards() {
        Settings.HIDE_STORY_SUGGESTIONS.save(true);
        assertTrue(StoriesTray.midcard(SUGGESTED));
        assertFalse(StoriesTray.midcard(TRENDING));
        assertFalse(StoriesTray.midcard(PROMPTS));
        assertFalse(StoriesTray.netego(TRENDING_NETEGO));
    }

    /** Paused, or before the settings are read, the cards stay. */
    @Test
    public void pausedAndUnreadyLeaveTheCards() {
        Settings.HIDE_ADD_YOURS_CARDS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", StoriesTray.midcard(TRENDING));
        assertFalse("paused", StoriesTray.netego(TRENDING_NETEGO));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse("no context", StoriesTray.midcard(TRENDING)));

        assertTrue("back on", StoriesTray.midcard(TRENDING));
    }

    private static String lineOf(String route) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(route + ":")) return line;
        }
        return null;
    }
}
