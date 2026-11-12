/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.EditText;

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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.EnumSet;

import app.hushgram.extension.instagram.direct.LockDelay;
import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.direct.MessagesLockForTests;
import app.hushgram.extension.instagram.direct.PinLock;
import app.hushgram.extension.instagram.direct.UnlockWith;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * With a HushGram PIN behind the locks, every setting that would weaken one asks the PIN, the
 * phone's lock never stands in for it, and a wrong PIN leaves the setting as it was. The rows are
 * the ones on the settings screen, reached by search and by the full list as well.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class PinBypassTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final String PIN = "4821";

    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before
    public void setup() throws Exception {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        MessagesLockForTests.reset();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.LOCK_MESSAGES.save(true);
        Settings.LOCK_APP.save(true);
        Settings.UNLOCK_WITH.save(UnlockWith.PIN);
        assertTrue(PinLock.set(PIN));
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.MESSAGES_LOCK);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, page, "pin-page").commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void close() throws Exception {
        controller.close();
        Utils.awaitBackgroundTasksForTests();
        MessagesLockForTests.reset();
        PauseForTests.resume();
        BaseSettings.PAUSED.save(false);
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCK_APP.resetToDefault();
        Settings.LOCK_AGAIN.resetToDefault();
        Settings.UNLOCK_WITH.resetToDefault();
        Settings.LOCK_PIN.resetToDefault();
        Settings.LOCK_PIN_MISSES.resetToDefault();
        Settings.LOCK_PIN_WAIT_UNTIL.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        PatchFamily.inBuildForTests = null;
    }

    private Preference row(String key) {
        Preference found = page.getPreferenceScreen().findPreference(key);
        assertNotNull("no row " + key, found);
        return found;
    }

    private static boolean change(Preference row, Object value) {
        return row.getOnPreferenceChangeListener().onPreferenceChange(row, value);
    }

    private static AlertDialog dialog() {
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no PIN asked", dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static void enter(AlertDialog dialog, String digits) {
        ((EditText) dialog.getWindow().getDecorView().findViewWithTag("hushgram_pin_field")).setText(digits);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
    }

    /**
     * The full list, search in the list, and search with categories as pages: the row reached each
     * way is the one built once, with the same handler.
     */
    private void eachRoute(String key, String query, Runnable check) {
        Preference built = null;
        for (boolean pages : new boolean[]{false, false, true}) {
            Settings.CATEGORY_PAGES.save(pages);
            page.searchSettings(built == null ? "" : query);
            Preference row = row(key);
            if (built == null) built = row;
            assertSame("search made another row", built, row);
            check.run();
        }
        Settings.CATEGORY_PAGES.resetToDefault();
    }

    @Test
    public void turningLockYourMessagesOffAsksThePin() {
        checkSwitch(Settings.LOCK_MESSAGES.key, "Lock your messages");
    }

    @Test
    public void turningLockAllOfInstagramOffAsksThePin() {
        checkSwitch(Settings.LOCK_APP.key, "Lock all of Instagram");
    }

    private void checkSwitch(String key, String query) {
        eachRoute(key, query, () -> {
            SwitchPreference row = (SwitchPreference) row(key);
            row.setChecked(true);
            assertFalse("the switch let go without the PIN", change(row, false));
            AlertDialog dialog = dialog();
            enter(dialog, "0000");
            assertTrue("a wrong PIN turned it off", row.isChecked());
            assertTrue(dialog.isShowing());
            enter(dialog, PIN);
            assertFalse("the right PIN didn't turn it off", row.isChecked());
        });
    }

    @Test
    public void aSwitchWithNothingToAskStaysOn() {
        SwitchPreference row = HushgramPreferenceFragment.lockToggle(
                RuntimeEnvironment.getApplication(), Settings.LOCK_MESSAGES, "Lock your messages", "");
        row.setChecked(true);
        assertFalse(change(row, false));
        assertTrue(row.isChecked());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void turningALockOffStillAsksThePinWhileHushGramIsPaused() {
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{
                HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP, HushgramPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            try {
                assertTrue("paused " + reason + " dropped the lock", Settings.LOCK_MESSAGES.get());
                assertTrue(Settings.LOCK_APP.get());
                assertTrue(PinLock.active());
                SwitchPreference row = (SwitchPreference) row(Settings.LOCK_MESSAGES.key);
                row.setChecked(true);
                assertFalse(change(row, false));
                AlertDialog dialog = dialog();
                enter(dialog, "1111");
                assertTrue(row.isChecked());
                dialog.cancel();
            } finally {
                PauseForTests.resume();
            }
        }
    }

    @Test
    public void switchingBackToPhoneLockAsksThePin() {
        eachRoute(Settings.UNLOCK_WITH.key, "Unlock with", () -> {
            UnlockRow row = unlockRow();
            row.setValue("PIN");
            assertFalse(change(row, "PHONE_LOCK"));
            AlertDialog dialog = dialog();
            enter(dialog, "9999");
            assertEquals("a wrong PIN changed how it unlocks", "PIN", row.getValue());
            assertEquals(UnlockWith.PIN, Settings.UNLOCK_WITH.get());
            enter(dialog, PIN);
            assertEquals("PHONE_LOCK", row.getValue());
            row.setValue("PIN");
        });
    }

    @Test
    public void changingThePinAsksTheCurrentOneFirst() {
        eachRoute("hushgram_change_pin_row", "HushGram PIN", () -> {
            Preference row = row("hushgram_change_pin_row");
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            AlertDialog ask = dialog();
            enter(ask, "5555");
            assertTrue("a wrong PIN opened the new-PIN dialog", ask.isShowing());
            assertTrue(PinLock.matches(PIN));

            enter(ask, PIN);
            AlertDialog set = dialog();
            ((EditText) set.getWindow().getDecorView().findViewWithTag("hushgram_pin_field")).setText("13572468");
            ((EditText) set.getWindow().getDecorView().findViewWithTag("hushgram_pin_confirm")).setText("13572468");
            set.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertTrue(PinLock.matches("13572468"));
            assertTrue(PinLock.set(PIN));
        });
    }

    @Test
    public void changingThePinWithoutTheCurrentOneChangesNothing() {
        Preference row = row("hushgram_change_pin_row");
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        AlertDialog ask = dialog();
        ask.cancel();
        ShadowLooper.idleMainLooper();
        assertTrue(PinLock.matches(PIN));
        assertFalse(PinLock.matches("13572468"));
    }

    /**
     * A longer Lock again weakens the locks, so with a PIN it asks the PIN every time, even with the
     * session already unlocked, and a wrong PIN changes nothing (audit 2026-10-12).
     */
    @Test
    public void waitingLongerToLockAgainAsksThePin() {
        MessagesLockForTests.open();
        eachRoute(Settings.LOCK_AGAIN.key, "Lock again", () -> {
            android.preference.ListPreference row = delayRow();
            row.setValue(LockDelay.RIGHT_AWAY.name());
            ShadowLooper.idleMainLooper();
            assertFalse("an hour went in without the PIN", change(row, LockDelay.ONE_HOUR.name()));
            AlertDialog dialog = dialog();
            enter(dialog, "0000");
            assertEquals("a wrong PIN changed the wait", LockDelay.RIGHT_AWAY.name(), row.getValue());
            assertEquals(LockDelay.RIGHT_AWAY, Settings.LOCK_AGAIN.savedValue());
            enter(dialog, PIN);
            assertEquals(LockDelay.ONE_HOUR.name(), row.getValue());
            assertEquals("the right PIN didn't save the wait", LockDelay.ONE_HOUR, Settings.LOCK_AGAIN.savedValue());
            row.setValue(LockDelay.RIGHT_AWAY.name());
            ShadowLooper.idleMainLooper();
        });
    }

    @Test
    public void cancellingThePinKeepsTheWaitAndAShorterOneNeedsNone() {
        android.preference.ListPreference row = delayRow();
        row.setValue(LockDelay.FIFTEEN_MINUTES.name());
        ShadowLooper.idleMainLooper();
        assertFalse(change(row, LockDelay.ONE_HOUR.name()));
        AlertDialog asked = dialog();
        asked.cancel();
        ShadowLooper.idleMainLooper();
        assertEquals("a cancel changed the wait", LockDelay.FIFTEEN_MINUTES.name(), row.getValue());
        assertEquals(LockDelay.FIFTEEN_MINUTES, Settings.LOCK_AGAIN.savedValue());

        assertTrue("a shorter wait didn't go in", change(row, LockDelay.ONE_MINUTE.name()));
        assertTrue("the same wait didn't go in", change(row, LockDelay.FIFTEEN_MINUTES.name()));
        ShadowLooper.idleMainLooper();
        assertSame("a shorter wait asked the PIN", asked, ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void withThePhoneLockALongerWaitNeedsNoPin() {
        Settings.UNLOCK_WITH.save(UnlockWith.PHONE_LOCK);
        android.preference.ListPreference row = delayRow();
        row.setValue(LockDelay.RIGHT_AWAY.name());
        ShadowLooper.idleMainLooper();
        assertTrue(change(row, LockDelay.ONE_HOUR.name()));
        ShadowLooper.idleMainLooper();
        assertNull("the PIN was asked with the phone lock chosen", ShadowAlertDialog.getLatestAlertDialog());
    }

    private android.preference.ListPreference delayRow() {
        return (android.preference.ListPreference) row(Settings.LOCK_AGAIN.key);
    }

    private UnlockRow unlockRow() {
        return new UnlockRow(row(Settings.UNLOCK_WITH.key));
    }

    /** The Unlock with row, read and set as its list. */
    private static final class UnlockRow {
        private final android.preference.ListPreference list;
        private final Preference row;

        UnlockRow(Preference row) {
            this.row = row;
            this.list = (android.preference.ListPreference) row;
        }

        String getValue() { return list.getValue(); }
        void setValue(String value) { list.setValue(value); }
        Preference.OnPreferenceChangeListener listener() { return row.getOnPreferenceChangeListener(); }
    }

    private static boolean change(UnlockRow row, Object value) {
        return row.listener().onPreferenceChange(row.row, value);
    }
}
