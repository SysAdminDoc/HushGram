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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Hide profile banners: one Profiles row per kind of banner, side by side, each starting off and kept in a backup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 37)
@SuppressWarnings("deprecation")
public class ProfileBannersSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    private static List<BooleanSetting> switches() {
        return Arrays.asList(Settings.HIDE_PROFILE_THREADS_BADGE, Settings.HIDE_PROFILE_FACEBOOK_LINKS,
                Settings.HIDE_PROFILE_WHATSAPP_LINK, Settings.HIDE_PROFILE_SONG, Settings.HIDE_PROFILE_INTERESTS,
                Settings.HIDE_PROFILE_CHANNEL_BANNER, Settings.HIDE_OTHER_PROFILE_BANNERS);
    }

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        for (BooleanSetting setting : switches()) setting.resetToDefault();
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
    @Test public void theSwitchesSitTogetherUnderProfilesAndStartOff() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_BANNERS));
        String[] titles = {"Hide the Threads badge", "Hide Facebook links", "Hide the WhatsApp link", "Hide the profile song",
                "Hide interests", "Hide channel invites", "Hide the other profile banners"};
        PreferenceGroup group = null;
        int previous = -1;
        for (int i = 0; i < titles.length; i++) {
            BooleanSetting setting = switches().get(i);
            Preference row = page.getPreferenceScreen().findPreference(setting.key);
            assertNotNull(setting.key, row);
            assertEquals(titles[i], row.getTitle().toString());
            PreferenceGroup parent = (PreferenceGroup) row.getParent();
            assertEquals("Profiles", parent.getTitle().toString());
            if (group != null) assertSame("one group", group, parent);
            group = parent;
            int at = indexIn(parent, row);
            if (previous >= 0) assertEquals(setting.key + " comes right after the one before", previous + 1, at);
            previous = at;
            assertFalse(setting.key, setting.get());
            assertTrue(setting.key, PatchFamily.PROFILE_BANNERS.switches.contains(setting));
            assertTrue(setting.key, ConfigurationBackup.eligible().containsKey(setting.key));
        }
    }
    @Test public void missingPatchHasNoSwitches() throws Exception {
        open(EnumSet.of(PatchFamily.PROFILE_NOTES));
        for (BooleanSetting setting : switches()) assertNull(setting.key, page.getPreferenceScreen().findPreference(setting.key));
    }
    private static int indexIn(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
