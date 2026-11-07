/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The color typed in HushGram settings, {@link AccentHue#CUSTOM} (#520).
 *
 * <p>Its six shades are worked out from it as Instagram runs, so each reads as well as a preset's:
 * the fill is darkened until white text on it reads at 4 to 1 or better, the light link until it
 * reads at 5 to 1 on white and 4.5 to 1 on the pale shade, and the dark link is lightened from the
 * typed color until it reads light at 5 to 1 on Instagram's dark background. The pressed, dim and pale
 * shades come from the fill as a preset's do. {@link AccentColor}'s hooks swap Instagram's blues for
 * these.
 *
 * <p>The theme's styles read the color through resources, and the patch can't know the color. So it
 * adds a set of colors for this one too, {@code hushgram_accent_custom_*}, holding stand-ins, and lists
 * their names in {@link AccentTheme#customColors()}. On Android 11 and newer {@link #load} gives those
 * colors the typed color's shades through a resource table of its own ({@link ColourTable}), added to
 * the assets behind the activity's resources. A Resources made here on those assets isn't one Android
 * manages, and Android puts a loader added to such Resources into the assets in place, so every
 * Resources and theme on them takes it. On Android 10 and older the theme keeps Instagram's own
 * styles, and only the hooks' colors change.
 */
public final class CustomAccent {
    /** What every color the patch adds for the typed color is named after. */
    static final String PREFIX = "hushgram_accent_custom_";

    /** What's counted when a set of assets takes the typed color's table. */
    static final String LOADED = "custom color tables loaded";

    /** What's counted when a listed color name isn't one this knows how to shade, so it keeps the patch's stand-in. */
    static final String UNKNOWN = "custom colors it couldn't shade";

    /** Instagram's dark background (Prism's black), which the dark link has to read on. */
    static final int PRISM_BLACK = 0x0c1014;

    private static final int WHITE = 0xffffff;

    /** More steps than any color needs; a guard, since each step moves every channel that can move. */
    private static final int MOST_STEPS = 400;

    /** The assets given the table, so each set gets it once. Weak keys let a set go when Instagram drops it. */
    private static final Map<AssetManager, Boolean> LOADED_ASSETS = new WeakHashMap<>();

    /** The last color worked out, and its shades. */
    private static int knownColor = -1;
    @Nullable private static int[] knownShades;

    /** Package-visible for tests, which have no memory files to load a table from. */
    static Installer installer = CustomAccent::install;

    /** Gives the assets behind a Resources a resource table. */
    interface Installer {
        void install(Resources resources, byte[] table) throws Exception;
    }

    private CustomAccent() {
    }

    /**
     * [typed] as a color HushGram can save, {@code #RRGGBB} in capitals, or null when it isn't one.
     * Takes six hex digits or three (each doubled), with or without a {@code #}, and eight, whose first
     * two (an alpha) are dropped since an accent is always solid.
     */
    @Nullable
    public static String normalize(@Nullable String typed) {
        if (typed == null) return null;
        String hex = typed.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) return null;
        }
        if (hex.length() == 3) {
            hex = new String(new char[]{hex.charAt(0), hex.charAt(0), hex.charAt(1), hex.charAt(1), hex.charAt(2), hex.charAt(2)});
        } else if (hex.length() == 8) {
            hex = hex.substring(2);
        } else if (hex.length() != 6) {
            return null;
        }
        return "#" + hex.toUpperCase(Locale.ROOT);
    }

    /** The saved color without alpha, or null when none is saved or it isn't a color. */
    @Nullable
    static Integer saved() {
        String color = normalize(Settings.ACCENT_CUSTOM.get());
        return color == null ? null : Integer.parseInt(color.substring(1), 16);
    }

    /** The saved color's shades in {@link AccentColor.Role} order after NONE, or null when none is saved. */
    @Nullable
    static int[] shades() {
        Integer color = saved();
        return color == null ? null : shades(color);
    }

    /** [rgb]'s shades: fill, pressed, dim, link on light, link on dark and pale, as {@link AccentColor.Role} lists them. */
    static synchronized int[] shades(int rgb) {
        rgb &= 0xffffff;
        if (rgb == knownColor && knownShades != null) return knownShades.clone();
        int fill = rgb;
        for (int i = 0; i < MOST_STEPS && contrast(WHITE, fill) < 4.0; i++) fill = AccentHue.scale(fill, 0.95f);
        int pale = AccentHue.towardWhite(fill, 0.878f);
        int linkOnLight = fill;
        for (int i = 0; i < MOST_STEPS && (contrast(WHITE, linkOnLight) < 5.0 || contrast(pale, linkOnLight) < 4.5
                || AccentTheme.readsLight(linkOnLight)); i++) {
            linkOnLight = AccentHue.scale(linkOnLight, 0.95f);
        }
        int linkOnDark = rgb;
        for (int i = 0; i < MOST_STEPS && (contrast(PRISM_BLACK, linkOnDark) < 5.0 || !AccentTheme.readsLight(linkOnDark)); i++) {
            linkOnDark = AccentHue.towardWhite(linkOnDark, 0.1f);
        }
        int[] shades = {fill, AccentHue.scale(fill, 0.85f), AccentHue.scale(fill, 0.45f), linkOnLight, linkOnDark, pale};
        knownColor = rgb;
        knownShades = shades;
        return shades.clone();
    }

    /** The shade of [shades] for [role]. */
    static int shade(int[] shades, AccentColor.Role role) {
        return shades[role.ordinal() - 1];
    }

    /**
     * The color the patch's color [name] takes from [shades], with its alpha, or null for a name this
     * doesn't know. The patch names them, after {@link #PREFIX}: {@code badge}, the fill;
     * {@code icon_light} and {@code icon_dark}, the link shades; {@code ring_<part>}, a story ring's
     * shade; {@code background_<light|dark>_<base|raised|top>}, a screen's; and {@code <part>_<alpha>},
     * a shade with the alpha of the blue it stands for.
     */
    @Nullable
    static Integer colorFor(String name, int[] shades) {
        if (!name.startsWith(PREFIX)) return null;
        String rest = name.substring(PREFIX.length());
        int fill = shade(shades, AccentColor.Role.FILL);
        if (rest.equals("badge")) return 0xff000000 | fill;
        if (rest.equals("icon_light")) return 0xff000000 | shade(shades, AccentColor.Role.LINK_ON_LIGHT);
        if (rest.equals("icon_dark")) return 0xff000000 | shade(shades, AccentColor.Role.LINK_ON_DARK);
        if (rest.startsWith("ring_")) {
            AccentColor.Role role = role(rest.substring("ring_".length()));
            return role == null ? null : 0xff000000 | shade(shades, role);
        }
        if (rest.startsWith("background_")) {
            String[] parts = rest.substring("background_".length()).split("_");
            if (parts.length != 2 || !(parts[0].equals("light") || parts[0].equals("dark"))) return null;
            float[] share = backgroundShare(parts[1]);
            if (share == null) return null;
            return 0xff000000 | backgroundShade(fill, parts[0].equals("dark"), parts[0].equals("dark") ? share[0] : share[1]);
        }
        int split = rest.lastIndexOf('_');
        if (split <= 0 || rest.length() - split != 3) return null;
        AccentColor.Role role = role(rest.substring(0, split));
        int alpha = hexByte(rest.substring(split + 1));
        if (role == null || alpha < 0) return null;
        return (alpha << 24) | shade(shades, role);
    }

    /** The part [name] names, as the patch writes it (lower case), or null. */
    @Nullable
    private static AccentColor.Role role(String name) {
        for (AccentColor.Role role : AccentColor.Role.values()) {
            if (role != AccentColor.Role.NONE && role.name().toLowerCase(Locale.ROOT).equals(name)) return role;
        }
        return null;
    }

    private static int hexByte(String two) {
        int high = Character.digit(two.charAt(0), 16);
        int low = Character.digit(two.charAt(1), 16);
        return high < 0 || low < 0 ? -1 : high * 16 + low;
    }

    /** How much of the fill a background step keeps on a dark theme and on a light one, as the patch's BackgroundStep has them. */
    @Nullable
    private static float[] backgroundShare(String step) {
        switch (step) {
            case "base":
                return new float[]{0.08f, 0.04f};
            case "raised":
                return new float[]{0.14f, 0.08f};
            case "top":
                return new float[]{0.20f, 0.12f};
            default:
                return null;
        }
    }

    /** The background [fill] gives a dark theme or a light one: [share] of it in black, or in white. */
    static int backgroundShade(int fill, boolean dark, float share) {
        int color = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int channel = (fill >> shift) & 0xff;
            int shaded = dark ? Math.round(channel * share) : 0xff - Math.round((0xff - channel) * share);
            color |= shaded << shift;
        }
        return color;
    }

    /**
     * Gives the assets behind [context]'s resources the saved color's table for the patch's colors
     * [names], space separated, once per set of assets. Answers whether they have it: false on Android
     * 10 and older, with no color saved, no names, none found or anything thrown.
     */
    static synchronized boolean load(Context context, @Nullable String names) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || context == null || names == null || names.isEmpty()) return false;
        try {
            int[] shades = shades();
            if (shades == null) return false;
            Resources resources = context.getResources();
            AssetManager assets = resources.getAssets();
            if (LOADED_ASSETS.containsKey(assets)) return true;
            String app = context.getPackageName();
            List<Integer> ids = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (String name : names.split(" ")) {
                Integer color = colorFor(name, shades);
                int id = color == null ? 0 : resources.getIdentifier(name, "color", app);
                if (color == null) HookStatus.counted(FamilyNames.ACCENT_COLOR, UNKNOWN);
                if (id == 0 || ids.contains(id)) continue;
                ids.add(id);
                colors.add(color);
            }
            if (ids.isEmpty()) return false;
            int[] idArray = new int[ids.size()];
            int[] colorArray = new int[ids.size()];
            for (int i = 0; i < idArray.length; i++) {
                idArray[i] = ids.get(i);
                colorArray[i] = colors.get(i);
            }
            installer.install(resources, ColourTable.write(app, idArray, colorArray));
            LOADED_ASSETS.put(assets, Boolean.TRUE);
            HookStatus.counted(FamilyNames.ACCENT_COLOR, LOADED);
            Logger.printDebug(() -> "Accent color: " + idArray.length + " colors take the typed color");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "custom color table", failure);
            return false;
        }
    }

    /** Forgets which assets have the table, for tests. */
    static synchronized void forget() {
        LOADED_ASSETS.clear();
        knownColor = -1;
        knownShades = null;
    }

    /**
     * Adds a loader for [table] to Resources of this class's own on [resources]' assets, which puts it
     * into those assets in place. The table is read from memory.
     */
    @SuppressWarnings("deprecation") // The constructor is the public way to Resources Android doesn't manage.
    private static void install(Resources resources, byte[] table) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw new UnsupportedOperationException("No resource loaders before Android 11");
        FileDescriptor memory = Os.memfd_create("hushgram-accent.arsc", 0);
        ResourcesLoader loader = new ResourcesLoader();
        try {
            // A stream on a descriptor it didn't open leaves the descriptor open when it closes.
            try (FileOutputStream out = new FileOutputStream(memory)) {
                out.write(table);
            }
            try (ParcelFileDescriptor copy = ParcelFileDescriptor.dup(memory)) {
                loader.addProvider(ResourcesProvider.loadFromTable(copy, null));
            }
        } finally {
            Os.close(memory);
        }
        Resources onAssets = new Resources(resources.getAssets(), resources.getDisplayMetrics(), resources.getConfiguration());
        onAssets.addLoaders(loader);
    }

    /** The WCAG contrast of two colors without alpha. */
    static double contrast(int first, int second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * linear((rgb >> 16) & 0xff) + 0.7152 * linear((rgb >> 8) & 0xff) + 0.0722 * linear(rgb & 0xff);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
