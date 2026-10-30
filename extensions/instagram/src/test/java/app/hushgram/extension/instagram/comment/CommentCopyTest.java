/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import static org.junit.Assert.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CommentCopyTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private final FakeNative nativeRows = new FakeNative();
    private final List<?> stock = Collections.singletonList("Report");

    @Before public void enable() {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().targetSdkVersion = 37;
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.COPY_COMMENTS.save(true);
    }

    @After public void restore() {
        Settings.COPY_COMMENTS.resetToDefault();
        Settings.COPY_COMMENTS_WITHOUT_MENTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
    }

    private ClipboardManager clipboard() {
        return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    @Test public void explicitTapCopiesTheOriginalVerbatimAndMarksItSensitive() {
        String text = "  @person #tag\n<b>literal &amp;</b> 😃\nالعربية\t  ";
        String[] comment = {text, "translated and shorter"};
        List<?> rows = CommentCopy.rows(stock, comment, context, nativeRows);
        assertEquals(2, rows.size());
        assertFalse("augmentation must not copy", clipboard().hasPrimaryClip());
        comment[0] = "edited after this menu opened";
        assertNull("stock callback may continue to dismissal", ((Row) rows.get(1)).callback.invoke());
        ClipData clip = clipboard().getPrimaryClip();
        assertNotNull(clip);
        assertEquals(text, clip.getItemAt(0).getText().toString());
        assertTrue(clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
        assertEquals(37, context.getApplicationInfo().targetSdkVersion);
    }

    @Test public void immutableAndRepeatedMenusKeepStockIdentityAndOnlyOneOwnedRow() {
        List<?> rows = CommentCopy.rows(stock, new String[]{"first"}, context, nativeRows);
        assertEquals(Collections.singletonList("Report"), stock);
        assertSame(stock.get(0), rows.get(0));
        assertSame(rows, CommentCopy.rows(rows, new String[]{"first"}, context, nativeRows));
        List<?> duplicated = Arrays.asList(stock.get(0), rows.get(1), rows.get(1));
        List<?> repaired = CommentCopy.rows(duplicated, new String[]{"first"}, context, nativeRows);
        assertEquals(2, repaired.size());
        assertEquals(3, duplicated.size());
        List<?> changed = CommentCopy.rows(rows, new String[]{"second"}, context, nativeRows);
        assertEquals(2, changed.size());
        assertSame(stock.get(0), changed.get(0));
        assertEquals("first", ((CommentCopy.CopyAction) ((Row) rows.get(1)).callback).text);
        ((Row) changed.get(1)).callback.invoke();
        assertEquals("second", clipboard().getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test public void offPausedUnreadyAndUnsupportedMenusAreUntouched() {
        Settings.COPY_COMMENTS.save(false);
        assertSame(stock, CommentCopy.rows(stock, new String[]{"text"}, context, nativeRows));
        Settings.COPY_COMMENTS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, CommentCopy.rows(stock, new String[]{"text"}, context, nativeRows));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertSame(stock, CommentCopy.rows(stock, new String[]{"text"}, context, nativeRows)));
        assertSame(stock, CommentCopy.rows(stock, new Object(), context, nativeRows));
        assertSame(stock, CommentCopy.rows(stock, new String[]{"text"}, null, nativeRows));
        assertSame(stock, CommentCopy.rows(stock, new String[]{"text"}, context));
        assertFalse(clipboard().hasPrimaryClip());
        assertEquals(0, nativeRows.created);
    }

    @Test public void initialOffAndSafetyGatesNeverInspectNativeCommentRows() {
        Settings.COPY_COMMENTS.resetToDefault();
        assertSame(stock, CommentCopy.rows(stock, new String[]{"original"}, context, nativeRows));
        assertSame(null, CommentCopy.rows(null, new String[]{"original"}, context, nativeRows));
        Settings.COPY_COMMENTS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(stock, CommentCopy.rows(stock, new String[]{"original"}, context, nativeRows));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertSame(stock, CommentCopy.rows(stock, new String[]{"original"}, context, nativeRows)));
        SettingsContextRule.beforeThePauseIsDecided(() ->
                assertSame(stock, CommentCopy.rows(stock, new String[]{"original"}, context, nativeRows)));
        assertEquals("no original-text getter or native row callback", 0, nativeRows.inspected);
        assertEquals(0, nativeRows.created);
        assertFalse(clipboard().hasPrimaryClip());
    }

    /** #270: with the switch on, a reply's leading @names stay out of the copy, read when Copy is tapped. */
    @Test public void theMentionSwitchLeavesOutTheNamesAReplyStartsWith() {
        String reply = "@user1 It's Breaking Bad";
        List<?> rows = CommentCopy.rows(stock, new String[]{reply}, context, nativeRows);
        ((Row) rows.get(1)).callback.invoke();
        assertEquals("off to start, the copy is verbatim", reply, clipboard().getPrimaryClip().getItemAt(0).getText().toString());
        Settings.COPY_COMMENTS_WITHOUT_MENTIONS.save(true);
        ((Row) rows.get(1)).callback.invoke();
        assertEquals("It's Breaking Bad", clipboard().getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals("the menu keeps the original", reply, ((CommentCopy.CopyAction) ((Row) rows.get(1)).callback).text);
        assertSame("an open menu isn't rebuilt", rows, CommentCopy.rows(rows, new String[]{reply}, context, nativeRows));
    }

    @Test public void onlyTheLeadingNamesGoAndATextOfNamesStaysWhole() {
        String[][] cases = {
                {"@user1 It's Breaking Bad", "It's Breaking Bad"},
                {"  @a.b_c @d9\n@e, Dark\nseason 2 ", "Dark\nseason 2 "},
                {"@name: the second one", "the second one"},
                {"@name" + (char) 0xA0 + "nbsp after", "nbsp after"},
                {"Ask @user1 about it", "Ask @user1 about it"},
                {"@user1's pick is better", "@user1's pick is better"},
                {"@a @b's pick", "@b's pick"},
                {"@ alone", "@ alone"},
                {"email@example.com", "email@example.com"},
                {"@user1", "@user1"},
                {"@user1 @user2  \n", "@user1 @user2  \n"},
                {"#tag @user1 text", "#tag @user1 text"},
                {"", ""},
        };
        for (String[] each : cases) {
            assertEquals(each[0], each[1], CommentCopy.withoutLeadingMentions(each[0]));
        }
    }

    @Test public void emptyTextGetsNoRowAndWhitespaceIsNeverTrimmed() {
        assertSame(stock, CommentCopy.rows(stock, new String[]{null}, context, nativeRows));
        assertSame(stock, CommentCopy.rows(stock, new String[]{""}, context, nativeRows));
        List<?> rows = CommentCopy.rows(stock, new String[]{" \n "}, context, nativeRows);
        ((Row) rows.get(1)).callback.invoke();
        assertEquals(" \n ", clipboard().getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test public void emptyOrNullCurrentTextRemovesStaleOwnedRowsFromImmutableMenus() {
        List<?> first = CommentCopy.rows(stock, new String[]{"first"}, context, nativeRows);
        List<?> duplicated = Collections.unmodifiableList(Arrays.asList(first.get(1), stock.get(0), first.get(1)));
        for (String text : new String[]{"", null}) {
            List<?> cleaned = CommentCopy.rows(duplicated, new String[]{text}, context, nativeRows);
            assertEquals(1, cleaned.size());
            assertSame(stock.get(0), cleaned.get(0));
            assertSame(cleaned, CommentCopy.rows(cleaned, new String[]{text}, context, nativeRows));
            assertEquals(3, duplicated.size());
            assertEquals("first", ((CommentCopy.CopyAction) ((Row) first.get(1)).callback).text);
        }
        assertFalse(clipboard().hasPrimaryClip());
        assertEquals(1, nativeRows.created);
    }

    @Test public void staleMenusAreUntouchedWhileOffPausedOrUnreadyAndFailuresRemainContained() {
        List<?> first = CommentCopy.rows(stock, new String[]{"first"}, context, nativeRows);
        Settings.COPY_COMMENTS.save(false);
        assertSame(first, CommentCopy.rows(first, new String[]{""}, context, nativeRows));
        Settings.COPY_COMMENTS.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(first, CommentCopy.rows(first, new String[]{null}, context, nativeRows));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertSame(first, CommentCopy.rows(first, new String[]{""}, context, nativeRows)));
        nativeRows.fail = true;
        assertSame(first, CommentCopy.rows(first, new String[]{""}, context, nativeRows));
        assertFalse(clipboard().hasPrimaryClip());
        assertSame(stock, CommentCopy.rows(stock, new Object(), context, new FakeNative()));
    }

    @Test public void discoveryAndClipboardFailuresReturnToNativeDismissal() {
        nativeRows.fail = true;
        assertSame(stock, CommentCopy.rows(stock, new String[]{"text"}, context, nativeRows));
        nativeRows.fail = false;
        Context denied = new ContextWrapper(context) {
            @Override public Object getSystemService(String name) {
                if (CLIPBOARD_SERVICE.equals(name)) throw new SecurityException("clipboard denied");
                return super.getSystemService(name);
            }
        };
        List<?> rows = CommentCopy.rows(stock, new String[]{"private text"}, denied, nativeRows);
        assertNull("clipboard failure must return to stock dismissal", ((Row) rows.get(1)).callback.invoke());
        assertFalse(clipboard().hasPrimaryClip());
        Settings.COPY_COMMENTS.save(false);
        new CommentCopy.CopyAction("must not copy after disabling", context).invoke();
        assertFalse(clipboard().hasPrimaryClip());
    }

    static final class Row {
        final Function0<?> callback;
        Row(Object callback) { this.callback = (Function0<?>) callback; }
    }
    static final class FakeNative implements CommentCopy.NativeRows {
        int created;
        int inspected;
        boolean fail;
        public String text(Object comment) {
            inspected++;
            if (fail) throw new IllegalStateException("native read failed");
            return comment instanceof String[] ? ((String[]) comment)[0] : null;
        }
        public Object row(Object callback) { created++; return new Row(callback); }
        public Object callback(Object row) { inspected++; return row instanceof Row ? ((Row) row).callback : null; }
    }
}
