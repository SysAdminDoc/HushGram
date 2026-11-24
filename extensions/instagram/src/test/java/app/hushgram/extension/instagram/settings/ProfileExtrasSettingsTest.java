/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.SwitchPreference;
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

/** #234: the Profile extras button's switch sits with Save profile picture, off to start, and goes with the patch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ProfileExtrasSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.PROFILE_EXTRAS_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.PROFILE_EXTRAS_BUTTON.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }

    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.PROFILE_PICTURE) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.PROFILE_EXTRAS_BUTTON.key));
    }

    @Test public void theSwitchSitsWithSaveProfilePictureAndStartsOff() throws Exception {
        open(true);
        SwitchPreference extras = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PROFILE_EXTRAS_BUTTON.key);
        SwitchPreference save = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SAVE_PROFILE_PICTURES.key);
        assertNotNull(extras);
        assertEquals("Profile extras button", extras.getTitle().toString());
        assertEquals("Puts a button next to the three dots on profiles that opens HushGram's profile actions. "
                        + "Needs Save profile picture, View profile picture or Copy username and bio on. "
                        + "The rows in the three-dot menu stay too.",
                extras.getSummary().toString());
        assertSame(save.getParent(), extras.getParent());
        assertFalse(extras.isChecked());
        assertFalse(Settings.PROFILE_EXTRAS_BUTTON.get());
        assertTrue(PatchFamily.PROFILE_PICTURE.switches.contains(Settings.PROFILE_EXTRAS_BUTTON));
    }

    @Test public void theSwitchIsInTheSettingsBackup() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.PROFILE_PICTURE);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PROFILE_EXTRAS_BUTTON.key));
    }

    /** #737: Long press a profile picture to view it sits under Profiles with About this account, off to start. */
    @Test public void theLongPressSwitchSitsUnderProfilesAndStartsOff() throws Exception {
        open(true);
        SwitchPreference hold = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PROFILE_PICTURE_LONG_PRESS.key);
        SwitchPreference about = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.ABOUT_ACCOUNT_BUTTON.key);
        assertNotNull(hold);
        assertEquals("Long press a profile picture to view it", hold.getTitle().toString());
        assertEquals("Long press the picture at the top of a profile to open the whole square picture at full size, "
                        + "with Save under it, instead of Instagram's own preview. A tap works as before.",
                hold.getSummary().toString());
        assertSame(about.getParent(), hold.getParent());
        assertEquals("Profiles", hold.getParent().getTitle().toString());
        assertFalse(hold.isChecked());
        assertFalse(Settings.PROFILE_PICTURE_LONG_PRESS.get());
        assertTrue(PatchFamily.PROFILE_PICTURE.switches.contains(Settings.PROFILE_PICTURE_LONG_PRESS));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.PROFILE_PICTURE_LONG_PRESS.key));
    }

    @Test public void missingPatchHasNoLongPressSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.PROFILE_PICTURE_LONG_PRESS.key));
    }

    /** #823: A story ring opens the story sits under Profiles right after the long press, off to start. */
    @Test public void theRingTapSwitchSitsUnderProfilesAndStartsOff() throws Exception {
        open(true);
        SwitchPreference ring = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.RING_TAP_OPENS_STORY.key);
        SwitchPreference hold = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PROFILE_PICTURE_LONG_PRESS.key);
        assertNotNull(ring);
        assertEquals("A story ring opens the story", ring.getTitle().toString());
        assertEquals("Tapping the picture at the top of a profile with a story ring opens the story whenever "
                        + "Instagram has it, where Instagram sometimes opens the picture instead.",
                ring.getSummary().toString());
        assertSame(hold.getParent(), ring.getParent());
        assertEquals("Profiles", ring.getParent().getTitle().toString());
        assertEquals(hold.getOrder() + 1, ring.getOrder());
        assertFalse(ring.isChecked());
        assertFalse(Settings.RING_TAP_OPENS_STORY.get());
        assertTrue(PatchFamily.PROFILE_PICTURE.switches.contains(Settings.RING_TAP_OPENS_STORY));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.RING_TAP_OPENS_STORY.key));
    }

    @Test public void missingPatchHasNoRingTapSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.RING_TAP_OPENS_STORY.key));
    }
}
