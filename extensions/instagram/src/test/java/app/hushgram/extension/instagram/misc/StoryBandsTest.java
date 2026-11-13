/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * #666: in light mode the strips under the status bar and the navigation bar round a story were white,
 * so the bars' white icons were lost. While a story fills the window between the bars, both strips are
 * black, drawn over whatever Instagram's layouts paint there, and they're Instagram's again once the
 * story goes, a screen covers it or Ambient light glows there.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StoryBandsTest {
    private static final int STORY = 0x7f0b6661;
    private static final int GAP_TOP = 30;
    private static final int GAP_BOTTOM = 45;
    private static final int STORY_RED = 0xffcc2233;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private FrameLayout page;
    private View decor;
    private Map<String, Integer> ids;
    private Integer previousId;

    @Before
    public void prepare() {
        HookStatus.clear();
        ids = ReflectionHelpers.getStaticField(GlassTabBar.class, "IDS");
        synchronized (ids) { previousId = ids.put(GlassTabBar.STORY_ROOT, STORY); }
        controller = Robolectric.buildActivity(Activity.class).setup();
        // The layout round the story viewer, in the light theme's white, as Instagram's is.
        page = new FrameLayout(controller.get());
        page.setBackgroundColor(Color.WHITE);
        controller.get().setContentView(page);
        decor = controller.get().getWindow().getDecorView();
        StoryBands.resetForTests(null);
    }

    @After
    public void restore() {
        StoryBands.resetForTests(null);
        PauseForTests.resume();
        controller.close();
        synchronized (ids) {
            if (previousId == null) ids.remove(GlassTabBar.STORY_ROOT);
            else ids.put(GlassTabBar.STORY_ROOT, previousId);
        }
    }

    /** A story viewer in [parent], [top] and [bottom] pixels in from its edges. */
    private FrameLayout story(FrameLayout parent, int top, int bottom) {
        FrameLayout story = new FrameLayout(controller.get());
        story.setId(STORY);
        story.setBackgroundColor(STORY_RED);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        params.topMargin = top;
        params.bottomMargin = bottom;
        parent.addView(story, params);
        return story;
    }

    /** Lays the window out, gives it bars as tall as the gaps the story leaves, and checks it as a frame would. */
    private void settle(View story) {
        shadowOf(Looper.getMainLooper()).idle();
        int[] at = new int[2];
        story.getLocationInWindow(at);
        int bottom = at[1] + story.getHeight();
        StoryBands.resetForTests(new int[] {at[1], decor.getHeight() - bottom});
        StoryBands.track(decor);
        StoryBands.refresh(decor);
    }

    /** Lays the window out again once the story viewer is due to be looked for again. */
    private static void nextLook() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GlassTabBar.SHEET_LOOK_MS + 1));
    }

    private Bitmap drawn() {
        Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static Rect where(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    @Test
    public void aStoryBetweenTheBarsHasBothStripsBlack() {
        View story = story(page, GAP_TOP, GAP_BOTTOM);
        settle(story);
        Rect shown = where(story);
        int width = decor.getWidth(), height = decor.getHeight();
        assertEquals(Arrays.asList(new Rect(0, 0, width, shown.top), new Rect(0, shown.bottom, width, height)),
                StoryBands.covers(decor));

        Bitmap bitmap = drawn();
        assertEquals("under the status bar", Color.BLACK, bitmap.getPixel(width / 2, shown.top - GAP_TOP / 2));
        assertEquals("under the navigation bar", Color.BLACK, bitmap.getPixel(width / 2, shown.bottom + GAP_BOTTOM / 2));
        assertEquals("the story itself", STORY_RED, bitmap.getPixel(width / 2, shown.centerY()));
        assertTrue(String.join("\n", HookStatus.report()).contains(StoryBands.COVERED));
    }

    @Test
    public void theStripsAreInstagramsAgainOnceTheStoryGoes() {
        View story = story(page, GAP_TOP, GAP_BOTTOM);
        settle(story);
        Rect shown = where(story);
        page.removeView(story);
        shadowOf(Looper.getMainLooper()).idle();
        StoryBands.refresh(decor);
        assertEquals(Collections.emptyList(), StoryBands.covers(decor));
        assertEquals("the page's own white", Color.WHITE, drawn().getPixel(decor.getWidth() / 2, shown.bottom + GAP_BOTTOM / 2));

        page.addView(story);
        nextLook();
        StoryBands.refresh(decor);
        assertEquals("the story came back", 2, StoryBands.covers(decor).size());
    }

    @Test
    public void aScreenOverTheStoryLeavesTheStripsAlone() {
        FrameLayout stack = new FrameLayout(controller.get());
        page.addView(stack);
        View story = story(stack, GAP_TOP, GAP_BOTTOM);
        View profile = new View(controller.get());
        profile.setBackgroundColor(Color.WHITE);
        stack.addView(profile, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        settle(story);
        assertEquals(Collections.emptyList(), StoryBands.covers(decor));

        stack.removeView(profile);
        nextLook();
        StoryBands.refresh(decor);
        assertEquals("back on the story", 2, StoryBands.covers(decor).size());
    }

    @Test
    public void aStoryReachingUnderTheNavigationBarKeepsThatStrip() {
        View story = story(page, GAP_TOP, 0);
        settle(story);
        List<Rect> covers = StoryBands.covers(decor);
        assertEquals(Collections.singletonList(new Rect(0, 0, decor.getWidth(), where(story).top)), covers);
    }

    @Test
    public void aStoryShortOfTheBarsIsNoStoryScreen() {
        View story = story(page, GAP_TOP, GAP_BOTTOM);
        settle(story);
        // A sheet's worth of story, far below the status bar.
        int[] bars = {where(story).top - 100, GAP_BOTTOM};
        StoryBands.resetForTests(bars);
        StoryBands.track(decor);
        assertEquals(Collections.emptyList(), StoryBands.covers(decor));
    }

    @Test
    public void ambientLightGlowingThereKeepsTheCoversOff() {
        View story = story(page, GAP_TOP, GAP_BOTTOM);
        settle(story);
        StoryBands.litForTests(true);
        StoryBands.refresh(decor);
        assertEquals(Collections.emptyList(), StoryBands.covers(decor));
        StoryBands.litForTests(false);
        StoryBands.refresh(decor);
        assertEquals(2, StoryBands.covers(decor).size());
    }

    @Test
    public void hushgramPausedLeavesTheStripsAsInstagramDrawsThem() {
        View story = story(page, GAP_TOP, GAP_BOTTOM);
        settle(story);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        StoryBands.refresh(decor);
        assertEquals(Collections.emptyList(), StoryBands.covers(decor));
        assertNotEquals(Color.BLACK, drawn().getPixel(decor.getWidth() / 2, where(story).bottom + GAP_BOTTOM / 2));
        PauseForTests.resume();
        StoryBands.refresh(decor);
        assertEquals(2, StoryBands.covers(decor).size());
    }
}
