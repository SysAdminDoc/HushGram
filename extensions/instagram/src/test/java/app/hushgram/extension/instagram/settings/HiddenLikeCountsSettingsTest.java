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

/** The hidden like counts switch: last under Feed, off to start, off while paused, and no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HiddenLikeCountsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.SHOW_HIDDEN_LIKE_COUNTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SHOW_HIDDEN_LIKE_COUNTS.resetToDefault();
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
    @Test public void missingPatchHasNoHiddenLikesSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.POST_TIME));
        assertNull(page.getPreferenceScreen().findPreference(Settings.SHOW_HIDDEN_LIKE_COUNTS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.SHOW_POST_TIME.key));
    }
    /** Without the other feed patches, Feed still opens for this switch alone. */
    @Test public void hiddenLikesAloneStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.HIDDEN_LIKE_COUNTS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_HIDDEN_LIKE_COUNTS.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.SHOW_POST_TIME.key));
    }
    @Test public void hiddenLikesSwitchStartsOffLastUnderFeedAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.ASK_BEFORE_LIKE, PatchFamily.POST_TIME, PatchFamily.HIDDEN_LIKE_COUNTS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SHOW_HIDDEN_LIKE_COUNTS.key);
        assertNotNull(row);
        assertEquals("Show like counts the poster hid", row.getTitle().toString());
        assertEquals("Shows how many likes a post or reel has when its owner hid the count, but only when Instagram "
                + "still sends the number. If it doesn't, nothing changes. Posts you load after a change show it.",
                row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int postTime = Arrays.asList(keys).indexOf(Settings.SHOW_POST_TIME.key);
        assertTrue(Arrays.toString(keys), postTime >= 0);
        assertEquals("right after Show a post's exact time", Settings.SHOW_HIDDEN_LIKE_COUNTS.key, keys[postTime + 1]);
        assertFalse(row.isChecked());
        assertFalse(Settings.SHOW_HIDDEN_LIKE_COUNTS.get());
        assertFalse("each like row reads the switch, so no restart prompt", Settings.SHOW_HIDDEN_LIKE_COUNTS.rebootApp);
        assertEquals(Collections.singletonList(Settings.SHOW_HIDDEN_LIKE_COUNTS), PatchFamily.HIDDEN_LIKE_COUNTS.switches);
        assertEquals("Show hidden like counts", PatchFamily.HIDDEN_LIKE_COUNTS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SHOW_HIDDEN_LIKE_COUNTS.key));

        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);
        assertTrue(Settings.SHOW_HIDDEN_LIKE_COUNTS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.SHOW_HIDDEN_LIKE_COUNTS.get());
        assertTrue(Settings.SHOW_HIDDEN_LIKE_COUNTS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.SHOW_HIDDEN_LIKE_COUNTS.get());
    }
}
