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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Keep Reels auto scroll on's two switches: under Reels, off to start, off while paused; its memory is no switch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ReelAutoScrollSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.KEEP_REEL_AUTO_SCROLL.resetToDefault();
        Settings.REEL_AUTO_ADVANCE.resetToDefault();
        Settings.REEL_AUTO_SCROLL_ON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.KEEP_REEL_AUTO_SCROLL.resetToDefault();
        Settings.REEL_AUTO_ADVANCE.resetToDefault();
        Settings.REEL_AUTO_SCROLL_ON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.REEL_AUTO_SCROLL) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoAutoScrollSwitch() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_AUTO_SCROLL.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.REEL_AUTO_ADVANCE.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.REEL_AUTO_SCROLL_ON.key));
    }
    @Test public void autoScrollSwitchStartsOffPersistsAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_AUTO_SCROLL.key);
        assertNotNull(row);
        assertEquals("Keep auto scroll on", row.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.KEEP_REEL_AUTO_SCROLL.get());
        assertNull("the memory isn't a control", page.getPreferenceScreen().findPreference(Settings.REEL_AUTO_SCROLL_ON.key));
        assertEquals(java.util.Arrays.asList(Settings.KEEP_REEL_AUTO_SCROLL, Settings.REEL_AUTO_ADVANCE),
                PatchFamily.REEL_AUTO_SCROLL.switches);
        assertFalse("a backup leaves the memory out", ConfigurationBackup.eligible().containsKey(Settings.REEL_AUTO_SCROLL_ON.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_REEL_AUTO_SCROLL.key));

        Settings.KEEP_REEL_AUTO_SCROLL.save(false);
        assertFalse(Settings.KEEP_REEL_AUTO_SCROLL.savedValue());
        Settings.KEEP_REEL_AUTO_SCROLL.save(true);
        Settings.REEL_AUTO_SCROLL_ON.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.KEEP_REEL_AUTO_SCROLL.get());
        assertTrue(Settings.KEEP_REEL_AUTO_SCROLL.savedValue());
        assertTrue("a pause leaves the memory alone", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.KEEP_REEL_AUTO_SCROLL.get());
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
    }
    /**
     * Move on when a reel ends sits right under Keep auto scroll on, off to start, and asks for a
     * restart, since the Reels viewer builds its scroller as it opens. A pause answers off and keeps
     * the saved choice. The words in the request it came from find it.
     */
    @Test public void moveOnSwitchStartsOffUnderKeepAsksForARestartAndHonorsPause() throws Exception {
        open(true);
        SwitchPreference keep = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_AUTO_SCROLL.key);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.REEL_AUTO_ADVANCE.key);
        assertNotNull(row);
        assertEquals("Move on when a reel ends", row.getTitle().toString());
        assertEquals("When a reel ends, Reels moves on to the next one instead of playing it again. It uses Instagram's own "
                + "auto scroll, even on accounts where Instagram doesn't offer it. While it's on, Instagram's own Auto scroll "
                + "switch can't turn it off. Restart Instagram to see the change.",
                row.getSummary().toString());
        assertEquals("Reels", row.getParent().getTitle().toString());
        assertSame(keep.getParent(), row.getParent());
        int above = -1;
        for (int i = 0; i < row.getParent().getPreferenceCount(); i++) if (row.getParent().getPreference(i) == keep) above = i;
        assertSame("right under Keep auto scroll on", row, row.getParent().getPreference(above + 1));
        assertFalse(row.isChecked());
        assertEquals(Boolean.FALSE, Settings.REEL_AUTO_ADVANCE.defaultValue);
        assertFalse(Settings.REEL_AUTO_ADVANCE.get());
        assertTrue("the viewer builds its scroller as it opens", Settings.REEL_AUTO_ADVANCE.rebootApp);
        assertFalse("keeping a choice needs no restart", Settings.KEEP_REEL_AUTO_SCROLL.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.REEL_AUTO_ADVANCE.key));

        Settings.REEL_AUTO_ADVANCE.save(true);
        assertTrue(Settings.REEL_AUTO_ADVANCE.get());
        assertFalse("turning it on turns on no remembered choice", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertFalse("nor the other switch", Settings.KEEP_REEL_AUTO_SCROLL.savedValue());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.REEL_AUTO_ADVANCE.get());
        assertTrue(Settings.REEL_AUTO_ADVANCE.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.REEL_AUTO_ADVANCE.get());

        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        for (String query : new String[]{"auto advance", "AUTOLOOP", "loop", "Keep Reels auto scroll on"}) {
            page.searchSettings(query);
            assertNotNull(query, page.getPreferenceScreen().findPreference(Settings.REEL_AUTO_ADVANCE.key));
        }
        page.searchSettings("autoloop");
        assertNull("the words are the new switch's alone", page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_AUTO_SCROLL.key));
        page.searchSettings("");
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.KEEP_REEL_AUTO_SCROLL.key));
    }
}
