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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.feed.ProfileFollowCounts;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The two #771 profile switches: under Profiles after Hide highlights, off to start, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ProfileFollowCountsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.resetToDefault();
        Settings.HIDE_PROFILE_FOLLOW_BUTTONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        ProfileFollowCounts.resetForTests();
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.resetToDefault();
        Settings.HIDE_PROFILE_FOLLOW_BUTTONS.resetToDefault();
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
    private SwitchPreference row(String key) {
        return (SwitchPreference) page.getPreferenceScreen().findPreference(key);
    }
    @Test public void missingPatchHasNoSwitches() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_HIGHLIGHTS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_PROFILE_FOLLOW_COUNTS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_PROFILE_FOLLOW_BUTTONS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_HIGHLIGHTS.key));
    }
    /** Without the other profile patches, Profiles still opens for these switches alone. */
    @Test public void aloneTheyStillGetProfiles() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_FOLLOW_COUNTS));
        SwitchPreference counts = row(Settings.HIDE_PROFILE_FOLLOW_COUNTS.key);
        assertNotNull(counts);
        assertEquals("Profiles", counts.getParent().getTitle().toString());
    }
    @Test public void bothStartOffRightAfterHighlights() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_HIGHLIGHTS, PatchFamily.PROFILE_FOLLOW_COUNTS));
        SwitchPreference counts = row(Settings.HIDE_PROFILE_FOLLOW_COUNTS.key);
        SwitchPreference buttons = row(Settings.HIDE_PROFILE_FOLLOW_BUTTONS.key);
        assertEquals("Hide follower counts", counts.getTitle().toString());
        assertEquals("Takes the numbers off Followers and Following at the top of profiles, yours included. "
                + "The words stay, and a tap still opens the list.", counts.getSummary().toString());
        assertEquals("Hide Followers and Following", buttons.getTitle().toString());
        assertEquals("Takes the Followers and Following buttons off the top of profiles, yours included, "
                + "with no gap left. The post count stays.", buttons.getSummary().toString());
        PreferenceGroup profiles = counts.getParent();
        assertEquals("Profiles", profiles.getTitle().toString());
        assertEquals("right after Hide highlights", Settings.HIDE_HIGHLIGHTS.key,
                profiles.getPreference(indexOf(profiles, counts) - 1).getKey());
        assertEquals("the buttons switch right under it", indexOf(profiles, counts) + 1, indexOf(profiles, buttons));
        assertFalse(counts.isChecked());
        assertFalse(buttons.isChecked());
        assertFalse("each frame reads the switch, so no restart prompt", Settings.HIDE_PROFILE_FOLLOW_COUNTS.rebootApp);
        assertFalse(Settings.HIDE_PROFILE_FOLLOW_BUTTONS.rebootApp);
        assertEquals(Arrays.asList(Settings.HIDE_PROFILE_FOLLOW_COUNTS, Settings.HIDE_PROFILE_FOLLOW_BUTTONS),
                PatchFamily.PROFILE_FOLLOW_COUNTS.switches);
        assertEquals("Hide Followers and Following on profiles", PatchFamily.PROFILE_FOLLOW_COUNTS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_PROFILE_FOLLOW_COUNTS.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_PROFILE_FOLLOW_BUTTONS.key));
    }
    /** Settings are a dialog over the profile, which doesn't resume when they close, so flipping on reaches it at once. */
    @Test public void flippingEitherOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_FOLLOW_COUNTS));
        SwitchPreference counts = row(Settings.HIDE_PROFILE_FOLLOW_COUNTS.key);
        SwitchPreference buttons = row(Settings.HIDE_PROFILE_FOLLOW_BUTTONS.key);
        assertFalse(ProfileFollowCounts.listeningForTests(controller.get()));
        assertTrue(counts.getOnPreferenceChangeListener().onPreferenceChange(counts, true));
        // The switch saves after its listener, so the helper reads the value once the change is posted.
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.save(true);
        ShadowLooper.idleMainLooper();
        assertTrue(ProfileFollowCounts.listeningForTests(controller.get()));

        ProfileFollowCounts.resetForTests();
        Settings.HIDE_PROFILE_FOLLOW_COUNTS.save(false);
        assertFalse(ProfileFollowCounts.listeningForTests(controller.get()));
        assertTrue(buttons.getOnPreferenceChangeListener().onPreferenceChange(buttons, true));
        Settings.HIDE_PROFILE_FOLLOW_BUTTONS.save(true);
        ShadowLooper.idleMainLooper();
        assertTrue(ProfileFollowCounts.listeningForTests(controller.get()));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
