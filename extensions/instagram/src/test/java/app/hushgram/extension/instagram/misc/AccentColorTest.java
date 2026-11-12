/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.util.TypedValue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Which colors Accent color swaps, into what, and when it leaves Instagram's blue alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class AccentColorTest {
    /** Instagram's blue_5 and Prism's indigo, the two fills. */
    private static final int BLUE = 0xff0095f6;
    private static final int INDIGO = 0xff4a5df9;
    /** Prism's pressed indigo, its dim one and the bright links on a light and a dark screen. */
    private static final int PRESSED = 0xff3b52e7;
    private static final int DIM = 0xff232e67;
    private static final int LINK_LIGHT = 0xff3849da;
    private static final int LINK_DARK = 0xff8593ff;
    /** Prism's dark background and white: never touched. */
    private static final int PRISM_BLACK = 0xff0c1014;
    private static final int WHITE = 0xffffffff;
    /** The older theme's links: blue_8 on a light screen, and blue_1, its pale blue, on a dark one. */
    private static final int CLASSIC_LINK = 0xff00376b;
    private static final int PALE_BLUE = 0xffe0f1ff;

    private static final int[] ENABLED = {android.R.attr.state_enabled};
    private static final int[] PRESSED_STATE = {android.R.attr.state_enabled, android.R.attr.state_pressed};
    private static final int[] DISABLED = {-android.R.attr.state_enabled};
    private static final int[] SELECTED = {android.R.attr.state_enabled, android.R.attr.state_selected};

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        MaterialYou.reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        prepare();
    }

    /** Off to start, so a patched Instagram keeps its blue until the switch goes on. */
    @Test
    public void offToStartInstagramsBlueGoesThrough() {
        assertEquals(Boolean.FALSE, Settings.ACCENT_COLOR.defaultValue);
        assertEquals(AccentHue.PURPLE, Settings.ACCENT_HUE.defaultValue);
        assertTrue("colors on screen keep theirs until a restart", Settings.ACCENT_COLOR.rebootApp);
        assertTrue("colors on screen keep theirs until a restart", Settings.ACCENT_HUE.rebootApp);
        assertEquals(BLUE, AccentColor.color(BLUE));
        ColorStateList button = button();
        assertSame(button, AccentColor.list(button));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** Each accent blue becomes the shade playing its part, keeping its alpha, and each swap is counted. */
    @Test
    public void eachAccentBlueBecomesItsShade() {
        Settings.ACCENT_COLOR.save(true);
        AccentHue purple = AccentHue.PURPLE;
        assertEquals(0xff7638fa, AccentColor.color(BLUE));
        assertEquals(0xff7638fa, AccentColor.color(INDIGO));
        assertEquals("a see-through step keeps its alpha", 0x4d7638fa, AccentColor.color(0x4d0095f6));
        assertEquals(0xff000000 | purple.pressed, AccentColor.color(PRESSED));
        assertEquals(0xff000000 | purple.dim, AccentColor.color(DIM));
        assertEquals(0xff5806e0, AccentColor.color(LINK_LIGHT));
        assertEquals(0xff5806e0, AccentColor.color(0xff3143e3));
        assertEquals(0xffa07cfd, AccentColor.color(LINK_DARK));
        assertEquals(0xffa07cfd, AccentColor.color(0xff85a1ff));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 9, 0 found, 0 missing. Counted: "
                + AccentColor.COLOR + " 9"), HookStatus.report());
    }

    /**
     * #8 on 451: a Compose palette's color holds its ARGB in the high 32 bits. Each accent blue
     * becomes its shade there, keeping its alpha. Grays, colors in other color spaces (something in
     * the low bits) and everything with the switch off go through.
     */
    @Test
    public void aComposePalettesBlueBecomesItsShade() {
        long link = 0xff85a1ffL << 32;
        assertEquals(link, AccentColor.composeColor(link));
        Settings.ACCENT_COLOR.save(true);
        HookStatus.clear();
        assertEquals(0xffa07cfdL << 32, AccentColor.composeColor(link));
        assertEquals(0xff7638faL << 32, AccentColor.composeColor(0xff4a5df9L << 32));
        assertEquals("a see-through step keeps its alpha", 0x4d7638faL << 32, AccentColor.composeColor(0x4d4a5df9L << 32));
        assertEquals(0xff737373L << 32, AccentColor.composeColor(0xff737373L << 32));
        long otherSpace = (0xff85a1ffL << 32) | 0x3f;
        assertEquals(otherSpace, AccentColor.composeColor(otherSpace));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 5, 0 found, 0 missing. Counted: "
                + AccentColor.COMPOSE + " 3"), HookStatus.report());
    }

    /**
     * A read through the theme and then Instagram's own Resources passes two hooks, so a color
     * already swapped comes back as it is, every shade of every color picked.
     */
    @Test
    public void aSwappedColorComesBackAsItIs() {
        Settings.ACCENT_COLOR.save(true);
        for (AccentHue hue : AccentHue.values()) {
            if (hue == AccentHue.BLUE || hue == AccentHue.WALLPAPER) continue;
            Settings.ACCENT_HUE.save(hue);
            for (int shade : new int[] {hue.fill, hue.pressed, hue.dim, hue.linkOnLight, hue.linkOnDark, hue.pale}) {
                int color = 0xff000000 | shade;
                assertEquals(hue + " " + Integer.toHexString(color), color, AccentColor.color(color));
            }
        }
    }

    /**
     * #8 on 451: the dark screen's link (#708dff, seen on the S22's captions and mentions), the
     * indigo fills of badges, dots and strong buttons, and a selected pill's pale backgrounds.
     */
    @Test
    public void theNewerIndigoShadesTakeTheColor() {
        Settings.ACCENT_COLOR.save(true);
        AccentHue purple = AccentHue.PURPLE;
        assertEquals(0xff000000 | purple.linkOnDark, AccentColor.color(0xff708dff));
        for (int fill : new int[] {0xff5c77ff, 0xff5465fe, 0xff4150f7}) {
            assertEquals(Integer.toHexString(fill), 0xff000000 | purple.fill, AccentColor.color(fill));
        }
        for (int pale : new int[] {0xffeff1ff, 0xffebf1ff, 0xffdbdfff, 0xffccddff}) {
            assertEquals(Integer.toHexString(pale), 0xff000000 | purple.pale, AccentColor.color(pale));
        }
        assertEquals("a see-through link keeps its alpha", 0x99000000 | purple.linkOnDark, AccentColor.color(0x99708dff));
    }

    /** No other color changes: text, backgrounds, grays and Instagram's other pale blues stay as they are. */
    @Test
    public void everyOtherColorGoesThrough() {
        Settings.ACCENT_COLOR.save(true);
        for (int color : new int[] {WHITE, 0xff000000, PRISM_BLACK, 0xffaddfff, 0xff0095f7, 0x00000000, 0xffed4956}) {
            assertEquals(Integer.toHexString(color), color, AccentColor.color(color));
        }
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 7, 0 found, 0 missing"), HookStatus.report());
    }

    /**
     * #8: the older theme's text links (mentions, hashtags, bio links) take the color too, blue_8 as
     * the link on a light screen, and its pale blue, a link on a dark screen and a selected pill on
     * a light one, as the color's pale shade. Each keeps its alpha.
     */
    @Test
    public void theOlderThemesLinksAndPaleBlueTakeTheColor() {
        Settings.ACCENT_COLOR.save(true);
        AccentHue purple = AccentHue.PURPLE;
        assertEquals(0xff000000 | purple.linkOnLight, AccentColor.color(CLASSIC_LINK));
        assertEquals(0xff000000 | purple.pale, AccentColor.color(PALE_BLUE));
        assertEquals(0x80000000 | purple.pale, AccentColor.color(0x80e0f1ff));
        assertEquals("the fill most of the way to white", 0xeee7fe, purple.pale);
        assertEquals("the same step from Instagram's blue lands within a shade of its pale blue, #e0f1ff",
                0xe0f2fe, AccentHue.towardWhite(0x0095f6, 0.878f));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 3, 0 found, 0 missing. Counted: "
                + AccentColor.COLOR + " 3"), HookStatus.report());
    }

    /**
     * #8: the value read hands over what an attribute resolves to. A color, the way mentions,
     * hashtags and links get theirs, swaps like any other read. The same bits as a number, a size or
     * a reference go through untouched, and so does a value with no TypedValue.
     */
    @Test
    public void aThemeValueSwapsOnlyWhenItsAColor() {
        Settings.ACCENT_COLOR.save(true);
        AccentHue purple = AccentHue.PURPLE;
        assertEquals(0xff000000 | purple.linkOnLight, AccentColor.value(CLASSIC_LINK, typed(TypedValue.TYPE_INT_COLOR_ARGB8, CLASSIC_LINK)));
        assertEquals(0xff000000 | purple.linkOnDark, AccentColor.value(0xff85a1ff, typed(TypedValue.TYPE_INT_COLOR_RGB8, 0xff85a1ff)));
        assertEquals(0xff7638fa, AccentColor.value(BLUE, typed(TypedValue.TYPE_INT_COLOR_ARGB4, BLUE)));
        assertEquals(WHITE, AccentColor.value(WHITE, typed(TypedValue.TYPE_INT_COLOR_ARGB8, WHITE)));
        for (int type : new int[] {TypedValue.TYPE_INT_DEC, TypedValue.TYPE_INT_HEX, TypedValue.TYPE_DIMENSION,
                TypedValue.TYPE_REFERENCE, TypedValue.TYPE_STRING, TypedValue.TYPE_INT_BOOLEAN, TypedValue.TYPE_NULL}) {
            assertEquals("type " + type, CLASSIC_LINK, AccentColor.value(CLASSIC_LINK, typed(type, CLASSIC_LINK)));
        }
        assertEquals(BLUE, AccentColor.value(BLUE, null));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 4, 0 found, 0 missing. Counted: "
                + AccentColor.COLOR + " 3"), HookStatus.report());

        Settings.ACCENT_COLOR.save(false);
        assertEquals("off, Instagram's own", CLASSIC_LINK, AccentColor.value(CLASSIC_LINK, typed(TypedValue.TYPE_INT_COLOR_ARGB8, CLASSIC_LINK)));
        Settings.ACCENT_COLOR.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused, Instagram's own", CLASSIC_LINK, AccentColor.value(CLASSIC_LINK, typed(TypedValue.TYPE_INT_COLOR_ARGB8, CLASSIC_LINK)));
    }

    /** Every color picked swaps, and Instagram blue picked leaves the stock blue. */
    @Test
    public void eachColorPickedAndInstagramBlue() {
        Settings.ACCENT_COLOR.save(true);
        for (AccentHue hue : AccentHue.values()) {
            if (hue == AccentHue.WALLPAPER) continue;
            Settings.ACCENT_HUE.save(hue);
            int swapped = AccentColor.color(INDIGO);
            if (hue == AccentHue.BLUE) {
                assertEquals(INDIGO, swapped);
                ColorStateList button = button();
                assertSame(button, AccentColor.list(button));
            } else {
                assertEquals(hue.name(), 0xff000000 | hue.fill, swapped);
            }
        }
    }

    /**
     * #187: Material You gives each part the wallpaper palette's tone, keeping the alpha. Before
     * Android 12 there's no palette, so Instagram's blue goes through.
     */
    @Test
    public void materialYouTakesTheWallpapersTones() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.WALLPAPER);
        ColorStateList button = button();
        if (!MaterialYou.available()) {
            assertNull(AccentColor.picked());
            assertEquals(BLUE, AccentColor.color(BLUE));
            assertSame(button, AccentColor.list(button));
            return;
        }
        Context context = RuntimeEnvironment.getApplication();
        int fill = context.getColor(android.R.color.system_accent1_500) & 0xffffff;
        int pressed = context.getColor(android.R.color.system_accent1_600) & 0xffffff;
        assertEquals(0xff000000 | fill, AccentColor.color(BLUE));
        assertEquals(0xff000000 | fill, AccentColor.color(INDIGO));
        assertEquals("a see-through step keeps its alpha", 0x4d000000 | fill, AccentColor.color(0x4d0095f6));
        assertEquals(0xff000000 | pressed, AccentColor.color(PRESSED));
        assertEquals(context.getColor(android.R.color.system_accent1_800) | 0xff000000, AccentColor.color(DIM));
        assertEquals(0xff000000 | pressed, AccentColor.color(LINK_LIGHT));
        assertEquals(context.getColor(android.R.color.system_accent1_200) | 0xff000000, AccentColor.color(LINK_DARK));
        assertEquals(context.getColor(android.R.color.system_accent1_50) | 0xff000000, AccentColor.color(PALE_BLUE));
        assertEquals(PRISM_BLACK, AccentColor.color(PRISM_BLACK));
        assertEquals(0xff000000 | pressed, AccentColor.list(button).getColorForState(PRESSED_STATE, 0));
    }

    /**
     * #435: the shades are read through Instagram's Resources, whose color reads come back through
     * {@link AccentColor#color}. A wallpaper tone that's one of Instagram's blues asked for the shades
     * again, until the stack ran out. On a phone that can end inside native code, as a crash at launch.
     */
    @Test
    public void aWallpaperToneThatIsAnAccentBlueDoesNotAskForTheShadesAgain() {
        if (!MaterialYou.available()) return;
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.WALLPAPER);
        HookedResources resources = hookedResources(INDIGO);
        Utils.setContext(contextWith(resources));

        assertEquals(INDIGO, AccentColor.color(BLUE));

        assertEquals("a tone is read without reading the tones again", 1, resources.deepest);
        assertEquals("the six tones are read once", 6, resources.reads);
        assertEquals(List.of(), HookStatus.missing(FamilyNames.ACCENT_COLOR));
    }

    /** A later read takes the palette as it is now, and not through the shades the last read kept. */
    @Test
    public void aNewWallpapersTonesAreReadAsTheyAre() {
        if (!MaterialYou.available()) return;
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.WALLPAPER);
        HookedResources resources = hookedResources(INDIGO);
        Context context = contextWith(resources);
        MaterialYou.read(context);

        resources.tone = LINK_DARK;
        int[] shades = MaterialYou.read(context);

        for (int shade : shades) assertEquals(LINK_DARK & 0xffffff, shade);
    }

    /**
     * White text on each fill reads at least as well as on Instagram's own blue, and each link
     * shade reads well on its screen, white for a light one and Prism's black for a dark one.
     */
    @Test
    public void everyColorStaysReadable() {
        double stock = contrast(WHITE, BLUE);
        for (AccentHue hue : AccentHue.values()) {
            // Material You's tones are the phone's, read as Instagram runs.
            if (hue == AccentHue.BLUE || hue == AccentHue.WALLPAPER) continue;
            assertTrue(hue + ": white on its fill",contrast(WHITE, 0xff000000 | hue.fill) >= 3.9);
            assertTrue(hue + ": white on its fill against stock", contrast(WHITE, 0xff000000 | hue.fill) > stock);
            assertTrue(hue + ": white on it held down", contrast(WHITE, 0xff000000 | hue.pressed) >= 3.9);
            assertTrue(hue + ": its link on white", contrast(WHITE, 0xff000000 | hue.linkOnLight) >= 5.0);
            assertTrue(hue + ": its link on black", contrast(PRISM_BLACK, 0xff000000 | hue.linkOnDark) >= 5.0);
            assertTrue(hue + ": its pale link on black", contrast(0xff000000, 0xff000000 | hue.pale) >= 5.0);
            assertTrue(hue + ": its link on its pale pill", contrast(0xff000000 | hue.pale, 0xff000000 | hue.linkOnLight) >= 4.5);
            assertNotEquals(hue + ": a color of its own", BLUE & 0xffffff, hue.fill);
        }
    }

    /**
     * A button's list keeps each state: held down takes the pressed shade, disabled the faded fill
     * at the same alpha, selected keeps its white, and the rest the fill. The same list comes back
     * for the same list asked about again.
     */
    @Test
    public void aListKeepsItsStates() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        ColorStateList stock = button();
        ColorStateList swapped = AccentColor.list(stock);

        assertNotEquals(stock, swapped);
        assertTrue(swapped.isStateful());
        assertEquals(0xff000000 | AccentHue.TEAL.pressed, swapped.getColorForState(PRESSED_STATE, 0));
        assertEquals(0x4d000000 | AccentHue.TEAL.fill, swapped.getColorForState(DISABLED, 0));
        assertEquals(WHITE, swapped.getColorForState(SELECTED, 0));
        assertEquals(0xff000000 | AccentHue.TEAL.fill, swapped.getColorForState(ENABLED, 0));
        assertEquals(0xff000000 | AccentHue.TEAL.fill, swapped.getDefaultColor());
        assertEquals("the stock list is left as it was", INDIGO, stock.getColorForState(ENABLED, 0));
        assertSame(swapped, AccentColor.list(stock));

        Settings.ACCENT_HUE.save(AccentHue.ORANGE);
        assertEquals("a new pick swaps it again", 0xff000000 | AccentHue.ORANGE.fill,
                AccentColor.list(stock).getColorForState(ENABLED, 0));
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 3, 0 found, 0 missing. Counted: "
                + AccentColor.LIST + " 3"), HookStatus.report());
    }

    /** A list with no accent blue in it, a plain color list and a subclass come back as they were. */
    @Test
    public void listsWithoutBlueComeBackAsTheyWere() {
        Settings.ACCENT_COLOR.save(true);
        ColorStateList gray = new ColorStateList(new int[][] {DISABLED, {}}, new int[] {0x4d737373, 0xff737373});
        assertSame(gray, AccentColor.list(gray));
        assertSame(gray, AccentColor.list(gray));
        ColorStateList plain = ColorStateList.valueOf(WHITE);
        assertSame(plain, AccentColor.list(plain));
        ColorStateList blue = ColorStateList.valueOf(BLUE);
        assertEquals(0xff7638fa, AccentColor.list(blue).getDefaultColor());
        ColorStateList subclass = new ColorStateList(new int[][] {{}}, new int[] {BLUE}) {
        };
        assertSame(subclass, AccentColor.list(subclass));
        assertSame(null, AccentColor.list(null));
    }

    /** Paused, not ready yet or Instagram blue picked: the stock blue, in a color and in a list. */
    @Test
    public void pausedUnreadyAndBlueLeaveInstagramsBlue() {
        Settings.ACCENT_COLOR.save(true);
        ColorStateList button = button();
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(BLUE, AccentColor.color(BLUE));
        assertSame(button, AccentColor.list(button));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            assertEquals(BLUE, AccentColor.color(BLUE));
            assertSame(button, AccentColor.list(button));
        });

        Settings.ACCENT_HUE.save(AccentHue.BLUE);
        assertEquals(BLUE, AccentColor.color(BLUE));
        assertSame(button, AccentColor.list(button));

        Settings.ACCENT_HUE.save(AccentHue.PINK);
        assertEquals(0xffd6005a, AccentColor.color(BLUE));
        assertFalse(button == AccentColor.list(button));
    }

    /** Instagram's Resources as the patch leaves them: each color read passes through the hook. */
    private static final class HookedResources extends Resources {
        int tone;
        int reads;
        int depth;
        int deepest;

        HookedResources(Resources base, int tone) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            this.tone = tone;
        }

        @Override
        public int getColor(int id, Resources.Theme theme) {
            reads++;
            deepest = Math.max(deepest, ++depth);
            try {
                return AccentColor.color(tone);
            } finally {
                depth--;
            }
        }
    }

    private static HookedResources hookedResources(int tone) {
        return new HookedResources(RuntimeEnvironment.getApplication().getResources(), tone);
    }

    private static Context contextWith(Resources resources) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public Resources getResources() {
                return resources;
            }
        };
    }

    /** A TypedValue as Theme.resolveAttribute fills it: its type and its data. */
    private static TypedValue typed(int type, int data) {
        TypedValue value = new TypedValue();
        value.type = type;
        value.data = data;
        return value;
    }

    /** A Prism primary button's list: pressed, disabled, selected white and the fill for the rest. */
    private static ColorStateList button() {
        return new ColorStateList(new int[][] {PRESSED_STATE, DISABLED, SELECTED, {}},
                new int[] {PRESSED, 0x4d4a5df9, WHITE, INDIGO});
    }

    /** The WCAG contrast of two opaque colors. */
    private static double contrast(int first, int second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(color >> 16) + 0.7152 * channel(color >> 8) + 0.0722 * channel(color);
    }

    private static double channel(int value) {
        double c = (value & 0xff) / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
