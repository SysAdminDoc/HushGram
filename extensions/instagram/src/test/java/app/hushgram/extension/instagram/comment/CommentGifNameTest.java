/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import android.os.Looper;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** #502: a comment GIF's or sticker's name, as a row in the comment's menu. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentGifNameTest {
    private static final class Row {
        final Object callback;
        Row(Object callback) { this.callback = callback; }
    }

    private static final class FakeNative implements CommentGifName.NativeRows {
        final Map<Object, String> names = new IdentityHashMap<>();
        public String name(Object comment) { return names.get(comment); }
        public Object row(Object callback) { return new Row(callback); }
        public Object callback(Object row) { return row instanceof Row ? ((Row) row).callback : null; }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final FakeNative nativeRows = new FakeNative();
    private final List<?> stock = Collections.singletonList("Report");
    private final Object comment = new Object();
    private Context context;

    @Before public void enable() {
        context = Robolectric.buildActivity(Activity.class).setup().get();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SHOW_COMMENT_GIF_NAMES.save(true);
        nativeRows.names.put(comment, "Happy Dance GIF by Someone");
        ShadowToast.reset();
    }

    @After public void restore() {
        Settings.SHOW_COMMENT_GIF_NAMES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
    }

    private List<?> rows(List<?> rows, Object selected) {
        return CommentGifName.rows(rows, selected, context, nativeRows);
    }

    private static Row nameRow(List<?> rows) {
        return (Row) rows.get(rows.size() - 1);
    }

    @Test public void theMenuShowsTheNameAsARowOfItsOwnAfterTheStockRows() {
        List<?> rows = rows(stock, comment);
        assertEquals(2, rows.size());
        assertEquals("Report", rows.get(0));
        assertEquals("the label hook answers the name itself", "Happy Dance GIF by Someone", CommentGifName.nameOf(nameRow(rows).callback));
    }

    @Test public void aTapCopiesTheName() {
        CommentGifName.NameAction action = (CommentGifName.NameAction) nameRow(rows(stock, comment)).callback;
        assertNull(action.invoke());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("Happy Dance GIF by Someone", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals("GIF name copied", ShadowToast.getTextOfLatestToast());
    }

    @Test public void offOrPausedOrNamelessAddsNothing() {
        assertEquals("no name", Collections.singletonList("Report"), new ArrayList<Object>(rows(stock, new Object())));
        for (String blank : Arrays.asList("", "   ")) {
            Object nameless = new Object();
            nativeRows.names.put(nameless, blank);
            assertEquals(1, rows(stock, nameless).size());
        }
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", stock, rows(stock, comment));
        PauseForTests.resume();
        Settings.SHOW_COMMENT_GIF_NAMES.save(false);
        assertSame("off", stock, rows(stock, comment));
    }

    @Test public void anEarlierRowIsReplacedOrTakenOffNotStacked() {
        List<?> once = rows(stock, comment);
        List<?> twice = rows(once, comment);
        assertEquals("one row after a second pass", 2, twice.size());
        Settings.SHOW_COMMENT_GIF_NAMES.save(false);
        List<Object> after = new ArrayList<>(rows(twice, comment));
        assertEquals("switched off, the row goes", Collections.singletonList("Report"), after);
    }

    @Test public void aStockRowHasNoName() {
        assertNull(CommentGifName.nameOf(null));
        assertNull(CommentGifName.nameOf(new Object()));
    }
}
