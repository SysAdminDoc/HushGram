/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Whether a post's hidden like count is let through, and what the count reader reports. Runtime
 * decisions only: whether Instagram's server sends the count with such a post needs a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HiddenLikeCountsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final Object POST = new Object();
    private static final Object OTHER_POST = new Object();
    private static final Object SECOND_POST = new Object();
    private static final Object ORPHAN_POST = new Object();
    /** The poster of each post's data, as the patch's reads would answer. */
    private static final Function<Object, String> POSTER_OF = tree ->
            tree == POST ? "alice" : tree == OTHER_POST ? "bob" : tree == SECOND_POST ? "alice" : null;
    private static final Function<Object, Boolean> HID_ITS_LIKES = tree -> Boolean.TRUE;
    private static final Function<Object, Boolean> SHOWS_ITS_LIKES = tree -> Boolean.FALSE;
    private static final Function<Object, Boolean> NOT_READ = tree -> null;
    private static final Function<Object, Object> TWELVE_LIKES = tree -> 12;
    private static final Function<Object, Object> NO_LIKES_SENT = tree -> null;
    private static final Function<Object, Object> NOTHING_ABOVE_ZERO = tree -> 0;
    private static final Function<Object, Object> COUNTS_BY_POST = tree -> tree == POST ? 12 : null;
    private static final Function<Object, Object> COUNTS_BY_POST_OTHER = tree -> tree == OTHER_POST ? 7 : null;
    private static final BooleanSupplier ON = HiddenLikeCounts::switchedOn;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);
        HiddenLikeCounts.reset();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SHOW_HIDDEN_LIKE_COUNTS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HiddenLikeCounts.reset();
        HookStatus.clear();
    }

    private void sawCount(Object tree, Object count) {
        HiddenLikeCounts.sawCount(tree, count, ON, HID_ITS_LIKES, POSTER_OF);
    }

    private void rowRead(Object tree, Object flag, Function<Object, Object> likeCount) {
        HiddenLikeCounts.rowRead(tree, flag, ON, likeCount, POSTER_OF);
    }

    /** The decider asked about a hidden post of this poster, and what it answers (true: hidden). */
    private boolean asked(String poster) {
        return HiddenLikeCounts.hidden(poster, true, ON);
    }

    @Test
    public void theKeysAreTheFieldNamesHashes() {
        assertEquals("like_and_view_counts_disabled".hashCode(), HiddenLikeCounts.LIKES_HIDDEN_KEY);
        assertEquals("like_count".hashCode(), HiddenLikeCounts.LIKE_COUNT_KEY);
        assertEquals("user".hashCode(), HiddenLikeCounts.USER_KEY);
        assertEquals("id".hashCode(), HiddenLikeCounts.ID_KEY);
    }

    @Test
    public void aPostThatCameWithACountHasItsHiddenCountShown() {
        sawCount(POST, 12);
        rowRead(POST, true, TWELVE_LIKES);
        assertFalse(asked("alice"));
        assertFalse("the next ask gets its own answer", asked("alice"));
        assertTrue(HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).toString(),
                HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).isEmpty());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT + " 1"));
        assertTrue(report, report.contains(HiddenLikeCounts.SHOWN + " 2"));
        assertFalse(report, report.contains(HiddenLikeCounts.NO_COUNT));
        assertFalse(report, report.contains(HiddenLikeCounts.LEFT_HIDDEN));
    }

    /**
     * Two hidden posts, one the server sent a count for and one it didn't, reach the decider with
     * nothing but their poster's id, as the Reels item config's call does. Each gets its own answer.
     */
    @Test
    public void twoHiddenPostsGetTheirOwnAnswersFromTheDeciderAlone() {
        sawCount(POST, 12);
        sawCount(OTHER_POST, null);
        assertFalse("the post with a count shows it", asked("alice"));
        assertTrue("the post without one stays as Instagram drew it", asked("bob"));
        assertTrue("a poster nothing was noted for stays hidden", asked("carol"));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HiddenLikeCounts.SHOWN + " 1"));
        assertTrue(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT + " 1"));
        assertTrue(report, report.contains(HiddenLikeCounts.NO_COUNT + " 1"));
    }

    /** The Reels item config never goes through a like row; what an earlier read noted is enough. */
    @Test
    public void aReelAskedWithOnlyItsPosterIsAnsweredFromTheNotes() {
        sawCount(POST, 12);
        assertFalse("no row ran before this ask", HiddenLikeCounts.hidden("alice", 1));
        assertTrue(HiddenLikeCounts.hidden("bob", 1));
        assertFalse("a post that shows its likes is left as it is", HiddenLikeCounts.hidden("alice", 0));
        assertTrue("a hidden reel whose poster was never seen stays hidden", HiddenLikeCounts.hidden("carol", 1));
        assertTrue("and one with no poster at all", HiddenLikeCounts.hidden(null, 1));
    }

    @Test
    public void rowsNoteHiddenPostsByTheirOwnCount() {
        rowRead(POST, true, COUNTS_BY_POST);
        rowRead(OTHER_POST, true, COUNTS_BY_POST);
        rowRead(OTHER_POST, true, NOTHING_ABOVE_ZERO);
        assertFalse(asked("alice"));
        assertTrue(asked("bob"));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HiddenLikeCounts.LEFT_HIDDEN + " 2"));
    }

    /**
     * One poster with a counted hidden post and an uncounted one: the uncounted mark is permanent, so
     * neither post is let through, whichever came first and whatever comes later.
     */
    @Test
    public void aPosterWithBothKindsOfHiddenPostsIsNeverLetThrough() {
        sawCount(POST, 12);
        assertFalse("only the counted post is known", asked("alice"));
        sawCount(SECOND_POST, null);
        assertTrue("the post without a count stays hidden", asked("alice"));
        sawCount(POST, 12);
        rowRead(POST, true, TWELVE_LIKES);
        assertTrue("a later counted post doesn't undo it", asked("alice"));

        HiddenLikeCounts.reset();
        sawCount(SECOND_POST, null);
        sawCount(POST, 12);
        assertTrue("the order doesn't matter", asked("alice"));
        assertTrue(asked("alice"));
    }

    /**
     * A row that decides before the count is read notes its own post first, so the poster is held
     * before the decider is asked; a decider asked before any note leaves the post as Instagram drew it.
     */
    @Test
    public void aDeciderAskedBeforeTheNoteIsStockAndAfterItFollowsIt() {
        sawCount(POST, 12);
        rowRead(SECOND_POST, true, NO_LIKES_SENT);
        assertTrue("the row noted its no-count post before it asked", asked("alice"));

        assertTrue("nothing is known about this poster yet", asked("bob"));
        rowRead(OTHER_POST, true, COUNTS_BY_POST_OTHER);
        assertFalse("and once its post is noted with a count it shows", asked("bob"));
    }

    @Test
    public void aPosterWhoseCountsNeverCameIsNeverLetThrough() {
        sawCount(OTHER_POST, null);
        rowRead(OTHER_POST, true, NO_LIKES_SENT);
        assertTrue(asked("bob"));
    }

    /** A post that shows its likes, or one the row found no flag for, is never noted as hidden. */
    @Test
    public void rowsOnlyNoteTheirHiddenPosts() {
        rowRead(POST, false, TWELVE_LIKES);
        rowRead(POST, null, TWELVE_LIKES);
        rowRead(null, true, TWELVE_LIKES);
        assertTrue(asked("alice"));
        assertEquals(0, HiddenLikeCounts.remembered());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.LEFT_HIDDEN));
    }

    /** Data with no poster, or no id, can't be told from another's, so nothing is noted for it. */
    @Test
    public void aPostWithoutAPosterIsNeverNoted() {
        sawCount(ORPHAN_POST, 12);
        rowRead(ORPHAN_POST, true, TWELVE_LIKES);
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES, tree -> "");
        assertEquals(0, HiddenLikeCounts.remembered());
        assertTrue(asked("alice"));
        assertTrue(asked(""));
        assertTrue(HiddenLikeCounts.hidden(null, true, ON));
    }

    /** With no count sent, or none above zero, there's no number to show, so the count stays hidden. */
    @Test
    public void noCountLeavesItHidden() {
        sawCount(POST, null);
        sawCount(POST, 0);
        sawCount(POST, "12");
        rowRead(POST, true, NO_LIKES_SENT);
        assertTrue(asked("alice"));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(HiddenLikeCounts.NO_COUNT + " 3"));
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
        assertFalse(report, report.contains(HiddenLikeCounts.SHOWN));
    }

    /** A count that comes with a post showing its likes says nothing about hidden ones. */
    @Test
    public void postsThatDidntHideTheirLikesProveNothing() {
        HiddenLikeCounts.sawCount(POST, 40, ON, SHOWS_ITS_LIKES, POSTER_OF);
        HiddenLikeCounts.sawCount(POST, 40, ON, NOT_READ, POSTER_OF);
        HiddenLikeCounts.sawCount(null, 40, ON, HID_ITS_LIKES, POSTER_OF);
        assertTrue(asked("alice"));
        assertEquals(0, HiddenLikeCounts.remembered());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
        assertFalse(report, report.contains(HiddenLikeCounts.NO_COUNT));
    }

    @Test
    public void aShownCountStaysShown() {
        assertFalse(HiddenLikeCounts.hidden("alice", 0));
        sawCount(POST, 12);
        assertFalse(HiddenLikeCounts.hidden("alice", 0));
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.SHOWN));
    }

    /** Unpatched, the stubs read nothing, so nothing is noted and nothing changes. */
    @Test
    public void unpatchedTheReadersSeeNothing() {
        HiddenLikeCounts.sawCount(POST, 12);
        HiddenLikeCounts.rowRead(POST, true);
        assertTrue(HiddenLikeCounts.hidden("alice", 1));
        assertEquals(0, HiddenLikeCounts.remembered());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
    }

    @Test
    public void theNotesAreABoundedTable() {
        int total = HiddenLikeCounts.MAX_POSTERS + 40;
        for (int i = 0; i < total; i++) {
            String poster = "poster" + i;
            HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES, tree -> poster);
        }
        assertEquals(HiddenLikeCounts.MAX_POSTERS, HiddenLikeCounts.remembered());
        assertTrue("the oldest was forgotten", asked("poster0"));
        assertTrue(asked("poster39"));
        assertFalse("the newest is still there", asked("poster" + (total - 1)));
        assertFalse(asked("poster40"));
        assertEquals(HiddenLikeCounts.MAX_POSTERS, HiddenLikeCounts.remembered());
    }

    @Test
    public void aPosterAskedAboutRecentlyOutlivesOlderOnes() {
        for (int i = 0; i < HiddenLikeCounts.MAX_POSTERS; i++) {
            String poster = "poster" + i;
            HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES, tree -> poster);
        }
        assertFalse(asked("poster0"));
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES, tree -> "late");
        assertFalse("asked about lately, so kept", asked("poster0"));
        assertTrue("the next oldest went", asked("poster1"));
    }

    /**
     * Notes from several threads at once lose nothing: each thread's own posters show right after it
     * noted them, and a poster that one thread noted with a count and another without ends hidden
     * whichever got there first.
     */
    @Test
    public void notesFromSeveralThreadsLoseNothing() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        int threads = 4;
        int each = 200;
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < each; i++) {
                        String poster = "p" + id + "-" + i;
                        HiddenLikeCounts.sawCount(POST, 12, () -> true, HID_ITS_LIKES, tree -> poster);
                        if (HiddenLikeCounts.hidden(poster, true, () -> true)) throw new AssertionError(poster + " was lost");
                        HiddenLikeCounts.sawCount(POST, id == 0 ? null : 12, () -> true, HID_ITS_LIKES, tree -> "shared");
                    }
                } catch (Throwable e) {
                    failed.set(e);
                }
            });
            workers.add(worker);
            worker.start();
        }
        go.countDown();
        for (Thread worker : workers) worker.join();
        assertNull(String.valueOf(failed.get()), failed.get());
        assertEquals(threads * each + 1, HiddenLikeCounts.remembered());
        assertTrue("one uncounted post holds the shared poster", asked("shared"));
        for (int t = 0; t < threads; t++) assertFalse(asked("p" + t + "-" + (each - 1)));
    }

    @Test
    public void offToStartOffPausedAndUnreadyKeepItHidden() {
        sawCount(POST, 12);
        Settings.SHOW_HIDDEN_LIKE_COUNTS.resetToDefault();
        assertFalse(Settings.SHOW_HIDDEN_LIKE_COUNTS.defaultValue);
        assertTrue(HiddenLikeCounts.hidden("alice", 1));
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(false);
        assertTrue(HiddenLikeCounts.hidden("alice", 1));
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);
        assertFalse("the note is still there once it's on again", HiddenLikeCounts.hidden("alice", 1));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(HiddenLikeCounts.hidden("alice", 1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertTrue(HiddenLikeCounts.hidden("alice", 1)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertTrue(HiddenLikeCounts.hidden("alice", 1)));
        assertFalse(HiddenLikeCounts.hidden("alice", 1));
    }

    /** With the switch off the readers don't look, so a count seen then isn't noted. */
    @Test
    public void offTheReadersDontLook() {
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(false);
        HiddenLikeCounts.sawCount(POST, 12, ON, tree -> {
            throw new AssertionError("read the flag with the switch off");
        }, POSTER_OF);
        HiddenLikeCounts.rowRead(POST, true, ON, tree -> {
            throw new AssertionError("read the count with the switch off");
        }, POSTER_OF);
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);
        assertTrue(HiddenLikeCounts.hidden("alice", 1));
        assertEquals(0, HiddenLikeCounts.remembered());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
    }

    @Test
    public void throwingKeepsItHiddenAndIsReported() {
        sawCount(POST, 12);
        assertTrue(HiddenLikeCounts.hidden("alice", true, THROWS));
        HiddenLikeCounts.rowRead(POST, true, THROWS, TWELVE_LIKES, POSTER_OF);
        HiddenLikeCounts.rowRead(POST, true, ON, tree -> {
            throw new IllegalStateException("count went away");
        }, POSTER_OF);
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES, tree -> {
            throw new IllegalStateException("poster went away");
        });
        HiddenLikeCounts.sawCount(POST, 12, THROWS, HID_ITS_LIKES, POSTER_OF);
        HiddenLikeCounts.sawCount(POST, 12, ON, tree -> {
            throw new IllegalStateException("tree went away");
        }, POSTER_OF);

        String missing = HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).toString();
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.DECISION + "'"));
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.COUNT_READ + "'"));
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.ROW_READ + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
