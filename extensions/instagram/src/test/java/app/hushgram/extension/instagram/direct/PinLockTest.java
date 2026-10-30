/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The HushGram PIN: how it's set and kept, and that every lock path asks it when it's chosen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class PinLockTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int FRAME = 0x7f0b0001;
    private static final int LIST = 0x7f0b0002;
    private static final String ALICE = "340282366841710300949128111";
    private static final String PIN = "4821";

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @Before
    public void enable() {
        MessagesLock.resetForTests();
        Map<String, Integer> ids = new HashMap<>();
        ids.put(MessagesLock.INBOX_FRAME, FRAME);
        ids.put(MessagesLock.INBOX_LIST, LIST);
        MessagesLock.idsForTests = ids;
        PinLock.clock = now::get;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.LOCK_MESSAGES.save(true);
        Settings.UNLOCK_WITH.save(UnlockWith.PIN);
        assertTrue(PinLock.set(PIN));
        HookStatus.clear();
    }

    @After
    public void restore() {
        MessagesLock.resetForTests();
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCK_APP.resetToDefault();
        Settings.LOCKED_CHATS.resetToDefault();
        Settings.UNLOCK_WITH.resetToDefault();
        PinLock.clear();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    // ---------------------------------------------------------------- setting and storing it

    @Test
    public void aPinIsFourToEightDigitsAndNothingElse() {
        PinLock.clear();
        assertFalse(PinLock.isSet());
        for (String bad : new String[]{"", "1", "123", "123456789", "12a4", "12 34", "١٢٣٤", "-1234"}) {
            assertFalse("accepted " + bad, PinLock.set(bad));
            assertFalse(PinLock.isSet());
        }
        assertTrue(PinLock.set("1234"));
        assertTrue(PinLock.set("12345678"));
        assertTrue(PinLock.matches("12345678"));
        assertFalse(PinLock.matches("1234"));
    }

    @Test
    public void thePinIsKeptAsASaltedHashAndNotTheDigits() {
        assertTrue(PinLock.set("90210"));
        String first = Settings.LOCK_PIN.get();
        String[] parts = first.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("pbkdf2-sha256", parts[0]);
        assertEquals(String.valueOf(PinLock.ITERATIONS), parts[1]);
        assertTrue("salt too short", parts[2].length() >= 32);
        assertEquals("a 256-bit hash", 64, parts[3].length());
        assertFalse(first.contains("90210"));
        assertFalse(parts[3].contains("90210"));

        // The same digits again get a different salt and so a different hash.
        assertTrue(PinLock.set("90210"));
        String[] second = Settings.LOCK_PIN.get().split("\\$");
        assertNotEquals(parts[2], second[2]);
        assertNotEquals(parts[3], second[3]);
        assertTrue(PinLock.matches("90210"));
        assertFalse(PinLock.matches("90211"));
    }

    @Test
    public void settingAPinAsksTwiceAndSavesOnlyWhenTheyMatch() {
        Activity activity = MessagesLockTest.focused().get();
        List<Boolean> results = new ArrayList<>();

        PinLock.promptNew(activity, results::add);
        AlertDialog dialog = latest();
        type(dialog, PinLock.FIELD_TAG, "55556");
        type(dialog, PinLock.CONFIRM_TAG, "55557");
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals("The PINs don't match.", message(dialog));
        assertTrue("a mismatch closed the dialog", dialog.isShowing());
        assertTrue(results.isEmpty());
        assertFalse(PinLock.matches("55556"));
        assertTrue("the old PIN stays", PinLock.matches(PIN));
        assertTrue("a mismatch returns to confirmation", field(dialog, PinLock.CONFIRM_TAG).hasFocus());

        type(dialog, PinLock.FIELD_TAG, "123");
        type(dialog, PinLock.CONFIRM_TAG, "123");
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals("Use 4 to 8 digits.", message(dialog));
        assertTrue("an invalid new PIN returns to the first field", field(dialog, PinLock.FIELD_TAG).hasFocus());
        assertTrue(PinLock.matches(PIN));

        type(dialog, PinLock.FIELD_TAG, "55556");
        type(dialog, PinLock.CONFIRM_TAG, "55556");
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertFalse(dialog.isShowing());
        assertEquals(List.of(true), results);
        assertTrue(PinLock.matches("55556"));
        assertFalse(PinLock.matches(PIN));
    }

    @Test
    public void cancellingTheNewPinChangesNothing() {
        Activity activity = MessagesLockTest.focused().get();
        List<Boolean> results = new ArrayList<>();
        PinLock.promptNew(activity, results::add);
        AlertDialog dialog = latest();
        type(dialog, PinLock.FIELD_TAG, "777777");
        type(dialog, PinLock.CONFIRM_TAG, "777777");
        click(dialog, DialogInterface.BUTTON_NEGATIVE);
        assertEquals(List.of(false), results);
        assertTrue(PinLock.matches(PIN));
        assertFalse(PinLock.matches("777777"));
        assertEquals("", field(dialog, PinLock.FIELD_TAG).getText().toString());
        assertEquals("", field(dialog, PinLock.CONFIRM_TAG).getText().toString());
    }

    @Test
    public void settingAPinHasPersistentLabelsAndNextThenDoneActions() {
        Activity activity = MessagesLockTest.focused().get();
        List<Boolean> results = new ArrayList<>();
        PinLock.promptNew(activity, results::add);
        AlertDialog dialog = latest();
        EditText first = field(dialog, PinLock.FIELD_TAG);
        EditText again = field(dialog, PinLock.CONFIRM_TAG);
        ScrollView scroll = dialog.getWindow().getDecorView().findViewWithTag(PinLock.SCROLL_TAG);
        assertNotNull("the form can scroll above the keyboard", scroll);
        LinearLayout form = (LinearLayout) scroll.getChildAt(0);
        TextView newLabel = (TextView) form.getChildAt(0);
        TextView confirmLabel = (TextView) form.getChildAt(2);
        assertEquals("New PIN", newLabel.getText().toString());
        assertEquals(first.getId(), newLabel.getLabelFor());
        assertEquals("Confirm PIN", confirmLabel.getText().toString());
        assertEquals(again.getId(), confirmLabel.getLabelFor());
        assertTrue(first.hasFocus());
        assertEquals(again.getId(), first.getNextFocusForwardId());
        assertEquals(EditorInfo.IME_ACTION_NEXT, first.getImeOptions() & EditorInfo.IME_MASK_ACTION);
        assertEquals(EditorInfo.IME_ACTION_DONE, again.getImeOptions() & EditorInfo.IME_MASK_ACTION);
        assertTrue((first.getImeOptions() & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
        assertTrue((again.getImeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
        assertFalse("PIN text must not enter saved view state", first.isSaveEnabled());
        assertFalse(again.isSaveEnabled());
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                dialog.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);

        type(dialog, PinLock.FIELD_TAG, "5678");
        first.onEditorAction(EditorInfo.IME_ACTION_NEXT);
        assertTrue(again.hasFocus());
        assertEquals("the label stays after typing", "New PIN", newLabel.getText().toString());
        type(dialog, PinLock.CONFIRM_TAG, "5678");
        again.onEditorAction(EditorInfo.IME_ACTION_DONE);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of(true), results);
        assertTrue(PinLock.matches("5678"));
        assertEquals("", first.getText().toString());
        assertEquals("", again.getText().toString());
    }

    @Test
    public void aShortViewportScrollsToTheConfirmationField() {
        Activity activity = MessagesLockTest.focused().get();
        PinLock.promptNew(activity, saved -> { });
        AlertDialog dialog = latest();
        ScrollView scroll = dialog.getWindow().getDecorView().findViewWithTag(PinLock.SCROLL_TAG);
        int width = Math.round(320 * activity.getResources().getDisplayMetrics().density);
        int height = Math.round(80 * activity.getResources().getDisplayMetrics().density);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, width, height);
        assertTrue("the form keeps its full content height", scroll.getChildAt(0).getHeight() > scroll.getHeight());
        scroll.setSmoothScrollingEnabled(false);
        scroll.fullScroll(View.FOCUS_DOWN);
        assertTrue("confirmation can be reached when the IME leaves little height", scroll.getScrollY() > 0);
        dialog.dismiss();
    }

    @Test
    public void anUnexpectedSetupDismissalReportsCancelOnceAndForgetsTheDigits() {
        Activity activity = MessagesLockTest.focused().get();
        List<Boolean> results = new ArrayList<>();
        PinLock.promptNew(activity, results::add);
        AlertDialog dialog = latest();
        type(dialog, PinLock.FIELD_TAG, "7777");
        type(dialog, PinLock.CONFIRM_TAG, "7777");
        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        assertEquals(List.of(false), results);
        assertEquals("", field(dialog, PinLock.FIELD_TAG).getText().toString());
        assertEquals("", field(dialog, PinLock.CONFIRM_TAG).getText().toString());
        assertTrue("a dismiss leaves the existing PIN", PinLock.matches(PIN));
        dialog.cancel();
        ShadowLooper.idleMainLooper();
        assertEquals("cancel after dismissal can't report twice", List.of(false), results);
    }

    @Test
    public void anIncompleteUnlockDoesNotSpendATryAndDoneCanUnlock() {
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};
        PinLock.ask(activity, () -> opened[0] = true, () -> { });
        AlertDialog dialog = latest();
        EditText field = field(dialog, PinLock.FIELD_TAG);
        type(dialog, PinLock.FIELD_TAG, "123");
        field.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals("Use 4 to 8 digits.", message(dialog));
        assertEquals(0L, (long) Settings.LOCK_PIN_MISSES.get());
        assertEquals("123", field.getText().toString());
        assertFalse(opened[0]);
        type(dialog, PinLock.FIELD_TAG, PIN);
        assertEquals("typing a correction removes the old error", "", message(dialog));
        field.onEditorAction(EditorInfo.IME_ACTION_DONE);
        ShadowLooper.idleMainLooper();
        assertTrue(opened[0]);
        assertFalse(dialog.isShowing());
    }

    @Test
    public void theDialogIsMaskedNumericNoAutofillAndSecure() {
        Activity activity = MessagesLockTest.focused().get();
        MessagesLock.confirmHiddenThen(activity, () -> { });
        AlertDialog dialog = latest();
        EditText field = field(dialog, PinLock.FIELD_TAG);

        int type = field.getInputType();
        assertEquals(InputType.TYPE_CLASS_NUMBER, type & InputType.TYPE_MASK_CLASS);
        assertEquals(InputType.TYPE_NUMBER_VARIATION_PASSWORD, type & InputType.TYPE_MASK_VARIATION);
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.getImportantForAutofill());
        assertTrue("window not secure",
                (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        field.setText("123456789");
        assertEquals("at most 8 digits", "12345678", field.getText().toString());

        MessagesLock.confirmHiddenThen(activity, () -> { });
        dialog.dismiss();
    }

    // ---------------------------------------------------------------- every path asks the PIN

    @Test
    public void theRightPinOpensTheInboxAndTheCoverComesOff() {
        Activity activity = inbox();
        MessagesLock.check(activity);
        View cover = cover(activity);
        assertNotNull("no cover over the inbox", cover);
        assertEquals(View.VISIBLE, cover.getVisibility());
        AlertDialog dialog = latest();
        assertEquals("Enter your PIN", title(dialog));

        type(dialog, PinLock.FIELD_TAG, PIN);
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        MessagesLock.check(activity);

        assertFalse(MessagesLock.locked());
        assertEquals(View.GONE, cover.getVisibility());
        assertFalse(dialog.isShowing());
    }

    @Test
    public void aWrongPinKeepsTheInboxLockedWithAShortMessage() {
        Activity activity = inbox();
        MessagesLock.check(activity);
        AlertDialog dialog = latest();

        type(dialog, PinLock.FIELD_TAG, "0000");
        click(dialog, DialogInterface.BUTTON_POSITIVE);

        assertEquals("Wrong PIN. Try again.", message(dialog));
        assertTrue(dialog.isShowing());
        assertTrue(MessagesLock.locked());
        assertEquals("the field is cleared", "", field(dialog, PinLock.FIELD_TAG).getText().toString());
        assertEquals(View.VISIBLE, cover(activity).getVisibility());
    }

    @Test
    public void cancellingTheDialogLeavesTheCoverUpAndAsksAgainOnUnlock() {
        Activity activity = inbox();
        MessagesLock.check(activity);
        AlertDialog dialog = latest();
        click(dialog, DialogInterface.BUTTON_NEGATIVE);
        assertTrue(MessagesLock.locked());
        assertEquals(View.VISIBLE, cover(activity).getVisibility());

        // The cover's Unlock link asks again, since a cancel clears the ask.
        MessagesLock.confirmChatsThen(activity, () -> { });
        assertTrue(latest().isShowing());
    }

    @Test
    public void aDialogClosedByAnythingElseEndsTheAskToo() {
        Activity activity = inbox();
        MessagesLock.check(activity);
        AlertDialog dialog = latest();

        dialog.dismiss();
        // Android hands a dialog's dismiss listener a message of its own, so it runs on the next
        // turn of the main loop, well before anyone could tap Unlock.
        ShadowLooper.idleMainLooper();

        assertTrue(MessagesLock.locked());
        assertEquals(View.VISIBLE, cover(activity).getVisibility());
        MessagesLock.confirmChatsThen(activity, () -> { });
        assertNotSame("Unlock didn't ask again", dialog, latest());
        assertTrue(latest().isShowing());
    }

    @Test
    public void aLockedChatOpensOnlyWithThePin() {
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCKED_CHATS.save(ALICE + "\tAlice");
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};

        MessagesLock.confirmChatsThen(activity, () -> opened[0] = true);
        AlertDialog dialog = latest();
        assertFalse(opened[0]);

        type(dialog, PinLock.FIELD_TAG, "1111");
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertFalse("a wrong PIN opened the chat", opened[0]);

        type(dialog, PinLock.FIELD_TAG, PIN);
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue(opened[0]);
        assertFalse(MessagesLock.chatListLocked());
    }

    @Test
    public void theHiddenChatsListOpensOnlyWithThePin() {
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};

        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        AlertDialog dialog = latest();
        assertFalse(opened[0]);

        type(dialog, PinLock.FIELD_TAG, "2222");
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertFalse(opened[0]);

        type(dialog, PinLock.FIELD_TAG, PIN);
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue(opened[0]);
    }

    @Test
    public void lockAllOfInstagramAndTheSwitchesAskThePinToo() {
        Settings.LOCK_MESSAGES.resetToDefault();
        Settings.LOCK_APP.save(true);
        Activity activity = MessagesLockTest.focused().get();
        boolean[] done = {false};

        // Turning a lock off while locked goes through confirmThen, the same ask.
        MessagesLock.confirmThen(activity, () -> done[0] = true);
        AlertDialog dialog = latest();
        type(dialog, PinLock.FIELD_TAG, PIN);
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue(done[0]);
        assertFalse(MessagesLock.locked());
    }

    // ---------------------------------------------------------------- five misses

    @Test
    public void fiveMissesWaitThirtySecondsAndTheCountSurvivesARestart() {
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};
        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        AlertDialog dialog = latest();

        for (int i = 1; i <= 4; i++) {
            type(dialog, PinLock.FIELD_TAG, "000" + i);
            click(dialog, DialogInterface.BUTTON_POSITIVE);
            assertEquals("Wrong PIN. Try again.", message(dialog));
        }
        type(dialog, PinLock.FIELD_TAG, "0005");
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals("Too many tries. Wait 30 seconds.", message(dialog));
        assertFalse("typing waits with the countdown", field(dialog, PinLock.FIELD_TAG).isEnabled());
        assertFalse(dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled());

        // The right PIN doesn't count during the wait, and the wait doesn't grow.
        now.addAndGet(10_000);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        type(dialog, PinLock.FIELD_TAG, PIN);
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertFalse("the wait was skipped", opened[0]);
        assertEquals("Too many tries. Wait 20 seconds.", message(dialog));

        // A restart forgets the process's state, not the count or the wait.
        MessagesLock.resetForTests();
        PinLock.clock = now::get;
        assertEquals(5L, (long) Settings.LOCK_PIN_MISSES.get());
        assertTrue(PinLock.waitLeftMs() > 0);
        assertEquals(PinLock.Result.WAIT, PinLock.attempt(PIN));

        now.addAndGet(20_000);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        assertEquals(0, PinLock.waitLeftMs());
        assertEquals("the countdown clears without another tap", "", message(dialog));
        assertTrue(field(dialog, PinLock.FIELD_TAG).isEnabled());
        assertTrue(dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled());
        type(dialog, PinLock.FIELD_TAG, PIN);
        click(dialog, DialogInterface.BUTTON_POSITIVE);
        assertTrue(opened[0]);
        assertEquals(0L, (long) Settings.LOCK_PIN_MISSES.get());
    }

    @Test
    public void dismissingAWaitingDialogStopsItsCountdownAndForgetsTheDigits() {
        for (int i = 0; i < 5; i++) PinLock.attempt("9999");
        Activity activity = MessagesLockTest.focused().get();
        List<Boolean> results = new ArrayList<>();
        PinLock.ask(activity, () -> results.add(true), () -> results.add(false));
        AlertDialog dialog = latest();
        type(dialog, PinLock.FIELD_TAG, PIN);
        String waiting = message(dialog);
        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        now.addAndGet(PinLock.WAIT_MS);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS);
        assertEquals(List.of(false), results);
        assertEquals("", field(dialog, PinLock.FIELD_TAG).getText().toString());
        assertEquals("the dismissed dialog has no active countdown", waiting, message(dialog));
    }

    @Test
    public void aMissAfterTheWaitStartsAnotherWait() {
        for (int i = 0; i < 5; i++) assertEquals(PinLock.Result.WRONG, PinLock.attempt("9999"));
        assertEquals(30_000, PinLock.waitLeftMs());
        now.addAndGet(31_000);
        assertEquals(PinLock.Result.WRONG, PinLock.attempt("9999"));
        assertEquals(30_000, PinLock.waitLeftMs());
    }

    @Test
    public void aClockSetBackCannotStretchTheWait() {
        for (int i = 0; i < 5; i++) PinLock.attempt("9999");
        now.addAndGet(-3_600_000);
        assertEquals(30_000, PinLock.waitLeftMs());
    }

    @Test
    public void fewerThanFiveMissesNeverWaitAndARightPinResetsTheCount() {
        for (int i = 0; i < 4; i++) assertEquals(PinLock.Result.WRONG, PinLock.attempt("9999"));
        assertEquals(0, PinLock.waitLeftMs());
        assertEquals(PinLock.Result.RIGHT, PinLock.attempt(PIN));
        assertEquals(0L, (long) Settings.LOCK_PIN_MISSES.get());
        for (int i = 0; i < 4; i++) assertEquals(PinLock.Result.WRONG, PinLock.attempt("9999"));
        assertEquals(0, PinLock.waitLeftMs());
    }

    // ---------------------------------------------------------------- Forgot PIN

    @Test
    public void forgotPinOnlyExplainsAndResetsNothing() {
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};
        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        AlertDialog pinDialog = latest();
        String before = Settings.LOCK_PIN.get();

        click(pinDialog, DialogInterface.BUTTON_NEUTRAL);

        AlertDialog info = latest();
        assertNotSame(pinDialog, info);
        String text = ((TextView) info.findViewById(android.R.id.message)).getText().toString();
        assertTrue(text, text.contains("can't be recovered"));
        assertTrue(text, text.contains("clear Instagram's storage"));
        assertTrue(text, text.contains("resets HushGram's settings"));
        assertFalse("nothing asks the phone's lock or opens", opened[0]);
        assertTrue("the PIN dialog stays under it", pinDialog.isShowing());
        assertEquals(before, Settings.LOCK_PIN.get());
        assertTrue(PinLock.matches(PIN));
        assertTrue(MessagesLock.locked());
        assertNull("no new-PIN dialog", findDialogTitled("Set a PIN"));
        assertNull("nothing was launched", Shadows.shadowOf(activity).getNextStartedActivity());

        // Cancel goes back to the PIN dialog and changes nothing.
        click(info, DialogInterface.BUTTON_NEGATIVE);
        assertFalse(info.isShowing());
        assertTrue(pinDialog.isShowing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertEquals(before, Settings.LOCK_PIN.get());
        assertFalse(opened[0]);
    }

    @Test
    public void forgotPinOffersAppInfoForInstagramAndNothingElse() {
        Activity activity = MessagesLockTest.focused().get();
        MessagesLock.confirmHiddenThen(activity, () -> { });
        click(latest(), DialogInterface.BUTTON_NEUTRAL);
        AlertDialog info = latest();

        click(info, DialogInterface.BUTTON_POSITIVE);

        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.getAction());
        assertEquals("package:" + activity.getPackageName(), started.getDataString());
        assertTrue(PinLock.matches(PIN));
        assertTrue(MessagesLock.locked());
    }

    // ---------------------------------------------------------------- the phone's lock, unchanged

    @Test
    public void phoneLockChosenStillUsesThePhoneLockEvenWithAPinSet() {
        Settings.UNLOCK_WITH.save(UnlockWith.PHONE_LOCK);
        assertTrue(PinLock.isSet());
        assertFalse(PinLock.active());
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};

        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);

        assertNull("a PIN dialog showed", ShadowAlertDialog.getLatestAlertDialog());
        // The test phone has no screen lock, which the phone's path answers with a toast and an open.
        assertTrue(opened[0]);
        assertEquals("Set a screen lock on your phone so HushGram can lock your messages.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void pinChosenWithNoPinSetFallsBackToThePhoneLock() {
        PinLock.clear();
        assertFalse(PinLock.active());
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};
        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(opened[0]);
    }

    @Test
    public void offOpensAtOnceWithNoDialog() {
        Settings.LOCK_MESSAGES.resetToDefault();
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};
        MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        assertTrue(opened[0]);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertFalse(MessagesLock.locked());
    }

    @Test
    public void unreadySettingsLeaveInstagramAloneAndAskNothing() {
        Activity activity = MessagesLockTest.focused().get();
        boolean[] opened = {false};
        SettingsContextRule.withoutContext(() -> {
            assertFalse(PinLock.active());
            MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
        });
        assertTrue(opened[0]);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void pausedKeepsTheLockAndAsksThePin() {
        for (HushgramPause.Reason reason : new HushgramPause.Reason[]{
                HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP, HushgramPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            try {
                assertTrue(PinLock.active());
                Activity activity = MessagesLockTest.focused().get();
                boolean[] opened = {false};
                MessagesLock.confirmHiddenThen(activity, () -> opened[0] = true);
                AlertDialog dialog = latest();
                assertFalse(opened[0]);
                type(dialog, PinLock.FIELD_TAG, PIN);
                click(dialog, DialogInterface.BUTTON_POSITIVE);
                assertTrue(reason.name(), opened[0]);
            } finally {
                PauseForTests.resume();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static AlertDialog latest() {
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("no dialog is up", dialog);
        assertTrue("the latest dialog is gone", dialog.isShowing());
        return dialog;
    }

    private static EditText field(AlertDialog dialog, String tag) {
        View found = dialog.getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull("no field " + tag, found);
        return (EditText) found;
    }

    private static void type(AlertDialog dialog, String tag, String digits) {
        field(dialog, tag).setText(digits);
    }

    private static void click(AlertDialog dialog, int which) {
        dialog.getButton(which).performClick();
        ShadowLooper.idleMainLooper();
    }

    /** A dialog showing now whose title is [title], if any. */
    private static AlertDialog findDialogTitled(String title) {
        for (android.app.Dialog dialog : org.robolectric.shadows.ShadowDialog.getShownDialogs()) {
            if (dialog instanceof AlertDialog && dialog.isShowing() && title(((AlertDialog) dialog)).equals(title)) {
                return (AlertDialog) dialog;
            }
        }
        return null;
    }

    private static String message(AlertDialog dialog) {
        View found = dialog.getWindow().getDecorView().findViewWithTag(PinLock.MESSAGE_TAG);
        assertNotNull(found);
        return ((TextView) found).getText().toString();
    }

    private static String title(AlertDialog dialog) {
        TextView title = dialog.getWindow().getDecorView().findViewById(android.R.id.title) instanceof TextView
                ? (TextView) dialog.getWindow().getDecorView().findViewById(android.R.id.title)
                : null;
        if (title != null && title.getText().length() > 0) return title.getText().toString();
        return org.robolectric.Shadows.shadowOf(dialog).getTitle().toString();
    }

    private static Activity inbox() {
        Activity activity = MessagesLockTest.focused().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout frame = new FrameLayout(activity);
        frame.setId(FRAME);
        View list = new View(activity);
        list.setId(LIST);
        frame.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600));
        root.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 800));
        activity.setContentView(root);
        ShadowLooper.idleMainLooper();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
        return activity;
    }

    private static View cover(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        for (int i = 0; i < decor.getChildCount(); i++) {
            View child = decor.getChildAt(i);
            if (MessagesLock.COVER_TAG.equals(child.getTag())) return child;
        }
        return null;
    }
}
