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

/** The typing switch: under Messages after the other message rows, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class TypingSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.HIDE_TYPING.resetToDefault();
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
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
    @Test public void missingPatchHasNoTypingSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW, PatchFamily.THREAD_SEEN));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_TYPING.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.READ_WITHOUT_SEEN_RECEIPT.key));
    }
    /** Without the other message patches, Messages still opens for this switch alone. */
    @Test public void typingAloneStillGetsMessages() throws Exception {
        open(EnumSet.of(PatchFamily.TYPING));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_TYPING.key);
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_ROW.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.READ_WITHOUT_SEEN_RECEIPT.key));
    }
    @Test public void typingSwitchStartsOffUnderMessagesPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW, PatchFamily.INSTANTS, PatchFamily.THREAD_SEEN, PatchFamily.TYPING));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_TYPING.key);
        assertNotNull(row);
        assertEquals("Hide that you're typing", row.getTitle().toString());
        assertEquals("People you're chatting with don't see the typing dots while you write, and you still "
                + "see theirs.", row.getSummary().toString());
        PreferenceGroup messages = row.getParent();
        assertEquals("Messages", messages.getTitle().toString());
        String[] keys = new String[messages.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = messages.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.HIDE_NOTES_ROW.key, Settings.HIDE_INSTANTS.key,
                Settings.READ_WITHOUT_SEEN_RECEIPT.key, Settings.HIDE_TYPING.key), Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.HIDE_TYPING.get());
        assertFalse("each start of typing reads the switch, so no restart", Settings.HIDE_TYPING.rebootApp);
        assertEquals(Collections.singletonList(Settings.HIDE_TYPING), PatchFamily.TYPING.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.HIDE_TYPING.key));

        Settings.HIDE_TYPING.save(true);
        assertTrue(Settings.HIDE_TYPING.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.HIDE_TYPING.get());
        assertTrue(Settings.HIDE_TYPING.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.HIDE_TYPING.get());
        assertFalse("the seen receipt switch stays its own", Settings.READ_WITHOUT_SEEN_RECEIPT.get());
    }
}
