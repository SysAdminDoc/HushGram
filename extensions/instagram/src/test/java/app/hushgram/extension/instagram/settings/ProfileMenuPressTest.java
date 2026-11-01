/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ContextWrapper;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A long press on your profile's menu button opens HushGram (#84), on a stand-in of Instagram 450's
 * profile top bar: the Create New button in the left group, the Threads button and the menu in the
 * right group, each found by the id name Instagram keeps. Off, Pause, settings not read yet,
 * someone else's profile or a button with a long press of its own all stay Instagram's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ProfileMenuPressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Activity activity;
    private LinearLayout left;
    private LinearLayout right;
    private ImageView create;
    private ImageView threads;
    private ImageView menu;
    private final AtomicInteger taps = new AtomicInteger();

    @Before public void setUp() {
        PauseForTests.resume();
        ProfileMenuPress.forgetForTests();
        SettingsEntry.onClosedByUser();
        ((Application) RuntimeEnvironment.getApplication()).registerActivityLifecycleCallbacks(new SettingsEntry.OpenWhenResumed());
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        controller.windowFocusChanged(true);
        activity = controller.get();
        int createId = View.generateViewId();
        int rightId = View.generateViewId();
        ProfileMenuPress.idForTests(ProfileMenuPress.CREATE_BUTTON, createId);
        ProfileMenuPress.idForTests(ProfileMenuPress.RIGHT_BUTTONS, rightId);

        FrameLayout root = new FrameLayout(activity);
        LinearLayout bar = new LinearLayout(activity);
        left = new LinearLayout(activity);
        right = new LinearLayout(activity);
        right.setId(rightId);
        create = button();
        create.setId(createId);
        left.addView(create);
        threads = button();
        menu = button();
        right.addView(threads);
        right.addView(menu);
        bar.addView(left);
        bar.addView(right);
        root.addView(bar);
        activity.setContentView(root);
    }

    @After public void tearDown() throws Exception {
        Settings.PROFILE_MENU_LONG_PRESS.resetToDefault();
        SettingsEntry.onClosedByUser();
        PauseForTests.resume();
        ProfileMenuPress.forgetForTests();
        Utils.awaitBackgroundTasksForTests();
    }

    /** An icon button the way Instagram's bar builds one: a tap and no long press. */
    private ImageView button() {
        ImageView button = new ImageView(activity);
        button.setOnClickListener(view -> taps.incrementAndGet());
        return button;
    }

    private int shown() {
        shadowOf(Looper.getMainLooper()).idle();
        activity.getFragmentManager().executePendingTransactions();
        int count = 0;
        for (android.app.Fragment fragment : activity.getFragmentManager().getFragments()) {
            if (SettingsEntry.DIALOG_TAG.equals(fragment.getTag()) && !fragment.isRemoving()) count++;
        }
        return count;
    }

    private void closeSettings() {
        SettingsEntry.onClosedByUser();
        android.app.Fragment open = activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG);
        if (open != null) activity.getFragmentManager().beginTransaction().remove(open).commit();
        activity.getFragmentManager().executePendingTransactions();
    }

    @Test public void offByDefaultTheMenuKeepsInstagramsTapOnly() {
        assertFalse(Settings.PROFILE_MENU_LONG_PRESS.savedValue());
        ProfileMenuPress.look(activity);
        assertFalse("the menu gained a long press while off", menu.isLongClickable());
        assertFalse(menu.performLongClick());
        assertEquals(0, shown());
    }

    @Test public void onTheMenusLongPressOpensHushGramAndATapStaysInstagrams() {
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.look(activity);
        assertTrue(ProfileMenuPress.carries(menu));
        assertFalse("only the menu, the last button, gets the long press", threads.isLongClickable());
        assertFalse(create.isLongClickable());
        assertTrue(menu.performLongClick());
        assertEquals(1, shown());
        assertEquals(0, taps.get());
        assertTrue(menu.performClick());
        assertEquals("a tap still opens Instagram's menu", 1, taps.get());
    }

    @Test public void pauseHandsThePressBackAndResumeGivesItAgain() {
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.look(activity);
        assertTrue(ProfileMenuPress.carries(menu));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("a paused long press opened HushGram", menu.performLongClick());
        assertEquals(0, shown());
        ProfileMenuPress.look(activity);
        assertFalse("the long press stayed on while paused", menu.isLongClickable());
        assertTrue(Settings.PROFILE_MENU_LONG_PRESS.savedValue());

        PauseForTests.resume();
        ProfileMenuPress.look(activity);
        assertTrue(menu.performLongClick());
        assertEquals(1, shown());
    }

    @Test public void aPausedRunWatchesNoScreenAndAWatchStopsOnceItsPaused() {
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        View decor = activity.getWindow().getDecorView();
        int before = layoutListeners(decor);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ProfileMenuPress.watch(activity);
        assertEquals("paused, no screen is watched", before, layoutListeners(decor));

        PauseForTests.resume();
        ProfileMenuPress.watch(activity);
        assertEquals("not paused, the screen is watched", before + 1, layoutListeners(decor));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("paused, the next layout stops the watch", before, layoutListeners(decor));
    }

    /** How many layout listeners [view]'s window holds, from the observer's own list. */
    private static int layoutListeners(View view) {
        Object listeners = ReflectionHelpers.getField(view.getViewTreeObserver(), "mOnGlobalLayoutListeners");
        if (listeners == null) return 0;
        Integer size = ReflectionHelpers.callInstanceMethod(listeners, "size");
        return size;
    }

    @Test public void beforeTheSettingsAreReadTheMenuIsInstagrams() {
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            ProfileMenuPress.look(activity);
            assertFalse(menu.isLongClickable());
        });
        ProfileMenuPress.look(activity);
        assertTrue(ProfileMenuPress.carries(menu));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertFalse(menu.performLongClick()));
        assertEquals(0, shown());
    }

    @Test public void turningItOffTakesTheLongPressAwayAtOnce() {
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.look(activity);
        View.OnLongClickListener retained = shadowOf(menu).getOnLongClickListener();
        assertNotNull(retained);

        Settings.PROFILE_MENU_LONG_PRESS.save(false);
        ProfileMenuPress.applyChoice();
        assertFalse(menu.isLongClickable());
        assertNull(shadowOf(menu).getOnLongClickListener());
        assertFalse(menu.performLongClick());

        // A press kept from before can't open HushGram from a button that gave it back.
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        assertFalse(retained.onLongClick(menu));
        assertEquals(0, shown());
    }

    @Test public void turningItOnReachesTheMenuAlreadyOnScreen() {
        ProfileMenuPress.resumedForTests(activity);
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.applyChoice();
        assertTrue(menu.performLongClick());
        assertEquals(1, shown());
    }

    @Test public void someoneElsesProfileHasNoCreateButtonAndIsLeftAlone() {
        left.removeView(create);
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.look(activity);
        assertFalse(menu.isLongClickable());
        assertFalse(threads.isLongClickable());
    }

    @Test public void aBarWithoutTheMenuGetsNothing() {
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        right.removeView(menu);
        View badge = new View(activity);
        right.addView(badge);
        ProfileMenuPress.look(activity);
        assertFalse("a last view that isn't an image button got the long press", badge.isLongClickable());
        assertFalse("the button before it was taken for the menu", threads.isLongClickable());

        right.removeView(badge);
        ImageView quiet = new ImageView(activity);
        right.addView(quiet);
        ProfileMenuPress.look(activity);
        assertFalse("an image with no tap isn't the menu", quiet.isLongClickable());

        right.removeAllViews();
        ProfileMenuPress.look(activity);
        ((LinearLayout) right.getParent()).removeView(right);
        ProfileMenuPress.look(activity);
        assertEquals(0, shown());
    }

    @Test public void aHiddenLastButtonIsSkippedForTheMenuBeforeIt() {
        ImageView hidden = button();
        hidden.setVisibility(View.GONE);
        right.addView(hidden);
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.look(activity);
        assertFalse(hidden.isLongClickable());
        assertTrue(ProfileMenuPress.carries(menu));
    }

    @Test public void instagramsOwnLongPressIsNeverTaken() {
        AtomicInteger stock = new AtomicInteger();
        View.OnLongClickListener own = view -> { stock.incrementAndGet(); return true; };
        menu.setOnLongClickListener(own);
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.look(activity);
        assertFalse(ProfileMenuPress.carries(menu));
        assertSame(own, shadowOf(menu).getOnLongClickListener());
        assertTrue(menu.performLongClick());
        assertEquals(1, stock.get());
        assertEquals(0, shown());

        Settings.PROFILE_MENU_LONG_PRESS.save(false);
        ProfileMenuPress.applyChoice();
        assertSame("turning it off took Instagram's long press", own, shadowOf(menu).getOnLongClickListener());
        assertTrue(menu.isLongClickable());
    }

    @Test public void aRebuiltBarGivesTheNewMenuTheLongPress() {
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.look(activity);
        right.removeAllViews();
        ImageView rebuilt = button();
        right.addView(button());
        right.addView(rebuilt);
        ProfileMenuPress.look(activity);
        assertTrue(rebuilt.performLongClick());
        assertEquals(1, shown());
    }

    /** A profile opened from a link hands its bar a context with no activity under it. The press still opens HushGram. */
    @Test public void aMenuMadeOnAPlainContextStillOpensHushGram() {
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        right.removeAllViews();
        ImageView plain = new ImageView(new ContextWrapper(RuntimeEnvironment.getApplication()));
        plain.setOnClickListener(view -> taps.incrementAndGet());
        right.addView(button());
        right.addView(plain);
        ProfileMenuPress.look(activity);
        assertTrue(plain.performLongClick());
        assertEquals(1, shown());
    }

    @Test public void theScreenIsWatchedOnlyWhileTheSwitchIsSavedOn() {
        View decor = activity.getWindow().getDecorView();
        ProfileMenuPress.watch(activity);
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        layout(decor);
        assertFalse("a screen was watched with the switch off", menu.isLongClickable());

        ProfileMenuPress.watch(activity);
        layout(decor);
        assertTrue(ProfileMenuPress.carries(menu));

        Settings.PROFILE_MENU_LONG_PRESS.save(false);
        layout(decor);
        assertFalse(menu.isLongClickable());
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        layout(decor);
        assertFalse("the watch kept looking after the switch went off", menu.isLongClickable());
    }

    @Test public void aBurstOfLayoutsLooksOnceMoreAfterTheLast() {
        View decor = activity.getWindow().getDecorView();
        Settings.PROFILE_MENU_LONG_PRESS.save(true);
        ProfileMenuPress.watch(activity);
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
        right.removeAllViews();
        ImageView rebuilt = button();
        right.addView(rebuilt);
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
        assertFalse("a layout inside the wait looked at once", ProfileMenuPress.carries(rebuilt));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ProfileMenuPress.LOOK_EVERY_MS));
        assertTrue("the bar rebuilt in a burst kept its menu without the long press", ProfileMenuPress.carries(rebuilt));
    }

    /** One layout of the screen, and the look a burst of layouts saves for after it. */
    private static void layout(View decor) {
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ProfileMenuPress.LOOK_EVERY_MS));
    }
}
