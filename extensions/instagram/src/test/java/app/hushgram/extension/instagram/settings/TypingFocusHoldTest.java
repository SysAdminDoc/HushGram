/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

/** A field typed into inside a legacy ListView keeps the focus while the list re-lays its rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
public class TypingFocusHoldTest {
    private static final String[] TYPED = {"s", "sp", "spo", "spoof", "spo", "", "loc"};
    private ActivityController<Activity> controller;

    @After public void close() {
        if (controller != null) controller.close();
    }

    @Test public void takesTheListsPlaceWithItsLayout() {
        Page page = open(false);
        assertTrue(page.list.getParent() instanceof TypingFocusHold);
        View hold = (View) page.list.getParent();
        assertSame(page.root, hold.getParent());
        assertEquals(1, page.root.indexOfChild(hold));
        assertSame(page.params, hold.getLayoutParams());
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, page.list.getLayoutParams().height);
        TypingFocusHold.around(page.list);
        assertSame("a second wrap leaves the first hold alone", hold, page.list.getParent());
    }

    @Test public void aReusedFieldKeepsTheFocusThroughEveryLayout() {
        for (boolean touch : new boolean[]{true, false}) {
            Page page = open(touch);
            EditText field = page.root.findViewWithTag(Page.FIELD);
            assertTrue(field.requestFocus());
            ShadowLooper.idleMainLooper();
            List<String> moves = new ArrayList<>();
            page.root.getViewTreeObserver().addOnGlobalFocusChangeListener((from, to) ->
                    moves.add(name(from) + " -> " + name(to)));
            for (String typed : TYPED) {
                page.type(field, typed);
                String at = (touch ? "touch" : "keys") + " at \"" + typed + "\"";
                assertSame(at, field, page.root.findViewWithTag(Page.FIELD));
                assertTrue(at, field.isFocused());
                assertEquals(at, typed.length(), field.getSelectionEnd());
            }
            assertEquals((touch ? "touch" : "keys") + ": the focus moved while typing", "[]", moves.toString());
        }
    }

    @Test public void aRebuiltFieldTakesTheFocusOverWithItsCaret() {
        for (boolean touch : new boolean[]{true, false}) {
            Page page = open(touch);
            page.rows.rebuild = true;
            assertTrue(((View) page.root.findViewWithTag(Page.FIELD)).requestFocus());
            ShadowLooper.idleMainLooper();
            // Any layout since builds the row again, so type into the field on the screen now.
            EditText field = page.root.findViewWithTag(Page.FIELD);
            assertTrue(field.isFocused());
            for (String typed : TYPED) {
                for (int caret : new int[]{typed.length(), typed.length() / 2}) {
                    page.type(field, typed, caret);
                    String at = (touch ? "touch" : "keys") + " at \"" + typed + "\", caret " + caret;
                    EditText now = page.root.findViewWithTag(Page.FIELD);
                    assertNotSame(at + ": the row should have been built again", field, now);
                    assertSame(at, now, page.root.findFocus());
                    assertEquals(at, typed, now.getText().toString());
                    assertEquals(at, caret, now.getSelectionStart());
                    assertEquals(at, caret, now.getSelectionEnd());
                    field = now;
                }
            }
        }
    }

    private Page open(boolean touch) {
        if (controller != null) controller.close();
        controller = Robolectric.buildActivity(Activity.class).setup();
        Page page = new Page(controller.get());
        // The window would take the change at a later relayout, so it switches now. Entering touch
        // mode lays the list out directly, outside the hold, which on a phone takes a tap, not typing.
        new Instrumentation().setInTouchMode(touch);
        ReflectionHelpers.callInstanceMethod(ReflectionHelpers.callInstanceMethod(page.root, "getViewRootImpl"),
                "ensureTouchMode", ReflectionHelpers.ClassParameter.from(boolean.class, touch));
        assertEquals(touch, page.root.isInTouchMode());
        page.layout();
        return page;
    }

    private static String name(View view) {
        return view == null ? "nothing" : view.getClass().getSimpleName();
    }

    /** A title over a list whose first row holds the field, like the settings page's search row. */
    private static final class Page {
        static final String FIELD = "typing-focus-hold-field";
        final LinearLayout root;
        final ListView list;
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        final Rows rows;

        Page(Activity activity) {
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(activity);
            title.setText("Settings");
            root.addView(title);
            list = new ListView(activity);
            list.setItemsCanFocus(true);
            rows = new Rows(activity);
            list.setAdapter(rows);
            root.addView(list, params);
            TypingFocusHold.around(list);
            activity.setContentView(root);
        }

        void type(EditText field, String typed) {
            type(field, typed, typed.length());
        }

        void type(EditText field, String typed, int caret) {
            field.setText(typed);
            field.setSelection(caret);
            rows.query = typed;
            rows.notifyDataSetChanged();
            ShadowLooper.idleMainLooper();
            layout();
            ShadowLooper.idleMainLooper();
        }

        void layout() {
            float density = root.getResources().getDisplayMetrics().density;
            int width = Math.round(320 * density);
            int height = Math.round(640 * density);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
        }
    }

    /** The field's row comes back as the same view, or, with [rebuild], as a new one each time. */
    private static final class Rows extends BaseAdapter {
        private final Activity activity;
        private LinearLayout searchRow;
        boolean rebuild;
        String query = "";

        Rows(Activity activity) {
            this.activity = activity;
        }

        @Override public int getCount() {
            return 1 + (query.isEmpty() ? 6 : 2);
        }

        @Override public Object getItem(int position) {
            return position;
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public int getViewTypeCount() {
            return 2;
        }

        @Override public int getItemViewType(int position) {
            return position == 0 ? (rebuild ? IGNORE_ITEM_VIEW_TYPE : 0) : 1;
        }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            if (position > 0) {
                TextView row = convert instanceof TextView ? (TextView) convert : new TextView(activity);
                row.setText(query + " result " + position);
                row.setMinHeight(96);
                return row;
            }
            if (searchRow != null && !rebuild) return searchRow;
            searchRow = new LinearLayout(activity);
            searchRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            EditText field = new EditText(activity);
            field.setTag(Page.FIELD);
            field.setSingleLine(true);
            field.setText(query);
            searchRow.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return searchRow;
        }
    }
}
