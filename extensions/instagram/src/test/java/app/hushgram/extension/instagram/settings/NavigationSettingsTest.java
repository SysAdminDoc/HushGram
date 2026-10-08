/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
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

import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Actual framework long-click actions, with native listeners that expose accidental delegation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class NavigationSettingsTest {
    enum NativeTab { FEED, SEARCH, CLIPS, DIRECT, PROFILE, SHARE, CREATION, NEWS,
        PRODUCER_PROFILE_PANEL, FEED_SWITCHER, DYNAMIC_TAB }
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Activity activity;
    private ActivityController<Activity> controller;
    private LinearLayout bar;
    private View button;
    private final AtomicInteger stock = new AtomicInteger();
    private View.OnLongClickListener nativeListener;

    @Before public void setUp() {
        PauseForTests.resume();
        SettingsEntry.onClosedByUser();
        ((Application) RuntimeEnvironment.getApplication()).registerActivityLifecycleCallbacks(new SettingsEntry.OpenWhenResumed());
        controller = Robolectric.buildActivity(Activity.class).setup();
        controller.windowFocusChanged(true);
        activity = controller.get();
        bar = new LinearLayout(activity);
        button = new View(activity);
        bar.addView(button, new LinearLayout.LayoutParams(60, 60));
        activity.setContentView(bar);
        bar.layout(0, 0, 300, 100);
        button.layout(0, 0, 60, 60);
        nativeListener = view -> { stock.incrementAndGet(); return true; };
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.FEED);
    }

    @After public void tearDown() throws Exception {
        SettingsEntry.onClosedByUser();
        PauseForTests.resume();
        Utils.awaitBackgroundTasksForTests();
    }

    private void nativeBind(Object tab, View.OnLongClickListener listener) {
        button.setOnLongClickListener(NavigationSettings.remember(button, tab, listener));
        NavigationSettings.bind(button, tab);
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

    @Test public void selectedGestureOpensOnceWithoutCallingHomeDeveloperHandler() {
        nativeBind(NativeTab.FEED, nativeListener);
        assertTrue(button.performLongClick());
        assertTrue(button.performLongClick());
        assertEquals(0, stock.get());
        assertEquals(1, shown());
    }

    @Test public void everyNativeTabCanBeChosenWithoutInventingAButton() {
        for (NativeTab tab : NativeTab.values()) {
            SettingsEntry.onClosedByUser();
            android.app.Fragment current = activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG);
            if (current != null) activity.getFragmentManager().beginTransaction().remove(current).commit();
            activity.getFragmentManager().executePendingTransactions();
            Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.valueOf(tab.name()));
            nativeBind(tab, nativeListener);
            assertTrue(tab.name(), button.performLongClick());
            assertEquals(tab.name(), 1, shown());
        }
        assertEquals(0, stock.get());
    }

    /**
     * A choice reached the tabs only on the next start, so until then the chosen tab's long press
     * did what it always had, which read as the choice not saving (#82). It reaches the tabs
     * already built now, gives the tab it leaves its own long press back, and a tab with none of
     * its own gains none while nobody chooses it.
     */
    @Test public void aChoiceReachesTheTabsAlreadyBuilt() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        nativeBind(NativeTab.PROFILE, nativeListener);
        View search = new View(activity);
        bar.addView(search, new LinearLayout.LayoutParams(60, 60));
        search.layout(60, 0, 120, 60);
        NavigationSettings.bind(search, NativeTab.SEARCH);
        assertFalse("an unchosen tab gained a long press", search.isLongClickable());

        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        NavigationSettings.applyChoice();
        assertFalse(search.isLongClickable());
        assertTrue(button.performLongClick());
        assertEquals(0, stock.get());
        assertEquals(1, shown());

        SettingsEntry.onClosedByUser();
        activity.getFragmentManager().beginTransaction()
                .remove(activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG)).commit();
        activity.getFragmentManager().executePendingTransactions();
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.SEARCH);
        NavigationSettings.applyChoice();
        assertTrue(button.performLongClick());
        assertEquals("Profile didn't get its own long press back", 1, stock.get());
        assertTrue(search.performLongClick());
        assertEquals(1, shown());

        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        NavigationSettings.applyChoice();
        assertFalse("a tab with no long press of its own kept one", search.isLongClickable());
        assertTrue(button.performLongClick());
        assertEquals(2, stock.get());
    }

    /**
     * 450's activity also puts the account switcher straight on the Profile button, past the tab's
     * setter and sometimes after it. The listener kept for Profile was then gone, so a choice made
     * in settings never reached Profile and it went on opening the switcher (#82). Instagram's
     * order here: the factory binds Profile with no listener, the setter gives it one, then the
     * activity replaces that one.
     */
    @Test public void theActivitysOwnLongPressStaysUnderTheChoice() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        AtomicInteger replaced = new AtomicInteger();
        NavigationSettings.bind(button, NativeTab.PROFILE);
        button.setOnLongClickListener(NavigationSettings.remember(button, NativeTab.PROFILE,
                view -> { replaced.incrementAndGet(); return true; }));
        NavigationSettings.setOnLongClickListener(button, nativeListener);
        assertTrue(button.performLongClick());
        assertEquals(1, stock.get());

        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        NavigationSettings.applyChoice();
        assertTrue(button.performLongClick());
        assertEquals("Profile opened the switcher, not HushGram", 1, stock.get());
        assertEquals(1, shown());

        SettingsEntry.onClosedByUser();
        activity.getFragmentManager().beginTransaction()
                .remove(activity.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG)).commit();
        activity.getFragmentManager().executePendingTransactions();
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        NavigationSettings.applyChoice();
        assertTrue(button.performLongClick());
        assertEquals("Profile didn't get the activity's switcher back", 2, stock.get());
        assertEquals(0, replaced.get());
        assertEquals(0, shown());
    }

    /** The same replacement on a start with Profile chosen used to leave Profile on the switcher. */
    @Test public void theActivitysLongPressAfterTheSetterKeepsTheChosenTab() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.PROFILE);
        NavigationSettings.bind(button, NativeTab.PROFILE);
        button.setOnLongClickListener(NavigationSettings.remember(button, NativeTab.PROFILE, view -> true));
        NavigationSettings.setOnLongClickListener(button, nativeListener);
        assertTrue(button.performLongClick());
        assertEquals(0, stock.get());
        assertEquals(1, shown());
    }

    @Test public void theActivitysLongPressOnAnyOtherViewIsUntouched() {
        View other = new View(activity);
        bar.addView(other, new LinearLayout.LayoutParams(60, 60));
        NavigationSettings.setOnLongClickListener(other, nativeListener);
        assertSame(nativeListener, shadowOf(other).getOnLongClickListener());
        NavigationSettings.setOnLongClickListener(other, null);
        assertNull(shadowOf(other).getOnLongClickListener());

        nativeBind(NativeTab.FEED, nativeListener);
        NavigationSettings.setOnLongClickListener(button, null);
        assertNull("a tab's null long press is teardown", shadowOf(button).getOnLongClickListener());
        assertFalse(button.performLongClick());
        assertEquals(0, shown());
    }

    @Test public void offAndUnselectedReturnTheExactNativeListener() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        assertSame(nativeListener, NavigationSettings.remember(button, NativeTab.FEED, nativeListener));
        button.setOnLongClickListener(nativeListener);
        NavigationSettings.bind(button, NativeTab.FEED);
        assertTrue(button.performLongClick());
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.SEARCH);
        assertSame(nativeListener, NavigationSettings.remember(button, NativeTab.FEED, nativeListener));
        assertEquals(1, stock.get());
        assertEquals(0, shown());
    }

    @Test public void pausedListenerDelegatesWithoutChangingSavedChoice() {
        nativeBind(NativeTab.FEED, nativeListener);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(button.performLongClick());
        assertEquals(1, stock.get());
        assertEquals(NavigationTarget.FEED, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
        assertEquals(0, shown());
    }

    @Test public void rebindUsesNewestNativeHandlerAndDoesNotStackWrappers() {
        nativeBind(NativeTab.FEED, nativeListener);
        AtomicInteger replacement = new AtomicInteger();
        nativeBind(NativeTab.FEED, view -> { replacement.incrementAndGet(); return false; });
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        assertFalse(button.performLongClick());
        assertEquals(0, stock.get());
        assertEquals(1, replacement.get());
        assertEquals(0, shown());
    }

    @Test public void retainedListenerCannotCallAReplacedNativeHandler() {
        nativeBind(NativeTab.FEED, nativeListener);
        View.OnLongClickListener old = shadowOf(button).getOnLongClickListener();
        AtomicInteger replacement = new AtomicInteger();
        nativeBind(NativeTab.FEED, view -> { replacement.incrementAndGet(); return true; });
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        assertFalse(old.onLongClick(button));
        assertEquals(0, stock.get());
        assertEquals(0, replacement.get());
        assertTrue(button.performLongClick());
        assertEquals(1, replacement.get());
        assertEquals(0, shown());
    }

    @Test public void retainedListenerCannotOpenAfterTabReplacementOrTeardown() {
        nativeBind(NativeTab.FEED, nativeListener);
        View.OnLongClickListener old = shadowOf(button).getOnLongClickListener();
        nativeBind(NativeTab.CLIPS, nativeListener);
        assertFalse(old.onLongClick(button));
        assertEquals(0, shown());
        assertTrue(button.performLongClick());
        assertEquals(1, stock.get());
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.CLIPS);
        nativeBind(NativeTab.CLIPS, nativeListener);
        View.OnLongClickListener removed = shadowOf(button).getOnLongClickListener();
        button.setOnLongClickListener(NavigationSettings.remember(button, NativeTab.CLIPS, null));
        assertFalse(removed.onLongClick(button));
        assertEquals(1, stock.get());
        assertEquals(0, shown());
    }

    @Test public void factoryRebindInvalidatesAWrapperWithNoNativeHandler() {
        NavigationSettings.bind(button, NativeTab.FEED);
        View.OnLongClickListener old = shadowOf(button).getOnLongClickListener();
        NavigationSettings.bind(button, NativeTab.FEED);
        assertFalse(old.onLongClick(button));
        assertTrue(button.performLongClick());
        assertEquals(1, shown());
    }

    @Test public void factoryTabMismatchInvalidatesItsOldBinding() {
        nativeBind(NativeTab.FEED, nativeListener);
        View.OnLongClickListener old = shadowOf(button).getOnLongClickListener();
        NavigationSettings.bind(button, NativeTab.CLIPS);
        assertFalse(old.onLongClick(button));
        assertEquals(0, stock.get());
        assertEquals(0, shown());
    }

    @Test public void aBindingCannotActOnAnotherAttachedButton() {
        nativeBind(NativeTab.FEED, nativeListener);
        View another = new View(activity);
        bar.addView(another, new LinearLayout.LayoutParams(60, 60));
        another.layout(60, 0, 120, 60);
        assertFalse(shadowOf(button).getOnLongClickListener().onLongClick(another));
        assertEquals(0, stock.get());
        assertEquals(0, shown());
    }

    @Test @Config(sdk = 37)
    public void staleHapticRequestsCannotReachAnOldNativeHandler() {
        AtomicInteger haptics = new AtomicInteger();
        nativeBind(NativeTab.FEED, new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) { return true; }
            @Override public boolean onLongClickUseDefaultHapticFeedback(View view) {
                haptics.incrementAndGet();
                return true;
            }
        });
        View.OnLongClickListener old = shadowOf(button).getOnLongClickListener();
        nativeBind(NativeTab.FEED, nativeListener);
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        assertFalse(old.onLongClickUseDefaultHapticFeedback(button));
        assertEquals(0, haptics.get());
        assertTrue(shadowOf(button).getOnLongClickListener().onLongClickUseDefaultHapticFeedback(button));
    }

    @Test @Config(sdk = 37)
    public void retainedHapticRequestsRequireALiveVisibleEnabledOwner() {
        AtomicInteger haptics = new AtomicInteger();
        nativeBind(NativeTab.FEED, new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) { return true; }
            @Override public boolean onLongClickUseDefaultHapticFeedback(View view) {
                haptics.incrementAndGet();
                return true;
            }
        });
        View.OnLongClickListener retained = shadowOf(button).getOnLongClickListener();
        Runnable[] invalidate = {
                () -> button.setEnabled(false),
                () -> bar.setVisibility(View.GONE),
                () -> bar.removeView(button),
                () -> controller.windowFocusChanged(false),
                () -> controller.windowFocusChanged(false).pause().stop().destroy()
        };
        Runnable[] restore = {
                () -> button.setEnabled(true),
                () -> bar.setVisibility(View.VISIBLE),
                () -> bar.addView(button, new LinearLayout.LayoutParams(60, 60)),
                () -> controller.windowFocusChanged(true),
                () -> { }
        };
        for (int state = 0; state < invalidate.length; state++) {
            invalidate[state].run();
            for (NavigationTarget target : new NavigationTarget[] {NavigationTarget.FEED, NavigationTarget.OFF}) {
                Settings.NAVIGATION_SETTINGS_TARGET.save(target);
                assertFalse("state " + state + ", " + target,
                        retained.onLongClickUseDefaultHapticFeedback(button));
            }
            restore[state].run();
        }
        assertEquals(0, haptics.get());
    }

    @Test public void aPausedStartKeepsTheNativeHandlerAndTheNextStartBindsTheSavedGesture() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        nativeBind(NativeTab.FEED, nativeListener);
        assertTrue(button.performLongClick());
        assertEquals(1, stock.get());
        assertEquals(0, shown());
        assertEquals(NavigationTarget.FEED, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
        // Pause is fixed for the process. Turning it off takes effect on the next start,
        // when Instagram constructs the navigation tabs again.
        PauseForTests.resume();
        nativeBind(NativeTab.FEED, nativeListener);
        assertTrue(button.performLongClick());
        assertEquals(1, stock.get());
        assertEquals(1, shown());
    }

    @Test public void nullTeardownStaysNullAndNormalTapStaysNative() {
        AtomicInteger taps = new AtomicInteger();
        button.setOnClickListener(view -> taps.incrementAndGet());
        nativeBind(NativeTab.FEED, nativeListener);
        assertTrue(button.performClick());
        button.setOnLongClickListener(NavigationSettings.remember(button, NativeTab.FEED, null));
        assertFalse(button.performLongClick());
        assertEquals(1, taps.get());
        assertEquals(0, shown());
    }

    @Test public void selectedTabWithNoNativeLongPressStillOpensSettings() {
        button.setOnLongClickListener(null);
        NavigationSettings.bind(button, NativeTab.FEED);
        assertTrue(button.performLongClick());
        assertEquals(1, shown());
    }

    @Test public void detachedHiddenAndDisabledButtonsCannotOpenSettings() {
        nativeBind(NativeTab.FEED, nativeListener);
        bar.setEnabled(false);
        assertFalse(button.performLongClick());
        bar.setEnabled(true);
        bar.setVisibility(View.GONE);
        assertFalse(button.performLongClick());
        bar.setVisibility(View.VISIBLE);
        View.OnLongClickListener retained = shadowOf(button).getOnLongClickListener();
        bar.removeView(button);
        // A detached framework View falls back to parent.showContextMenu after a false listener
        // and has no parent. Exercise the actual retained callback, without inventing a parent.
        assertFalse(retained.onLongClick(button));
        assertEquals(0, stock.get());
        assertEquals(0, shown());
    }

    @Test public void offAndUnreadyDoNotAddALongClickAction() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        NavigationSettings.bind(button, NativeTab.FEED);
        assertFalse(button.isLongClickable());
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.FEED);
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            assertSame(nativeListener, NavigationSettings.remember(button, NativeTab.FEED, nativeListener));
            NavigationSettings.bind(button, NativeTab.FEED);
            assertFalse(button.isLongClickable());
        });
    }

    @Test public void retainedListenerUsesStockWhileSettingsBecomeUnready() {
        nativeBind(NativeTab.FEED, nativeListener);
        SettingsContextRule.withoutContext(() -> assertTrue(button.performLongClick()));
        assertEquals(1, stock.get());
        assertEquals(0, shown());
    }

    @Test public void reelsGestureKeepsItsNativeActionUnlessReelsIsChosen() {
        nativeBind(NativeTab.CLIPS, nativeListener);
        assertTrue(button.performLongClick());
        assertEquals(1, stock.get());
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.CLIPS);
        nativeBind(NativeTab.CLIPS, nativeListener);
        assertTrue(button.performLongClick());
        assertEquals(1, stock.get());
        assertEquals(1, shown());
    }

    @Test public void accessibilityLongClickPreservesLabelAndKeyboardFocus() {
        button.setContentDescription("Home tab");
        button.setFocusableInTouchMode(true);
        assertTrue(button.requestFocus());
        nativeBind(NativeTab.FEED, nativeListener);
        AccessibilityNodeInfo node = button.createAccessibilityNodeInfo();
        assertTrue(node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK));
        assertEquals("Home tab", node.getContentDescription());
        assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null));
        assertTrue(button.hasFocus());
        assertEquals(1, shown());
        assertEquals(0, stock.get());
    }

    @Test public void stoppedOrDestroyedOwnerCannotReceiveADeferredGesture() {
        nativeBind(NativeTab.FEED, nativeListener);
        controller.windowFocusChanged(false).pause().stop();
        assertFalse(button.performLongClick());
        controller.destroy();
        assertFalse(button.performLongClick());
        assertEquals(0, stock.get());
    }

    @Test public void gestureSettingsFollowSignedOutModalAndCloseStaysClosed() {
        nativeBind(NativeTab.FEED, nativeListener);
        assertTrue(button.performLongClick());
        assertEquals(1, shown());
        Activity front = Robolectric.buildActivity(Activity.class, new Intent()).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        front.getFragmentManager().executePendingTransactions();
        assertNotNull(front.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG));
        assertEquals(0, shown());
        SettingsEntry.onClosedByUser();
        Activity next = Robolectric.buildActivity(Activity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        next.getFragmentManager().executePendingTransactions();
        assertNull(next.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG));
    }

    @Test @Config(sdk = 37) public void inactiveWrapperKeepsTheNativeHapticDecision() {
        AtomicInteger haptics = new AtomicInteger();
        View.OnLongClickListener nativeHaptic = new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) { return true; }
            @Override public boolean onLongClickUseDefaultHapticFeedback(View view) { haptics.incrementAndGet(); return false; }
        };
        View.OnLongClickListener retained = NavigationSettings.remember(button, NativeTab.FEED, nativeHaptic);
        assertTrue(retained.onLongClickUseDefaultHapticFeedback(button));
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.OFF);
        assertFalse(retained.onLongClickUseDefaultHapticFeedback(button));
        SettingsContextRule.withoutContext(() -> assertFalse(retained.onLongClickUseDefaultHapticFeedback(button)));
        assertEquals(2, haptics.get());
    }
}
