/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.instagram.stories.StoryRingColor;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Story rings in the accent color on the rings the theme doesn't reach (#528): the shades the ring
 * style gives the default ring's attributes are kept, and Instagram's own story gradient takes them
 * in the ring view and on Compose screens.
 */
@RunWith(RobolectricTestRunner.class)
public class AccentRingsTest {
    /** A package of its own, so the resource numbers looked up for the app in another test aren't reused. */
    private static final String THEMED = "app.hushgram.test.accentrings";
    /** Instagram's own story gradient as the ring view's builder gets it: yellow, orange, pink and lavender. */
    private static final int[] STORY = {0xFFFFD600, 0xFFFF7A00, 0xFFFF0069, 0xFFD300C5};
    private static final float[] STORY_STOPS = {(float) 0.1435, (float) 0.3779, (float) 0.6071, (float) 0.831};
    private static final float[] FLAT_STOPS = {0f, 1f};
    private static final int[] CLOSE_FRIENDS = {0xFF1CD14F, 0xFF1CD14F};
    /**
     * The grays 457's themes hand a seen story's ring: Prism's dark and light seen and stroke grays, and
     * the older theme's dark and light ones.
     */
    private static final int[] SEEN_GRAYS = {0xFF2B3036, 0xFFDCE0E5, 0xFFDBDFE4, 0xFFE7EAEE, 0xFFE9EDF0, 0xFF363636, 0xFFC7C7C7, 0xFFDBDBDB};
    /** What a ring style might give the default ring's four attributes, light to deep. */
    private static final int[] SHADES = {0xFFE1BEE7, 0xFFBA68C8, 0xFF8E24AA, 0xFF4A148C};
    /** Android's own color attributes, standing in for the default ring's four in a test theme. */
    private static final int[] STAND_INS = {
            android.R.attr.colorPrimary, android.R.attr.colorPrimaryDark, android.R.attr.colorAccent, android.R.attr.colorBackground,
    };

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_RINGS.resetToDefault();
        Settings.ACCENT_RINGS_SOLID.resetToDefault();
        Settings.STORY_RING.resetToDefault();
        Settings.STORY_RING_COLOR.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        AccentRings.keptForTests(null);
    }

    @After
    public void restore() {
        prepare();
    }

    /** Off by default: with shades kept, every ring keeps Instagram's colors, and gets back the array it passed. */
    @Test
    public void offTheRingsKeepInstagramsColors() {
        AccentRings.keptForTests(SHADES);
        int[] story = STORY.clone();
        assertSame(story, AccentRings.colors(story, STORY_STOPS));
        assertSame(story, AccentRings.composeColors(story));
        assertArrayEquals(STORY, story);
    }

    /**
     * On, Instagram's story gradient takes the kept shades, each ring an array of its own, and the
     * array Instagram keeps isn't touched. Each is counted.
     */
    @Test
    public void theStoryGradientTakesTheKeptShades() {
        accentRingsOn();
        int[] story = STORY.clone();
        int[] painted = AccentRings.colors(story, STORY_STOPS);
        assertArrayEquals(SHADES, painted);
        assertArrayEquals("changed Instagram's own array", STORY, story);
        int[] again = AccentRings.colors(story, STORY_STOPS);
        assertNotSame("handed two rings one array", painted, again);
        assertArrayEquals(SHADES, again);
        painted[0] = 0;
        assertArrayEquals("a ring changed the kept shades", SHADES, AccentRings.colors(story, STORY_STOPS));
        assertTrue(HookStatus.report().toString(), HookStatus.report().get(0).contains(AccentRings.GIVEN + " 3"));
    }

    /** On Compose screens the default ring's four colors take the shades too; anything else is left. */
    @Test
    public void theComposeRingTakesTheKeptShades() {
        accentRingsOn();
        int[] story = STORY.clone();
        assertArrayEquals(SHADES, AccentRings.composeColors(story));
        assertArrayEquals(STORY, story);
        int[] two = {0xFF000001, 0xFF000002};
        assertSame("two colors", two, AccentRings.composeColors(two));
        assertNull(AccentRings.composeColors(null));
    }

    /** Close Friends' green, the rainbow, a ring of one color and a ring with no stops keep their colors. */
    @Test
    public void everyOtherRingKeepsItsColors() {
        accentRingsOn();
        assertKept("Close Friends", CLOSE_FRIENDS.clone(), FLAT_STOPS);
        assertKept("the rainbow", new int[]{1, 2, 3, 4, 5, 6, 7}, new float[]{0f, 0.16f, 0.33f, 0.5f, 0.66f, 0.83f, 1f});
        assertKept("one color at the story's stops", new int[]{0xFF1CD14F, 0xFF1CD14F, 0xFF1CD14F, 0xFF1CD14F}, STORY_STOPS);
        assertKept("no stops", STORY.clone(), null);
        assertNull("no colors", AccentRings.colors(null, STORY_STOPS));
    }

    /**
     * Story rings in the accent color off, Accent color off, Instagram's blue, paused, before the
     * settings are read, before a theme has had the ring style, or with shades for another ring:
     * the story gradient keeps Instagram's colors.
     */
    @Test
    public void offPausedAndUnreadyKeepInstagramsColors() {
        accentRingsOn();
        int[] story = STORY.clone();
        assertNotSame("on", story, AccentRings.colors(story, STORY_STOPS));

        Settings.ACCENT_RINGS.save(false);
        assertBoth("the rings off", story);
        Settings.ACCENT_RINGS.save(true);

        Settings.ACCENT_COLOR.save(false);
        assertBoth("Accent color off", story);
        Settings.ACCENT_COLOR.save(true);

        Settings.ACCENT_HUE.save(AccentHue.BLUE);
        assertBoth("Instagram's blue", story);
        Settings.ACCENT_HUE.save(AccentHue.PURPLE);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertBoth("paused", story);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertBoth("no context", story));

        AccentRings.keptForTests(null);
        assertBoth("no theme had the ring style yet", story);
        AccentRings.keptForTests(new int[]{1, 2, 3});
        assertBoth("shades for another ring", story);

        AccentRings.keptForTests(SHADES);
        assertNotSame("on again", story, AccentRings.colors(story, STORY_STOPS));
        assertArrayEquals(STORY, story);
    }

    /** A color picked under Ring color wins: those rings are left to Story ring size. */
    @Test
    @Config(shadows = StoryRingBuilt.class)
    public void aPickedRingColorWins() {
        accentRingsOn();
        Settings.STORY_RING_COLOR.save(StoryRingColor.TEAL);
        assertBoth("Ring color picked", STORY.clone());

        Settings.STORY_RING_COLOR.save(StoryRingColor.INSTAGRAM);
        assertArrayEquals("Instagram's own ring color", SHADES, AccentRings.colors(STORY.clone(), STORY_STOPS));

        Settings.STORY_RING_COLOR.save(StoryRingColor.TEAL);
        Settings.STORY_RING.save(false);
        assertArrayEquals("Story ring size off", SHADES, AccentRings.composeColors(STORY.clone()));
    }

    /** A Ring color saved in a build without Story ring size draws nothing, so the accent takes the rings. */
    @Test
    public void aRingColorFromABuildWithoutStoryRingSizeLeavesTheAccent() {
        accentRingsOn();
        Settings.STORY_RING_COLOR.save(StoryRingColor.TEAL);
        assertArrayEquals(SHADES, AccentRings.colors(STORY.clone(), STORY_STOPS));
        assertArrayEquals(SHADES, AccentRings.composeColors(STORY.clone()));
    }

    /**
     * A seen story's ring is drawn in one gray Instagram hands the ring view, kept as a 1x1 shader so
     * its alpha holds. Each of Instagram's seen grays, dark and light, Prism's and the older theme's,
     * takes the picked color's fill at 30 percent, which sits on a screen about as Instagram's gray does
     * (#187). Each is counted. No theme has to have had the ring style first.
     */
    @Test
    public void aSeenRingsGrayTakesTheAccent() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.PURPLE);
        Settings.ACCENT_RINGS.save(true);
        int seen = AccentRings.SEEN_ALPHA << 24 | AccentHue.PURPLE.fill & 0xffffff;
        for (int gray : SEEN_GRAYS) assertEquals(Integer.toHexString(gray), seen, AccentRings.seen(gray));
        assertTrue(HookStatus.report().toString(), HookStatus.report().get(0).contains(AccentRings.SEEN + " " + SEEN_GRAYS.length));

        Settings.ACCENT_HUE.save(AccentHue.DUSK);
        assertEquals("a color set's fill", AccentRings.SEEN_ALPHA << 24 | AccentHue.DUSK.fill & 0xffffff, AccentRings.seen(SEEN_GRAYS[0]));
    }

    /** A see-through, black, white or colored inactive ring isn't a seen story's gray and keeps its color. */
    @Test
    public void otherInactiveRingsKeepTheirColors() {
        accentRingsOn();
        for (int color : new int[]{0x00000000, 0x802b3036, 0xff000000, 0xff121212, 0xffffffff, 0xfffafafa, 0xff1cd14f, 0xffff0069}) {
            assertEquals(Integer.toHexString(color), color, AccentRings.seen(color));
        }
    }

    /**
     * Story rings in the accent color off, Accent color off, Instagram's blue, paused, before the
     * settings are read, or a picked Ring color: a seen ring keeps Instagram's gray.
     */
    @Test
    @Config(shadows = StoryRingBuilt.class)
    public void offPausedAndPickedKeepTheSeenGray() {
        accentRingsOn();
        int gray = SEEN_GRAYS[0];
        assertTrue("on", AccentRings.seen(gray) != gray);

        Settings.ACCENT_RINGS.save(false);
        assertEquals("the rings off", gray, AccentRings.seen(gray));
        Settings.ACCENT_RINGS.save(true);

        Settings.ACCENT_COLOR.save(false);
        assertEquals("Accent color off", gray, AccentRings.seen(gray));
        Settings.ACCENT_COLOR.save(true);

        Settings.ACCENT_HUE.save(AccentHue.BLUE);
        assertEquals("Instagram's blue", gray, AccentRings.seen(gray));
        Settings.ACCENT_HUE.save(AccentHue.PURPLE);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", gray, AccentRings.seen(gray));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals("no context", gray, AccentRings.seen(gray)));

        Settings.STORY_RING_COLOR.save(StoryRingColor.TEAL);
        assertEquals("Ring color picked", gray, AccentRings.seen(gray));
        Settings.STORY_RING_COLOR.save(StoryRingColor.INSTAGRAM);
        assertTrue("Instagram's own ring color", AccentRings.seen(gray) != gray);
    }

    /** Material You's seen ring takes the wallpaper's fill tone, and before Android 12 keeps the gray. */
    @Test
    @Config(sdk = {28, 37})
    public void materialYousSeenRingTakesTheWallpapersFill() {
        MaterialYou.reset();
        try {
            Settings.ACCENT_COLOR.save(true);
            Settings.ACCENT_HUE.save(AccentHue.WALLPAPER);
            Settings.ACCENT_RINGS.save(true);
            int gray = SEEN_GRAYS[0];
            if (!MaterialYou.available()) {
                assertEquals(gray, AccentRings.seen(gray));
                return;
            }
            int fill = RuntimeEnvironment.getApplication().getColor(android.R.color.system_accent1_500) & 0xffffff;
            assertEquals(AccentRings.SEEN_ALPHA << 24 | fill, AccentRings.seen(gray));
        } finally {
            MaterialYou.reset();
        }
    }

    /**
     * The chat list's rows draw a seen story's ring themselves, in a gray from Compose's palette (#187).
     * Each of Instagram's seen grays, as a Compose color, takes the same shade the ring view's does, as a
     * Compose color, and each is counted.
     */
    @Test
    public void aChatListsSeenRingTakesTheAccent() throws Exception {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.PURPLE);
        Settings.ACCENT_RINGS.save(true);
        long seen = (long) (AccentRings.SEEN_ALPHA << 24 | AccentHue.PURPLE.fill & 0xffffff) << 32;
        for (int gray : SEEN_GRAYS) assertEquals(Integer.toHexString(gray), seen, composeSeen((long) gray << 32));
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().get(0).contains("seen chat list story rings given the accent " + SEEN_GRAYS.length));
    }

    /**
     * A color outside sRGB, Compose's unspecified color, a colored, black or white ring, and every gray
     * while the rings are off, Accent color is off or a Ring color is picked keep their color.
     */
    @Test
    @Config(shadows = StoryRingBuilt.class)
    public void everyOtherChatListSeenRingKeepsItsColor() throws Exception {
        long gray = (long) SEEN_GRAYS[0] << 32;
        assertEquals("off", gray, composeSeen(gray));
        accentRingsOn();
        assertTrue("on", composeSeen(gray) != gray);

        assertEquals("outside sRGB", gray | 0x11, composeSeen(gray | 0x11));
        assertEquals("unspecified", 0x10L, composeSeen(0x10L));
        for (int color : new int[]{0xff000000, 0xffffffff, 0xff1cd14f, 0x802b3036}) {
            assertEquals(Integer.toHexString(color), (long) color << 32, composeSeen((long) color << 32));
        }

        Settings.ACCENT_RINGS.save(false);
        assertEquals("the rings off", gray, composeSeen(gray));
        Settings.ACCENT_RINGS.save(true);

        Settings.ACCENT_COLOR.save(false);
        assertEquals("Accent color off", gray, composeSeen(gray));
        Settings.ACCENT_COLOR.save(true);

        Settings.STORY_RING_COLOR.save(StoryRingColor.TEAL);
        assertEquals("Ring color picked", gray, composeSeen(gray));
    }

    /** The chat list's seen ring hook, called by name so this test builds against an extension without it. */
    private static long composeSeen(long color) throws Exception {
        return (long) AccentRings.class.getMethod("composeSeen", long.class).invoke(null, color);
    }

    /**
     * The spinning ring around a group of profile pictures pairs Compose's yellow, orange, pink and
     * purple with the default ring's stops itself (#187): on, each stop's color comes back as the kept
     * shade for that stop, and the ring is counted once, at its first stop. A build boxing each stop as
     * a Float gets the same.
     */
    @Test
    public void theGroupRingTakesTheKeptShades() throws Exception {
        accentRingsOn();
        for (int at = 0; at < STORY.length; at++) {
            assertEquals("stop " + at, (long) SHADES[at] << 32, groupStop(STORY_STOPS[at], (long) STORY[at] << 32));
        }
        assertTrue(HookStatus.report().toString(), HookStatus.report().get(0).contains("group story rings given the accent 1"));
        for (int at = 0; at < STORY.length; at++) {
            assertEquals("boxed stop " + at, (long) SHADES[at] << 32, groupBoxedStop(STORY_STOPS[at], (long) STORY[at] << 32));
        }
        assertTrue(HookStatus.report().toString(), HookStatus.report().get(0).contains("group story rings given the accent 2"));
    }

    /**
     * Off, paused, with a Ring color picked or no shades kept yet, and for any other stop, anything but
     * a boxed Float or a color outside sRGB, the group ring keeps its color.
     */
    @Test
    @Config(shadows = StoryRingBuilt.class)
    public void everyOtherGroupRingStopKeepsItsColor() throws Exception {
        long yellow = (long) STORY[0] << 32;
        assertEquals("off", yellow, groupStop(STORY_STOPS[0], yellow));
        accentRingsOn();
        assertTrue("on", groupStop(STORY_STOPS[0], yellow) != yellow);
        assertTrue("boxed", groupBoxedStop(STORY_STOPS[0], yellow) != yellow);
        for (Object stop : new Object[]{null, (double) STORY_STOPS[0], "0.1435", 0.5f}) {
            assertEquals("boxed stop " + stop, yellow, groupBoxedStop(stop, yellow));
        }

        for (float stop : new float[]{0f, 0.5f, 1f, 0.15f}) assertEquals("stop " + stop, yellow, groupStop(stop, yellow));
        assertEquals("outside sRGB", yellow | 0x11, groupStop(STORY_STOPS[0], yellow | 0x11));

        Settings.STORY_RING_COLOR.save(StoryRingColor.TEAL);
        assertEquals("Ring color picked", yellow, groupStop(STORY_STOPS[0], yellow));
        Settings.STORY_RING_COLOR.save(StoryRingColor.INSTAGRAM);

        Settings.ACCENT_RINGS.save(false);
        assertEquals("the rings off", yellow, groupStop(STORY_STOPS[0], yellow));
        Settings.ACCENT_RINGS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", yellow, groupStop(STORY_STOPS[0], yellow));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        AccentRings.keptForTests(null);
        assertEquals("no theme had the ring style yet", yellow, groupStop(STORY_STOPS[0], yellow));
    }

    /** The group ring's hook, called by name so this test builds against an extension without it. */
    private static long groupStop(float stop, long color) throws Exception {
        return (long) AccentRings.class.getMethod("composeStop", float.class, long.class).invoke(null, stop, color);
    }

    /** The group ring's hook for a boxed stop, called by name so this test builds against an extension without it. */
    private static long groupBoxedStop(Object stop, long color) throws Exception {
        return (long) AccentRings.class.getMethod("composeBoxedStop", Object.class, long.class).invoke(null, stop, color);
    }

    /**
     * Laying the ring style on a theme keeps the shades the theme then holds for the default ring's
     * four attributes, in their order.
     */
    @Test
    public void theRingStyleKeepsItsShades() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_RINGS.save(true);
        Context context = new ContextThemeWrapper(themed(THEMED + ".kept", (name, type) -> {
            if (type.equals("style") && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL))) return android.R.style.TextAppearance;
            if (type.equals("style") && name.equals(AccentTheme.ringStyle(AccentHue.TEAL))) return android.R.style.TextAppearance_Small;
            return type.equals("attr") ? standIn(name) : 0;
        }), android.R.style.Theme_Material_Light);

        AccentTheme.apply(context, "igds_color_link");

        int[] expected = new int[STAND_INS.length];
        TypedValue value = new TypedValue();
        for (int step = 0; step < STAND_INS.length; step++) {
            assertTrue("the test theme has no color " + step, context.getTheme().resolveAttribute(STAND_INS[step], value, true)
                    && value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT);
            expected[step] = value.data;
        }
        assertArrayEquals(expected, AccentRings.keptShades());
    }

    /** An attribute the app lacks, or one that isn't a color, leaves the shades kept before. */
    @Test
    public void shadesThatCantBeReadLeaveTheLastOnes() {
        AccentRings.keptForTests(SHADES);
        Context missing = new ContextThemeWrapper(themed(THEMED + ".missing", (name, type) ->
                name.equals(AccentRings.ATTRIBUTES[3]) ? 0 : standIn(name)), android.R.style.Theme_Material_Light);
        AccentRings.remember(missing.getResources(), missing.getTheme(), THEMED + ".missing");
        assertArrayEquals("an attribute the app lacks", SHADES, AccentRings.keptShades());

        Context notAColor = new ContextThemeWrapper(themed(THEMED + ".notacolor", (name, type) ->
                name.equals(AccentRings.ATTRIBUTES[2]) ? android.R.attr.windowIsFloating : standIn(name)), android.R.style.Theme_Material_Light);
        AccentRings.remember(notAColor.getResources(), notAColor.getTheme(), THEMED + ".notacolor");
        assertArrayEquals("an attribute that isn't a color", SHADES, AccentRings.keptShades());
    }

    /** The four attributes are the default ring's, light to deep, and what's kept is handed out as copies. */
    @Test
    public void theAttributesAreTheDefaultRingsFour() {
        assertEquals(List.of("igds_color_gradient_yellow", "igds_color_gradient_orange", "igds_color_gradient_pink",
                "igds_color_gradient_lavender"), List.of(AccentRings.ATTRIBUTES));
        assertNull(AccentRings.keptShades());
        AccentRings.keptForTests(SHADES);
        assertNotSame(SHADES, AccentRings.keptShades());
        assertArrayEquals(SHADES, AccentRings.keptShades());
        assertTrue(HookStatus.missing(FamilyNames.ACCENT_COLOR).isEmpty());
    }

    /**
     * The chat list's rings hand their brush Compose's yellow, orange, pink and purple as boxed colors
     * (#683): on, they get the kept shades back in a list of their own, boxed the same way, and the list
     * Instagram made isn't touched. Each is counted.
     */
    @Test
    public void theChatListsRingTakesTheKeptShades() {
        accentRingsOn();
        List<Boxed> story = boxed(STORY);
        List<?> painted = AccentRings.composeBrush(story);
        assertNotSame(story, painted);
        assertArrayEquals(SHADES, unboxed(painted));
        assertArrayEquals("changed Instagram's own list", STORY, unboxed(story));
        assertNotSame("handed two rings one list", painted, AccentRings.composeBrush(story));
        assertTrue(HookStatus.report().toString(), HookStatus.report().get(0).contains(AccentRings.CHAT_LIST + " 2"));
    }

    /**
     * Off, paused, with a Ring color picked, or for a list that isn't four of Instagram's sRGB colors in
     * one class it can read, the chat list's ring keeps the list it was given.
     */
    @Test
    @Config(shadows = StoryRingBuilt.class)
    public void everyOtherChatListRingKeepsItsColors() {
        List<Boxed> story = boxed(STORY);
        assertSame("off", story, AccentRings.composeBrush(story));
        accentRingsOn();
        assertNotSame("on", story, AccentRings.composeBrush(story));

        List<Boxed> two = boxed(CLOSE_FRIENDS);
        assertSame("two colors", two, AccentRings.composeBrush(two));
        List<Boxed> flat = boxed(new int[]{0xFF1CD14F, 0xFF1CD14F, 0xFF1CD14F, 0xFF1CD14F});
        assertSame("one color", flat, AccentRings.composeBrush(flat));
        List<Object> wide = new ArrayList<>(story);
        wide.set(2, new Boxed(0x3c00_0000_0000_0011L));
        assertSame("a color outside sRGB", wide, AccentRings.composeBrush(wide));
        List<Object> mixed = new ArrayList<>(story);
        mixed.set(1, new OtherBoxed(1L << 32));
        assertSame("two kinds of color", mixed, AccentRings.composeBrush(mixed));
        List<String> named = Arrays.asList("#feda75", "#fa7e1e", "#d62976", "#962fbf");
        assertSame("not colors", named, AccentRings.composeBrush(named));
        assertNull(AccentRings.composeBrush(null));

        Settings.STORY_RING_COLOR.save(StoryRingColor.TEAL);
        assertSame("Ring color picked", story, AccentRings.composeBrush(story));
        Settings.STORY_RING_COLOR.save(StoryRingColor.INSTAGRAM);

        Settings.ACCENT_RINGS.save(false);
        assertSame("the rings off", story, AccentRings.composeBrush(story));
        Settings.ACCENT_RINGS.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertSame("paused", story, AccentRings.composeBrush(story));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        AccentRings.keptForTests(null);
        assertSame("no theme had the ring style yet", story, AccentRings.composeBrush(story));
        assertArrayEquals(STORY, unboxed(story));
    }

    private static List<Boxed> boxed(int[] colors) {
        List<Boxed> boxed = new ArrayList<>();
        for (int color : colors) boxed.add(new Boxed((long) color << 32));
        return boxed;
    }

    private static int[] unboxed(List<?> colors) {
        int[] argb = new int[colors.size()];
        for (int at = 0; at < argb.length; at++) {
            long packed = ((Boxed) colors.get(at)).packed;
            assertEquals("an sRGB color", 0L, packed & 0xffffffffL);
            argb[at] = (int) (packed >>> 32);
        }
        return argb;
    }

    /** Compose's color class as the build leaves it: a long and a constructor taking it, under a name of its own. */
    private static final class Boxed {
        static final long UNSPECIFIED = 0x10L;
        final long packed;

        private Boxed(long packed) {
            this.packed = packed;
        }
    }

    private static final class OtherBoxed {
        final long packed;

        private OtherBoxed(long packed) {
            this.packed = packed;
        }
    }

    private static void accentRingsOn() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.PURPLE);
        Settings.ACCENT_RINGS.save(true);
        AccentRings.keptForTests(SHADES);
    }

    private static void assertBoth(String what, int[] story) {
        int[] before = story.clone();
        assertSame(what, story, AccentRings.colors(story, STORY_STOPS));
        assertSame(what + " on Compose screens", story, AccentRings.composeColors(story));
        assertArrayEquals(what, before, story);
    }

    private static void assertKept(String what, int[] colors, float[] stops) {
        int[] before = colors.clone();
        assertSame(what, colors, AccentRings.colors(colors, stops));
        assertArrayEquals(what, before, colors);
    }

    /** The stand-in attribute for one of the default ring's, or none. */
    private static int standIn(String name) {
        for (int step = 0; step < AccentRings.ATTRIBUTES.length; step++) {
            if (AccentRings.ATTRIBUTES[step].equals(name)) return STAND_INS[step];
        }
        return 0;
    }

    /** Answers a resource's number by name and type for {@link #themed}. */
    private interface Ids {
        int of(String name, String type);
    }

    /** The app's context under [pkg], whose resources answer only [ids] for that package. */
    private static Context themed(String pkg, Ids ids) {
        Context app = RuntimeEnvironment.getApplication();
        Resources resources = new Resources(app.getAssets(), app.getResources().getDisplayMetrics(), app.getResources().getConfiguration()) {
            @Override
            public int getIdentifier(String name, String type, String defPackage) {
                return pkg.equals(defPackage) ? ids.of(name, type) : 0;
            }
        };
        return new ContextWrapper(app) {
            @Override
            public Resources getResources() {
                return resources;
            }

            @Override
            public String getPackageName() {
                return pkg;
            }
        };
    }

    /** This build has Story ring size, as the patch writes its status method. */
    @Implements(value = SettingsStatus.class, isInAndroidSdk = false)
    public static class StoryRingBuilt {
        @Implementation
        protected static boolean storyRingSize() {
            return true;
        }
    }
}
