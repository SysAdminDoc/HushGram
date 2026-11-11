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
import android.view.ViewStub;
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
 * while it holds the focus, and the switch off or a pause puts it back. The Like, Comment and Share
 * buttons (#618) each go gone on a switch of their own, the rest of the footer closes up, and the
 * frame that moved them is the only one cancelled.
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
    private View comment;
    private View share;
    private int footerId;
    private int boxId;
    private int oldBoxId;
    private int likeId;
    private int commentId;
    private int shareId;
    private int shareStubId;
    private int likeClicks;
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
        likeId = View.generateViewId();
        commentId = View.generateViewId();
        shareId = View.generateViewId();
        shareStubId = View.generateViewId();
        StoryReplyBox.putIdForTests(activity, "toolbar_like_container", likeId);
        StoryReplyBox.putIdForTests(activity, "toolbar_comment_button", commentId);
        StoryReplyBox.putIdForTests(activity, "toolbar_reshare_button", shareId);
        StoryReplyBox.putIdForTests(activity, "toolbar_reshare_button_stub", shareStubId);
        viewerView = new FrameLayout(activity);
        footer = footer();
        viewerView.addView(footer, new FrameLayout.LayoutParams(720, 120, Gravity.BOTTOM));
        box = (FrameLayout) footer.getChildAt(0);
        field = (EditText) box.getChildAt(0);
        like = footer.getChildAt(1);
        comment = footer.getChildAt(2);
        share = footer.getChildAt(3);
        like.setOnClickListener(v -> likeClicks++);
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
        resetButtonSwitches();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryReplyBox.resetForTests();
        Settings.HIDE_STORY_REPLY_BOX.resetToDefault();
        resetButtonSwitches();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static void resetButtonSwitches() {
        Settings.HIDE_STORY_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_STORY_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_STORY_SHARE_BUTTON.resetToDefault();
    }

    /** A footer the way 454 lays one out: the box holding its text field, then like, comment and share. */
    private LinearLayout footer() {
        LinearLayout row = new LinearLayout(activity);
        row.setId(footerId);
        FrameLayout composer = new FrameLayout(activity);
        composer.setId(boxId);
        composer.addView(new EditText(activity));
        row.addView(composer, new LinearLayout.LayoutParams(0, 120, 1f));
        FrameLayout heart = new FrameLayout(activity);
        heart.setId(likeId);
        heart.setFocusable(true);
        heart.setFocusableInTouchMode(true);
        heart.addView(new View(activity));
        row.addView(heart, new LinearLayout.LayoutParams(120, 120));
        View bubble = new View(activity);
        bubble.setId(commentId);
        row.addView(bubble, new LinearLayout.LayoutParams(120, 120));
        View plane = new View(activity);
        plane.setId(shareId);
        row.addView(plane, new LinearLayout.LayoutParams(120, 120));
        return row;
    }

    /** Lays the footer out again, the way the frame after a cancelled one does. */
    private void layOut() {
        footer.requestLayout();
        idle();
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

    @Test
    public void hidingLikeTakesTheHeartAwayAndTheRestCloseUp() {
        int boxEnd = box.getRight();
        int likeAt = like.getLeft();
        int commentAt = comment.getLeft();
        int shareAt = share.getLeft();
        assertTrue("the test lays the footer out", likeAt > 0);
        Settings.HIDE_STORY_LIKE_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        assertTrue("the frame that moved the heart doesn't draw the old layout", frame());
        assertEquals(View.GONE, like.getVisibility());
        assertFalse("the next frame draws", frame());
        layOut();
        assertEquals("the box closes the gap", boxEnd + like.getLayoutParams().width, box.getRight());
        assertEquals("comment stays put", commentAt, comment.getLeft());
        assertEquals("share stays put", shareAt, share.getLeft());
        assertEquals("the reply box stays", View.VISIBLE, box.getVisibility());
        assertEquals(View.VISIBLE, comment.getVisibility());
        assertEquals(View.VISIBLE, share.getVisibility());
        assertEquals("no tap reaches a gone heart", 0, likeClicks);
        assertFalse("a gone heart takes no taps", like.isShown());
        assertTrue(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(), HookStatus.missing(FamilyNames.STORY_REPLY_BOX).isEmpty());

        Settings.HIDE_STORY_LIKE_BUTTON.save(false);
        assertTrue("putting the heart back also skips the old layout", frame());
        assertEquals(View.VISIBLE, like.getVisibility());
        assertFalse(frame());
        layOut();
        assertEquals("back in its place", likeAt, like.getLeft());
        assertEquals("never clicked", 0, likeClicks);
    }

    @Test
    public void commentAndShareGoOnTheirOwnSwitches() {
        Settings.HIDE_STORY_COMMENT_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.VISIBLE, share.getVisibility());
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        frame();
        assertEquals(View.GONE, share.getVisibility());
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.VISIBLE, box.getVisibility());
        Settings.HIDE_STORY_COMMENT_BUTTON.save(false);
        frame();
        assertEquals(View.VISIBLE, comment.getVisibility());
        assertEquals("share stays gone", View.GONE, share.getVisibility());
    }

    @Test
    public void allFourTogetherLeaveAnEmptyFooter() {
        Settings.HIDE_STORY_REPLY_BOX.save(true);
        Settings.HIDE_STORY_LIKE_BUTTON.save(true);
        Settings.HIDE_STORY_COMMENT_BUTTON.save(true);
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        assertEquals(View.INVISIBLE, box.getVisibility());
        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(View.GONE, share.getVisibility());
        Settings.HIDE_STORY_REPLY_BOX.save(false);
        frame();
        assertEquals("the box comes back on its own switch", View.VISIBLE, box.getVisibility());
        assertEquals(View.GONE, like.getVisibility());
    }

    @Test
    public void aButtonInstagramShowsAgainIsHiddenAgainWithoutCancellingTwoFramesInARow() {
        Settings.HIDE_STORY_LIKE_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        assertTrue(frame());
        like.setVisibility(View.VISIBLE);
        assertFalse("never two cancelled frames in a row", frame());
        assertEquals(View.GONE, like.getVisibility());
        like.setVisibility(View.VISIBLE);
        assertTrue("a later rebind skips its old layout again", frame());
        assertEquals(View.GONE, like.getVisibility());
    }

    @Test
    public void aButtonInstagramMadeInvisibleGoesBackToInvisible() {
        comment.setVisibility(View.INVISIBLE);
        Settings.HIDE_STORY_COMMENT_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        assertEquals("no space kept for it", View.GONE, comment.getVisibility());
        Settings.HIDE_STORY_COMMENT_BUTTON.save(false);
        frame();
        assertEquals(View.INVISIBLE, comment.getVisibility());
    }

    @Test
    public void aPauseOrAResumeWithEverythingOffPutsTheButtonsBack() {
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        assertEquals(View.GONE, share.getVisibility());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertEquals(View.VISIBLE, share.getVisibility());
        assertTrue("the saved choice is kept", Settings.HIDE_STORY_SHARE_BUTTON.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        frame();
        assertEquals(View.GONE, share.getVisibility());
        Settings.HIDE_STORY_SHARE_BUTTON.save(false);
        StoryReplyBox.resumed(viewer);
        assertEquals("put back at once", View.VISIBLE, share.getVisibility());
        assertFalse(StoryReplyBox.keptForTests());
    }

    @Test
    public void aShareButtonStillAStubIsFollowedToTheViewItInflates() {
        footer.removeView(share);
        ViewStub stub = new ViewStub(activity, android.R.layout.simple_list_item_1);
        stub.setId(shareStubId);
        footer.addView(stub, new LinearLayout.LayoutParams(120, 120));
        layOut();
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        assertFalse("a stub has nothing to move", frame());
        View plane = stub.inflate();
        assertTrue("the inflated view carries another id", plane.getId() != shareId && plane.getId() != shareStubId);
        assertTrue(frame());
        assertEquals(View.GONE, plane.getVisibility());
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.VISIBLE, comment.getVisibility());
    }

    @Test
    public void aBuildWithoutTheHeartIsReportedOnlyWhileItsSwitchIsOn() {
        StoryReplyBox.putIdForTests(activity, "toolbar_like_container", 0);
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        assertFalse(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(),
                HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString().contains("toolbar_like_container"));
        Settings.HIDE_STORY_LIKE_BUTTON.save(true);
        frame();
        assertTrue(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(),
                HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString().contains("toolbar_like_container"));
        assertEquals("a heart the build doesn't name stays", View.VISIBLE, like.getVisibility());
    }
}
