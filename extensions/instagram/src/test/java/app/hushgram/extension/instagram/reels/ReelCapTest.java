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
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Stop after 20 reels: when a session's reels run out, what it stops, and when a break starts a new one. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelCapTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for the Reels pager, recording what its input is set to. */
    public static final class Pager {
        public final List<Boolean> inputs = new ArrayList<>();

        public void setUserInputEnabled(boolean enabled) {
            inputs.add(enabled);
        }
    }

    @Before
    public void setUp() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_REELS_SCROLLING.save(false);
        Settings.REEL_CAP.save(true);
        ReelScrolling.forget();
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_REELS_SCROLLING.resetToDefault();
        Settings.REEL_CAP.resetToDefault();
        ReelScrolling.forget();
        HookStatus.clear();
    }

    private static void play(Pager pager, int from, int reels) {
        for (int position = from; position < from + reels; position++) ReelScrolling.page(pager, position);
    }

    @Test
    public void theTwentiethReelTurnsSwipingOff() {
        Pager pager = new Pager();
        assertEquals(1, ReelScrolling.pager(pager));

        // The reel the viewer opens on, then 19 more.
        play(pager, 0, ReelScrolling.CAP);
        assertTrue(pager.inputs.isEmpty());
        assertEquals(1, ReelScrolling.userInput(pager, 1));

        ReelScrolling.page(pager, ReelScrolling.CAP);
        assertEquals(java.util.Collections.singletonList(false), pager.inputs);
        assertEquals("Instagram turning it back on is refused", 0, ReelScrolling.userInput(pager, 1));
        assertEquals("a reel opened from a message still plays, without swipes", 0, ReelScrolling.pager(new Pager()));
        assertEquals(0, ReelScrolling.pull());
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(FamilyNames.REEL_SCROLLING) && report.contains(ReelScrolling.CAPPED + " 1"));
    }

    @Test
    public void theSamePageAndOtherPagersDontCount() {
        Pager reels = new Pager();
        ReelScrolling.pager(reels);
        Pager other = new Pager();
        for (int i = 0; i < ReelScrolling.CAP * 2; i++) {
            ReelScrolling.page(reels, 3);
            ReelScrolling.page(other, i);
        }
        assertTrue(reels.inputs.isEmpty());
        assertEquals(1, ReelScrolling.userInput(reels, 1));
    }

    /** Going back to a reel and forward again, or a refresh back to the top, counts nothing new. */
    @Test
    public void onlyReelsNotSeenBeforeCount() {
        Pager pager = new Pager();
        ReelScrolling.pager(pager);
        play(pager, 0, 11);
        for (int i = 0; i < ReelScrolling.CAP * 2; i++) {
            ReelScrolling.page(pager, 5);
            ReelScrolling.page(pager, 10);
        }
        play(pager, 0, 11);
        play(pager, 11, ReelScrolling.CAP - 11);
        assertTrue("19 new reels past the first", pager.inputs.isEmpty());
        assertEquals(1, ReelScrolling.userInput(pager, 1));

        ReelScrolling.page(pager, ReelScrolling.CAP);
        assertEquals(java.util.Collections.singletonList(false), pager.inputs);
    }

    /** Turned off while capped, the open viewer swipes again at the next touch, and on again it counts afresh. */
    @Test
    public void turningItOffGivesTheSwipesBackAtOnce() {
        Pager pager = new Pager();
        ReelScrolling.pager(pager);
        play(pager, 0, ReelScrolling.CAP + 1);
        assertEquals(0, ReelScrolling.userInput(pager, 1));

        Settings.REEL_CAP.save(false);
        assertEquals("a touch in Reels", 1, ReelScrolling.pull());
        assertEquals(java.util.Arrays.asList(false, true), pager.inputs);
        assertEquals(1, ReelScrolling.userInput(pager, 1));
        assertEquals("a new viewer", 1, ReelScrolling.pager(new Pager()));
        play(pager, 0, ReelScrolling.CAP * 2);
        assertEquals(2, pager.inputs.size());

        Settings.REEL_CAP.save(true);
        play(pager, 100, ReelScrolling.CAP);
        assertEquals(2, pager.inputs.size());
        assertEquals(1, ReelScrolling.userInput(pager, 1));
    }

    /** Pausing HushGram while capped gives the swipes back too, at the next page store. */
    @Test
    public void pausingGivesTheSwipesBack() {
        Pager pager = new Pager();
        ReelScrolling.pager(pager);
        play(pager, 0, ReelScrolling.CAP + 1);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        ReelScrolling.page(pager, 3);
        assertEquals(java.util.Arrays.asList(false, true), pager.inputs);
        assertEquals(1, ReelScrolling.userInput(pager, 1));
    }

    /** Turned off with Stop Reels scrolling on, the pager stays still for that switch. */
    @Test
    public void turningItOffLeavesStopReelsScrollingInCharge() {
        Pager pager = new Pager();
        ReelScrolling.pager(pager);
        play(pager, 0, ReelScrolling.CAP + 1);
        Settings.STOP_REELS_SCROLLING.save(true);
        Settings.REEL_CAP.save(false);
        assertEquals(0, ReelScrolling.pull());
        assertEquals(java.util.Collections.singletonList(false), pager.inputs);
        assertEquals(0, ReelScrolling.userInput(pager, 1));
    }

    @Test
    public void offItNeverCaps() {
        Settings.REEL_CAP.save(false);
        Pager pager = new Pager();
        ReelScrolling.pager(pager);
        play(pager, 0, ReelScrolling.CAP * 2);
        assertTrue(pager.inputs.isEmpty());
        assertEquals(1, ReelScrolling.userInput(pager, 1));
    }

    @Test
    public void aLongEnoughBreakStartsANewSession() {
        Pager pager = new Pager();
        ReelScrolling.pager(pager);
        play(pager, 0, ReelScrolling.CAP + 1);
        assertEquals(0, ReelScrolling.userInput(pager, 1));

        ReelScrolling.hidden(1_000);
        ReelScrolling.shown(1_000 + ReelScrolling.BREAK_MILLIS - 1);
        assertEquals("a short break keeps the cap", 0, ReelScrolling.userInput(pager, 1));

        ReelScrolling.hidden(5_000_000);
        ReelScrolling.shown(5_000_000 + ReelScrolling.BREAK_MILLIS);
        assertEquals(java.util.Arrays.asList(false, true), pager.inputs);
        assertEquals(1, ReelScrolling.userInput(pager, 1));
        play(pager, 100, ReelScrolling.CAP - 1);
        assertEquals(1, ReelScrolling.userInput(pager, 1));
    }

    @Test
    public void stopReelsScrollingKeepsSwipesOffAfterABreak() {
        Pager pager = new Pager();
        ReelScrolling.pager(pager);
        play(pager, 0, ReelScrolling.CAP + 1);
        Settings.STOP_REELS_SCROLLING.save(true);
        ReelScrolling.hidden(1_000);
        ReelScrolling.shown(1_000 + ReelScrolling.BREAK_MILLIS);
        assertEquals(java.util.Collections.singletonList(false), pager.inputs);
        assertEquals(0, ReelScrolling.userInput(pager, 1));
    }

    @Test
    public void aPagerWithoutTheSetterIsReportedNotThrown() {
        Object pager = new Object();
        ReelScrolling.pager(pager);
        for (int position = 0; position <= ReelScrolling.CAP; position++) ReelScrolling.page(pager, position);
        assertFalse(HookStatus.missing(FamilyNames.REEL_SCROLLING).isEmpty());
        assertEquals(0, ReelScrolling.userInput(pager, 1));
    }
}
