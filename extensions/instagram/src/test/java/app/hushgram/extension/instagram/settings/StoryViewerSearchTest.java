/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.stories.StoryViewers;
import app.hushgram.extension.instagram.stories.StoryViewersRows;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.ui.Dim;

/**
 * Search viewers in the viewer list (#833): with Keep story viewers and its search switch on, a
 * story's viewer list gets one Search viewers bar however many rows it binds, above the list in a
 * column or over its top anywhere else, and the bar opens the story viewers page on the story the
 * list is of with the search box ready, Back going back to Instagram's list.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "w360dp-h640dp-mdpi")
@SuppressWarnings("deprecation")
public class StoryViewerSearchTest {
    private static final long DAY = StoryViewers.DAY;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Lays out its first child alone, as a pull-to-refresh holder does. */
    public static final class OneChild extends ViewGroup {
        public OneChild(Context context) {
            super(context);
        }

        @Override protected void onMeasure(int width, int height) {
            setMeasuredDimension(MeasureSpec.getSize(width), MeasureSpec.getSize(height));
            if (getChildCount() > 0) getChildAt(0).measure(width, height);
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            if (getChildCount() > 0) getChildAt(0).layout(0, 0, right - left, bottom - top);
        }
    }

    /** Takes only its own one view, as a fragment's container takes only its fragments' views. */
    public static final class Picky extends FrameLayout {
        public Picky(Context context) {
            super(context);
        }

        @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
            if (getChildCount() > 0) throw new IllegalStateException("not one of mine");
            super.addView(child, index, params);
        }
    }

    private int username;
    private HookStatus.Snapshot saved;

    @Before public void setUp() {
        saved = HookStatus.snapshotAndClear();
        username = View.generateViewId();
        StoryViewerSearch.forgetForTests(username);
        StoryViewersRows.forget();
        Settings.STORY_VIEWERS.save(true);
        Settings.STORY_VIEWERS_SEARCH.save(true);
    }

    @After public void restore() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        Settings.STORY_VIEWERS.resetToDefault();
        Settings.STORY_VIEWERS_SEARCH.resetToDefault();
        StoryViewers.clear(RuntimeEnvironment.getApplication());
        StoryViewersRows.forget();
        StoryViewerSearch.forgetForTests(0);
        HookStatus.restore(saved);
    }

    @Test public void aColumnGetsOneBarAboveItsList() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            RecyclerView list = new RecyclerView(activity);
            column.addView(new View(activity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40));
            column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            activity.setContentView(column);
            int padding = list.getPaddingTop();

            StoryViewerSearch.row(row(activity, list, "ana"));
            settle();
            assertEquals(3, column.getChildCount());
            TextView bar = (TextView) column.getChildAt(1);
            assertEquals("Search viewers", String.valueOf(bar.getText()));
            assertSame("the list comes right under it", list, column.getChildAt(2));
            LinearLayout.LayoutParams room = (LinearLayout.LayoutParams) bar.getLayoutParams();
            assertEquals(Dim.dp(StoryViewerSearch.HEIGHT_DP), room.height);
            assertEquals(Dim.dp(StoryViewerSearch.SIDE_DP), room.getMarginStart());
            assertTrue("a place to tap", bar.isClickable());
            assertEquals("a column leaves the list's padding alone", padding, list.getPaddingTop());

            StoryViewerSearch.row(row(activity, list, "bo"));
            StoryViewerSearch.row(row(activity, list, "cy"));
            settle();
            assertEquals("one bar however many rows bind", 3, column.getChildCount());
        }
    }

    @Test public void anyOtherHolderGetsTheBarOverTheListsTop() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout holder = new FrameLayout(activity);
            RecyclerView list = new RecyclerView(activity);
            list.setPadding(0, 10, 0, 0);
            FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            below.topMargin = 100;
            holder.addView(list, below);
            holder.addView(new View(activity), new FrameLayout.LayoutParams(50, 50));
            activity.setContentView(holder);
            settle();
            lay(holder);

            StoryViewerSearch.row(row(activity, list, "ana"));
            settle();
            lay(holder);
            settle();
            lay(holder);
            assertEquals(3, holder.getChildCount());
            assertSame(list, holder.getChildAt(0));
            TextView bar = (TextView) holder.getChildAt(2);
            assertEquals("last, so it draws over the list", "Search viewers", String.valueOf(bar.getText()));
            int side = Dim.dp(StoryViewerSearch.SIDE_DP);
            int gap = Dim.dp(StoryViewerSearch.GAP_DP);
            int height = Dim.dp(StoryViewerSearch.HEIGHT_DP);
            assertEquals("the first row starts under the bar", 10 + height + 2 * gap, list.getPaddingTop());
            assertEquals(100, list.getTop());
            assertEquals("over the list's top", list.getTop() + gap, bar.getTop() + Math.round(bar.getTranslationY()));
            assertEquals(list.getLeft() + side, bar.getLeft() + Math.round(bar.getTranslationX()));
            assertEquals("as wide as the list allows", list.getWidth() - 2 * side, bar.getWidth());
            assertEquals(height, bar.getHeight());

            StoryViewerSearch.row(row(activity, list, "bo"));
            settle();
            lay(holder);
            assertEquals("one bar", 3, holder.getChildCount());
            assertEquals("the padding grows once", 10 + height + 2 * gap, list.getPaddingTop());
        }
    }

    /**
     * A list whose parent lays out its first child alone, as a pull-to-refresh holder does, inside a
     * fragment container that takes only its own views: the bar goes in the plain frame above both,
     * over the list wherever it sits, and hides while the list does.
     */
    @Test public void aParentLayingOutOneChildHandsTheBarToThePlainFrameAbove() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout frame = new FrameLayout(activity);
            Picky container = new Picky(activity);
            OneChild refresh = new OneChild(activity);
            RecyclerView list = new RecyclerView(activity);
            refresh.addView(list);
            container.addView(refresh);
            FrameLayout.LayoutParams lower = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lower.topMargin = 60;
            lower.leftMargin = 20;
            frame.addView(container, lower);
            activity.setContentView(frame);
            lay(frame);
            assertFalse(StoryViewerSearch.plain(OneChild.class, FrameLayout.class));
            assertFalse("a fragment container takes only its own views", StoryViewerSearch.plain(Picky.class, FrameLayout.class));
            assertTrue(StoryViewerSearch.plain(FrameLayout.class, FrameLayout.class));

            StoryViewerSearch.row(row(activity, list, "ana"));
            settle();
            lay(frame);
            settle();
            lay(frame);
            assertEquals("the one-child parent keeps its one child", 1, refresh.getChildCount());
            assertEquals(1, container.getChildCount());
            assertEquals(2, frame.getChildCount());
            TextView bar = (TextView) frame.getChildAt(1);
            assertEquals("Search viewers", String.valueOf(bar.getText()));
            int side = Dim.dp(StoryViewerSearch.SIDE_DP);
            int gap = Dim.dp(StoryViewerSearch.GAP_DP);
            assertEquals(60 + gap, bar.getTop() + Math.round(bar.getTranslationY()));
            assertEquals(20 + side, bar.getLeft() + Math.round(bar.getTranslationX()));
            assertEquals(list.getWidth() - 2 * side, bar.getWidth());
            assertEquals(View.VISIBLE, bar.getVisibility());

            container.setTranslationY(100);
            list.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("it follows the list as it moves", 160 + gap, bar.getTop() + Math.round(bar.getTranslationY()));
            refresh.setVisibility(View.GONE);
            list.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("hidden with the list", View.INVISIBLE, bar.getVisibility());
            refresh.setVisibility(View.VISIBLE);
            list.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.VISIBLE, bar.getVisibility());
            refresh.removeView(list);
            assertEquals("and gone with it", View.INVISIBLE, bar.getVisibility());
        }
    }

    /** No plain frame or column within reach: the list is left alone and the report says why. */
    @Test public void withNoPlainHolderTheListIsLeftAlone() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            OneChild refresh = new OneChild(activity);
            RecyclerView list = new RecyclerView(activity);
            refresh.addView(list);
            int padding = list.getPaddingTop();
            assertFalse(StoryViewerSearch.place(list, new TextView(activity)));
            assertEquals(1, refresh.getChildCount());
            assertEquals(padding, list.getPaddingTop());
        }
    }

    @Test public void eitherSwitchOffLeavesTheListAlone() throws Exception {
        StoryViewerSearch.row(null);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            RecyclerView list = new RecyclerView(activity);
            column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            activity.setContentView(column);

            Settings.STORY_VIEWERS_SEARCH.save(false);
            StoryViewerSearch.row(row(activity, list, "ana"));
            settle();
            assertEquals("the search switch off", 1, column.getChildCount());

            Settings.STORY_VIEWERS_SEARCH.save(true);
            Settings.STORY_VIEWERS.save(false);
            StoryViewerSearch.row(row(activity, list, "bo"));
            settle();
            assertEquals("Keep story viewers off", 1, column.getChildCount());

            LinearLayout loose = new LinearLayout(activity);
            column.addView(loose);
            Settings.STORY_VIEWERS.save(true);
            TextView outside = new TextView(activity);
            loose.addView(outside);
            StoryViewerSearch.row(outside);
            settle();
            assertEquals("a row outside a list", 2, column.getChildCount());

            StoryViewerSearch.row(row(activity, list, "cy"));
            settle();
            assertEquals("both on", 3, column.getChildCount());
        }
    }

    /** The bar opens the page on the story with most of the names on screen; Back goes back to the list. */
    @Test public void theBarOpensTheListsStoryWithTheSearchReady() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long now = System.currentTimeMillis();
        StoryViewersRows.keep(context, "101", now - 2 * DAY, 2, now, "11", "ana", "12", "bo");
        StoryViewersRows.keep(context, "102", now - DAY, 2, now, "11", "ana", "13", "cy");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            RecyclerView list = new RecyclerView(activity);
            column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            activity.setContentView(column);
            row(activity, list, "ana");
            StoryViewerSearch.row(row(activity, list, "Bo"));
            settle();
            assertEquals(Arrays.asList("ana", "bo"), StoryViewerSearch.usernames(list));

            StoryViewersPage page = StoryViewerSearch.open(list);
            settle();
            assertNotNull(page);
            assertTrue(page.dialog.isShowing());
            assertNotNull("it opened on a story", page.opened);
            assertEquals("the one holding ana and bo", "101", page.opened.id);
            assertSame(page.opened, page.landed);
            assertEquals(Arrays.asList("@ana", "@bo"), titles(page));
            assertTrue(String.valueOf(page.overview.getText()), String.valueOf(page.overview.getText()).startsWith("Story from "));
            assertTrue("the search box is ready", page.search.isFocused());
            int mode = page.dialog.getWindow().getAttributes().softInputMode;
            assertEquals("the keyboard pushes the list up", WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            assertEquals("and shows at once", WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                    mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE);
            assertTrue("no Clear outside the settings", described(page.dialog.getWindow().getDecorView(), "Clear story viewers").isEmpty());
            page.search.setText("b");
            assertEquals(Arrays.asList("@bo"), titles(page));

            page.dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            page.dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            settle();
            assertFalse("Back goes back to Instagram's list", page.dialog.isShowing());

            TextView bar = (TextView) column.getChildAt(0);
            assertTrue(bar.performClick());
            settle();
            Dialog shown = ShadowDialog.getLatestDialog();
            assertNotSame(page.dialog, shown);
            assertTrue("a tap on the bar opens it", shown.isShowing());
            shown.dismiss();
        }
    }

    @Test public void withNothingKeptTheBarOpensThePageOnItsViewers() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            RecyclerView list = new RecyclerView(activity);
            column.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            activity.setContentView(column);
            StoryViewerSearch.row(row(activity, list, "ana"));
            settle();

            StoryViewersPage page = StoryViewerSearch.open(list);
            settle();
            assertNotNull(page);
            assertNull(page.opened);
            assertTrue(String.valueOf(page.empty.getText()), String.valueOf(page.empty.getText()).startsWith("Nothing here yet."));
            page.dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            page.dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            settle();
            assertFalse(page.dialog.isShowing());
        }
    }

    /** One row of the list as Instagram's maker builds it, the username's view in a holder, as the bind hands it. */
    private View row(Activity activity, RecyclerView list, String name) {
        FrameLayout row = new FrameLayout(activity);
        TextView who = new TextView(activity);
        who.setId(username);
        who.setText(name);
        row.addView(who);
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
        return row;
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    /** Lays [root] out on a phone-sized screen, as a frame would. */
    /** The window's own size (the class's qualifiers), so a layout pass between two of these agrees on every width. */
    private static void lay(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 640);
    }

    private static List<String> titles(StoryViewersPage page) {
        List<String> titles = new ArrayList<>();
        for (int at = 0; at < page.list.getAdapter().getCount(); at++) {
            titles.add(((StoryViewersPage.Entry) page.list.getAdapter().getItem(at)).title);
        }
        return titles;
    }

    private static List<View> described(View view, String label) {
        List<View> found = new ArrayList<>();
        if (label.contentEquals(String.valueOf(view.getContentDescription()))) found.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int at = 0; at < group.getChildCount(); at++) found.addAll(described(group.getChildAt(at), label));
        }
        return found;
    }
}
