/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
 * Mark a story seen when you like or reply (#876): a like or reply on a story whose view is held
 * back lets that one story's view go, through Instagram's own send and {@link StorySeen#toSend}, and
 * the other stories stay held back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryAnswerSeenTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Instagram's id for someone else's story, account 905's, and the key its view has in a batch. */
    private static final String STORY = "111_905";
    private static final String STORY_KEY = "111_905_905";
    private static final String OTHER_KEY = "222_905_905";
    /** A story of the account signed in, as on your own story or highlight (#850). */
    private static final String MINE = "333_" + StoryMarksTest.ME;
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;
    private static final BooleanSupplier THROWS = () -> {
        throw new IllegalStateException("settings went away");
    };

    private final AtomicLong now = new AtomicLong(5_000_000L);
    private final StoryMarksTest.Batches batches = new StoryMarksTest.Batches();
    /** The account signed in. In these tests a session, a like store and a story are the text they stand for. */
    private final Object session = StoryMarksTest.ME;
    private StoryMarks marks;

    /** What the open story viewer's batch holds: the stories watched since it last handed one over. */
    private StoryMarksTest.Batch viewerBatch;
    private final List<Object> sent = new ArrayList<>();
    private int heldSends;
    private int viewerSends;

    private static final StoryAnswerSeen.Reader READER = new StoryAnswerSeen.Reader() {
        @Override
        public Object likeSession(Object store) {
            return store;
        }

        @Override
        public String mediaId(Object media) {
            return media instanceof String ? (String) media : null;
        }

        @Override
        public String account(Object session) {
            return session instanceof String ? (String) session : null;
        }
    };

    /**
     * Lets a view go as the patched app does, through Instagram's send asking {@link StorySeen#toSend}:
     * an empty batch for the stories an earlier batch held back, or the open viewer's batch, copied
     * and cleared as the viewer's flush hands it over.
     */
    private final StoryAnswerSeen.Release release = new StoryAnswerSeen.Release() {
        @Override
        public void sendHeld(Object account) {
            heldSends++;
            sent.add(send(new StoryMarksTest.Batch()));
        }

        @Override
        public void sendViewers() {
            viewerSends++;
            StoryMarksTest.Batch copy = new StoryMarksTest.Batch();
            copy.stories.putAll(viewerBatch.stories);
            viewerBatch.stories.clear();
            sent.add(send(copy));
        }
    };

    @Before
    public void prepare() {
        marks = new StoryMarks(now::get);
        viewerBatch = new StoryMarksTest.Batch().with(STORY_KEY, OTHER_KEY);
        StoryAnswerSeen.resetForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
    }

    @After
    public void restore() {
        StoryAnswerSeen.resetForTests();
        Settings.VIEW_STORIES_ANONYMOUSLY.resetToDefault();
        Settings.MARK_STORIES_SEEN.resetToDefault();
        Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Instagram's send for the account signed in, with both switches on, through the real hook. */
    private Object send(Object batch) {
        return StorySeen.toSend(session, batch, batches, ON, ON, marks, StorySeen.COUNTED);
    }

    private boolean like(String id) {
        return StoryAnswerSeen.likedFrom(session, id, ON, READER, marks, release);
    }

    private boolean reply(String id) {
        return StoryAnswerSeen.answeredFrom(session, id, ON, READER, marks, release);
    }

    private static void assertHolds(Object batch, String... keys) {
        assertNotNull("a batch goes out", batch);
        Map<Object, Object> stories = ((StoryMarksTest.Batch) batch).stories;
        assertEquals(new HashSet<>(Arrays.asList(keys)), stories.keySet());
    }

    private void assertNothingLetGo() {
        assertEquals("nothing sent", 0, heldSends + viewerSends);
        assertEquals("nothing marked", StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "111"));
    }

    /** A like on a story still in the open viewer's batch hands that batch over now, and only the liked story goes. */
    @Test
    public void aLikedStoryInTheOpenViewerGoesAlone() {
        assertTrue(like(STORY));

        assertEquals(1, viewerSends);
        assertEquals(0, heldSends);
        assertEquals(1, sent.size());
        assertHolds(sent.get(0), STORY_KEY);
        assertEquals(StoryMarks.State.SENT, marks.state(StoryMarksTest.ME, "111"));
        assertTrue("the other story is held back", marks.held(StoryMarksTest.ME, "222"));
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "222"));
        assertTrue(HookStatus.missing(FamilyNames.STORY_SEEN).toString(), HookStatus.missing(FamilyNames.STORY_SEEN).isEmpty());
    }

    /** A reply on a story an earlier batch held back sends it with an empty batch the hook fills, and nothing else. */
    @Test
    public void aRepliedStoryHeldBackEarlierGoesThroughTheHeldSend() {
        assertNull("leaving the viewer held both back", send(viewerBatch));
        assertTrue(marks.held(StoryMarksTest.ME, "111"));

        assertTrue(reply(STORY));

        assertEquals(1, heldSends);
        assertEquals(0, viewerSends);
        assertHolds(sent.get(0), STORY_KEY);
        assertFalse("sent, it isn't kept", marks.held(StoryMarksTest.ME, "111"));
        assertTrue("the other one still is", marks.held(StoryMarksTest.ME, "222"));
    }

    /** A second like, an unlike and like again, or a reply after a like, sends nothing more. */
    @Test
    public void aStoryAlreadySentIsLeftAlone() {
        assertTrue(like(STORY));
        assertFalse(like(STORY));
        assertFalse(reply(STORY));
        assertEquals(1, viewerSends + heldSends);
    }

    /** A mark made by the Mark as seen button and not sent yet still goes on a like, once. */
    @Test
    public void aStoryMarkedByTheButtonGoesOnALike() {
        assertEquals(StoryMarks.State.MARKED, marks.toggle(StoryMarksTest.ME, "111"));
        assertTrue(like(STORY));
        assertHolds(sent.get(0), STORY_KEY);
        assertEquals("a like never unmarks", StoryMarks.State.SENT, marks.state(StoryMarksTest.ME, "111"));
    }

    /** Your own story or highlight sends no view (#850), so a like or reply on it changes nothing. */
    @Test
    public void yourOwnStoryIsLeftAlone() {
        assertFalse(like(MINE));
        assertFalse(reply(MINE));
        assertEquals(0, heldSends + viewerSends);
        assertEquals(StoryMarks.State.UNMARKED, marks.state(StoryMarksTest.ME, "333"));
    }

    /** With the switch off, its read throwing, or nothing to read, a like or reply goes on as it would without the patch. */
    @Test
    public void offUnreadableOrThrowingChangesNothing() {
        assertFalse(StoryAnswerSeen.likedFrom(session, STORY, OFF, READER, marks, release));
        assertFalse(StoryAnswerSeen.answeredFrom(session, STORY, OFF, READER, marks, release));
        assertNothingLetGo();
        assertTrue(HookStatus.missing(FamilyNames.STORY_SEEN).toString(), HookStatus.missing(FamilyNames.STORY_SEEN).isEmpty());

        assertFalse(StoryAnswerSeen.likedFrom(null, STORY, ON, READER, marks, release));
        assertFalse(StoryAnswerSeen.likedFrom(session, null, ON, READER, marks, release));
        assertFalse(StoryAnswerSeen.likedFrom(session, "not a story", ON, READER, marks, release));
        assertFalse(StoryAnswerSeen.answeredFrom(null, STORY, ON, READER, marks, release));
        assertFalse(StoryAnswerSeen.answeredFrom(session, null, ON, READER, marks, release));
        assertFalse(StoryAnswerSeen.answeredFrom(session, new Object(), ON, READER, marks, release));
        assertFalse("no account", StoryAnswerSeen.answeredFrom(new Object(), STORY, ON, READER, marks, release));
        assertNothingLetGo();

        assertFalse(StoryAnswerSeen.likedFrom(session, STORY, THROWS, READER, marks, release));
        assertFalse(StoryAnswerSeen.answeredFrom(session, STORY, THROWS, READER, marks, release));
        StoryAnswerSeen.Reader broken = new StoryAnswerSeen.Reader() {
            @Override
            public Object likeSession(Object store) {
                throw new IllegalStateException("store went away");
            }

            @Override
            public String mediaId(Object media) {
                throw new IllegalStateException("media went away");
            }

            @Override
            public String account(Object session) {
                throw new IllegalStateException("session went away");
            }
        };
        assertFalse(StoryAnswerSeen.likedFrom(session, STORY, ON, broken, marks, release));
        assertFalse(StoryAnswerSeen.answeredFrom(session, STORY, ON, broken, marks, release));
        assertNothingLetGo();
        String missing = HookStatus.missing(FamilyNames.STORY_SEEN).toString();
        assertTrue(missing, missing.contains("'" + StoryAnswerSeen.HOOK + "'"));
    }

    /** A send that throws is reported and never reaches Instagram's like or reply. */
    @Test
    public void aFailedSendIsReportedNotThrown() {
        StoryAnswerSeen.Release failing = new StoryAnswerSeen.Release() {
            @Override
            public void sendHeld(Object account) {
                throw new IllegalStateException("send broke");
            }

            @Override
            public void sendViewers() {
                throw new IllegalStateException("send broke");
            }
        };
        assertFalse(StoryAnswerSeen.likedFrom(session, STORY, ON, READER, marks, failing));
        String missing = HookStatus.missing(FamilyNames.STORY_SEEN).toString();
        assertTrue(missing, missing.contains("'" + StoryAnswerSeen.HOOK + "'"));
    }

    /**
     * Each viewer Instagram builds is remembered and handed to the flush, on the main thread: right
     * away from the main thread, and from another one only once the main thread runs. One viewer's
     * failure never stops the next.
     */
    @Test
    public void theViewersAreFlushedOnTheMainThread() throws InterruptedException {
        Object first = new Object();
        Object second = new Object();
        StoryAnswerSeen.viewerOpened(first);
        StoryAnswerSeen.viewerOpened(second);
        StoryAnswerSeen.viewerOpened(null);
        assertEquals(2, StoryAnswerSeen.viewersForTests());
        List<Object> flushed = new ArrayList<>();
        StoryAnswerSeen.Release real = StoryAnswerSeen.releasing(viewer -> {
            flushed.add(viewer);
            if (viewer == first) throw new IllegalStateException("viewer went away");
        });

        real.sendViewers();
        assertEquals("from the main thread, at once", new HashSet<>(Arrays.asList(first, second)), new HashSet<>(flushed));
        String missing = HookStatus.missing(FamilyNames.STORY_SEEN).toString();
        assertTrue(missing, missing.contains("'" + StoryAnswerSeen.HOOK + "'"));

        flushed.clear();
        Thread other = new Thread(real::sendViewers);
        other.start();
        other.join(5_000);
        assertTrue("not on another thread", flushed.isEmpty());
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("once the main thread runs", 2, flushed.size());
    }

    /** The real switch needs views held back and its own switch on, and reads off while paused or before settings are read. */
    @Test
    public void theSwitchFollowsTheSettingsAndThePause() {
        assertFalse("starts off", Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.get());
        assertFalse(StoryAnswerSeen.switchedOn());
        Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.save(true);
        assertTrue(StoryAnswerSeen.switchedOn());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(false);
        assertFalse("needs anonymous viewing on", StoryAnswerSeen.switchedOn());
        Settings.VIEW_STORIES_ANONYMOUSLY.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(StoryAnswerSeen.switchedOn());
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertFalse(StoryAnswerSeen.switchedOn()));
        assertTrue(StoryAnswerSeen.switchedOn());
    }

    /** The patched entry points, unpatched stubs and all, read the real switch and never throw. */
    @Test
    public void theHooksNeverThrow() {
        StoryAnswerSeen.liked(null, null);
        StoryAnswerSeen.answered(null, null);
        Settings.MARK_STORY_SEEN_ON_LIKE_OR_REPLY.save(true);
        StoryAnswerSeen.liked(new Object(), STORY);
        StoryAnswerSeen.answered(session, new Object());
        StoryAnswerSeen.flush(new Object());
        assertNull(StoryAnswerSeen.likeSession(new Object()));
        assertNull(StoryAnswerSeen.storyMediaId(new Object()));
    }
}
