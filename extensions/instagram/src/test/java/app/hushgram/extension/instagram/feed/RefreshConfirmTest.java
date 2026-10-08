/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static app.hushgram.extension.instagram.feed.LikeConfirmTest.shown;
import static app.hushgram.extension.instagram.feed.LikeConfirmTest.tap;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import java.util.ArrayList;
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
public class RefreshConfirmTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Activity activity;

    @Before public void start() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        ShadowDialog.reset();
        HookStatus.clear();
    }

    @After public void restore() {
        Settings.ASK_BEFORE_REFRESH.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** A refresh layout as Instagram's keeps it: setRefreshing is public, and the extension reaches it by name. */
    public static final class Spinner extends FrameLayout {
        final List<Boolean> refreshing = new ArrayList<>();

        public Spinner(Context context) {
            super(context);
        }

        public void setRefreshing(boolean on) {
            refreshing.add(on);
        }
    }

    /** The layout's end-of-pull animation: it hands its listener to the hook and refreshes when it gets it back. */
    private static final class PullEnd implements Animation.AnimationListener {
        final View layout;
        final Object listener = new Object();
        final List<Object> handed = new ArrayList<>();

        PullEnd(View layout) {
            this.layout = layout;
        }

        @Override public void onAnimationEnd(Animation animation) {
            handed.add(RefreshConfirm.listener(layout, listener, this));
        }

        @Override public void onAnimationStart(Animation animation) {
        }

        @Override public void onAnimationRepeat(Animation animation) {
        }
    }

    @Test public void offTheRefreshGoesThroughUnasked() {
        PullEnd end = new PullEnd(new Spinner(activity));
        end.onAnimationEnd(null);
        assertEquals(List.of(end.listener), end.handed);
        assertNull(ShadowDialog.getLatestDialog());
        assertNull("no listener stays none", RefreshConfirm.listener(end.layout, null, end));
    }

    @Test public void onTheRefreshWaitsForRefresh() {
        Settings.ASK_BEFORE_REFRESH.save(true);
        Spinner layout = new Spinner(activity);
        PullEnd end = new PullEnd(layout);
        end.onAnimationEnd(null);
        assertEquals("skipped while the question is up", 1, end.handed.size());
        assertNull(end.handed.get(0));

        AlertDialog question = shown();
        end.onAnimationEnd(null);
        assertNull("a second pull doesn't put up a second question", end.handed.get(1));
        assertSame(question, ShadowDialog.getLatestDialog());

        assertTrue(RefreshConfirm.holds(layout));
        tap(question, AlertDialog.BUTTON_POSITIVE);
        assertFalse("the question is let go once it's gone", RefreshConfirm.holds(layout));
        assertEquals("Refresh ends the pull again, and this time it refreshes", 3, end.handed.size());
        assertSame(end.listener, end.handed.get(2));
        assertTrue("the spinner is left to the refresh", layout.refreshing.isEmpty());
        assertEquals(List.of(FamilyNames.ASK_BEFORE_REFRESH + ": invoked 3, 0 found, 0 missing. Counted: " + RefreshConfirm.ASKED + " 1"),
                HookStatus.report());
    }

    @Test public void cancelStopsTheSpinnerAndTheNextPullAsksAgain() {
        Settings.ASK_BEFORE_REFRESH.save(true);
        Spinner layout = new Spinner(activity);
        PullEnd end = new PullEnd(layout);
        end.onAnimationEnd(null);
        assertTrue(RefreshConfirm.holds(layout));
        tap(shown(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(List.of(false), layout.refreshing);
        assertEquals(1, end.handed.size());
        assertFalse("Cancel lets the question go", RefreshConfirm.holds(layout));

        end.onAnimationEnd(null);
        assertNull("the next pull asks again", end.handed.get(1));
        shown().cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(false, false), layout.refreshing);
        assertFalse("Back lets the question go", RefreshConfirm.holds(layout));
    }

    /** Paused, unready, with no screen to ask on or throwing, the refresh goes ahead as Instagram's. */
    @Test public void pausedUnreadyScreenlessAndThrowingLetTheRefreshThrough() {
        Settings.ASK_BEFORE_REFRESH.save(true);
        PullEnd end = new PullEnd(new Spinner(activity));
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        end.onAnimationEnd(null);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> end.onAnimationEnd(null));
        SettingsContextRule.beforeThePauseIsDecided(() -> end.onAnimationEnd(null));
        PullEnd noActivity = new PullEnd(new Spinner(RuntimeEnvironment.getApplication()));
        noActivity.onAnimationEnd(null);
        assertEquals(List.of(end.listener, end.listener, end.listener), end.handed);
        assertEquals(List.of(noActivity.listener), noActivity.handed);

        Object listener = new Object();
        assertSame("no layout to ask on", listener, RefreshConfirm.listener(null, listener, end));
        assertNull(ShadowDialog.getLatestDialog());
        String missing = HookStatus.missing(FamilyNames.ASK_BEFORE_REFRESH).toString();
        assertTrue(missing, missing.contains("'" + RefreshConfirm.ASK + "'"));
    }

    @Test public void aLayoutWithoutSetRefreshingIsReportedOnCancel() {
        Settings.ASK_BEFORE_REFRESH.save(true);
        PullEnd end = new PullEnd(new FrameLayout(activity));
        end.onAnimationEnd(null);
        tap(shown(), AlertDialog.BUTTON_NEGATIVE);
        String missing = HookStatus.missing(FamilyNames.ASK_BEFORE_REFRESH).toString();
        assertTrue(missing, missing.contains("'" + RefreshConfirm.SPINNER + "'"));
    }

    /** A nested-scrolling layout's refresh listener, as the layout calls it once a pull goes far enough. */
    private static final class Listener implements Runnable {
        int refreshes;

        @Override public void run() {
            refreshes++;
        }
    }

    /** The nested-scrolling layout's pull: it hands its listener to the hook, and calls what it gets back. */
    private static Object pull(View layout, Object listener) {
        Object back = RefreshConfirm.pull(layout, listener);
        if (back instanceof Runnable) ((Runnable) back).run();
        return back;
    }

    @Test public void offANestedPullRefreshesUnasked() {
        Listener listener = new Listener();
        assertSame(listener, pull(new Spinner(activity), listener));
        assertEquals(1, listener.refreshes);
        assertNull(ShadowDialog.getLatestDialog());
        assertNull("no listener stays none", RefreshConfirm.pull(new Spinner(activity), null));
    }

    @Test public void onANestedPullWaitsForRefresh() {
        Settings.ASK_BEFORE_REFRESH.save(true);
        Spinner layout = new Spinner(activity);
        Listener listener = new Listener();
        assertNull("skipped while the question is up", pull(layout, listener));
        AlertDialog question = shown();
        assertNull("a second pull doesn't put up a second question", pull(layout, listener));
        assertSame(question, ShadowDialog.getLatestDialog());
        assertEquals(0, listener.refreshes);

        tap(question, AlertDialog.BUTTON_POSITIVE);
        assertEquals("Refresh calls the listener the pull would have", 1, listener.refreshes);
        assertFalse("the question is let go once it's gone", RefreshConfirm.holds(layout));
        assertTrue("the spinner is left to the refresh", layout.refreshing.isEmpty());
        assertEquals(List.of(FamilyNames.ASK_BEFORE_REFRESH + ": invoked 2, 0 found, 0 missing. Counted: " + RefreshConfirm.ASKED + " 1"),
                HookStatus.report());
    }

    @Test public void cancelOnANestedPullStopsItsSpinnerAndTheNextPullAsksAgain() {
        Settings.ASK_BEFORE_REFRESH.save(true);
        Spinner layout = new Spinner(activity);
        Listener listener = new Listener();
        pull(layout, listener);
        tap(shown(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(List.of(false), layout.refreshing);
        assertEquals(0, listener.refreshes);
        assertFalse("Cancel lets the question go", RefreshConfirm.holds(layout));

        assertNull("the next pull asks again", pull(layout, listener));
        tap(shown(), AlertDialog.BUTTON_POSITIVE);
        assertEquals(1, listener.refreshes);
    }

    @Test public void aListenerRefreshCantCallIsReported() {
        Settings.ASK_BEFORE_REFRESH.save(true);
        assertNull(pull(new Spinner(activity), new Object()));
        tap(shown(), AlertDialog.BUTTON_POSITIVE);
        String missing = HookStatus.missing(FamilyNames.ASK_BEFORE_REFRESH).toString();
        assertTrue(missing, missing.contains("'" + RefreshConfirm.ASK + "'"));
    }
}
