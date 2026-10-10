/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** When Hide shopping takes the shopping bag off a post with products tagged in it. */
@RunWith(RobolectricTestRunner.class)
public class ProductTagsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FeedFilterCounters.Snapshot counters;
    private HookStatus.Snapshot hooks;

    @Before
    public void switchOn() {
        Settings.HIDE_FEED_SHOPPING.save(true);
        counters = FeedFilterCounters.snapshotAndClear();
        hooks = HookStatus.snapshotAndClear();
    }

    @After
    public void restore() {
        ProductTags.hides = ProductTags::hiding;
        PauseForTests.resume();
        Settings.HIDE_FEED_SHOPPING.resetToDefault();
        FeedFilterCounters.restore(counters);
        HookStatus.restore(hooks);
    }

    private static String report() {
        return String.join("\n", FeedFilterCounters.report());
    }

    @Test
    public void aPostsProductsLoseTheIndicatorWhileTheSwitchIsOn() {
        assertEquals(0, ProductTags.indicator(1));
        assertEquals(ProductTags.ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: PRODUCTS. Removed: PRODUCTS 1. "
                + "Kinds: PRODUCTS 1", report());
    }

    @Test
    public void aPostWithoutProductsGoesOnAsItWas() {
        assertEquals(0, ProductTags.indicator(0));
        assertEquals(ProductTags.ROUTE + ": 1 lists, 1 items, 0 removed", report());
    }

    @Test
    public void offPausedOrNotReadyTheIndicatorStays() {
        Settings.HIDE_FEED_SHOPPING.save(false);
        assertEquals("off", 1, ProductTags.indicator(1));
        Settings.HIDE_FEED_SHOPPING.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 1, ProductTags.indicator(1));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals("settings not ready", 1, ProductTags.indicator(1)));
        assertEquals(ProductTags.ROUTE + ": 3 lists, 3 items, 0 removed. Kinds: PRODUCTS 3", report());
        assertEquals("the control: on, the same post loses it", 0, ProductTags.indicator(1));
    }

    @Test
    public void aCheckThatThrowsKeepsTheIndicatorAndSaysSo() {
        ProductTags.hides = () -> {
            throw new IllegalStateException("settings went away");
        };
        assertEquals(1, ProductTags.indicator(1));
        String hooks = String.join("\n", HookStatus.report());
        assertTrue(hooks, hooks.contains("Product tag indicator"));
        assertTrue(hooks, hooks.contains(IllegalStateException.class.getName()));
        assertEquals(ProductTags.ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: PRODUCTS 1", report());
    }
}
