/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.text.Layout;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/**
 * The settings row in a narrow window at twice the text size: the title wraps rather than running
 * off the row, keeps its size, and in a right-to-left layout the mark and the chevron swap sides.
 * Text is measured with real fonts, which the default graphics mode doesn't do.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 34, 37})
public class SettingsScreenRowLayoutTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Before public void hostTarget() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
    }

    @After
    public void tearDown() throws Exception {
        RuntimeEnvironment.setFontScale(1f);
        SettingsEntry.onClosedByUser();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    public void theTitleWrapsAtTwiceTheTextSize() {
        ViewGroup row = rowAtTwiceTheTextSize();
        assertFits(row, "HushGram settings");
    }

    @Test
    @Config(qualifiers = "pt-rBR-w320dp-h640dp-night-xhdpi")
    public void aLongTranslationWrapsToo() {
        ViewGroup row = rowAtTwiceTheTextSize();
        assertFits(row, "Configurações do HushGram");
        assertTrue("one line held a translation this long at 200%", title(row).getLayout().getLineCount() > 1);
    }

    @Test
    @Config(qualifiers = "ldrtl-w320dp-h640dp-xhdpi")
    public void rightToLeftPutsTheMarkOnTheRight() {
        ViewGroup row = rowAtTwiceTheTextSize(true);
        assertFits(row, "HushGram settings");
        View mark = row.getChildAt(0);
        View chevron = row.getChildAt(2);
        assertEquals(View.LAYOUT_DIRECTION_RTL, row.getLayoutDirection());
        assertTrue("the title isn't left of the mark", title(row).getRight() <= mark.getLeft());
        assertTrue("the chevron isn't left of the title", chevron.getRight() <= title(row).getLeft());
    }

    private static ViewGroup rowAtTwiceTheTextSize() {
        return rowAtTwiceTheTextSize(false);
    }

    /**
     * The row on Settings and activity, laid out in the activity's window at a 200% font scale.
     * Instagram's window is right to left in a right-to-left language. The test app doesn't say it
     * supports that, so [rtl] turns its window around the way Instagram's is.
     */
    private static ViewGroup rowAtTwiceTheTextSize(boolean rtl) {
        RuntimeEnvironment.setFontScale(2f);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        if (rtl) activity.getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Bundle arguments = new Bundle();
        arguments.putSerializable(SettingsScreenRow.SCREEN_ID, SettingsScreenRowTest.Screen.MAIN_SETTINGS_SCREEN);
        ViewGroup column = (ViewGroup) SettingsEntry.withSettingsRow(arguments, new FrameLayout(activity));
        activity.setContentView(column);
        shadowOf(Looper.getMainLooper()).idle();
        ViewGroup row = (ViewGroup) column.getChildAt(0);
        assertEquals(SettingsScreenRow.TAG, row.getTag());
        return row;
    }

    private static TextView title(ViewGroup row) {
        return (TextView) row.getChildAt(1);
    }

    /**
     * The title reads [text] in full: every line inside the title's width, nothing cut short, the
     * title as tall as its lines, inside the row, and still 16 sp at the font scale.
     */
    private static void assertFits(ViewGroup row, String text) {
        TextView title = title(row);
        assertEquals(text, title.getText().toString());
        DisplayMetrics metrics = title.getResources().getDisplayMetrics();
        // Android 14 and newer grow large text less than small text, so 16 sp at 200% isn't 32 dp.
        assertEquals("the title's text was shrunk", TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, metrics),
                title.getTextSize(), 0.5f);
        assertTrue("the font scale didn't reach the title", title.getTextSize() >= 16f * 1.5f * metrics.density);

        Layout layout = title.getLayout();
        int room = title.getWidth() - title.getTotalPaddingLeft() - title.getTotalPaddingRight();
        assertTrue("the title has no room: " + room, room > 0);
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertEquals("line " + line + " was cut short", 0, layout.getEllipsisCount(line));
            assertTrue("line " + line + " runs " + layout.getLineWidth(line) + " px in " + room,
                    layout.getLineWidth(line) <= room + 0.5f);
        }
        assertEquals("the lines don't hold all of the title", text.length(), layout.getLineEnd(layout.getLineCount() - 1));
        assertTrue("the title is shorter than its lines", title.getHeight() >= layout.getHeight());
        assertTrue("the title runs past the row", title.getBottom() <= row.getHeight() - row.getPaddingBottom());
        assertTrue("the row is under 56 dp", row.getHeight() >= Math.round(56 * metrics.density));
    }
}
