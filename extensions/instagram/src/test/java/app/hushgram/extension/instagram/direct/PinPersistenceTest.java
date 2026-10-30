/* Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.FailingStore;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PinPersistenceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before public void prepare() {
        PinLock.clear();
        assertTrue(PinLock.set("4821"));
    }

    @After public void restore() {
        PinLock.closeNew();
        PinLock.clear();
        PinLock.clock = System::currentTimeMillis;
    }

    @Test public void aFailedPinTransactionKeepsThePreviousPinAndMisses() {
        for (FailingStore.Fault fault : failures()) {
            Settings.LOCK_PIN_MISSES.save(3L);
            Settings.LOCK_PIN_WAIT_UNTIL.save(1234L);
            String hash = Settings.LOCK_PIN.savedValue();
            try (FailingStore ignored = FailingStore.install(fault)) {
                assertFalse("reported a saved PIN after " + fault, PinLock.set("9517"));
            }
            assertEquals(hash, Settings.LOCK_PIN.savedValue());
            assertEquals(Long.valueOf(3), Settings.LOCK_PIN_MISSES.savedValue());
            assertEquals(Long.valueOf(1234), Settings.LOCK_PIN_WAIT_UNTIL.savedValue());
            assertTrue(PinLock.matches("4821"));
            assertFalse(PinLock.matches("9517"));
        }
        assertTrue("a normal retry works", PinLock.set("9517"));
        assertTrue(PinLock.matches("9517"));
        assertEquals(Long.valueOf(0), Settings.LOCK_PIN_MISSES.savedValue());
        assertEquals(Long.valueOf(0), Settings.LOCK_PIN_WAIT_UNTIL.savedValue());
    }

    @Test public void aFailedFormSaveStaysOpenAndNeverReportsSuccess() {
        Activity activity = MessagesLockTest.focused().get();
        List<Boolean> completed = new ArrayList<>();
        PinLock.promptNew(activity, completed::add);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ((EditText) dialog.getWindow().getDecorView().findViewWithTag(PinLock.FIELD_TAG)).setText("9517");
        ((EditText) dialog.getWindow().getDecorView().findViewWithTag(PinLock.CONFIRM_TAG)).setText("9517");
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.LOST)) {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
        }
        assertTrue(dialog.isShowing());
        assertTrue(completed.isEmpty());
        assertEquals("Couldn't save the PIN.",
                ((TextView) dialog.getWindow().getDecorView().findViewWithTag(PinLock.MESSAGE_TAG)).getText().toString());
        assertTrue(PinLock.matches("4821"));
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(List.of(true), completed);
        assertTrue(PinLock.matches("9517"));
        activity.finish();
    }

    @Test public void failedCounterWritesCannotRemoveTheCurrentProcessWait() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        PinLock.clock = now::get;
        FailingStore.Fault[] lost = new FailingStore.Fault[20];
        java.util.Arrays.fill(lost, FailingStore.Fault.LOST);
        try (FailingStore ignored = FailingStore.install(lost)) {
            for (int i = 0; i < PinLock.MAX_MISSES; i++) {
                assertEquals(PinLock.Result.WRONG, PinLock.attempt("9999"));
            }
            assertEquals(PinLock.WAIT_MS, PinLock.waitLeftMs());
            assertEquals(PinLock.Result.WAIT, PinLock.attempt("4821"));
        }
        now.addAndGet(PinLock.WAIT_MS);
        assertEquals("retry after storage recovers", PinLock.Result.RIGHT, PinLock.attempt("4821"));
    }

    @Test public void aDelayedFormRechecksPermissionAndCancelsWithoutChangingThePin() {
        Activity activity = MessagesLockTest.focused().get();
        List<Boolean> completed = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        PinLock.promptNew(activity, allowed::get, completed::add);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText first = dialog.getWindow().getDecorView().findViewWithTag(PinLock.FIELD_TAG);
        EditText again = dialog.getWindow().getDecorView().findViewWithTag(PinLock.CONFIRM_TAG);
        first.setText("9517");
        again.setText("9517");
        allowed.set(false);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals(List.of(false), completed);
        assertEquals("", first.getText().toString());
        assertEquals("", again.getText().toString());
        assertTrue(PinLock.matches("4821"));
        assertFalse(PinLock.matches("9517"));
        activity.finish();
    }

    private static FailingStore.Fault[] failures() {
        return new FailingStore.Fault[]{FailingStore.Fault.LOST, FailingStore.Fault.COMMIT_FALSE,
                FailingStore.Fault.COMMIT_THROWS, FailingStore.Fault.COMMIT_THROWS_AFTER_LANDING,
                FailingStore.Fault.EDIT_THROWS, FailingStore.Fault.STAGE_THROWS};
    }
}
