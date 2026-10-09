/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What goes out of a batch of viewed stories once some are marked as seen: the marked ones, each
 * once, in a batch of their own, and never one that wasn't marked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryMarksTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Keys as Instagram writes them: media ID, owner ID, reel ID. */
    static final String A = "111_900_900";
    static final String B = "222_900_900";
    static final String C = "333_901_901";
    static final String D = "444_902_902";
    static final String E = "555_903_903";

    /** The account signed in, by Instagram's user ID, and a second one on the same phone. */
    static final String ME = "900";
    static final String OTHER = "901";

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** A batch of Instagram's as the hook sees it: a map of stories, each key to Instagram's own entry. */
    static final class Batch {
        final Map<Object, Object> stories = new LinkedHashMap<>();

        Batch with(String... keys) {
            for (String key : keys) stories.put(key, "entry " + key);
            return this;
        }
    }

    /** Starts batches as Instagram's constructor does, empty unless told to start one with a story in it. */
    static final class Batches implements StorySeen.Batches {
        int started;
        boolean startFull;

        @Override
        public Object empty() {
            started++;
            Batch batch = new Batch();
            return startFull ? batch.with("999_999_999") : batch;
        }

        @Override
        public Map<Object, Object> stories(Object batch) {
            return ((Batch) batch).stories;
        }

        /** A store in these tests is just the user ID of the account it sends for. */
        @Override
        public String account(Object store) {
            return store instanceof String ? (String) store : null;
        }
    }

    /** Diagnostics that throw on every call, as a broken counter or log would. */
    static final StorySeen.Diagnostics BROKEN = new StorySeen.Diagnostics() {
        @Override
        public void saw() {
            throw new IllegalStateException("counter broke");
        }

        @Override
        public void heldBack() {
            throw new IllegalStateException("counter broke");
        }

        @Override
        public void sentMarked() {
            throw new IllegalStateException("log broke");
        }

        @Override
        public void threw(String hook, Throwable failure) {
            throw new IllegalStateException("report broke");
        }
    };

    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final Batches batches = new Batches();
    private StoryMarks marks;

    @Before
    public void prepare() {
        marks = new StoryMarks(now::get);
        HookStatus.clear();
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
    }

    @After
    public void restore() {
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private Object send(Object batch) {
        return send(ME, batch);
    }

    private Object send(String account, Object batch) {
        return StorySeen.toSend(account, batch, batches, ON, ON, marks, StorySeen.COUNTED);
    }

    private StoryMarks.State state(String story) {
        return marks.state(ME, story);
    }

    private StoryMarks.State toggle(String story) {
        return marks.toggle(ME, story);
    }

    private static Batch batch(String... keys) {
        return new Batch().with(keys);
    }

    private static void assertHolds(Object sent, String... keys) {
        assertNotNull("a batch goes out", sent);
        Map<Object, Object> stories = ((Batch) sent).stories;
        assertEquals(new HashSet<>(Arrays.asList(keys)), stories.keySet());
        for (String key : keys) assertEquals("Instagram's own entry", "entry " + key, stories.get(key));
    }

    @Test
    public void unmarkedStoriesNeverReachTheRequest() {
        assertNull("nothing marked: the whole batch stays", send(batch(A, B)));

        toggle("111");
        Batch handed = batch(A, B);
        Object sent = send(handed);
        assertHolds(sent, A);
        assertFalse("a batch of its own, not Instagram's", sent == handed);
        assertFalse(((Batch) sent).stories.containsKey(B));
        assertEquals("the story that wasn't marked isn't counted as sent", StoryMarks.State.UNMARKED, state("222"));
        assertNull("and goes nowhere later", send(batch(B)));
    }

    @Test
    public void aMarkedStoryGoesOnceAndOnlyOnce() {
        assertEquals(StoryMarks.State.MARKED, toggle("111"));
        assertHolds(send(batch(A, B)), A);
        assertEquals(StoryMarks.State.SENT, state("111"));

        assertNull("watched again, it's held back", send(batch(A, B)));
        assertEquals("a tap on a sent story changes nothing", StoryMarks.State.SENT, toggle("111"));
        assertNull(send(batch(A)));
        assertNull("nothing held back is sent with an empty batch either", send(new Batch()));
        assertEquals("one batch was started", 1, batches.started);
    }

    @Test
    public void severalMarksGoTogether() {
        toggle("111");
        toggle("333");
        assertHolds(send(batch(A, B, C)), A, C);
        assertEquals(StoryMarks.State.SENT, state("111"));
        assertEquals(StoryMarks.State.SENT, state("333"));
        assertEquals(StoryMarks.State.UNMARKED, state("222"));
    }

    /** A mark waits for a send that carries its story, for 24 hours after the tap at most. */
    @Test
    public void aMarkForAStoryNotInTheBatchWaitsThenLapses() {
        toggle("444");
        assertNull(send(batch(A)));
        assertEquals("still waiting", StoryMarks.State.MARKED, state("444"));
        assertHolds(send(batch(D)), D);

        toggle("555");
        now.addAndGet(StoryMarks.LIFETIME_MS - 1);
        assertEquals(StoryMarks.State.MARKED, state("555"));
        now.addAndGet(1);
        assertEquals("lapsed a day after the tap", StoryMarks.State.UNMARKED, state("555"));
        assertNull(send(batch(E)));
    }

    /** A story whose batch went before the tap is kept, and goes with the send the tap starts. */
    @Test
    public void aStoryHeldBackBeforeItsMarkGoesWithTheNextSend() {
        assertNull(send(batch(A, B)));
        assertTrue(marks.held(ME, "111"));
        assertFalse(marks.held(ME, "333"));

        toggle("111");
        assertHolds(send(new Batch()), A);
        assertFalse("sent, it isn't kept", marks.held(ME, "111"));
        assertTrue("the other one still is", marks.held(ME, "222"));
        now.addAndGet(StoryMarks.LIFETIME_MS);
        assertFalse("for a day", marks.held(ME, "222"));
    }

    @Test
    public void anUndoneMarkSendsNothing() {
        assertEquals(StoryMarks.State.MARKED, toggle("111"));
        assertEquals(StoryMarks.State.UNMARKED, toggle("111"));
        assertNull(send(batch(A)));
        assertEquals(0, batches.started);
    }

    /** A story is matched by the media ID its key starts with; a key of another shape is never sent. */
    @Test
    public void keysOfAnotherShapeStayHeldBack() {
        toggle("111");
        assertNull(send(batch("111", "111_900", "SUPERLATIVE_111_900", "111_owner_900", "111_900_")));
        assertEquals(StoryMarks.State.MARKED, state("111"));

        assertEquals("111", StoryMarks.storyOf("111_900"));
        assertEquals("111", StoryMarks.storyOf("111"));
        assertNull(StoryMarks.storyOf("live_111"));
        assertNull(StoryMarks.storyOf("111_owner"));
        assertNull(StoryMarks.storyOf(""));
        assertNull(StoryMarks.storyOf(null));
        assertEquals("111", StoryMarks.storyOfKey(A));
        assertNull(StoryMarks.storyOfKey(111));
    }

    /**
     * Anonymous viewing off sends Instagram's batch as it is, and the button off holds every batch
     * back, marks or not. A switch that can't be read sends as Instagram would; a failure picking
     * the marked stories, or a new batch that doesn't start empty, holds everything back.
     */
    @Test
    public void offPausedUnreadyOrThrowingKeepsStockBehavior() {
        toggle("111");
        Batch batch = batch(A, B);
        assertSame("views not anonymous: Instagram's batch goes as it is", batch, StorySeen.toSend(ME, batch, batches, OFF, ON, marks, StorySeen.COUNTED));
        assertNull("the button off: the whole batch is held back", StorySeen.toSend(ME, batch(A, B), batches, ON, OFF, marks, StorySeen.COUNTED));
        assertEquals("the mark isn't spent", StoryMarks.State.MARKED, state("111"));

        assertSame("the switch unreadable: Instagram's batch goes", batch, StorySeen.toSend(ME, batch, batches, THROWS, ON, marks, StorySeen.COUNTED));
        assertReported("story seen send");
        assertNull("the button's switch unreadable: held back", StorySeen.toSend(ME, batch(A), batches, ON, THROWS, marks, StorySeen.COUNTED));
        assertReported("marked story send");

        batches.startFull = true;
        assertNull("a new batch with something in it: held back", send(batch(A)));
        assertEquals(StoryMarks.State.MARKED, state("111"));
        batches.startFull = false;

        StorySeen.Batches broken = new StorySeen.Batches() {
            @Override
            public Object empty() {
                return new Batch();
            }

            @Override
            public Map<Object, Object> stories(Object batch) {
                throw new ClassCastException("not a batch");
            }

            @Override
            public String account(Object store) {
                return ME;
            }
        };
        HookStatus.clear();
        assertNull(StorySeen.toSend(ME, batch(A), broken, ON, ON, marks, StorySeen.COUNTED));
        assertReported("marked story send");
        assertEquals(StoryMarks.State.MARKED, state("111"));

        assertHolds(send(batch(A)), A);
    }

    /**
     * The counters and the log never decide anything. Throwing at every call, they leave views
     * held back while anonymous: the whole batch held, or only the marked stories sent. Never the
     * batch Instagram handed over, unless views aren't anonymous, and the hook still doesn't throw.
     */
    @Test
    public void throwingDiagnosticsNeverLetTheWholeBatchOut() {
        Batch handed = batch(A, B);
        assertNull("nothing marked: held back", StorySeen.toSend(ME, handed, batches, ON, ON, marks, BROKEN));
        assertNull("the button off: held back", StorySeen.toSend(ME, handed, batches, ON, OFF, marks, BROKEN));

        toggle("111");
        Object sent = StorySeen.toSend(ME, handed, batches, ON, ON, marks, BROKEN);
        assertHolds(sent, A);
        assertFalse(sent == handed);

        assertNull("the button's switch unreadable: held back", StorySeen.toSend(ME, batch(A, B), batches, ON, THROWS, marks, BROKEN));
        assertSame("views not anonymous: Instagram's batch as it is", handed, StorySeen.toSend(ME, handed, batches, OFF, ON, marks, BROKEN));
        assertSame("the switch unreadable: Instagram's batch as it is", handed, StorySeen.toSend(ME, handed, batches, THROWS, ON, marks, BROKEN));

        Object retried = StorySeen.toRetry(ME, batch(A, B), batches, ON, OFF, marks, BROKEN);
        assertNull("a retry held back is canceled even when diagnostics fail", retried);
    }

    /** A mark made on one account never sends in another's batch, and what one account held back stays its own. */
    @Test
    public void aMarkBelongsToTheAccountItWasMadeOn() {
        assertEquals(StoryMarks.State.MARKED, toggle("111"));
        assertEquals("the other account has no mark", StoryMarks.State.UNMARKED, marks.state(OTHER, "111"));
        assertNull("the other account's batch with the same story: held back", send(OTHER, batch(A, B)));
        assertEquals(StoryMarks.State.MARKED, state("111"));
        assertEquals(StoryMarks.State.UNMARKED, marks.state(OTHER, "111"));
        assertTrue("held for the other account", marks.held(OTHER, "111"));
        assertFalse(marks.held(ME, "111"));

        assertHolds(send(batch(A)), A);
        assertEquals(StoryMarks.State.SENT, state("111"));
        assertEquals("sent for this account only", StoryMarks.State.UNMARKED, marks.state(OTHER, "111"));

        assertNull(send(OTHER, batch(C)));
        toggle("333");
        assertNull("a story the other account held back isn't sent for this one", send(new Batch()));
        assertTrue(marks.held(OTHER, "333"));
        assertEquals(StoryMarks.State.MARKED, state("333"));
    }

    /** Without the account's user ID nothing is marked, kept or sent. */
    @Test
    public void withNoAccountNothingIsMarkedKeptOrSent() {
        assertEquals(StoryMarks.State.UNMARKED, marks.toggle(null, "111"));
        assertEquals(StoryMarks.State.UNMARKED, marks.state(null, "111"));
        toggle("111");
        assertNull("a store whose account can't be read sends nothing", send(null, batch(A, B)));
        assertFalse(marks.held(null, "111"));
        assertFalse("nothing was kept for anyone", marks.held(ME, "222"));
        assertEquals("the mark waits for its own account", StoryMarks.State.MARKED, state("111"));
        assertEquals(0, batches.started);
    }

    /** A new batch that doesn't start empty sends nothing, and the marked stories wait for a later send. */
    @Test
    public void aBatchThatCantStartEmptyKeepsTheMarkedStoriesForLater() {
        toggle("111");
        batches.startFull = true;
        assertNull(send(batch(A, B)));
        assertTrue("kept to send later", marks.held(ME, "111"));
        assertEquals(StoryMarks.State.MARKED, state("111"));
        batches.startFull = false;
        assertHolds(send(new Batch()), A);
        assertEquals(StoryMarks.State.SENT, state("111"));
        assertFalse(marks.held(ME, "111"));
    }

    /**
     * A batch the store retries goes through the same choice: held back, null skips the request
     * before claiming its pending entry. With anonymity off Instagram's original goes through. No factory
     * failure may send the original while anonymity is active.
     */
    @Test
    public void aRetriedBatchHeldBackIsCanceled() {
        Batch handed = batch(A, B);
        Object retried = StorySeen.toRetry(ME, handed, batches, ON, OFF, marks, StorySeen.COUNTED);
        assertNull(retried);
        assertEquals("cancellation doesn't need a new batch", 0, batches.started);
        assertSame("views not anonymous", handed, StorySeen.toRetry(ME, handed, batches, OFF, ON, marks, StorySeen.COUNTED));

        toggle("111");
        assertHolds(StorySeen.toRetry(ME, batch(A, B), batches, ON, ON, marks, StorySeen.COUNTED), A);

        batches.startFull = true;
        assertNull("no empty batch is needed to cancel", StorySeen.toRetry(ME, handed, batches, ON, OFF, marks, StorySeen.COUNTED));
        batches.startFull = false;
        StorySeen.Batches throwing = new StorySeen.Batches() {
            @Override
            public Object empty() {
                throw new IllegalStateException("no batch");
            }

            @Override
            public Map<Object, Object> stories(Object batch) {
                return batches.stories(batch);
            }

            @Override
            public String account(Object store) {
                return batches.account(store);
            }
        };
        HookStatus.clear();
        assertNull(StorySeen.toRetry(ME, handed, throwing, ON, OFF, marks, StorySeen.COUNTED));
    }

    /** The real switches: the button needs both on, and both read off while paused or before settings are read. */
    @Test
    public void theSwitchesFollowTheSettingsAndThePause() {
        assertFalse("the button starts off", Settings.MARK_STORIES_SEEN.get());
        assertTrue(StorySeen.anonymous());
        assertFalse(StorySeenButton.switchedOn());
        Settings.MARK_STORIES_SEEN.save(true);
        assertTrue(StorySeenButton.switchedOn());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertFalse("the button needs anonymous viewing on", StorySeenButton.switchedOn());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(StorySeen.anonymous());
        assertFalse(StorySeenButton.switchedOn());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertFalse(StorySeen.anonymous());
            assertFalse(StorySeenButton.switchedOn());
        });
        assertTrue(StorySeenButton.switchedOn());
    }

    private static void assertReported(String hook) {
        String missing = HookStatus.missing(FamilyNames.STORY_SEEN).toString();
        assertTrue(missing, missing.contains("'" + hook + "'"));
    }
}
