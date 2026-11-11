/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
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
import app.hushgram.extension.shared.Utils;
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
 * <p>A typed color's styles (#520) point at colors the patch added with stand-in values, which
 * {@link CustomAccent} gives the typed color's shades before the styles go on. On Android 10 and
 * older it can't, so the theme is left alone there.
 *
 * <p>With {@link Settings#ACCENT_BADGES} on, the picked color's badge style goes on too (#315). It
 * points {@code igds_color_icon_badge}, the red of the dots and counts on Instagram's icons, at the
 * picked fill. The red delete buttons read other attributes, so they stay red. The feed's liked heart
 * reads this one through the tint list its binder builds, so it takes the picked fill too (#519). The
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
 * gray and the liked heart keeps the badge color. The wordmark is tinted with the primary text color, so it stays
 * too.
 *
 * <p>With {@link Settings#ACCENT_RINGS} on, the picked color's ring style goes on (#187). It points the
 * {@code igds_color_gradient_*} attributes Instagram's story rings read at the picked shades. Other
 * things read those gradient colors too, a few pink highlights among them, and change with the rings.
 * With {@link Settings#ACCENT_RINGS_SOLID} on too, the solid ring style goes on in its place, every
 * stop at the picked fill, so a ring is one color (#519).
 *
 * <p>With {@link Settings#ACCENT_BACKGROUNDS} on, the picked color's background style goes on as well
 * (#187): the theme's screens, panels, cards and menus take a very dark or very light shade of it, for
 * a dark theme or a light one, and text stays readable on it as it is. A viewer's theme keeps its own
 * (see {@link #paintsItsOwnBackground}). Laid on at run time, it wins over Pure black dark mode.
 *
 * <p>Home's newer header tints its heart and Create buttons with a color it reads from the primary text
 * color's resource as it's built, so no attribute reaches them. With Icons in the accent color on, the
 * patch hands that color to {@link #headerIcons} as the header keeps it, which answers the icon color the
 * theme holds instead (#8). Home's older header bakes that color into each icon as it builds it, so the
 * patch hands each icon to {@link #headerIcon}, which lays the theme's icon color over it.
 *
 * <p>Fails open: with the switch off, Instagram blue picked, HushGram paused, the resources missing or
 * anything thrown, the theme stays Instagram's. A change shows on screens opened after a restart.
 */
public final class AccentTheme {
    /** What's counted each time the picked color goes onto a theme. */
    static final String APPLIED = "themes given the accent color";

    /** What's counted when the patch's styles or its list of attributes aren't in the app, so the theme is left alone. */
    static final String MISSING = "themes left blue, the accent styles are missing";

    /**
     * What's counted when Custom is picked but its colors can't take the typed color here (Android 10
     * and older, or the patch listed none), so the theme stays Instagram's and only the hooks recolor.
     */
    static final String CUSTOM_KEPT = "themes left blue, the custom color can't load";

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

    /** What's counted each time Home's newer header takes the picked icon color for its buttons. */
    static final String HEADER_ICONS = "Home header icons given the accent color";

    /** What's counted each time the story rings take the picked color, with Story rings in the accent color on. */
    static final String RINGS = "themes given accent story rings";

    /** What's counted when Story rings in the accent color is on but the patch added no ring style, so rings keep Instagram's gradient. */
    static final String RINGS_MISSING = "themes left with Instagram's story rings, the ring styles are missing";

    /** What's counted each time the backgrounds take the picked color, with Backgrounds in the accent color on. */
    static final String BACKGROUNDS = "themes given accent backgrounds";

    /** What's counted when a theme keeps its background because it isn't a solid color of its own, as a viewer's does. */
    static final String BACKGROUNDS_KEPT = "themes keeping their own background, a viewer's or a see-through one";

    /** What's counted when Backgrounds in the accent color is on but the patch added no background style, so backgrounds stay. */
    static final String BACKGROUNDS_MISSING = "themes left with Instagram's backgrounds, the background styles are missing";

    /** The theme attribute Instagram's icons read, the patch's ICON_ATTRIBUTE. */
    static final String ICON_ATTRIBUTE = "igds_color_primary_icon";

    /** The theme attribute Instagram's screens paint themselves with, the patch's BACKGROUND_ATTRIBUTE. */
    static final String BACKGROUND_ATTRIBUTE = "igds_color_primary_background";

    /** What's counted each time one of the Compose palettes' dark grays takes an accent background's shade. */
    static final String SURFACES = "Compose surfaces given accent backgrounds";

    /** The theme attributes holding the page, raised and top background shades, the patch's BACKGROUND_ATTRIBUTES steps. */
    private static final String[] SURFACE_ATTRIBUTES = {
            BACKGROUND_ATTRIBUTE, "igds_color_elevated_background", "igds_color_secondary_background",
    };

    /** The shades {@link #SURFACE_ATTRIBUTES} last held on a dark theme given the accent backgrounds, or null for none yet. */
    @Nullable
    private static volatile int[] darkSurfaces;

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
        apply(context, attributes(), customColors());
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

    /**
     * Filled in by the patch: the names of the colors it added for {@link AccentHue#CUSTOM}, space
     * separated, or null. {@link CustomAccent} gives them the typed color's shades.
     */
    @Nullable
    public static String customColors() {
        return null;
    }

    /** {@link #apply(Context)} with the attributes the patch listed, and no custom colors. */
    static void apply(Context context, @Nullable String listed) {
        apply(context, listed, null);
    }

    /** {@link #apply(Context)} with the attributes and the custom colors the patch listed. */
    static void apply(Context context, @Nullable String listed, @Nullable String customNames) {
        try {
            AccentHue hue = AccentColor.picked();
            if (hue == null || context == null) return;
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            Resources resources = context.getResources();
            Resources.Theme theme = context.getTheme();
            String app = Utils.resourcePackage(context);
            int palette = id(resources, paletteStyle(hue), "style", app);
            if (palette == 0 || listed == null || listed.isEmpty()) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, MISSING);
                return;
            }
            // The custom styles hold stand-ins until the typed color's table is in the assets, so
            // without it (Android 10 and older, or no names) the theme stays and the hooks do the rest.
            if (hue == AccentHue.CUSTOM && !CustomAccent.load(context, customNames)) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, CUSTOM_KEPT);
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
            if (Settings.ACCENT_RINGS.get()) {
                int rings = id(resources, Settings.ACCENT_RINGS_SOLID.get() ? solidRingStyle(hue) : ringStyle(hue), "style", app);
                if (rings == 0) {
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, RINGS_MISSING);
                } else {
                    theme.applyStyle(rings, true);
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, RINGS);
                }
            }
            if (Settings.ACCENT_BACKGROUNDS.get()) {
                int attr = id(resources, BACKGROUND_ATTRIBUTE, "attr", app);
                int icon = id(resources, ICON_ATTRIBUTE, "attr", app);
                boolean dark = icon != 0 && dark(context, theme, icon);
                int backgrounds = attr == 0 || icon == 0 ? 0 : id(resources, backgroundStyle(hue, dark), "style", app);
                if (backgrounds == 0) {
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, BACKGROUNDS_MISSING);
                } else if (!paintsItsOwnBackground(theme, attr)) {
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, BACKGROUNDS_KEPT);
                } else {
                    theme.applyStyle(backgrounds, true);
                    HookStatus.counted(FamilyNames.ACCENT_COLOR, BACKGROUNDS);
                    if (dark) rememberSurfaces(resources, theme, app);
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "theme", failure);
        }
    }

    /** Keeps the shades [theme] now holds for {@link #SURFACE_ATTRIBUTES}, leaving the last ones if any can't be read. */
    private static void rememberSurfaces(Resources resources, Resources.Theme theme, String app) {
        int[] surfaces = new int[SURFACE_ATTRIBUTES.length];
        TypedValue value = new TypedValue();
        for (int step = 0; step < surfaces.length; step++) {
            int attr = id(resources, SURFACE_ATTRIBUTES[step], "attr", app);
            if (attr == 0 || !theme.resolveAttribute(attr, value, true)
                    || value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) {
                return;
            }
            surfaces[step] = value.data;
        }
        darkSurfaces = surfaces;
    }

    /** Sets what {@link #rememberSurfaces} keeps, for tests. */
    static void darkSurfacesForTests(@Nullable int[] surfaces) {
        darkSurfaces = surfaces;
    }

    /**
     * Injected at the end of the set-up of Instagram's Compose palettes, once for each gray a dark
     * screen paints itself with (#577). Compose screens such as the message list take those from the
     * code, not the theme, so {@link #apply} alone leaves their rows and search field near-black under
     * a colored page. With Backgrounds in the accent color on and a dark theme seen, answers [value] as
     * the same shade the theme's own backgrounds hold, keeping the alpha, and [value] itself for any
     * other color. Light themes stay, since those grays are their text. Never throws.
     */
    public static long composeSurface(long value) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if ((value & 0xffffffffL) != 0) return value;
            int[] surfaces = paintedSurfaces();
            if (surfaces == null) return value;
            int argb = (int) (value >>> 32);
            int step = surfaceStep(argb & 0xffffff);
            if (step < 0) return value;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, SURFACES);
            return (long) (argb & 0xff000000 | surfaces[step] & 0xffffff) << 32;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Compose surface", failure);
            return value;
        }
    }

    /** The shades {@link #rememberSurfaces} kept, while Backgrounds in the accent color is painting with them, or null. */
    @Nullable
    private static int[] paintedSurfaces() {
        int[] surfaces = darkSurfaces;
        return surfaces == null || AccentColor.picked() == null || !Settings.ACCENT_BACKGROUNDS.get() ? null : surfaces;
    }

    /**
     * Whether Backgrounds in the accent color is painting a dark theme's surfaces. Pure black's fills ask
     * first: Material You's neutral shades are gray enough to pass for Instagram's own dark gray, and
     * would otherwise go black on a colored page (#577).
     */
    static boolean paintsSurfaces() {
        return paintedSurfaces() != null;
    }

    /** The opaque shade a raised sheet has while the accent's backgrounds are painting, or [otherwise]. */
    static int raisedSurface(int otherwise) {
        int[] surfaces = paintedSurfaces();
        return surfaces == null ? otherwise : 0xff000000 | surfaces[1];
    }

    /** Which of {@link #SURFACE_ATTRIBUTES} a dark gray of Instagram's stands for, or -1. Black is Pure black's page. */
    private static int surfaceStep(int rgb) {
        switch (rgb) {
            case 0x000000:
            case 0x0c1014:
                return 0;
            case 0x191c1f:
                return 1;
            case 0x212328:
            case 0x25292e:
            case 0x2b3036:
                return 2;
            default:
                return -1;
        }
    }

    /**
     * Whether [theme] holds a solid color of its own for its background [attribute]. A viewer's theme
     * points it at another attribute, the media background, or at a see-through color, and what's
     * behind it has to keep showing, so those keep their own.
     */
    static boolean paintsItsOwnBackground(Resources.Theme theme, int attribute) {
        TypedValue value = new TypedValue();
        if (!theme.resolveAttribute(attribute, value, false) || value.type == TypedValue.TYPE_ATTRIBUTE) return false;
        return theme.resolveAttribute(attribute, value, true)
                && value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                && value.data >>> 24 == 0xff;
    }

    /**
     * Injected where Home's newer header keeps the color it tints its heart and Create buttons with,
     * [color], read from the primary text color (#8). With Icons in the accent color on, answers the
     * color [context]'s theme holds for {@link #ICON_ATTRIBUTE}, the picked link shade once
     * {@link #apply(Context)} has laid the icon style on. Answers [color] with the switch off, HushGram
     * paused, no plain color there or anything thrown.
     */
    public static int headerIcons(Context context, int color) {
        try {
            Integer icons = iconColor(context);
            if (icons == null || icons == color) return color;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, HEADER_ICONS);
            return icons;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "header icons", failure);
            return color;
        }
    }

    /**
     * Injected after Home's older header builds each icon, [icon], for the heart and the plain buttons
     * (#8). That header bakes the primary text color into the icon's bitmap as it's made, so with Icons
     * in the accent color on this lays the color [context]'s theme holds for {@link #ICON_ATTRIBUTE}
     * over it. The badge on the heart has its own paint and stays as it is. Leaves the icon alone with
     * the switch off, HushGram paused, no plain color there or anything thrown.
     */
    public static void headerIcon(Context context, Drawable icon) {
        try {
            Integer color = icon == null ? null : iconColor(context);
            if (color == null) return;
            icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            HookStatus.counted(FamilyNames.ACCENT_COLOR, HEADER_ICONS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "header icon", failure);
        }
    }

    /**
     * The plain color [context]'s theme holds for {@link #ICON_ATTRIBUTE} with Icons in the accent color
     * on, or null with the switch off, HushGram paused, no context or no plain color there.
     */
    @Nullable
    private static Integer iconColor(@Nullable Context context) {
        if (context == null || AccentColor.picked() == null || !Settings.ACCENT_ICONS.get()) return null;
        int attr = id(context.getResources(), ICON_ATTRIBUTE, "attr", Utils.resourcePackage(context));
        TypedValue value = new TypedValue();
        if (attr == 0 || !context.getTheme().resolveAttribute(attr, value, true)
                || value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) {
            return null;
        }
        return value.data;
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

    /** The style pointing the gradient Instagram's story rings read at [hue]'s shades (#187). */
    static String ringStyle(AccentHue hue) {
        return "HushGramAccentRings_" + hue.name();
    }

    /** The style pointing every stop of that gradient at [hue]'s fill, for Solid story rings (#519). */
    static String solidRingStyle(AccentHue hue) {
        return "HushGramAccentRingsSolid_" + hue.name();
    }

    /** The style pointing Instagram's background attributes at [hue]'s shades for a dark theme or a light one (#187). */
    static String backgroundStyle(AccentHue hue, boolean dark) {
        return "HushGramAccentBackgrounds_" + hue.name() + (dark ? "_dark" : "_light");
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
