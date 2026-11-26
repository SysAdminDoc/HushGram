/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;

/** Primary and General tabs (#832): a switch under Messages right after Split the notes row, off to start, owing a restart. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class InboxTabsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.INBOX_TABS.resetToDefault();
        AbstractPreferenceFragment.restartPending.remove(Settings.INBOX_TABS.key);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.INBOX_TABS.resetToDefault();
        AbstractPreferenceFragment.restartPending.remove(Settings.INBOX_TABS.key);
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> installed) throws Exception {
        PatchFamily.inBuildForTests = installed;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRow() throws Exception {
        open(EnumSet.noneOf(PatchFamily.class));
        assertNull(page.getPreferenceScreen().findPreference(Settings.INBOX_TABS.key));
    }
    @Test public void theSwitchSitsUnderMessagesOffToStart() throws Exception {
        open(EnumSet.of(PatchFamily.INBOX_TABS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.INBOX_TABS.key);
        assertNotNull("the patch alone brings the Messages page", row);
        assertEquals("Primary and General tabs", row.getTitle().toString());
        String summary = row.getSummary().toString();
        assertTrue(summary, summary.startsWith("Experimental. "));
        assertTrue(summary, summary.contains(" Starts off. "));
        assertTrue(summary, summary.endsWith("Restart Instagram to see the change."));
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Messages", section.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.INBOX_TABS.get());
        assertTrue("Instagram reads the test when your messages are set up", Settings.INBOX_TABS.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.INBOX_TABS.key));
        assertEquals(Collections.singletonList(Settings.INBOX_TABS), PatchFamily.INBOX_TABS.switches);
        assertEquals("Primary and General tabs", PatchFamily.INBOX_TABS.patchName);
    }
    @Test public void turningItOnOwesARestartAndBackOffPaysIt() throws Exception {
        open(EnumSet.of(PatchFamily.INBOX_TABS));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.INBOX_TABS.key);
        assertFalse("nothing owed before a change", AbstractPreferenceFragment.restartPending.contains(Settings.INBOX_TABS.key));
        row.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(Settings.INBOX_TABS.get());
        assertTrue(AbstractPreferenceFragment.restartPending.contains(Settings.INBOX_TABS.key));
        row.setChecked(false);
        ShadowLooper.idleMainLooper();
        assertFalse(Settings.INBOX_TABS.get());
        assertFalse("back where the app runs, nothing owed", AbstractPreferenceFragment.restartPending.contains(Settings.INBOX_TABS.key));
    }
    @Test public void theSwitchFollowsSplitTheNotesRow() throws Exception {
        open(EnumSet.of(PatchFamily.SPLIT_NOTES_ROW, PatchFamily.INBOX_TABS, PatchFamily.INBOX_SEARCH));
        Preference row = page.getPreferenceScreen().findPreference(Settings.INBOX_TABS.key);
        PreferenceCategory messages = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(messages);
        int at = -1;
        for (int i = 0; i < messages.getPreferenceCount(); i++) {
            if (messages.getPreference(i) == row) at = i;
        }
        assertEquals(Settings.SPLIT_NOTES_ROW.key, messages.getPreference(at - 1).getKey());
        assertEquals(Settings.HIDE_INBOX_SEARCH.key, messages.getPreference(at + 1).getKey());
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
