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

/** The seen receipt switch: under Messages after the other message rows, off to start, off while paused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class ThreadSeenSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
        Settings.VIEW_DM_MEDIA_ANONYMOUSLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.READ_WITHOUT_SEEN_RECEIPT.resetToDefault();
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
    @Test public void missingPatchHasNoSeenReceiptSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW, PatchFamily.DM_MEDIA_SEEN));
        assertNull(page.getPreferenceScreen().findPreference(Settings.READ_WITHOUT_SEEN_RECEIPT.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_ROW.key));
    }
    /** Without the other message patches, Messages still opens for this switch alone. */
    @Test public void seenReceiptAloneStillGetsMessages() throws Exception {
        open(EnumSet.of(PatchFamily.THREAD_SEEN));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.READ_WITHOUT_SEEN_RECEIPT.key);
        assertNotNull(row);
        assertEquals("Messages", row.getParent().getTitle().toString());
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_NOTES_ROW.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.VIEW_DM_MEDIA_ANONYMOUSLY.key));
    }
    @Test public void seenReceiptSwitchStartsOffUnderMessagesPersistsAndHonorsPause() throws Exception {
        open(EnumSet.of(PatchFamily.NOTES_ROW, PatchFamily.INSTANTS, PatchFamily.THREAD_SEEN));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.READ_WITHOUT_SEEN_RECEIPT.key);
        assertNotNull(row);
        assertEquals("Read messages without the seen receipt", row.getTitle().toString());
        assertEquals("Opening a chat doesn't tell people you've seen their messages, and you still see "
                + "when they've seen yours. To let one chat know, long press it in your messages and "
                + "tap Mark as read.", row.getSummary().toString());
        PreferenceGroup messages = row.getParent();
        assertEquals("Messages", messages.getTitle().toString());
        String[] keys = new String[messages.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = messages.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.HIDE_NOTES_ROW.key, Settings.HIDE_INSTANTS.key,
                Settings.READ_WITHOUT_SEEN_RECEIPT.key), Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.get());
        assertFalse("each receipt reads the switch, so no restart", Settings.READ_WITHOUT_SEEN_RECEIPT.rebootApp);
        assertEquals(Collections.singletonList(Settings.READ_WITHOUT_SEEN_RECEIPT), PatchFamily.THREAD_SEEN.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.READ_WITHOUT_SEEN_RECEIPT.key));

        Settings.READ_WITHOUT_SEEN_RECEIPT.save(true);
        assertTrue(Settings.READ_WITHOUT_SEEN_RECEIPT.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.get());
        assertTrue(Settings.READ_WITHOUT_SEEN_RECEIPT.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(Settings.READ_WITHOUT_SEEN_RECEIPT.get());
        assertFalse("the view-once switch stays its own", Settings.VIEW_DM_MEDIA_ANONYMOUSLY.get());
    }
}
