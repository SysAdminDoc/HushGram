/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
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

    private Activity activity;
    private final List<Activity> opened = new ArrayList<>();
    private boolean onButton = true;

    @Before public void prepare() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        HiddenChatsTap.resetForTests();
        HiddenChatsTap.buttons = (shown, x, y) -> onButton;
        HiddenChatsTap.opener = opened::add;
        Settings.DOUBLE_TAP_HIDDEN_CHATS.save(true);
    }

    @After public void close() {
        HiddenChatsTap.resetForTests();
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
}
