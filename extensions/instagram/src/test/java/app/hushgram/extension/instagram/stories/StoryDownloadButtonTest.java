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
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        StoryDownloadButton.resetForTests();
        Settings.DOWNLOAD_BUTTON_ON_STORIES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private void bind(Item item, Header header) {
        StoryDownloadButton.bind(item, header, reader, switches, saved::add);
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
