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
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Glass in chats (#449): a switch under Messages, off to start. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class GlassChatSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        resetAll();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        resetAll();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.GLASS_CHAT) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    private static BooleanSetting[] all() {
        return new BooleanSetting[]{Settings.GLASS_CHAT};
    }
    private static void resetAll() {
        for (BooleanSetting each : all()) each.resetToDefault();
    }
    @Test public void missingPatchHasNoRow() throws Exception {
        open(false);
        for (BooleanSetting each : all()) assertNull(page.getPreferenceScreen().findPreference(each.key));
    }
    @Test public void theSwitchesSitUnderMessagesAndStartOff() throws Exception {
        open(true);
        String[] titles = {"Glass in chats"};
        BooleanSetting[] switches = all();
        for (int i = 0; i < switches.length; i++) {
            SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(switches[i].key);
            assertNotNull(titles[i], row);
            assertEquals(titles[i], row.getTitle().toString());
            PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
            assertNotNull(section);
            assertEquals("Messages", section.getTitle().toString());
            assertFalse(row.isChecked());
            assertFalse(switches[i].get());
            assertTrue(ConfigurationBackup.eligible().containsKey(switches[i].key));
            row.setChecked(true);
            assertTrue(switches[i].get());
        }
        assertEquals(java.util.Arrays.asList(switches), PatchFamily.GLASS_CHAT.switches);
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
