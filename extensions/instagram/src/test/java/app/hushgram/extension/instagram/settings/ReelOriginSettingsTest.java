/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
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

/**
 * The Find the reel you came from switch and its Wait for a tap to find it: last under Reels, off to
 * start, off while paused, and no restart needed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelOriginSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.FIND_REEL_ORIGIN.resetToDefault();
        Settings.FIND_REEL_ORIGIN_ON_TAP.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.FIND_REEL_ORIGIN.resetToDefault();
        Settings.FIND_REEL_ORIGIN_ON_TAP.resetToDefault();
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
    @Test public void missingPatchHasNoReelOriginSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_SCROLLING));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FIND_REEL_ORIGIN.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.FIND_REEL_ORIGIN_ON_TAP.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.STOP_REELS_SCROLLING.key));
    }
    /** Without the other Reels patches, Reels still opens for this switch alone. */
    @Test public void reelOriginAloneStillGetsReels() throws Exception {
        open(EnumSet.of(PatchFamily.REEL_ORIGIN));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FIND_REEL_ORIGIN.key);
        assertNotNull(row);
        assertEquals("Reels", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.STOP_REELS_SCROLLING.key));
    }
    @Test public void reelOriginSwitchStartsOffLastUnderReelsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_REELS, PatchFamily.REEL_SCROLLING, PatchFamily.REEL_ORIGIN));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FIND_REEL_ORIGIN.key);
        assertNotNull(row);
        assertEquals("Find the reel you came from", row.getTitle().toString());
        assertEquals("Open a creator's profile from a reel, then their Reels tab, and it scrolls to that reel and "
                + "outlines it for a moment. A photo reel is found in their posts. Stop looking ends the search where it is.",
                row.getSummary().toString());
        PreferenceGroup reels = row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        String[] keys = new String[reels.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = reels.getPreference(i).getKey();
        int cap = Arrays.asList(keys).indexOf(Settings.REEL_CAP.key);
        assertTrue(Arrays.toString(keys), cap >= 0);
        assertEquals("its two sliders follow Stop after a set number of reels", Settings.REEL_CAP_COUNT.key, keys[cap + 1]);
        assertEquals(Settings.REEL_CAP_BREAK.key, keys[cap + 2]);
        assertEquals("then Take a scroll break", Settings.SCROLL_BREAK.key, keys[cap + 3]);
        assertEquals("then this switch", Settings.FIND_REEL_ORIGIN.key, keys[cap + 4]);
        assertEquals("then Wait for a tap, last", Settings.FIND_REEL_ORIGIN_ON_TAP.key, keys[cap + 5]);
        assertEquals(keys.length - 1, cap + 5);
        assertFalse(row.isChecked());
        assertFalse(Settings.FIND_REEL_ORIGIN.get());
        assertFalse("each profile opened from a reel reads the switch, so no restart prompt", Settings.FIND_REEL_ORIGIN.rebootApp);
        assertEquals(Arrays.asList(Settings.FIND_REEL_ORIGIN, Settings.FIND_REEL_ORIGIN_ON_TAP), PatchFamily.REEL_ORIGIN.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FIND_REEL_ORIGIN.key));

        SwitchPreference tap = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.FIND_REEL_ORIGIN_ON_TAP.key);
        assertEquals("Wait for a tap to find it", tap.getTitle().toString());
        assertEquals("When the reel isn't on the first screen, the profile shows a Find the reel button instead of "
                + "scrolling on its own.", tap.getSummary().toString());
        assertFalse(tap.isChecked());
        assertFalse(Settings.FIND_REEL_ORIGIN_ON_TAP.defaultValue);
        assertFalse("read each time the tab comes up", Settings.FIND_REEL_ORIGIN_ON_TAP.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.FIND_REEL_ORIGIN_ON_TAP.key));
        Settings.FIND_REEL_ORIGIN_ON_TAP.save(true);
        assertTrue(Settings.FIND_REEL_ORIGIN_ON_TAP.get());

        Settings.FIND_REEL_ORIGIN.save(true);
        assertTrue(Settings.FIND_REEL_ORIGIN.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.FIND_REEL_ORIGIN.get());
        assertFalse(Settings.FIND_REEL_ORIGIN_ON_TAP.get());
        assertTrue(Settings.FIND_REEL_ORIGIN.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.FIND_REEL_ORIGIN.get());
    }
}
