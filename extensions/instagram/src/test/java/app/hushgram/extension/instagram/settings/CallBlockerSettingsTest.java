/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.preference.ListPreference;
import android.preference.Preference;

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

import app.hushgram.extension.instagram.direct.CallBlockMode;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Call blocker's rows: under Messages, Everyone to start, and the list resting until a choice reads it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class CallBlockerSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CALL_BLOCK_MODE.resetToDefault();
        Settings.CALL_BLOCK_ACCOUNTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CALL_BLOCK_MODE.resetToDefault();
        Settings.CALL_BLOCK_ACCOUNTS.resetToDefault();
    }

    private void open(boolean installed) {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.CALL_BLOCKER) : Collections.emptySet();
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
    }

    private Preference row(String key) {
        return page.getPreferenceScreen().findPreference(key);
    }

    @Test public void notBuiltNoRows() {
        open(false);
        assertNull(row(Settings.CALL_BLOCK_MODE.key));
        assertNull(row(Settings.CALL_BLOCK_ACCOUNTS.key));
    }

    @Test public void everyoneToStartAndSaved() {
        open(true);
        ListPreference mode = (ListPreference) row(Settings.CALL_BLOCK_MODE.key);
        assertNotNull(mode);
        assertEquals(CallBlockMode.EVERYONE.name(), mode.getValue());
        assertEquals(CallBlockMode.EVERYONE, Settings.CALL_BLOCK_MODE.get());
        assertEquals(4, mode.getEntries().length);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CALL_BLOCK_MODE.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CALL_BLOCK_ACCOUNTS.key));
    }

    @Test public void theListRestsUntilAChoiceReadsIt() {
        open(true);
        ListPreference mode = (ListPreference) row(Settings.CALL_BLOCK_MODE.key);
        Preference list = row(Settings.CALL_BLOCK_ACCOUNTS.key);
        assertFalse("Everyone reads no list", list.isEnabled());

        mode.setValue(CallBlockMode.ONLY_LIST.name());
        Settings.CALL_BLOCK_MODE.save(CallBlockMode.ONLY_LIST);
        page.updatePreferenceAvailability(list, Settings.CALL_BLOCK_ACCOUNTS);
        assertTrue(list.isEnabled());
        assertEquals(HushgramPreferenceFragment.callBlockModeSummary(CallBlockMode.ONLY_LIST), mode.getSummary());

        mode.setValue(CallBlockMode.NOBODY.name());
        Settings.CALL_BLOCK_MODE.save(CallBlockMode.NOBODY);
        page.updatePreferenceAvailability(list, Settings.CALL_BLOCK_ACCOUNTS);
        assertFalse("Nobody reads no list", list.isEnabled());
    }
}
