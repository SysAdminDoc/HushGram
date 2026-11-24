/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class AccentThemeTest {
    /** A package of its own, so the resource numbers looked up for the app in another test aren't reused. */
    private static final String THEMED = "app.hushgram.test.themed";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_BADGES.resetToDefault();
        Settings.ACCENT_ICONS.resetToDefault();
        Settings.ACCENT_REELS.resetToDefault();
        Settings.ACCENT_RINGS.resetToDefault();
        Settings.ACCENT_RINGS_SOLID.resetToDefault();
        Settings.ACCENT_BACKGROUNDS.resetToDefault();
        Settings.CLASSIC_COLORS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        prepare();
    }

    /**
     * Each style is named for the attribute, the part its blue plays and the blue's alpha, the way the
     * patch names the ones it adds (AccentThemeTest on the patch side holds the same names).
     */
    @Test
    public void eachBlueNamesItsStyle() {
        assertEquals("HushGramAccent_igds_color_link_link_on_dark_ff", AccentTheme.styleFor("igds_color_link", color(0xff85a1ff)));
        assertEquals("HushGramAccent_igds_color_link_link_on_light_ff", AccentTheme.styleFor("igds_color_link", color(0xff3143e3)));
        assertEquals("HushGramAccent_igds_color_primary_button_indigo_fill_ff",
                AccentTheme.styleFor("igds_color_primary_button_indigo", color(0xff4a5df9)));
        assertEquals("HushGramAccent_igds_color_selected_text_background_fill_4d",
                AccentTheme.styleFor("igds_color_selected_text_background", color(0x4d0095f6)));
        assertEquals("HushGramAccent_android_textColorLink_pale_b3", AccentTheme.styleFor("android:textColorLink", color(0xb3e0f1ff)));
        assertEquals("HushGramAccentPalette_PURPLE", AccentTheme.paletteStyle(AccentHue.PURPLE));
        assertEquals("Material You's, which the patch adds on its own", "HushGramAccentPalette_WALLPAPER",
                AccentTheme.paletteStyle(AccentHue.WALLPAPER));
        assertEquals("HushGramAccentBadge_WALLPAPER", AccentTheme.badgeStyle(AccentHue.WALLPAPER));
    }

    /** An attribute holding a gray, a color list's file or a size has no style. */
    @Test
    public void anythingButABlueHasNoStyle() {
        assertNull(AccentTheme.styleFor("igds_color_secondary_text", color(0xff737373)));
        TypedValue file = new TypedValue();
        file.type = TypedValue.TYPE_STRING;
        file.data = 0xff4a5df9;
        assertNull("a color list's file", AccentTheme.styleFor("igds_color_link", file));
        TypedValue size = new TypedValue();
        size.type = TypedValue.TYPE_DIMENSION;
        size.data = 0xff4a5df9;
        assertNull(AccentTheme.styleFor("igds_color_link", size));
    }

    /** The patch works the same shades out of the fill, and holds these. */
    @Test
    public void theWorkedOutShadesAreThePatchs() {
        assertEquals(0x6430d5, AccentHue.PURPLE.pressed);
        assertEquals(0x351971, AccentHue.PURPLE.dim);
        assertEquals(0xeee7fe, AccentHue.PURPLE.pale);
    }

    /** Off, the theme is left alone and nothing's counted. On without the patch's styles, it's counted and left alone. */
    @Test
    public void withoutTheSwitchOrTheStylesTheThemeStaysInstagrams() {
        Context context = RuntimeEnvironment.getApplication();
        AccentTheme.apply(context);
        assertEquals(List.of(), HookStatus.report());

        Settings.ACCENT_COLOR.save(true);
        AccentTheme.apply(null);
        AccentTheme.apply(context);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.MISSING + " 1"),
                HookStatus.report());

        Settings.ACCENT_HUE.save(AccentHue.BLUE);
        HookStatus.clear();
        AccentTheme.apply(context);
        assertEquals("Instagram's own blue picked", List.of(), HookStatus.report());
    }

    /**
     * With the styles there but no listed attribute set on the theme, nothing goes on and that's counted.
     * Without the list the patch writes in, it's counted as missing.
     */
    @Test
    public void aThemeWithNoBlueIsCountedAndLeftAlone() {
        Settings.ACCENT_COLOR.save(true);
        Context app = RuntimeEnvironment.getApplication();
        Resources listed = new Resources(app.getAssets(), app.getResources().getDisplayMetrics(), app.getResources().getConfiguration()) {
            @Override
            public int getIdentifier(String name, String type, String defPackage) {
                if (!THEMED.equals(defPackage)) return 0;
                if (type.equals("style") && name.equals(AccentTheme.paletteStyle(AccentHue.PURPLE))) return 0x7f990001;
                // Attributes no theme sets, so none resolves.
                if (type.equals("attr")) return name.equals("igds_color_link") ? 0x7f990003 : 0x7f990004;
                return 0;
            }
        };
        Context context = new ContextWrapper(app) {
            @Override
            public Resources getResources() {
                return listed;
            }

            @Override
            public String getPackageName() {
                return THEMED;
            }
        };
        AccentTheme.apply(context, "igds_color_link igds_color_primary_button_indigo");
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1"),
                HookStatus.report());

        HookStatus.clear();
        AccentTheme.apply(context);
        assertEquals("the patch's list never written in", List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: "
                + AccentTheme.MISSING + " 1"), HookStatus.report());
    }

    /**
     * With Badges in the accent color on, the picked color's badge style goes on even on a theme with
     * no blue, and its absence is counted (#315). Off, it's never looked up.
     */
    @Test
    public void theBadgeStyleGoesOnWithItsSwitch() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        assertEquals("HushGramAccentBadge_TEAL", AccentTheme.badgeStyle(AccentHue.TEAL));
        int[] badgeLookups = {0};
        Context context = themed(THEMED + ".badges", (name, type) -> {
            if (type.equals("style") && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL))) return android.R.style.TextAppearance;
            if (type.equals("style") && name.equals(AccentTheme.badgeStyle(AccentHue.TEAL))) {
                badgeLookups[0]++;
                return android.R.style.TextAppearance_Small;
            }
            return type.equals("attr") ? 0x7f990005 : 0;
        });
        AccentTheme.apply(context, "igds_color_link");
        assertEquals("off", 0, badgeLookups[0]);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1"),
                HookStatus.report());

        Settings.ACCENT_BADGES.save(true);
        HookStatus.clear();
        AccentTheme.apply(context, "igds_color_link");
        assertEquals(1, badgeLookups[0]);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.BADGES + " 1"), HookStatus.report());

        HookStatus.clear();
        Context bare = themed(THEMED + ".nobadges", (name, type) -> type.equals("style")
                && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL)) ? android.R.style.TextAppearance : 0);
        AccentTheme.apply(bare, "igds_color_link");
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.BADGES_MISSING + " 1"), HookStatus.report());
    }

    @Test
    public void theIconStyleGoesOnWithItsSwitchInTheThemesShade() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        assertEquals("HushGramAccentIcons_TEAL_light", AccentTheme.iconStyle(AccentHue.TEAL, false));
        assertEquals("HushGramAccentIcons_TEAL_dark", AccentTheme.iconStyle(AccentHue.TEAL, true));
        List<String> asked = new ArrayList<>();
        Ids ids = (name, type) -> {
            if (type.equals("style") && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL))) return android.R.style.TextAppearance;
            if (type.equals("style") && name.startsWith("HushGramAccentIcons_")) {
                asked.add(name);
                return android.R.style.TextAppearance_Small;
            }
            if (type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE)) return android.R.attr.colorForeground;
            return type.equals("attr") ? 0x7f990005 : 0;
        };
        Context light = new ContextThemeWrapper(themed(THEMED + ".icons.light", ids), android.R.style.Theme_Material_Light);
        AccentTheme.apply(light, "igds_color_link");
        assertEquals("off", List.of(), asked);

        Settings.ACCENT_ICONS.save(true);
        HookStatus.clear();
        AccentTheme.apply(light, "igds_color_link");
        assertEquals(List.of(AccentTheme.iconStyle(AccentHue.TEAL, false)), asked);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.ICONS + " 1"), HookStatus.report());

        Context dark = new ContextThemeWrapper(themed(THEMED + ".icons.dark", ids), android.R.style.Theme_Material);
        AccentTheme.apply(dark, "igds_color_link");
        assertEquals(List.of(AccentTheme.iconStyle(AccentHue.TEAL, false), AccentTheme.iconStyle(AccentHue.TEAL, true)), asked);

        HookStatus.clear();
        Context bare = themed(THEMED + ".noicons", (name, type) -> type.equals("style")
                && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL)) ? android.R.style.TextAppearance : 0);
        AccentTheme.apply(bare, "igds_color_link");
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.ICONS_MISSING + " 1"), HookStatus.report());
    }

    /** The ring style goes on with its switch, and a build without one says so (#187). */
    @Test
    public void theRingStyleGoesOnWithItsSwitch() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        assertEquals("HushGramAccentRings_TEAL", AccentTheme.ringStyle(AccentHue.TEAL));
        assertEquals("HushGramAccentRings_WALLPAPER", AccentTheme.ringStyle(AccentHue.WALLPAPER));
        List<String> asked = new ArrayList<>();
        Ids ids = (name, type) -> {
            if (type.equals("style") && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL))) return android.R.style.TextAppearance;
            if (type.equals("style") && (name.startsWith("HushGramAccentRings_") || name.startsWith("HushGramAccentRingsSolid_"))) {
                asked.add(name);
                return android.R.style.TextAppearance_Small;
            }
            return type.equals("attr") ? 0x7f990005 : 0;
        };
        Context context = new ContextThemeWrapper(themed(THEMED + ".rings", ids), android.R.style.Theme_Material_Light);
        AccentTheme.apply(context, "igds_color_link");
        assertEquals("off", List.of(), asked);

        Settings.ACCENT_RINGS.save(true);
        HookStatus.clear();
        AccentTheme.apply(context, "igds_color_link");
        assertEquals(List.of("HushGramAccentRings_TEAL"), asked);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.RINGS + " 1"), HookStatus.report());

        // Solid story rings swaps in the style with every stop at the fill (#519).
        assertEquals("HushGramAccentRingsSolid_TEAL", AccentTheme.solidRingStyle(AccentHue.TEAL));
        Settings.ACCENT_RINGS_SOLID.save(true);
        asked.clear();
        AccentTheme.apply(context, "igds_color_link");
        assertEquals(List.of("HushGramAccentRingsSolid_TEAL"), asked);
        Settings.ACCENT_RINGS.save(false);
        asked.clear();
        AccentTheme.apply(context, "igds_color_link");
        assertEquals("solid alone does nothing with the rings off", List.of(), asked);
        Settings.ACCENT_RINGS.save(true);
        Settings.ACCENT_RINGS_SOLID.save(false);

        HookStatus.clear();
        Context bare = themed(THEMED + ".norings", (name, type) -> type.equals("style")
                && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL)) ? android.R.style.TextAppearance : 0);
        AccentTheme.apply(bare, "igds_color_link");
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.RINGS_MISSING + " 1"), HookStatus.report());
    }

    /**
     * The background style goes on with its switch in the theme's own shade, a theme whose background
     * isn't a solid color of its own keeps it, and a build without the style says so (#187).
     */
    @Test
    public void theBackgroundStyleGoesOnWithItsSwitchInTheThemesShade() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        assertEquals("HushGramAccentBackgrounds_TEAL_light", AccentTheme.backgroundStyle(AccentHue.TEAL, false));
        assertEquals("HushGramAccentBackgrounds_TEAL_dark", AccentTheme.backgroundStyle(AccentHue.TEAL, true));
        List<String> asked = new ArrayList<>();
        Ids ids = (name, type) -> {
            if (type.equals("style") && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL))) return android.R.style.TextAppearance;
            if (type.equals("style") && name.startsWith("HushGramAccentBackgrounds_")) {
                asked.add(name);
                return android.R.style.TextAppearance_Small;
            }
            if (type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE)) return android.R.attr.colorForeground;
            if (type.equals("attr") && name.equals(AccentTheme.BACKGROUND_ATTRIBUTE)) return android.R.attr.colorBackground;
            return type.equals("attr") ? 0x7f990005 : 0;
        };
        Context light = new ContextThemeWrapper(themed(THEMED + ".backgrounds.light", ids), android.R.style.Theme_Material_Light);
        assertTrue("the light theme's background reads as its own", AccentTheme.paintsItsOwnBackground(light.getTheme(), android.R.attr.colorBackground));
        AccentTheme.apply(light, "igds_color_link");
        assertEquals("off", List.of(), asked);

        Settings.ACCENT_BACKGROUNDS.save(true);
        HookStatus.clear();
        AccentTheme.apply(light, "igds_color_link");
        assertEquals(List.of(AccentTheme.backgroundStyle(AccentHue.TEAL, false)), asked);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.BACKGROUNDS + " 1"), HookStatus.report());

        Context dark = new ContextThemeWrapper(themed(THEMED + ".backgrounds.dark", ids), android.R.style.Theme_Material);
        AccentTheme.apply(dark, "igds_color_link");
        assertEquals(List.of(AccentTheme.backgroundStyle(AccentHue.TEAL, false), AccentTheme.backgroundStyle(AccentHue.TEAL, true)), asked);

        HookStatus.clear();
        Context bare = themed(THEMED + ".nobackgrounds", (name, type) -> type.equals("style")
                && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL)) ? android.R.style.TextAppearance : 0);
        AccentTheme.apply(bare, "igds_color_link");
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.BACKGROUNDS_MISSING + " 1"), HookStatus.report());
    }

    /**
     * Classic colors' style goes on with Accent color off, in the theme's own shade, and after the picked
     * color's backgrounds when both are on, so it wins over them (#639).
     */
    @Test
    public void theClassicStyleGoesOnWithItsSwitchInTheThemesShade() {
        assertEquals("HushGramClassicBackgrounds_light", AccentTheme.classicStyle(false));
        assertEquals("HushGramClassicBackgrounds_dark", AccentTheme.classicStyle(true));
        List<String> asked = new ArrayList<>();
        Ids ids = (name, type) -> {
            if (type.equals("style") && name.equals(AccentTheme.paletteStyle(AccentHue.TEAL))) return android.R.style.TextAppearance;
            if (type.equals("style") && (name.startsWith("HushGramClassicBackgrounds_") || name.startsWith("HushGramAccentBackgrounds_"))) {
                asked.add(name);
                return android.R.style.TextAppearance_Small;
            }
            if (type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE)) return android.R.attr.colorForeground;
            if (type.equals("attr") && name.equals(AccentTheme.BACKGROUND_ATTRIBUTE)) return android.R.attr.colorBackground;
            return type.equals("attr") ? 0x7f990005 : 0;
        };
        Context light = new ContextThemeWrapper(themed(THEMED + ".classic.light", ids), android.R.style.Theme_Material_Light);
        AccentTheme.apply(light, "igds_color_link");
        assertEquals("off", List.of(), asked);
        assertEquals(List.of(), HookStatus.report());

        Settings.CLASSIC_COLORS.save(true);
        AccentTheme.apply(light, "igds_color_link");
        assertEquals("with Accent color off", List.of(AccentTheme.classicStyle(false)), asked);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.CLASSIC + " 1"),
                HookStatus.report());

        asked.clear();
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_BACKGROUNDS.save(true);
        HookStatus.clear();
        Context dark = new ContextThemeWrapper(themed(THEMED + ".classic.dark", ids), android.R.style.Theme_Material);
        AccentTheme.apply(dark, "igds_color_link");
        assertEquals("the classic style goes on last", List.of(AccentTheme.backgroundStyle(AccentHue.TEAL, true),
                AccentTheme.classicStyle(true)), asked);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.NO_BLUE + " 1, "
                + AccentTheme.BACKGROUNDS + " 1, " + AccentTheme.CLASSIC + " 1"), HookStatus.report());
    }

    /**
     * Classic colors lays nothing on a theme painting no solid background of its own, says so on a build
     * without its styles, and looks nothing up while HushGram is paused (#639).
     */
    @Test
    public void theClassicStyleKeepsAViewersBackgroundAndStaysOffWhilePaused() {
        Settings.CLASSIC_COLORS.save(true);
        Context viewer = new ContextThemeWrapper(themed(THEMED + ".classic.viewer", (name, type) -> {
            if (type.equals("style") && name.startsWith("HushGramClassicBackgrounds_")) return android.R.style.TextAppearance_Small;
            if (type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE)) return android.R.attr.colorForeground;
            return type.equals("attr") && name.equals(AccentTheme.BACKGROUND_ATTRIBUTE) ? android.R.attr.windowFrame : 0;
        }), android.R.style.Theme_Material);
        AccentTheme.apply(viewer, null);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.BACKGROUNDS_KEPT + " 1"),
                HookStatus.report());

        HookStatus.clear();
        AccentTheme.apply(themed(THEMED + ".classic.missing", (name, type) -> 0), null);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 1, 0 found, 0 missing. Counted: " + AccentTheme.CLASSIC_MISSING + " 1"),
                HookStatus.report());

        HookStatus.clear();
        List<String> asked = new ArrayList<>();
        Context light = new ContextThemeWrapper(themed(THEMED + ".classic.paused", (name, type) -> {
            asked.add(type + "/" + name);
            if (type.equals("style")) return android.R.style.TextAppearance_Small;
            if (type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE)) return android.R.attr.colorForeground;
            return type.equals("attr") && name.equals(AccentTheme.BACKGROUND_ATTRIBUTE) ? android.R.attr.colorBackground : 0;
        }), android.R.style.Theme_Material_Light);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        AccentTheme.apply(light, null);
        assertEquals(List.of(), asked);
        assertEquals(List.of(), HookStatus.report());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        AccentTheme.apply(light, null);
        assertTrue("resumed, " + asked, asked.contains("style/" + AccentTheme.classicStyle(false)));
    }

    /**
     * With Accent color off, Classic colors on a dark theme hands that theme's surfaces to Compose's
     * dark grays, so the message list and search field match the page around them (#639, #577).
     */
    @Test
    public void classicColorsHandsADarkThemesSurfacesToCompose() {
        Settings.CLASSIC_COLORS.save(true);
        AccentTheme.darkSurfacesForTests(null);
        try {
            Context dark = new ContextThemeWrapper(themed(THEMED + ".classic.compose", (name, type) -> {
                if (type.equals("style") && name.startsWith("HushGramClassicBackgrounds_")) return android.R.style.TextAppearance_Small;
                if (type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE)) return android.R.attr.colorForeground;
                return type.equals("attr") && name.startsWith("igds_color_") ? android.R.attr.colorBackground : 0;
            }), android.R.style.Theme_Material);
            long raised = (long) 0xff191c1f << 32;
            assertEquals("before a classic theme is seen", raised, AccentTheme.composeSurface(raised));

            AccentTheme.apply(dark, null);

            TypedValue page = new TypedValue();
            assertTrue(dark.getTheme().resolveAttribute(android.R.attr.colorBackground, page, true));
            assertTrue("a page color unlike Compose's own", (page.data & 0xffffff) != 0x191c1f);
            assertEquals((long) (0xff000000 | page.data & 0xffffff) << 32, AccentTheme.composeSurface(raised));
        } finally {
            AccentTheme.darkSurfacesForTests(null);
        }
    }

    /**
     * Classic colors on a light theme hands that theme's page to Compose's white, so the message list
     * and its search field match the off-white View screens, and the dark grays a light screen writes
     * its text with stay (#639).
     */
    @Test
    public void classicColorsHandsALightThemesPageToCompose() {
        Settings.CLASSIC_COLORS.save(true);
        AccentTheme.darkSurfacesForTests(null);
        AccentTheme.lightSurfacesForTests(null);
        try {
            Context light = new ContextThemeWrapper(themed(THEMED + ".classic.compose.light", (name, type) -> {
                if (type.equals("style") && name.startsWith("HushGramClassicBackgrounds_")) return android.R.style.TextAppearance_Small;
                if (type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE)) return android.R.attr.colorForeground;
                if (type.equals("attr") && name.equals("igds_color_secondary_background")) return android.R.attr.colorForeground;
                return type.equals("attr") && name.startsWith("igds_color_") ? android.R.attr.colorBackground : 0;
            }), android.R.style.Theme_Material_Light);
            long white = (long) 0xffffffff << 32;
            long text = (long) 0xff0c1014 << 32;
            assertEquals("before a classic theme is seen", white, AccentTheme.composeSurface(white));

            AccentTheme.apply(light, null);

            TypedValue page = new TypedValue();
            assertTrue(light.getTheme().resolveAttribute(android.R.attr.colorBackground, page, true));
            TypedValue top = new TypedValue();
            assertTrue(light.getTheme().resolveAttribute(android.R.attr.colorForeground, top, true));
            assertTrue("a page color unlike Compose's own", (page.data & 0xffffff) != 0xffffff);
            assertTrue("a top shade unlike the page", (top.data & 0xffffff) != (page.data & 0xffffff));
            assertEquals((long) (0xff000000 | page.data & 0xffffff) << 32, AccentTheme.composeSurface(white));
            assertEquals("the search field takes the top shade", (long) (0xff000000 | top.data & 0xffffff) << 32,
                    AccentTheme.composeSurface((long) 0xfff0f2f5 << 32));
            assertEquals("the text", text, AccentTheme.composeSurface(text));
        } finally {
            AccentTheme.darkSurfacesForTests(null);
            AccentTheme.lightSurfacesForTests(null);
        }
    }

    /**
     * Instagram builds its Compose palettes on a thread of its own as it starts, before the first
     * screen's theme is styled, so with Classic colors on the grays come straight from the classic
     * style for the kind of screen Instagram's Dark mode choice will show, and the other kind's stay
     * (#528).
     */
    @Test
    public void composeReadsTheClassicStyleBeforeAnyScreenIsStyled() {
        Settings.CLASSIC_COLORS.save(true);
        AccentTheme.darkSurfacesForTests(null);
        AccentTheme.lightSurfacesForTests(null);
        String pkg = THEMED + ".classic.early";
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences(pkg + "_preferences", Context.MODE_PRIVATE);
        try {
            Context app = themed(pkg, (name, type) -> {
                if (type.equals("style") && name.equals("HushGramClassicBackgrounds_dark")) return android.R.style.Theme_Material;
                if (type.equals("style") && name.equals("HushGramClassicBackgrounds_light")) return android.R.style.Theme_Material_Light;
                return type.equals("attr") && name.startsWith("igds_color_") ? android.R.attr.colorBackground : 0;
            });
            long black = (long) 0xff000000 << 32;
            long white = (long) 0xffffffff << 32;
            long darkPage = (long) (0xff000000 | page(app, android.R.style.Theme_Material)) << 32;
            long lightPage = (long) (0xff000000 | page(app, android.R.style.Theme_Material_Light)) << 32;
            assertTrue("page colors unlike Compose's own", darkPage != black && lightPage != white);

            prefs.edit().putInt(AccentTheme.DARK_MODE_KEY, AccentTheme.DARK_MODE_ON).commit();
            assertEquals("Dark mode on", darkPage, AccentTheme.composeSurface(black, app));
            assertEquals("a light screen's white with Dark mode on", white, AccentTheme.composeSurface(white, app));
            AccentTheme.darkSurfacesForTests(null);

            prefs.edit().putInt(AccentTheme.DARK_MODE_KEY, AccentTheme.DARK_MODE_OFF).commit();
            assertEquals("a light screen's black text with Dark mode off", black, AccentTheme.composeSurface(black, app));
            assertEquals("Dark mode off", lightPage, AccentTheme.composeSurface(white, app));
            AccentTheme.lightSurfacesForTests(null);

            prefs.edit().clear().commit();
            assertEquals("the system's light mode", black, AccentTheme.composeSurface(black, app));
            assertEquals("the system's light mode", lightPage, AccentTheme.composeSurface(white, app));
            AccentTheme.lightSurfacesForTests(null);

            Settings.CLASSIC_COLORS.save(false);
            prefs.edit().putInt(AccentTheme.DARK_MODE_KEY, AccentTheme.DARK_MODE_ON).commit();
            assertEquals("Classic colors off", black, AccentTheme.composeSurface(black, app));
        } finally {
            prefs.edit().clear().commit();
            AccentTheme.darkSurfacesForTests(null);
            AccentTheme.lightSurfacesForTests(null);
        }
    }

    /** The RGB of the window background [style] gives a theme of [context]'s resources. */
    private static int page(Context context, int style) {
        Resources.Theme theme = context.getResources().newTheme();
        theme.applyStyle(style, true);
        TypedValue value = new TypedValue();
        assertTrue(theme.resolveAttribute(android.R.attr.colorBackground, value, true));
        return value.data & 0xffffff;
    }

    /**
     * Meta's own menus, the account switcher among them: its blues take the picked fill on either
     * screen, its dark grays the dark theme's shades with the accent backgrounds on, its white page and
     * wash the light theme's with Classic colors on, and its text grays stay (#187).
     */
    @Test
    public void metasMenuColorsTakeTheAccent() {
        int[] dark = {0xff101820, 0xff182030, 0xff203040};
        int[] light = {0xfffafafa, 0xfff0f0f0, 0xffe8e8e8};
        AccentTheme.darkSurfacesForTests(dark);
        AccentTheme.lightSurfacesForTests(light);
        try {
            assertEquals("the switch off", 0xff0064e0, AccentTheme.dspLight(0xff0064e0));
            assertEquals("the switch off", 0xff1f1f20, AccentTheme.dspDark(0xff1f1f20));

            Settings.ACCENT_COLOR.save(true);
            Settings.ACCENT_HUE.save(AccentHue.PINK);
            int fill = 0xff000000 | AccentHue.PINK.fill & 0xffffff;
            assertEquals(fill, AccentTheme.dspLight(0xff0064e0));
            assertEquals(fill, AccentTheme.dspDark(0xff0082fb));
            assertEquals("the blue's own alpha", 0x80000000 | fill & 0xffffff, AccentTheme.dspDark(0x800082fb));
            assertEquals("backgrounds off", 0xff1f1f20, AccentTheme.dspDark(0xff1f1f20));

            Settings.ACCENT_BACKGROUNDS.save(true);
            assertEquals("the menu", dark[1], AccentTheme.dspDark(0xff1f1f20));
            assertEquals("the wash", dark[2], AccentTheme.dspDark(0xff28292b));
            assertEquals("a scrim keeps its alpha", 0xb2000000 | dark[1] & 0xffffff, AccentTheme.dspDark(0xb21f1f20));
            assertEquals("the light text gray", 0xffd1d3d5, AccentTheme.dspDark(0xffd1d3d5));
            assertEquals("a dark gray on a light screen is text", 0xff28292b, AccentTheme.dspLight(0xff28292b));
            assertEquals("a light page without Classic colors", 0xffffffff, AccentTheme.dspLight(0xffffffff));

            Settings.CLASSIC_COLORS.save(true);
            assertEquals(light[0], AccentTheme.dspLight(0xffffffff));
            assertEquals(light[2], AccentTheme.dspLight(0xfff3f4f5));
            assertEquals("the white page's share of a see-through white", 0x33000000 | light[0] & 0xffffff, AccentTheme.dspLight(0x33ffffff));

            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            assertEquals("paused", 0xff0064e0, AccentTheme.dspLight(0xff0064e0));
        } finally {
            AccentTheme.darkSurfacesForTests(null);
            AccentTheme.lightSurfacesForTests(null);
        }
    }

    /**
     * An opened chat's default background, a bottom layer of Instagram's page black (or white on a light
     * screen) and a strip of the header's black behind the status bar, takes the painted page shade, the
     * strip as a copy of its shape, and a chat theme of its own keeps its look (#528).
     */
    @Test
    public void aChatsPageBlackBackgroundTakesThePageShade() {
        int[] dark = {0xff101b2d, 0xff182030, 0xff203040};
        int[] light = {0xfffafafa, 0xfff0f0f0, 0xffe8e8e8};
        AccentTheme.darkSurfacesForTests(dark);
        AccentTheme.lightSurfacesForTests(light);
        try {
            LayerDrawable off = chat(0xff000000);
            AccentTheme.chatBackground(off);
            assertEquals("the switches off", List.of(0xff000000, 0xff000000), colors(off));

            Settings.CLASSIC_COLORS.save(true);
            LayerDrawable black = chat(0xff000000);
            ShapeDrawable strip = (ShapeDrawable) black.getDrawable(1);
            Drawable above = black.getDrawable(2);
            AccentTheme.chatBackground(black);
            assertEquals(List.of(dark[0], dark[0]), colors(black));
            assertEquals("the strip kept for the next chat", 0xff000000, strip.getPaint().getColor());
            // Android 9 clones the shape with the copy and later versions share it.
            assertEquals("the strip's shape", strip.getShape().getClass(), ((ShapeDrawable) black.getDrawable(1)).getShape().getClass());
            assertEquals("the chat's own layer above them", above, black.getDrawable(2));
            LayerDrawable instagramsBlack = chat(0xff0c1014);
            AccentTheme.chatBackground(instagramsBlack);
            assertEquals(List.of(dark[0], dark[0]), colors(instagramsBlack));
            LayerDrawable white = chat(0xffffffff);
            AccentTheme.chatBackground(white);
            assertEquals(List.of(light[0], light[0]), colors(white));
            for (int own : new int[] {0xff3a1f5d, 0x80000000, 0xff191c1f}) {
                LayerDrawable themed = chat(own);
                AccentTheme.chatBackground(themed);
                assertEquals("a chat theme's own color", List.of(own, own), colors(themed));
            }
            Drawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xff000000, 0xff3a1f5d});
            LayerDrawable pictured = new LayerDrawable(new Drawable[] {gradient});
            AccentTheme.chatBackground(pictured);
            assertEquals("a gradient", gradient, pictured.getDrawable(0));
            AccentTheme.chatBackground(new ColorDrawable(0xff000000));
            AccentTheme.chatBackground(new LayerDrawable(new Drawable[0]));
            AccentTheme.chatBackground(null);

            Settings.CLASSIC_COLORS.save(false);
            Settings.ACCENT_COLOR.save(true);
            Settings.ACCENT_HUE.save(AccentHue.PINK);
            Settings.ACCENT_BACKGROUNDS.save(true);
            LayerDrawable accent = chat(0xff000000);
            AccentTheme.chatBackground(accent);
            assertEquals("the accent's backgrounds", List.of(dark[0], dark[0]), colors(accent));
            LayerDrawable lightAccent = chat(0xffffffff);
            AccentTheme.chatBackground(lightAccent);
            assertEquals("a light page without Classic colors", List.of(0xffffffff, 0xffffffff), colors(lightAccent));

            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            LayerDrawable paused = chat(0xff000000);
            AccentTheme.chatBackground(paused);
            assertEquals("paused", List.of(0xff000000, 0xff000000), colors(paused));
        } finally {
            AccentTheme.darkSurfacesForTests(null);
            AccentTheme.lightSurfacesForTests(null);
        }
    }

    /**
     * An opened chat's header row, given the default theme's plain black or white, takes the page shade
     * after Instagram sets it, and a theme's own header color stays (#528).
     */
    @Test
    public void aChatsPageBlackHeaderTakesThePageShade() {
        int[] dark = {0xff101b2d, 0xff182030, 0xff203040};
        int[] light = {0xfffafafa, 0xfff0f0f0, 0xffe8e8e8};
        AccentTheme.darkSurfacesForTests(dark);
        AccentTheme.lightSurfacesForTests(light);
        try {
            View header = new View(RuntimeEnvironment.getApplication());
            header.setBackgroundColor(0xff000000);
            AccentTheme.chatHeader(header);
            assertEquals("the switches off", 0xff000000, ((ColorDrawable) header.getBackground()).getColor());

            Settings.CLASSIC_COLORS.save(true);
            AccentTheme.chatHeader(header);
            assertEquals(dark[0], ((ColorDrawable) header.getBackground()).getColor());
            header.setBackgroundColor(0xffffffff);
            AccentTheme.chatHeader(header);
            assertEquals(light[0], ((ColorDrawable) header.getBackground()).getColor());
            header.setBackgroundColor(0xff3a1f5d);
            AccentTheme.chatHeader(header);
            assertEquals("a chat theme's own header", 0xff3a1f5d, ((ColorDrawable) header.getBackground()).getColor());
            Drawable gradient = new GradientDrawable();
            header.setBackground(gradient);
            AccentTheme.chatHeader(header);
            assertEquals("a gradient", gradient, header.getBackground());
            AccentTheme.chatHeader(null);
        } finally {
            AccentTheme.darkSurfacesForTests(null);
            AccentTheme.lightSurfacesForTests(null);
        }
    }

    /**
     * A chat's background the way Instagram builds it: the theme's color, a strip of the header's color
     * behind the status bar, and a layer of the chat's own.
     */
    private static LayerDrawable chat(int color) {
        ShapeDrawable strip = new ShapeDrawable(new RectShape());
        strip.getPaint().setColor(color);
        return new LayerDrawable(new Drawable[] {new ColorDrawable(color), strip, new GradientDrawable()});
    }

    /** The colors of a chat's page and of its strip. */
    private static List<Integer> colors(LayerDrawable layers) {
        return List.of(((ColorDrawable) layers.getDrawable(0)).getColor(), ((ShapeDrawable) layers.getDrawable(1)).getPaint().getColor());
    }

    /** An attribute the theme doesn't hold, one holding a size or one that isn't opaque isn't a background of its own (#187). */
    @Test
    public void onlyASolidColorIsABackgroundOfItsOwn() {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material);
        Resources.Theme theme = context.getTheme();
        assertTrue(AccentTheme.paintsItsOwnBackground(theme, android.R.attr.colorForeground));
        assertFalse("holds a size", AccentTheme.paintsItsOwnBackground(theme, android.R.attr.listPreferredItemHeight));
        assertFalse("not in the theme", AccentTheme.paintsItsOwnBackground(theme, 0x7f990099));
        assertFalse("see-through", AccentTheme.paintsItsOwnBackground(theme, android.R.attr.windowFrame));
    }

    /**
     * Home's newer header takes the icon color its theme holds for its heart and Create buttons with
     * both switches on (#8), and keeps its own with either off, paused, or no icon attribute.
     */
    @Test
    public void homesNewerHeaderTakesTheThemesIconColor() {
        Context light = new ContextThemeWrapper(themed(THEMED + ".header", (name, type) ->
                type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE) ? android.R.attr.colorForeground : 0),
                android.R.style.Theme_Material_Light);
        TypedValue held = new TypedValue();
        assertTrue(light.getTheme().resolveAttribute(android.R.attr.colorForeground, held, true));
        int own = 0xfff5f5f5;
        assertFalse("the theme's icon color differs from the header's own", held.data == own);

        assertEquals("off", own, AccentTheme.headerIcons(light, own));
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        assertEquals("icons off", own, AccentTheme.headerIcons(light, own));
        Settings.ACCENT_ICONS.save(true);
        assertEquals(held.data, AccentTheme.headerIcons(light, own));
        assertEquals("no context", own, AccentTheme.headerIcons(null, own));
        Context bare = new ContextThemeWrapper(themed(THEMED + ".header.bare", (name, type) -> 0), android.R.style.Theme_Material_Light);
        assertEquals("no icon attribute", own, AccentTheme.headerIcons(bare, own));
        assertEquals("already the icon color, not counted", held.data, AccentTheme.headerIcons(light, held.data));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", own, AccentTheme.headerIcons(light, own));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.HEADER_ICONS + " 1"));
    }

    /**
     * The theme Litho's resource resolver keeps takes the picked color's icon style with both switches
     * on, so the feed's post buttons read the accent (#724). Each theme is looked at once, one already
     * holding the style's color isn't counted, a viewer's keeps its own, a build without the styles says
     * so, and with either switch off, paused or no context nothing changes.
     */
    @Test
    public void lithosThemeTakesTheIconStyleOnce() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Ids ids = (name, type) -> {
            if (type.equals("style") && name.startsWith("HushGramAccentIcons_TEAL_")) return android.R.style.Theme_Material;
            if (type.equals("attr") && (name.equals(AccentTheme.ICON_ATTRIBUTE) || name.equals(AccentTheme.GLYPH_ATTRIBUTE))) {
                return android.R.attr.colorForeground;
            }
            return type.equals("attr") && name.equals(AccentTheme.BACKGROUND_ATTRIBUTE) ? android.R.attr.colorBackground : 0;
        };
        int accent = foreground(new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material));
        Context light = new ContextThemeWrapper(themed(THEMED + ".litho", ids), android.R.style.Theme_Material_Light);
        int own = foreground(light);
        assertFalse("the light theme's icon color differs from the style's", own == accent);

        AccentTheme.lithoIcons(light);
        assertEquals("icons off", own, foreground(light));
        Settings.ACCENT_ICONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        AccentTheme.lithoIcons(light);
        assertEquals("paused", own, foreground(light));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        AccentTheme.lithoIcons(null);
        assertEquals("nothing counted yet", List.of(), HookStatus.report());

        AccentTheme.lithoIcons(light);
        assertEquals(accent, foreground(light));
        light.getTheme().applyStyle(android.R.style.Theme_Material_Light, true);
        AccentTheme.lithoIcons(light);
        assertEquals("looked at once", own, foreground(light));
        Context another = new ContextThemeWrapper(themed(THEMED + ".litho", ids), android.R.style.Theme_Material_Light);
        AccentTheme.lithoIcons(another);
        assertEquals("another tree's theme", accent, foreground(another));

        Context dark = new ContextThemeWrapper(themed(THEMED + ".litho", ids), android.R.style.Theme_Material);
        AccentTheme.lithoIcons(dark);
        assertEquals("already the dark style's color", accent, foreground(dark));
        Context viewer = new ContextThemeWrapper(themed(THEMED + ".litho.viewer", (name, type) ->
                type.equals("attr") && name.equals(AccentTheme.BACKGROUND_ATTRIBUTE) ? android.R.attr.textColorPrimary : ids.of(name, type)),
                android.R.style.Theme_Material_Light);
        AccentTheme.lithoIcons(viewer);
        assertEquals("over media", own, foreground(viewer));
        AccentTheme.lithoIcons(new ContextThemeWrapper(themed(THEMED + ".litho.missing", (name, type) -> 0),
                android.R.style.Theme_Material_Light));

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.LITHO_ICONS + " 2"));
        assertTrue(report, report.contains(AccentTheme.ICONS_MISSING + " 1"));
    }

    /**
     * The repost button's animation, black or white in its file, takes the icon color through an
     * SRC_IN layer once the Litho theme it loads on carries the icon style (#724), and the layer is
     * closed when it's done drawing. Off, paused, over media, on a theme without the style, for any
     * other animation or after a load that failed, it draws as Instagram's.
     */
    @Test
    public void theRepostAnimationTakesTheIconColorWhereTheThemeHasIt() {
        int repost = 0x7f120105;
        int unrepost = 0x7f120113;
        int other = 0x7f120200;
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Ids ids = (name, type) -> {
            if (type.equals("style") && name.startsWith("HushGramAccentIcons_TEAL_")) return android.R.style.Theme_Material;
            if (type.equals("attr") && (name.equals(AccentTheme.ICON_ATTRIBUTE) || name.equals(AccentTheme.GLYPH_ATTRIBUTE))) {
                return android.R.attr.colorForeground;
            }
            if (type.equals("attr") && name.equals(AccentTheme.BACKGROUND_ATTRIBUTE)) return android.R.attr.colorBackground;
            if (type.equals("raw") && name.equals("repost_light")) return repost;
            return type.equals("raw") && name.equals("unrepost_dark") ? unrepost : 0;
        };
        int accent = foreground(new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material));
        Context light = new ContextThemeWrapper(themed(THEMED + ".repost", ids), android.R.style.Theme_Material_Light);

        assertNull("icons off", layer(light, repost));
        Settings.ACCENT_ICONS.save(true);
        assertNull("the theme hasn't the icon style", layer(light, repost));
        AccentTheme.lithoIcons(light);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull("paused", layer(light, repost));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertNull("another animation", layer(light, other));
        assertNull("no context", layer(null, repost));

        PorterDuffColorFilter tint = new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN);
        assertEquals("repost", tint, layer(light, repost));
        assertEquals("unrepost", tint, layer(light, unrepost));
        Context dark = new ContextThemeWrapper(themed(THEMED + ".repost", ids), android.R.style.Theme_Material);
        assertEquals("a dark theme already in the dark style's color", tint, layer(dark, unrepost));
        Context viewer = new ContextThemeWrapper(themed(THEMED + ".repost.viewer", (name, type) ->
                type.equals("attr") && name.equals(AccentTheme.BACKGROUND_ATTRIBUTE) ? android.R.attr.textColorPrimary : ids.of(name, type)),
                android.R.style.Theme_Material);
        assertNull("over media", layer(viewer, repost));

        AccentTheme.repostAnimation(light, repost);
        AccentTheme.repostAnimation((Drawable) null);
        Animation late = new Animation();
        AccentTheme.repostAnimation(late);
        Layers canvas = new Layers();
        AccentTheme.repostDraw(late, canvas);
        assertNull("a load that failed leaves nothing for the next one", canvas.paint);

        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.REPOST_ICONS + " 3"));
    }

    /**
     * Loads [raw] on [context] the way the patched Keyframes loader does, draws the animation it hands
     * back, and returns the color filter of the layer it was drawn in, null when it wasn't. A layer is
     * checked to reach a quarter past the icon and to be closed again.
     */
    private static ColorFilter layer(Context context, int raw) {
        AccentTheme.repostAnimation(context, raw);
        Animation animation = new Animation();
        animation.setBounds(0, 0, 24, 24);
        AccentTheme.repostAnimation(animation);
        Layers canvas = new Layers();
        AccentTheme.repostDraw(animation, canvas);
        AccentTheme.repostDrawn(animation, canvas);
        if (canvas.paint == null) {
            assertEquals("nothing to close", -1, canvas.restoredTo);
            return null;
        }
        assertEquals(List.of(-6f, -6f, 30f, 30f), canvas.bounds);
        assertEquals("closed", canvas.saved, canvas.restoredTo);
        return canvas.paint.getColorFilter();
    }

    /** A Keyframes animation as the loader hands it back, drawing nothing here. */
    private static final class Animation extends Drawable {
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter colorFilter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A canvas that keeps the layer opened on it and the save count it's put back to. */
    private static final class Layers extends Canvas {
        Paint paint;
        List<Float> bounds;
        final int saved = 7;
        int restoredTo = -1;

        @Override
        public int saveLayer(float left, float top, float right, float bottom, Paint layerPaint) {
            paint = layerPaint;
            bounds = List.of(left, top, right, bottom);
            return saved;
        }

        @Override
        public void restoreToCount(int count) {
            restoredTo = count;
        }
    }

    /** The plain color [context]'s theme holds for {@code android:colorForeground}. */
    private static int foreground(Context context) {
        TypedValue value = new TypedValue();
        assertTrue(context.getTheme().resolveAttribute(android.R.attr.colorForeground, value, true));
        return value.data;
    }

    /**
     * A tab's icon told Reels' white takes the picked color's shade for a dark screen, and told the
     * dark text color Instagram resets to after Reels takes its shade for a light one, alpha kept, with
     * both switches on (#663). Either switch off or paused leaves the color as told, and a missing
     * unselected color stays missing.
     */
    @Test
    public void tabIconsTakeThePickedShadeForTheColorTheyreTold() {
        int white = 0xffffffff;
        int dark = 0xff0c1014;
        int faded = 0x99ffffff;
        assertEquals("off", white, AccentTheme.tabIcons(white));
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        assertEquals("icons off", white, AccentTheme.tabIcons(white));
        Settings.ACCENT_ICONS.save(true);

        int onDark = 0xff000000 | AccentColor.swap(0xff85a1ff, AccentHue.TEAL);
        int onLight = 0xff000000 | AccentColor.swap(0xff3143e3, AccentHue.TEAL);
        assertFalse("the two shades differ", onDark == onLight);
        assertEquals("white on the black Reels bar", onDark, AccentTheme.tabIcons(white));
        assertEquals("the text color after Reels", onLight, AccentTheme.tabIcons(dark));
        assertEquals("alpha kept", (faded & 0xff000000) | (onDark & 0xffffff), AccentTheme.tabIcons(faded));
        assertEquals("boxed", Integer.valueOf(onLight), AccentTheme.tabIcons(Integer.valueOf(dark)));
        assertNull("no unselected color", AccentTheme.tabIcons((Integer) null));
        assertEquals("already the shade, not counted", onDark, AccentTheme.tabIcons(onDark));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", white, AccentTheme.tabIcons(white));
        assertEquals("paused, boxed", Integer.valueOf(dark), AccentTheme.tabIcons(Integer.valueOf(dark)));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.TAB_ICONS + " 4"));
    }

    /**
     * A reel's button column reads Instagram's white for its counts over the video, and with Reel
     * buttons in the accent color on that white becomes the picked color's shade for a dark screen, the
     * dark text color of its look under the video the shade for a light one, alpha kept (#187). The
     * switch off, Accent color off or paused leaves the color as read, and the icons switch alone
     * doesn't reach the column.
     */
    @Test
    public void reelButtonsTakeThePickedShadeForTheColorTheColumnReads() {
        int white = 0xffffffff;
        int dark = 0xff0c1014;
        int faded = 0x99ffffff;
        Settings.ACCENT_REELS.save(true);
        assertEquals("accent off", white, AccentTheme.reelButtons(white));
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_REELS.save(false);
        Settings.ACCENT_ICONS.save(true);
        assertEquals("reel buttons off", white, AccentTheme.reelButtons(white));
        Settings.ACCENT_REELS.save(true);

        int onDark = 0xff000000 | AccentColor.swap(0xff85a1ff, AccentHue.TEAL);
        int onLight = 0xff000000 | AccentColor.swap(0xff3143e3, AccentHue.TEAL);
        assertEquals("white over the video", onDark, AccentTheme.reelButtons(white));
        assertEquals("the text color under the video", onLight, AccentTheme.reelButtons(dark));
        assertEquals("alpha kept", (faded & 0xff000000) | (onDark & 0xffffff), AccentTheme.reelButtons(faded));
        assertEquals("already the shade, not counted", onDark, AccentTheme.reelButtons(onDark));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", white, AccentTheme.reelButtons(white));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.REEL_BUTTONS + " 3"));
    }

    /**
     * Over the video the column builds no filter for its icons, so they draw the white they're made in.
     * With Reel buttons in the accent color on it gets one that multiplies by the picked color's shade
     * for a dark screen: white turns into that shade, black stays black and a see-through pixel stays
     * see-through, and every icon shares it until the color changes (#187). A filter Instagram built is
     * kept, and the switch off or paused leaves none.
     */
    @Test
    public void reelIconsTakeAFilterOfThePickedShadeOverTheVideo() {
        ColorFilter own = new PorterDuffColorFilter(0xff123456, PorterDuff.Mode.SRC_IN);
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        assertNull("reel buttons off", AccentTheme.reelButtons((ColorFilter) null));
        Settings.ACCENT_REELS.save(true);
        assertTrue("Instagram's own kept", own == AccentTheme.reelButtons(own));

        ColorFilter teal = AccentTheme.reelButtons((ColorFilter) null);
        int shade = AccentColor.swap(0xff85a1ff, AccentHue.TEAL);
        assertTrue("a multiplying filter", teal instanceof android.graphics.LightingColorFilter);
        assertEquals("by the shade", shade & 0xffffff, ((android.graphics.LightingColorFilter) teal).getColorMultiply());
        assertEquals("adding nothing", 0, ((android.graphics.LightingColorFilter) teal).getColorAdd());
        assertTrue("shared", teal == AccentTheme.reelButtons((ColorFilter) null));
        Settings.ACCENT_HUE.save(AccentHue.ORANGE);
        ColorFilter orange = AccentTheme.reelButtons((ColorFilter) null);
        assertEquals("a new one for a new color", AccentColor.swap(0xff85a1ff, AccentHue.ORANGE) & 0xffffff,
                ((android.graphics.LightingColorFilter) orange).getColorMultiply());

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertNull("paused", AccentTheme.reelButtons((ColorFilter) null));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.REEL_BUTTONS + " 3"));
    }

    /**
     * The Messages folder chip's unread dot, which Compose paints red from a palette in the code, takes
     * the picked color's fill with both switches on, alpha kept (#671). Either switch off or paused
     * leaves it red, and a color outside sRGB comes back opaque in the fill.
     */
    @Test
    public void theFolderChipsDotTakesThePickedFill() {
        long red = (long) 0xffff3040 << 32;
        long faded = (long) 0x80ff3040 << 32;
        assertEquals("off", red, AccentTheme.composeBadge(red));
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        assertEquals("badges off", red, AccentTheme.composeBadge(red));
        Settings.ACCENT_BADGES.save(true);

        int fill = AccentColor.swap(0xff4a5df9, AccentHue.TEAL) & 0xffffff;
        assertFalse("teal's fill isn't the red", fill == 0xff3040);
        assertEquals("the dot", (long) (0xff000000 | fill) << 32, AccentTheme.composeBadge(red));
        assertEquals("alpha kept", (long) (0x80000000 | fill) << 32, AccentTheme.composeBadge(faded));
        assertEquals("outside sRGB", (long) (0xff000000 | fill) << 32, AccentTheme.composeBadge(red | 7));
        assertEquals("already the fill, not counted", (long) (0xff000000 | fill) << 32,
                AccentTheme.composeBadge((long) (0xff000000 | fill) << 32));

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals("paused", red, AccentTheme.composeBadge(red));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.COMPOSE_BADGES + " 3"));
    }

    /**
     * Home's older header bakes the primary text color into each icon, so each one gets the theme's
     * icon color laid over it with both switches on (#8), and none with either off, paused, or no icon
     * attribute.
     */
    @Test
    public void homesOlderHeaderLaysTheThemesIconColorOverEachIcon() {
        Context light = new ContextThemeWrapper(themed(THEMED + ".older", (name, type) ->
                type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE) ? android.R.attr.colorForeground : 0),
                android.R.style.Theme_Material_Light);
        TypedValue held = new TypedValue();
        assertTrue(light.getTheme().resolveAttribute(android.R.attr.colorForeground, held, true));
        Icon icon = new Icon();

        AccentTheme.headerIcon(light, icon);
        assertNull("off", icon.filter);
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        AccentTheme.headerIcon(light, icon);
        assertNull("icons off", icon.filter);
        Settings.ACCENT_ICONS.save(true);
        AccentTheme.headerIcon(null, icon);
        assertNull("no context", icon.filter);
        AccentTheme.headerIcon(new ContextThemeWrapper(themed(THEMED + ".older.bare", (name, type) -> 0),
                android.R.style.Theme_Material_Light), icon);
        assertNull("no icon attribute", icon.filter);
        AccentTheme.headerIcon(light, null);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        AccentTheme.headerIcon(light, icon);
        assertNull("paused", icon.filter);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        AccentTheme.headerIcon(light, icon);
        assertEquals(new PorterDuffColorFilter(held.data, PorterDuff.Mode.SRC_IN), icon.filter);
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.HEADER_ICONS + " 1"));
    }

    /** A drawable that keeps the color filter it's given, as Instagram's header icon hands it to its glyph. */
    private static final class Icon extends Drawable {
        ColorFilter filter;

        @Override public void setColorFilter(ColorFilter colorFilter) { filter = colorFilter; }
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Paused, the icon style never goes on with both switches on, so the glyph colors it also sets
     * (#8) stay Instagram's, and nothing is looked up or counted.
     */
    @Test
    public void pausedTheIconStyleNeverGoesOn() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        Settings.ACCENT_ICONS.save(true);
        List<String> asked = new ArrayList<>();
        Context light = new ContextThemeWrapper(themed(THEMED + ".icons.paused", (name, type) -> {
            asked.add(type + "/" + name);
            if (type.equals("style")) return android.R.style.TextAppearance_Small;
            return type.equals("attr") && name.equals(AccentTheme.ICON_ATTRIBUTE) ? android.R.attr.colorForeground : 0;
        }), android.R.style.Theme_Material_Light);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        AccentTheme.apply(light, "igds_color_link");
        assertEquals(List.of(), asked);
        assertEquals(List.of(), HookStatus.report());

        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        AccentTheme.apply(light, "igds_color_link");
        assertTrue("resumed, " + asked, asked.contains("style/" + AccentTheme.iconStyle(AccentHue.TEAL, false)));
    }

    @Test
    public void aThemeIsDarkWhenItsIconColorReadsLight() {
        Context app = RuntimeEnvironment.getApplication();
        Resources.Theme light = app.getResources().newTheme();
        light.applyStyle(android.R.style.Theme_Material_Light, true);
        Resources.Theme dark = app.getResources().newTheme();
        dark.applyStyle(android.R.style.Theme_Material, true);
        assertFalse(AccentTheme.dark(app, light, android.R.attr.colorForeground));
        assertTrue(AccentTheme.dark(app, dark, android.R.attr.colorForeground));
        assertFalse("day, nothing held", AccentTheme.dark(app, light, 0x7f990005));
        RuntimeEnvironment.setQualifiers("+night");
        assertTrue("night, nothing held", AccentTheme.dark(app, light, 0x7f990005));

        assertTrue(AccentTheme.readsLight(0xffffffff));
        assertFalse(AccentTheme.readsLight(0xff0c1014));
        // A theme styled twice keeps its shade: the dark one still reads as a dark theme's.
        for (AccentHue hue : AccentHue.values()) {
            if (hue == AccentHue.BLUE || hue == AccentHue.WALLPAPER || hue == AccentHue.CUSTOM) continue;
            assertTrue(hue.name(), AccentTheme.readsLight(hue.linkOnDark));
            assertFalse(hue.name(), AccentTheme.readsLight(hue.linkOnLight));
        }
    }

    /**
     * The default secondary button, Edit profile's and Share profile's, takes the color its theme points
     * the other secondary buttons at once that's one of the patch's (#187), and keeps its own fill while
     * the theme holds Instagram's, with no context or no attribute.
     */
    @Test
    public void theDefaultSecondaryButtonTakesTheThemesShadeOnceItsThePatchs() {
        Context ours = new ContextThemeWrapper(named(THEMED + ".buttons", "hushgram_accent_teal_background_dark_top"),
                android.R.style.Theme_Material);
        TypedValue held = new TypedValue();
        assertTrue(ours.getTheme().resolveAttribute(android.R.attr.colorBackground, held, false));
        assertEquals("the theme points at a color", TypedValue.TYPE_REFERENCE, held.type);
        int fill = 0x7f060355;

        assertEquals(held.data, AccentTheme.secondaryButtonFill(ours, fill));
        assertEquals("already that color, not counted", held.data, AccentTheme.secondaryButtonFill(ours, held.data));
        Context instagrams = new ContextThemeWrapper(named(THEMED + ".buttons.stock", "igds_prism_secondary_button_background"),
                android.R.style.Theme_Material);
        assertEquals("Instagram's own color", fill, AccentTheme.secondaryButtonFill(instagrams, fill));
        assertEquals("no context", fill, AccentTheme.secondaryButtonFill(null, fill));
        Context bare = new ContextThemeWrapper(themed(THEMED + ".buttons.bare", (name, type) -> 0), android.R.style.Theme_Material);
        assertEquals("no attribute", fill, AccentTheme.secondaryButtonFill(bare, fill));
        String report = HookStatus.report().toString();
        assertTrue(report, report.contains(AccentTheme.SECONDARY_BUTTONS + " 1"));
    }

    /**
     * The app's context under [pkg], whose resources answer {@code android:colorBackground} for
     * {@link AccentTheme#SECONDARY_BUTTON_ATTRIBUTE} and name every resource [entry].
     */
    private static Context named(String pkg, String entry) {
        Context app = RuntimeEnvironment.getApplication();
        Resources resources = new Resources(app.getAssets(), app.getResources().getDisplayMetrics(), app.getResources().getConfiguration()) {
            @Override
            public int getIdentifier(String name, String type, String defPackage) {
                return pkg.equals(defPackage) && type.equals("attr") && name.equals(AccentTheme.SECONDARY_BUTTON_ATTRIBUTE)
                        ? android.R.attr.colorBackground : 0;
            }

            @Override
            public String getResourceEntryName(int resid) {
                return entry;
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

    private static TypedValue color(int argb) {
        TypedValue value = new TypedValue();
        value.type = TypedValue.TYPE_INT_COLOR_ARGB8;
        value.data = argb;
        return value;
    }
}
