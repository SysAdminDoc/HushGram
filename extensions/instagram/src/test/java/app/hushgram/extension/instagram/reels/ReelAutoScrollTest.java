/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** What the auto scroll hooks answer and remember, and that Instagram's answer stands when they can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelAutoScrollTest {
    /** A memory that counts its writes. */
    static final class CountingMemory implements ReelAutoScroll.Memory {
        boolean on;
        int writes;

        CountingMemory(boolean on) {
            this.on = on;
        }

        @Override
        public boolean on() {
            return on;
        }

        @Override
        public void remember(boolean on) {
            this.on = on;
            writes++;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.KEEP_REEL_AUTO_SCROLL.save(true);
        Settings.REEL_AUTO_ADVANCE.resetToDefault();
        Settings.REEL_AUTO_SCROLL_ON.resetToDefault();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.KEEP_REEL_AUTO_SCROLL.resetToDefault();
        Settings.REEL_AUTO_ADVANCE.resetToDefault();
        Settings.REEL_AUTO_SCROLL_ON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, Instagram saying auto scroll is on is remembered, and a later "off", such as after a restart, is answered on. */
    @Test
    public void anOnAnswerIsRememberedAndOutlivesInstagramsOff() {
        assertFalse(ReelAutoScroll.answer(0));
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());

        assertTrue(ReelAutoScroll.answer(1));
        assertTrue(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertTrue("any non-zero is yes", ReelAutoScroll.answer(0x7f));

        assertTrue("Instagram forgot, HushGram didn't", ReelAutoScroll.answer(0));
        assertTrue(ReelAutoScroll.answer(0));
    }

    /** Turning auto scroll off with Instagram's switch is remembered, so Instagram's "off" stands from then on. */
    @Test
    public void turningItOffIsRemembered() {
        assertTrue(ReelAutoScroll.answer(1));

        ReelAutoScroll.chosen(1);
        assertTrue("turning it on changes nothing until Instagram says it's on", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        ReelAutoScroll.chosen(0);
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertFalse(ReelAutoScroll.answer(0));

        assertTrue("on again once Instagram says so", ReelAutoScroll.answer(1));
        assertTrue(ReelAutoScroll.answer(0));
    }

    /** A choice to turn it on isn't remembered by itself: Instagram may still be asking how long for. */
    @Test
    public void turningItOnWaitsForInstagram() {
        ReelAutoScroll.chosen(1);
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertFalse(ReelAutoScroll.answer(0));
    }

    /** A completed duration save is remembered before the timer can expire or another reel checks it. */
    @Test
    public void aFutureTimerIsRememberedBeforeAnyOnAnswer() {
        ReelAutoScroll.timerSet(Long.MAX_VALUE);
        assertTrue("a saved future timestamp remembers on at once", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertTrue("the timer may expire before any check", ReelAutoScroll.answer(0));

        ReelAutoScroll.chosen(0);
        for (long expiry : new long[]{0, -1, System.currentTimeMillis() - 1}) {
            ReelAutoScroll.timerSet(expiry);
            assertFalse("an expired timestamp never remembers on", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        }
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelAutoScroll.timerSet(Long.MAX_VALUE);
        assertFalse("a future timer is not learned while paused", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> ReelAutoScroll.timerSet(Long.MAX_VALUE));
        assertFalse("a future timer is not learned before settings are ready", Settings.REEL_AUTO_SCROLL_ON.savedValue());
    }

    /** Expired, equal, reset and failed timestamps never change the remembered choice. */
    @Test
    public void onlyAStrictlyFutureTimestampIsLearnedAndFailuresDoNotEscape() {
        CountingMemory memory = new CountingMemory(false);
        for (long expiry : new long[]{Long.MIN_VALUE, -1, 0, 999, 1000}) {
            ReelAutoScroll.timerSet(expiry, () -> 1000, () -> true, memory);
            assertFalse(memory.on);
        }
        assertEquals(0, memory.writes);
        ReelAutoScroll.timerSet(1001, () -> 1000, () -> true, memory);
        assertTrue(memory.on);
        ReelAutoScroll.timerSet(Long.MAX_VALUE, () -> 1000, () -> true, memory);
        ReelAutoScroll.timerSet(0, () -> 1000, () -> true, memory);
        assertEquals("a reset does not erase the chosen on", 1, memory.writes);
        memory.on = false;
        ReelAutoScroll.timerSet(1001, () -> { throw new AssertionError("a paused hook read the clock"); }, () -> false, memory);
        ReelAutoScroll.timerSet(1001, () -> { throw new OutOfMemoryError("clock failed"); }, () -> true, memory);
        String missing = HookStatus.missing(FamilyNames.REEL_AUTO_SCROLL).toString();
        assertTrue(missing, missing.contains("'auto scroll timer'"));
        assertTrue(missing, missing.contains(OutOfMemoryError.class.getName()));
        HookStatus.clear();
        ReelAutoScroll.timerSet(1001, () -> 1000, () -> { throw new IllegalStateException("settings failed"); }, memory);
        assertFalse(memory.on);
        assertEquals(1, memory.writes);
        missing = HookStatus.missing(FamilyNames.REEL_AUTO_SCROLL).toString();
        assertTrue(missing, missing.contains("'auto scroll timer'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /** The disabled switch learns the completed choice without forcing stock scrolling on. */
    @Test
    public void aTimerChosenWhileTheSwitchIsOffDoesNotOverrideStock() {
        Settings.KEEP_REEL_AUTO_SCROLL.save(false);
        ReelAutoScroll.timerSet(Long.MAX_VALUE);
        assertTrue(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertFalse("stock off stands while the switch is off", ReelAutoScroll.answer(0));
        Settings.KEEP_REEL_AUTO_SCROLL.save(true);
        assertTrue("the completed choice is available once the switch is enabled", ReelAutoScroll.answer(0));
    }

    /**
     * The switch's choice, once Instagram keeps it in memory or saves it, is remembered at once,
     * on and off, before anything asks the check again: the Playback sheet's switch turned on is
     * still on after a restart even if no reel ended in between.
     */
    @Test
    public void aChoiceInstagramKeepsIsRememberedAtOnce() {
        ReelAutoScroll.chosen(1);
        ReelAutoScroll.stored(1);
        assertTrue(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertTrue("Instagram forgot, HushGram didn't", ReelAutoScroll.answer(0));

        ReelAutoScroll.chosen(0);
        ReelAutoScroll.stored(0);
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertFalse(ReelAutoScroll.answer(0));

        ReelAutoScroll.stored(0x7f);
        assertTrue("any non-zero is on", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        ReelAutoScroll.stored(0);
        assertFalse("off kept without the switch's own call", Settings.REEL_AUTO_SCROLL_ON.savedValue());
    }

    /**
     * The Reels tab's long press saved on, auto scroll was turned off with the plugin's switch
     * where Instagram goes by memory or a timer, and nothing cleared the saved preference. Reading
     * it, as the long press does, answers Instagram's own on but remembers nothing, so the off stands.
     */
    @Test
    public void aStaleSavedOnDoesNotTurnItBackOn() {
        ReelAutoScroll.chosen(1);
        assertTrue("the plugin turns it on", ReelAutoScroll.answer(1));
        assertTrue(Settings.REEL_AUTO_SCROLL_ON.savedValue());

        ReelAutoScroll.chosen(0);
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertTrue("the getter keeps Instagram's saved on", ReelAutoScroll.saved(1));
        assertFalse("a saved on isn't remembered", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertFalse("so the plugin's off stands", ReelAutoScroll.answer(0));
        assertTrue(ReelAutoScroll.saved(1));
        assertFalse(ReelAutoScroll.answer(0));
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());
    }

    /**
     * The getter answers on when the saved preference is on, and while the switch is on, when
     * auto scroll was last left on. It never writes. Paused or unready, the preference stands.
     */
    @Test
    public void theSavedPreferenceIsAnsweredButNeverRemembered() {
        CountingMemory memory = new CountingMemory(false);
        assertFalse(ReelAutoScroll.saved(0, () -> true, memory));
        assertTrue(ReelAutoScroll.saved(1, () -> true, memory));
        assertTrue("any non-zero is on", ReelAutoScroll.saved(0x7f, () -> true, memory));
        assertFalse(memory.on);
        memory.on = true;
        assertTrue("left on answers for a cleared preference", ReelAutoScroll.saved(0, () -> true, memory));
        assertFalse("switch off, the preference stands", ReelAutoScroll.saved(0, () -> false, memory));
        assertTrue(ReelAutoScroll.saved(1, () -> false, memory));
        assertEquals(0, memory.writes);

        Settings.REEL_AUTO_SCROLL_ON.save(true);
        assertTrue(ReelAutoScroll.saved(0));
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelAutoScroll.saved(0));
        assertTrue(ReelAutoScroll.saved(1));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> {
            assertFalse(ReelAutoScroll.saved(0));
            assertTrue(ReelAutoScroll.saved(1));
        });
        assertTrue(Settings.REEL_AUTO_SCROLL_ON.savedValue());
    }

    /** The hot path writes only when the choice changes. */
    @Test
    public void nothingIsWrittenWhenTheChoiceIsUnchanged() {
        CountingMemory memory = new CountingMemory(false);

        for (int i = 0; i < 5; i++) assertFalse(ReelAutoScroll.answer(0, () -> true, () -> true, () -> false, memory));
        assertEquals(0, memory.writes);
        for (int i = 0; i < 5; i++) assertTrue(ReelAutoScroll.answer(1, () -> true, () -> true, () -> false, memory));
        assertEquals(1, memory.writes);
        for (int i = 0; i < 5; i++) assertTrue(ReelAutoScroll.answer(0, () -> true, () -> true, () -> false, memory));
        assertEquals(1, memory.writes);

        ReelAutoScroll.chosen(1, () -> true, memory);
        assertEquals(1, memory.writes);
        ReelAutoScroll.chosen(0, () -> true, memory);
        ReelAutoScroll.chosen(0, () -> true, memory);
        assertEquals(2, memory.writes);
        assertFalse(memory.on);

        ReelAutoScroll.stored(0, () -> true, memory);
        assertEquals(2, memory.writes);
        ReelAutoScroll.stored(1, () -> true, memory);
        ReelAutoScroll.stored(1, () -> true, memory);
        assertEquals(3, memory.writes);
        assertTrue(memory.on);
    }

    /** Concurrent first answers each report the unavailable and ready summary at most once. */
    @Test(timeout = 45000)
    public void concurrentFirstAnswersDoNotDuplicateTheSummary() throws Exception {
        Field field = ReelAutoScroll.class.getDeclaredField("reported");
        field.setAccessible(true);
        Object counter = field.get(null);
        if (counter instanceof AtomicInteger) ((AtomicInteger) counter).set(0);
        else field.setInt(null, 0);
        ShadowLog.reset();

        answerTogether(false);
        assertEquals("one summary before learning is allowed", 1, summaryCount());
        answerTogether(true);
        assertEquals("one additional summary once learning is allowed", 2, summaryCount());
        for (int i = 0; i < 5; i++) ReelAutoScroll.answer(0, () -> false, () -> false, () -> false, new CountingMemory(false));
        assertEquals("the completed summary never reopens", 2, summaryCount());
    }

    private static long summaryCount() {
        return ShadowLog.getLogs().stream().filter(item -> item.msg.startsWith("Reel auto scroll: answer since start,")).count();
    }

    private static void answerTogether(boolean learning) throws Exception {
        int threads = 8;
        CountDownLatch reached = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> answers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                AtomicBoolean first = new AtomicBoolean(true);
                answers.add(pool.submit(() -> ReelAutoScroll.answer(0, () -> {
                    if (first.compareAndSet(true, false)) {
                        reached.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("summary gate timed out");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                    }
                    return learning;
                }, () -> true, () -> false, new CountingMemory(false))));
            }
            assertTrue("every answer reaches the summary before any can report", reached.await(10, TimeUnit.SECONDS));
            release.countDown();
            for (Future<Boolean> answer : answers) assertFalse("stock off is unchanged", answer.get(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Off, paused or before the settings are read, Instagram's answer stands. While the switch is
     * off the memory still follows Instagram, so turning the switch on goes by the latest choice;
     * paused or unready, nothing is remembered.
     */
    @Test
    public void offPausedAndUnreadyKeepInstagramsAnswer() {
        Settings.REEL_AUTO_SCROLL_ON.save(true);

        Settings.KEEP_REEL_AUTO_SCROLL.save(false);
        assertFalse(ReelAutoScroll.answer(0));
        assertTrue(ReelAutoScroll.answer(1));
        ReelAutoScroll.chosen(0);
        assertFalse("turned off while the switch was off", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertTrue(ReelAutoScroll.answer(1));
        assertTrue("seen on while the switch was off", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        Settings.KEEP_REEL_AUTO_SCROLL.save(true);
        assertTrue(ReelAutoScroll.answer(0));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelAutoScroll.answer(0));
        assertTrue(ReelAutoScroll.answer(1));
        ReelAutoScroll.chosen(0);
        ReelAutoScroll.stored(0);
        assertTrue("nothing is remembered while paused", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(ReelAutoScroll.answer(0));

        SettingsContextRule.withoutContext(() -> {
            assertFalse(ReelAutoScroll.answer(0));
            assertTrue(ReelAutoScroll.answer(1));
            ReelAutoScroll.chosen(0);
            ReelAutoScroll.stored(0);
        });
        assertTrue("nothing is remembered before the settings are read", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        Settings.REEL_AUTO_SCROLL_ON.save(false);
        CountingMemory memory = new CountingMemory(false);
        ReelAutoScroll.stored(1, () -> false, memory);
        assertEquals("a kept on isn't remembered while paused", 0, memory.writes);
    }

    /** A switch or a memory that throws leaves Instagram's answer, and says which hook threw. */
    @Test
    public void aThrowingSwitchOrMemoryKeepsInstagramsAnswerAndIsReported() {
        ReelAutoScroll.Memory broken = new ReelAutoScroll.Memory() {
            @Override
            public boolean on() {
                throw new IllegalStateException("preferences went away");
            }

            @Override
            public void remember(boolean on) {
                throw new IllegalStateException("preferences went away");
            }
        };

        assertFalse(ReelAutoScroll.answer(0, () -> true, () -> true, () -> false, broken));
        assertTrue(ReelAutoScroll.answer(1, () -> true, () -> true, () -> false, broken));
        assertFalse(ReelAutoScroll.answer(0, () -> true, () -> {
            throw new IllegalStateException("settings went away");
        }, () -> false, new CountingMemory(true)));
        assertFalse("a move on switch that throws moves nothing on", ReelAutoScroll.answer(0, () -> true, () -> false, () -> {
            throw new IllegalStateException("settings went away");
        }, new CountingMemory(false)));
        assertTrue(ReelAutoScroll.answer(1, () -> {
            throw new IllegalStateException("settings went away");
        }, () -> true, () -> false, new CountingMemory(false)));
        ReelAutoScroll.chosen(0, () -> true, broken);
        ReelAutoScroll.stored(1, () -> true, broken);
        ReelAutoScroll.timerSet(2000, () -> 1000, () -> true, broken);
        ReelAutoScroll.stored(0, () -> {
            throw new IllegalStateException("settings went away");
        }, new CountingMemory(true));
        assertFalse(ReelAutoScroll.saved(0, () -> true, broken));
        assertTrue(ReelAutoScroll.saved(1, () -> true, broken));
        assertFalse(ReelAutoScroll.saved(0, () -> {
            throw new IllegalStateException("settings went away");
        }, new CountingMemory(true)));
        assertFalse("the viewer builds no scroller on a switch that throws", ReelAutoScroll.install(0, () -> {
            throw new IllegalStateException("settings went away");
        }));
        assertTrue("and still builds the one Instagram offers", ReelAutoScroll.install(1, () -> {
            throw new IllegalStateException("settings went away");
        }));

        String missing = HookStatus.missing(FamilyNames.REEL_AUTO_SCROLL).toString();
        assertTrue(missing, missing.contains("'auto scroll answer'"));
        assertTrue(missing, missing.contains("'auto scroll choice'"));
        assertTrue(missing, missing.contains("'auto scroll saved'"));
        assertTrue(missing, missing.contains("'auto scroll stored'"));
        assertTrue(missing, missing.contains("'auto scroll timer'"));
        assertTrue(missing, missing.contains("'auto scroll install'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    /**
     * Move on when a reel ends: the viewer builds Instagram's scroller where Instagram doesn't offer
     * auto scroll, and every check of whether auto scroll is on answers yes, so the scroller's own
     * rules decide each reel that ends. Off, nothing changes.
     */
    @Test
    public void moveOnBuildsTheScrollerAndAnswersOn() {
        Settings.KEEP_REEL_AUTO_SCROLL.save(false);
        assertFalse("off to start", Settings.REEL_AUTO_ADVANCE.get());
        assertFalse("off, Instagram's no stands", ReelAutoScroll.install(0));
        assertTrue("off, Instagram's yes stands", ReelAutoScroll.install(1));
        assertFalse(ReelAutoScroll.answer(0));
        assertFalse(ReelAutoScroll.saved(0));

        Settings.REEL_AUTO_ADVANCE.save(true);
        assertTrue("the viewer builds the scroller", ReelAutoScroll.install(0));
        assertTrue(ReelAutoScroll.install(1));
        assertTrue("any non-zero is yes", ReelAutoScroll.install(0x7f));
        assertTrue("the scroller is told auto scroll is on", ReelAutoScroll.answer(0));
        assertFalse("the Reels tab's long press shows the remembered choice", ReelAutoScroll.saved(0));
        assertFalse("with Keep auto scroll on off and nothing remembered",
                Settings.KEEP_REEL_AUTO_SCROLL.get() || Settings.REEL_AUTO_SCROLL_ON.savedValue());

        Settings.REEL_AUTO_ADVANCE.save(false);
        assertFalse(ReelAutoScroll.install(0));
        assertFalse(ReelAutoScroll.answer(0));
        assertFalse(ReelAutoScroll.saved(0));
    }

    /**
     * The remembered choice follows Instagram's own switches alone. Move on when a reel ends
     * answers on without writing it, so a choice kept before it was turned on comes back as it was,
     * and one that was never made isn't made by it.
     */
    @Test
    public void moveOnNeverWritesTheRememberedChoice() {
        CountingMemory memory = new CountingMemory(false);
        for (int i = 0; i < 5; i++) assertTrue(ReelAutoScroll.answer(0, () -> true, () -> true, () -> true, memory));
        assertFalse("the saved getter answers what's remembered, not the switch", ReelAutoScroll.saved(0, () -> true, memory));
        assertTrue(ReelAutoScroll.install(0, () -> true));
        assertEquals("answering on for the switch is no choice of Instagram's", 0, memory.writes);
        assertFalse(memory.on);
        assertFalse("turned off, the choice that was never made stays unmade",
                ReelAutoScroll.answer(0, () -> true, () -> true, () -> false, memory));

        Settings.REEL_AUTO_ADVANCE.save(true);
        assertTrue(ReelAutoScroll.answer(0));
        assertFalse("nothing is remembered for the switch", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        ReelAutoScroll.chosen(1);
        ReelAutoScroll.stored(1);
        assertTrue("Instagram's own switch is still remembered", Settings.REEL_AUTO_SCROLL_ON.savedValue());
        ReelAutoScroll.chosen(0);
        ReelAutoScroll.stored(0);
        assertFalse(Settings.REEL_AUTO_SCROLL_ON.savedValue());
        assertTrue("the switch keeps answering on", ReelAutoScroll.answer(0));
        Settings.REEL_AUTO_ADVANCE.save(false);
        assertFalse("and its off leaves what Instagram's switch last said", ReelAutoScroll.answer(0));
    }

    /** Paused or before the settings are read, the viewer and every check get Instagram's own answer. */
    @Test
    public void moveOnStandsDownWhilePausedOrUnread() {
        Settings.KEEP_REEL_AUTO_SCROLL.save(false);
        Settings.REEL_AUTO_ADVANCE.save(true);
        assertTrue(ReelAutoScroll.install(0));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelAutoScroll.install(0));
        assertTrue(ReelAutoScroll.install(1));
        assertFalse(ReelAutoScroll.answer(0));
        assertFalse(ReelAutoScroll.saved(0));
        assertTrue("a pause keeps the saved switch", Settings.REEL_AUTO_ADVANCE.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(ReelAutoScroll.install(0));

        SettingsContextRule.withoutContext(() -> {
            assertFalse(ReelAutoScroll.install(0));
            assertTrue(ReelAutoScroll.install(1));
            assertFalse(ReelAutoScroll.answer(0));
            assertFalse(ReelAutoScroll.saved(0));
        });
        assertFalse("a paused scroller's check moves nothing on",
                ReelAutoScroll.answer(0, () -> false, () -> true, () -> true, new CountingMemory(true)));
    }
}
