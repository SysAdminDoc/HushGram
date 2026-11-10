/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.instagram.download.InstagramMedia;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Stop videos looping: the loop flag Instagram's video player hands its engine (#248, #175), and Loop this video (#610). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StopLoopingTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Object player = new Object();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.STOP_VIDEO_LOOPING.save(true);
        StopLooping.autoScrollsForTests = false;
        StopLooping.reelsForTests = false;
        HookStatus.clear();
    }

    @After
    public void restore() {
        StopLooping.autoScrollsForTests = null;
        StopLooping.reelsForTests = null;
        StopLooping.forget();
        Built.stopLooping = true;
        Settings.STOP_VIDEO_LOOPING.resetToDefault();
        Settings.REEL_AUTO_ADVANCE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** On, a video Instagram would loop plays once, and one it wouldn't loop stays that way. */
    @Test
    public void onAVideoPlaysOnce() {
        assertFalse(StopLooping.loop(player, 1));
        assertFalse(StopLooping.loop(player, 0));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.STOP_LOOPING));
    }

    /** A nonzero flag of any value counts as Instagram's yes: the register can hold an int. */
    @Test
    public void anyNonzeroFlagIsAYes() {
        Settings.STOP_VIDEO_LOOPING.save(false);
        assertTrue(StopLooping.loop(player, 1));
        assertTrue(StopLooping.loop(player, 0x100));
        assertTrue(StopLooping.loop(player, -1));
    }

    /** Off, the engine gets Instagram's flag either way. */
    @Test
    public void offInstagramDecides() {
        Settings.STOP_VIDEO_LOOPING.save(false);
        assertTrue(StopLooping.loop(player, 1));
        assertFalse(StopLooping.loop(player, 0));
    }

    /** Paused, the engine gets Instagram's flag. */
    @Test
    public void pausedInstagramDecides() {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(StopLooping.loop(player, 1));
        assertFalse(StopLooping.loop(player, 0));
    }

    /**
     * While auto scroll moves Reels on, a reel in the Reels viewer still loops, since the scroller
     * waits for the loop. A video anywhere else still stops.
     */
    @Test
    public void autoScrollKeepsReelsLooping() {
        StopLooping.autoScrollsForTests = true;
        StopLooping.reelsForTests = true;
        assertTrue(StopLooping.loop(player, 1));
        assertFalse(StopLooping.loop(player, 0));

        StopLooping.reelsForTests = false;
        assertFalse(StopLooping.loop(player, 1));

        StopLooping.autoScrollsForTests = false;
        StopLooping.reelsForTests = true;
        assertFalse(StopLooping.loop(player, 1));
    }

    /**
     * Without Keep Reels auto scroll on in the build, nothing says Reels moves on by itself, so Move
     * on's saved switch alone doesn't count. The unpatched reels stub answers no.
     */
    @Test
    public void unpatchedBuildNeverKeepsTheLoop() {
        StopLooping.autoScrollsForTests = null;
        StopLooping.reelsForTests = null;
        Settings.REEL_AUTO_ADVANCE.save(true);
        assertFalse(StopLooping.autoScrolls());
        assertFalse(StopLooping.reels(player));
        assertFalse(StopLooping.loop(player, 1));
    }

    /**
     * Loop this video (#610): a video looped from its menu loops in each player playing it, even
     * where Instagram wouldn't loop it, and the players playing it are handed their flag again at
     * once. Stop looping this video takes it off the same way. Other videos still play once.
     */
    @Test
    @Config(shadows = {Built.class, Posts.class})
    public void aVideoLoopedFromItsMenuLoops() {
        Players players = new Players();
        StopLooping.access = players;
        Object looped = new Object();
        Object other = new Object();
        Object later = new Object();
        players.playing.put(looped, "3711_99");
        players.playing.put(other, "4802");
        players.playing.put(later, "3711");
        assertFalse(StopLooping.loop(looped, 1));
        assertFalse(StopLooping.loop(other, 1));

        assertFalse(StopLooping.looping("3711_42"));
        assertEquals(Boolean.TRUE, StopLooping.toggle("3711_42"));
        assertTrue(StopLooping.looping("3711_42"));
        assertEquals("only the player playing it is handed its flag again", List.of(looped), players.relooped);
        assertTrue(StopLooping.loop(looped, 1));
        assertTrue("whatever Instagram's flag says", StopLooping.loop(looped, 0));
        assertTrue("a player that starts it later loops it too", StopLooping.loop(later, 0));
        assertFalse("another video still plays once", StopLooping.loop(other, 1));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(StopLooping.LOOPED));

        players.relooped.clear();
        assertEquals(Boolean.FALSE, StopLooping.toggle("3711_42"));
        assertFalse(StopLooping.looping("3711_42"));
        assertEquals(List.of(looped, later), sorted(players.relooped, looped, later));
        assertFalse(StopLooping.loop(looped, 1));
        assertTrue(String.join("\n", HookStatus.report()).contains(StopLooping.UNLOOPED));
    }

    /**
     * A video that already stopped on its last frame plays again from the start when it's looped
     * from its menu, since a new flag alone doesn't move it (#610). One still playing, paused partway
     * or whose length isn't known is only handed its flag, and taking the loop off restarts nothing.
     */
    @Test
    @Config(shadows = {Built.class, Posts.class})
    public void anEndedVideoPlaysAgainWhenLooped() {
        Players players = new Players();
        StopLooping.access = players;
        Object ended = new Object();
        Object nearlyEnded = new Object();
        Object partway = new Object();
        Object unknown = new Object();
        Object elsewhere = new Object();
        for (Object one : List.of(ended, nearlyEnded, partway, unknown)) players.playing.put(one, "3711");
        players.playing.put(elsewhere, "4802");
        players.at.put(ended, new int[] {31_000, 31_000});
        players.at.put(nearlyEnded, new int[] {31_000 - StopLooping.END_MS, 31_000});
        players.at.put(partway, new int[] {12_000, 31_000});
        players.at.put(elsewhere, new int[] {9_000, 9_000});
        for (Object one : List.of(ended, nearlyEnded, partway, unknown, elsewhere)) StopLooping.loop(one, 1);

        assertEquals(Boolean.TRUE, StopLooping.toggle("3711_42"));
        assertEquals(List.of(ended, nearlyEnded), sorted(players.restarted, ended, nearlyEnded));
        assertEquals("each player showing it still gets its flag", 4, players.relooped.size());

        players.restarted.clear();
        assertEquals(Boolean.FALSE, StopLooping.toggle("3711_42"));
        assertTrue("taking the loop off restarts nothing", players.restarted.isEmpty());

        assertTrue(StopLooping.ended(ended));
        assertFalse(StopLooping.ended(partway));
        assertFalse("no length, no end", StopLooping.ended(unknown));
        assertEquals("the unpatched stub knows no length", -1, StopLooping.PATCHED.length(ended));
    }

    /** With the switch off, paused, or for media without an ID, the row changes nothing. */
    @Test
    @Config(shadows = {Built.class, Posts.class})
    public void aLoopNeedsTheSwitchAndAnId() {
        Players players = new Players();
        StopLooping.access = players;
        assertNull(StopLooping.toggle(new Object()));
        assertNull(StopLooping.toggle(" _5"));

        Settings.STOP_VIDEO_LOOPING.save(false);
        assertNull(StopLooping.toggle("3711_42"));
        assertFalse(StopLooping.offersRow(false));

        Settings.STOP_VIDEO_LOOPING.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull(StopLooping.toggle("3711_42"));
        assertTrue(players.relooped.isEmpty());
    }

    /**
     * The row is offered with the patch in the build and its switch on. A reel's menu leaves it out
     * while auto scroll keeps Reels looping anyway, and a feed menu keeps it.
     */
    @Test
    @Config(shadows = Built.class)
    public void theRowNeedsThePatchAndItsSwitch() {
        assertTrue(StopLooping.offersRow(false));
        assertTrue(StopLooping.offersRow(true));
        StopLooping.autoScrollsForTests = true;
        assertFalse(StopLooping.offersRow(true));
        assertTrue(StopLooping.offersRow(false));
        Built.stopLooping = false;
        assertFalse(StopLooping.offersRow(false));
    }

    /** A media ID or a video source's ID, joined to its owner's or not, comes down to the media's own number. */
    @Test
    public void theNumberIsTheIdBeforeTheOwner() {
        assertEquals("3711", StopLooping.number("3711_42"));
        assertEquals("3711", StopLooping.number("3711"));
        assertEquals("3711", StopLooping.number(" 3711 _42"));
        assertNull(StopLooping.number(null));
        assertNull(StopLooping.number(""));
        assertNull(StopLooping.number("_42"));
    }

    /** [seen] in the order of [order], since players are kept in no order. */
    private static List<Object> sorted(List<Object> seen, Object... order) {
        List<Object> sorted = new ArrayList<>();
        for (Object one : order) if (seen.contains(one)) sorted.add(one);
        assertEquals("handed more than once, or one not asked for: " + seen, seen.size(), sorted.size());
        return sorted;
    }

    /** Players, the media ID each plays and where each is in it, standing in for the patch's readers. */
    private static final class Players implements StopLooping.Player {
        final Map<Object, String> playing = new IdentityHashMap<>();
        final Map<Object, int[]> at = new IdentityHashMap<>();
        final List<Object> relooped = new ArrayList<>();
        final List<Object> restarted = new ArrayList<>();

        @Override
        public String videoId(Object player) {
            return playing.get(player);
        }

        @Override
        public void reloop(Object player) {
            relooped.add(player);
        }

        @Override
        public int position(Object player) {
            int[] where = at.get(player);
            return where == null ? -1 : where[0];
        }

        @Override
        public int length(Object player) {
            int[] where = at.get(player);
            return where == null ? -1 : where[1];
        }

        @Override
        public void restart(Object player) {
            restarted.add(player);
        }
    }

    /** This build has Stop videos looping, as the patch writes its status method. */
    @Implements(value = SettingsStatus.class, isInAndroidSdk = false)
    public static class Built {
        static boolean stopLooping = true;

        @Implementation protected static boolean stopLooping() { return stopLooping; }
    }

    /** A post here is its media ID, as a String. */
    @Implements(value = InstagramMedia.class, isInAndroidSdk = false)
    public static class Posts {
        @Implementation protected static String mediaId(Object media) { return media instanceof String ? (String) media : null; }
    }
}
