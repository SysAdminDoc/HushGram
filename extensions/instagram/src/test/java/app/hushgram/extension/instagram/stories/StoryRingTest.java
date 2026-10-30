/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * What Story ring size makes of the size Instagram settles on for the stories row, and of the
 * colors and stops a ring builds its gradient from.
 */
@RunWith(RobolectricTestRunner.class)
public class StoryRingTest {
    /** Instagram's own story gradient as a ring's builder gets it: yellow, orange, pink and lavender. */
    private static final int[] STORY = {0xFFFFD600, 0xFFFF7A00, 0xFFFF0069, 0xFFD300C5};
    /** Its four stops, which Instagram keeps as doubles and hands over as floats. */
    private static final float[] STORY_STOPS = {(float) 0.1435, (float) 0.3779, (float) 0.6071, (float) 0.831};
    /** One color from end to end, the way Close Friends, subscribers and a Live's pink are drawn. */
    private static final float[] FLAT_STOPS = {0f, 1f};
    private static final int[] CLOSE_FRIENDS = {0xFF1CD14F, 0xFF1CD14F};
    private static final int[] LIVE_PINK = {0xFFFF0069, 0xFFFF0069};

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.STORY_RING.resetToDefault();
        Settings.STORY_RING_SCALE.resetToDefault();
        Settings.STORY_RING_COLOR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Picking the patch turns its switch on, and the size starts as Instagram's, so the row is unchanged. */
    @Test
    public void picked_theRowKeepsInstagramsSize() {
        assertTrue(Settings.STORY_RING.get());
        assertEquals(StoryRingSize.INSTAGRAM, Settings.STORY_RING_SCALE.get());
        assertEquals(270f, StoryRing.size(270f), 0f);
    }

    @Test
    public void aChosenSizeScalesTheRow() {
        Settings.STORY_RING_SCALE.save(StoryRingSize.LARGEST);
        assertEquals(351f, StoryRing.size(270f), 0.01f);
        Settings.STORY_RING_SCALE.save(StoryRingSize.SMALLEST);
        assertEquals(189f, StoryRing.size(270f), 0.01f);
    }

    @Test
    public void withTheSwitchOffTheRowKeepsInstagramsSize() {
        Settings.STORY_RING_SCALE.save(StoryRingSize.LARGER);
        Settings.STORY_RING.save(false);
        assertEquals(270f, StoryRing.size(270f), 0f);
    }

    /** A size that isn't a size passes through as it came. */
    @Test
    public void noSizeIsLeftAsItIs() {
        Settings.STORY_RING_SCALE.save(StoryRingSize.LARGEST);
        assertEquals(0f, StoryRing.size(0f), 0f);
        assertEquals(-4f, StoryRing.size(-4f), 0f);
        assertTrue(Float.isNaN(StoryRing.size(Float.NaN)));
    }

    /**
     * The shares the list offers, smallest first, from 50% to 150% with Instagram's in the middle
     * (#223). The sizes offered before keep their names and shares, so a saved pick draws the same.
     */
    @Test
    public void theSizesRunFromSmallestToLargest() {
        StoryRingSize[] sizes = StoryRingSize.values();
        for (int i = 1; i < sizes.length; i++) assertTrue(sizes[i - 1].scale < sizes[i].scale);
        assertEquals(StoryRingSize.INSTAGRAM, sizes[sizes.length / 2]);
        assertEquals(50, sizes[0].percent());
        assertEquals(150, sizes[sizes.length - 1].percent());
        assertEquals(70, StoryRingSize.valueOf("SMALLEST").percent());
        assertEquals(85, StoryRingSize.valueOf("SMALLER").percent());
        assertEquals(115, StoryRingSize.valueOf("LARGER").percent());
        assertEquals(130, StoryRingSize.valueOf("LARGEST").percent());
    }

    /** The color starts as Instagram's, so the builder gets back the very array it passed. */
    @Test
    public void picked_theRingKeepsInstagramsColors() {
        assertEquals(StoryRingColor.INSTAGRAM, Settings.STORY_RING_COLOR.get());
        int[] story = STORY.clone();
        assertSame(story, StoryRing.colors(story, STORY_STOPS));
        assertArrayEquals(STORY, story);
        assertNull(StoryRingColor.INSTAGRAM.colors(4));
        assertTrue(HookStatus.missing(FamilyNames.STORY_RING).toString(), HookStatus.missing(FamilyNames.STORY_RING).isEmpty());
    }

    /** Each color's two shades, as they are written down for the settings screen's list. */
    @Test
    public void eachColorHasItsTwoShades() {
        assertShades(StoryRingColor.BLUE, 0xFF2F8CFF, 0xFF3D5AFE);
        assertShades(StoryRingColor.TEAL, 0xFF009E9E, 0xFF00897B);
        assertShades(StoryRingColor.PURPLE, 0xFF9B5CFF, 0xFFB14BE0);
        assertShades(StoryRingColor.RED, 0xFFFF3B30, 0xFFE0010D);
        assertShades(StoryRingColor.ORANGE, 0xFFF26B0F, 0xFFE0461F);
        assertShades(StoryRingColor.GOLD, 0xFFC58500, 0xFFB86E00);
        assertEquals("a color with no shades listed here", 7, StoryRingColor.values().length);
    }

    /**
     * A chosen color takes the place of Instagram's story gradient: as many colors as Instagram
     * gave, from the first shade to the second, every one opaque. The array Instagram keeps isn't
     * touched, and each ring gets an array of its own.
     */
    @Test
    public void aChosenColorPaintsTheStoryGradient() {
        for (StoryRingColor color : StoryRingColor.values()) {
            if (color == StoryRingColor.INSTAGRAM) continue;
            Settings.STORY_RING_COLOR.save(color);
            int[] story = STORY.clone();
            int[] painted = StoryRing.colors(story, STORY_STOPS);
            assertNotSame(color.name(), story, painted);
            assertArrayEquals(color.name() + " changed Instagram's own array", STORY, story);
            assertEquals(color.name(), STORY.length, painted.length);
            assertEquals(color.name(), color.from, painted[0]);
            assertEquals(color.name(), color.to, painted[painted.length - 1]);
            for (int at = 0; at < painted.length; at++) {
                assertEquals(color.name() + " at " + at + " isn't opaque", 0xFF, painted[at] >>> 24);
                for (int shift = 0; shift <= 16; shift += 8) {
                    int low = Math.min(color.from >> shift & 0xFF, color.to >> shift & 0xFF);
                    int high = Math.max(color.from >> shift & 0xFF, color.to >> shift & 0xFF);
                    int channel = painted[at] >> shift & 0xFF;
                    assertTrue(color.name() + " at " + at + " leaves its two shades", channel >= low && channel <= high);
                }
            }
            int[] again = StoryRing.colors(story, STORY_STOPS);
            assertNotSame(color.name() + " handed two rings one array", painted, again);
            assertArrayEquals(color.name(), painted, again);
        }
        assertTrue(HookStatus.missing(FamilyNames.STORY_RING).toString(), HookStatus.missing(FamilyNames.STORY_RING).isEmpty());
    }

    /**
     * Instagram draws the same ring on a white page, on a black one and over a Reel, so every shade
     * a ring can show stands out on both white and black.
     */
    @Test
    public void everyShadeStandsOutOnWhiteAndOnBlack() {
        for (StoryRingColor color : StoryRingColor.values()) {
            if (color == StoryRingColor.INSTAGRAM) continue;
            for (int shade : color.colors(STORY.length)) {
                String what = color.name() + " #" + Integer.toHexString(shade);
                assertTrue(what + " on white is " + contrast(shade, 0xFFFFFFFF),
                        contrast(shade, 0xFFFFFFFF) >= StoryRingColor.LEAST_CONTRAST);
                assertTrue(what + " on black is " + contrast(shade, 0xFF000000),
                        contrast(shade, 0xFF000000) >= StoryRingColor.LEAST_CONTRAST);
            }
        }
    }

    /** Close Friends keeps green to itself and a Live keeps pink, so no color on the list is either. */
    @Test
    public void noColorIsCloseFriendsGreenOrALivesPink() {
        assertTrue("the green band misses Close Friends", isGreen(CLOSE_FRIENDS[0]));
        assertTrue("the pink band misses a Live", isPink(LIVE_PINK[0]));
        for (StoryRingColor color : StoryRingColor.values()) {
            if (color == StoryRingColor.INSTAGRAM) continue;
            for (int shade : color.colors(STORY.length)) {
                String what = color.name() + " #" + Integer.toHexString(shade) + " at " + hue(shade) + " degrees";
                assertFalse(what + " is green", isGreen(shade));
                assertFalse(what + " is pink", isPink(shade));
            }
        }
    }

    /** Off, paused or before the settings are read, the builder gets back the array it passed. */
    @Test
    public void offPausedAndUnreadyKeepInstagramsColors() {
        Settings.STORY_RING_COLOR.save(StoryRingColor.BLUE);
        int[] story = STORY.clone();
        assertNotSame("on", story, StoryRing.colors(story, STORY_STOPS));

        Settings.STORY_RING.save(false);
        assertSame("off", story, StoryRing.colors(story, STORY_STOPS));
        Settings.STORY_RING.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", story, StoryRing.colors(story, STORY_STOPS));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertSame("no context", story, StoryRing.colors(story, STORY_STOPS)));
        SettingsContextRule.beforeThePauseIsDecided(
                () -> assertSame("before the pause is decided", story, StoryRing.colors(story, STORY_STOPS)));

        assertNotSame("on again", story, StoryRing.colors(story, STORY_STOPS));
        assertArrayEquals(STORY, story);
        assertEquals("the saved color outlives the pause", StoryRingColor.BLUE, Settings.STORY_RING_COLOR.savedValue());
    }

    /**
     * Every ring that isn't a story's own gradient keeps its colors: Close Friends' green, a
     * Live's pink, the rainbow's seven, a ring built with no stops at all, and a ring with the
     * story's stops but one color.
     */
    @Test
    public void everyOtherRingKeepsItsColors() {
        Settings.STORY_RING_COLOR.save(StoryRingColor.BLUE);
        assertKept("Close Friends", CLOSE_FRIENDS.clone(), FLAT_STOPS);
        assertKept("a Live's pink", LIVE_PINK.clone(), FLAT_STOPS);
        assertKept("the rainbow", new int[]{1, 2, 3, 4, 5, 6, 7}, new float[]{0f, 0.16f, 0.33f, 0.5f, 0.66f, 0.83f, 1f});
        assertKept("no stops", STORY.clone(), null);
        assertKept("four colors at other stops", STORY.clone(), new float[]{0f, 0.33f, 0.66f, 1f});
        assertKept("one stop a little off", STORY.clone(), new float[]{0.1435f, 0.3779f, 0.6071f, 0.84f});
        assertKept("one color at the story's stops", new int[]{0xFF1CD14F, 0xFF1CD14F, 0xFF1CD14F, 0xFF1CD14F}, STORY_STOPS);
        assertKept("more colors than stops", new int[]{1, 2, 3, 4, 5}, STORY_STOPS);
        assertKept("a stop that isn't a number", STORY.clone(), new float[]{Float.NaN, 0.3779f, 0.6071f, 0.831f});
        assertNull("no colors", StoryRing.colors(null, STORY_STOPS));
        assertTrue(HookStatus.missing(FamilyNames.STORY_RING).toString(), HookStatus.missing(FamilyNames.STORY_RING).isEmpty());
    }

    /**
     * The stories row reuses its views. One ring bound to an unwatched story, then a watched one,
     * then Close Friends, then an unwatched story again shows the right ring each time: the chosen
     * color only around the unwatched stories, grey around the watched one, green around Close
     * Friends. Back on Instagram's own choice, the same ring shows Instagram's gradient again.
     */
    @Test
    public void aReusedRingShowsTheRightColorsEachTimeItIsBound() {
        Settings.STORY_RING_COLOR.save(StoryRingColor.PURPLE);
        int[] purple = StoryRingColor.PURPLE.colors(STORY.length);
        Ring ring = new Ring();

        ring.bind(STORY.clone(), STORY_STOPS, false);
        assertArrayEquals("unwatched", purple, ring.drawn());

        ring.bind(STORY.clone(), STORY_STOPS, true);
        assertArrayEquals("watched", new int[]{Ring.WATCHED}, ring.drawn());

        ring.bind(CLOSE_FRIENDS.clone(), FLAT_STOPS, false);
        assertArrayEquals("Close Friends", CLOSE_FRIENDS, ring.drawn());

        ring.bind(CLOSE_FRIENDS.clone(), FLAT_STOPS, true);
        assertArrayEquals("Close Friends, watched", new int[]{Ring.WATCHED}, ring.drawn());

        ring.bind(STORY.clone(), STORY_STOPS, false);
        assertArrayEquals("unwatched again", purple, ring.drawn());
        assertArrayEquals("the ring's own colors are still Instagram's", STORY, ring.given);

        Settings.STORY_RING_COLOR.save(StoryRingColor.INSTAGRAM);
        ring.bind(ring.given, STORY_STOPS, false);
        assertSame("Instagram's own choice", ring.given, ring.drawn());
        assertArrayEquals(STORY, ring.drawn());
    }

    /** The size and the color are two choices: either works with the other on Instagram's own. */
    @Test
    public void theSizeAndTheColorAreChosenApart() {
        int[] story = STORY.clone();
        Settings.STORY_RING_SCALE.save(StoryRingSize.LARGEST);
        Settings.STORY_RING_COLOR.save(StoryRingColor.GOLD);
        assertEquals(351f, StoryRing.size(270f), 0.01f);
        assertArrayEquals(StoryRingColor.GOLD.colors(STORY.length), StoryRing.colors(story, STORY_STOPS));

        Settings.STORY_RING_COLOR.save(StoryRingColor.INSTAGRAM);
        assertEquals("the size without a color", 351f, StoryRing.size(270f), 0.01f);
        assertSame("the size without a color", story, StoryRing.colors(story, STORY_STOPS));

        Settings.STORY_RING_SCALE.save(StoryRingSize.INSTAGRAM);
        Settings.STORY_RING_COLOR.save(StoryRingColor.GOLD);
        assertEquals("a color without a size", 270f, StoryRing.size(270f), 0f);
        assertArrayEquals("a color without a size", StoryRingColor.GOLD.colors(STORY.length), StoryRing.colors(story, STORY_STOPS));
    }

    /** A gradient needs two colors, and Instagram's own choice has none to give. */
    @Test
    public void aColorGivesAsManyShadesAsItIsAskedFor() {
        assertArrayEquals(new int[]{StoryRingColor.RED.from, StoryRingColor.RED.to}, StoryRingColor.RED.colors(2));
        assertEquals(7, StoryRingColor.RED.colors(7).length);
        assertNull(StoryRingColor.RED.colors(1));
        assertNull(StoryRingColor.RED.colors(0));
        assertNull(StoryRingColor.INSTAGRAM.colors(2));
    }

    private static void assertShades(StoryRingColor color, int from, int to) {
        assertEquals(color.name(), from, color.from);
        assertEquals(color.name(), to, color.to);
    }

    private static void assertKept(String what, int[] colors, float[] stops) {
        int[] before = colors.clone();
        assertSame(what, colors, StoryRing.colors(colors, stops));
        assertArrayEquals(what, before, colors);
    }

    private static double contrast(int one, int other) {
        double lighter = Math.max(luminance(one), luminance(other));
        double darker = Math.min(luminance(one), luminance(other));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(color >> 16 & 0xFF) + 0.7152 * linear(color >> 8 & 0xFF) + 0.0722 * linear(color & 0xFF);
    }

    private static double linear(int channel) {
        double share = channel / 255.0;
        return share <= 0.04045 ? share / 12.92 : Math.pow((share + 0.055) / 1.055, 2.4);
    }

    /** Where [color] sits on the color wheel, in degrees from red. */
    private static double hue(int color) {
        double red = (color >> 16 & 0xFF) / 255.0;
        double green = (color >> 8 & 0xFF) / 255.0;
        double blue = (color & 0xFF) / 255.0;
        double most = Math.max(red, Math.max(green, blue));
        double spread = most - Math.min(red, Math.min(green, blue));
        if (spread == 0) return 0;
        double turn;
        if (most == red) turn = (green - blue) / spread;
        else if (most == green) turn = 2 + (blue - red) / spread;
        else turn = 4 + (red - green) / spread;
        return (turn * 60 + 360) % 360;
    }

    private static boolean isGreen(int color) {
        return hue(color) >= 75 && hue(color) <= 165;
    }

    private static boolean isPink(int color) {
        return hue(color) >= 320 && hue(color) <= 350;
    }

    /**
     * A ring the way Instagram's view works: it keeps the colors it is given, builds its gradient
     * from them through the hook when the story hasn't been watched, and draws a watched story
     * with a paint of its own that the hook never sees.
     */
    private static final class Ring {
        static final int WATCHED = 0xFFDBDBDB;

        int[] given;
        private int[] gradient;
        private boolean watched;

        void bind(int[] colors, float[] stops, boolean watched) {
            given = colors;
            this.watched = watched;
            if (!watched) gradient = StoryRing.colors(colors, stops);
        }

        int[] drawn() {
            return watched ? new int[]{WATCHED} : gradient;
        }
    }
}
