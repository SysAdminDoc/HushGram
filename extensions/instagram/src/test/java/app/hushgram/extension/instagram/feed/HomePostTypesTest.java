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

import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide videos, Hide photos and Hide carousels: which of Home's items go, and when Home ends. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HomePostTypesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final BooleanSetting[] SWITCHES =
            {Settings.HIDE_FEED_VIDEOS, Settings.HIDE_FEED_PHOTOS, Settings.HIDE_FEED_CAROUSELS};

    /** A feed item carrying a post of [type], 0 for an item with no post. */
    private static final class Item {
        final int type;

        Item(int type) {
            this.type = type;
        }
    }

    /** Reads an item's type the way the patched stub does, counting the reads. */
    private int reads;
    private final ToIntFunction<Object> typeOf = item -> {
        reads++;
        return ((Item) item).type;
    };

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        FeedSuggestions.typesTookOut = false;
        FeedSuggestions.tookOut = false;
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        for (BooleanSetting setting : SWITCHES) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        FeedSuggestions.typesTookOut = false;
        FeedSuggestions.tookOut = false;
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @Test
    public void everyTypeSwitchStartsOff() {
        for (BooleanSetting setting : SWITCHES) {
            setting.resetToDefault();
            assertFalse(setting.key, setting.get());
        }
    }

    /** With every switch off Home keeps everything, and no item's post is read at all. */
    @Test
    public void allOffKeepsEveryItemAndReadsNothing() {
        for (int type : new int[] {0, FeedSuggestions.PHOTO, FeedSuggestions.VIDEO, FeedSuggestions.CAROUSEL}) {
            Item item = new Item(type);
            assertSame(item, FeedSuggestions.homeItem(item, typeOf));
        }
        assertEquals(0, reads);
        assertFalse(FeedSuggestions.typesTookOut);
    }

    /** Each switch takes out its own type alone, and with all three on each type goes. */
    @Test
    public void eachSwitchTakesOutOnlyItsType() {
        int[] types = {FeedSuggestions.VIDEO, FeedSuggestions.PHOTO, FeedSuggestions.CAROUSEL};
        for (int on = 0; on < SWITCHES.length; on++) {
            for (BooleanSetting setting : SWITCHES) setting.save(false);
            SWITCHES[on].save(true);
            for (int type : types) {
                Item item = new Item(type);
                Object kept = FeedSuggestions.homeItem(item, typeOf);
                if (type == types[on]) assertNull(SWITCHES[on].key + " type " + type, kept);
                else assertSame(SWITCHES[on].key + " type " + type, item, kept);
            }
        }
        for (BooleanSetting setting : SWITCHES) setting.save(true);
        for (int type : types) assertNull("type " + type, FeedSuggestions.homeItem(new Item(type), typeOf));
        assertTrue(FeedSuggestions.typesTookOut);
    }

    /** An item with no post, a post that doesn't say, or a type that's none of the three stays. */
    @Test
    public void anItemWithNoKnownTypeStays() {
        for (BooleanSetting setting : SWITCHES) setting.save(true);
        for (int type : new int[] {0, 3, 11, -1}) {
            Item item = new Item(type);
            assertSame("type " + type, item, FeedSuggestions.homeItem(item, typeOf));
        }
        assertNull(FeedSuggestions.homeItem(null, typeOf));
        assertFalse(FeedSuggestions.typesTookOut);
    }

    @Test
    public void pausedAndUnreadyKeepEveryItem() {
        Settings.HIDE_FEED_VIDEOS.save(true);
        Item video = new Item(FeedSuggestions.VIDEO);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(video, FeedSuggestions.homeItem(video, typeOf));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(video, FeedSuggestions.homeItem(video, typeOf)));

        assertNull(FeedSuggestions.homeItem(video, typeOf));
    }

    @Test
    public void aThrowingReadKeepsTheItemAndIsReported() {
        Settings.HIDE_FEED_PHOTOS.save(true);
        Item item = new Item(FeedSuggestions.PHOTO);
        assertSame(item, FeedSuggestions.homeItem(item, ignored -> {
            throw new IllegalStateException("the post went away");
        }));
        assertFalse(FeedSuggestions.typesTookOut);
        String missing = HookStatus.missing(FamilyNames.FEED_SUGGESTIONS).toString();
        assertTrue(missing, missing.contains("'post type'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** The stub the patch fills answers 0 as built, so an unpatched Home keeps every item. */
    @Test
    public void asBuiltNoItemHasAType() {
        for (BooleanSetting setting : SWITCHES) setting.save(true);
        Item item = new Item(FeedSuggestions.VIDEO);
        assertEquals(0, FeedSuggestions.mediaType(item));
        assertSame(item, FeedSuggestions.homeItem(item));
    }

    /**
     * Once a post type's been taken out there's no next page while a type switch is on, so a Home
     * emptied by them gets Instagram's empty feed card. Off brings Instagram's answer back.
     */
    @Test
    public void aHomeEmptiedByTypesEndsUntilTheSwitchesGoOff() {
        Settings.HIDE_FEED_CAROUSELS.save(true);
        assertEquals("nothing taken out yet", 0, FeedSuggestions.feedEnded(0));
        FeedSuggestions.homeItem(new Item(FeedSuggestions.PHOTO), typeOf);
        assertEquals("a kept photo", 0, FeedSuggestions.feedEnded(0));

        FeedSuggestions.homeItem(new Item(FeedSuggestions.CAROUSEL), typeOf);
        assertEquals(1, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));

        Settings.HIDE_FEED_CAROUSELS.save(false);
        assertEquals(0, FeedSuggestions.feedEnded(0));
        assertEquals(1, FeedSuggestions.feedEnded(1));
        Settings.HIDE_FEED_VIDEOS.save(true);
        assertEquals("another type switch", 1, FeedSuggestions.feedEnded(0));
    }

    /** The report counts the types read and the posts taken out on a route of their own. */
    @Test
    public void theReportCountsTheTypes() {
        Settings.HIDE_FEED_VIDEOS.save(true);
        FeedSuggestions.homeItem(new Item(FeedSuggestions.VIDEO), typeOf);
        FeedSuggestions.homeItem(new Item(FeedSuggestions.PHOTO), typeOf);
        FeedSuggestions.homeItem(new Item(0), typeOf);
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains(FeedSuggestions.TYPES_ROUTE));
        assertTrue(report, report.contains("video"));
        assertTrue(report, report.contains("photo"));
        assertTrue(report, report.contains(FeedSuggestions.OTHER_TYPE));
        assertFalse("not on the suggestions' route: " + report, report.contains(FeedSuggestions.ROUTE));
    }
}
