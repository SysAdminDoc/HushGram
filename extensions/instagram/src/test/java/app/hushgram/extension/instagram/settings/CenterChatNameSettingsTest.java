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
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Center names in chats (#779): a switch under Messages right after the glass rows, off to start. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CenterChatNameSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CENTER_CHAT_NAME.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CENTER_CHAT_NAME.resetToDefault();
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
        assertNull(page.getPreferenceScreen().findPreference(Settings.CENTER_CHAT_NAME.key));
    }
    @Test public void theSwitchSitsUnderMessagesOffToStart() throws Exception {
        open(EnumSet.of(PatchFamily.CENTER_CHAT_NAME));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.CENTER_CHAT_NAME.key);
        assertNotNull("the patch alone brings the Messages page", row);
        assertEquals("Center names in chats", row.getTitle().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Messages", section.getTitle().toString());
        assertFalse(row.isChecked());
        assertFalse(Settings.CENTER_CHAT_NAME.get());
        assertFalse("a change shows on the next frame", Settings.CENTER_CHAT_NAME.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CENTER_CHAT_NAME.key));
        row.setChecked(true);
        assertTrue(Settings.CENTER_CHAT_NAME.get());
        assertEquals(Collections.singletonList(Settings.CENTER_CHAT_NAME), PatchFamily.CENTER_CHAT_NAME.switches);
    }
    @Test public void theSwitchFollowsTheGlassRows() throws Exception {
        open(EnumSet.of(PatchFamily.GLASS_CHAT, PatchFamily.CENTER_CHAT_NAME));
        Preference row = page.getPreferenceScreen().findPreference(Settings.CENTER_CHAT_NAME.key);
        PreferenceCategory messages = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(messages);
        int at = -1;
        for (int i = 0; i < messages.getPreferenceCount(); i++) {
            if (messages.getPreference(i) == row) at = i;
        }
        assertEquals(Settings.GLASS_CHAT_BOX_OPACITY.key, messages.getPreference(at - 1).getKey());
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
