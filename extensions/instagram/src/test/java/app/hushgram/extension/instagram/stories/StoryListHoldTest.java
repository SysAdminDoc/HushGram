/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/** A list opened over a story keeps the story still until the last list closes (#745, #726). */
@RunWith(RobolectricTestRunner.class)
public class StoryListHoldTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final List<String> calls = new ArrayList<>();
    private final Object viewer = new Object();
    private ActivityController<Activity> controller;
    private ImageView button;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        FrameLayout root = new FrameLayout(activity);
        button = new ImageView(activity);
        root.addView(button);
        activity.setContentView(root);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        StoryListHold.playback = new StoryListHold.Playback() {
            @Override
            public void hold(Object held) {
                assertTrue(held == viewer);
                calls.add("hold");
            }

            @Override
            public void play(Object held) {
                assertTrue(held == viewer);
                calls.add("play");
            }
        };
    }

    @After
    public void tearDown() {
        StoryListHold.forget();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    @Test
    public void aListHoldsTheStoryUntilItCloses() {
        StoryListHold.viewerShown(viewer);
        PopupMenu list = shown();

        StoryListHold.over(list);
        assertEquals(Collections.singletonList("hold"), calls);

        close(list);
        assertEquals(Arrays.asList("hold", "play"), calls);
    }

    /** A row of the first list can open the second before the first has closed: one hold, one play, after the last. */
    @Test
    public void aListOpenedFromARowKeepsTheOneHold() {
        StoryListHold.viewerShown(viewer);
        PopupMenu first = shown();
        StoryListHold.over(first);
        PopupMenu second = shown();
        StoryListHold.over(second);
        assertEquals(Collections.singletonList("hold"), calls);

        close(first);
        assertEquals("the second list is still open", Collections.singletonList("hold"), calls);

        close(second);
        assertEquals(Arrays.asList("hold", "play"), calls);
    }

    /** Instagram plays the story itself when the viewer comes back, so a list left open just goes away. */
    @Test
    public void comingBackClosesTheListAndLeavesThePlayToInstagram() {
        StoryListHold.viewerShown(viewer);
        PopupMenu list = shown();
        StoryListHold.over(list);

        StoryListHold.viewerShown(viewer);
        assertFalse("the list is still up", Shadows.shadowOf(list).isShowing());
        dismissListener(list).onDismiss(list);
        assertEquals(Collections.singletonList("hold"), calls);

        PopupMenu next = shown();
        StoryListHold.over(next);
        assertEquals("the next list holds again", Arrays.asList("hold", "hold"), calls);
        close(next);
        assertEquals(Arrays.asList("hold", "hold", "play"), calls);
    }

    @Test
    public void withNoViewerYetNothingIsHeldOrPlayed() {
        PopupMenu list = shown();
        StoryListHold.over(list);
        close(list);
        assertEquals(Collections.emptyList(), calls);
    }

    /** A pause that fails leaves the list working, is reported, and doesn't turn into a play. */
    @Test
    public void aPauseThatFailsIsReportedAndNothingPlays() {
        StoryListHold.playback = new StoryListHold.Playback() {
            @Override
            public void hold(Object held) {
                throw new IllegalStateException("no pause");
            }

            @Override
            public void play(Object held) {
                calls.add("play");
            }
        };
        StoryListHold.viewerShown(viewer);
        PopupMenu list = shown();

        StoryListHold.over(list);
        close(list);

        assertEquals(Collections.emptyList(), calls);
        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains(StoryListHold.HOOK));
    }

    /** Closes [list] as Android does. Robolectric's dismiss only marks it closed, so its listener is run here. */
    private static void close(PopupMenu list) {
        list.dismiss();
        dismissListener(list).onDismiss(list);
    }

    private static PopupMenu.OnDismissListener dismissListener(PopupMenu list) {
        return ReflectionHelpers.getField(list, "mOnDismissListener");
    }

    private PopupMenu shown() {
        PopupMenu list = new PopupMenu(button.getContext(), button);
        list.getMenu().add("Download");
        list.show();
        return list;
    }
}
