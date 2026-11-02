/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The speed button on video stories (#469): the speeds a tap moves through, what's kept, and when it stays out. */
@RunWith(RobolectricTestRunner.class)
public class StorySpeedTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private ActivityController<Activity> controller;
    private Activity activity;
    private int rowId;
    private int menuId;
    private Locale locale;

    /** The speeds handed to the player, in order. */
    private final List<Float> set = new ArrayList<>();
    private final Object viewer = new Object();

    /**
     * One of the viewer's pages: a root with the media view a few levels down and the header's row of
     * buttons beside it, the three-dot menu in the row.
     */
    private final class Page {
        final FrameLayout root = new FrameLayout(activity);
        final FrameLayout frame = new FrameLayout(activity);
        final FrameLayout media = new FrameLayout(activity);
        final LinearLayout row = new LinearLayout(activity);
        final ImageView other = new ImageView(activity);
        final ImageView menu = new ImageView(activity);

        Page() {
            row.setId(rowId);
            menu.setId(menuId);
            row.addView(other);
            row.addView(menu);
            frame.addView(media);
            root.addView(frame);
            root.addView(row);
        }

        /** The row's speed button, or null when it has none. */
        StorySpeed.SpeedButton button() {
            return StorySpeed.find(row);
        }
    }

    /** A story that's an ad. */
    private static final class Ad {
    }

    private class Fake implements StorySpeed.Access {
        @Override
        public boolean setSpeed(Object viewer, float speed) {
            set.add(speed);
            return true;
        }

        @Override
        public boolean isPage(Object holder) {
            return holder instanceof Page;
        }

        @Override
        public View pageView(Object page) {
            return ((Page) page).media;
        }

        @Override
        public boolean ad(Object item) {
            return item instanceof Ad;
        }
    }

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        locale = Locale.getDefault();
        StorySpeed.resetForTests();
        StoryDownloadButton.resetForTests();
        rowId = View.generateViewId();
        menuId = View.generateViewId();
        StorySpeed.putIdForTests(activity, StorySpeed.CONTAINER, rowId);
        StoryDownloadButton.putIdForTests(activity, StoryDownloadButton.MENU, menuId);
        StorySpeed.access = new Fake();
        Settings.STORY_SPEED_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Locale.setDefault(locale);
        StorySpeed.resetForTests();
        StoryDownloadButton.resetForTests();
        Settings.STORY_SPEED_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.STORY_SPEED + ":")) return line;
        }
        return "";
    }

    private void on() {
        Settings.STORY_SPEED_BUTTON.save(true);
    }

    @Test
    public void theSwitchStartsOff() {
        assertEquals(Boolean.FALSE, Settings.STORY_SPEED_BUTTON.defaultValue);
        assertFalse(StorySpeed.wanted());
    }

    @Test
    public void aTapMovesThroughOneAndAHalfTwoAndBackToOne() {
        assertEquals(1.5f, StorySpeed.next(1f), 0f);
        assertEquals(2f, StorySpeed.next(1.5f), 0f);
        assertEquals(1f, StorySpeed.next(2f), 0f);
        assertEquals(1f, StorySpeed.next(3f), 0f);
    }

    @Test
    public void theLabelUsesThePhonesDecimalMark() {
        Locale.setDefault(Locale.US);
        assertEquals("1x", StorySpeed.text(1f));
        assertEquals("1.5x", StorySpeed.text(1.5f));
        assertEquals("2x", StorySpeed.text(2f));
        Locale.setDefault(Locale.GERMANY);
        assertEquals("1,5x", StorySpeed.text(1.5f));
    }

    @Test
    public void onPutsOneButtonBeforeTheMenuAndEachTapSetsTheNextSpeed() {
        Locale.setDefault(Locale.US);
        on();
        Page page = new Page();
        Object story = new Object();
        StorySpeed.playing(viewer, story, page);
        assertTrue("normal speed isn't set over Instagram's own: " + set, set.isEmpty());
        StorySpeed.SpeedButton button = page.button();
        assertNotNull(button);
        assertEquals(1, page.row.indexOfChild(button));
        assertSame(page.menu, page.row.getChildAt(2));
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals("1x", button.getText().toString());
        assertEquals("Playback speed, 1x", String.valueOf(button.getContentDescription()));

        StorySpeed.playing(viewer, story, page);
        assertEquals("a second start adds no second button", 3, page.row.getChildCount());

        button.performClick();
        assertEquals("1.5x", button.getText().toString());
        assertEquals("Playback speed, 1.5x", String.valueOf(button.getContentDescription()));
        button.performClick();
        assertEquals("2x", button.getText().toString());
        button.performClick();
        assertEquals("1x", button.getText().toString());
        assertEquals(Arrays.asList(1.5f, 2f, 1f), set);
        assertEquals(1f, StorySpeed.current(), 0f);
        assertTrue(statusLine(), statusLine().contains(StorySpeed.TAPPED + " 3"));
    }

    @Test
    public void theNextVideoStoryStartsAtTheKeptSpeed() {
        Locale.setDefault(Locale.US);
        on();
        Page first = new Page();
        StorySpeed.playing(viewer, new Object(), first);
        first.button().performClick();
        set.clear();

        Page second = new Page();
        StorySpeed.playing(viewer, new Object(), second);
        assertEquals(Arrays.asList(1.5f), set);
        assertEquals("1.5x", second.button().getText().toString());
        StorySpeed.playing(viewer, new Object(), first);
        assertEquals(Arrays.asList(1.5f, 1.5f), set);
        assertTrue(statusLine(), statusLine().contains(StorySpeed.APPLIED + " 2"));
    }

    @Test
    public void anAdPlaysAtNormalSpeedWithNoButtonAndTheNextStoryGetsTheSpeedBack() {
        on();
        Page page = new Page();
        StorySpeed.playing(viewer, new Object(), page);
        page.button().performClick();
        set.clear();

        Page ad = new Page();
        StorySpeed.playing(viewer, new Ad(), ad);
        assertEquals(Arrays.asList(1f), set);
        assertNull(ad.button());

        StorySpeed.playing(viewer, new Ad(), page);
        assertEquals("normal speed already went back once", Arrays.asList(1f), set);
        assertEquals(View.GONE, page.button().getVisibility());

        StorySpeed.playing(viewer, new Object(), page);
        assertEquals(Arrays.asList(1f, 1.5f), set);
        assertEquals(View.VISIBLE, page.button().getVisibility());
    }

    @Test
    public void offForgetsTheSpeedPutsNormalBackOnceAndHidesTheButton() {
        Locale.setDefault(Locale.US);
        on();
        Page page = new Page();
        StorySpeed.playing(viewer, new Object(), page);
        page.button().performClick();
        page.button().performClick();
        set.clear();

        Settings.STORY_SPEED_BUTTON.save(false);
        StorySpeed.playing(viewer, new Object(), page);
        assertEquals(Arrays.asList(1f), set);
        assertEquals(View.GONE, page.button().getVisibility());
        StorySpeed.playing(viewer, new Object(), page);
        assertEquals(Arrays.asList(1f), set);

        on();
        StorySpeed.playing(viewer, new Object(), page);
        assertEquals("on again starts at normal speed", Arrays.asList(1f), set);
        assertEquals("1x", page.button().getText().toString());
        assertEquals(View.VISIBLE, page.button().getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        on();
        Page page = new Page();
        StorySpeed.playing(viewer, new Object(), page);
        page.button().performClick();
        set.clear();

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(StorySpeed.wanted());
        StorySpeed.playing(viewer, new Object(), page);
        assertEquals(Arrays.asList(1f), set);
        assertEquals(View.GONE, page.button().getVisibility());
        assertEquals(1f, StorySpeed.current(), 0f);
    }

    @Test
    public void aTapAfterTheSwitchWentOffHidesTheButtonAndKeepsNothing() {
        on();
        Page page = new Page();
        StorySpeed.playing(viewer, new Object(), page);
        StorySpeed.SpeedButton button = page.button();
        Settings.STORY_SPEED_BUTTON.save(false);
        button.performClick();
        assertTrue(set.isEmpty());
        assertEquals(View.GONE, button.getVisibility());
        assertEquals(1f, StorySpeed.current(), 0f);
    }

    @Test
    public void anotherStoryBoundIntoThePageHidesTheButtonUntilItsVideoStarts() {
        on();
        Page page = new Page();
        Object video = new Object();
        StorySpeed.playing(viewer, video, page);
        StorySpeed.bound(page, video);
        assertEquals("the same story keeps it", View.VISIBLE, page.button().getVisibility());

        StorySpeed.bound(page, new Object());
        assertEquals("a photo has no video to speed up", View.GONE, page.button().getVisibility());
        page.button().performClick();
        assertTrue("a hidden button sets nothing", set.isEmpty());
        assertEquals("and keeps nothing", 1f, StorySpeed.current(), 0f);

        Object next = new Object();
        StorySpeed.bound(page, next);
        StorySpeed.playing(viewer, next, page);
        assertEquals(View.VISIBLE, page.button().getVisibility());
    }

    @Test
    public void aSwitchNeverOnAddsNothingAndSetsNothing() {
        Page page = new Page();
        StorySpeed.playing(viewer, new Object(), page);
        StorySpeed.bound(page, new Object());
        assertNull(page.button());
        assertEquals(2, page.row.getChildCount());
        assertTrue(set.isEmpty());
    }

    @Test
    public void somethingOtherThanAPageIsLeftAlone() {
        on();
        StorySpeed.playing(viewer, new Object(), new Object());
        StorySpeed.playing(viewer, new Object(), null);
        StorySpeed.playing(null, new Object(), new Page());
        assertTrue(set.isEmpty());
    }

    @Test
    public void aPageWithoutTheRowIsReportedAndLeftAlone() {
        on();
        Page page = new Page();
        page.root.removeView(page.row);
        StorySpeed.playing(viewer, new Object(), page);
        assertEquals(1, page.root.getChildCount());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(StorySpeed.CONTAINER));
    }

    @Test
    public void aPlayerThatIsntThereKeepsTheSpeedForTheNextStory() {
        on();
        Page page = new Page();
        StorySpeed.playing(viewer, new Object(), page);
        StorySpeed.access = new Fake() {
            @Override
            public boolean setSpeed(Object viewer, float speed) {
                return false;
            }
        };
        page.button().performClick();
        assertEquals(1.5f, StorySpeed.current(), 0f);
        StorySpeed.access = new Fake();
        StorySpeed.playing(viewer, new Object(), page);
        assertEquals(Arrays.asList(1.5f), set);
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        on();
        Page page = new Page();
        StorySpeed.playing(viewer, new Object(), page);
        StorySpeed.access = new StorySpeed.Access() {
            @Override
            public boolean setSpeed(Object viewer, float speed) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean isPage(Object holder) {
                throw new IllegalStateException("boom");
            }

            @Override
            public View pageView(Object page) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean ad(Object item) {
                throw new IllegalStateException("boom");
            }
        };
        StorySpeed.playing(viewer, new Object(), page);
        StorySpeed.bound(page, new Object());
        page.button().performClick();
        assertNotNull(page.button());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("'" + StorySpeed.HOOK + "' hook") && report.contains("IllegalStateException"));
    }
}
