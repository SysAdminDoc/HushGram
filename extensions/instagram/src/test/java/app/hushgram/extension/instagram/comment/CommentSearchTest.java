/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

import app.hushgram.extension.shared.SettingsContextRule;

/** The search bar over a comments list (#560): each key and arrow looks once, from the last match on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentSearchTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Instagram's comments RecyclerView, which the bar finds by what it tells accessibility. */
    public static final class Comments extends LinearLayout {
        public Comments(Context context) {
            super(context);
            setOrientation(VERTICAL);
        }

        @Override
        public CharSequence getAccessibilityClassName() {
            return "androidx.recyclerview.widget.RecyclerView";
        }
    }

    private static boolean flashed(View row) {
        return row.getForeground() instanceof ColorDrawable;
    }

    @Test
    public void theSearchKeyAndOneEnterEachLookOnceAndTheArrowsGoBothWays() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Comments list = new Comments(activity);
            String[] words = {"alex: nice view", "sam: my bike is red", "kim: wow", "lee: which bike is that"};
            View[] rows = new View[words.length];
            for (int at = 0; at < words.length; at++) {
                TextView row = new TextView(activity);
                row.setText(words[at]);
                rows[at] = row;
                list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50));
            }
            FrameLayout container = new FrameLayout(activity);
            container.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ViewGroup bar = (ViewGroup) CommentSearch.box(activity, container);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(bar);
            column.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            activity.setContentView(column);
            ShadowLooper.idleMainLooper();
            column.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
            column.layout(0, 0, 320, 600);

            EditText field = (EditText) bar.getChildAt(0);
            TextView status = (TextView) bar.getChildAt(1);
            View up = bar.getChildAt(2);
            View down = bar.getChildAt(3);
            field.setText("BIKE");

            field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertTrue("the keyboard's search key lands on the first bike", flashed(rows[1]));
            assertFalse(flashed(rows[3]));

            field.onKeyDown(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            field.onKeyUp(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            assertTrue("one hardware Enter goes to the next bike", flashed(rows[3]));
            assertEquals("and stops there, rather than looking past it on the key's release",
                    View.GONE, status.getVisibility());

            ShadowLooper.idleMainLooper(CommentSearch.FLASH_MILLIS + 1, TimeUnit.MILLISECONDS);
            InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            field.requestFocus();
            keyboard.showSoftInput(field, 0);
            assertTrue(shadowOf(keyboard).isSoftInputVisible());
            down.performClick();
            assertFalse("an arrow closes the keyboard, so the list shows in full", shadowOf(keyboard).isSoftInputVisible());
            ShadowLooper.idleMainLooper(CommentFinder.WAIT_MILLIS * (CommentFinder.WAITS + 1), TimeUnit.MILLISECONDS);
            assertEquals("No more matches", String.valueOf(status.getText()));
            assertNull(rows[3].getForeground());

            up.performClick();
            assertTrue("up goes back to the first bike", flashed(rows[1]));
            assertFalse(flashed(rows[3]));
            assertEquals(View.GONE, status.getVisibility());
        }
    }

    private static void lay(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    @Test
    public void aFeedPostsListSlidingUpUnderTheAiRowGoesUnderTheBoxNotOverIt() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout sheet = new FrameLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setClipToPadding(false);
            View aiRow = new View(activity);
            column.addView(aiRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 63));
            FrameLayout container = new FrameLayout(activity);
            container.addView(new Comments(activity), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams slide = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            column.addView(container, slide);
            sheet.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(sheet);

            assertTrue(CommentSearch.place(container));
            assertTrue("a view made again keeps one box", CommentSearch.place(container));
            View box = sheet.findViewWithTag(CommentSearch.TAG);
            assertSame("the box sits in the sheet, over the column", sheet, box.getParent());
            assertEquals(sheet.indexOfChild(column) + 1, sheet.indexOfChild(box));
            assertEquals(2, sheet.getChildCount());

            // Instagram's scroll-away: the row goes up and the list follows it, an action bar deep.
            aiRow.setTranslationY(-112);
            slide.topMargin = -112;
            container.setLayoutParams(slide);
            lay(sheet, 320, 600);
            ShadowLooper.idleMainLooper();
            lay(sheet, 320, 600);

            assertTrue(box.getHeight() > 0);
            assertEquals("the column keeps room for the box", box.getHeight(), column.getPaddingTop());
            assertEquals(column.getTop() + column.getPaddingTop(), box.getBottom());
            assertTrue("the column clips what slides into that room", column.getClipToPadding());
            assertTrue("the list did slide up into it", container.getTop() < column.getPaddingTop());
            CommentSearch.ListHost host = new CommentSearch.ListHost(container, new TextView(activity), 0);
            assertEquals("a match lands below the box, not under it",
                    column.getPaddingTop() - container.getTop(), host.top());

            slide.topMargin = 0;
            container.setLayoutParams(slide);
            lay(sheet, 320, 600);
            assertEquals("with nothing slid under the box, rows start at the list's top", 0, host.top());
        }
    }

    @Test
    public void aColumnWithNoFrameAroundItGetsTheBoxRightAboveTheList() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout outer = new LinearLayout(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(new View(activity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 63));
            FrameLayout container = new FrameLayout(activity);
            column.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            outer.addView(column);
            activity.setContentView(outer);

            assertTrue(CommentSearch.place(container));
            View box = column.findViewWithTag(CommentSearch.TAG);
            assertSame(column, box.getParent());
            assertEquals(column.indexOfChild(container) - 1, column.indexOfChild(box));
            assertEquals(0, column.getPaddingTop());
        }
    }
}
