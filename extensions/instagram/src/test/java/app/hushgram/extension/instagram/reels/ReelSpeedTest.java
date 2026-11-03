/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Keep the reel speed: a lock carries the speed the reel plays at to every next reel that isn't an
 * ad, and an ad plays at normal speed. Scrolling on and switching tabs keep it; sliding the lock off, the speed menu and a hold let go
 * of without a lock forget it. Instagram's reset of the reel's speed keeps the kept speed. Off,
 * paused or failing, every reel starts as Instagram starts it.
 */
@RunWith(RobolectricTestRunner.class)
public class ReelSpeedTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The reason Instagram's scroll to the next reel ends a lock with, a string Instagram's build shortens. */
    private static final String SCROLLED = "scroll";

    /** Stands in for Instagram's players and reels: the speeds set on each player, and which reels are ads. */
    private static final class FakeReels implements ReelSpeed.Player {
        final List<String> set = new ArrayList<>();
        final Map<Object, String> names = new IdentityHashMap<>();
        final Set<Object> ads = Collections.newSetFromMap(new IdentityHashMap<>());
        RuntimeException failure;

        Object player(String name) {
            Object player = new Object();
            names.put(player, name);
            return player;
        }

        Object ad() {
            Object item = new Object();
            ads.add(item);
            return item;
        }

        @Override
        public void setSpeed(Object player, float speed) {
            if (failure != null) throw failure;
            set.add(names.get(player) + " " + speed);
        }

        @Override
        public boolean ad(Object item) {
            return ads.contains(item);
        }
    }

    private FakeReels reels;

    @Before
    public void start() {
        ReelSpeed.forget();
        reels = new FakeReels();
        ReelSpeed.access = reels;
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.KEEP_REEL_SPEED.resetToDefault();
        ReelSpeed.forget();
        HookStatus.clear();
    }

    /** A lock as Instagram makes it: the hold at the edge sets the hold speed, then the slide down locks it. */
    private static void lock(float speed) {
        ReelSpeed.speedSet(speed);
        ReelSpeed.lockedUp();
    }

    /** maybeResumePlayer as the patch hooks it: the reel, then its player, just before it plays. */
    private static void play(Object item, Object player) {
        ReelSpeed.item(item);
        ReelSpeed.resuming(player);
    }

    private void playNext(String name) {
        play(new Object(), reels.player(name));
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.KEEP_REEL_SPEED + ":")) return line;
        }
        return null;
    }

    @Test
    public void aLockedSpeedCarriesToEveryNextReel() {
        assertTrue("the switch starts on", Settings.KEEP_REEL_SPEED.get());
        playNext("before");
        assertEquals("a reel before any lock was changed", 0, reels.set.size());
        lock(2f);
        assertEquals(2f, ReelSpeed.kept(), 0f);
        // Scrolling on ends Instagram's lock on the reel it was made on.
        ReelSpeed.lockUpEnded(SCROLLED);
        playNext("second");
        playNext("third");
        Object pooled = reels.player("pooled");
        play(new Object(), pooled);
        play(new Object(), pooled);
        assertEquals(List.of("second 2.0", "third 2.0", "pooled 2.0", "pooled 2.0"), reels.set);
        String status = statusLine();
        assertTrue(status, status.endsWith("Counted: " + ReelSpeed.APPLIED + " 4"));
    }

    /** Switching tabs ends the lock too, and the reel you come back to plays at the kept speed. */
    @Test
    public void switchingTabsKeepsTheSpeed() {
        lock(2f);
        ReelSpeed.lockUpEnded("switch_tab");
        assertEquals(2f, ReelSpeed.kept(), 0f);
        playNext("back");
        assertEquals(List.of("back 2.0"), reels.set);
    }

    @Test
    public void slidingTheLockOffForgetsTheSpeed() {
        lock(2f);
        ReelSpeed.lockUpEnded(ReelSpeed.SLID_OFF);
        assertEquals(1f, ReelSpeed.kept(), 0f);
        assertEquals("the reset after sliding the lock off isn't normal", 1f, ReelSpeed.resetSpeed(1f), 0f);
        playNext("next");
        assertEquals(0, reels.set.size());
    }

    @Test
    public void theSpeedMenuEndingTheLockForgetsTheSpeed() {
        lock(2f);
        ReelSpeed.lockUpEnded(ReelSpeed.MENU);
        playNext("next");
        assertEquals(0, reels.set.size());
    }

    /**
     * A hold at the edge of a later reel, let go of without a lock, ends at normal speed as
     * Instagram ends it, and the reels after it start there too.
     */
    @Test
    public void aHoldLetGoWithoutALockForgetsTheSpeed() {
        lock(2f);
        ReelSpeed.lockUpEnded(SCROLLED);
        playNext("held");
        ReelSpeed.speedSet(2f);
        ReelSpeed.speedSet(1f);
        ReelSpeed.holdEnded();
        assertEquals(1f, ReelSpeed.kept(), 0f);
        playNext("after");
        assertEquals(List.of("held 2.0"), reels.set);
    }

    /** A new lock takes the place of the kept speed, and a hold before it doesn't count. */
    @Test
    public void aNewLockKeepsItsOwnSpeed() {
        lock(2f);
        ReelSpeed.lockUpEnded(SCROLLED);
        lock(1.5f);
        playNext("next");
        assertEquals(List.of("next 1.5"), reels.set);
    }

    /** Instagram's reset sets the reel on screen back to normal; with a speed kept it stays at that speed. */
    @Test
    public void theResetKeepsTheKeptSpeed() {
        assertEquals("nothing kept, the reset is Instagram's", 1f, ReelSpeed.resetSpeed(1f), 0f);
        lock(2f);
        assertEquals(2f, ReelSpeed.resetSpeed(1f), 0f);
    }

    @Test
    public void aLockAtNormalSpeedKeepsNothing() {
        lock(1f);
        assertEquals(1f, ReelSpeed.kept(), 0f);
        playNext("next");
        assertEquals(0, reels.set.size());
    }

    /**
     * Instagram's reset on the scroll to an ad hands the ad's player the kept speed, as it does any
     * reel's, so the ad is set back to normal before it plays. The speed stays kept for the next reel.
     */
    @Test
    public void anAdPlaysAtNormalSpeed() {
        lock(2f);
        assertEquals("the reset on the scroll to the ad", 2f, ReelSpeed.resetSpeed(1f), 0f);
        play(reels.ad(), reels.player("ad"));
        assertEquals(List.of("ad 1.0"), reels.set);
        assertEquals(2f, ReelSpeed.kept(), 0f);
        playNext("reel");
        assertEquals(List.of("ad 1.0", "reel 2.0"), reels.set);
    }

    @Test
    public void withNothingKeptAnAdIsLeftAlone() {
        play(reels.ad(), reels.player("ad"));
        assertEquals(0, reels.set.size());
    }

    @Test
    public void offNothingIsKeptOrApplied() {
        Settings.KEEP_REEL_SPEED.save(false);
        lock(2f);
        Settings.KEEP_REEL_SPEED.save(true);
        playNext("first");
        assertEquals("a lock made while off was kept", 0, reels.set.size());

        lock(2f);
        Settings.KEEP_REEL_SPEED.save(false);
        playNext("second");
        assertEquals("a reel started while off got the kept speed", 0, reels.set.size());
        assertEquals("the reset while off isn't Instagram's", 1f, ReelSpeed.resetSpeed(1f), 0f);
        Settings.KEEP_REEL_SPEED.save(true);
        playNext("third");
        assertEquals("a speed kept before the switch went off came back", 0, reels.set.size());
    }

    @Test
    public void pausedEveryReelStartsAsInstagramStartsIt() {
        for (HushgramPause.Reason reason : new HushgramPause.Reason[] {
                HushgramPause.Reason.SWITCH, HushgramPause.Reason.CRASH_LOOP}) {
            lock(2f);
            PauseForTests.pause(reason);
            playNext(reason.name());
            assertEquals(reason.name(), 0, reels.set.size());
            PauseForTests.resume();
        }
        lock(2f);
        playNext("running");
        assertEquals(List.of("running 2.0"), reels.set);
    }

    @Test
    public void withoutSettingsNothingIsKept() {
        SettingsContextRule.withoutContext(() -> lock(2f));
        playNext("next");
        assertEquals(0, reels.set.size());
    }

    @Test
    public void aFailingSetterIsReportedAndTheReelPlaysOn() {
        lock(2f);
        reels.failure = new IllegalStateException("the setter failed");
        playNext("next");
        assertTrue(HookStatus.missing(FamilyNames.KEEP_REEL_SPEED).get(0)
                .startsWith("a working 'reel start' hook (it threw "));
    }

    /** The reel handed over goes with the next player only: a player resumed without one isn't taken for an ad. */
    @Test
    public void theReelHandedOverGoesWithOnePlayer() {
        lock(2f);
        ReelSpeed.item(reels.ad());
        ReelSpeed.resuming(reels.player("ad"));
        ReelSpeed.resuming(reels.player("alone"));
        assertEquals(List.of("ad 1.0", "alone 2.0"), reels.set);
    }

    /** The speed icon (#480): a pick goes on the reel on screen and, with the switch on, on the next reels too. */
    @Test
    public void aPickedSpeedPlaysNowAndIsKeptForTheNextReels() {
        assertFalse("before any reel played there's nothing to set", ReelSpeed.choose(2f));
        assertEquals(1f, ReelSpeed.playing(), 0f);
        Object first = reels.player("first");
        play(new Object(), first);
        assertTrue(ReelSpeed.choose(1.75f));
        assertEquals(List.of("first 1.75"), reels.set);
        assertEquals(1.75f, ReelSpeed.playing(), 0f);
        assertEquals("kept like a lock", 1.75f, ReelSpeed.kept(), 0f);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelSpeed.PICKED));

        playNext("second");
        assertEquals(List.of("first 1.75", "second 1.75"), reels.set);
        assertEquals(1.75f, ReelSpeed.playing(), 0f);
        assertTrue("normal speed forgets it", ReelSpeed.choose(1f));
        assertEquals(1f, ReelSpeed.kept(), 0f);
        playNext("third");
        assertEquals("the third starts as Instagram starts it", 3, reels.set.size());
    }

    @Test
    public void withTheSwitchOffAPickPlaysOnTheReelAloneAndPausedNothingChanges() {
        Settings.KEEP_REEL_SPEED.save(false);
        Object first = reels.player("first");
        play(new Object(), first);
        assertTrue(ReelSpeed.choose(3f));
        assertEquals(3f, ReelSpeed.playing(), 0f);
        assertEquals("nothing kept", 1f, ReelSpeed.kept(), 0f);
        play(new Object(), first);
        assertEquals("the same player resumed still plays at it", 3f, ReelSpeed.playing(), 0f);
        playNext("second");
        assertEquals(List.of("first 3.0"), reels.set);
        assertEquals("another player starts at normal speed", 1f, ReelSpeed.playing(), 0f);

        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelSpeed.choose(2f));
        assertEquals(List.of("first 3.0"), reels.set);
    }

    @Test
    public void aFailingPickIsReportedNotThrown() {
        playNext("first");
        reels.failure = new IllegalStateException("the setter failed");
        assertFalse(ReelSpeed.choose(2f));
        assertTrue(String.join("\n", HookStatus.report()).contains("speed pick"));
    }

    /** Until the patch fills the stubs in, no reel is an ad and setting a speed does nothing. */
    @Test
    public void unpatchedStubsDoNothing() {
        ReelSpeed.access = ReelSpeed.PATCHED;
        lock(2f);
        playNext("next");
        assertTrue(HookStatus.missing(FamilyNames.KEEP_REEL_SPEED).isEmpty());
    }
}
