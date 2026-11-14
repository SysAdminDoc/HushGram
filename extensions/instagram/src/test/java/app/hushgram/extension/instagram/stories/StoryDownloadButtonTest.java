/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Download button in a story's header (#400): where it goes, what a tap saves, and when it stays out. */
@RunWith(RobolectricTestRunner.class)
public class StoryDownloadButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final AtomicBoolean on = new AtomicBoolean(true);
    private final BooleanSupplier switches = on::get;
    private final List<Object> saved = new ArrayList<>();
    private ActivityController<Activity> controller;
    private Activity activity;
    private int rowId;
    private int menuId;
    private int footerId;
    private int likeId;
    private final AtomicBoolean thumb = new AtomicBoolean(false);

    /** A story's view holder: its header root, with the row of buttons and the three-dot menu in it. */
    private final class Header {
        final FrameLayout root = new FrameLayout(activity);
        final LinearLayout row = new LinearLayout(activity);
        final ImageView other = new ImageView(activity);
        final ImageView menu = new ImageView(activity);

        Header() {
            row.setId(rowId);
            menu.setId(menuId);
            row.addView(other);
            row.addView(menu);
            root.addView(row);
        }
    }

    /**
     * A story's view holder with Instagram's bottom row too (#647): the toolbar under the story, and
     * in it the row with Like and Share at the end.
     */
    private final class Footed {
        final Header header = new Header();
        final LinearLayout toolbar = new LinearLayout(activity);
        final LinearLayout row = new LinearLayout(activity);
        final FrameLayout like = new FrameLayout(activity);
        final ImageView share = new ImageView(activity);

        Footed() {
            row.setId(footerId);
            like.setId(likeId);
            row.addView(like);
            row.addView(share);
            toolbar.addView(new View(activity));
            toolbar.addView(row);
            header.root.addView(toolbar);
        }
    }

    /** A story item whose Media is [media], read the way the patch's stub reads it. */
    private static final class Item {
        final Object media;

        Item(Object media) {
            this.media = media;
        }
    }

    private final StoryDownloadButton.Reader reader = new StoryDownloadButton.Reader() {
        @Override
        public Object media(Object item) {
            return ((Item) item).media;
        }

        @Override
        public View itemView(Object holder) {
            return ((Header) holder).root;
        }
    };

    @Before
    public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        StoryDownloadButton.resetForTests();
        rowId = View.generateViewId();
        menuId = View.generateViewId();
        StoryDownloadButton.putIdForTests(activity, StoryDownloadButton.CONTAINER, rowId);
        StoryDownloadButton.putIdForTests(activity, StoryDownloadButton.MENU, menuId);
        StoryButtonRows.resetForTests();
        footerId = View.generateViewId();
        likeId = View.generateViewId();
        StoryButtonRows.putIdForTests(activity, StoryButtonRows.FOOTER, footerId);
        StoryButtonRows.putIdForTests(activity, StoryButtonRows.LIKE, likeId);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryDownloadButton.resetForTests();
        StoryButtonRows.resetForTests();
        Settings.DOWNLOAD_BUTTON_ON_STORIES.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        Settings.STORY_BUTTONS_BY_THUMB.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private void bind(Item item, Header header) {
        StoryDownloadButton.bind(item, header, reader, switches, saved::add);
    }

    private void bind(Item item, Footed story) {
        StoryDownloadButton.bind(item, story.header, reader, switches, thumb::get, saved::add);
    }

    /** Runs what's posted on the main thread, as the next frame would once Instagram's bind is done. */
    private static void afterTheBind() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** By the thumb (#647), the button goes before Like in the bottom row, Like and Share keep the edge, and a tap still saves. */
    @Test
    public void byTheThumbItGoesBeforeLikeAndStillSaves() {
        Footed story = new Footed();
        Object media = new Object();
        thumb.set(true);
        bind(new Item(media), story);
        ImageView button = StoryDownloadButton.find(story.row);
        assertNotNull(button);
        assertNull("not in the header too", StoryDownloadButton.find(story.header.row));
        assertEquals(0, story.row.indexOfChild(button));
        assertSame(story.like, story.row.getChildAt(1));
        assertSame(story.share, story.row.getChildAt(2));
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        assertEquals(Math.round(48 * density), params.width);
        assertEquals(Math.round(40 * density), params.height + params.topMargin + params.bottomMargin);
        bind(new Item(media), story);
        assertEquals(3, story.row.getChildCount());
        button.performClick();
        assertEquals(1, saved.size());
        assertSame(media, saved.get(0));
    }

    /** Turned off, the same button goes back before the three dots, and turned on again it comes back down. */
    @Test
    public void theSwitchMovesTheSameButtonBetweenTheRows() {
        Footed story = new Footed();
        thumb.set(true);
        bind(new Item(new Object()), story);
        ImageView button = StoryDownloadButton.find(story.row);
        thumb.set(false);
        bind(new Item(new Object()), story);
        assertSame(button, StoryDownloadButton.find(story.header.row));
        assertEquals(1, story.header.row.indexOfChild(button));
        assertSame(story.header.menu, story.header.row.getChildAt(2));
        assertEquals(2, story.row.getChildCount());
        thumb.set(true);
        bind(new Item(new Object()), story);
        assertSame(button, StoryDownloadButton.find(story.row));
        assertEquals(2, story.header.row.getChildCount());
    }

    /**
     * A story whose bottom row is hidden when the button is bound keeps it at the top, and the look
     * posted for after Instagram's bind follows the row Instagram shows or hides for the story.
     */
    @Test
    public void theLookAfterTheBindFollowsTheBottomRow() {
        Footed story = new Footed();
        thumb.set(true);
        story.toolbar.setVisibility(View.GONE);
        bind(new Item(new Object()), story);
        ImageView button = StoryDownloadButton.find(story.header.row);
        assertNotNull("a hidden bottom row leaves it in the header", button);

        // Instagram shows the row for this story after the hook ran.
        story.toolbar.setVisibility(View.VISIBLE);
        activity.setContentView(story.header.root);
        afterTheBind();
        assertSame(button, StoryDownloadButton.find(story.row));
        assertEquals(0, story.row.indexOfChild(button));

        // The next story has its row hidden by Instagram's bind, after the hook put the button below.
        bind(new Item(new Object()), story);
        story.toolbar.setVisibility(View.GONE);
        afterTheBind();
        assertSame(button, StoryDownloadButton.find(story.header.row));
        assertEquals(2, story.row.getChildCount());
    }

    /** A story with no bottom row in its layout, as your own, keeps the button at the top. */
    @Test
    public void aStoryWithoutTheBottomRowKeepsItAtTheTop() {
        Header header = new Header();
        thumb.set(true);
        StoryDownloadButton.bind(new Item(new Object()), header, reader, switches, thumb::get, saved::add);
        assertNotNull(StoryDownloadButton.find(header.row));
        assertTrue(String.join("\n", HookStatus.report()), HookStatus.missing(
                app.hushgram.extension.instagram.settings.FamilyNames.STORY_DOWNLOAD).isEmpty());
    }

    /** The bottom row switch starts off, waits on a button to move, and reads off while paused. */
    @Test
    public void theBottomRowSwitchStartsOffAndWaitsOnAButton() {
        assertEquals(Boolean.FALSE, Settings.STORY_BUTTONS_BY_THUMB.defaultValue);
        assertEquals(false, StoryButtonRows.byThumb());
        assertEquals(false, Settings.STORY_BUTTONS_BY_THUMB.isAvailable());
        Settings.DOWNLOAD_BUTTON_ON_STORIES.save(true);
        assertTrue(Settings.STORY_BUTTONS_BY_THUMB.isAvailable());
        Settings.DOWNLOAD_BUTTON_ON_STORIES.save(false);
        Settings.MARK_STORIES_SEEN.save(true);
        assertTrue(Settings.STORY_BUTTONS_BY_THUMB.isAvailable());
        Settings.STORY_BUTTONS_BY_THUMB.save(true);
        assertTrue(StoryButtonRows.byThumb());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(false, StoryButtonRows.byThumb());
    }

    @Test
    public void theSwitchStartsOff() {
        assertEquals(Boolean.FALSE, Settings.DOWNLOAD_BUTTON_ON_STORIES.defaultValue);
        assertEquals(false, StoryDownloadButton.switchedOn());
    }

    @Test
    public void onPutsOneButtonBeforeTheMenuAndATapSavesTheStory() {
        Header header = new Header();
        Object media = new Object();
        bind(new Item(media), header);
        ImageView button = StoryDownloadButton.find(header.row);
        assertNotNull(button);
        assertEquals(1, header.row.indexOfChild(button));
        assertSame(header.menu, header.row.getChildAt(2));
        assertEquals("Download", String.valueOf(button.getContentDescription()));
        assertTrue(button.getDrawable() instanceof StoryDownloadButton.Arrow);
        bind(new Item(media), header);
        assertEquals(3, header.row.getChildCount());
        button.performClick();
        assertEquals(1, saved.size());
        assertSame(media, saved.get(0));
    }

    @Test
    public void aRecycledHeaderFollowsItsNewStory() {
        Header header = new Header();
        Object first = new Object();
        Object second = new Object();
        bind(new Item(first), header);
        bind(new Item(second), header);
        StoryDownloadButton.find(header.row).performClick();
        assertEquals(1, saved.size());
        assertSame(second, saved.get(0));
    }

    @Test
    public void aStoryWithNoMediaHidesTheButtonAndItCantSave() {
        Header header = new Header();
        bind(new Item(new Object()), header);
        ImageView button = StoryDownloadButton.find(header.row);
        bind(new Item(null), header);
        assertEquals(View.GONE, button.getVisibility());
        button.performClick();
        assertTrue(saved.isEmpty());
    }

    @Test
    public void offHidesItAndANeverOnSwitchAddsNothing() {
        Header never = new Header();
        on.set(false);
        bind(new Item(new Object()), never);
        assertNull(StoryDownloadButton.find(never.row));
        assertEquals(2, never.row.getChildCount());

        on.set(true);
        Header header = new Header();
        bind(new Item(new Object()), header);
        ImageView button = StoryDownloadButton.find(header.row);
        on.set(false);
        bind(new Item(new Object()), header);
        assertEquals(View.GONE, button.getVisibility());
        button.performClick();
        assertTrue(saved.isEmpty());
    }

    @Test
    public void aTapAfterTheSwitchWentOffDoesNothing() {
        Header header = new Header();
        bind(new Item(new Object()), header);
        ImageView button = StoryDownloadButton.find(header.row);
        on.set(false);
        button.performClick();
        assertTrue(saved.isEmpty());
        assertEquals(View.GONE, button.getVisibility());
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.DOWNLOAD_BUTTON_ON_STORIES.save(true);
        assertTrue(StoryDownloadButton.switchedOn());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(false, StoryDownloadButton.switchedOn());
    }

    @Test
    public void aHeaderWithoutTheRowIsReportedAndLeftAlone() {
        Header header = new Header();
        header.root.removeView(header.row);
        bind(new Item(new Object()), header);
        assertEquals(0, header.root.getChildCount());
        assertTrue(String.join("\n", HookStatus.report()), String.join("\n", HookStatus.report())
                .contains(StoryDownloadButton.CONTAINER));
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        StoryDownloadButton.bind(null, null, reader, switches, saved::add);
        StoryDownloadButton.bind(new Item(new Object()), new Header(), reader, () -> {
            throw new IllegalStateException("boom");
        }, saved::add);
        Header header = new Header();
        bind(new Item(new Object()), header);
        StoryDownloadButton.find(header.row).setTag("not a story");
        StoryDownloadButton.find(header.row).performClick();
        StoryDownloadButton.bind(new Item(new Object()), header, reader, switches, media -> {
            throw new IllegalStateException("boom");
        });
        StoryDownloadButton.find(header.row).performClick();
        assertNotNull(StoryDownloadButton.find(header.row));
    }
}
