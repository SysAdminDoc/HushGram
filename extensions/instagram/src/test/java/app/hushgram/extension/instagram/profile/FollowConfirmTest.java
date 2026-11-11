/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Ask before a follow (#631): with the switch on, a Follow or Follow back tap on someone you don't
 * follow waits for "Follow @name?", the dialog's Follow runs Instagram's own tap once, and Cancel
 * leaves everything as it was. Off, or on someone you already follow, the tap goes through unasked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class FollowConfirmTest {
    /** The follow status as Instagram's enum has it: the constant's name and the value toString answers. */
    enum Status {
        FOLLOWSTATUSFOLLOWING("FollowStatusFollowing"),
        FOLLOWSTATUSNOTFOLLOWING("FollowStatusNotFollowing"),
        FOLLOWSTATUSREQUESTED("FollowStatusRequested");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        @Override public String toString() {
            return value;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Activity activity;
    private int follows;

    @Before public void start() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ShadowDialog.reset();
        HookStatus.clear();
        follows = 0;
    }

    @After public void restore() {
        Dialog left = FollowConfirm.open();
        if (left != null) left.dismiss();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Settings.ASK_BEFORE_FOLLOW.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    /**
     * A tap on a Follow button with [status], as the patched tap action makes it: the second run,
     * after the dialog's Follow, has to go through to Instagram.
     */
    private boolean tapFollow(Context screen, AtomicReference<Object> status, String username) {
        return FollowConfirm.hold(() -> screen, status::get, () -> username, () -> {
            follows++;
            assertFalse("the second run goes through", FollowConfirm.hold(() -> screen, status::get, () -> username,
                    () -> fail("asked twice")));
        });
    }

    private static AlertDialog shown() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("a question is up", dialog);
        assertTrue(dialog.isShowing());
        return (AlertDialog) dialog;
    }

    private static void tap(AlertDialog dialog, int button) {
        dialog.getButton(button).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void offTheFollowGoesThroughUnasked() {
        assertFalse(Settings.ASK_BEFORE_FOLLOW.get());
        assertFalse(tapFollow(activity, new AtomicReference<>(Status.FOLLOWSTATUSNOTFOLLOWING), "someone"));
        assertNull("nothing asked", ShadowDialog.getLatestDialog());
        assertEquals(0, follows);
    }

    @Test public void onTheFollowWaitsForFollowAndRunsInstagramsTapOnce() {
        Settings.ASK_BEFORE_FOLLOW.save(true);
        AtomicReference<Object> status = new AtomicReference<>(Status.FOLLOWSTATUSNOTFOLLOWING);
        assertTrue("held while the question is up", tapFollow(activity, status, "someone"));
        assertEquals(0, follows);
        AlertDialog question = shown();
        assertTrue(Shadows.shadowOf(question).getTitle().toString().contains("Follow @"));
        assertTrue(Shadows.shadowOf(question).getTitle().toString().contains("someone"));
        assertEquals("Follow", question.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());

        assertTrue("a second tap is held under the same question", tapFollow(activity, status, "someone"));
        assertEquals(1, ShadowDialog.getShownDialogs().size());

        tap(question, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Follow runs Instagram's tap once", 1, follows);
        assertFalse(question.isShowing());
        assertNull(FollowConfirm.open());
        assertEquals(List.of(FamilyNames.ASK_BEFORE_FOLLOW + ": invoked 3, 0 found, 0 missing. Counted: " + FollowConfirm.ASKED + " 1"),
                HookStatus.report());
    }

    @Test public void cancelLeavesTheButtonAsItWas() {
        Settings.ASK_BEFORE_FOLLOW.save(true);
        assertTrue(tapFollow(activity, new AtomicReference<>(Status.FOLLOWSTATUSNOTFOLLOWING), "someone"));
        AlertDialog question = shown();
        tap(question, AlertDialog.BUTTON_NEGATIVE);
        assertFalse(question.isShowing());
        assertEquals("Cancel never follows", 0, follows);
        assertNull(FollowConfirm.open());
    }

    /** Following, Requested and an unread status go through as Instagram has them: the tap there unfollows or cancels. */
    @Test public void aTapThatDoesntFollowIsntAsked() {
        Settings.ASK_BEFORE_FOLLOW.save(true);
        assertFalse(tapFollow(activity, new AtomicReference<>(Status.FOLLOWSTATUSFOLLOWING), "someone"));
        assertFalse(tapFollow(activity, new AtomicReference<>(Status.FOLLOWSTATUSREQUESTED), "someone"));
        assertFalse(tapFollow(activity, new AtomicReference<>(null), "someone"));
        assertNull("nothing asked", ShadowDialog.getLatestDialog());
        assertEquals("Instagram runs its own tap, the hook never does", 0, follows);
    }

    /** If the account follows back or the status changes while the question is up, Follow doesn't send anything. */
    @Test public void followAfterTheStatusChangedDoesNothing() {
        Settings.ASK_BEFORE_FOLLOW.save(true);
        AtomicReference<Object> status = new AtomicReference<>(Status.FOLLOWSTATUSNOTFOLLOWING);
        assertTrue(tapFollow(activity, status, "someone"));
        status.set(Status.FOLLOWSTATUSFOLLOWING);
        tap(shown(), AlertDialog.BUTTON_POSITIVE);
        assertEquals(0, follows);
    }

    /** No name to show asks a plain question; no screen to ask on lets the tap through. */
    @Test public void noNameAsksPlainlyAndNoScreenLetsItThrough() {
        Settings.ASK_BEFORE_FOLLOW.save(true);
        AtomicReference<Object> status = new AtomicReference<>(Status.FOLLOWSTATUSNOTFOLLOWING);
        assertTrue(tapFollow(activity, status, null));
        AlertDialog question = shown();
        assertEquals("Follow this account?", Shadows.shadowOf(question).getTitle().toString());
        tap(question, AlertDialog.BUTTON_NEGATIVE);

        ShadowDialog.reset();
        assertFalse("no screen", tapFollow(null, status, "someone"));
        assertNull(ShadowDialog.getLatestDialog());
        assertEquals(0, follows);
    }
}
