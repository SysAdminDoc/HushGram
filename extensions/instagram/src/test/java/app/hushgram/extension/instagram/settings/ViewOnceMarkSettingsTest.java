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

/** The Mark one-time photos and videos switch (#165): under Messages right after Keep in chat, off to start, no restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ViewOnceMarkSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.MARK_VIEW_ONCE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.MARK_VIEW_ONCE.resetToDefault();
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
        open(EnumSet.of(PatchFamily.KEEP_IN_CHAT));
        assertNull(page.getPreferenceScreen().findPreference(Settings.MARK_VIEW_ONCE.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.KEEP_IN_CHAT.key));
    }
    /** Without the other Messages patches, Messages still opens for this switch alone. */
    @Test public void aloneItStillGetsMessages() throws Exception {
        open(EnumSet.of(PatchFamily.VIEW_ONCE_MARK));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_VIEW_ONCE.key);
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
    }
    @Test public void switchStartsOffUnderMessagesRightAfterKeepInChat() throws Exception {
        open(EnumSet.of(PatchFamily.KEEP_IN_CHAT, PatchFamily.VIEW_ONCE_MARK));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_VIEW_ONCE.key);
        assertNotNull(row);
        assertEquals("Mark one-time photos and videos", row.getTitle().toString());
        PreferenceGroup messages = row.getParent();
        assertEquals("Messages", messages.getTitle().toString());
        assertEquals("right after Keep in chat", Settings.KEEP_IN_CHAT.key,
                messages.getPreference(indexOf(messages, row) - 1).getKey());
        assertFalse(row.isChecked());
        assertFalse(Settings.MARK_VIEW_ONCE.get());
        assertFalse("read at each bind, so no restart", Settings.MARK_VIEW_ONCE.rebootApp);
        assertEquals(Collections.singletonList(Settings.MARK_VIEW_ONCE), PatchFamily.VIEW_ONCE_MARK.switches);
        assertEquals("Mark one-time photos and videos", PatchFamily.VIEW_ONCE_MARK.patchName);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.MARK_VIEW_ONCE.key));
    }
    private static int indexOf(PreferenceGroup group, SwitchPreference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) if (group.getPreference(i) == row) return i;
        return -1;
    }
}
