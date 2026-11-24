/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Keep 2x after you let go (#552): a Feed row right under Hold for 2x on feed videos, off to start, greyed out while that's off, kept in a backup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class FeedHoldKeepSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HOLD_FEED_VIDEO_FAST.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HOLD_FEED_VIDEO_FAST.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP_LONG.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP_BADGE.resetToDefault();
        Settings.HOLD_FEED_VIDEO_KEEP_NAME.resetToDefault();
        Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.resetToDefault();
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
    @Test public void theKeepSwitchSitsRightUnderTheHoldSwitchAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_KEEP.key);
        assertNotNull(row);
        assertEquals("Keep 2x after you let go", row.getTitle().toString());
        assertEquals("Feed", ((PreferenceGroup) row.getParent()).getTitle().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse("greyed out with Hold for 2x on feed videos off", row.isEnabled());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.HOLD_FEED_VIDEO_KEEP.key);
        assertEquals(Arrays.toString(keys), Settings.HOLD_FEED_VIDEO_FAST.key, keys[at - 1]);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HOLD_FEED_VIDEO_FAST.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HOLD_FEED_VIDEO_KEEP.key));
    }
    @Test public void withTheHoldSwitchOnItCanBeTurnedOn() throws Exception {
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_KEEP.key);
        assertNotNull(row);
        assertTrue(row.isEnabled());
    }
    /** #552: Only after a long hold sits right under Keep 2x after you let go, off, and rests until that one is on. */
    @Test public void theLongHoldSwitchSitsUnderTheKeepSwitch() throws Exception {
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_KEEP_LONG.key);
        assertNotNull(row);
        assertEquals("Only after a long hold", row.getTitle().toString());
        assertEquals("Keeps 2x only when you hold the video for about 3 seconds. Let go sooner and it goes back to "
                + "normal speed.", row.getSummary().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse("greyed out with Keep 2x after you let go off", row.isEnabled());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.HOLD_FEED_VIDEO_KEEP_LONG.key);
        assertEquals(Arrays.toString(keys), Settings.HOLD_FEED_VIDEO_KEEP.key, keys[at - 1]);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HOLD_FEED_VIDEO_KEEP_LONG.key));
        assertEquals(Arrays.asList(Settings.HOLD_FEED_VIDEO_FAST, Settings.HOLD_FEED_VIDEO_KEEP, Settings.HOLD_FEED_VIDEO_KEEP_LONG,
                Settings.HOLD_FEED_VIDEO_KEEP_BADGE, Settings.HOLD_FEED_VIDEO_KEEP_NAME, Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES),
                PatchFamily.FEED_HOLD_FAST.switches);
    }

    /** #860: Show 2x after you let go sits right under Only after a long hold, off, rests until Keep 2x after you let go is on, and goes in a backup. */
    @Test public void theShowTwoTimesSwitchFollowsTheLongHoldSwitch() throws Exception {
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_KEEP_BADGE.key);
        assertNotNull(row);
        assertEquals("Show 2x after you let go", row.getTitle().toString());
        assertEquals("A feed video that keeps playing at 2x shows Instagram's 2x and time labels in place of the name until it "
                + "goes back to normal speed.", row.getSummary().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse("greyed out with Keep 2x after you let go off", row.isEnabled());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.HOLD_FEED_VIDEO_KEEP_BADGE.key);
        assertEquals(Arrays.toString(keys), Settings.HOLD_FEED_VIDEO_KEEP_LONG.key, keys[at - 1]);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HOLD_FEED_VIDEO_KEEP_BADGE.key));
        controller.close();
        controller = null;
        Settings.HOLD_FEED_VIDEO_KEEP.save(true);
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST));
        assertTrue("on with Keep 2x after you let go on",
                page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_KEEP_BADGE.key).isEnabled());
    }

    /** #639: Hold the middle to pause it follows Keep the name, off, rests until Hold for 2x on feed videos is on, and needs the tap patch. */
    @Test public void theMiddlePausesSwitchFollowsKeepTheNameWhenTheTapPatchIsIn() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST, PatchFamily.FEED_TAP_PAUSE));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.key);
        assertNotNull(row);
        assertEquals("Hold the middle to pause it", row.getTitle().toString());
        assertEquals("Holding the middle of a feed video pauses it until you let go. Hold its left or right side for 2x.",
                row.getSummary().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse("greyed out with Hold for 2x on feed videos off", row.isEnabled());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.key);
        assertEquals(Arrays.toString(keys), Settings.HOLD_FEED_VIDEO_KEEP_NAME.key, keys[at - 1]);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.key));
        controller.close();
        controller = null;
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST));
        assertNull("no tap patch, nothing to pause with", page.getPreferenceScreen().findPreference(
                Settings.HOLD_FEED_VIDEO_MIDDLE_PAUSES.key));
    }

    /** #552: Keep the name while you hold comes after the Keep 2x switches, off, and rests until Hold for 2x on feed videos is on. */
    @Test public void theKeepTheNameSwitchFollowsTheKeepSwitches() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST));
        Preference row = page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_KEEP_NAME.key);
        assertNotNull(row);
        assertEquals("Keep the name while you hold", row.getTitle().toString());
        assertEquals("The name over a feed video stays while you hold it for 2x. Instagram's 2x and time labels don't show then.",
                row.getSummary().toString());
        assertFalse(((SwitchPreference) row).isChecked());
        assertFalse("greyed out with Hold for 2x on feed videos off", row.isEnabled());
        PreferenceGroup feed = row.getParent();
        String[] keys = new String[feed.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = feed.getPreference(i).getKey();
        int at = Arrays.asList(keys).indexOf(Settings.HOLD_FEED_VIDEO_KEEP_NAME.key);
        assertEquals(Arrays.toString(keys), Settings.HOLD_FEED_VIDEO_KEEP_BADGE.key, keys[at - 1]);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HOLD_FEED_VIDEO_KEEP_NAME.key));
        controller.close();
        controller = null;
        Settings.HOLD_FEED_VIDEO_FAST.save(true);
        open(EnumSet.of(PatchFamily.FEED_HOLD_FAST));
        Preference on = page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_KEEP_NAME.key);
        assertTrue("on with Hold for 2x on feed videos on, Keep 2x after you let go off", on.isEnabled());
    }

    @Test public void missingPatchHasNoKeepSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.FEED_TAP_PAUSE));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HOLD_FEED_VIDEO_KEEP.key));
    }
}
