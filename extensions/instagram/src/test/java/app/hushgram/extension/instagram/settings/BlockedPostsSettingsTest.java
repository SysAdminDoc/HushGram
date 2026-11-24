/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Hide posts when you block someone switch (#861): last under Feed, off to start, off while paused, kept in a backup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class BlockedPostsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_BLOCKED_POSTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_BLOCKED_POSTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> families) throws Exception {
        PatchFamily.inBuildForTests = families;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void aBuildWithoutThePatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_AUTO_SCROLL));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_BLOCKED_POSTS.key));
    }
    @Test public void theSwitchStartsOffLastUnderFeedPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_AUTO_SCROLL, PatchFamily.BLOCKED_POSTS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_BLOCKED_POSTS.key);
        assertNotNull(row);
        assertEquals("Hide posts when you block someone", row.getTitle().toString());
        assertEquals("When you block someone, their posts in Home go away right away instead of after you refresh it.",
                row.getSummary().toString());
        PreferenceGroup section = StoryTimeSettingsTest.sectionOf(page.getPreferenceScreen(), row);
        assertEquals("Feed", String.valueOf(section.getTitle()));
        assertEquals(section.getPreferenceCount() - 1, indexIn(section, row));
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_BLOCKED_POSTS.get());
        assertEquals(Collections.singletonList(Settings.HIDE_BLOCKED_POSTS), PatchFamily.BLOCKED_POSTS.switches);
        assertEquals("Hide posts when you block someone", PatchFamily.BLOCKED_POSTS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_BLOCKED_POSTS.key));

        Settings.HIDE_BLOCKED_POSTS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_BLOCKED_POSTS.get());
        assertTrue(Settings.HIDE_BLOCKED_POSTS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_BLOCKED_POSTS.get());
    }
    @Test public void aBuildWithOnlyThisPatchStillShowsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.BLOCKED_POSTS));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_BLOCKED_POSTS.key));
    }
    private static int indexIn(PreferenceGroup section, android.preference.Preference row) {
        for (int i = 0; i < section.getPreferenceCount(); i++) {
            if (section.getPreference(i) == row) return i;
        }
        return -1;
    }
}
