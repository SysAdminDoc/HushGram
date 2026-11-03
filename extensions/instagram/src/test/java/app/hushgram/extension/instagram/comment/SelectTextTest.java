/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

import kotlin.jvm.functions.Function0;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAlertDialog;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Select text to copy (#492): the window the text opens in, and the row that opens it from a comment. */
@RunWith(RobolectricTestRunner.class)
public class SelectTextTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<?> stock = Collections.singletonList("Report");

    @Before public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SELECT_TEXT.save(true);
    }

    @After public void restore() {
        Settings.SELECT_TEXT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private static TextView textIn(View root) {
        if (root instanceof TextView && ((TextView) root).isTextSelectable()) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = textIn(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void theWindowHoldsTheTextSelectableWithLinksOn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SelectText.show(activity, "Caption", "line one\nsee https://example.com now");
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        TextView body = textIn(dialog.getWindow().getDecorView());
        assertNotNull("the text is in a selectable view", body);
        assertEquals("line one\nsee https://example.com now", body.getText().toString());
        assertTrue(body.getLinksClickable());
    }

    @Test public void aCommentGetsASelectRowOnceAndANewCommentReplacesIt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeNative natives = new FakeNative();
        List<?> rows = SelectText.rows(stock, new String[]{"first"}, activity, natives);
        assertEquals(2, rows.size());
        assertSame(stock.get(0), rows.get(0));
        List<?> again = SelectText.rows(rows, new String[]{"second"}, activity, natives);
        assertEquals("one Select row, not two", 2, again.size());
        assertNull(((Row) again.get(1)).callback.invoke());
        TextView body = textIn(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView());
        assertEquals("second", body.getText().toString());
    }

    @Test public void withTheSwitchOffNoRowIsAddedAndAnOldOneGoes() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeNative natives = new FakeNative();
        List<?> rows = SelectText.rows(stock, new String[]{"text"}, activity, natives);
        Settings.SELECT_TEXT.save(false);
        assertSame(stock, SelectText.rows(stock, new String[]{"text"}, activity, natives));
        assertEquals(stock, SelectText.rows(rows, new String[]{"text"}, activity, natives));
    }

    @Test public void pausedOrEmptyCommentsGetNoRow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeNative natives = new FakeNative();
        assertEquals(stock, SelectText.rows(stock, new String[]{""}, activity, natives));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(app.hushgram.extension.shared.settings.HushgramPause.Reason.SWITCH);
        assertFalse(SelectText.on());
        assertSame(stock, SelectText.rows(stock, new String[]{"text"}, activity, natives));
    }

    private static final class Row {
        final Function0<Object> callback;
        Row(Function0<Object> callback) { this.callback = callback; }
    }

    private static final class FakeNative implements SelectText.NativeRows {
        @Override public String text(Object comment) { return ((String[]) comment)[0]; }
        @SuppressWarnings("unchecked")
        @Override public Object row(Object callback) { return new Row((Function0<Object>) callback); }
        @Override public Object callback(Object row) { return row instanceof Row ? ((Row) row).callback : null; }
    }
}
