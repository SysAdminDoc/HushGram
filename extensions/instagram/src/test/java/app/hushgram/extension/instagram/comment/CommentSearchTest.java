/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
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
            down.performClick();
            ShadowLooper.idleMainLooper(CommentFinder.WAIT_MILLIS * (CommentFinder.WAITS + 1), TimeUnit.MILLISECONDS);
            assertEquals("No more matches", String.valueOf(status.getText()));
            assertNull(rows[3].getForeground());

            up.performClick();
            assertTrue("up goes back to the first bike", flashed(rows[1]));
            assertFalse(flashed(rows[3]));
            assertEquals(View.GONE, status.getVisibility());
        }
    }
}
