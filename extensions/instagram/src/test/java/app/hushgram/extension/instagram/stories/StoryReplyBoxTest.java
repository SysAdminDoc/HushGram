/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Hide the reply box on stories (#486): with the switch on, a story's Send message box goes
 * invisible before a frame draws it while the like and share buttons beside it stay put, it shows
 * while it holds the focus, and the switch off or a pause puts it back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryReplyBoxTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout viewerView;
    private LinearLayout footer;
    private FrameLayout box;
    private EditText field;
    private View like;
    private View share;
    private int footerId;
    private int boxId;
    private int oldBoxId;
    private final Object viewer = new Object();

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        StoryReplyBox.resetForTests();
        footerId = View.generateViewId();
        boxId = View.generateViewId();
        oldBoxId = View.generateViewId();
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.FOOTER, footerId);
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.BOX, boxId);
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.OLD_BOX, oldBoxId);
        viewerView = new FrameLayout(activity);
        footer = footer();
        viewerView.addView(footer, new FrameLayout.LayoutParams(720, 120, Gravity.BOTTOM));
        box = (FrameLayout) footer.getChildAt(0);
        field = (EditText) box.getChildAt(0);
        like = footer.getChildAt(1);
        share = footer.getChildAt(2);
        activity.setContentView(viewerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        StoryReplyBox.readerForTests(of -> of == viewer ? viewerView : null);
        idle();
        // A phone is in touch mode once a finger has been on the screen. A Robolectric window starts
        // out of it, and there a view turning VISIBLE takes the focus, which no phone does.
        new Instrumentation().setInTouchMode(true);
        ReflectionHelpers.callInstanceMethod(ReflectionHelpers.callInstanceMethod(viewerView, "getViewRootImpl"),
                "ensureTouchMode", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        idle();
        assertTrue("in touch mode, as on a phone", viewerView.isInTouchMode());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_STORY_REPLY_BOX.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryReplyBox.resetForTests();
        Settings.HIDE_STORY_REPLY_BOX.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    /** A footer the way 453 lays one out: the box holding its text field, then like, then share. */
    private LinearLayout footer() {
        LinearLayout row = new LinearLayout(activity);
        row.setId(footerId);
        FrameLayout composer = new FrameLayout(activity);
        composer.setId(boxId);
        composer.addView(new EditText(activity));
        row.addView(composer, new LinearLayout.LayoutParams(0, 120, 1f));
        View heart = new View(activity);
        heart.setFocusable(true);
        heart.setFocusableInTouchMode(true);
        row.addView(heart, new LinearLayout.LayoutParams(120, 120));
        row.addView(new View(activity), new LinearLayout.LayoutParams(120, 120));
        return row;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** One frame: the layout pass's listeners, then the pre-draw's. Answers whether a listener cancelled it. */
    private boolean frame() {
        ViewTreeObserver tree = viewerView.getViewTreeObserver();
        tree.dispatchOnGlobalLayout();
        return tree.dispatchOnPreDraw();
    }

    private void hidden() {
        Settings.HIDE_STORY_REPLY_BOX.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        assertEquals("the box is hidden", View.INVISIBLE, box.getVisibility());
    }

    @Test
    public void offToStartLeavesTheBoxAlone() {
        StoryReplyBox.resumed(viewer);
        assertFalse(frame());
        assertEquals(View.VISIBLE, box.getVisibility());
        assertFalse("no keeper while the switch was never on", StoryReplyBox.keptForTests());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.STORY_REPLY_BOX + ": invoked 1"));
    }

    @Test
    public void onHidesTheBoxAndLeavesLikeAndShareWhereTheyWere() {
        int likeAt = like.getLeft();
        int shareAt = share.getLeft();
        assertTrue("the test lays the footer out", likeAt > 0);
        hidden();
        assertFalse("the frame goes on", frame());
        footer.requestLayout();
        idle();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.VISIBLE, share.getVisibility());
        assertEquals("like stays put", likeAt, like.getLeft());
        assertEquals("share stays put", shareAt, share.getLeft());
        assertEquals("the box keeps its place", View.INVISIBLE, box.getVisibility());
        assertTrue(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(), HookStatus.missing(FamilyNames.STORY_REPLY_BOX).isEmpty());
    }

    @Test
    public void aFocusedBoxShowsUntilItLetsGo() {
        hidden();
        assertTrue("the field takes the focus", field.requestFocus());
        frame();
        assertEquals("shown while typing", View.VISIBLE, box.getVisibility());
        assertTrue(like.requestFocus());
        frame();
        assertEquals("hidden again once the focus moves on", View.INVISIBLE, box.getVisibility());
    }

    @Test
    public void theSwitchGoingOffPutsTheBoxBack() {
        hidden();
        Settings.HIDE_STORY_REPLY_BOX.save(false);
        frame();
        assertEquals(View.VISIBLE, box.getVisibility());
    }

    @Test
    public void aPausePutsTheBoxBack() {
        hidden();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertEquals(View.VISIBLE, box.getVisibility());
        assertTrue("the saved choice is kept", Settings.HIDE_STORY_REPLY_BOX.savedValue());
    }

    @Test
    public void aResumeWithTheSwitchOffLetsTheKeeperGo() {
        hidden();
        Settings.HIDE_STORY_REPLY_BOX.save(false);
        StoryReplyBox.resumed(viewer);
        assertEquals("put back at once", View.VISIBLE, box.getVisibility());
        assertFalse(StoryReplyBox.keptForTests());
        Settings.HIDE_STORY_REPLY_BOX.save(true);
        frame();
        assertEquals("nothing listens until the next resume", View.VISIBLE, box.getVisibility());
    }

    @Test
    public void anotherViewerPutsTheLastOnesBoxBack() {
        hidden();
        Object other = new Object();
        FrameLayout otherView = new FrameLayout(activity);
        StoryReplyBox.readerForTests(of -> of == other ? otherView : of == viewer ? viewerView : null);
        StoryReplyBox.resumed(other);
        assertEquals(View.VISIBLE, box.getVisibility());
        assertTrue(StoryReplyBox.keptForTests());
    }

    @Test
    public void aBoxInstagramShowsAgainIsHiddenAgain() {
        hidden();
        box.setVisibility(View.VISIBLE);
        frame();
        assertEquals(View.INVISIBLE, box.getVisibility());
    }

    @Test
    public void aBoxInstagramTookAwayStaysAway() {
        hidden();
        box.setVisibility(View.GONE);
        frame();
        assertEquals("not shown by the keeper", View.GONE, box.getVisibility());
        Settings.HIDE_STORY_REPLY_BOX.save(false);
        frame();
        assertEquals("nor put back", View.GONE, box.getVisibility());
    }

    @Test
    public void aStoryBoundLaterIsFoundAfterItsLayout() {
        viewerView.removeView(footer);
        Settings.HIDE_STORY_REPLY_BOX.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        LinearLayout later = footer();
        viewerView.addView(later, new FrameLayout.LayoutParams(720, 120, Gravity.BOTTOM));
        frame();
        assertEquals(View.INVISIBLE, later.getChildAt(0).getVisibility());
        assertEquals(View.VISIBLE, later.getChildAt(1).getVisibility());
    }

    @Test
    public void theOlderLayoutsBoxIsHiddenToo() {
        View old = new View(activity);
        old.setId(oldBoxId);
        viewerView.addView(old, new FrameLayout.LayoutParams(720, 120, Gravity.TOP));
        hidden();
        assertEquals(View.INVISIBLE, old.getVisibility());
        Settings.HIDE_STORY_REPLY_BOX.save(false);
        frame();
        assertEquals(View.VISIBLE, old.getVisibility());
    }

    @Test
    public void aBuildWithoutTheBoxIsReportedAndDrawsOn() {
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.BOX, 0);
        Settings.HIDE_STORY_REPLY_BOX.save(true);
        StoryReplyBox.resumed(viewer);
        assertFalse(frame());
        assertEquals(View.VISIBLE, box.getVisibility());
        assertTrue(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(),
                HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString().contains(StoryReplyBox.BOX));
    }

    @Test
    public void aDetachedViewerStopsListeningAndPicksUpOnReattach() {
        hidden();
        box.setVisibility(View.VISIBLE);
        ViewTreeObserver attached = viewerView.getViewTreeObserver();
        activity.setContentView(new FrameLayout(activity));
        idle();
        attached.dispatchOnPreDraw();
        assertEquals("a detached viewer isn't watched", View.VISIBLE, box.getVisibility());
        activity.setContentView(viewerView);
        idle();
        frame();
        assertEquals(View.INVISIBLE, box.getVisibility());
    }
}
