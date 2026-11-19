/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** #756: the kinds of notification row kept for Hide notifications by type, the line each one lists, and the setting. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class NotificationKindsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;

    /** Shaped like Instagram 456's row: ids and names of its own, and its words one level in. */
    static final class Row {
        final String id = "3757001234567890123";
        final String type = "comment_like";
        final int count = 2;
        final Args args;
        Row(Args args) {
            this.args = args;
        }
    }

    static final class Args {
        /** The page's words, which 456 keeps in its markup: a name as {name|color|bold|link|badge}. */
        final String text;
        final String link = "instagram://comment?media_id=1 and more words than the line";
        final String tracking = "a=1 b=2 c=3 d=4 e=5 f=6 g=7 h=8 i=9 j=10";
        final Deeper deeper = new Deeper();
        Args(String text) {
            this.text = text;
        }
    }

    /** Two levels in, which the line never reads. */
    static final class Deeper {
        final String far = "a much longer bit of text two levels into the row that the line should never pick up at all";
    }

    @Before
    public void clear() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(NotificationKinds.STORE, Context.MODE_PRIVATE).edit().clear().commit();
        NotificationKinds.forgetForTests();
        Settings.HIDDEN_NOTIFICATION_KINDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @After
    public void restore() {
        context.getSharedPreferences(NotificationKinds.STORE, Context.MODE_PRIVATE).edit().clear().commit();
        NotificationKinds.forgetForTests();
        Settings.HIDDEN_NOTIFICATION_KINDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    @Test
    public void theSettingKeepsKindNamesInOrderAndDropsAnythingElse() {
        assertEquals(new TreeSet<>(Arrays.asList("comment_like", "user_followed")),
                NotificationKinds.parse(" user_followed, comment_like,,bad name,{x},user_followed"));
        assertTrue(NotificationKinds.parse(null).isEmpty());
        assertTrue(NotificationKinds.parse("").isEmpty());
        assertEquals("comment_like,user_followed",
                NotificationKinds.join(new TreeSet<>(Arrays.asList("user_followed", "comment_like"))));
        assertEquals("", NotificationKinds.join(Collections.emptySet()));
    }

    /** The line is the longest plain text in the row or one level in: not a link, markup, a list of values or an id. */
    @Test
    public void theLineIsTheRowsWordsOneLevelIn() {
        assertEquals("alice liked your comment: nice shot", NotificationKinds.line(new Row(new Args("alice liked your comment: nice shot"))));
        assertEquals("nothing to read", "", NotificationKinds.line(new Row(new Args(null))));
        assertEquals("", NotificationKinds.line(new Object()));
    }

    /** A name in Instagram's markup reads as the name, the way the page shows it. */
    @Test
    public void markupReadsAsTheNameItShows() {
        assertEquals("ivo.banovic started following you.", NotificationKinds.line(new Row(
                new Args("{ivo.banovic|000000|1|user?id=73889867198|none} started following you."))));
        assertEquals("Suggested thread for you by natgeo: Big cats at night", NotificationKinds.line(new Row(
                new Args("Suggested thread for you by {natgeo|000000|1|barcelona?url_fallback=https%3A%2F%2F"
                        + "www.threads.com%2F%40natgeo&id=1}: Big cats at night"))));
        assertEquals("cnn and espn shared a photo.", NotificationKinds.line(new Row(
                new Args("{cnn|000000|1|user?id=1|verified} and {espn|000000|1|user?id=2|verified} shared a photo."))));
        assertEquals("markup left open is still no line", "",
                NotificationKinds.line(new Row(new Args("{alice|000000|1|user?id=1 liked your comment"))));
    }

    @Test
    public void aLongLineIsCutWithThreeDots() {
        StringBuilder words = new StringBuilder();
        while (words.length() < 300) words.append("bob also commented ");
        String line = NotificationKinds.line(new Row(new Args(words.toString().trim())));
        assertTrue(line, line.length() <= NotificationKinds.LONGEST_LINE);
        assertTrue(line, line.endsWith("..."));
        assertTrue(line, line.startsWith("bob also commented"));
    }

    /** The first row of a kind lends its line, later rows of that kind change nothing, and the file keeps them. */
    @Test
    public void eachKindIsKeptOnceWithItsFirstLine() {
        NotificationKinds.saw("comment_like", new Row(new Args("alice liked your comment: nice shot")));
        NotificationKinds.saw("comment_like", new Row(new Args("bob liked your comment: wow")));
        NotificationKinds.saw("user_followed", new Row(new Args("carol started following you.")));
        NotificationKinds.saw("bad name", new Row(new Args("never kept here")));
        NotificationKinds.saw(null, new Row(new Args("never kept either")));
        NotificationKinds.saw("story_like", null);

        NotificationKinds.forgetForTests();
        List<String[]> offered = NotificationKinds.offered(context, Collections.emptySet());
        assertEquals(2, offered.size());
        assertEquals("comment_like", offered.get(0)[0]);
        assertEquals("alice liked your comment: nice shot\ncomment_like", offered.get(0)[1]);
        assertEquals("user_followed", offered.get(1)[0]);
        assertEquals("carol started following you.\nuser_followed", offered.get(1)[1]);
    }

    /** A kind first seen with no words gets them from a later row, and lists by its name until then. */
    @Test
    public void aKindWithoutALineTakesOneLater() {
        NotificationKinds.saw("story_like", new Object());
        assertEquals("story_like", NotificationKinds.offered(context, Collections.emptySet()).get(0)[1]);
        NotificationKinds.saw("story_like", new Row(new Args("dana liked your story.")));
        assertEquals("dana liked your story.\nstory_like", NotificationKinds.offered(context, Collections.emptySet()).get(0)[1]);
    }

    /** Hidden kinds this phone hasn't seen come last, by name, so they can still be shown again. */
    @Test
    public void hiddenKindsNotSeenHereComeLast() {
        NotificationKinds.saw("user_followed", new Row(new Args("carol started following you.")));
        Set<String> hidden = new TreeSet<>(Arrays.asList("user_followed", "comment_subscribed"));
        List<String[]> offered = NotificationKinds.offered(context, hidden);
        assertEquals(2, offered.size());
        assertEquals("user_followed", offered.get(0)[0]);
        assertEquals("comment_subscribed", offered.get(1)[0]);
        assertEquals("comment_subscribed", offered.get(1)[1]);
    }

    @Test
    public void theListStopsGrowingAtItsLimit() {
        for (int i = 0; i < NotificationKinds.MOST + 5; i++) NotificationKinds.saw("kind_" + i, new Object());
        assertEquals(NotificationKinds.MOST, NotificationKinds.offered(context, Collections.emptySet()).size());
        NotificationKinds.forgetForTests();
        assertEquals("read back to the same limit", NotificationKinds.MOST,
                NotificationKinds.offered(context, Collections.emptySet()).size());
    }

    /** A kind counts as hidden only while it's named, HushGram isn't paused and the settings are ready. */
    @Test
    public void aKindIsHiddenWhileNamedAndNotPaused() {
        assertFalse(NotificationKinds.hidden("comment_like"));
        Settings.HIDDEN_NOTIFICATION_KINDS.save("comment_like,mentioned_comment");
        assertTrue(NotificationKinds.hidden("comment_like"));
        assertTrue(NotificationKinds.hidden("mentioned_comment"));
        assertFalse(NotificationKinds.hidden("user_followed"));
        assertFalse(NotificationKinds.hidden(null));

        Settings.HIDDEN_NOTIFICATION_KINDS.save("user_followed");
        assertTrue("a new list is read", NotificationKinds.hidden("user_followed"));
        assertFalse(NotificationKinds.hidden("comment_like"));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", NotificationKinds.hidden("user_followed"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(NotificationKinds.hidden("user_followed"));
        SettingsContextRule.withoutContext(() -> assertFalse("unready", NotificationKinds.hidden("user_followed")));
    }
}
