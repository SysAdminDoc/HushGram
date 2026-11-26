/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
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

/** Keep HDR on video uploads' switch (#532): under Playback, off to start, said to be experimental, and the patch's one switch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HdrVideosSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_HDR_ON_VIDEO_UPLOADS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_HDR_ON_VIDEO_UPLOADS.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        open(installed ? EnumSet.of(PatchFamily.HDR_VIDEOS) : EnumSet.noneOf(PatchFamily.class));
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRow() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_HDR_ON_VIDEO_UPLOADS.key));
    }
    @Test public void theSwitchSitsUnderPlaybackStartsOffAndSaysItsExperimental() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_HDR_ON_VIDEO_UPLOADS.key);
        assertNotNull(row);
        assertEquals("Keep HDR on video uploads", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().startsWith("Experimental."));
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Playback", section.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.KEEP_HDR_ON_VIDEO_UPLOADS.get());
        assertEquals(Collections.singletonList(Settings.KEEP_HDR_ON_VIDEO_UPLOADS), PatchFamily.HDR_VIDEOS.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_HDR_ON_VIDEO_UPLOADS.key));
        row.setChecked(true);
        assertTrue(Settings.KEEP_HDR_ON_VIDEO_UPLOADS.get());
    }
    @Test public void theVideoSwitchComesRightAfterThePhotoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.HDR_PHOTOS, PatchFamily.HDR_VIDEOS));
        Preference photos = page.getPreferenceScreen().findPreference(Settings.KEEP_HDR_ON_PHOTO_UPLOADS.key);
        Preference videos = page.getPreferenceScreen().findPreference(Settings.KEEP_HDR_ON_VIDEO_UPLOADS.key);
        assertNotNull(photos);
        assertNotNull(videos);
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), videos);
        assertSame(section, categoryHolding(page.getPreferenceScreen(), photos));
        int at = -1;
        for (int i = 0; i < section.getPreferenceCount(); i++) if (section.getPreference(i) == photos) at = i;
        assertSame(videos, section.getPreference(at + 1));
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
