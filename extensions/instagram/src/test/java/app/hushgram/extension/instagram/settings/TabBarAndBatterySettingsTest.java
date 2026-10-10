/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.StoryTimeSettingsTest.sectionOf;
import static org.junit.Assert.*;

import android.app.Activity;
import android.preference.Preference;
import android.preference.SwitchPreference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

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
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.misc.GlassHeight;
import app.hushgram.extension.instagram.misc.GlassOpacity;
import app.hushgram.extension.instagram.misc.HapticStyle;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Glass tab bar's section, Saved on your profile's switch and Stop background wake-ups' Battery section: each
 * shows only with its patch in the build, and each main switch starts off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class TabBarAndBatterySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        reset();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        reset();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private static void reset() {
        Settings.GLASS_TAB_BAR.resetToDefault();
        Settings.GLASS_TAB_BAR_HAPTICS.resetToDefault();
        Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.resetToDefault();
        Settings.GLASS_TAB_BAR_HAPTIC_STYLE.resetToDefault();
        Settings.GLASS_TAB_BAR_OPACITY.resetToDefault();
        Settings.GLASS_TAB_BAR_HEIGHT.resetToDefault();
        Settings.SAVED_ON_PROFILE.resetToDefault();
        Settings.STOP_HEARTBEAT.resetToDefault();
        Settings.STOP_UPLOAD_ALARM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private void open(PatchFamily... families) throws Exception {
        PatchFamily.inBuildForTests = families.length == 0 ? EnumSet.noneOf(PatchFamily.class) : EnumSet.copyOf(Arrays.asList(families));
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    private Preference row(String key) {
        return page.getPreferenceScreen().findPreference(key);
    }

    @Test public void withoutThePatchesNoneOfTheirRowsShow() throws Exception {
        open();
        for (String key : List.of(Settings.GLASS_TAB_BAR.key, Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key, Settings.GLASS_TAB_BAR_OPACITY.key, Settings.GLASS_TAB_BAR_HEIGHT.key,
                Settings.SAVED_ON_PROFILE.key, Settings.STOP_HEARTBEAT.key, Settings.STOP_UPLOAD_ALARM.key)) {
            assertNull(key, row(key));
        }
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key));
    }

    @Test public void theTabBarSectionHoldsItsSwitchesAndTheTickChoice() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        SwitchPreference glass = (SwitchPreference) row(Settings.GLASS_TAB_BAR.key);
        assertNotNull(glass);
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), glass).getTitle()));
        assertFalse(glass.isChecked());
        assertTrue(Settings.GLASS_TAB_BAR.rebootApp);
        assertEquals(Arrays.asList(Settings.GLASS_TAB_BAR, Settings.GLASS_TAB_BAR_BLUR, Settings.GLASS_TAB_BAR_FLOAT,
                Settings.GLASS_TAB_BAR_HAPTICS, Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL), PatchFamily.GLASS_TAB_BAR.switches);
        for (String key : List.of(Settings.GLASS_TAB_BAR_BLUR.key, Settings.GLASS_TAB_BAR_HAPTICS.key, Settings.GLASS_TAB_BAR_FLOAT.key, Settings.GLASS_TAB_BAR_HIDE_ON_SCROLL.key)) {
            assertNotNull(key, row(key));
        }

        Preference found = row(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key);
        assertTrue(found instanceof HushgramPreferenceFragment.HapticStyleRow);
        HushgramPreferenceFragment.HapticStyleRow tick = (HushgramPreferenceFragment.HapticStyleRow) found;
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : tick.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Short tick", "System tick", "Soft tick", "Full tick"), entries);
        assertEquals("SYSTEM", tick.getValue());
        assertEquals("System tick", String.valueOf(tick.getSummary()));
        tick.setValue("SOFT");
        ShadowLooper.idleMainLooper();
        assertEquals(HapticStyle.SOFT, Settings.GLASS_TAB_BAR_HAPTIC_STYLE.savedValue());
        assertEquals("Soft tick", String.valueOf(tick.getSummary()));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key));
    }

    @Test public void opacityAndHeightAreListsThatStartOnTodaysLookAndAreBackedUp() throws Exception {
        open(PatchFamily.GLASS_TAB_BAR);
        Preference opacityFound = row(Settings.GLASS_TAB_BAR_OPACITY.key);
        assertTrue(opacityFound instanceof HushgramPreferenceFragment.GlassOpacityRow);
        HushgramPreferenceFragment.GlassOpacityRow opacity = (HushgramPreferenceFragment.GlassOpacityRow) opacityFound;
        List<String> entries = new ArrayList<>();
        for (CharSequence entry : opacity.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Clearest", "Clear", "Standard", "Frosted"), entries);
        assertEquals("STANDARD", opacity.getValue());
        assertEquals("Standard", String.valueOf(opacity.getSummary()));
        assertEquals("Tab bar", String.valueOf(sectionOf(page.getPreferenceScreen(), opacity).getTitle()));
        opacity.setValue("CLEAR");
        ShadowLooper.idleMainLooper();
        assertEquals(GlassOpacity.CLEAR, Settings.GLASS_TAB_BAR_OPACITY.savedValue());
        assertEquals("Clearest", String.valueOf(opacity.getSummary()));

        Preference heightFound = row(Settings.GLASS_TAB_BAR_HEIGHT.key);
        assertTrue(heightFound instanceof HushgramPreferenceFragment.GlassHeightRow);
        HushgramPreferenceFragment.GlassHeightRow height = (HushgramPreferenceFragment.GlassHeightRow) heightFound;
        entries = new ArrayList<>();
        for (CharSequence entry : height.getEntries()) entries.add(String.valueOf(entry));
        assertEquals(Arrays.asList("Compact", "Standard", "Tall"), entries);
        assertEquals("STANDARD", height.getValue());
        height.setValue("TALL");
        ShadowLooper.idleMainLooper();
        assertEquals(GlassHeight.TALL, Settings.GLASS_TAB_BAR_HEIGHT.savedValue());
        assertEquals("Tall", String.valueOf(height.getSummary()));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_OPACITY.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.GLASS_TAB_BAR_HEIGHT.key));
    }

    @Test public void savedOnYourProfileStartsOffUnderProfiles() throws Exception {
        open(PatchFamily.SAVED_ON_PROFILE);
        SwitchPreference saved = (SwitchPreference) row(Settings.SAVED_ON_PROFILE.key);
        assertNotNull(saved);
        assertEquals("Saved tab on your profile", String.valueOf(saved.getTitle()));
        assertEquals("Profiles", String.valueOf(sectionOf(page.getPreferenceScreen(), saved).getTitle()));
        assertFalse(saved.isChecked());
        assertTrue(Settings.SAVED_ON_PROFILE.rebootApp);
    }

    @Test public void batteryHoldsBothAlarmSwitchesAndBothStartOff() throws Exception {
        open(PatchFamily.STOP_HEARTBEAT);
        SwitchPreference heartbeat = (SwitchPreference) row(Settings.STOP_HEARTBEAT.key);
        SwitchPreference upload = (SwitchPreference) row(Settings.STOP_UPLOAD_ALARM.key);
        assertNotNull(heartbeat);
        assertNotNull(upload);
        assertEquals("Battery", String.valueOf(sectionOf(page.getPreferenceScreen(), heartbeat).getTitle()));
        assertSame(sectionOf(page.getPreferenceScreen(), heartbeat), sectionOf(page.getPreferenceScreen(), upload));
        assertFalse(heartbeat.isChecked());
        assertFalse(upload.isChecked());
        assertEquals(Arrays.asList(Settings.STOP_HEARTBEAT, Settings.STOP_UPLOAD_ALARM), PatchFamily.STOP_HEARTBEAT.switches);
    }
}
