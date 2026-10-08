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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * A story watched while its view is held back keeps its ring new: Instagram's write of what you've
 * seen is skipped, and made once the story's Mark as seen goes out (#92).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StorySeenRingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** The account signed in, by Instagram's user ID, and a second one on the same phone. */
    static final String ME = "900";
    static final String OTHER = "901";

    /** Stories in the viewer, by Instagram's id for each: media ID, then owner ID. */
    static final String WATCHED = "111_900";
    static final String NEXT = "222_900";

    /** The keys a batch of viewed stories holds them under: media, owner, reel. */
    static final String WATCHED_KEY = "111_900_900";
    static final String NEXT_KEY = "222_900_900";

    /** The time of the story watched, as the viewer reads it off the story. */
    static final long AT = 1_700_000_000L;

    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    /** A session in these tests is the account's user ID, and a story is its id. */
    static final StorySeenRings.Reader READER = new StorySeenRings.Reader() {
        @Override
        public String account(Object session) {
            return session instanceof String ? (String) session : null;
        }

        @Override
        public String storyId(Object item) {
            return item instanceof String ? (String) item : null;
        }
    };

    static final StorySeenRings.Reader BROKEN_READER = new StorySeenRings.Reader() {
        @Override
        public String account(Object session) {
            throw new IllegalStateException("session went away");
        }

        @Override
        public String storyId(Object item) {
            throw new IllegalStateException("story went away");
        }
    };

    /** Instagram's writes, each as the reel, the account and the time. */
    static final class Writes implements StorySeenRings.Writer {
        final List<String> made = new ArrayList<>();

        @Override
        public void write(Object reel, Object session, long at) {
            made.add(reel + " " + session + " " + at);
        }
    }

    /** The main thread: what's posted runs when the test says. */
    static final class Posts implements StorySeenRings.Poster {
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public void post(Runnable task) {
            queued.add(task);
        }

        void run() {
            List<Runnable> due = new ArrayList<>(queued);
            queued.clear();
            for (Runnable task : due) task.run();
        }
    }

    static final class Counts implements StorySeenRings.Counts {
        int kept;
        int shown;
        final List<Throwable> threw = new ArrayList<>();

        @Override
        public void keptNew() {
            kept++;
        }

        @Override
        public void shownSeen() {
            shown++;
        }

        @Override
        public void threw(Throwable failure) {
            threw.add(failure);
        }
    }

    /** Counts that throw on every call, as a broken counter or log would. */
    static final StorySeenRings.Counts BROKEN = new StorySeenRings.Counts() {
        @Override
        public void keptNew() {
            throw new IllegalStateException("counter broke");
        }

        @Override
        public void shownSeen() {
            throw new IllegalStateException("counter broke");
        }

        @Override
        public void threw(Throwable failure) {
            throw new IllegalStateException("report broke");
        }
    };

    /** The hooks StorySeen reports failures under. */
    static final class Reports implements StorySeen.Diagnostics {
        final List<String> threw = new ArrayList<>();

        @Override
        public void saw() {
        }

        @Override
        public void heldBack() {
        }

        @Override
        public void sentMarked() {
        }

        @Override
        public void threw(String hook, Throwable failure) {
            threw.add(hook);
        }
    }

    /** The reel the story is in. Held only weakly by what's kept, so the test keeps it. */
    private final Object reel = "reel";

    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final StoryMarksTest.Batches batches = new StoryMarksTest.Batches();
    private final Writes writes = new Writes();
    private final Posts posts = new Posts();
    private final Counts counts = new Counts();
    private StoryMarks marks;
    private StorySeenRings rings;

    @Before
    public void prepare() {
        marks = new StoryMarks(now::get);
        rings = new StorySeenRings(now::get);
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** The viewer showing [story] to [account], views held back. True when Instagram's write is skipped. */
    private boolean watch(String account, String story) {
        return rings.keep(reel, account, AT, story, ON, READER, marks, counts);
    }

    /** StorySeen sending [batch] for [account], both switches on, handing what goes out to the rings. */
    private Object send(String account, StoryMarksTest.Batch batch) {
        return StorySeen.toSend(account, batch, batches, ON, ON, marks, StorySeen.COUNTED,
            (sentFor, marked, used) -> rings.shown(sentFor, marked, used, writes, posts, counts));
    }

    private static StoryMarksTest.Batch batch(String... keys) {
        return new StoryMarksTest.Batch().with(keys);
    }

    @Test
    public void whileViewsAreHeldBackTheWriteIsSkippedAndKept() {
        assertTrue(watch(ME, WATCHED));
        assertEquals(1, rings.size());
        assertEquals(1, counts.kept);
        assertTrue("nothing written", writes.made.isEmpty());
        assertTrue(counts.threw.isEmpty());
    }

    @Test
    public void withViewsGoingOutInstagramWrites() {
        assertFalse(rings.keep(reel, ME, AT, WATCHED, OFF, READER, marks, counts));
        assertEquals("nothing kept", 0, rings.size());
        assertEquals("nothing counted", 0, counts.kept);
    }

    /** The real switch: on it skips, and off, paused or before the settings are read Instagram writes. */
    @Test
    public void theSwitchPauseAndSettingsDecide() {
        BooleanSupplier anonymous = StorySeen::anonymous;
        assertTrue("on", rings.keep(reel, ME, AT, WATCHED, anonymous, READER, marks, counts));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertFalse("off", rings.keep(reel, ME, AT, WATCHED, anonymous, READER, marks, counts));
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse("paused", rings.keep(reel, ME, AT, WATCHED, anonymous, READER, marks, counts));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
            assertFalse("settings not ready", rings.keep(reel, ME, AT, WATCHED, anonymous, READER, marks, counts)));
        assertTrue("the control: on again", rings.keep(reel, ME, AT, WATCHED, anonymous, READER, marks, counts));
    }

    /** A grey ring is what Instagram draws anyway, so a failure deciding lets its write through. */
    @Test
    public void aFailureDecidingLetsInstagramWrite() {
        assertFalse("the switch", rings.keep(reel, ME, AT, WATCHED, THROWS, READER, marks, counts));
        assertFalse("the account or story", rings.keep(reel, ME, AT, WATCHED, ON, BROKEN_READER, marks, counts));
        assertEquals(2, counts.threw.size());
        assertEquals(0, rings.size());
        assertFalse("nor do broken counts change it", rings.keep(reel, ME, AT, WATCHED, THROWS, READER, marks, BROKEN));
        assertTrue("a broken counter doesn't stop the skip", rings.keep(reel, ME, AT, WATCHED, ON, READER, marks, BROKEN));
    }

    /** The view is held back whatever's read, so the ring stays new, but nothing can be kept for a mark. */
    @Test
    public void aStoryOrAccountThatCantBeReadStaysNewWithoutBeingKept() {
        assertTrue("a live video's id", watch(ME, "live_17900000000"));
        assertTrue("no story", watch(ME, null));
        assertTrue("no account", watch(null, WATCHED));
        assertTrue("no reel", rings.keep(null, ME, AT, WATCHED, ON, READER, marks, counts));
        assertEquals(0, rings.size());
        assertTrue(counts.threw.isEmpty());
    }

    @Test
    public void aStoryWhoseMarkWentOutIsWrittenAsInstagramWould() {
        marks.toggle(ME, "111");
        assertNotNull(marks.choose(ME, batch(WATCHED_KEY), batches));
        assertEquals(StoryMarks.State.SENT, marks.state(ME, "111"));

        assertFalse(watch(ME, WATCHED));
        assertEquals(0, rings.size());
        assertTrue("another account's mark isn't this one's", watch(OTHER, WATCHED));
    }

    /** A story marked but not sent yet stays new until its mark goes out, then it's written down once. */
    @Test
    public void aMarkedStoryIsWrittenWhenItsMarkGoesOut() {
        assertEquals(StoryMarks.State.MARKED, marks.toggle(ME, "111"));
        assertTrue(watch(ME, WATCHED));

        Object sent = send(ME, batch(WATCHED_KEY));
        assertNotNull("the marked story goes out", sent);
        assertTrue("not on the send's thread", writes.made.isEmpty());
        assertEquals(1, posts.queued.size());

        posts.run();
        assertEquals(Collections.singletonList("reel 900 " + AT), writes.made);
        assertEquals(1, counts.shown);
        assertEquals(0, rings.size());

        assertNull("watched again, nothing goes", send(ME, batch(WATCHED_KEY)));
        posts.run();
        assertEquals("written once", 1, writes.made.size());
        assertFalse("and the ring is Instagram's from now on", watch(ME, WATCHED));
    }

    /** Only the marked stories of the account the batch is for are written; the rest stay new. */
    @Test
    public void onlyTheAccountsMarkedStoriesAreWritten() {
        marks.toggle(ME, "111");
        watch(ME, WATCHED);
        watch(ME, NEXT);
        watch(OTHER, WATCHED);

        send(ME, batch(WATCHED_KEY, NEXT_KEY));
        posts.run();

        assertEquals(Collections.singletonList("reel 900 " + AT), writes.made);
        assertEquals("the story not marked and the other account's stay kept", 2, rings.size());
    }

    /** The Mark as seen button sends what was held back with an empty batch of its own: its stories are written too. */
    @Test
    public void aStoryHeldBackThenMarkedIsWrittenWhenTheButtonSendsIt() {
        watch(ME, WATCHED);
        assertNull("held back", send(ME, batch(WATCHED_KEY)));
        marks.toggle(ME, "111");

        assertNotNull(send(ME, new StoryMarksTest.Batch()));
        posts.run();
        assertEquals(Collections.singletonList("reel 900 " + AT), writes.made);
    }

    @Test
    public void keptWritesLastADay() {
        watch(ME, WATCHED);
        now.addAndGet(StorySeenRings.LIFETIME_MS - 1);
        assertEquals(1, rings.size());
        now.addAndGet(1);
        assertEquals(0, rings.size());

        marks.toggle(ME, "111");
        assertNotNull(send(ME, batch(WATCHED_KEY)));
        posts.run();
        assertTrue("an expired write isn't made", writes.made.isEmpty());
    }

    @Test
    public void onlyTheMostRecentWritesAreKept() {
        for (int i = 0; i <= StorySeenRings.MAX_KEPT; i++) watch(ME, (1000 + i) + "_900");
        assertEquals(StorySeenRings.MAX_KEPT, rings.size());

        marks.toggle(ME, "1000");
        marks.toggle(ME, String.valueOf(1000 + StorySeenRings.MAX_KEPT));
        send(ME, batch("1000_900_900", (1000 + StorySeenRings.MAX_KEPT) + "_900_900"));
        posts.run();
        assertEquals("the oldest went", Collections.singletonList("reel 900 " + AT), writes.made);
    }

    /** Watching a story again keeps the newest write for it, once. */
    @Test
    public void aStoryWatchedTwiceIsKeptOnce() {
        watch(ME, WATCHED);
        rings.keep(reel, ME, AT + 1, WATCHED, ON, READER, marks, counts);
        assertEquals(1, rings.size());

        marks.toggle(ME, "111");
        send(ME, batch(WATCHED_KEY));
        posts.run();
        assertEquals(Collections.singletonList("reel 900 " + (AT + 1)), writes.made);
    }

    /** Nothing a write, a counter or an odd batch does reaches what goes out or throws into the send. */
    @Test
    public void aBrokenWriteOrCounterIsReportedAndContained() {
        StorySeenRings.Writer throwing = (reel, session, at) -> {
            throw new IllegalStateException("Instagram's write broke");
        };
        watch(ME, WATCHED);
        rings.shown(ME, batch(WATCHED_KEY), batches, throwing, Runnable::run, counts);
        assertEquals(1, counts.threw.size());

        watch(ME, WATCHED);
        rings.shown(ME, batch(WATCHED_KEY), batches, writes, Runnable::run, BROKEN);
        assertEquals("written though the counter broke", 1, writes.made.size());

        rings.shown(null, batch(WATCHED_KEY), batches, writes, Runnable::run, counts);
        rings.shown(ME, null, batches, writes, Runnable::run, counts);
        rings.shown(ME, new Object(), batches, writes, Runnable::run, counts);
        assertEquals("a batch that isn't one is reported", 2, counts.threw.size());
        assertEquals(1, writes.made.size());
    }

    /** StorySeen hands on the batch that goes out and the account it's for, and only when one goes out. */
    @Test
    public void storySeenHandsOnWhatGoesOut() {
        List<Object> handed = new ArrayList<>();
        StorySeen.Shown shown = (account, marked, used) -> {
            handed.add(account);
            handed.add(marked);
        };
        Reports reports = new Reports();

        assertNull(StorySeen.toSend(ME, batch(WATCHED_KEY), batches, ON, ON, marks, reports, shown));
        assertNull("marking off", StorySeen.toSend(ME, batch(WATCHED_KEY), batches, ON, OFF, marks, reports, shown));
        StoryMarksTest.Batch original = batch(WATCHED_KEY);
        assertSame("views going out", original, StorySeen.toSend(ME, original, batches, OFF, ON, marks, reports, shown));
        assertTrue("nothing marked went out", handed.isEmpty());

        marks.toggle(ME, "111");
        Object sent = StorySeen.toSend(ME, batch(WATCHED_KEY), batches, ON, ON, marks, reports, shown);
        assertNotNull(sent);
        assertEquals(ME, handed.get(0));
        assertSame(sent, handed.get(1));

        marks.toggle(ME, "222");
        Object retried = StorySeen.toRetry(ME, batch(NEXT_KEY), batches, ON, ON, marks, reports, shown);
        assertNotNull("a retry's marked story goes out too", retried);
        assertSame(retried, handed.get(3));
        assertTrue(reports.threw.isEmpty());
    }

    @Test
    public void aBrokenRingsChangesNothingThatGoesOut() {
        Reports reports = new Reports();
        StorySeen.Shown broken = (account, marked, used) -> {
            throw new IllegalStateException("rings broke");
        };
        marks.toggle(ME, "111");

        Object sent = StorySeen.toSend(ME, batch(WATCHED_KEY), batches, ON, ON, marks, reports, broken);
        assertNotNull("the marked story still goes out", sent);
        assertTrue(((StoryMarksTest.Batch) sent).stories.containsKey(WATCHED_KEY));
        assertEquals(Collections.singletonList(StorySeen.SHOWN_HOOK), reports.threw);
    }

    /** The diagnostics the hook keeps never throw. */
    @Test
    public void theCountsNeverThrow() {
        StorySeenRings.COUNTED.keptNew();
        StorySeenRings.COUNTED.shownSeen();
        StorySeenRings.COUNTED.threw(new IllegalStateException("test"));
        StorySeen.NOT_SHOWN.sent(ME, new Object(), batches);
    }
}
