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
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Copy text button in a story's header (#303): where it goes, what a tap hands on, and when it stays out. */
@RunWith(RobolectricTestRunner.class)
public class StoryTextButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Function<Context, Drawable> originalIcon = StoryTextButton.icon;
    private final BiConsumer<View, Activity> originalGrabber = StoryTextButton.grabber;
    private final AtomicBoolean on = new AtomicBoolean(true);
    private final BooleanSupplier switches = on::get;
    private final List<Object[]> grabbed = new ArrayList<>();
    private ActivityController<Activity> controller;
    private Activity activity;
    private int rowId;
    private int menuId;

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
            // A strip across the top, the way Instagram lays the header out.
            root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private final StoryDownloadButton.Reader reader = new StoryDownloadButton.Reader() {
        @Override
        public Object media(Object item) {
            return item;
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
        StoryTextButton.resetForTests();
        StoryTextButton.icon = context -> new ColorDrawable(Color.WHITE);
        StoryTextButton.grabber = (page, from) -> grabbed.add(new Object[] {page, from});
        rowId = View.generateViewId();
        menuId = View.generateViewId();
        StoryDownloadButton.putIdForTests(activity, StoryDownloadButton.CONTAINER, rowId);
        StoryDownloadButton.putIdForTests(activity, StoryDownloadButton.MENU, menuId);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryDownloadButton.resetForTests();
        StoryTextButton.resetForTests();
        StoryTextButton.icon = originalIcon;
        StoryTextButton.grabber = originalGrabber;
        Settings.TEXT_BUTTON_ON_STORIES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private void bind(Object item, Header header) {
        StoryTextButton.bind(item, header, reader, switches);
    }

    @Test
    public void theSwitchStartsOff() {
        assertEquals(Boolean.FALSE, Settings.TEXT_BUTTON_ON_STORIES.defaultValue);
        assertEquals(false, StoryTextButton.switchedOn());
    }

    @Test
    public void onPutsOneButtonBeforeTheMenu() {
        Header header = new Header();
        bind(new Object(), header);
        ImageView button = StoryTextButton.find(header.row);
        assertNotNull(button);
        assertEquals(1, header.row.indexOfChild(button));
        assertSame(header.menu, header.row.getChildAt(2));
        assertEquals("Copy text", String.valueOf(button.getContentDescription()));
        bind(new Object(), header);
        assertEquals("a second bind adds no second button", 3, header.row.getChildCount());
    }

    @Test
    public void withDownloadAlsoOnTheyGoDownloadThenLensThenMenu() {
        Header header = new Header();
        StoryDownloadButton.bind(new Object(), header, reader, switches, media -> { });
        bind(new Object(), header);
        assertTrue(header.row.getChildAt(1) instanceof ImageView);
        assertNotNull(StoryDownloadButton.find(header.row));
        assertSame(StoryDownloadButton.find(header.row), header.row.getChildAt(1));
        assertSame(StoryTextButton.find(header.row), header.row.getChildAt(2));
        assertSame(header.menu, header.row.getChildAt(3));
    }

    @Test
    public void aTapHandsOnTheStorysPageAndItsActivity() {
        Header header = new Header();
        activity.setContentView(header.root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        bind(new Object(), header);
        ShadowLooper.idleMainLooper();
        StoryTextButton.find(header.row).performClick();
        assertEquals(1, grabbed.size());
        assertSame("the page the story fills", header.root, grabbed.get(0)[0]);
        assertSame(activity, grabbed.get(0)[1]);
    }

    @Test
    public void offHidesItAndANeverOnSwitchAddsNothing() {
        Header never = new Header();
        on.set(false);
        bind(new Object(), never);
        assertNull(StoryTextButton.find(never.row));
        assertEquals(2, never.row.getChildCount());

        on.set(true);
        Header header = new Header();
        bind(new Object(), header);
        ImageView button = StoryTextButton.find(header.row);
        on.set(false);
        bind(new Object(), header);
        assertEquals(View.GONE, button.getVisibility());
        button.performClick();
        assertTrue(grabbed.isEmpty());
    }

    @Test
    public void aStoryThatIsntThereHidesIt() {
        Header header = new Header();
        bind(new Object(), header);
        ImageView button = StoryTextButton.find(header.row);
        bind(null, header);
        assertEquals(View.GONE, button.getVisibility());
        bind(new Object(), header);
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    @Test
    public void aBuildWithoutTheGlyphAddsNothingAndSaysWhy() {
        StoryTextButton.icon = context -> null;
        Header header = new Header();
        bind(new Object(), header);
        assertNull(StoryTextButton.find(header.row));
        assertTrue(String.join("\n", HookStatus.report()).contains(StoryTextButton.COUNT_NO_GLYPH));
    }

    @Test
    public void pausedReadsAsOff() {
        Settings.TEXT_BUTTON_ON_STORIES.save(true);
        assertTrue(StoryTextButton.switchedOn());
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(false, StoryTextButton.switchedOn());
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        StoryTextButton.bind(null, null, reader, switches);
        StoryTextButton.bind(new Object(), new Header(), reader, () -> {
            throw new IllegalStateException("boom");
        });
        Header header = new Header();
        bind(new Object(), header);
        StoryTextButton.grabber = (page, from) -> {
            throw new IllegalStateException("boom");
        };
        StoryTextButton.find(header.row).performClick();
        assertNotNull(StoryTextButton.find(header.row));
    }
}
