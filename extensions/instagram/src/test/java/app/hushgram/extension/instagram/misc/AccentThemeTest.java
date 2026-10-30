/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.ContextWrapper;
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
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
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
