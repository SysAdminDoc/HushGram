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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

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
    private static final BooleanSupplier ON = HiddenLikeCounts::switchedOn;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final LongSupplier now = clock::get;

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
        HiddenLikeCounts.sawCount(tree, count, ON, HID_ITS_LIKES, POSTER_OF, now);
    }

    private void rowRead(Object tree, Object flag, Function<Object, Object> likeCount) {
        HiddenLikeCounts.rowRead(tree, flag, ON, likeCount, POSTER_OF, now);
    }

    /** The decider asked about a hidden post of this poster, and what it answers (true: hidden). */
    private boolean asked(String poster) {
        return HiddenLikeCounts.hidden(poster, true, ON, now);
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

    /** One of a poster's hidden posts coming without a count holds the rest of theirs hidden for a while. */
    @Test
    public void aPostersHiddenPostWithoutACountHoldsTheirOthersHiddenForAWhile() {
        sawCount(POST, 12);
        assertFalse(asked("alice"));
        sawCount(SECOND_POST, null);
        assertTrue("the post without a count stays hidden", asked("alice"));
        clock.addAndGet(HiddenLikeCounts.HOLD_MILLIS - 1);
        assertTrue("still within the hold", asked("alice"));
        clock.addAndGet(1);
        assertFalse("the hold ran out and the poster's counted post shows again", asked("alice"));
        sawCount(SECOND_POST, null);
        assertTrue("another post without a count holds it again", asked("alice"));
    }

    @Test
    public void aPosterWhoseCountsNeverCameIsNeverLetThrough() {
        sawCount(OTHER_POST, null);
        rowRead(OTHER_POST, true, NO_LIKES_SENT);
        assertTrue(asked("bob"));
        clock.addAndGet(HiddenLikeCounts.HOLD_MILLIS * 3);
        assertTrue("an expired hold alone shows nothing", asked("bob"));
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
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES, tree -> "", now);
        assertEquals(0, HiddenLikeCounts.remembered());
        assertTrue(asked("alice"));
        assertTrue(asked(""));
        assertTrue(HiddenLikeCounts.hidden(null, true, ON, now));
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
        HiddenLikeCounts.sawCount(POST, 40, ON, SHOWS_ITS_LIKES, POSTER_OF, now);
        HiddenLikeCounts.sawCount(POST, 40, ON, NOT_READ, POSTER_OF, now);
        HiddenLikeCounts.sawCount(null, 40, ON, HID_ITS_LIKES, POSTER_OF, now);
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
            HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES, tree -> poster, now);
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
            HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES, tree -> poster, now);
        }
        assertFalse(asked("poster0"));
        HiddenLikeCounts.sawCount(POST, 12, ON, HID_ITS_LIKES, tree -> "late", now);
        assertFalse("asked about lately, so kept", asked("poster0"));
        assertTrue("the next oldest went", asked("poster1"));
    }

    @Test
    public void notesFromSeveralThreadsAreSafe() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int id = t;
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < 500; i++) {
                        String poster = "p" + id + "-" + i;
                        HiddenLikeCounts.sawCount(POST, 12, () -> true, HID_ITS_LIKES, tree -> poster, now);
                        HiddenLikeCounts.hidden(poster, true, () -> true, now);
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
        assertNull(failed.get());
        assertEquals(HiddenLikeCounts.MAX_POSTERS, HiddenLikeCounts.remembered());
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
        }, POSTER_OF, now);
        HiddenLikeCounts.rowRead(POST, true, ON, tree -> {
            throw new AssertionError("read the count with the switch off");
        }, POSTER_OF, now);
        Settings.SHOW_HIDDEN_LIKE_COUNTS.save(true);
        assertTrue(HiddenLikeCounts.hidden("alice", 1));
        assertEquals(0, HiddenLikeCounts.remembered());
        String report = HookStatus.report().toString();
        assertFalse(report, report.contains(HiddenLikeCounts.CAME_WITH_COUNT));
    }

    @Test
    public void throwingKeepsItHiddenAndIsReported() {
        sawCount(POST, 12);
        assertTrue(HiddenLikeCounts.hidden("alice", true, THROWS, now));
        assertTrue(HiddenLikeCounts.hidden("alice", true, ON, () -> {
            throw new IllegalStateException("clock went away");
        }));
        HiddenLikeCounts.rowRead(POST, true, THROWS, TWELVE_LIKES, POSTER_OF, now);
        HiddenLikeCounts.rowRead(POST, true, ON, tree -> {
            throw new IllegalStateException("count went away");
        }, POSTER_OF, now);
        HiddenLikeCounts.rowRead(POST, true, ON, TWELVE_LIKES, tree -> {
            throw new IllegalStateException("poster went away");
        }, now);
        HiddenLikeCounts.sawCount(POST, 12, THROWS, HID_ITS_LIKES, POSTER_OF, now);
        HiddenLikeCounts.sawCount(POST, 12, ON, tree -> {
            throw new IllegalStateException("tree went away");
        }, POSTER_OF, now);

        String missing = HookStatus.missing(FamilyNames.HIDDEN_LIKE_COUNTS).toString();
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.DECISION + "'"));
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.COUNT_READ + "'"));
        assertTrue(missing, missing.contains("'" + HiddenLikeCounts.ROW_READ + "'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }
}
