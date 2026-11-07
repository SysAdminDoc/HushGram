/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.os.Build;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** A typed accent color (#520): what's accepted, the shades worked out from it, and the table that carries them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CustomAccentTest {
    private static final int WHITE = 0xffffff;
    private static final int PRISM_BLACK = 0x0c1014;
    /** Instagram's blue_5, whose white-on-fill contrast every color has to beat. */
    private static final int STOCK_BLUE = 0x0095f6;
    private static final String PKG = "app.hushgram.test.custom";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final CustomAccent.Installer realInstaller = CustomAccent.installer;

    @Before
    public void prepare() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        Settings.ACCENT_CUSTOM.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        CustomAccent.forget();
    }

    @After
    public void restore() {
        prepare();
        CustomAccent.installer = realInstaller;
    }

    @Test
    public void typedColorsAreSavedAsSixCapitalHexDigits() {
        assertEquals("#4A6B82", CustomAccent.normalize("#4a6b82"));
        assertEquals("#4A6B82", CustomAccent.normalize("  4A6B82 "));
        assertEquals("three digits double up", "#AABBCC", CustomAccent.normalize("#abc"));
        assertEquals("an alpha is dropped", "#4A6B82", CustomAccent.normalize("#804a6b82"));
        for (String wrong : new String[] {null, "", "#", "teal", "#4a6b8", "#4a6b8g", "4a6b82ff00", "# 4a6b82", "##4a6b82", "0x4a6b82"}) {
            assertNull(String.valueOf(wrong), CustomAccent.normalize(wrong));
        }
    }

    /**
     * Every color typed gets shades that read as well as a preset's: white on the fill and on it held
     * down, the light link on white and on the pale pill, the dark link on Prism's black, and each link
     * shade on the right side of light and dark so a theme styled twice keeps its shade.
     */
    @Test
    public void everyTypedColorGetsReadableShades() {
        List<Integer> colors = new ArrayList<>();
        for (int rgb : new int[] {0x000000, 0xffffff, 0xffff00, 0xff0000, 0x00ff00, 0x0000ff, 0x00ffff, 0xff00ff, 0x808080,
                0x4a6b82, 0x769f95, 0xc8d5b9, 0xd48c70, 0x7638fa, 0x010101, 0xfefefe}) {
            colors.add(rgb);
        }
        Random random = new Random(520);
        for (int i = 0; i < 400; i++) colors.add(random.nextInt(0x1000000));
        double stock = CustomAccent.contrast(WHITE, STOCK_BLUE);
        for (int rgb : colors) {
            int[] shades = CustomAccent.shades(rgb);
            String name = String.format("#%06x", rgb);
            int fill = shades[0];
            int pressed = shades[1];
            int linkOnLight = shades[3];
            int linkOnDark = shades[4];
            int pale = shades[5];
            assertTrue(name + ": white on its fill", CustomAccent.contrast(WHITE, fill) >= 3.9);
            assertTrue(name + ": white on its fill against stock", CustomAccent.contrast(WHITE, fill) > stock);
            assertTrue(name + ": white on it held down", CustomAccent.contrast(WHITE, pressed) >= 3.9);
            assertTrue(name + ": its link on white", CustomAccent.contrast(WHITE, linkOnLight) >= 5.0);
            assertTrue(name + ": its link on black", CustomAccent.contrast(PRISM_BLACK, linkOnDark) >= 5.0);
            assertTrue(name + ": its pale shade on black", CustomAccent.contrast(0, pale) >= 5.0);
            assertTrue(name + ": its link on its pale pill", CustomAccent.contrast(pale, linkOnLight) >= 4.5);
            assertTrue(name + ": the dark link reads light", AccentTheme.readsLight(linkOnDark));
            assertFalse(name + ": the light link reads dark", AccentTheme.readsLight(linkOnLight));
            for (int shade : shades) assertEquals(name + ": no alpha", 0, shade >>> 24);
        }
    }

    /** A color that already reads well keeps its fill, and the presets made the same way match it. */
    @Test
    public void aReadableColorKeepsItsFillAndMatchesThePresetsMadeFromIt() {
        for (AccentHue hue : new AccentHue[] {AccentHue.DUSK, AccentHue.PINE, AccentHue.SLATE}) {
            int[] shades = CustomAccent.shades(hue.fill);
            assertArrayEquals(hue.name(), new int[] {hue.fill, hue.pressed, hue.dim, hue.linkOnLight, hue.linkOnDark, hue.pale}, shades);
        }
    }

    /** Each name the patch gives a color answers the shade it stands for, with the alpha in its name. */
    @Test
    public void eachColorNameAnswersItsShade() {
        int[] shades = CustomAccent.shades(0x4a6b82);
        assertEquals(Integer.valueOf(0xff000000 | shades[0]), CustomAccent.colorFor("hushgram_accent_custom_fill_ff", shades));
        assertEquals(Integer.valueOf(0x33000000 | shades[0]), CustomAccent.colorFor("hushgram_accent_custom_fill_33", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[1]), CustomAccent.colorFor("hushgram_accent_custom_pressed_ff", shades));
        assertEquals(Integer.valueOf(0x80000000 | shades[2]), CustomAccent.colorFor("hushgram_accent_custom_dim_80", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[3]), CustomAccent.colorFor("hushgram_accent_custom_link_on_light_ff", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[4]), CustomAccent.colorFor("hushgram_accent_custom_link_on_dark_ff", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[5]), CustomAccent.colorFor("hushgram_accent_custom_pale_ff", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[0]), CustomAccent.colorFor("hushgram_accent_custom_badge", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[3]), CustomAccent.colorFor("hushgram_accent_custom_icon_light", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[4]), CustomAccent.colorFor("hushgram_accent_custom_icon_dark", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[0]), CustomAccent.colorFor("hushgram_accent_custom_ring_fill", shades));
        assertEquals(Integer.valueOf(0xff000000 | shades[5]), CustomAccent.colorFor("hushgram_accent_custom_ring_pale", shades));
        // The patch's BackgroundStep: 8, 14 and 20 percent of the fill in black, 4, 8 and 12 in white.
        assertEquals(Integer.valueOf(0xff000000 | CustomAccent.backgroundShade(shades[0], true, 0.08f)),
                CustomAccent.colorFor("hushgram_accent_custom_background_dark_base", shades));
        assertEquals(Integer.valueOf(0xff000000 | CustomAccent.backgroundShade(shades[0], false, 0.12f)),
                CustomAccent.colorFor("hushgram_accent_custom_background_light_top", shades));
        assertEquals(0x06090a, CustomAccent.backgroundShade(0x4a6b82, true, 0.08f));
        assertEquals(0xf8f9fa, CustomAccent.backgroundShade(0x4a6b82, false, 0.04f));
        for (String unknown : new String[] {"hushgram_accent_dusk_fill_ff", "hushgram_accent_custom_glow_ff", "hushgram_accent_custom_fill_f",
                "hushgram_accent_custom_fill_zz", "hushgram_accent_custom_ring_none", "hushgram_accent_custom_background_dim_base",
                "hushgram_accent_custom_background_dark_floor", "hushgram_accent_custom_none_ff"}) {
            assertNull(unknown, CustomAccent.colorFor(unknown, shades));
        }
    }

    /** Custom with nothing typed keeps Instagram's blue; with a color, every blue takes its shade and keeps its alpha. */
    @Test
    public void customSwapsToTheTypedColorsShades() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.CUSTOM);
        assertNull(AccentColor.picked());
        assertEquals(0xff4a5df9, AccentColor.color(0xff4a5df9));

        Settings.ACCENT_CUSTOM.save("#4A6B82");
        int[] shades = CustomAccent.shades(0x4a6b82);
        assertEquals(AccentHue.CUSTOM, AccentColor.picked());
        assertEquals(0xff000000 | shades[0], AccentColor.color(0xff4a5df9));
        assertEquals(0x66000000 | shades[0], AccentColor.color(0x660095f6));
        assertEquals(0xff000000 | shades[4], AccentColor.color(0xff8593ff));
        assertEquals(0xff000000 | shades[5], AccentColor.color(0xffe0f1ff));
        assertEquals("other colors stay", 0xff0c1014, AccentColor.color(0xff0c1014));
    }

    /** The table holds one default configuration with each id's color at its entry, read back by the format's own layout. */
    @Test
    public void theTableHoldsEachColorAtItsId() {
        int[] ids = {0x7f060a12, 0x7f060003, 0x7f0600ff};
        int[] colors = {0xff4a6b82, 0x334a6b82, 0xffe2e8ec};
        Map<Integer, Integer> read = readTable(ColourTable.write(PKG, ids, colors), PKG);
        assertEquals(3, read.size());
        for (int i = 0; i < ids.length; i++) assertEquals(Integer.toHexString(ids[i]), Integer.valueOf(colors[i]), read.get(ids[i]));

        assertThrows(IllegalArgumentException.class, () -> ColourTable.write(PKG, new int[0], new int[0]));
        assertThrows(IllegalArgumentException.class, () -> ColourTable.write(PKG, ids, new int[] {1}));
        assertThrows(IllegalArgumentException.class, () -> ColourTable.write(PKG, new int[] {0x7f060001, 0x7f070001}, new int[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> ColourTable.write(PKG, new int[] {0x7f060001, 0x7f060001}, new int[] {1, 2}));
        assertThrows(IllegalArgumentException.class, () -> ColourTable.write(PKG, new int[] {0x00060001}, new int[] {1}));
    }

    /**
     * On Android 11 and newer the colors the app has get the typed color's shades in one table, once per
     * set of assets; names it doesn't have are skipped. Older Android, no color typed or no names leave it.
     */
    @Test
    public void loadGivesTheAssetsOneTableOfTheTypedColor() {
        List<byte[]> installed = new ArrayList<>();
        CustomAccent.installer = (resources, table) -> installed.add(table);
        Map<String, Integer> ids = new HashMap<>();
        ids.put("hushgram_accent_custom_fill_ff", 0x7f060101);
        ids.put("hushgram_accent_custom_badge", 0x7f060102);
        ids.put("hushgram_accent_custom_icon_dark", 0x7f060103);
        Context context = themed(ids);
        String names = "hushgram_accent_custom_fill_ff hushgram_accent_custom_badge hushgram_accent_custom_icon_dark "
                + "hushgram_accent_custom_pale_ff hushgram_accent_custom_glow";

        assertFalse("no color typed", CustomAccent.load(context, names));
        Settings.ACCENT_CUSTOM.save("#4A6B82");
        assertFalse("no names", CustomAccent.load(context, null));
        assertFalse("no names", CustomAccent.load(context, ""));
        boolean loaders = Build.VERSION.SDK_INT >= 30;
        assertEquals(loaders, CustomAccent.load(context, names));
        assertEquals(loaders, CustomAccent.load(context, names));
        if (!loaders) {
            assertTrue(installed.isEmpty());
            return;
        }
        assertEquals("once per set of assets", 1, installed.size());
        int[] shades = CustomAccent.shades(0x4a6b82);
        Map<Integer, Integer> read = readTable(installed.get(0), PKG);
        assertEquals(3, read.size());
        assertEquals(Integer.valueOf(0xff000000 | shades[0]), read.get(0x7f060101));
        assertEquals(Integer.valueOf(0xff000000 | shades[0]), read.get(0x7f060102));
        assertEquals(Integer.valueOf(0xff000000 | shades[4]), read.get(0x7f060103));
        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains(CustomAccent.LOADED + " 1"));
        assertTrue(report, report.contains(CustomAccent.UNKNOWN));
    }

    /** An installer that throws leaves the assets as they were and is reported, so the next try runs again. */
    @Test
    public void aFailedLoadIsReportedAndTriedAgain() {
        if (Build.VERSION.SDK_INT < 30) return;
        Settings.ACCENT_CUSTOM.save("#4A6B82");
        Map<String, Integer> ids = new HashMap<>();
        ids.put("hushgram_accent_custom_fill_ff", 0x7f060101);
        Context context = themed(ids);
        CustomAccent.installer = (resources, table) -> {
            throw new IllegalStateException("no loader");
        };
        assertFalse(CustomAccent.load(context, "hushgram_accent_custom_fill_ff"));
        List<byte[]> installed = new ArrayList<>();
        CustomAccent.installer = (resources, table) -> installed.add(table);
        assertTrue(CustomAccent.load(context, "hushgram_accent_custom_fill_ff"));
        assertEquals(1, installed.size());
    }

    /**
     * Custom picked where its colors can't load (no names, or Android 10 and older) leaves the theme as
     * Instagram's and says so; with the table in, it goes on to the theme's own colors.
     */
    @Test
    public void customLeftUnloadedKeepsTheTheme() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.CUSTOM);
        Settings.ACCENT_CUSTOM.save("#4A6B82");
        List<byte[]> installed = new ArrayList<>();
        CustomAccent.installer = (resources, table) -> installed.add(table);
        Map<String, Integer> ids = new HashMap<>();
        ids.put("HushGramAccentPalette_CUSTOM", 0x7f140001);
        ids.put("hushgram_accent_custom_fill_ff", 0x7f060101);
        Context context = themed(ids);
        AccentTheme.apply(context, "igds_color_link", null);
        String report = String.valueOf(HookStatus.report());
        assertTrue(report, report.contains(AccentTheme.CUSTOM_KEPT));
        assertTrue(installed.isEmpty());

        HookStatus.clear();
        AccentTheme.apply(context, "igds_color_link", "hushgram_accent_custom_fill_ff");
        report = String.valueOf(HookStatus.report());
        boolean loaders = Build.VERSION.SDK_INT >= 30;
        assertEquals(report, !loaders, report.contains(AccentTheme.CUSTOM_KEPT));
        assertEquals(report, loaders, report.contains(AccentTheme.NO_BLUE));
        assertEquals(loaders ? 1 : 0, installed.size());
    }

    /** The app's context under {@link #PKG}, whose resources answer only [ids], by name, for that package. */
    private static Context themed(Map<String, Integer> ids) {
        Context app = RuntimeEnvironment.getApplication();
        Resources resources = new Resources(app.getAssets(), app.getResources().getDisplayMetrics(), app.getResources().getConfiguration()) {
            @Override
            public int getIdentifier(String name, String type, String defPackage) {
                Integer id = PKG.equals(defPackage) ? ids.get(name) : null;
                return id == null ? 0 : id;
            }
        };
        return new ContextWrapper(app) {
            @Override
            public Resources getResources() {
                return resources;
            }

            @Override
            public String getPackageName() {
                return PKG;
            }
        };
    }

    /**
     * The colors in [table] by id, read the way AssetManager does: a table chunk holding a string pool
     * and one package, whose type chunk lists an offset per entry and a ResTable_entry plus Res_value
     * at each. Checks every chunk's size and the package's name on the way.
     */
    private static Map<Integer, Integer> readTable(byte[] table, String packageName) {
        ByteBuffer in = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RES_TABLE_TYPE", 0x0002, in.getShort(0));
        assertEquals(12, in.getShort(2));
        assertEquals(table.length, in.getInt(4));
        assertEquals("one package", 1, in.getInt(8));
        int at = 12;
        assertEquals("global string pool", 0x0001, in.getShort(at));
        at += in.getInt(at + 4);
        assertEquals("RES_TABLE_PACKAGE_TYPE", 0x0200, in.getShort(at));
        int packageStart = at;
        int packageHeader = in.getShort(at + 2);
        int packageEnd = at + in.getInt(at + 4);
        assertEquals(table.length, packageEnd);
        int packageId = in.getInt(at + 8);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            char c = in.getChar(at + 12 + 2 * i);
            if (c == 0) break;
            name.append(c);
        }
        assertEquals(packageName, name.toString());
        int typeStrings = in.getInt(at + 12 + 256);
        int keyStrings = in.getInt(at + 12 + 256 + 8);
        assertEquals(0x0001, in.getShort(packageStart + typeStrings));
        assertEquals(0x0001, in.getShort(packageStart + keyStrings));
        Map<Integer, Integer> colors = new HashMap<>();
        at = packageStart + packageHeader;
        boolean sawSpec = false;
        while (at < packageEnd) {
            int type = in.getShort(at);
            int header = in.getShort(at + 2);
            int size = in.getInt(at + 4);
            assertTrue("chunk size", size >= header && at + size <= packageEnd);
            if (type == 0x0001) {
                at += size;
                continue;
            }
            if (type == 0x0202) {
                sawSpec = true;
            } else if (type == 0x0201) {
                assertTrue("the spec comes first", sawSpec);
                int typeId = in.get(at + 8) & 0xff;
                int count = in.getInt(at + 12);
                int entriesStart = in.getInt(at + 16);
                assertEquals("the default configuration", 64, in.getInt(at + 20));
                for (int i = 0; i < 60; i++) assertEquals("default configuration bytes", 0, in.get(at + 24 + i));
                for (int entry = 0; entry < count; entry++) {
                    int offset = in.getInt(at + header + 4 * entry);
                    if (offset == -1) continue;
                    int e = at + entriesStart + offset;
                    assertEquals("ResTable_entry size", 8, in.getShort(e));
                    assertEquals("a plain entry", 0, in.getShort(e + 2));
                    assertEquals("Res_value size", 8, in.getShort(e + 8));
                    assertEquals("TYPE_INT_COLOR_ARGB8", 0x1c, in.get(e + 11));
                    colors.put((packageId << 24) | (typeId << 16) | entry, in.getInt(e + 12));
                }
            } else {
                throw new AssertionError("unexpected chunk " + Integer.toHexString(type));
            }
            at += size;
        }
        assertNotNull(colors);
        return colors;
    }
}
