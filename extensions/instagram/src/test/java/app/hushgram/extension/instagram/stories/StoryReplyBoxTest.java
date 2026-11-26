/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.widget.EditText;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.instagram.misc.AccentHue;
import app.hushgram.extension.instagram.misc.AccentTheme;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.ViewWatcher;
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
    /** The ids of {@link StoryReplyBox#COMMENTS}, in its order. */
    private final int[] commentsIds = new int[StoryReplyBox.COMMENTS.length];
    private int commentLineId;
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
        for (int i = 0; i < commentsIds.length; i++) {
            commentsIds[i] = View.generateViewId();
            StoryReplyBox.putIdForTests(activity, StoryReplyBox.COMMENTS[i], commentsIds[i]);
        }
        commentLineId = View.generateViewId();
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.COMMENT_LINE, commentLineId);
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
        StoryReplyBox.unwatch(activity);
        StoryReplyBox.resetForTests();
        Settings.HIDE_STORY_REPLY_BOX.resetToDefault();
        resetButtonSwitches();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static void resetButtonSwitches() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_ICONS.resetToDefault();
        Settings.HIDE_STORY_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_STORY_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_STORY_SHARE_BUTTON.resetToDefault();
        Settings.HIDE_STORY_COMMENTS.resetToDefault();
        Settings.STORY_COMMENT_LINE.resetToDefault();
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
    public void aStoryOpenedFromARingHidesTheHeartFromItsFirstFrame() {
        activity.setContentView(new FrameLayout(activity));
        idle();
        Settings.HIDE_STORY_LIKE_BUTTON.save(true);
        StoryReplyBox.viewCreated(viewer);
        assertTrue("the viewer is watched before it's on a window", StoryReplyBox.keptForTests());
        activity.setContentView(viewerView);
        assertTrue(viewerView.isAttachedToWindow());
        assertEquals("nothing changes before a frame", View.VISIBLE, like.getVisibility());
        assertTrue("the first frame doesn't draw the heart", frame());
        assertEquals(View.GONE, like.getVisibility());
        StoryReplyBox.resumed(viewer);
        assertFalse("resuming later adds no second keeper", frame());
        assertEquals(View.GONE, like.getVisibility());
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

    /**
     * Icons in the accent color (#187): with no hiding switch on, the keeper still gives the heart (an
     * icon inside its container) and the share icon the picked shade for each frame, a liked heart with
     * no tint keeps its own, an icon Instagram tints white again takes the shade again, a hidden button
     * is left alone, and the switch off or a pause puts Instagram's tint back.
     */
    @Test
    public void theIconsTakeThePickedColorWithIconsInTheAccentColor() {
        ColorStateList white = new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}}, new int[] {0x4dffffff, 0xffffffff});
        ImageView heart = new ImageView(activity);
        heart.setImageTintList(white);
        ((ViewGroup) like).addView(heart);
        ImageView plane = new ImageView(activity);
        plane.setId(shareId);
        plane.setImageTintList(white);
        footer.removeView(share);
        footer.addView(plane, new LinearLayout.LayoutParams(120, 120));
        ColorStateList commentTint = ColorStateList.valueOf(0xffff3040);
        ImageView bubble = new ImageView(activity);
        bubble.setId(commentId);
        bubble.setImageTintList(commentTint);
        footer.removeView(comment);
        footer.addView(bubble, 2, new LinearLayout.LayoutParams(120, 120));
        layOut();

        StoryReplyBox.resumed(viewer);
        frame();
        assertFalse("nothing to do with the accent off", StoryReplyBox.keptForTests());
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_ICONS.save(true);
        int shade = AccentTheme.storyButton(white).getDefaultColor();
        assertTrue("a shade of its own", shade != 0xffffffff);
        StoryReplyBox.resumed(viewer);
        assertFalse("tinting moves nothing, so the frame draws", frame());
        assertEquals("the heart", shade, heart.getImageTintList().getDefaultColor());
        assertEquals("its disabled look keeps its alpha", 0x4d000000 | (shade & 0xffffff), heart.getImageTintList().getColorForState(new int[0], 0));
        assertEquals("share", shade, plane.getImageTintList().getDefaultColor());
        assertSame("a color of its own stays", commentTint, bubble.getImageTintList());
        ColorStateList ours = heart.getImageTintList();
        frame();
        assertSame("tinted once", ours, heart.getImageTintList());

        heart.setImageTintList(null);
        frame();
        assertEquals("a liked heart keeps Instagram's red", null, heart.getImageTintList());
        heart.setImageTintList(white);
        frame();
        assertEquals("unliked, it takes the shade again", shade, heart.getImageTintList().getDefaultColor());

        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        plane.setImageTintList(white);
        frame();
        assertSame("a hidden button is left alone", white, plane.getImageTintList());
        Settings.HIDE_STORY_SHARE_BUTTON.save(false);
        frame();
        frame();
        assertEquals("shown again, tinted again", shade, plane.getImageTintList().getDefaultColor());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertSame("paused, Instagram's tint is back", white, heart.getImageTintList());
        assertSame(white, plane.getImageTintList());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        frame();
        assertEquals("resumed", shade, heart.getImageTintList().getDefaultColor());
        Settings.ACCENT_ICONS.save(false);
        frame();
        assertSame("the switch off puts it back", white, heart.getImageTintList());
        assertSame(white, plane.getImageTintList());
        assertSame(commentTint, bubble.getImageTintList());
    }

    /**
     * A story's More actions dots take the picked color with Icons in the accent color on (#187): as a
     * filter while Instagram draws them with no tint or filter, through their tint when they have one,
     * and the switch off puts Instagram's look back. A filter of Instagram's own is left alone.
     */
    @Test
    public void theMoreActionsDotsTakeThePickedColor() {
        int moreId = View.generateViewId();
        StoryReplyBox.putIdForTests(activity, "mimicry_top_right_hamburger", moreId);
        FrameLayout header = new FrameLayout(activity);
        ImageView dots = new ImageView(activity);
        dots.setId(moreId);
        header.addView(dots, new FrameLayout.LayoutParams(96, 96));
        viewerView.addView(header, new FrameLayout.LayoutParams(720, 140, Gravity.TOP));
        layOut();
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_ICONS.save(true);
        int shade = AccentTheme.storyButton(ColorStateList.valueOf(0xffffffff)).getDefaultColor();
        assertTrue("a shade of its own", shade != 0xffffffff);

        StoryReplyBox.resumed(viewer);
        frame();
        assertEquals("drawn with no tint, a filter of the shade", new PorterDuffColorFilter(shade, PorterDuff.Mode.SRC_IN), dots.getColorFilter());
        ColorFilter ours = dots.getColorFilter();
        frame();
        assertSame("filtered once", ours, dots.getColorFilter());
        Settings.ACCENT_ICONS.save(false);
        frame();
        assertNull("the switch off takes the filter away", dots.getColorFilter());

        ColorFilter instagrams = new PorterDuffColorFilter(0xff000000, PorterDuff.Mode.SRC_IN);
        dots.setColorFilter(instagrams);
        Settings.ACCENT_ICONS.save(true);
        frame();
        assertSame("a filter of Instagram's own stays", instagrams, dots.getColorFilter());

        dots.clearColorFilter();
        ColorStateList white = ColorStateList.valueOf(0xffffffff);
        dots.setImageTintList(white);
        frame();
        assertEquals("tinted, the tint takes the shade", shade, dots.getImageTintList().getDefaultColor());
        assertNull(dots.getColorFilter());
        Settings.ACCENT_ICONS.save(false);
        frame();
        assertSame("the switch off puts the tint back", white, dots.getImageTintList());
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

    /**
     * The copy of a story's first page 454 adds straight to the activity's content as a ring opens
     * it, before the viewer exists: plain frames, then the page holding its footer [depth] levels down.
     */
    private FrameLayout copyOf(int pageId, LinearLayout pageFooter, int depth) {
        LinearLayout page = new LinearLayout(activity);
        page.setId(pageId);
        page.addView(pageFooter, new LinearLayout.LayoutParams(720, 120));
        View inner = page;
        for (int level = 0; level < depth; level++) {
            FrameLayout frame = new FrameLayout(activity);
            frame.addView(inner);
            inner = frame;
        }
        return (FrameLayout) inner;
    }

    private ViewGroup content() {
        return activity.findViewById(android.R.id.content);
    }

    @Test
    public void aCopyARingOpensWithIsHiddenFromItsFirstFrame() {
        int pageId = View.generateViewId();
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.OPENING, pageId);
        Settings.HIDE_STORY_REPLY_BOX.save(true);
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        LinearLayout copyFooter = footer();
        content().addView(copyOf(pageId, copyFooter, 2), new FrameLayout.LayoutParams(720, 1280));
        assertTrue("the frame it was added in waits for the footer to close up",
                StoryReplyBox.OPENINGS.preDraw(activity, true));
        assertEquals("its box is hidden", View.INVISIBLE, copyFooter.getChildAt(0).getVisibility());
        assertEquals("its plane is gone", View.GONE, copyFooter.getChildAt(3).getVisibility());
        assertEquals("its heart stays", View.VISIBLE, copyFooter.getChildAt(1).getVisibility());
        assertEquals(1, StoryReplyBox.openingsForTests());
        assertFalse("a copy already kept isn't taken again", StoryReplyBox.OPENINGS.preDraw(activity, true));
        assertEquals(1, StoryReplyBox.openingsForTests());
        assertFalse("the next frame draws", frame());
        copyFooter.getChildAt(3).setVisibility(View.VISIBLE);
        assertTrue("its own keeper has the frames from here", frame());
        assertEquals(View.GONE, copyFooter.getChildAt(3).getVisibility());
        assertEquals("the viewer's footer is left to the viewer's keeper", View.VISIBLE, box.getVisibility());
    }

    @Test
    public void aStoryPageDeepInTheAppsOwnTreeIsNoCopy() {
        int pageId = View.generateViewId();
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.OPENING, pageId);
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        LinearLayout deepFooter = footer();
        content().addView(copyOf(pageId, deepFooter, StoryReplyBox.OPENING_DEPTH + 1),
                new FrameLayout.LayoutParams(720, 1280));
        assertFalse(StoryReplyBox.OPENINGS.preDraw(activity, true));
        assertEquals(0, StoryReplyBox.openingsForTests());
        assertEquals(View.VISIBLE, deepFooter.getChildAt(3).getVisibility());
    }

    @Test
    public void onlyAnActivityResumedWithASwitchOnLooksForCopies() {
        StoryReplyBox.watch(activity);
        assertFalse("nothing on, nothing watched", ViewWatcher.joined(activity, StoryReplyBox.OPENINGS));
        Settings.HIDE_STORY_LIKE_BUTTON.save(true);
        StoryReplyBox.watch(activity);
        assertTrue(ViewWatcher.joined(activity, StoryReplyBox.OPENINGS));
        Settings.HIDE_STORY_LIKE_BUTTON.save(false);
        assertFalse(StoryReplyBox.OPENINGS.preDraw(activity, true));
        assertFalse("it leaves once every switch is off", ViewWatcher.joined(activity, StoryReplyBox.OPENINGS));
    }

    /**
     * The comments 457 floats over a story (#826): the bubbles, the faces of who wrote them and the
     * newest one's preview, on the story's page under the viewer rather than in its footer.
     */
    private FrameLayout floatingComments() {
        FrameLayout page = new FrameLayout(activity);
        FrameLayout bubbles = new FrameLayout(activity);
        bubbles.setId(commentsIds[0]);
        bubbles.addView(new View(activity));
        page.addView(bubbles, new FrameLayout.LayoutParams(600, 300));
        View faces = new View(activity);
        faces.setId(commentsIds[1]);
        page.addView(faces, new FrameLayout.LayoutParams(200, 60));
        View preview = new View(activity);
        preview.setId(commentsIds[2]);
        page.addView(preview, new FrameLayout.LayoutParams(600, 80));
        viewerView.addView(page, 0, new FrameLayout.LayoutParams(720, 1000));
        idle();
        return page;
    }

    private static void assertComments(String why, int visibility, ViewGroup page) {
        for (int i = 0; i < page.getChildCount(); i++) {
            assertEquals(why + ": " + StoryReplyBox.COMMENTS[i], visibility, page.getChildAt(i).getVisibility());
        }
    }

    @Test
    public void hidingCommentsTakesTheFloatingCommentsOffAndLeavesTheFooter() {
        FrameLayout page = floatingComments();
        StoryReplyBox.resumed(viewer);
        frame();
        assertComments("off to start", View.VISIBLE, page);
        Settings.HIDE_STORY_COMMENTS.save(true);
        StoryReplyBox.resumed(viewer);
        assertFalse("nothing moves, so the frame draws", frame());
        assertComments("hidden in their place", View.INVISIBLE, page);
        assertEquals(View.VISIBLE, box.getVisibility());
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals("the Comment button has its own switch", View.VISIBLE, comment.getVisibility());
        assertEquals(View.VISIBLE, share.getVisibility());
        assertTrue(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(), HookStatus.missing(FamilyNames.STORY_REPLY_BOX).isEmpty());
    }

    @Test
    public void commentsShownAgainAreHiddenAgainUntilTheSwitchGoesOff() {
        FrameLayout page = floatingComments();
        Settings.HIDE_STORY_COMMENTS.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        View bubbles = page.getChildAt(0);
        bubbles.setVisibility(View.VISIBLE);
        assertFalse(frame());
        assertEquals("Instagram showing them again doesn't last a frame", View.INVISIBLE, bubbles.getVisibility());
        Settings.HIDE_STORY_COMMENTS.save(false);
        frame();
        assertComments("the switch off puts them back", View.VISIBLE, page);
    }

    @Test
    public void theReplyBoxSwitchLeavesTheCommentsAndAPausePutsThemBack() {
        FrameLayout page = floatingComments();
        hidden();
        assertComments("the reply box switch leaves them", View.VISIBLE, page);
        Settings.HIDE_STORY_COMMENTS.save(true);
        frame();
        assertComments("hidden on their own switch", View.INVISIBLE, page);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertComments("a pause puts them back", View.VISIBLE, page);
        assertEquals(View.VISIBLE, box.getVisibility());
        assertTrue("the saved choice is kept", Settings.HIDE_STORY_COMMENTS.savedValue());
    }

    @Test
    public void commentsStillAStubAreFollowedToTheViewTheyInflate() {
        FrameLayout page = new FrameLayout(activity);
        ViewStub stub = new ViewStub(activity, android.R.layout.simple_list_item_1);
        stub.setId(commentsIds[0]);
        page.addView(stub, new FrameLayout.LayoutParams(600, 300));
        viewerView.addView(page, 0, new FrameLayout.LayoutParams(720, 1000));
        idle();
        Settings.HIDE_STORY_COMMENTS.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        View bubbles = stub.inflate();
        assertTrue("the inflated view carries another id", bubbles.getId() != commentsIds[0]);
        frame();
        assertEquals(View.INVISIBLE, bubbles.getVisibility());
    }

    @Test
    public void aBuildWithoutTheBubblesIsReportedOnlyWhileTheCommentsSwitchIsOn() {
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.COMMENTS[0], 0);
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        assertFalse(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(),
                HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString().contains(StoryReplyBox.COMMENTS[0]));
        Settings.HIDE_STORY_COMMENTS.save(true);
        frame();
        assertTrue(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(),
                HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString().contains(StoryReplyBox.COMMENTS[0]));
    }

    @Test
    public void theCommentsSwitchAloneWatchesForCopies() {
        Settings.HIDE_STORY_COMMENTS.save(true);
        StoryReplyBox.watch(activity);
        assertTrue(ViewWatcher.joined(activity, StoryReplyBox.OPENINGS));
    }

    /** The line 457 shows for writing a comment (#826), on the story's page under the viewer, not in its footer. */
    private View commentLine() {
        FrameLayout page = new FrameLayout(activity);
        View line = new View(activity);
        line.setId(commentLineId);
        page.addView(line, new FrameLayout.LayoutParams(600, 80, Gravity.BOTTOM));
        viewerView.addView(page, 0, new FrameLayout.LayoutParams(720, 1000));
        idle();
        return line;
    }

    private float up(StoryCommentLine height) {
        return -height.px(activity.getResources().getDisplayMetrics().density);
    }

    @Test
    public void theCommentLineGoesUpByTheHeightChosenAndBackOnInstagramsPlace() {
        View line = commentLine();
        int top = line.getTop();
        StoryReplyBox.resumed(viewer);
        frame();
        assertEquals("Instagram's place to start", 0f, line.getTranslationY(), 0f);
        assertFalse("no keeper for Instagram's place", StoryReplyBox.keptForTests());
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.HIGHER);
        StoryReplyBox.resumed(viewer);
        assertFalse("nothing is laid out again, so the frame draws", frame());
        assertTrue(up(StoryCommentLine.HIGHER) < 0f);
        assertEquals(up(StoryCommentLine.HIGHER), line.getTranslationY(), 0f);
        assertEquals("its place in the layout is Instagram's", top, line.getTop());
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.HIGHEST);
        frame();
        assertEquals("a new height moves it on", up(StoryCommentLine.HIGHEST), line.getTranslationY(), 0f);
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.INSTAGRAM);
        frame();
        assertEquals("Instagram's place puts it back while another switch keeps the keeper", 0f, line.getTranslationY(), 0f);
        assertEquals(View.VISIBLE, box.getVisibility());
        assertEquals(View.VISIBLE, line.getVisibility());
    }

    @Test
    public void aMoveOfInstagramsOwnWinsAndTheLineGoesUpAgainOnceItRests() {
        View line = commentLine();
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.HIGHER);
        StoryReplyBox.resumed(viewer);
        frame();
        line.setTranslationY(-300f);
        frame();
        assertEquals("Instagram moved it itself", -300f, line.getTranslationY(), 0f);
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.INSTAGRAM);
        frame();
        assertEquals("putting it back leaves Instagram's move alone", -300f, line.getTranslationY(), 0f);
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.HIGHER);
        StoryReplyBox.resumed(viewer);
        line.setTranslationY(0f);
        frame();
        assertEquals("at rest again, so up it goes", up(StoryCommentLine.HIGHER), line.getTranslationY(), 0f);
    }

    @Test
    public void aPausePutsTheLineBackAndKeepsTheChoice() {
        View line = commentLine();
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.HIGHEST);
        StoryReplyBox.resumed(viewer);
        frame();
        assertEquals(up(StoryCommentLine.HIGHEST), line.getTranslationY(), 0f);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertEquals("a pause puts it back", 0f, line.getTranslationY(), 0f);
        assertEquals(StoryCommentLine.HIGHEST, Settings.STORY_COMMENT_LINE.savedValue());
    }

    @Test
    public void aCommentLineStillAStubIsFollowedToTheViewItInflates() {
        FrameLayout page = new FrameLayout(activity);
        ViewStub stub = new ViewStub(activity, android.R.layout.simple_list_item_1);
        stub.setId(commentLineId);
        page.addView(stub, new FrameLayout.LayoutParams(600, 80, Gravity.BOTTOM));
        viewerView.addView(page, 0, new FrameLayout.LayoutParams(720, 1000));
        idle();
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.HIGHER);
        StoryReplyBox.resumed(viewer);
        frame();
        View line = stub.inflate();
        assertTrue("the inflated view carries another id", line.getId() != commentLineId);
        frame();
        assertEquals(up(StoryCommentLine.HIGHER), line.getTranslationY(), 0f);
    }

    @Test
    public void aBuildWithoutTheLineIsReportedOnlyWhileAHeightIsChosen() {
        StoryReplyBox.putIdForTests(activity, StoryReplyBox.COMMENT_LINE, 0);
        Settings.HIDE_STORY_SHARE_BUTTON.save(true);
        StoryReplyBox.resumed(viewer);
        frame();
        assertFalse(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(),
                HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString().contains(StoryReplyBox.COMMENT_LINE));
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.HIGHER);
        frame();
        assertTrue(HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString(),
                HookStatus.missing(FamilyNames.STORY_REPLY_BOX).toString().contains(StoryReplyBox.COMMENT_LINE));
    }

    @Test
    public void aHeightAloneWatchesForCopies() {
        Settings.STORY_COMMENT_LINE.save(StoryCommentLine.HIGHER);
        StoryReplyBox.watch(activity);
        assertTrue(ViewWatcher.joined(activity, StoryReplyBox.OPENINGS));
    }
}
