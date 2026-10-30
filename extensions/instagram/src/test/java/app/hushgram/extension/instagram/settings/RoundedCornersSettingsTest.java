/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import java.util.Arrays;
import java.util.EnumSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.feed.CornerSize;
import app.hushgram.extension.instagram.feed.ReelCorners;
import app.hushgram.extension.instagram.feed.StoryCorners;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Rounded corners on posts and on Reels: Feed switches that start off, with a Corner size list under them, all kept in a backup, and off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class RoundedCornersSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.ROUNDED_POST_CORNERS.resetToDefault();
        Settings.ROUNDED_REEL_CORNERS.resetToDefault();
        Settings.ROUNDED_STORY_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.ROUNDED_POST_CORNERS.resetToDefault();
        Settings.ROUNDED_REEL_CORNERS.resetToDefault();
        Settings.ROUNDED_STORY_CORNERS.resetToDefault();
        Settings.ROUNDED_CORNER_SIZE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    /** Settings are a dialog over Instagram's screen, which doesn't resume when they close, so flipping Reels on reaches it at once. */
    @Test public void flippingTheReelsSwitchOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.ROUNDED_REEL_CORNERS.key);
        try {
            assertFalse(ReelCorners.listeningForTests(controller.get()));
            assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
            // The switch saves after its listener, so the helper reads the value once the change is posted.
            Settings.ROUNDED_REEL_CORNERS.save(true);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertTrue(ReelCorners.listeningForTests(controller.get()));
        } finally {
            ReelCorners.resetForTests();
        }
    }
    @Test public void theSwitchAndItsListAreUnderFeedAfterTheZoomAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.LONG_PRESS_ZOOM, PatchFamily.ROUNDED_POST_CORNERS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.ROUNDED_POST_CORNERS.key);
        assertNotNull(row);
        assertEquals("Rounded corners on posts", row.getTitle().toString());
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.ROUNDED_POST_CORNERS.get());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.ROUNDED_POST_CORNERS.key);
        // The zoom's own rows, Long press a photo to zoom and then Keep the zoom open, come right before.
        assertEquals(Arrays.toString(keys), Settings.LONG_PRESS_TO_ZOOM.key, keys[at - 2]);
        assertEquals(Arrays.toString(keys), Settings.LONG_PRESS_ZOOM_STAYS_OPEN.key, keys[at - 1]);
        // Rounded corners on Reels (#362) and on stories (#363) sit between the posts switch and the list they share.
        assertEquals(Arrays.toString(keys), Settings.ROUNDED_REEL_CORNERS.key, keys[at + 1]);
        assertEquals(Arrays.toString(keys), Settings.ROUNDED_STORY_CORNERS.key, keys[at + 2]);
        assertEquals(Arrays.toString(keys), Settings.ROUNDED_CORNER_SIZE.key, keys[at + 3]);
        assertEquals("Rounded corners on Reels", feed.getPreference(at + 1).getTitle().toString());
        assertEquals("Rounded corners on stories", feed.getPreference(at + 2).getTitle().toString());
        assertFalse(Settings.ROUNDED_REEL_CORNERS.get());
        assertFalse(Settings.ROUNDED_STORY_CORNERS.get());

        HushgramPreferenceFragment.CornerSizeRow size = (HushgramPreferenceFragment.CornerSizeRow) feed.getPreference(at + 3);
        assertEquals("Corner size", size.getTitle().toString());
        assertEquals(Arrays.asList("Small", "Large"), Arrays.asList(size.getEntries()[0].toString(), size.getEntries()[1].toString()));
        assertEquals("SMALL", size.getValue());
        assertEquals("Small", size.getSummary().toString());
        assertFalse("the list waits for the switch", size.isEnabled());
        ((android.preference.SwitchPreference) row).setChecked(true);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(size.isEnabled());
        size.setValue("LARGE");
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(CornerSize.LARGE, Settings.ROUNDED_CORNER_SIZE.savedValue());
        assertEquals("Large", size.getSummary().toString());

        assertEquals("Rounded post corners", PatchFamily.ROUNDED_POST_CORNERS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_POST_CORNERS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_REEL_CORNERS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_STORY_CORNERS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_CORNER_SIZE.key));
    }
    /** The stories switch (#363, #368) wakes the size list alone and reaches the screen under settings at once. */
    @Test public void theStoriesSwitchAloneWakesTheListAndReachesTheScreen() throws Exception {
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        android.preference.SwitchPreference stories = (android.preference.SwitchPreference)
                page.getPreferenceScreen().findPreference(Settings.ROUNDED_STORY_CORNERS.key);
        Preference size = page.getPreferenceScreen().findPreference(Settings.ROUNDED_CORNER_SIZE.key);
        assertFalse(size.isEnabled());
        try {
            assertFalse(StoryCorners.listeningForTests(controller.get()));
            stories.setChecked(true);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertTrue(Settings.ROUNDED_STORY_CORNERS.savedValue());
            assertFalse("posts stay as they were", Settings.ROUNDED_POST_CORNERS.savedValue());
            assertFalse("reels stay as they were", Settings.ROUNDED_REEL_CORNERS.savedValue());
            assertTrue(size.isEnabled());
            assertTrue(stories.getOnPreferenceChangeListener().onPreferenceChange(stories, true));
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertTrue(StoryCorners.listeningForTests(controller.get()));
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertFalse(Settings.ROUNDED_STORY_CORNERS.get());
        } finally {
            StoryCorners.resetForTests();
        }
    }
    /** Peek at the next carousel page (#361) comes after Corner size, starts off, is kept in a backup and reaches the screen under settings. */
    @Test public void thePeekSwitchFollowsTheCornerSizeAndStartsOff() throws Exception {
        Settings.PEEK_CAROUSEL_PAGES.resetToDefault();
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        Preference size = page.getPreferenceScreen().findPreference(Settings.ROUNDED_CORNER_SIZE.key);
        PreferenceGroup feed = size.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.ROUNDED_CORNER_SIZE.key);
        assertEquals(Arrays.toString(keys), Settings.PEEK_CAROUSEL_PAGES.key, keys[at + 1]);
        Preference row = feed.getPreference(at + 1);
        assertEquals("Peek at the next carousel page", row.getTitle().toString());
        assertFalse(Settings.PEEK_CAROUSEL_PAGES.get());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PEEK_CAROUSEL_PAGES.key));
        try {
            assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
            Settings.PEEK_CAROUSEL_PAGES.save(true);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertTrue(app.hushgram.extension.instagram.feed.CarouselPeek.listeningForTests(controller.get()));
        } finally {
            app.hushgram.extension.instagram.feed.CarouselPeek.resetForTests();
            Settings.PEEK_CAROUSEL_PAGES.resetToDefault();
        }
    }
    /** The list follows either switch, so reels alone can be rounded at either size. */
    @Test public void theReelsSwitchAloneWakesTheList() throws Exception {
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        android.preference.SwitchPreference reels = (android.preference.SwitchPreference)
                page.getPreferenceScreen().findPreference(Settings.ROUNDED_REEL_CORNERS.key);
        Preference size = page.getPreferenceScreen().findPreference(Settings.ROUNDED_CORNER_SIZE.key);
        assertFalse(size.isEnabled());
        reels.setChecked(true);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(Settings.ROUNDED_REEL_CORNERS.savedValue());
        assertFalse("posts stay as they were", Settings.ROUNDED_POST_CORNERS.savedValue());
        assertTrue(size.isEnabled());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.ROUNDED_REEL_CORNERS.get());
    }
    @Test public void theSwitchAloneStillMakesTheFeedSection() throws Exception {
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.ROUNDED_POST_CORNERS.key);
        assertNotNull(row);
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.ROUNDED_POST_CORNERS));
        Settings.ROUNDED_POST_CORNERS.save(true);
        assertTrue(Settings.ROUNDED_POST_CORNERS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.ROUNDED_POST_CORNERS.get());
        assertTrue(Settings.ROUNDED_POST_CORNERS.savedValue());
    }
    @Test public void missingPatchHasNeitherRowNorBackupKey() throws Exception {
        open(EnumSet.of(PatchFamily.LONG_PRESS_ZOOM));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ROUNDED_POST_CORNERS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ROUNDED_CORNER_SIZE.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ROUNDED_REEL_CORNERS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ROUNDED_STORY_CORNERS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_REEL_CORNERS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_STORY_CORNERS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_POST_CORNERS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.ROUNDED_CORNER_SIZE.key));
    }
}
