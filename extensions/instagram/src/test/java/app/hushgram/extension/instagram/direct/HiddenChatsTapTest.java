/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Double-tap Messages for hidden chats (#358): which taps pair, which reach Instagram, and when the list opens. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HiddenChatsTapTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ALICE = "340282366841710300949128";

    private Activity activity;
    private final List<Activity> opened = new ArrayList<>();
    private boolean onButton = true;

    @Before public void prepare() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        MessagesLock.resetForTests();
        ChatAccounts.signInForTests(MessagesLockTest.ME);
        HiddenChatsTap.resetForTests();
        HiddenChatsTap.buttons = (shown, x, y) -> onButton;
        HiddenChatsTap.opener = opened::add;
        Settings.DOUBLE_TAP_HIDDEN_CHATS.save(true);
    }

    @After public void close() {
        HiddenChatsTap.resetForTests();
        MessagesLock.resetForTests();
        Settings.HIDDEN_CHATS.resetToDefault();
        Settings.DOUBLE_TAP_HIDDEN_CHATS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private boolean at(int action, float x, float y, long time) {
        return HiddenChatsTap.decide(activity, action, x, y, time);
    }

    /** A plain tap at (x, y) from [down] to [up], answering whether any of its events was kept from Instagram. */
    private boolean tap(float x, float y, long down, long up) {
        boolean kept = at(MotionEvent.ACTION_DOWN, x, y, down);
        kept |= at(MotionEvent.ACTION_UP, x, y, up);
        return kept;
    }

    @Test public void oneTapOnMessagesGoesToInstagram() {
        assertFalse(tap(100, 1500, 0, 50));
        assertTrue(opened.isEmpty());
    }

    @Test public void aQuickSecondTapIsKeptFromInstagramAndOpensTheList() {
        assertFalse(tap(100, 1500, 0, 50));
        assertTrue("the second finger down", at(MotionEvent.ACTION_DOWN, 104, 1498, 150));
        assertTrue("a wobble while it's down", at(MotionEvent.ACTION_MOVE, 106, 1497, 170));
        assertTrue(opened.isEmpty());
        assertTrue("the second finger up", at(MotionEvent.ACTION_UP, 106, 1497, 200));
        assertEquals(1, opened.size());
        assertSame(activity, opened.get(0));
        assertFalse("a third tap starts over", tap(100, 1500, 260, 300));
        assertEquals(1, opened.size());
    }

    @Test public void aSlowSecondTapGoesToInstagram() {
        long late = 50 + ViewConfiguration.getDoubleTapTimeout() + 1;
        assertFalse(tap(100, 1500, 0, 50));
        assertFalse(tap(100, 1500, late, late + 50));
        assertTrue(opened.isEmpty());
    }

    @Test public void tapsAwayFromMessagesDontPair() {
        onButton = false;
        assertFalse(tap(100, 400, 0, 50));
        assertFalse(tap(100, 400, 150, 200));
        assertTrue(opened.isEmpty());
    }

    @Test public void aSecondTapFarFromTheFirstGoesToInstagram() {
        int slop = ViewConfiguration.get(activity).getScaledDoubleTapSlop();
        assertFalse(tap(100, 1500, 0, 50));
        assertFalse(tap(100 + slop + 1, 1500, 150, 200));
        assertTrue(opened.isEmpty());
    }

    @Test public void aScrollOrAHoldIsNoFirstTap() {
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        assertFalse(at(MotionEvent.ACTION_DOWN, 100, 1500, 0));
        assertFalse(at(MotionEvent.ACTION_MOVE, 100, 1500 - slop - 1, 20));
        assertFalse(at(MotionEvent.ACTION_UP, 100, 1500 - slop - 1, 50));
        assertFalse("after a scroll", tap(100, 1500, 150, 200));

        long held = ViewConfiguration.getLongPressTimeout();
        assertFalse(tap(100, 1500, 1000, 1000 + held));
        assertFalse("after a long press", tap(100, 1500, 1000 + held + 50, 1000 + held + 100));
        assertTrue(opened.isEmpty());
    }

    @Test public void aSecondFingerIsNoFirstTap() {
        assertFalse(at(MotionEvent.ACTION_DOWN, 100, 1500, 0));
        assertFalse(at(MotionEvent.ACTION_POINTER_DOWN, 300, 1500, 10));
        assertFalse(at(MotionEvent.ACTION_UP, 100, 1500, 50));
        assertFalse(tap(100, 1500, 150, 200));
        assertTrue(opened.isEmpty());
    }

    @Test public void aCancelledSecondTapOpensNothing() {
        assertFalse(tap(100, 1500, 0, 50));
        assertTrue(at(MotionEvent.ACTION_DOWN, 100, 1500, 150));
        assertTrue(at(MotionEvent.ACTION_CANCEL, 100, 1500, 170));
        assertTrue(opened.isEmpty());
        assertFalse(at(MotionEvent.ACTION_MOVE, 100, 1500, 180));
    }

    @Test public void offOrPausedEveryTapGoesToInstagram() {
        Settings.DOUBLE_TAP_HIDDEN_CHATS.save(false);
        assertFalse(tap(100, 1500, 0, 50));
        assertFalse(tap(100, 1500, 150, 200));

        Settings.DOUBLE_TAP_HIDDEN_CHATS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(tap(100, 1500, 1000, 1050));
        assertFalse(tap(100, 1500, 1150, 1200));
        assertTrue(opened.isEmpty());
        assertTrue("the saved choice stays", Settings.DOUBLE_TAP_HIDDEN_CHATS.savedValue());
    }

    @Test public void aScreenWithoutMessagesButtonsHasNone() {
        HiddenChatsTap.resetForTests();
        assertFalse(HiddenChatsTap.onMessagesButton(activity, 100, 1500));
    }

    @Test public void withNothingHiddenTheTapsOpenTheListToPickChats() {
        List<Runnable[]> asks = new ArrayList<>();
        MessagesLockForTests.askWith(asks);

        HiddenChatsTap.toggle(activity);

        assertTrue("no lock on, so nothing to ask", asks.isEmpty());
        AlertDialog list = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(list);
        assertTrue(list.isShowing());
        assertEquals("Hidden chats", shadowOf(list).getTitle().toString());
        assertFalse(HiddenChats.revealed());
    }

    @Test public void hiddenChatsShowInTheInboxOnlyAfterTheLockAndTwoMoreTapsHideThem() {
        List<Runnable[]> asks = new ArrayList<>();
        MessagesLockForTests.askWith(asks);
        HiddenChats.add(ALICE, "Alice");

        HiddenChatsTap.toggle(activity);
        assertEquals("asked even with no messages lock on (#661)", 1, asks.size());
        assertFalse(HiddenChats.revealed());
        asks.get(0)[1].run();
        assertFalse("cancelled, nothing shows", HiddenChats.revealed());

        HiddenChatsTap.toggle(activity);
        assertEquals(2, asks.size());
        asks.get(1)[0].run();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(HiddenChats.revealed());
        assertEquals("Showing your hidden chats", ShadowToast.getTextOfLatestToast());

        HiddenChatsTap.toggle(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(HiddenChats.revealed());
        assertEquals("hiding them asks nothing", 2, asks.size());
        assertEquals("Hidden chats locked again", ShadowToast.getTextOfLatestToast());
        assertNull("the list didn't open either", ShadowAlertDialog.getLatestAlertDialog());
    }
}
