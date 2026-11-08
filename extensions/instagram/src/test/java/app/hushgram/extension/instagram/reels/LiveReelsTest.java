/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.FeedFilterCounters;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which items a page of reels keeps when lives are hidden (#566), and where the filter reads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LiveReelsTest {
    /** A Reels item; a live one carries a live preview part, as Instagram's item does. */
    static final class Clip {
        final Object preview;

        Clip(Object preview) {
            this.preview = preview;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final AtomicInteger reads = new AtomicInteger();

    /** Reads a Clip's live preview part the way the patch-filled stub reads Instagram's item. */
    private final Function<Object, Object> preview = item -> {
        reads.incrementAndGet();
        return item instanceof Clip ? ((Clip) item).preview : null;
    };

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_LIVE_REELS.save(true);
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_LIVE_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private static final String REELS_TAB = "clips_viewer_clips_tab";

    private List<?> page(List<?> items, String module) {
        return LiveReels.page(items, module, preview, LiveReels::switchedOn);
    }

    private static Clip reel() {
        return new Clip(null);
    }

    private static Clip live() {
        return new Clip(new Object());
    }

    /** On, the lives in a Reels tab page go and the reels around them stay, in order, in a new list. */
    @Test
    public void livesInTheReelsTabAreTakenOut() {
        Clip first = reel();
        Clip second = reel();
        Clip third = reel();
        List<Object> items = Arrays.asList(first, live(), second, "a suggestion card", live(), third, null);

        List<?> kept = page(items, REELS_TAB);

        assertEquals(Arrays.asList(first, second, "a suggestion card", third, null), kept);
        assertNotSame(items, kept);
        assertEquals("the page handed in is left as it was", 7, items.size());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Lives in Reels: 1 lists, 7 items, 2 removed"));
        assertTrue(report, report.contains("live 2"));
        assertTrue(HookStatus.missing(FamilyNames.LIVE_REELS).toString(), HookStatus.missing(FamilyNames.LIVE_REELS).isEmpty());
    }

    /** Every lane of the Reels tab and every viewer Explore opens on a tile is filtered. */
    @Test
    public void theReelsTabAndExploreAreFiltered() {
        for (String module : PaidPartnershipReels.PICKED_BY_INSTAGRAM) {
            assertTrue(module, LiveReels.filtered(module));
        }
        for (String module : new String[] {
            "clips_viewer_explore_popular_major_unit", "clips_viewer_explore_popular_minor_unit",
            "clips_viewer_explore_popular_default_unit", "clips_viewer_explore_video_chaining",
            "clips_viewer_explore_tile_2x2", "clips_viewer_explore_trending_minor_unit",
        }) {
            assertTrue(module, LiveReels.filtered(module));
            Clip reel = reel();
            assertEquals(module, Collections.singletonList(reel), page(Arrays.asList(live(), reel), module));
        }
    }

    /** A viewer opened on something the user chose keeps its lives, and nothing in it is read. */
    @Test
    public void viewersTheUserOpenedStayAsTheyCame() {
        List<Clip> items = Arrays.asList(live(), reel());
        for (String module : new String[] {
            "clips_viewer_clips_profile", "clips_viewer_direct", "clips_viewer_feed_timeline",
            "clips_viewer_audio_page", "clips_viewer_", "explore_popular_major_unit", "", null,
        }) {
            assertFalse(String.valueOf(module), LiveReels.filtered(module));
            assertSame(String.valueOf(module), items, page(items, module));
        }
        assertEquals(0, reads.get());
        assertTrue(FeedFilterCounters.report().toString(), FeedFilterCounters.report().isEmpty());
    }

    /** A page of nothing but lives comes back whole, so the viewer has something to show, and that's counted. */
    @Test
    public void aPageOfOnlyLivesIsKeptWhole() {
        List<Clip> items = Arrays.asList(live(), live());

        assertSame(items, page(items, REELS_TAB));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("page kept whole 1"));
        assertTrue(report, report.contains("0 removed"));
    }

    /** A page with no live comes back as the same list. */
    @Test
    public void aPageWithoutLivesIsTheSameList() {
        List<Object> items = Arrays.asList(reel(), "a card", null, reel());
        assertSame(items, page(items, REELS_TAB));
    }

    /** Off, paused or before the settings are read, the page comes back as it came, read by nothing. */
    @Test
    public void offPausedAndUnreadyKeepEveryItem() {
        List<Clip> items = Arrays.asList(live(), reel());

        Settings.HIDE_LIVE_REELS.save(false);
        assertSame(items, page(items, REELS_TAB));
        Settings.HIDE_LIVE_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(items, page(items, REELS_TAB));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(items, page(items, REELS_TAB)));

        assertEquals("an item was read while the switch was off", 0, reads.get());
        assertEquals(Collections.singletonList(items.get(1)), page(items, REELS_TAB));
    }

    /** A reader or a switch that throws leaves the page as it came and says which hook threw. */
    @Test
    public void aThrowingReaderOrSwitchKeepsThePageAndIsReported() {
        List<Clip> items = Arrays.asList(live(), reel());

        assertSame(items, LiveReels.page(items, REELS_TAB, item -> {
            throw new IllegalStateException("broken item");
        }, LiveReels::switchedOn));
        assertSame(items, LiveReels.page(items, REELS_TAB, preview, () -> {
            throw new IllegalStateException("broken switch");
        }));

        String missing = HookStatus.missing(FamilyNames.LIVE_REELS).toString();
        assertTrue(missing, missing.contains("reels page"));
    }

    /** A null page stays null, and the unpatched stub reads every item as a reel, so nothing goes. */
    @Test
    public void nullAndUnpatchedPagesStay() {
        assertNull(LiveReels.page(null, REELS_TAB));
        assertNull(LiveReels.livePreview(live()));
        List<Clip> items = Arrays.asList(live(), reel());
        assertSame(items, LiveReels.page(items, REELS_TAB));
    }
}
