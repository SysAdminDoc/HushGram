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

/** Which reels a page keeps by their AI label, and that the page comes back as it came whenever the hook can't decide. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class AiLabeledReelsTest {
    /** A post's ai_label_info: should_read_attribution_label and attribution_label_text, as Instagram parsed them. */
    static final class Label {
        final Boolean reads;
        final String text;

        Label(Boolean reads, String text) {
            this.reads = reads;
            this.text = text;
        }
    }

    /** A reel's post, with its label info, which can be null. */
    static final class Post {
        final Label label;

        Post(Label label) {
            this.label = label;
        }
    }

    /** A Reels item carrying a post, which can be null. */
    static final class Clip {
        final Post post;

        Clip(Post post) {
            this.post = post;
        }
    }

    /** Reads a Clip the way the patch-filled stubs read Instagram's item, counting each read. */
    static class Reader implements AiLabeledReels.Reader {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Object media(Object item) {
            reads.incrementAndGet();
            return item instanceof Clip ? ((Clip) item).post : null;
        }

        @Override
        public Object labelInfo(Object media) {
            return ((Post) media).label;
        }

        @Override
        public Boolean readsLabel(Object info) {
            return ((Label) info).reads;
        }

        @Override
        public String labelText(Object info) {
            return ((Label) info).text;
        }
    }

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Reader reader = new Reader();

    /** The For you lane's module name, as the Reels tab's viewer hands it over. */
    private static final String REELS_TAB = "clips_viewer_clips_tab";

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_AI_LABELED_REELS.save(true);
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_AI_LABELED_REELS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private List<?> page(List<?> items) {
        return page(items, REELS_TAB);
    }

    private List<?> page(List<?> items, String module) {
        return AiLabeledReels.page(items, module, reader, AiLabeledReels::switchedOn);
    }

    private static Clip clip(Boolean reads, String text) {
        return new Clip(new Post(new Label(reads, text)));
    }

    /** On, a reel whose label says to show itself and has text goes, and is counted. */
    @Test
    public void reelsWithInstagramsAiLabelAreTakenOut() {
        Clip plain = new Clip(new Post(null));
        Clip labeled = clip(true, "AI info");
        Clip other = clip(true, "Info IA");
        Clip last = clip(false, "AI info");
        List<Clip> items = Arrays.asList(plain, labeled, other, last);

        List<?> kept = page(items);

        assertEquals(Arrays.asList(plain, last), kept);
        assertNotSame(items, kept);
        assertEquals("the page handed in is left as it was", 4, items.size());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("AI-labeled reels: 1 lists, 4 items, 2 removed"));
        assertTrue(report, report.contains("AI label 2"));
        assertTrue(HookStatus.missing(FamilyNames.AI_LABELED_REELS).toString(),
                HookStatus.missing(FamilyNames.AI_LABELED_REELS).isEmpty());
    }

    /**
     * Only the flag and the text together count. The flag alone, the text alone, a label object
     * with neither, no label, no post and anything that isn't a reel all stay.
     */
    @Test
    public void unknownOrPartialLabelsStay() {
        Clip flagOnly = clip(true, null);
        Clip emptyText = clip(true, "");
        Clip textOnly = clip(null, "AI info");
        Clip notRead = clip(false, "AI info");
        Clip bare = clip(null, null);
        Clip noLabel = new Clip(new Post(null));
        Clip noPost = new Clip(null);
        Object card = "a suggestion card";
        Clip labeled = clip(true, "AI info");
        List<Object> items = Arrays.asList(flagOnly, emptyText, textOnly, notRead, bare, noLabel, noPost, card, null, labeled);

        assertEquals(Arrays.asList(flagOnly, emptyText, textOnly, notRead, bare, noLabel, noPost, card, null), page(items));

        List<Object> nothingMatches = Arrays.asList(flagOnly, emptyText, textOnly, notRead, bare, noLabel, noPost, card);
        assertSame(nothingMatches, page(nothingMatches));
        assertFalse(AiLabeledReels.labeled(flagOnly.post, reader));
        assertTrue(AiLabeledReels.labeled(labeled.post, reader));
    }

    /** A page where every reel would go comes back whole, so the viewer has a reel to show, and that's counted. */
    @Test
    public void aPageWhereEveryReelMatchesIsKeptWhole() {
        List<Clip> items = Arrays.asList(clip(true, "AI info"), clip(true, "KI-Info"));
        assertSame(items, page(items));
        List<Object> withCard = Arrays.asList("a suggestion card", clip(true, "AI info"), new Clip(null));
        assertSame(withCard, page(withCard));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("AI-labeled reels: 2 lists, 5 items, 0 removed"));
        assertTrue(report, report.contains("page kept whole 2"));
    }

    /** Off, paused or before the settings are read, the page comes back as it came, read by nothing. */
    @Test
    public void offPausedAndUnreadyKeepEveryReel() {
        List<Clip> items = Arrays.asList(clip(true, "AI info"), clip(false, null));

        Settings.HIDE_AI_LABELED_REELS.save(false);
        assertSame(items, page(items));
        Settings.HIDE_AI_LABELED_REELS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(items, page(items));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(items, page(items)));

        assertEquals("a reel was read while the switch was off", 0, reader.reads.get());
        assertEquals(Collections.singletonList(items.get(1)), page(items));
    }

    /** A reader or a switch that throws leaves the page as it came and says which hook threw. */
    @Test
    public void aThrowingReaderOrSwitchKeepsThePageAndIsReported() {
        List<Clip> items = Arrays.asList(clip(true, "AI info"), clip(false, null));
        AiLabeledReels.Reader broken = new Reader() {
            @Override
            public Boolean readsLabel(Object info) {
                throw new ClassCastException("Label cannot be cast to XDTAILabelInfo");
            }
        };

        assertSame(items, AiLabeledReels.page(items, REELS_TAB, broken, () -> true));
        assertSame(items, AiLabeledReels.page(items, REELS_TAB, reader, () -> {
            throw new IllegalStateException("settings went away");
        }));

        String missing = HookStatus.missing(FamilyNames.AI_LABELED_REELS).toString();
        assertTrue(missing, missing.contains("'reels page'"));
        assertTrue(missing, missing.contains(ClassCastException.class.getName()));
        assertEquals(0, reader.reads.get());
    }

    /** Every Reels tab lane is filtered. A viewer the user opened on something they chose gets its page as it came. */
    @Test
    public void onlyTheReelsTabIsFiltered() {
        for (String lane : PaidPartnershipReels.PICKED_BY_INSTAGRAM) {
            Clip plain = clip(false, null);
            assertEquals(lane, Collections.singletonList(plain), page(Arrays.asList(plain, clip(true, "AI info")), lane));
        }
        int reads = reader.reads.get();
        List<Clip> items = Arrays.asList(clip(true, "AI info"), clip(false, null));
        for (String module : new String[]{"clips_viewer_serp_top_clips_unit", "clips_viewer_clips_profile",
                "clips_viewer_audio_page", "clips_viewer_feed_saved_collections_clips", "clips_viewer_direct", "", null}) {
            assertSame(String.valueOf(module), items, page(items, module));
        }
        assertEquals("a reel the user went to was read", reads, reader.reads.get());
    }

    /** A null page stays null, an empty one comes back as it came, and the stubs as built keep every reel. */
    @Test
    public void nullEmptyAndUnpatchedPagesStay() {
        assertNull(AiLabeledReels.page(null, REELS_TAB));
        List<Clip> empty = Collections.emptyList();
        assertSame(empty, page(empty));

        List<Clip> items = Arrays.asList(clip(true, "AI info"), clip(false, null));
        assertSame(items, AiLabeledReels.page(items, REELS_TAB));
        assertNull(AiLabeledReels.media(items.get(0)));
        assertNull(AiLabeledReels.labelInfo(items.get(0).post));
        assertNull(AiLabeledReels.readsLabel(items.get(0).post.label));
        assertNull(AiLabeledReels.labelText(items.get(0).post.label));
    }
}
