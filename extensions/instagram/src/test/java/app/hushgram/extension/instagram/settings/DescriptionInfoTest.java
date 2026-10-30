/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.preference.SwitchPreference;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

import app.hushgram.extension.shared.SettingsContextRule;

/** #130: a switch's description behind an "i" at the end of its title, and back again. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h480dp-mdpi")
// Real text widths, so the title is as wide as its words and the start of it is clear of the i.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class DescriptionInfoTest {
    private static final String DESCRIPTION = "Leaves out the HushGram row in Instagram's menu.";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private FrameLayout parent;

    @Before public void prepare() {
        Settings.DESCRIPTIONS_BEHIND_INFO.resetToDefault();
        controller = Robolectric.buildActivity(Activity.class).setup();
        parent = new FrameLayout(controller.get());
        controller.get().setContentView(parent);
    }

    @After public void close() {
        Settings.DESCRIPTIONS_BEHIND_INFO.resetToDefault();
        DescriptionInfo.searching = false;
        controller.close();
    }

    private SwitchPreference row() {
        SwitchPreference row = HushgramPreferenceFragment.toggle(controller.get(), Settings.HIDE_MENU_ROW,
                "Hide the HushGram row", DESCRIPTION);
        row.setPersistent(false);
        return row;
    }

    private View bind(SwitchPreference row, View recycled) {
        View view = row.getView(recycled, parent);
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        return view;
    }

    private static boolean press(TextView title, float x) {
        long now = SystemClock.uptimeMillis();
        float y = title.getHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        boolean taken = title.dispatchTouchEvent(down);
        title.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
        return taken;
    }

    private static void assertShownAsBefore(View view) {
        TextView title = view.findViewById(android.R.id.title);
        TextView summary = view.findViewById(android.R.id.summary);
        assertEquals("the description is under the title", View.VISIBLE, summary.getVisibility());
        assertEquals(DESCRIPTION, summary.getText().toString());
        assertNull("no i", title.getCompoundDrawablesRelative()[2]);
        assertNotEquals(DescriptionInfo.TAG, title.getTag());
    }

    @Test public void offLeavesTheDescriptionUnderTheTitle() {
        assertFalse("off to start", Settings.DESCRIPTIONS_BEHIND_INFO.savedValue());
        assertShownAsBefore(bind(row(), null));
    }

    @Test public void onPutsItBehindTheMarkAndATapThereShowsIt() {
        Settings.DESCRIPTIONS_BEHIND_INFO.save(true);
        View view = bind(row(), null);
        TextView title = view.findViewById(android.R.id.title);
        assertEquals("hidden from the row", View.GONE, view.findViewById(android.R.id.summary).getVisibility());
        assertTrue("an i at the end of the title", title.getCompoundDrawablesRelative()[2] instanceof DescriptionInfo.Mark);
        assertTrue("the title is wider than its i, " + title.getWidth(), title.getWidth() > 80);

        assertFalse("the start of the title is the row's to flip", DescriptionInfo.onMark(title, 1));
        press(title, 1);
        assertNull("no description for a tap off the i", ShadowAlertDialog.getLatestAlertDialog());

        assertTrue(press(title, title.getWidth() - 2));
        AlertDialog shown = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("a tap on the i shows the description", shown);
        assertTrue(shown.isShowing());
        assertEquals(DESCRIPTION, shadowOf(shown).getMessage().toString());
        assertEquals("Hide the HushGram row", shadowOf(shown).getTitle().toString());
    }

    @Test public void theMarkGrowsWithALargerFont() {
        Settings.DESCRIPTIONS_BEHIND_INFO.save(true);
        SwitchPreference row = row();
        View view = bind(row, null);
        TextView title = view.findViewById(android.R.id.title);
        int usual = title.getCompoundDrawablesRelative()[2].getBounds().width();
        // Android's bind puts the title back to its usual size, so the larger one goes straight
        // to the "i", as a larger font size reaches it on a phone.
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        DescriptionInfo.bind(view, DESCRIPTION);
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        int large = title.getCompoundDrawablesRelative()[2].getBounds().width();
        assertEquals("as tall as the title's letters", Math.round(title.getTextSize()), large);
        assertTrue("grew from " + usual, large > usual);
        assertTrue("a tap on the bigger i still shows the description", press(title, title.getWidth() - 2));
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void rightToLeftPutsTheMarkOnTheLeft() {
        controller.get().getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        Settings.DESCRIPTIONS_BEHIND_INFO.save(true);
        SwitchPreference row = row();
        View view = bind(row, null);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bind(row, view);
        TextView title = view.findViewById(android.R.id.title);
        assertEquals(View.LAYOUT_DIRECTION_RTL, title.getLayoutDirection());
        assertTrue("the title is wider than its i, " + title.getWidth(), title.getWidth() > 80);

        assertFalse("the title's start, on the right, is the row's to flip",
                DescriptionInfo.onMark(title, title.getWidth() - 1));
        press(title, title.getWidth() - 1);
        assertNull(ShadowAlertDialog.getLatestAlertDialog());

        assertTrue(press(title, 2));
        AlertDialog shown = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("a tap on the i at the left shows the description", shown);
        assertEquals(DESCRIPTION, shadowOf(shown).getMessage().toString());
    }

    @Test public void aRecycledRowGetsItsDescriptionBack() {
        SwitchPreference row = row();
        Settings.DESCRIPTIONS_BEHIND_INFO.save(true);
        View recycled = bind(row, null);
        Settings.DESCRIPTIONS_BEHIND_INFO.save(false);
        View view = bind(row, recycled);
        assertSame("Android handed the same row back", recycled, view);
        assertShownAsBefore(view);
        press(view.findViewById(android.R.id.title), view.findViewById(android.R.id.title).getWidth() - 2);
        assertNull("no i left to tap", ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void aGreyedOutRowKeepsTheLineSayingWhy() {
        Settings.DESCRIPTIONS_BEHIND_INFO.save(true);
        SwitchPreference row = row();
        row.setEnabled(false);
        assertShownAsBefore(bind(row, null));
    }

    @Test public void aScreenReaderExploringByTouchKeepsTheFullText() {
        Settings.DESCRIPTIONS_BEHIND_INFO.save(true);
        shadowOf(controller.get().getSystemService(AccessibilityManager.class)).setTouchExplorationEnabled(true);
        assertShownAsBefore(bind(row(), null));
    }

    @Test public void aSearchShowsTheDescriptionsItMatched() {
        Settings.DESCRIPTIONS_BEHIND_INFO.save(true);
        DescriptionInfo.searching = true;
        assertShownAsBefore(bind(row(), null));
    }

    @Test public void aRowWithNoDescriptionGetsNoMark() {
        Settings.DESCRIPTIONS_BEHIND_INFO.save(true);
        SwitchPreference row = row();
        row.setSummary(null);
        View view = bind(row, null);
        assertNull(((TextView) view.findViewById(android.R.id.title)).getCompoundDrawablesRelative()[2]);
    }

    @Test public void theChoiceTravelsInABackup() {
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.DESCRIPTIONS_BEHIND_INFO.key));
    }
}
