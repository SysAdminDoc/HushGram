/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.preference.DialogPreference;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.hushgram.extension.instagram.direct.HiddenChats;
import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.direct.MessagesLockForTests;
import app.hushgram.extension.instagram.direct.PinLock;
import app.hushgram.extension.instagram.direct.UnlockWith;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * Settings opened during an unlocked session can stay up across a leave, and the lock's cover over
 * Instagram is in the activity's window, under them. When the lock comes back they're covered too,
 * and a settings file or an Undo that arrives while locked (the file picker's own screen is a leave)
 * waits for a new unlock, with the phone's lock behind Lock your messages (audit 2026-10-12).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SettingsRelockTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private static final Uri DOCUMENT = Uri.parse("content://test-documents/settings.json");
    /** MessagesLock's mark on its covers. */
    private static final String COVER_TAG = "hushgram_messages_lock";
    private static final String ACCOUNT = "17841400000000002";

    private final List<Runnable[]> asks = new ArrayList<>();

    @Before
    public void setup() {
        MessagesLockForTests.reset();
        MessagesLockForTests.askWith(asks);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.MESSAGES_LOCK, PatchFamily.HIDE_ADS);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.UNLOCK_WITH.save(UnlockWith.PHONE_LOCK);
        Settings.LOCK_MESSAGES.save(true);
        Settings.HIDE_ADS.save(true);
        ConfigurationBackup.forgetUndo();
        HushgramPreferenceFragment.importFeedback = null;
    }

    @After
    public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        MessagesLockForTests.reset();
        MessagesLockForTests.signOut();
        ConfigurationBackup.forgetUndo();
        HushgramPreferenceFragment.importFeedback = null;
        Settings.HIDDEN_CHATS.resetToDefault();
        Settings.LOCK_AGAIN.resetToDefault();
        Settings.LOCK_PIN.resetToDefault();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCK_APP.resetToDefault();
        Settings.UNLOCK_WITH.resetToDefault();
        Settings.HIDE_ADS.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    @Test
    public void settingsLeftOpenAreCoveredWhenTheLockComesBack() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            MessagesLockForTests.open();
            SettingsDialog dialog = show(controller);
            draw(dialog);
            assertNull("covered while unlocked", cover(dialog));

            // Left with the settings up (Home has no inbox, so nothing covers the activity), and back.
            controller.pause().stop();
            MessagesLockForTests.relock();
            controller.start().resume();
            draw(dialog);

            View cover = cover(dialog);
            assertNotNull("the settings came back usable while locked", cover);
            assertEquals(View.VISIBLE, cover.getVisibility());
            View content = dialog.getDialog().getWindow().getDecorView().findViewById(android.R.id.content);
            assertEquals("a screen reader could still read the settings",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, content.getImportantForAccessibility());
            assertTrue(texts(cover).contains("HushGram settings are locked"));

            // A cancel keeps the cover and its Unlock button.
            unlockButton(cover).performClick();
            assertEquals(1, asks.size());
            asks.get(0)[1].run();
            draw(dialog);
            assertEquals(View.VISIBLE, cover.getVisibility());
            assertTrue(MessagesLock.locked());

            unlockButton(cover).performClick();
            assertEquals(2, asks.size());
            asks.get(1)[0].run();
            draw(dialog);
            assertEquals("unlocked, the cover stayed", View.GONE, cover.getVisibility());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, content.getImportantForAccessibility());
        }
    }

    /** Robolectric gives a dialog's window no focus of its own, so each test here hands it over. */
    @Test
    public void theLockIsAskedOnceAsTheSettingsGetTheFocus() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            MessagesLockForTests.open();
            SettingsDialog dialog = show(controller);
            draw(dialog);
            focus(dialog, true);
            assertEquals("asked while unlocked", 0, asks.size());

            leaveAndComeBack(controller, dialog);
            assertNotNull(cover(dialog));
            assertEquals("asked before the settings had the focus", 0, asks.size());
            focus(dialog, true);
            assertEquals("not asked once the settings had the focus", 1, asks.size());

            // Cancelled: the cover stays, and the focus going and coming back doesn't ask again.
            asks.get(0)[1].run();
            focus(dialog, false);
            focus(dialog, true);
            draw(dialog);
            assertEquals("a cancel was asked again at once", 1, asks.size());
            assertEquals(View.VISIBLE, cover(dialog).getVisibility());

            // The next time the lock comes back, it's asked again.
            leaveAndComeBack(controller, dialog);
            focus(dialog, true);
            assertEquals(2, asks.size());
        }
    }

    /** The file's ask comes up before the settings have the focus back, and is their one ask. */
    @Test
    public void aCancelledSettingsFileIsNotAskedAgainAsTheFocusComesBack() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            MessagesLockForTests.open();
            SettingsDialog dialog = show(controller);
            pickWhileTheLockComesBack(controller, page(dialog));
            assertEquals(1, asks.size());

            asks.get(0)[1].run();
            finish();
            draw(dialog);
            focus(dialog, true);
            assertEquals("the cancel was asked again as the settings got the focus", 1, asks.size());
            assertNotNull(cover(dialog));
            assertEquals(View.VISIBLE, cover(dialog).getVisibility());
            assertTrue(Settings.LOCK_MESSAGES.savedValue());
        }
    }

    /**
     * A list or a row's choices left open across a leave sat over the settings' cover, the hidden
     * chats naming them and a tap able to show one again. They close once the cover is up, and with
     * them gone the settings have the focus for the lock's ask.
     */
    @Test
    public void whatWasOpenOverTheSettingsClosesWhenTheyAreCovered() throws Exception {
        MessagesLockForTests.signIn(ACCOUNT);
        assertTrue(HiddenChats.add(ACCOUNT, "350000000000001", "Qorvath Brindle"));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            MessagesLockForTests.open();
            SettingsDialog dialog = show(controller);
            HushgramPreferenceFragment page = page(dialog);
            draw(dialog);

            Preference hiddenRow = page.findPreference(HushgramPreferenceFragment.HIDDEN_CHATS_ROW);
            assertNotNull(hiddenRow);
            hiddenRow.getOnPreferenceClickListener().onPreferenceClick(hiddenRow);
            assertEquals("the list didn't ask first", 1, asks.size());
            asks.get(0)[0].run();
            ShadowLooper.idleMainLooper();
            AlertDialog list = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(list);
            assertTrue(list.isShowing());

            DialogPreference waitRow = (DialogPreference) page.findPreference(Settings.LOCK_AGAIN.key);
            assertNotNull(waitRow);
            ReflectionHelpers.callInstanceMethod(waitRow, "onClick");
            ShadowLooper.idleMainLooper();
            Dialog choices = waitRow.getDialog();
            assertNotNull(choices);
            assertTrue(choices.isShowing());

            // Unlocked, the settings' own frames leave them be.
            draw(dialog);
            assertTrue("closed while unlocked", list.isShowing());
            assertTrue("closed while unlocked", choices.isShowing());
            String wait = Settings.LOCK_AGAIN.get().name();

            leaveAndComeBack(controller, dialog);
            assertNotNull(cover(dialog));
            assertFalse("the hidden chats list stayed over the cover", list.isShowing());
            assertFalse("Lock again's choices stayed over the cover", choices.isShowing());
            assertEquals("a hidden chat was shown again", 1, HiddenChats.saved(ACCOUNT).size());
            assertEquals("closing the choices picked one", wait, Settings.LOCK_AGAIN.get().name());

            focus(dialog, true);
            assertEquals("the lock wasn't asked once they'd gone", 2, asks.size());
        }
    }

    /**
     * Set a PIN is a window of its own over the settings. Left open across a leave, whoever picked
     * up the phone next could have chosen the PIN that unlocks the messages. It closes with the
     * cover, as a cancel: no PIN, and Unlock with stays on the phone's lock.
     */
    @Test
    public void setAPinLeftOpenClosesWhenTheSettingsAreCovered() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            MessagesLockForTests.open();
            SettingsDialog dialog = show(controller);
            HushgramPreferenceFragment page = page(dialog);
            draw(dialog);

            Preference unlockWith = page.findPreference(Settings.UNLOCK_WITH.key);
            assertNotNull(unlockWith);
            assertFalse(unlockWith.getOnPreferenceChangeListener().onPreferenceChange(unlockWith, UnlockWith.PIN.name()));
            ShadowLooper.idleMainLooper();
            AlertDialog setPin = (AlertDialog) ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("Set a PIN didn't open", setPin);
            assertTrue(setPin.isShowing());
            draw(dialog);
            assertTrue("closed while unlocked", setPin.isShowing());

            leaveAndComeBack(controller, dialog);
            assertNotNull(cover(dialog));
            assertFalse("Set a PIN stayed over the cover", setPin.isShowing());
            assertFalse(PinLock.isSet());
            assertEquals(UnlockWith.PHONE_LOCK, Settings.UNLOCK_WITH.get());
        }
    }

    /**
     * On Android 9 and 10 the phone's own check is a screen of its own, so Instagram leaves while
     * it's up and the lock comes back under it. Cancelled, that ask was the settings' one ask.
     */
    @Test
    public void anAskUpAsTheLockComesBackIsTheSettingsOneAsk() throws Exception {
        MessagesLockForTests.signIn(ACCOUNT);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            MessagesLockForTests.open();
            SettingsDialog dialog = show(controller);
            HushgramPreferenceFragment page = page(dialog);
            draw(dialog);

            Preference hiddenRow = page.findPreference(HushgramPreferenceFragment.HIDDEN_CHATS_ROW);
            assertNotNull(hiddenRow);
            hiddenRow.getOnPreferenceClickListener().onPreferenceClick(hiddenRow);
            assertEquals(1, asks.size());
            focus(dialog, false);
            controller.pause().stop();
            MessagesLockForTests.leave();
            controller.start().resume();
            assertTrue("the leave didn't lock", MessagesLock.locked());

            asks.get(0)[1].run();
            draw(dialog);
            assertNotNull(cover(dialog));
            focus(dialog, true);
            assertEquals("the cancel was asked again as the settings got the focus", 1, asks.size());
            assertEquals(View.VISIBLE, cover(dialog).getVisibility());
        }
    }

    @Test
    public void aSettingsFileThatArrivesAfterTheLockCameBackWaitsForANewUnlock() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            MessagesLockForTests.open();
            HushgramPreferenceFragment page = page(show(controller));

            // Cancelled: nothing changes, and the switch stays on.
            pickWhileTheLockComesBack(controller, page);
            assertEquals("the import didn't wait for an unlock", 1, asks.size());
            finish();
            assertTrue(Settings.LOCK_MESSAGES.savedValue());
            assertTrue(Settings.HIDE_ADS.savedValue());
            asks.get(0)[1].run();
            finish();
            assertTrue("a cancel turned the lock off", Settings.LOCK_MESSAGES.savedValue());
            assertTrue(Settings.HIDE_ADS.savedValue());
            assertFalse(ConfigurationBackup.canUndo());
            assertTrue("the import row stayed off after the cancel",
                    page.findPreference("hushgram_import_configuration").isEnabled());

            // Unlocked: the file applies once, with no second ask.
            pickWhileTheLockComesBack(controller, page);
            assertEquals(2, asks.size());
            asks.get(1)[0].run();
            finish();
            assertFalse(Settings.LOCK_MESSAGES.savedValue());
            assertFalse(Settings.HIDE_ADS.savedValue());
            assertEquals("asked again after the unlock", 2, asks.size());
        }
    }

    @Test
    public void anUndoWhileLockedWaitsForANewUnlock() throws Exception {
        Settings.LOCK_MESSAGES.save(false);
        Settings.LOCK_APP.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            MessagesLockForTests.open();
            HushgramPreferenceFragment page = page(show(controller));
            // An import turned Lock your messages on; its Undo would turn it off again.
            ConfigurationBackup.restore(ConfigurationBackupTest.file(
                    ConfigurationBackupTest.entry(Settings.LOCK_MESSAGES.key, "boolean", true)));
            page.onResume();
            Preference undo = page.findPreference("hushgram_undo_configuration");
            assertTrue(undo.isEnabled());

            MessagesLockForTests.relock();
            undo.getOnPreferenceClickListener().onPreferenceClick(undo);
            finish();
            assertEquals(1, asks.size());
            assertTrue("Undo ran while locked", Settings.LOCK_MESSAGES.savedValue());

            asks.get(0)[0].run();
            finish();
            assertFalse("the unlocked Undo didn't run", Settings.LOCK_MESSAGES.savedValue());
        }
    }

    @Test
    public void aWriteWhileLockedNeverSwitchesALockOff() throws Exception {
        Settings.LOCK_APP.save(true);
        ConfigurationBackup.Result result = ConfigurationBackup.restore(ConfigurationBackupTest.file(
                ConfigurationBackupTest.entry(Settings.LOCK_MESSAGES.key, "boolean", false) + ","
                        + ConfigurationBackupTest.entry(Settings.LOCK_APP.key, "boolean", false) + ","
                        + ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false)));
        assertTrue(Settings.LOCK_MESSAGES.savedValue());
        assertTrue(Settings.LOCK_APP.savedValue());
        assertFalse("the rest of the file still applies", Settings.HIDE_ADS.savedValue());
        assertEquals("the locks are held, not unknown", 0, result.skipped);
        assertEquals(2, result.held);
    }

    /** Picks a settings file turning the lock off; the picker's screen is a leave that locks again. */
    private void pickWhileTheLockComesBack(ActivityController<Activity> controller, HushgramPreferenceFragment page)
            throws Exception {
        Shadows.shadowOf(controller.get().getContentResolver()).registerInputStream(DOCUMENT,
                new ByteArrayInputStream(ConfigurationBackupTest.file(
                        ConfigurationBackupTest.entry(Settings.LOCK_MESSAGES.key, "boolean", false) + ","
                                + ConfigurationBackupTest.entry(Settings.HIDE_ADS.key, "boolean", false))));
        Preference row = page.findPreference("hushgram_import_configuration");
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        ShadowActivity.IntentForResult picked = Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(picked);
        MessagesLockForTests.relock();
        Shadows.shadowOf(controller.get()).receiveResult(picked.intent, Activity.RESULT_OK, new Intent().setData(DOCUMENT));
    }

    /** Left with the settings up, locked while away, and back with a frame drawn. */
    private static void leaveAndComeBack(ActivityController<Activity> controller, SettingsDialog dialog) {
        focus(dialog, false);
        controller.pause().stop();
        MessagesLockForTests.relock();
        controller.start().resume();
        draw(dialog);
    }

    /** What Android does as the settings' window gains or loses the focus. */
    private static void focus(SettingsDialog dialog, boolean hasFocus) {
        View decor = dialog.getDialog().getWindow().getDecorView();
        Object attachInfo = ReflectionHelpers.getField(decor, "mAttachInfo");
        assertNotNull("the settings' window isn't attached", attachInfo);
        ReflectionHelpers.setField(attachInfo, "mHasWindowFocus", hasFocus);
        assertEquals(hasFocus, decor.hasWindowFocus());
        ReflectionHelpers.callInstanceMethod(decor.getViewTreeObserver(), "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(boolean.class, hasFocus));
        ShadowLooper.idleMainLooper();
    }

    private static SettingsDialog show(ActivityController<Activity> controller) {
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "hushgram_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        return dialog;
    }

    private static HushgramPreferenceFragment page(SettingsDialog dialog) {
        HushgramPreferenceFragment page = (HushgramPreferenceFragment)
                dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertNotNull(page);
        return page;
    }

    /** Lays the dialog's window out the size of a phone and runs what it does before a frame. */
    private static void draw(SettingsDialog dialog) {
        ShadowLooper.idleMainLooper();
        View decor = dialog.getDialog().getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
        decor.getViewTreeObserver().dispatchOnPreDraw();
    }

    private static View cover(SettingsDialog dialog) {
        ViewGroup decor = (ViewGroup) dialog.getDialog().getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            if (COVER_TAG.equals(decor.getChildAt(i).getTag())) return decor.getChildAt(i);
        }
        return null;
    }

    private static View unlockButton(View cover) {
        List<TextView> found = new ArrayList<>();
        collect(cover, found);
        for (TextView text : found) if ("Unlock".contentEquals(text.getText())) return text;
        throw new AssertionError("no Unlock button on the cover");
    }

    private static List<String> texts(View cover) {
        List<TextView> found = new ArrayList<>();
        collect(cover, found);
        List<String> texts = new ArrayList<>();
        for (TextView text : found) texts.add(text.getText().toString());
        return texts;
    }

    private static void collect(View view, List<TextView> found) {
        if (view instanceof TextView) found.add((TextView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found);
    }

    private static void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }
}
