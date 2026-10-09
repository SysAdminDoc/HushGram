/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
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
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Ghost mode: one switch over the ghost switches, first under Ads and privacy, keeping no value of its own. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class GhostModeSettingsTest {
    private static final BooleanSetting[] GHOST = {Settings.VIEW_STORIES_ANONYMOUSLY,
            Settings.VIEW_LIVE_ANONYMOUSLY, Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.VIEW_DM_MEDIA_ANONYMOUSLY, Settings.HIDE_TYPING,
            Settings.HIDE_SCREENSHOTS};
    private static final EnumSet<PatchFamily> ALL_GHOST = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.STORY_SEEN,
            PatchFamily.LIVE_SEEN, PatchFamily.THREAD_SEEN, PatchFamily.DM_MEDIA_SEEN, PatchFamily.TYPING, PatchFamily.SCREENSHOT_REPORTS);

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        for (BooleanSetting setting : GHOST) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        for (BooleanSetting setting : GHOST) setting.resetToDefault();
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
    private SwitchPreference ghost() {
        PreferenceGroup screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference section = screen.getPreference(i);
            if (!(section instanceof PreferenceGroup)) continue;
            PreferenceGroup group = (PreferenceGroup) section;
            for (int j = 0; j < group.getPreferenceCount(); j++) {
                Preference row = group.getPreference(j);
                if ("Ghost mode".equals(String.valueOf(row.getTitle()))) return (SwitchPreference) row;
            }
        }
        return null;
    }
    private SwitchPreference own(BooleanSetting setting) {
        return (SwitchPreference) page.findPreference(setting.key);
    }

    @Test public void theSwitchListFollowsTheBuild() {
        assertEquals(Arrays.asList(GHOST), GhostMode.switches(ALL_GHOST));
        assertEquals(Arrays.asList(Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.HIDE_TYPING),
                GhostMode.switches(EnumSet.of(PatchFamily.TYPING, PatchFamily.NOTES_ROW, PatchFamily.THREAD_SEEN)));
        assertTrue(GhostMode.switches(null).isEmpty());
        assertFalse(GhostMode.offered(GhostMode.switches(EnumSet.of(PatchFamily.TYPING))));
        assertFalse(GhostMode.on(GhostMode.switches(EnumSet.noneOf(PatchFamily.class))));
    }

    @Test public void oneGhostPatchGetsNoMasterSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.TYPING));
        assertNull(ghost());
        assertNotNull(own(Settings.HIDE_TYPING));
    }

    @Test public void itSitsFirstUnderAdsAndPrivacyAndStartsAsTheSwitchesAre() throws Exception {
        open(ALL_GHOST);
        SwitchPreference row = ghost();
        assertNotNull(row);
        assertFalse("every ghost switch starts off", row.isChecked());
        assertFalse(row.isPersistent());
        assertNull("it keeps no value, so it has no setting key", row.getKey());
        assertEquals("Turns the switches that keep what you do to yourself on or off in one go, like View stories "
                + "anonymously and Hide that you're typing. Each one keeps its own switch.", row.getSummary().toString());
        PreferenceGroup privacy = row.getParent();
        assertEquals("Ads and privacy", privacy.getTitle().toString());
        assertSame(row, privacy.getPreference(0));
        assertEquals(Settings.HIDE_ADS.key, privacy.getPreference(1).getKey());
    }

    @Test public void aTapTurnsEverySwitchAndEachRowShowsIt() throws Exception {
        open(ALL_GHOST);
        SwitchPreference row = ghost();
        row.getOnPreferenceChangeListener().onPreferenceChange(row, true);
        for (BooleanSetting setting : GHOST) {
            assertTrue(setting.key, setting.savedValue());
            assertTrue(setting.key, own(setting).isChecked());
        }
        assertTrue(row.isChecked());
        assertEquals("Ghost mode is on, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
        assertEquals("Hide ads isn't a ghost switch", Settings.HIDE_ADS.defaultValue, Settings.HIDE_ADS.savedValue());

        row.getOnPreferenceChangeListener().onPreferenceChange(row, false);
        for (BooleanSetting setting : GHOST) {
            assertFalse(setting.key, setting.savedValue());
            assertFalse(setting.key, own(setting).isChecked());
        }
        assertFalse(row.isChecked());
        assertEquals("Ghost mode is off, and so is each of its switches.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void itFollowsTheOwnSwitchesAndPauseStillWins() throws Exception {
        open(ALL_GHOST);
        SwitchPreference row = ghost();
        for (BooleanSetting setting : GHOST) own(setting).setChecked(true);
        Utils.awaitBackgroundTasksForTests();
        assertTrue("every switch on by hand turns Ghost mode on", row.isChecked());
        own(Settings.HIDE_TYPING).setChecked(false);
        Utils.awaitBackgroundTasksForTests();
        assertFalse("one switch off turns it off", row.isChecked());

        row.getOnPreferenceChangeListener().onPreferenceChange(row, true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        for (BooleanSetting setting : GHOST) {
            assertFalse(setting.key, setting.get());
            assertTrue(setting.key, setting.savedValue());
        }
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        for (BooleanSetting setting : GHOST) assertTrue(setting.key, setting.get());
    }

    @Test public void searchFindsItByName() throws Exception {
        open(ALL_GHOST);
        page.searchSettings("ghost");
        assertNotNull(ghost());
        assertNull(own(Settings.HIDE_ADS).getParent());
        page.searchSettings("");
        assertNotNull(own(Settings.HIDE_ADS).getParent());
    }
}
