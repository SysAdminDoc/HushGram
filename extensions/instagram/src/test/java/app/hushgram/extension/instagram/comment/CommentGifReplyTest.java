/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import kotlin.jvm.functions.Function0;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** #402: a GIF reply in a selected comment's menu, sent through Instagram's own Reply and GIF send. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentGifReplyTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<?> stock = Collections.singletonList("Report");
    private final FakeNative nativeRows = new FakeNative();
    private final FakeComposer composer = new FakeComposer();
    private final Object screen = new Object();
    private final Object comment = new Object();
    private final Object gif = new Object();
    private Activity activity;
    private Context context;

    @Before public void enable() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        // The menu's context is a wrapper around the Activity, as Instagram's themed bottom sheet hands it.
        context = new ContextWrapper(new ContextWrapper(activity));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.REPLY_WITH_COMMENT_GIFS.save(true);
        nativeRows.gifs.put(comment, gif);
        CommentGifReply.selecting(screen, comment, "17900000000000001", "17900000000000000");
        HookStatus.clear();
        ShadowToast.reset();
    }

    @After public void restore() {
        CommentGifReply.selecting(null, null, null, null);
        Settings.REPLY_WITH_COMMENT_GIFS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        HookStatus.clear();
    }

    private List<?> rows(List<?> rows, Object selected) {
        return CommentGifReply.rows(rows, selected, context, nativeRows, composer);
    }

    private static List<String> counted(String counts) {
        return Collections.singletonList(FamilyNames.COMMENT_PHOTO + ": invoked 0, 0 found, 0 missing. Counted: " + counts);
    }

    private String latestToast() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    @Test public void tapRepliesToTheSelectedCommentThenSendsItsGif() {
        List<?> rows = rows(stock, comment);
        assertEquals(2, rows.size());
        assertSame(stock.get(0), rows.get(0));
        assertTrue(((Row) rows.get(1)).callback instanceof CommentGifReply.ReplyAction);
        assertTrue("showing the menu sends nothing", composer.calls.isEmpty());
        assertNull(((Row) rows.get(1)).callback.invoke());
        assertEquals(Arrays.asList("info", "target", "reply 17900000000000001 17900000000000000", "target", "send"), composer.calls);
        assertSame(gif, composer.converted);
        assertSame(screen, composer.sentOn);
        assertSame("the Activity under the menu's wrappers", activity, composer.sentFrom);
        assertSame(composer.info, composer.sent);
        assertEquals(counted(CommentGifReply.SENT + " 1"), HookStatus.report());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowToast.getLatestToast());
    }

    @Test public void aReplyThatDoesntTakeTheCommentSendsNothing() {
        composer.takes = false;
        assertNull(((Row) rows(stock, comment).get(1)).callback.invoke());
        assertEquals(Arrays.asList("info", "target", "reply 17900000000000001 17900000000000000", "target"), composer.calls);
        assertNull(composer.sent);
        assertEquals(counted(CommentGifReply.NO_TARGET + " 1"), HookStatus.report());
        assertEquals("Couldn't reply with this GIF", latestToast());

        HookStatus.clear();
        composer.calls.clear();
        composer.current = null;
        assertNull(((Row) rows(stock, comment).get(1)).callback.invoke());
        assertNull("no target at all is no reply either", composer.sent);
        assertEquals(counted(CommentGifReply.NO_TARGET + " 1"), HookStatus.report());
    }

    @Test public void noScreenActivityOrSendModelStopsBeforeReply() {
        composer.info = null;
        assertNull(((Row) rows(stock, comment).get(1)).callback.invoke());
        assertEquals(Collections.singletonList("info"), composer.calls);
        assertEquals("Couldn't reply with this GIF", latestToast());

        composer.info = new Object();
        composer.calls.clear();
        ShadowToast.reset();
        Context application = RuntimeEnvironment.getApplication();
        List<?> rows = CommentGifReply.rows(stock, comment, application, nativeRows, composer);
        assertNull(((Row) rows.get(1)).callback.invoke());
        assertEquals("no Activity under the menu", Collections.singletonList("info"), composer.calls);
        assertEquals("Couldn't reply with this GIF", latestToast());
        assertNull(composer.sent);
    }

    @Test public void onlyTheCommentLastSelectedGetsTheRow() {
        Object other = new Object();
        nativeRows.gifs.put(other, new Object());
        assertSame("another comment's menu would reply to the wrong one", stock, rows(stock, other));
        CommentGifReply.selecting(screen, other, "2", "1");
        assertSame(stock, rows(stock, comment));
        assertEquals(2, rows(stock, other).size());
        CommentGifReply.selecting(null, other, "2", "1");
        assertSame("a selection without its screen is dropped", stock, rows(stock, other));
        assertEquals("only the menu's own comment is asked for its GIF", 1, nativeRows.read);
    }

    @Test public void repeatedChangedAndGiflessMenusKeepStockRowsAndOneOwnedRow() {
        List<?> rows = rows(stock, comment);
        assertSame(rows, rows(rows, comment));
        List<?> duplicated = Collections.unmodifiableList(Arrays.asList(stock.get(0), rows.get(1), rows.get(1)));
        List<?> repaired = rows(duplicated, comment);
        assertEquals(2, repaired.size());
        assertSame(stock.get(0), repaired.get(0));
        assertEquals(3, duplicated.size());
        CommentGifReply.selecting(screen, comment, "17900000000000001", "17900000000000000");
        List<?> reselected = rows(rows, comment);
        assertEquals("a new selection gets a fresh row", 2, reselected.size());
        assertNotSame(rows.get(1), reselected.get(1));
        nativeRows.gifs.remove(comment);
        List<?> cleaned = rows(duplicated, comment);
        assertEquals(Collections.singletonList(stock.get(0)), cleaned);
        assertSame(cleaned, rows(cleaned, comment));
        assertEquals(3, nativeRows.created);
        assertTrue(composer.calls.isEmpty());
    }

    @Test public void offPausedUnreadyAndMissingInputsLeaveStockUntouched() {
        Settings.REPLY_WITH_COMMENT_GIFS.save(false);
        assertSame(stock, rows(stock, comment));
        Settings.REPLY_WITH_COMMENT_GIFS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, rows(stock, comment));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertSame(stock, rows(stock, comment)));
        assertSame(stock, CommentGifReply.rows(stock, comment, null, nativeRows, composer));
        assertSame(stock, rows(stock, null));
        assertNull(rows(null, comment));
        assertSame("the unpatched stubs find no GIF", stock, CommentGifReply.rows(stock, comment, context));
        assertEquals(0, nativeRows.created);
        assertEquals(0, nativeRows.read);
    }

    @Test public void aSwitchTurnedOffAfterTheMenuOpenedSendsNothing() {
        List<?> rows = rows(stock, comment);
        Settings.REPLY_WITH_COMMENT_GIFS.save(false);
        assertNull(((Row) rows.get(1)).callback.invoke());
        Settings.REPLY_WITH_COMMENT_GIFS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull(((Row) rows.get(1)).callback.invoke());
        assertTrue(composer.calls.isEmpty());
    }

    @Test public void rowAndComposerFailuresStayContained() {
        nativeRows.failRow = true;
        assertSame(stock, rows(stock, comment));
        nativeRows.failRow = false;
        nativeRows.nullRow = true;
        assertSame(stock, rows(stock, comment));
        nativeRows.nullRow = false;
        composer.failReply = true;
        assertNull(((Row) rows(stock, comment).get(1)).callback.invoke());
        assertNull(composer.sent);
        assertEquals("Couldn't reply with this GIF", latestToast());
    }

    @Test public void theActivityIsFoundThroughItsWrappers() {
        assertSame(activity, CommentGifReply.activity(activity));
        assertSame(activity, CommentGifReply.activity(context));
        assertNull(CommentGifReply.activity(RuntimeEnvironment.getApplication()));
        assertNull(CommentGifReply.activity(null));
        assertNull("a wrapper around nothing", CommentGifReply.activity(new ContextWrapper(null)));
    }

    static final class Row {
        final Function0<?> callback;
        Row(Object callback) { this.callback = (Function0<?>) callback; }
    }

    static final class FakeNative implements CommentGifReply.NativeRows {
        final Map<Object, Object> gifs = new IdentityHashMap<>();
        int created;
        int read;
        boolean failRow, nullRow;
        public Object gif(Object comment) { read++; return gifs.get(comment); }
        public Object row(Object callback) {
            if (failRow) throw new IllegalStateException("row failed");
            if (nullRow) return null;
            created++;
            return new Row(callback);
        }
        public Object callback(Object row) { return row instanceof Row ? ((Row) row).callback : null; }
    }

    /** The comment screen: Reply makes a new target each time it takes a comment, as Instagram's does. */
    static final class FakeComposer implements CommentGifReply.Composer {
        final List<String> calls = new ArrayList<>();
        Object info = new Object();
        Object current = new Object();
        boolean takes = true, failReply;
        Object converted, sentOn, sentFrom, sent;
        public Object info(Object gif) { calls.add("info"); converted = gif; return info; }
        public Object target(Object screen) { calls.add("target"); return current; }
        public void reply(Object screen, String id, String parent) {
            calls.add("reply " + id + " " + parent);
            if (failReply) throw new IllegalStateException("reply failed");
            if (takes) current = new Object();
        }
        public void send(Object screen, Object activity, Object info) {
            calls.add("send");
            sentOn = screen;
            sentFrom = activity;
            sent = info;
        }
    }
}
