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
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
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
            if (hue == AccentHue.BLUE || hue == AccentHue.WALLPAPER) continue;
            assertTrue(hue.name(), AccentTheme.readsLight(hue.linkOnDark));
            assertFalse(hue.name(), AccentTheme.readsLight(hue.linkOnLight));
        }
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
