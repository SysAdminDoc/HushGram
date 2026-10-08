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

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide the home feed: every feed item goes while the switch is on, and an emptied Home ends. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HomeFeedTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Any feed item, whatever its kind. */
    private static final class Item {
    }

    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_HOME_FEED.save(true);
        HomeFeed.tookOut = false;
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_HOME_FEED.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HomeFeed.tookOut = false;
        FeedSuggestions.tookOut = false;
        HookStatus.clear();
    }

    @Test
    public void withTheSwitchOnEveryItemGoes() {
        assertNull(HomeFeed.filter(new Item()));
        assertNull(HomeFeed.filter("a post"));
        assertNull(HomeFeed.filter(null));
        assertTrue(HomeFeed.tookOut);
        assertTrue(HookStatus.missing(FamilyNames.HOME_FEED).toString(), HookStatus.missing(FamilyNames.HOME_FEED).isEmpty());
        assertTrue(HookStatus.report().toString(), HookStatus.report().toString().contains(HomeFeed.TAKEN_OUT));
    }

    @Test
    public void offToStartAndOffKeepEveryItem() {
        Settings.HIDE_HOME_FEED.resetToDefault();
        assertFalse(Settings.HIDE_HOME_FEED.get());
        Item item = new Item();
        assertSame(item, HomeFeed.filter(item));
        Settings.HIDE_HOME_FEED.save(false);
        assertSame(item, HomeFeed.filter(item));
        assertFalse(HomeFeed.tookOut);
    }

    @Test
    public void pausedAndUnreadyKeepEveryItem() {
        Item item = new Item();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(item, HomeFeed.filter(item));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(item, HomeFeed.filter(item)));

        assertNull(HomeFeed.filter(item));
    }

    /** Once Home's been emptied there's no next page, and off brings Instagram's answer back. */
    @Test
    public void anEmptiedHomeEndsUntilTheSwitchGoesOff() {
        assertEquals("nothing taken out yet", 0, FeedSuggestions.feedEnded(0));
        HomeFeed.filter(new Item());
        assertEquals(1, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));

        Settings.HIDE_HOME_FEED.save(false);
        assertEquals(0, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));

        Settings.HIDE_HOME_FEED.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, FeedSuggestions.feedEnded(0));
    }

    @Test
    public void aThrowingSwitchKeepsTheItemAndIsReported() {
        Item item = new Item();
        assertSame(item, HomeFeed.filter(item, THROWS));
        assertFalse(HomeFeed.tookOut);

        String missing = HookStatus.missing(FamilyNames.HOME_FEED).toString();
        assertTrue(missing, missing.contains("'" + HomeFeed.SWITCH + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
