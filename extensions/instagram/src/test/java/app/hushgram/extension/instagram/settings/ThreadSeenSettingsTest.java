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
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
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
        Settings.KEEP_CHATS_UNREAD.resetToDefault();
        Settings.MARK_SEEN_ON_REPLY.resetToDefault();
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
                Settings.READ_WITHOUT_SEEN_RECEIPT.key, Settings.KEEP_CHATS_UNREAD.key, Settings.MARK_SEEN_ON_REPLY.key), Arrays.asList(keys));
        assertFalse(row.isChecked());
        assertFalse(Settings.READ_WITHOUT_SEEN_RECEIPT.get());
        assertFalse("each receipt reads the switch, so no restart", Settings.READ_WITHOUT_SEEN_RECEIPT.rebootApp);
        assertEquals(Arrays.asList(Settings.READ_WITHOUT_SEEN_RECEIPT, Settings.KEEP_CHATS_UNREAD, Settings.MARK_SEEN_ON_REPLY), PatchFamily.THREAD_SEEN.switches);
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
    /** #184: the unread switch sits right under the seen receipt switch, on the same page, on to start. */
    @Test public void keepChatsUnreadComesRightUnderTheSwitchAndStartsOn() throws Exception {
        open(EnumSet.of(PatchFamily.THREAD_SEEN, PatchFamily.TYPING));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.KEEP_CHATS_UNREAD.key);
        assertNotNull(row);
        assertEquals("Keep chats unread here", row.getTitle().toString());
        assertEquals("A chat you open stays unread in your messages on this phone until you long press it and tap "
                + "Mark as read. A newer message in it stays unread too. Turned off, a chat you open stays read "
                + "on this phone until a newer message comes in, even after your messages refresh.",
                row.getSummary().toString());
        PreferenceGroup messages = row.getParent();
        assertEquals("one level below the main page, beside its switch", "Messages", messages.getTitle().toString());
        String[] keys = new String[messages.getPreferenceCount()];
        for (int i = 0; i < keys.length; i++) keys[i] = messages.getPreference(i).getKey();
        assertEquals(Arrays.asList(Settings.READ_WITHOUT_SEEN_RECEIPT.key, Settings.KEEP_CHATS_UNREAD.key, Settings.MARK_SEEN_ON_REPLY.key, Settings.HIDE_TYPING.key),
                Arrays.asList(keys));
        assertTrue(Settings.KEEP_CHATS_UNREAD.defaultValue);
        assertTrue(Settings.KEEP_CHATS_UNREAD.get());
        assertTrue(row.isChecked());
        assertFalse("each chat that opens reads the switch, so no restart", Settings.KEEP_CHATS_UNREAD.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.KEEP_CHATS_UNREAD.key));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.KEEP_CHATS_UNREAD.get());
        assertTrue(Settings.KEEP_CHATS_UNREAD.savedValue());
    }
    /** #365: the reply switch sits under Keep chats unread, starts off and follows the seen receipt switch. */
    @Test public void markSeenOnReplyStartsOffAndFollowsTheReceiptSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.THREAD_SEEN));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.MARK_SEEN_ON_REPLY.key);
        assertNotNull(row);
        assertEquals("Mark a chat seen when you reply", row.getTitle().toString());
        assertFalse(Settings.MARK_SEEN_ON_REPLY.defaultValue);
        assertFalse(row.isChecked());
        assertFalse("each reply reads the switch, so no restart", Settings.MARK_SEEN_ON_REPLY.rebootApp);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.MARK_SEEN_ON_REPLY.key));
        Settings.MARK_SEEN_ON_REPLY.save(true);
        assertTrue(Settings.MARK_SEEN_ON_REPLY.get());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(Settings.MARK_SEEN_ON_REPLY.get());
        assertTrue(Settings.MARK_SEEN_ON_REPLY.savedValue());
    }
}
