/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.util.Collections;
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
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Reels on Home below the name switch (#788): under Feed right after Hide the line under names, off to start, no restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HomeReelsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HOME_REELS_BELOW_NAME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HOME_REELS_BELOW_NAME.resetToDefault();
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
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_HEADER_LINE));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HOME_REELS_BELOW_NAME.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_HEADER_LINE.key));
    }
    /** Without the other Feed patches, Feed still opens for this switch alone. */
    @Test public void aloneItStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.HOME_REELS_BELOW_NAME));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HOME_REELS_BELOW_NAME.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffAfterTheLineUnderNames() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS, PatchFamily.FEED_HEADER_LINE, PatchFamily.HOME_REELS_BELOW_NAME,
                PatchFamily.FEED_SAVE));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HOME_REELS_BELOW_NAME.key);
        SwitchPreference line = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_HEADER_LINE.key);
        assertNotNull(row);
        assertEquals("Reels on Home below the name", row.getTitle().toString());
        assertEquals("Shows reels on Home like other posts, with the name and the three-dot menu above the video "
                + "instead of on top of it. Posts already on screen change when they load again.", row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        assertSame(feed, line.getParent());
        assertEquals(line.getOrder() + 1, row.getOrder());
        assertFalse(row.isChecked());
        assertFalse(Settings.HOME_REELS_BELOW_NAME.get());
        assertFalse("each row of Home reads the switch as it's laid out, so no restart prompt", Settings.HOME_REELS_BELOW_NAME.rebootApp);
        assertEquals(Collections.singletonList(Settings.HOME_REELS_BELOW_NAME), PatchFamily.HOME_REELS_BELOW_NAME.switches);
        assertEquals("Reels on Home below the name", PatchFamily.HOME_REELS_BELOW_NAME.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HOME_REELS_BELOW_NAME.key));
    }
}
