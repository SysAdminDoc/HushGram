/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import com.instagram.clips.intf.ClipsViewerSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What the Reels viewer's hooks answer (#606): a viewer opened from a link skips the reels Instagram
 * kept on the phone, and holds the network's chunks of reels until the linked reel is in, so the
 * linked reel is the one shown. Every other viewer, off and paused keep Instagram's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelLinksTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Stands in for Instagram's ClipsViewerSource, whose constant names its build keeps. */
    enum Source { THIRD_PARTY_URL, PRIME_THIRD_PARTY_URL, CLIPS_TAB, FEED_TIMELINE, DIRECT_THREAD, THIRD_PARTY_URL_NOTIFICATION }

    /** Stands in for Instagram's Reels viewer config, which holds where the viewer was opened from. */
    static final class ViewerConfig {
        final ClipsViewerSource source;

        ViewerConfig(ClipsViewerSource source) {
            this.source = source;
        }
    }

    /** Stands in for Instagram's Reels viewer adapter: its config and its one method adding a chunk. */
    static final class Adapter {
        final ViewerConfig config;
        final List<String> added = new ArrayList<>();

        Adapter(ClipsViewerSource source) {
            config = new ViewerConfig(source);
        }

        public void fill(Object head, List<?> items, boolean firstPage, boolean refreshing,
                         boolean loading, boolean more, boolean forward) {
            added.add(head + " " + items + " first=" + firstPage + " refreshing=" + refreshing
                    + " loading=" + loading + " more=" + more + " forward=" + forward);
        }
    }

    /** An adapter with two methods shaped like the one adding a chunk, so neither can be told apart. */
    static final class TwoFills {
        final ViewerConfig config = new ViewerConfig(ClipsViewerSource.THIRD_PARTY_URL);

        public void fill(Object head, List<?> items, boolean a, boolean b, boolean c, boolean d, boolean e) {
        }

        public void refill(Object head, List<?> items, boolean a, boolean b, boolean c, boolean d, boolean e) {
        }
    }

    @Before
    public void clean() {
        Settings.OPEN_REEL_LINKS_FIRST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ReelLinks.forgetForTests();
    }

    @After
    public void restore() {
        Settings.OPEN_REEL_LINKS_FIRST.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        ReelLinks.forgetForTests();
    }

    /** A forward chunk that adds to the reels already there, as Instagram passes it. */
    private static int chunk(Object adapter, String reel) {
        return ReelLinks.holdsReels(adapter, "head", Collections.singletonList(reel), 0, 0, 0, 1, 1);
    }

    private static String added(String reel) {
        return "head [" + reel + "] first=false refreshing=false loading=false more=true forward=true";
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static String report() {
        List<String> lines = HookStatus.report();
        StringBuilder all = new StringBuilder();
        for (String line : lines) all.append(line).append('\n');
        return all.toString();
    }

    /** On to start: a link's viewer skips the cached reels, and each skip is counted. */
    @Test
    public void aLinksViewerSkipsTheCachedReels() {
        assertTrue("the switch starts on", Settings.OPEN_REEL_LINKS_FIRST.get());
        assertEquals("a link", 1, ReelLinks.skipsCachedReels(Source.THIRD_PARTY_URL));
        assertEquals("a link Instagram opened itself", 1, ReelLinks.skipsCachedReels(Source.PRIME_THIRD_PARTY_URL));
        String report = report();
        assertTrue(report, report.contains(FamilyNames.REEL_LINKS + ": invoked 2"));
        assertTrue(report, report.contains(ReelLinks.SKIPPED + " 2"));
    }

    /** Any other viewer keeps Instagram's own, names that only start like a link's included. */
    @Test
    public void everyOtherViewerKeepsInstagramsOwn() {
        assertEquals("the Reels tab", 0, ReelLinks.skipsCachedReels(Source.CLIPS_TAB));
        assertEquals("Home", 0, ReelLinks.skipsCachedReels(Source.FEED_TIMELINE));
        assertEquals("a chat", 0, ReelLinks.skipsCachedReels(Source.DIRECT_THREAD));
        assertEquals("a longer name", 0, ReelLinks.skipsCachedReels(Source.THIRD_PARTY_URL_NOTIFICATION));
        assertEquals("not a source", 0, ReelLinks.skipsCachedReels("THIRD_PARTY_URL"));
        assertEquals("no source", 0, ReelLinks.skipsCachedReels(null));
        assertFalse(report(), report().contains(ReelLinks.SKIPPED));
    }

    /** Off, paused or before the settings are read, a link's viewer keeps Instagram's cached reels. */
    @Test
    public void offPausedOrNotReadyKeepInstagramsOwn() {
        Settings.OPEN_REEL_LINKS_FIRST.save(false);
        assertEquals("off", 0, ReelLinks.skipsCachedReels(Source.THIRD_PARTY_URL));
        Settings.OPEN_REEL_LINKS_FIRST.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, ReelLinks.skipsCachedReels(Source.THIRD_PARTY_URL));
        assertTrue("the saved choice is kept", Settings.OPEN_REEL_LINKS_FIRST.savedValue());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertEquals(1, ReelLinks.skipsCachedReels(Source.THIRD_PARTY_URL));
        SettingsContextRule.withoutContext(() -> assertEquals("not ready", 0, ReelLinks.skipsCachedReels(Source.THIRD_PARTY_URL)));
    }

    /**
     * A warm link: the network's chunks come back before the linked reel. They wait, then go in behind
     * it in the order they came, after Instagram has placed it. A chunk arriving while they still wait
     * waits with them; one after goes straight in.
     */
    @Test
    public void aWarmLinksChunksWaitForTheLinkedReel() {
        Adapter viewer = new Adapter(ClipsViewerSource.THIRD_PARTY_URL);
        assertEquals("the first chunk waits", 1, chunk(viewer, "a"));
        assertEquals("so does the next", 1, chunk(viewer, "b"));
        idle();
        assertTrue("nothing added before the linked reel", viewer.added.isEmpty());

        ReelLinks.linkedReelIn(viewer);
        assertTrue("held until Instagram is done placing the linked reel", viewer.added.isEmpty());
        assertEquals("a chunk ahead of the replay waits behind the held ones", 1, chunk(viewer, "c"));
        idle();
        assertEquals(Arrays.asList(added("a"), added("b"), added("c")), viewer.added);

        assertEquals("later chunks go straight in", 0, chunk(viewer, "d"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ReelLinks.WAIT_MILLIS, TimeUnit.MILLISECONDS);
        assertEquals("the wait running out adds nothing twice", 3, viewer.added.size());
        String report = report();
        assertTrue(report, report.contains(ReelLinks.HELD + " 3"));
        assertTrue(report, report.contains(ReelLinks.ADDED + " 3"));
        assertFalse(report, report.contains(ReelLinks.WAITED_OUT));
    }

    /** A held chunk keeps Instagram's own first page, loading and more pages flags. */
    @Test
    public void aHeldChunkKeepsItsFlags() {
        Adapter viewer = new Adapter(ClipsViewerSource.THIRD_PARTY_URL);
        assertEquals(1, ReelLinks.holdsReels(viewer, "top", Collections.singletonList("a"), 1, 0, 1, 0, 1));
        ReelLinks.linkedReelIn(viewer);
        idle();
        assertEquals(Collections.singletonList("top [a] first=true refreshing=false loading=true more=false forward=true"),
                viewer.added);
    }

    /** A linked reel that never comes still lets the held chunks in once the wait runs out. */
    @Test
    public void heldChunksGoInWhenTheLinkedReelNeverComes() {
        Adapter viewer = new Adapter(ClipsViewerSource.THIRD_PARTY_URL);
        assertEquals(1, chunk(viewer, "a"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ReelLinks.WAIT_MILLIS - 1, TimeUnit.MILLISECONDS);
        assertTrue("still waiting", viewer.added.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals(Collections.singletonList(added("a")), viewer.added);
        assertEquals("after that, chunks go straight in", 0, chunk(viewer, "b"));
        ReelLinks.linkedReelIn(viewer);
        idle();
        assertEquals(1, viewer.added.size());
        String report = report();
        assertTrue(report, report.contains(ReelLinks.WAITED_OUT + " 1"));
        assertFalse(report, report.contains(ReelLinks.ADDED));
    }

    /** A cold link puts the linked reel in first, so nothing after it is ever held. */
    @Test
    public void aColdLinkNeverHolds() {
        Adapter viewer = new Adapter(ClipsViewerSource.THIRD_PARTY_URL);
        ReelLinks.linkedReelIn(viewer);
        assertEquals(0, chunk(viewer, "a"));
        assertEquals(0, chunk(viewer, "b"));
        idle();
        assertFalse(report(), report().contains(ReelLinks.HELD));
    }

    /**
     * Never held: a refresh (it can clear the viewer), a chunk that isn't forward, a viewer that
     * wasn't opened from a link (a link Instagram warmed up first included), and no adapter.
     */
    @Test
    public void otherChunksAndViewersGoStraightIn() {
        Adapter link = new Adapter(ClipsViewerSource.THIRD_PARTY_URL);
        assertEquals("a refresh", 0, ReelLinks.holdsReels(link, "head", Collections.singletonList("a"), 1, 1, 0, 1, 1));
        assertEquals("not forward", 0, ReelLinks.holdsReels(link, "head", Collections.singletonList("a"), 0, 0, 0, 1, 0));
        assertEquals("the Reels tab", 0, chunk(new Adapter(ClipsViewerSource.CLIPS_TAB), "a"));
        assertEquals("a chat", 0, chunk(new Adapter(ClipsViewerSource.DIRECT_THREAD), "a"));
        assertEquals("a warmed up link", 0, chunk(new Adapter(ClipsViewerSource.PRIME_THIRD_PARTY_URL), "a"));
        assertEquals("no source", 0, chunk(new Adapter(null), "a"));
        assertEquals("not a viewer", 0, chunk("adapter", "a"));
        assertEquals("no adapter", 0, chunk(null, "a"));
        ReelLinks.linkedReelIn(null);
        assertFalse(report(), report().contains(ReelLinks.HELD));
    }

    /**
     * A comment shared in a chat, tapped on a reel, opens a viewer from the chat that loads the reel by
     * its id: its chunks wait like a link's until the reel is in (#662). One viewer per tap.
     */
    @Test
    public void aSharedCommentsViewerWaitsForItsReel() {
        ReelLinks.sharedCommentOpened(ReelLinks.ON_REEL);
        Adapter viewer = new Adapter(ClipsViewerSource.DIRECT);
        assertEquals("the chunk waits", 1, chunk(viewer, "a"));
        idle();
        assertTrue("nothing added before the reel", viewer.added.isEmpty());
        ReelLinks.linkedReelIn(viewer);
        idle();
        assertEquals(Collections.singletonList(added("a")), viewer.added);
        assertEquals("the next chat viewer isn't the comment's", 0, chunk(new Adapter(ClipsViewerSource.DIRECT), "b"));
        String report = report();
        assertTrue(report, report.contains(ReelLinks.HELD + " 1"));
        assertTrue(report, report.contains(ReelLinks.ADDED + " 1"));
    }

    /**
     * Never held for a shared comment: a chat viewer with no comment tapped, a comment on something
     * that isn't a reel, another viewer, a viewer too long after the tap, one whose reel went in
     * first (which spends the tap), and the switch off.
     */
    @Test
    public void otherSharedCommentsAndViewersGoStraightIn() {
        assertEquals("no comment tapped", 0, chunk(new Adapter(ClipsViewerSource.DIRECT), "a"));
        ReelLinks.sharedCommentOpened("photo");
        assertEquals("a comment on a post", 0, chunk(new Adapter(ClipsViewerSource.DIRECT), "a"));
        ReelLinks.sharedCommentOpened(null);
        assertEquals("nothing said what it was on", 0, chunk(new Adapter(ClipsViewerSource.DIRECT), "a"));

        ReelLinks.sharedCommentOpened(ReelLinks.ON_GLIMMER);
        assertEquals("the Reels tab", 0, chunk(new Adapter(ClipsViewerSource.CLIPS_TAB), "a"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(ReelLinks.COMMENT_MILLIS + 1, TimeUnit.MILLISECONDS);
        assertEquals("too long after the tap", 0, chunk(new Adapter(ClipsViewerSource.DIRECT), "a"));

        ReelLinks.sharedCommentOpened(ReelLinks.ON_REEL);
        Adapter cold = new Adapter(ClipsViewerSource.DIRECT);
        ReelLinks.linkedReelIn(cold);
        assertEquals("its reel went in first", 0, chunk(cold, "a"));
        assertEquals("and that spent the tap", 0, chunk(new Adapter(ClipsViewerSource.DIRECT), "b"));

        Settings.OPEN_REEL_LINKS_FIRST.save(false);
        ReelLinks.sharedCommentOpened(ReelLinks.ON_REEL);
        assertEquals("off", 0, chunk(new Adapter(ClipsViewerSource.DIRECT), "a"));
        assertFalse(report(), report().contains(ReelLinks.HELD));
    }

    /** A chunk is never held when it couldn't be added again: the adapter has two methods shaped like it. */
    @Test
    public void noSingleWayToAddItMeansNoHold() {
        assertEquals(0, chunk(new TwoFills(), "a"));
        assertFalse(report(), report().contains(ReelLinks.HELD));
    }

    /** Off, paused or before the settings are read, a link's chunks go straight in. */
    @Test
    public void offPausedOrNotReadyHoldNothing() {
        Settings.OPEN_REEL_LINKS_FIRST.save(false);
        assertEquals("off", 0, chunk(new Adapter(ClipsViewerSource.THIRD_PARTY_URL), "a"));
        Settings.OPEN_REEL_LINKS_FIRST.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", 0, chunk(new Adapter(ClipsViewerSource.THIRD_PARTY_URL), "a"));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() ->
                assertEquals("not ready", 0, chunk(new Adapter(ClipsViewerSource.THIRD_PARTY_URL), "a")));
        assertEquals(1, chunk(new Adapter(ClipsViewerSource.THIRD_PARTY_URL), "a"));
    }
}
