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
import java.util.Set;
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

/**
 * Hide reels by sound (#191): the saved list of sounds, and which reels a Reels tab page keeps by
 * them, with the page coming back as it came whenever the hook can't decide.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class HiddenSoundsTest {
    /** A reel's post and the id of its sound, which can be null. */
    static final class Post {
        final String sound;

        Post(String sound) {
            this.sound = sound;
        }
    }

    /** A Reels item carrying a post, which can be null. */
    static final class Clip {
        final Post post;

        Clip(Post post) {
            this.post = post;
        }
    }

    /** Reads a Clip the way the patch-filled stub and the sound bridges read Instagram's item, counting each read. */
    static class Reader implements HiddenSounds.Reader {
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public Object media(Object item) {
            reads.incrementAndGet();
            return item instanceof Clip ? ((Clip) item).post : null;
        }

        @Override
        public String soundId(Object media) {
            return ((Post) media).sound;
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
        Settings.HIDE_SOUND_REELS.save(true);
        Settings.HIDDEN_SOUNDS.save("111\tEspresso\tSabrina Carpenter\n222\tOriginal audio\t@nasa");
        HiddenSounds.resetForTests();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After
    public void restore() {
        Settings.HIDE_SOUND_REELS.resetToDefault();
        Settings.HIDDEN_SOUNDS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HiddenSounds.resetForTests();
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    private List<?> page(List<?> items) {
        return page(items, REELS_TAB);
    }

    private List<?> page(List<?> items, String module) {
        return HiddenSounds.page(items, module, reader, HiddenSounds::hiddenWhenOn);
    }

    private static Clip clip(String sound) {
        return new Clip(new Post(sound));
    }

    /** On, a reel whose sound is on the list goes and is counted, and the others stay in order. */
    @Test
    public void reelsUsingAHiddenSoundAreTakenOut() {
        Clip lake = clip("333");
        Clip espresso = clip("111");
        Clip nasa = clip("222");
        Clip silent = clip(null);
        Clip again = clip("111");
        List<Clip> items = Arrays.asList(lake, espresso, nasa, silent, again);

        List<?> kept = page(items);

        assertEquals(Arrays.asList(lake, silent), kept);
        assertNotSame(items, kept);
        assertEquals("the page handed in is left as it was", 5, items.size());
        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Hidden sounds: 1 lists, 5 items, 3 removed"));
        assertTrue(report, report.contains("hidden sound 3"));
        assertTrue(HookStatus.missing(FamilyNames.REEL_DOWNLOAD).toString(),
                HookStatus.missing(FamilyNames.REEL_DOWNLOAD).isEmpty());
    }

    /** No post, anything that isn't a reel and a null item all stay, and a page with no match comes back as it came. */
    @Test
    public void itemsWithoutASoundStay() {
        Clip noPost = new Clip(null);
        Object card = "a suggestion card";
        Clip hidden = clip("111");
        Clip plain = clip("333");
        List<Object> items = Arrays.asList(noPost, card, null, hidden, plain);

        assertEquals(Arrays.asList(noPost, card, null, plain), page(items));

        List<Object> nothingMatches = Arrays.asList(noPost, card, plain);
        assertSame(nothingMatches, page(nothingMatches));
    }

    /** A page where every reel would go comes back whole, so the viewer has a reel to show, and that's counted. */
    @Test
    public void aPageWhereEveryReelMatchesIsKeptWhole() {
        List<Clip> items = Arrays.asList(clip("111"), clip("222"));
        assertSame(items, page(items));
        List<Object> withCard = Arrays.asList("a suggestion card", clip("111"), new Clip(null));
        assertSame(withCard, page(withCard));

        String report = FeedFilterCounters.report().toString();
        assertTrue(report, report.contains("Hidden sounds: 2 lists, 5 items, 0 removed"));
        assertTrue(report, report.contains("page kept whole 2"));
    }

    /** Off, paused, with nothing hidden or before the settings are read, the page comes back as it came, read by nothing. */
    @Test
    public void offPausedEmptyAndUnreadyKeepEveryReel() {
        List<Clip> items = Arrays.asList(clip("111"), clip("333"));

        Settings.HIDE_SOUND_REELS.save(false);
        assertSame(items, page(items));
        Settings.HIDE_SOUND_REELS.save(true);

        Settings.HIDDEN_SOUNDS.save("");
        assertSame(items, page(items));
        Settings.HIDDEN_SOUNDS.save("111\tEspresso\tSabrina Carpenter");

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame(items, page(items));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame(items, page(items)));

        assertEquals("a reel was read while the switch was off", 0, reader.reads.get());
        assertEquals(Collections.singletonList(items.get(1)), page(items));
    }

    /** Every Reels tab lane is filtered. A viewer the user opened on something they chose, the sound's own page too, gets its page as it came. */
    @Test
    public void onlyTheReelsTabIsFiltered() {
        for (String lane : PaidPartnershipReels.PICKED_BY_INSTAGRAM) {
            Clip plain = clip("333");
            assertEquals(lane, Collections.singletonList(plain), page(Arrays.asList(plain, clip("111")), lane));
        }
        int reads = reader.reads.get();
        List<Clip> items = Arrays.asList(clip("111"), clip("333"));
        for (String module : new String[]{"clips_viewer_audio_page", "clips_viewer_clips_profile",
                "clips_viewer_serp_top_clips_unit", "clips_viewer_direct", "", null}) {
            assertSame(String.valueOf(module), items, page(items, module));
        }
        assertEquals("a reel the user went to was read", reads, reader.reads.get());
    }

    /** A reader or a list that throws leaves the page as it came and says which hook threw. */
    @Test
    public void aThrowingReaderOrListKeepsThePageAndIsReported() {
        List<Clip> items = Arrays.asList(clip("111"), clip("333"));
        HiddenSounds.Reader broken = new Reader() {
            @Override
            public String soundId(Object media) {
                throw new ClassCastException("Post cannot be cast to Media");
            }
        };

        assertSame(items, HiddenSounds.page(items, REELS_TAB, broken, () -> Collections.singleton("111")));
        assertSame(items, HiddenSounds.page(items, REELS_TAB, reader, () -> {
            throw new IllegalStateException("settings went away");
        }));

        String missing = HookStatus.missing(FamilyNames.REEL_DOWNLOAD).toString();
        assertTrue(missing, missing.contains("'hidden sounds page'"));
        assertTrue(missing, missing.contains(ClassCastException.class.getName()));
        assertEquals(0, reader.reads.get());
    }

    /** A null page stays null, an empty one comes back as it came, and the stubs as built keep every reel and read no sound. */
    @Test
    public void nullEmptyAndUnpatchedPagesStay() {
        assertNull(HiddenSounds.page(null, REELS_TAB));
        List<Clip> empty = Collections.emptyList();
        assertSame(empty, page(empty));

        List<Clip> items = Arrays.asList(clip("111"), clip("333"));
        assertSame(items, HiddenSounds.page(items, REELS_TAB));
        assertNull(HiddenSounds.media(items.get(0)));
        assertNull(HiddenSounds.soundOf(new Object(), "nasa"));
        assertNull(HiddenSounds.soundOf(null, "nasa"));
    }

    /** Adding keeps the order and each sound once, removing takes only that one out, and both hold paused. */
    @Test
    public void theListAddsAndRemovesBySoundId() {
        Settings.HIDDEN_SOUNDS.save("");
        assertTrue(HiddenSounds.saved().isEmpty());
        assertTrue(HiddenSounds.add(new HiddenSounds.Sound("111", "Espresso", "Sabrina Carpenter")));
        assertTrue(HiddenSounds.add(new HiddenSounds.Sound("222", "Original\taudio\n", "@nasa")));
        assertTrue("one already there isn't added twice", HiddenSounds.add(new HiddenSounds.Sound("111", "Other", "")));
        assertEquals("111\tEspresso\tSabrina Carpenter\n222\tOriginal audio\t@nasa", Settings.HIDDEN_SOUNDS.savedValue());
        assertTrue(HiddenSounds.hides("222"));
        assertFalse(HiddenSounds.hides("333"));
        assertFalse(HiddenSounds.hides(null));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        List<HiddenSounds.Sound> saved = HiddenSounds.saved();
        assertEquals("paused, the list still shows what you chose", 2, saved.size());
        assertEquals("Espresso", saved.get(0).title);
        assertEquals("@nasa", saved.get(1).artist);
        assertTrue(HiddenSounds.hiddenWhenOn().isEmpty());
        HiddenSounds.remove("111");
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        assertEquals("222\tOriginal audio\t@nasa", Settings.HIDDEN_SOUNDS.savedValue());
        assertEquals(Collections.singleton("222"), HiddenSounds.hiddenWhenOn());
        HiddenSounds.remove("222");
        assertEquals("", Settings.HIDDEN_SOUNDS.savedValue());
    }

    /** A sound without a usable id is never added, and lines that aren't a sound are skipped when read. */
    @Test
    public void onlyDigitIdsAreKept() {
        Settings.HIDDEN_SOUNDS.save("");
        assertFalse(HiddenSounds.add(null));
        assertFalse(HiddenSounds.add(new HiddenSounds.Sound("abc", "x", "y")));
        assertFalse(HiddenSounds.add(new HiddenSounds.Sound("", "x", "y")));
        assertFalse(HiddenSounds.add(new HiddenSounds.Sound("1234567890123456789012345678901", "x", "y")));
        assertEquals("", Settings.HIDDEN_SOUNDS.savedValue());

        assertEquals("42", HiddenSounds.id(" 42 "));
        assertNull(HiddenSounds.id("4 2"));
        assertNull(HiddenSounds.id(null));
        Set<String> ids = HiddenSounds.sounds("junk\n\n 7 \t\tsomeone\n7\tdupe\t\nx\ty").keySet();
        assertEquals(Collections.singleton("7"), ids);
        assertEquals("", HiddenSounds.sounds("7").get("7").title);

        String longTitle = String.join("", Collections.nCopies(30, "abcde"));
        assertEquals(HiddenSounds.MAX_NAME, HiddenSounds.name(longTitle).length());
        assertEquals("", HiddenSounds.name(null));
    }

    /** A changed list is read again on the next page, and the same list isn't. */
    @Test
    public void aChangedListIsReadOnTheNextPage() {
        Clip one = clip("111");
        Clip three = clip("333");
        List<Clip> items = Arrays.asList(one, three);

        assertEquals(Collections.singletonList(three), page(items));
        assertSame(HiddenSounds.hiddenWhenOn(), HiddenSounds.hiddenWhenOn());

        Settings.HIDDEN_SOUNDS.save("333\t\t");
        assertEquals(Collections.singletonList(one), page(items));
    }
}
