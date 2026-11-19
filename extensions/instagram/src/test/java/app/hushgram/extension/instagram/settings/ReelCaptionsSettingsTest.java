/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
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

/**
 * The reel captions switch (#752) comes with Expand captions, sits on the Reels page right before the
 * reel name line switch (so the #769 pair after it stays together), starts off apart from the feed
 * switch and is kept in a backup.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelCaptionsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.EXPAND_REEL_CAPTIONS.resetToDefault();
        Settings.EXPAND_CAPTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.EXPAND_REEL_CAPTIONS.resetToDefault();
        Settings.EXPAND_CAPTIONS.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    private static int indexOf(PreferenceGroup group, String key) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (key.equals(group.getPreference(i).getKey())) return i;
        }
        return -1;
    }
    @Test public void itIsOnTheReelsPageBeforeTheNameLineAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.LOW_VIEW_REELS, PatchFamily.FEED_HEADER_LINE, PatchFamily.EXPAND_CAPTIONS,
                PatchFamily.FEED_SUGGESTIONS, PatchFamily.REEL_DECLUTTER));
        Preference row = page.getPreferenceScreen().findPreference(Settings.EXPAND_REEL_CAPTIONS.key);
        assertNotNull(row);
        assertEquals("Expand captions on reels", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("whole caption"));
        PreferenceGroup reels = (PreferenceGroup) row.getParent();
        assertEquals("Reels", reels.getTitle().toString());
        int nameLine = indexOf(reels, Settings.HIDE_REEL_HEADER_LINE.key);
        assertTrue("Hide the line under names on reels is on Reels", nameLine >= 0);
        assertEquals("right before the name line switch", nameLine - 1, indexOf(reels, Settings.EXPAND_REEL_CAPTIONS.key));
        int interest = indexOf(reels, Settings.HIDE_REEL_INTEREST.key);
        assertEquals("the #769 pair stays together", nameLine + 1, interest);
        assertEquals(interest + 1, indexOf(reels, Settings.HIDE_REEL_FOLLOW_BUTTON.key));
        assertFalse(Settings.EXPAND_REEL_CAPTIONS.get());
        assertTrue("needs a restart", Settings.EXPAND_REEL_CAPTIONS.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.EXPAND_REEL_CAPTIONS.key));
        assertTrue(PatchFamily.EXPAND_CAPTIONS.switches.contains(Settings.EXPAND_REEL_CAPTIONS));
    }
    @Test public void itWorksWithoutTheFeedSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.EXPAND_CAPTIONS));
        Preference row = page.getPreferenceScreen().findPreference(Settings.EXPAND_REEL_CAPTIONS.key);
        assertNotNull(row);
        assertFalse("the feed switch is off", Settings.EXPAND_CAPTIONS.get());
        assertTrue("usable on its own", row.isEnabled());
        assertNotNull("the feed switch keeps its own row",
                page.getPreferenceScreen().findPreference(Settings.EXPAND_CAPTIONS.key));
    }
    @Test public void withoutTheExpandCaptionsPatchThereIsNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_HEADER_LINE));
        assertNull(page.getPreferenceScreen().findPreference(Settings.EXPAND_REEL_CAPTIONS.key));
    }
}
