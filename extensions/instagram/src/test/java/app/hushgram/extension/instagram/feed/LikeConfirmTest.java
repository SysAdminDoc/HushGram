/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LikeConfirmTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Activity activity;
    private int likes;

    @Before public void start() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ShadowDialog.reset();
        HookStatus.clear();
    }

    @After public void restore() {
        Dialog left = LikeConfirm.open();
        if (left != null) left.dismiss();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Settings.ASK_BEFORE_LIKE.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A tap on the Like button over [screen]: the second run, after Continue, must go through. */
    private boolean like(Context screen) {
        return LikeConfirm.hold(() -> screen, () -> {
            likes++;
            assertFalse("the second run goes through", LikeConfirm.hold(() -> screen, () -> fail("asked twice")));
        });
    }

    static AlertDialog shown() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("a question is up", dialog);
        assertTrue(dialog.isShowing());
        return (AlertDialog) dialog;
    }

    static void tap(AlertDialog dialog, int button) {
        dialog.getButton(button).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void offTheLikeGoesThroughUnasked() {
        assertFalse(Settings.ASK_BEFORE_LIKE.get());
        assertFalse(like(activity));
        assertNull("nothing asked", ShadowDialog.getLatestDialog());
        assertEquals(0, likes);
    }

    @Test public void onTheLikeWaitsForContinue() {
        Settings.ASK_BEFORE_LIKE.save(true);
        assertTrue("held while the question is up", like(activity));
        assertEquals(0, likes);
        tap(shown(), AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue runs it once", 1, likes);
        assertFalse(ShadowDialog.getLatestDialog().isShowing());
        assertEquals(List.of(FamilyNames.ASK_BEFORE_LIKE + ": invoked 2, 0 found, 0 missing. Counted: " + LikeConfirm.ASKED + " 1"),
                HookStatus.report());
    }

    /**
     * A second tap before the question goes away, a quick double tap on Like, is held without a
     * second question, so Continue likes once. Once it's gone, the question is let go.
     */
    @Test public void aSecondTapWhileTheQuestionIsUpIsHeldWithoutASecondQuestion() {
        Settings.ASK_BEFORE_LIKE.save(true);
        assertTrue(like(activity));
        AlertDialog question = shown();
        assertTrue("the second tap is held", like(activity));
        assertSame("no second question", question, ShadowDialog.getLatestDialog());
        assertEquals(1, ShadowDialog.getShownDialogs().size());
        tap(question, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue likes once", 1, likes);
        assertNull("the question is let go", LikeConfirm.open());

        assertTrue("the next tap asks again", like(activity));
        tap(shown(), AlertDialog.BUTTON_NEGATIVE);
        assertNull(LikeConfirm.open());
        assertEquals(List.of(FamilyNames.ASK_BEFORE_LIKE + ": invoked 4, 0 found, 0 missing. Counted: " + LikeConfirm.ASKED + " 2"),
                HookStatus.report());
    }

    /** A question whose activity went without dismissing it doesn't hold the next tap on another screen. */
    @Test public void aQuestionWhoseScreenWentDoesntHoldTheNextTap() {
        Settings.ASK_BEFORE_LIKE.save(true);
        ActivityController<Activity> gone = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(like(gone.get()));
        Dialog left = shown();
        gone.pause().stop().destroy();
        assertTrue("it still says it's showing", left.isShowing());
        assertTrue(like(activity));
        assertNotSame("a new question on the new screen", left, ShadowDialog.getLatestDialog());
        tap(shown(), AlertDialog.BUTTON_POSITIVE);
        assertEquals(1, likes);
    }

    /**
     * A question left up behind a screen opened over its own, as a notification opens one, doesn't
     * hold a tap on the new screen, where nothing would be there to answer. It's taken down and the
     * tap gets its own question there.
     */
    @Test public void aQuestionBehindAnotherScreenDoesntHoldATapThere() {
        Settings.ASK_BEFORE_LIKE.save(true);
        ActivityController<Activity> behind = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(like(behind.get()));
        Dialog left = shown();
        behind.pause().stop();
        assertFalse("its activity is stopped, not finishing", behind.get().isFinishing());

        ActivityController<Activity> over = Robolectric.buildActivity(Activity.class).setup();
        assertTrue(like(over.get()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("the one behind is taken down", left.isShowing());
        AlertDialog asked = shown();
        assertNotSame("a question on the screen tapped", left, asked);
        assertSame(over.get(), ConfirmDialog.activityOf(asked.getContext()));
        assertSame(asked, LikeConfirm.open());

        tap(asked, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Continue likes once", 1, likes);
        assertNull(LikeConfirm.open());
    }

    /** A second tap on the same screen while its question is up is still held. */
    @Test public void aTapOnTheSameScreenIsStillHeld() {
        Settings.ASK_BEFORE_LIKE.save(true);
        assertTrue(like(activity));
        AlertDialog question = shown();
        assertTrue(like(new android.view.ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault)));
        assertSame("a context wrapping the same activity is the same screen", question, ShadowDialog.getLatestDialog());
        assertTrue(question.isShowing());
        tap(question, AlertDialog.BUTTON_NEGATIVE);
        assertEquals(0, likes);
    }

    @Test public void theQuestionNeedsAnActivityBehindTheScreensContext() {
        Settings.ASK_BEFORE_LIKE.save(true);
        assertFalse("nothing to show it over", like(new ContextWrapper(RuntimeEnvironment.getApplication())));
        assertNull(ShadowDialog.getLatestDialog());
        assertTrue("a wrapped activity is found", like(new ContextWrapper(activity)));
        assertNotNull(shown());
    }

    @Test public void cancelLeavesThePostAsItWas() {
        Settings.ASK_BEFORE_LIKE.save(true);
        assertTrue(like(activity));
        tap(shown(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(0, likes);

        assertTrue(like(activity));
        shown().cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("backing out of it doesn't like either", 0, likes);
    }

    /** Paused, unready, with no screen to ask on or throwing, the like goes through as Instagram's. */
    @Test public void pausedUnreadyScreenlessAndThrowingLetTheLikeThrough() {
        Settings.ASK_BEFORE_LIKE.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(like(activity));
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(like(activity)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(like(activity)));
        assertFalse("no screen", like(null));
        assertFalse("no activity behind it", like(RuntimeEnvironment.getApplication()));
        activity.finish();
        assertFalse("an activity going away", like(activity));
        assertFalse("unpatched, the stub has none", LikeConfirm.hold(new Object(), null, null, null, null, 0));
        assertFalse(LikeConfirm.hold(() -> { throw new IllegalStateException("no screen"); }, () -> likes++));
        assertNull("nothing asked", ShadowDialog.getLatestDialog());
        assertEquals(0, likes);
        String missing = HookStatus.missing(FamilyNames.ASK_BEFORE_LIKE).toString();
        assertTrue(missing, missing.contains("'" + LikeConfirm.ASK + "'"));
    }
}
