/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** The views a patch keeps gone without touching Instagram's code: found, hidden before the frame, kept hidden. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ViewHiderTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String FAMILY = FamilyNames.COMMENT_EMOJI_ROW;

    private final AtomicBoolean on = new AtomicBoolean(true);
    private ActivityController<Activity> controller;
    private Activity activity;
    private ViewHider hider;
    private int targetId;
    private int otherTargetId;
    private int scopeId;
    private int keepId;
    private FrameLayout root;

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        targetId = View.generateViewId();
        otherTargetId = View.generateViewId();
        scopeId = View.generateViewId();
        keepId = View.generateViewId();
        root = new FrameLayout(activity);
        HookStatus.clear();
    }

    @After
    public void restore() {
        if (hider != null) hider.resetForTests();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private ViewHider hider(String scope) {
        return hider(scope, on::get);
    }

    private ViewHider hider(String scope, java.util.function.BooleanSupplier wanted) {
        hider = new ViewHider(FAMILY, "test views", "test views hidden", wanted, scope, "target", "other_target");
        hider.idForTests(activity, "target", targetId);
        hider.idForTests(activity, "other_target", otherTargetId);
        hider.idForTests(activity, "scope", scopeId);
        return hider;
    }

    private TextView view(int id, String text) {
        TextView view = new TextView(activity);
        if (id != 0) view.setId(id);
        view.setText(text);
        return view;
    }

    private void show() {
        activity.setContentView(root);
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** One frame's pre-draw on the window. True when a listener canceled the frame. */
    private boolean frame() {
        return root.getViewTreeObserver().dispatchOnPreDraw();
    }

    private void layout() {
        root.getViewTreeObserver().dispatchOnGlobalLayout();
    }

    @Test
    public void hidesOnlyTheNamedViewsAndCancelsTheFrameThatWouldShowThem() {
        LinearLayout column = new LinearLayout(activity);
        TextView target = view(targetId, "emoji");
        TextView other = view(otherTargetId, "more emoji");
        TextView keep = view(keepId, "comment box");
        column.addView(target);
        column.addView(other);
        column.addView(keep);
        root.addView(column);
        show();
        hider(null).watch(activity);

        assertTrue("the frame that would show them is canceled", frame());

        assertEquals(View.GONE, target.getVisibility());
        assertEquals(View.GONE, other.getVisibility());
        assertEquals("the rest stays", View.VISIBLE, keep.getVisibility());
        assertEquals("nothing is taken out", 3, column.getChildCount());
        layout();
        assertFalse("a frame with nothing more to hide goes ahead", frame());
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).isEmpty());
    }

    @Test
    public void aViewInstagramShowsAgainIsHiddenBeforeTheNextFrame() {
        TextView target = view(targetId, "emoji");
        root.addView(target);
        show();
        hider(null).watch(activity);
        frame();

        target.setVisibility(View.VISIBLE);

        assertTrue(frame());
        assertEquals(View.GONE, target.getVisibility());
    }

    @Test
    public void aViewAddedAfterTheFirstLookIsFoundAfterTheNextLayout() {
        show();
        hider(null).watch(activity);
        frame();
        TextView late = view(targetId, "emoji");

        root.addView(late);
        layout();

        assertTrue(frame());
        assertEquals(View.GONE, late.getVisibility());
    }

    @Test
    public void aViewStubIsLeftAlone() {
        ViewStub stub = new ViewStub(activity);
        stub.setId(targetId);
        root.addView(stub);
        show();
        hider(null).watch(activity);

        assertFalse(frame());
        assertEquals("the stub is as it was", View.GONE, stub.getVisibility());
        assertEquals(1, root.getChildCount());
    }

    @Test
    public void withAScopeOnlyTheViewsInsideItGo() {
        LinearLayout inside = new LinearLayout(activity);
        inside.setId(scopeId);
        TextView in = view(targetId, "follow in the header");
        inside.addView(in);
        TextView out = view(targetId, "follow elsewhere");
        root.addView(inside);
        root.addView(out);
        show();
        hider("scope").watch(activity);

        assertTrue(frame());

        assertEquals(View.GONE, in.getVisibility());
        assertEquals("the same id outside the scope stays", View.VISIBLE, out.getVisibility());
        assertEquals("the scope stays", View.VISIBLE, inside.getVisibility());
    }

    @Test
    public void aBuildWithoutTheScopeSaysSoAndChangesNothing() {
        TextView target = view(targetId, "emoji");
        root.addView(target);
        show();
        hider("scope");
        hider.idForTests(activity, "scope", 0);
        hider.watch(activity);

        assertFalse(frame());

        assertEquals(View.VISIBLE, target.getVisibility());
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).contains("view id 'scope'"));
    }

    @Test
    public void aBuildWithoutAnIdSaysSoAndKeepsTheOtherOne() {
        TextView target = view(targetId, "emoji");
        TextView other = view(otherTargetId, "more emoji");
        root.addView(target);
        root.addView(other);
        show();
        hider(null);
        hider.idForTests(activity, "target", 0);
        hider.watch(activity);

        assertTrue(frame());

        assertEquals(View.VISIBLE, target.getVisibility());
        assertEquals(View.GONE, other.getVisibility());
        assertTrue(HookStatus.missing(FAMILY).toString(), HookStatus.missing(FAMILY).contains("view id 'target'"));
    }

    @Test
    public void offAtResumeListensToNothing() {
        on.set(false);
        TextView target = view(targetId, "emoji");
        root.addView(target);
        show();
        hider(null).watch(activity);

        assertFalse(hider.listeningForTests(activity));
        assertEquals(0, hider.sessionsForTests());
        assertFalse(frame());
        assertEquals(View.VISIBLE, target.getVisibility());
    }

    @Test
    public void turnedOffWhileShowingItDropsItsListenersAtTheNextFrame() {
        TextView target = view(targetId, "emoji");
        root.addView(target);
        show();
        hider(null).watch(activity);
        frame();
        assertTrue(hider.listeningForTests(activity));

        on.set(false);

        assertFalse(frame());
        assertFalse(hider.listeningForTests(activity));
        TextView late = view(targetId, "emoji");
        root.addView(late);
        layout();
        frame();
        assertEquals("nothing is hidden once it's off", View.VISIBLE, late.getVisibility());
    }

    @Test
    public void pausingAnActivityTakesItsListenersAway() {
        root.addView(view(targetId, "emoji"));
        show();
        hider(null).watch(activity);
        assertTrue(hider.listeningForTests(activity));

        hider.unwatch(activity);

        assertEquals(0, hider.sessionsForTests());
        assertFalse(frame());
    }

    @Test
    public void watchingTwiceListensOnce() {
        TextView target = view(targetId, "emoji");
        root.addView(target);
        show();
        hider(null).watch(activity);
        hider.watch(activity);

        assertEquals(1, hider.sessionsForTests());
        assertTrue(frame());
        assertEquals(View.GONE, target.getVisibility());
    }

    @Test
    public void theApplicationHookWatchesEachActivityThatResumes() {
        TextView target = view(targetId, "emoji");
        root.addView(target);
        hider(null);
        hider.install(activity.getApplication(), true);
        hider.install(activity.getApplication(), true);
        show();

        controller.pause().resume();

        assertTrue(hider.listeningForTests(activity));
        assertTrue(frame());
        assertEquals(View.GONE, target.getVisibility());
        controller.pause();
        assertEquals(0, hider.sessionsForTests());
    }

    /** HushGram settings are a dialog over the screen, so a switch flipped there can't wait for a resume. */
    @Test
    public void aSwitchFlippedUnderSettingsTakesHoldWithoutAResume() {
        TextView target = view(targetId, "emoji");
        root.addView(target);
        show();
        on.set(false);
        hider(null).watch(activity);
        assertFalse(hider.listeningForTests(activity));

        on.set(true);
        hider.switchChanged(activity);
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(hider.listeningForTests(activity));
        assertTrue(frame());
        assertEquals(View.GONE, target.getVisibility());
    }

    @Test
    public void aBuildWithoutThePatchRegistersNothing() {
        root.addView(view(targetId, "emoji"));
        hider(null);
        hider.install(activity.getApplication(), false);
        show();

        controller.pause().resume();

        assertEquals(0, hider.sessionsForTests());
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        TextView target = view(targetId, "emoji");
        root.addView(target);
        show();
        AtomicBoolean broken = new AtomicBoolean(false);
        hider(null, () -> {
            if (broken.get()) throw new IllegalStateException("settings broke");
            return true;
        }).watch(activity);
        assertTrue(frame());

        broken.set(true);
        target.setVisibility(View.VISIBLE);

        assertFalse("a frame whose check throws goes ahead", frame());
        assertEquals("and the view is as Instagram set it", View.VISIBLE, target.getVisibility());
        hider.watch(activity);
    }
}
