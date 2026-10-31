/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * The theme half of the "Accent color" patch (#8). Some of Instagram's blues never pass the color
 * reads {@link AccentColor} hooks: an XML style that takes a color from a theme attribute, such as a
 * bio link's or the Activity tab's Follow buttons', is read by the framework through TypedArray. So
 * the patch adds theme styles, and this lays the picked color's onto each activity's theme right
 * after Instagram lays its own overrides on.
 *
 * <p>The patch lists every theme attribute Instagram's styles set to one of its accent blues, and
 * adds a style for each blue it found there (an attribute, the part its blue plays and the blue's
 * alpha) that points the attribute at a shade attribute, plus a style per color setting every shade.
 * Here each listed attribute is resolved as the theme holds it now, so a dark theme's link takes the
 * dark link shade and a light one's the light shade, and the styles for the blues found go on along
 * with the picked color's. An attribute holding anything else is left alone.
 *
 * <p>Material You's styles point at Android 12's wallpaper colors (#187). A plain color can't give a
 * system color another alpha, so the patch gives it opaque shades only, and a blue with alpha is left
 * for the color hooks.
 *
 * <p>With {@link Settings#ACCENT_BADGES} on, the picked color's badge style goes on too (#315). It
 * points {@code igds_color_icon_badge}, the red of the dots and counts on Instagram's icons, at the
 * picked fill. The like heart and the red delete buttons read other attributes, so they stay red. The
 * tab bar's badges come from a theme wrapper Instagram builds off the main thread and styles with its
 * theme util, so they take the color through that hook rather than the activity's.
 *
 * <p>With {@link Settings#ACCENT_ICONS} on, the picked color's icon style goes on as well (#8). It
 * points {@code igds_color_primary_icon}, which Instagram's glyphs read and its themes tie to the
 * primary text color, at the picked link shade for the theme: the light shade on a light theme, the
 * dark one on a dark theme. It points the glyph attributes Instagram's themes color on their own at
 * the same shade: {@code glyphColorPrimary} (the top bar's heart, the back and overflow buttons, the
 * post buttons), {@code glyphColorSecondaryActive} (the selected profile tab) and
 * {@code feedSaveActiveColor} (the saved bookmark). Text keeps its own color, the unselected tabs stay
 * gray and the liked heart stays red. The wordmark is tinted with the primary text color, so it stays
 * too.
 *
 * <p>Fails open: with the switch off, Instagram blue picked, HushGram paused, the resources missing or
 * anything thrown, the theme stays Instagram's. A change shows on screens opened after a restart.
 */
public final class AccentTheme {
    /** What's counted each time the picked color goes onto a theme. */
    static final String APPLIED = "themes given the accent color";

    /** What's counted when the patch's styles or its list of attributes aren't in the app, so the theme is left alone. */
    static final String MISSING = "themes left blue, the accent styles are missing";

    /** What's counted when a theme holds none of the blues the patch made styles for. */
    static final String NO_BLUE = "themes with no accent blue";

    /** What's counted each time the badges take the picked color, with Badges in the accent color on. */
    static final String BADGES = "themes given accent badges";

    /** What's counted when Badges in the accent color is on but the patch added no badge style, so badges stay red. */
    static final String BADGES_MISSING = "themes left with red badges, the badge styles are missing";

    /** What's counted each time the icons take the picked color, with Icons in the accent color on. */
    static final String ICONS = "themes given accent icons";

    /** What's counted when Icons in the accent color is on but the patch added no icon style, so icons keep their color. */
    static final String ICONS_MISSING = "themes left with Instagram's icon color, the icon styles are missing";

    /** The theme attribute Instagram's icons read, the patch's ICON_ATTRIBUTE. */
    static final String ICON_ATTRIBUTE = "igds_color_primary_icon";

    /** Resource numbers by type and name, 0 for none. They don't change while the app runs. */
    private static final Map<String, Integer> IDS = new HashMap<>();

    private AccentTheme() {
    }

    /**
     * Injected at each return of the method laying Instagram's overrides over [context]'s theme, and of
     * its theme util, which also styles contexts of Instagram's own making such as the tab bar's (#315).
     * Never throws.
     */
    public static void apply(Context context) {
        apply(context, attributes());
    }

    /**
     * Filled in by the patch: every theme attribute it made styles for, space separated, or null. It's
     * written into the code because Instagram answers its string resources from its own string packs by
     * number, so a string the patch added would read back as one of Instagram's.
     */
    @Nullable
    public static String attributes() {
        return null;
    }

    /** {@link #apply(Context)} with the attributes the patch listed. */
    static void apply(Context context, @Nullable String listed) {
        try {
            AccentHue hue = AccentColor.picked();
            if (hue == null || context == null) return;
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            Resources resources = context.getResources();
            Resources.Theme theme = context.getTheme();
            String app = context.getPackageName();
            int palette = id(resources, paletteStyle(hue), "style", app);
            if (palette == 0 || listed == null || listed.isEmpty()) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, MISSING);
                return;
            }
            boolean wallpaper = hue == AccentHue.WALLPAPER;
            if (wallpaper) MaterialYou.read(context);
            List<Integer> styles = new ArrayList<>();
            TypedValue value = new TypedValue();
            for (String attribute : listed.split(" ")) {
                int attr = attribute.startsWith("android:")
                        ? id(resources, attribute.substring("android:".length()), "attr", "android")
                        : id(resources, attribute, "attr", app);
                if (attr == 0 || !theme.resolveAttribute(attr, value, true)) continue;
                // Material You's palette has opaque shades only, so a see-through blue keeps its
                // attribute and the color hooks swap it as it's read.
                if (wallpaper && value.data >>> 24 != 0xff) continue;
                String style = styleFor(attribute, value);
                int styleId = style == null ? 0 : id(resources, style, "style", app);
                if (styleId != 0) styles.add(styleId);
            }
            if (styles.isEmpty()) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, NO_BLUE);
                Logger.printDebug(() -> "Accent color: no accent blue on " + context.getClass().getName() + "'s theme, "
                        + held(resources, theme, listed, app));
            } else {
                theme.applyStyle(palette, true);
                for (int style : styles) theme.applyStyle(style, true);
                HookStatus.counted(FamilyNames.ACCENT_COLOR, APPLIED);
                Logger.printDebug(() -> "Accent color: " + styles.size() + " styles on " + context.getClass().getName() + "'s theme");
            }
            if (Settings.ACCENT_BADGES.get()) {
                int badges = id(resources, badgeStyle(hue), "style", app);
                if (badges == 0) {
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, BADGES_MISSING);
                } else {
                    theme.applyStyle(badges, true);
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, BADGES);
                }
            }
            if (Settings.ACCENT_ICONS.get()) {
                int attr = id(resources, ICON_ATTRIBUTE, "attr", app);
                int icons = attr == 0 ? 0 : id(resources, iconStyle(hue, dark(context, theme, attr)), "style", app);
                if (icons == 0) {
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, ICONS_MISSING);
                } else {
                    theme.applyStyle(icons, true);
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, ICONS);
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "theme", failure);
        }
    }

    /**
     * The name of the style pointing [attribute] at the shade for the blue [value] holds, or null when
     * it holds no color or no accent blue. The patch names its styles the same way.
     */
    static String styleFor(String attribute, TypedValue value) {
        if (value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) return null;
        AccentColor.Role role = AccentColor.role(value.data);
        if (role == AccentColor.Role.NONE) return null;
        return "HushGramAccent_" + attribute.replace(':', '_') + "_" + role.name().toLowerCase(Locale.ROOT)
                + String.format(Locale.ROOT, "_%02x", value.data >>> 24);
    }

    /** What each listed attribute holds on [theme], as type and data, for the debug log. */
    private static String held(Resources resources, Resources.Theme theme, String listed, String app) {
        StringBuilder held = new StringBuilder();
        TypedValue value = new TypedValue();
        for (String attribute : listed.split(" ")) {
            int attr = attribute.startsWith("android:")
                    ? resources.getIdentifier(attribute.substring("android:".length()), "attr", "android")
                    : resources.getIdentifier(attribute, "attr", app);
            if (held.length() > 0) held.append(", ");
            held.append(attribute).append('=');
            if (attr == 0) held.append("no attribute");
            else if (!theme.resolveAttribute(attr, value, true)) held.append("unset");
            else held.append(value.type).append('/').append(Integer.toHexString(value.data));
        }
        return held.toString();
    }

    /** The style setting every shade attribute to [hue]'s colors. */
    static String paletteStyle(AccentHue hue) {
        return "HushGramAccentPalette_" + hue.name();
    }

    /** The style pointing Instagram's badge attribute at [hue]'s fill (#315). */
    static String badgeStyle(AccentHue hue) {
        return "HushGramAccentBadge_" + hue.name();
    }

    /** The style pointing Instagram's icon attribute at [hue]'s link shade for a dark theme or a light one (#8). */
    static String iconStyle(AccentHue hue, boolean dark) {
        return "HushGramAccentIcons_" + hue.name() + (dark ? "_dark" : "_light");
    }

    /**
     * Whether [theme] is a dark one: the icon color it holds, Instagram's primary text color, reads
     * light. Every color's dark icon shade reads light too and its light one dark, so a theme styled
     * twice keeps its shade. Without a plain color there, the screen's night mode decides.
     */
    static boolean dark(Context context, Resources.Theme theme, int iconAttribute) {
        TypedValue value = new TypedValue();
        if (theme.resolveAttribute(iconAttribute, value, true)
                && value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return readsLight(value.data);
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Whether [rgb] is a light color, by its weighted brightness. */
    static boolean readsLight(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return 299 * red + 587 * green + 114 * blue > 127_500;
    }

    private static int id(Resources resources, String name, String type, String app) {
        String key = type + "/" + app + ":" + name;
        synchronized (IDS) {
            Integer known = IDS.get(key);
            if (known != null) return known;
        }
        int found = resources.getIdentifier(name, type, app);
        synchronized (IDS) {
            IDS.put(key, found);
        }
        return found;
    }
}
