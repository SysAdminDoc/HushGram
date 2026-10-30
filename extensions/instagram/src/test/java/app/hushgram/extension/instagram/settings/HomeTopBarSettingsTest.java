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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Keep Home's top bar switch: under Feed, off to start, asks for a restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class HomeTopBarSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_HOME_TOP_BAR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_HOME_TOP_BAR.resetToDefault();
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
        open(EnumSet.of(PatchFamily.HIDE_CAPTIONS));
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_HOME_TOP_BAR.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_CAPTIONS.key));
    }
    /** Without the other Feed patches, Feed still opens for this switch alone. */
    @Test public void aloneItStillGetsFeed() throws Exception {
        open(EnumSet.of(PatchFamily.HOME_TOP_BAR));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_HOME_TOP_BAR.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffUnderFeedAfterHideCaptions() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_FOLLOW, PatchFamily.HIDE_CAPTIONS, PatchFamily.HOME_TOP_BAR));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_HOME_TOP_BAR.key);
        assertNotNull(row);
        assertEquals("Keep Home's top bar", row.getTitle().toString());
        assertEquals("Keeps the Instagram logo and the buttons beside it at the top of Home while you scroll, "
                + "instead of sliding them away.", row.getSummary().toString());
        PreferenceGroup feed = row.getParent();
        assertEquals("Feed", feed.getTitle().toString());
        assertEquals("right after Hide captions", Settings.HIDE_CAPTIONS.key,
                feed.getPreference(indexOf(feed, row) - 1).getKey());
        assertFalse(row.isChecked());
        assertFalse(Settings.KEEP_HOME_TOP_BAR.get());
        assertTrue("Home hands its header over as it opens, so a flip needs a restart", Settings.KEEP_HOME_TOP_BAR.rebootApp);
        assertEquals(Collections.singletonList(Settings.KEEP_HOME_TOP_BAR), PatchFamily.HOME_TOP_BAR.switches);
        assertEquals("Keep Home's top bar", PatchFamily.HOME_TOP_BAR.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_HOME_TOP_BAR.key));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
