/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import kotlin.jvm.functions.Function0;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Copy username in a selected comment's menu (#35). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentAuthorTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private final FakeNative nativeRows = new FakeNative();
    private final List<?> stock = Collections.singletonList("Report");

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.COPY_COMMENT_AUTHORS.save(true);
    }

    @After public void restore() {
        Settings.COPY_COMMENT_AUTHORS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    private ClipboardManager clipboard() {
        return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    @Test public void aTapCopiesTheCommentersUsernameExactlyAndMarksItSensitive() {
        String[] comment = {"some.person_99"};
        List<?> rows = CommentAuthor.rows(stock, comment, context, nativeRows);
        assertEquals(2, rows.size());
        assertSame(stock.get(0), rows.get(0));
        assertFalse("adding the row must not copy", clipboard().hasPrimaryClip());
        assertNull("stock callback may continue to dismissal", ((Row) rows.get(1)).callback.invoke());
        ClipData clip = clipboard().getPrimaryClip();
        assertNotNull(clip);
        assertEquals("some.person_99", clip.getItemAt(0).getText().toString());
        assertTrue(clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
    }

    @Test public void repeatedMenusKeepOneRowAndANewCommentReplacesIt() {
        List<?> rows = CommentAuthor.rows(stock, new String[]{"first"}, context, nativeRows);
        assertSame(rows, CommentAuthor.rows(rows, new String[]{"first"}, context, nativeRows));
        List<?> changed = CommentAuthor.rows(Arrays.asList(stock.get(0), rows.get(1), rows.get(1)),
                new String[]{"second"}, context, nativeRows);
        assertEquals(2, changed.size());
        ((Row) changed.get(1)).callback.invoke();
        assertEquals("second", clipboard().getPrimaryClip().getItemAt(0).getText().toString());
        List<?> cleaned = CommentAuthor.rows(rows, new String[]{null}, context, nativeRows);
        assertEquals("a comment with no author keeps no stale row", Collections.singletonList(stock.get(0)), cleaned);
    }

    @Test public void offPausedUnreadyAndUnpatchedMenusAreUntouched() {
        Settings.COPY_COMMENT_AUTHORS.save(false);
        assertSame(stock, CommentAuthor.rows(stock, new String[]{"name"}, context, nativeRows));
        Settings.COPY_COMMENT_AUTHORS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, CommentAuthor.rows(stock, new String[]{"name"}, context, nativeRows));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertSame(stock, CommentAuthor.rows(stock, new String[]{"name"}, context, nativeRows)));
        assertSame(stock, CommentAuthor.rows(stock, new String[]{""}, context, nativeRows));
        // The stubs a build without the author's boundaries keeps answer null, so no row goes in.
        assertSame(stock, CommentAuthor.rows(stock, new String[]{"name"}, context));
        assertEquals(0, nativeRows.created);
        assertFalse(clipboard().hasPrimaryClip());
        Settings.COPY_COMMENT_AUTHORS.save(false);
        new CommentAuthor.CopyAction("must not copy after disabling", context).invoke();
        assertFalse(clipboard().hasPrimaryClip());
    }

    @Test public void onlyTheUsernameRowGetsItsOwnLabel() {
        int copy = android.R.string.copy;
        assertEquals("Copy username", CommentAuthor.label(context, copy, new AuthorRow(null, null, null, null)));
        assertEquals(context.getString(copy), CommentAuthor.label(context, copy, new CopyRow(null, null, null, null)));
        assertEquals(context.getString(copy), CommentAuthor.label(context, copy, null));
        assertThrows("a stock row's bad id fails as it did", android.content.res.Resources.NotFoundException.class,
                () -> CommentAuthor.label(context, 0, new Object()));
    }

    static final class Row {
        final Function0<?> callback;
        Row(Object callback) { this.callback = (Function0<?>) callback; }
    }
    static final class FakeNative implements CommentAuthor.NativeRows {
        int created;
        public String username(Object comment) { return comment instanceof String[] ? ((String[]) comment)[0] : null; }
        public Object row(Object callback) { created++; return new Row(callback); }
        public Object callback(Object row) { return row instanceof Row ? ((Row) row).callback : null; }
    }
}
