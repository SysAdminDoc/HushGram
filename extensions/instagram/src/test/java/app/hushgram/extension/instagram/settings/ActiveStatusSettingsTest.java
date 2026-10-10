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

/** The active status switch: under Messages after typing, off to start, off while paused, and one of Ghost mode's. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ActiveStatusSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.HIDE_ACTIVE_STATUS.resetToDefault();
        Settings.HIDE_TYPING.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_ACTIVE_STATUS.resetToDefault();
        Settings.HIDE_TYPING.resetToDefault();
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
    @Test public void missingPatchHasNoActiveStatusSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.THREAD_SEEN, PatchFamily.TYPING));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ACTIVE_STATUS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_TYPING.key));
    }
    /** Without the other message patches, Messages still opens for this switch alone. */
    @Test public void activeStatusAloneStillGetsMessages() throws Exception {
        open(EnumSet.of(PatchFamily.ACTIVE_STATUS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_ACTIVE_STATUS.key);
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_TYPING.key));
    }
    @Test public void activeStatusSwitchStartsOffAfterTypingPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.THREAD_SEEN, PatchFamily.TYPING, PatchFamily.ACTIVE_STATUS, PatchFamily.MESSAGES_LOCK));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_ACTIVE_STATUS.key);
        assertNotNull(row);
        assertEquals("Hide your active status", row.getTitle().toString());
        assertEquals("People don't see Active now for you while you use Instagram, and you still see when they're "
                + "active. Restart Instagram to see the change.", row.getSummary().toString());
        PreferenceGroup messages = row.getParent();
        assertEquals("Messages", messages.getTitle().toString());
        String[] keys = new String[messages.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = messages.getPreference(i).getKey();
        int typing = Arrays.asList(keys).indexOf(Settings.HIDE_TYPING.key);
        assertTrue(Arrays.toString(keys), typing >= 0);
        assertEquals("right after Hide that you're typing", Settings.HIDE_ACTIVE_STATUS.key, keys[typing + 1]);
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_ACTIVE_STATUS.get());
        assertFalse("each presence write reads the switch, so no restart prompt", Settings.HIDE_ACTIVE_STATUS.rebootApp);
        assertEquals(Collections.singletonList(Settings.HIDE_ACTIVE_STATUS), PatchFamily.ACTIVE_STATUS.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_ACTIVE_STATUS.key));

        Settings.HIDE_ACTIVE_STATUS.save(true);
        assertTrue(Settings.HIDE_ACTIVE_STATUS.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_ACTIVE_STATUS.get());
        assertTrue(Settings.HIDE_ACTIVE_STATUS.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_ACTIVE_STATUS.get());
        assertFalse("the typing switch stays its own", Settings.HIDE_TYPING.get());
    }
    /** Ghost mode turns it with the others, right after typing, and only when its patch is in the build. */
    @Test public void ghostModeTurnsItAfterTyping() {
        assertEquals(Arrays.asList(Settings.HIDE_TYPING, Settings.HIDE_ACTIVE_STATUS),
                GhostMode.switches(EnumSet.of(PatchFamily.ACTIVE_STATUS, PatchFamily.TYPING)));
        assertEquals(Collections.singletonList(Settings.HIDE_TYPING), GhostMode.switches(EnumSet.of(PatchFamily.TYPING)));
        assertTrue(GhostMode.offered(GhostMode.switches(EnumSet.of(PatchFamily.ACTIVE_STATUS, PatchFamily.TYPING))));
        assertFalse(GhostMode.offered(GhostMode.switches(EnumSet.of(PatchFamily.ACTIVE_STATUS))));
    }
}
