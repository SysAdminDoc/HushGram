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
import app.hushgram.extension.instagram.feed.ActivityBubbles;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Hide activity bubbles switch (#475): under Feed, off to start, and read by the hook as it changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ActivityBubblesSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_ACTIVITY_BUBBLES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_ACTIVITY_BUBBLES.resetToDefault();
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
        open(EnumSet.of(PatchFamily.FEED_FOLLOW));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ACTIVITY_BUBBLES.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_FEED_FOLLOW.key));
    }
    /** Without the other Feed patches, Feed still opens for this switch alone, and it starts off. */
    @Test public void aloneItStillGetsFeedAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.ACTIVITY_BUBBLES));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_ACTIVITY_BUBBLES.key);
        assertNotNull(row);
        assertEquals("Feed", row.getParent().getTitle().toString());
        assertEquals("Hide activity bubbles", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("a friend liked, commented on or reshared"));
        assertFalse(row.isChecked());
        assertFalse(ActivityBubbles.hide(0));
    }
    /** The hook reads the switch on every read, so a flip reaches the next post Instagram builds. */
    @Test public void flippingItReachesTheNextRead() throws Exception {
        open(EnumSet.of(PatchFamily.ACTIVITY_BUBBLES));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_ACTIVITY_BUBBLES.key);
        row.setChecked(true);
        assertTrue(Settings.HIDE_ACTIVITY_BUBBLES.get());
        assertTrue(ActivityBubbles.hide(0));
        row.setChecked(false);
        assertFalse(ActivityBubbles.hide(0));
    }
}
