/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Long press a photo to zoom: a Feed row that starts off, comes after Start feed videos with sound, says it takes the place of Instagram's long press, is kept in a backup and answers off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class LongPressZoomSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.LONG_PRESS_TO_ZOOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.LONG_PRESS_TO_ZOOM.resetToDefault();
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
    @Test public void theSwitchIsUnderFeedAfterFeedSoundAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SOUND, PatchFamily.LONG_PRESS_ZOOM));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.LONG_PRESS_TO_ZOOM.key);
        assertNotNull(row);
        assertEquals("Long press a photo to zoom", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("takes the place of Instagram's own long press"));
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(Settings.LONG_PRESS_TO_ZOOM.get());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        assertEquals(Arrays.toString(keys), Settings.START_FEED_VIDEOS_WITH_SOUND.key,
                keys[Arrays.asList(keys).indexOf(Settings.LONG_PRESS_TO_ZOOM.key) - 1]);
        assertEquals("Long press to zoom", PatchFamily.LONG_PRESS_ZOOM.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.LONG_PRESS_TO_ZOOM.key));
    }
    @Test public void theSwitchAloneStillMakesTheFeedSection() throws Exception {
        open(EnumSet.of(PatchFamily.LONG_PRESS_ZOOM));
        android.preference.Preference row = page.getPreferenceScreen().findPreference(Settings.LONG_PRESS_TO_ZOOM.key);
        assertNotNull(row);
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
    }
    @Test public void aSavedSwitchAnswersOffWhilePaused() throws Exception {
        open(EnumSet.of(PatchFamily.LONG_PRESS_ZOOM));
        Settings.LONG_PRESS_TO_ZOOM.save(true);
        assertTrue(Settings.LONG_PRESS_TO_ZOOM.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.LONG_PRESS_TO_ZOOM.get());
        assertTrue(Settings.LONG_PRESS_TO_ZOOM.savedValue());
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_SOUND));
        assertNull(page.getPreferenceScreen().findPreference(Settings.LONG_PRESS_TO_ZOOM.key));
    }
}
