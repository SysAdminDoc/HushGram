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
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Keep story buttons below the story (#598): at a large font the row along a story's bottom rides
 * up over the story while room under it stays empty. With the switch on, the toolbar holding the
 * row moves down by as much as the row covers the story, never past the page, and the switch off or
 * a pause puts it back.
 *
 * <p>The page here is 454's at font 1.3 scaled down: a 1000 px page, an 800 px story at its top, and
 * a toolbar 860 px tall whose 100 px row starts at 760, so the row covers 40 px of the story with
 * 140 px empty under it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryButtonsRoomTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout viewerView;
    private FrameLayout page;
    private View media;
    private FrameLayout toolbar;
    private View footer;
    private int pageId;
    private int mediaId;
    private int toolbarId;
    private int footerId;
    private final Object viewer = new Object();

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        StoryButtonsRoom.resetForTests();
        pageId = View.generateViewId();
        mediaId = View.generateViewId();
        toolbarId = View.generateViewId();
        footerId = View.generateViewId();
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.PAGE, pageId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.MEDIA, mediaId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.TOOLBAR, toolbarId);
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.FOOTER, footerId);
        viewerView = new FrameLayout(activity);
        page = page(800, 860);
        media = page.getChildAt(0);
        toolbar = (FrameLayout) page.getChildAt(1);
        footer = toolbar.getChildAt(0);
        viewerView.addView(page, new FrameLayout.LayoutParams(720, 1000));
        activity.setContentView(viewerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        StoryButtonsRoom.readerForTests(of -> of == viewer ? viewerView : null);
        idle();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_STORY_BUTTONS_BELOW.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryButtonsRoom.resetForTests();
        Settings.KEEP_STORY_BUTTONS_BELOW.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    /** A page the way 454 lays one out: the story at the top, then the toolbar with its row along its bottom. */
    private FrameLayout page(int storyHeight, int toolbarHeight) {
        FrameLayout holder = new FrameLayout(activity);
        holder.setId(pageId);
        View story = new View(activity);
        story.setId(mediaId);
        holder.addView(story, new FrameLayout.LayoutParams(720, storyHeight, Gravity.TOP));
        FrameLayout bar = new FrameLayout(activity);
        bar.setId(toolbarId);
        View row = new View(activity);
        row.setId(footerId);
        bar.addView(row, new FrameLayout.LayoutParams(720, 100, Gravity.BOTTOM));
        holder.addView(bar, new FrameLayout.LayoutParams(720, toolbarHeight, Gravity.TOP));
        return holder;
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

    private void resize(View view, int height) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.height = height;
        view.setLayoutParams(params);
        idle();
    }

    private void moved() {
        StoryButtonsRoom.resumed(viewer);
        assertFalse("the frame goes on", frame());
        assertEquals("the row moves off the story", 40f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void theRoomIsTheOverlapCappedByTheSpaceBelow() {
        assertEquals("covers 44, 113 below", 44, StoryButtonsRoom.shift(1920, 1876, 2057, 2170));
        assertEquals("covers 40, only 20 below", 20, StoryButtonsRoom.shift(800, 760, 860, 880));
        assertEquals("doesn't cover the story", 0, StoryButtonsRoom.shift(700, 760, 860, 1000));
        assertEquals("already past the page", 0, StoryButtonsRoom.shift(800, 760, 1010, 1000));
    }

    @Test
    public void onToStartMovesTheRowOffTheStory() {
        assertTrue("on by default", Settings.KEEP_STORY_BUTTONS_BELOW.get());
        assertEquals("the test lays the row over the story", 760, footer.getTop());
        moved();
        int[] row = new int[2];
        int[] story = new int[2];
        footer.getLocationInWindow(row);
        media.getLocationInWindow(story);
        assertEquals("the row starts where the story ends", story[1] + media.getHeight(), row[1]);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.STORY_BUTTONS_ROOM + ": invoked 1"));
        assertTrue(HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).toString(), HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).isEmpty());
    }

    @Test
    public void laterFramesKeepItThereWithoutCreepingFurther() {
        moved();
        toolbar.requestLayout();
        idle();
        frame();
        frame();
        assertEquals(40f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void neverPastThePagesBottom() {
        resize(page, 880);
        StoryButtonsRoom.resumed(viewer);
        frame();
        assertEquals("only the 20 px under the row", 20f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void aRowClearOfTheStoryStaysPut() {
        resize(media, 700);
        StoryButtonsRoom.resumed(viewer);
        frame();
        assertEquals(0f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void aStoryFillingThePageLeavesTheRowWhereItIs() {
        resize(media, 1000);
        resize(toolbar, 1000);
        StoryButtonsRoom.resumed(viewer);
        frame();
        assertEquals("no room under it", 0f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void aStoryThatGrowsShorterLetsTheRowBackUp() {
        moved();
        resize(media, 700);
        frame();
        assertEquals(0f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void instagramsOwnSlideWins() {
        moved();
        toolbar.setTranslationY(-300f);
        frame();
        assertEquals("the reply keyboard's slide is left alone", -300f, toolbar.getTranslationY(), 0f);
        Settings.KEEP_STORY_BUTTONS_BELOW.save(false);
        frame();
        assertEquals("and not put back when the switch goes off", -300f, toolbar.getTranslationY(), 0f);
        Settings.KEEP_STORY_BUTTONS_BELOW.save(true);
        toolbar.setTranslationY(0f);
        frame();
        assertEquals("back at rest, it moves again", 40f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void theSwitchGoingOffPutsTheRowBack() {
        moved();
        Settings.KEEP_STORY_BUTTONS_BELOW.save(false);
        frame();
        assertEquals(0f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void aPausePutsTheRowBack() {
        moved();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        frame();
        assertEquals(0f, toolbar.getTranslationY(), 0f);
        assertTrue("the saved choice is kept", Settings.KEEP_STORY_BUTTONS_BELOW.savedValue());
    }

    @Test
    public void aResumeWithTheSwitchOffLetsTheKeeperGo() {
        moved();
        Settings.KEEP_STORY_BUTTONS_BELOW.save(false);
        StoryButtonsRoom.resumed(viewer);
        assertEquals("put back at once", 0f, toolbar.getTranslationY(), 0f);
        assertFalse(StoryButtonsRoom.keptForTests());
    }

    @Test
    public void offToStartLeavesTheRowAlone() {
        Settings.KEEP_STORY_BUTTONS_BELOW.save(false);
        StoryButtonsRoom.resumed(viewer);
        assertFalse(frame());
        assertEquals(0f, toolbar.getTranslationY(), 0f);
        assertFalse("no keeper while the switch is off", StoryButtonsRoom.keptForTests());
    }

    @Test
    public void eachPageGetsItsOwn() {
        FrameLayout next = page(800, 860);
        viewerView.addView(next, new FrameLayout.LayoutParams(720, 880));
        idle();
        moved();
        assertEquals("the next page has less room", 20f, next.getChildAt(1).getTranslationY(), 0f);
    }

    @Test
    public void aHiddenRowIsLeftAlone() {
        footer.setVisibility(View.GONE);
        StoryButtonsRoom.resumed(viewer);
        frame();
        assertEquals(0f, toolbar.getTranslationY(), 0f);
    }

    @Test
    public void aBuildWithoutTheRowIsReportedAndDrawsOn() {
        StoryButtonsRoom.putIdForTests(activity, StoryButtonsRoom.FOOTER, 0);
        StoryButtonsRoom.resumed(viewer);
        assertFalse(frame());
        assertEquals(0f, toolbar.getTranslationY(), 0f);
        assertTrue(HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).toString(),
                HookStatus.missing(FamilyNames.STORY_BUTTONS_ROOM).toString().contains(StoryButtonsRoom.FOOTER));
    }

    @Test
    public void aDetachedViewerStopsListeningAndPicksUpOnReattach() {
        moved();
        toolbar.setTranslationY(0f);
        ViewTreeObserver attached = viewerView.getViewTreeObserver();
        activity.setContentView(new FrameLayout(activity));
        idle();
        attached.dispatchOnPreDraw();
        assertEquals("a detached viewer isn't watched", 0f, toolbar.getTranslationY(), 0f);
        activity.setContentView(viewerView);
        idle();
        frame();
        assertEquals(40f, toolbar.getTranslationY(), 0f);
    }
}
