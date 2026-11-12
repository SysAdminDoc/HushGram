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
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.instagram.feed.HideCaptions;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Hide captions switch: under Feed, off to start, no restart needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HideCaptionsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_CAPTIONS.resetToDefault();
        Settings.HIDE_FEED_FOLLOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_CAPTIONS.resetToDefault();
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
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTIONS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.FULL_SCREEN_HOME.key));
    }
    /** Without the other Feed patches, Feed still opens for this switch alone. */
    @Test public void aloneItStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.HIDE_CAPTIONS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTIONS.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
    }
    /** Settings are a dialog over Instagram's screen, which doesn't resume when they close, so flipping on reaches it at once. */
    @Test public void flippingOnReachesTheScreenUnderSettings() throws Exception {
        open(EnumSet.of(PatchFamily.HIDE_CAPTIONS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTIONS.key);
        try {
            assertFalse(HideCaptions.listeningForTests(controller.get()));
            assertTrue(row.getOnPreferenceChangeListener().onPreferenceChange(row, true));
            // The switch saves after its listener, so the helper reads the value once the change is posted.
            Settings.HIDE_CAPTIONS.save(true);
            ShadowLooper.idleMainLooper();
            assertTrue(HideCaptions.listeningForTests(controller.get()));
        } finally {
            HideCaptions.resetForTests();
        }
    }
    @Test public void switchStartsOffUnderFeedAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.FULL_SCREEN_BARS, PatchFamily.FEED_FOLLOW, PatchFamily.HIDE_CAPTIONS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTIONS.key);
        assertNotNull(row);
        assertEquals("Hide captions", row.getTitle().toString());
        assertEquals("Takes the caption off posts in your feed and off reels. The post stays, and the comments sheet still shows it.",
                row.getSummary().toString());
        PreferenceGroup comments = row.getParent();
        assertEquals("Feed", comments.getTitle().toString());
        assertEquals("right after Hide Follow on posts", Settings.HIDE_FEED_FOLLOW.key,
                comments.getPreference(indexOf(comments, row) - 1).getKey());
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_CAPTIONS.get());
        assertFalse("each frame reads the switch, so no restart prompt", Settings.HIDE_CAPTIONS.rebootApp);
        assertEquals(Collections.singletonList(Settings.HIDE_CAPTIONS), PatchFamily.HIDE_CAPTIONS.switches);
        assertEquals("Hide captions", PatchFamily.HIDE_CAPTIONS.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_CAPTIONS.key));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
