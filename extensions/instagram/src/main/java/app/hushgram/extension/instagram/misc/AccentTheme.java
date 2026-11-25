/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

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
 * too. Litho trees, the feed's post buttons among them, read their icon colors from the theme of the
 * context they're built on, which isn't always one the overrides or the theme util reached, so the
 * icon style also goes onto each such theme as Litho takes it up ({@link #lithoIcons}, #724).
 *
 * <p>With {@link Settings#ACCENT_TEXT} on under it, the picked color's text style goes on after the
 * icon style, wherever that does (#187). It points {@code igds_color_primary_text} and
 * {@code igds_color_text_and_icon_primary} at the same link shade, so names, captions and titles take
 * it. Secondary text stays gray.
 *
 * <p>With {@link Settings#ACCENT_PILLS} on, Saved's filters take the picked color through
 * {@link #savedFilter} as each gets its background (#187): the outlined ones a border and title in the
 * link shade, the selected one the picked fill.
 *
 * <p>With {@link Settings#ACCENT_REELS} on, the buttons beside a reel and their counts take the picked
 * color (#187). The column reads one color for its counts, white over the video, and builds its buttons
 * with no color filter for their white icons there, so the color goes through {@link #reelButtons(int)}
 * as the column reads it and each button's filter through {@link #reelButtons(ColorFilter)} as the
 * column builds the button.
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
 * <p>With {@link Settings#CLASSIC_COLORS} on, whatever the accent switches say, the classic style goes
 * on last (#639): Instagram's older look, a navy screen with charcoal panels on a dark theme and an
 * off-white one on a light theme. It sets the same attributes as the accent backgrounds, so it wins over
 * them and over Pure black, and a viewer's theme keeps its own the same way. Compose's dark grays take
 * its shades through {@link #composeSurface} as they take the accent backgrounds', and on a light theme
 * so do Compose's white page and its search fields' gray.
 *
 * <p>Home's newer header tints its heart and Create buttons with a color it reads from the primary text
 * color's resource as it's built, so no attribute reaches them. With Icons in the accent color on, the
 * patch hands that color to {@link #headerIcons} as the header keeps it, which answers the icon color the
 * theme holds instead (#8). Home's older header bakes that color into each icon as it builds it, so the
 * patch hands each icon to {@link #headerIcon}, which lays the theme's icon color over it.
 *
 * <p>Fails open: with the switch off, Instagram blue picked, HushGram paused, the resources missing or
 * anything thrown, the theme stays Instagram's. Classic colors fails apart from the accent color, with
 * its own switch. A change shows on screens opened after a restart.
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

    /** What's counted each time a tab's icon is told a color and takes the picked one instead (#663). */
    static final String TAB_ICONS = "Tab icons given the accent color";

    /** What's counted each time a reel's button column takes the picked color for its counts or icons (#187). */
    static final String REEL_BUTTONS = "Reel buttons given the accent color";

    /** What's counted each time a top bar's icon, a profile tab or the Search tab's Saved bookmark takes the picked color (#187). */
    static final String BAR_ICONS = "Top bar and profile tab icons given the accent color";

    /** Counted each time the text style goes onto a theme. */
    static final String TEXT = "themes given accent text";

    /** Counted when Text in the accent color is on but the build has no text styles. */
    static final String TEXT_MISSING = "themes left with Instagram's text color, the text styles are missing";

    /** Counted each time one of Saved's filters takes the accent. */
    static final String PILLS = "Saved filters given the accent color";

    /**
     * The background Saved's filters are given: a selector of two pills, the one you're on filled with
     * Instagram's secondary button color and the others outlined 1.25dp in its stroke color.
     */
    static final String SAVED_TAB = "saved_tab_background";

    /** The corner radius of those pills, round at their height. */
    static final String CHIP_RADIUS = "igds_chip_radius";

    /** The outlined pill's border width, as Instagram draws it. */
    static final float PILL_STROKE_DP = 1.25f;

    /** {@link #CHIP_RADIUS} as Instagram 457 has it, for a build without it. */
    static final float CHIP_RADIUS_DP = 99f;

    /** How far apart a color's channels can be for {@link #barIcons(int)} to take it for a gray. */
    static final int NEUTRAL_SPREAD = 0x18;

    /**
     * The brightness range {@link #barIcons(int)} reads as a tab you aren't on: Instagram's secondary
     * icon gray, #737373 on a light screen and #a8a8a8 on a dark one, and not its near black or white.
     */
    static final int SECONDARY_LOW = 0x50;
    static final int SECONDARY_HIGH = 0xd0;

    /** The filters {@link #barIconFilter(int)} made, one per color, as the top bar's own maker keeps them. */
    private static final Map<Integer, ColorFilter> BAR_FILTERS = new HashMap<>();

    /** What's counted each time a theme a Litho tree reads its colors from takes the icon style (#724). */
    static final String LITHO_ICONS = "Litho themes given accent icons";

    /** What's counted each time a repost button's animation takes the picked icon color (#724). */
    static final String REPOST_ICONS = "Repost buttons given the accent color";

    /**
     * The raw animations the repost button under a post plays, whichever way it's turning. Instagram's
     * black, or white in dark mode, is in the file, so no theme color or tint reaches them (#724).
     */
    static final String[] REPOST_ANIMATIONS = {"repost_light", "repost_dark", "unrepost_light", "unrepost_dark"};

    /** The color the animation loading on this thread takes, from {@link #repostAnimation(Context, int)}. */
    private static final ThreadLocal<Integer> REPOST_PENDING = new ThreadLocal<>();

    /** The paint each repost animation given the accent color is drawn through, by the drawable. */
    private static final Map<Drawable, Paint> REPOST_PAINTS = new WeakHashMap<>();

    /** The layer {@link #repostDraw} opened on this thread, which {@link #repostDrawn} closes. */
    private static final ThreadLocal<RepostLayer> REPOST_LAYER = new ThreadLocal<>();

    /** The glyph attribute the feed's post buttons read through Litho, the first of the patch's GLYPH_ATTRIBUTES. */
    static final String GLYPH_ATTRIBUTE = "glyphColorPrimary";

    /**
     * The themes {@link #lithoIcons} looked at last, as weak references, told apart by identity since a
     * theme's equals compares the styles laid on it. Litho takes the same few themes up over and over.
     */
    private static final Object[] SETTLED = new Object[16];

    /** The slot of {@link #SETTLED} the next theme goes in. */
    private static int settledNext;

    /** Instagram's link on a dark screen and on a light one, which {@link AccentColor#swap} turns into the picked color's. */
    private static final int LINK_ON_DARK = 0xff85a1ff;
    private static final int LINK_ON_LIGHT = 0xff3143e3;

    /** Instagram's fill blue, which {@link AccentColor#swap} turns into the picked color's fill, the shade the badge styles hold. */
    private static final int FILL = 0xff4a5df9;

    /** What's counted each time a Compose badge, the Messages folder chip's unread dot, takes the picked color (#671). */
    static final String COMPOSE_BADGES = "Compose badges given the accent color";

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

    /** What's counted each time a theme takes Instagram's older look, with Classic colors on (#639). */
    static final String CLASSIC = "themes given classic colors";

    /** What's counted when Classic colors is on but the patch added no classic style, so backgrounds stay. */
    static final String CLASSIC_MISSING = "themes left with Instagram's backgrounds, the classic styles are missing";

    /** The theme attribute Instagram's icons read, the patch's ICON_ATTRIBUTE. */
    static final String ICON_ATTRIBUTE = "igds_color_primary_icon";

    /** The theme attribute Instagram's screens paint themselves with, the patch's BACKGROUND_ATTRIBUTE. */
    static final String BACKGROUND_ATTRIBUTE = "igds_color_primary_background";

    /** What's counted each time one of the Compose palettes' dark grays takes an accent background's shade. */
    static final String SURFACES = "Compose surfaces given accent backgrounds";

    /** What's counted each time a color of Meta's own menus, the account switcher's among them, takes an accent shade (#187). */
    static final String DSP = "Meta menu colors given the accent";

    /** What's counted each time a gray secondary button, Edit profile and Share profile among them, takes an accent background's shade (#187). */
    static final String SECONDARY_BUTTONS = "Secondary buttons given accent backgrounds";

    /** What's counted each time an opened chat's black or white background or header takes the page shade (#528). */
    static final String CHAT_BACKGROUNDS = "Chat backgrounds and headers given the page shade";

    /** The key Instagram keeps its Dark mode choice under in its default preferences, -1 for the system's. */
    static final String DARK_MODE_KEY = "dark_mode_toggle_setting";

    /** {@link #DARK_MODE_KEY}'s value for Dark mode on, and for off. */
    static final int DARK_MODE_ON = 2, DARK_MODE_OFF = 1;

    /** The theme attribute Instagram's other secondary buttons read their fill from, the first of the patch's SECONDARY_BUTTONS. */
    static final String SECONDARY_BUTTON_ATTRIBUTE = "igds_color_secondary_button_panavision";

    /** The start of the names of the colors the patch adds, the backgrounds' shades among them. */
    static final String OWN_COLORS = "hushgram_";

    /** The theme attributes holding the page, raised and top background shades, the patch's BACKGROUND_ATTRIBUTES steps. */
    private static final String[] SURFACE_ATTRIBUTES = {
            BACKGROUND_ATTRIBUTE, "igds_color_elevated_background", "igds_color_secondary_background",
    };

    /** The shades {@link #SURFACE_ATTRIBUTES} last held on a dark theme given the accent backgrounds, or null for none yet. */
    @Nullable
    private static volatile int[] darkSurfaces;

    /** The shades {@link #SURFACE_ATTRIBUTES} last held on a light theme given Classic colors, or null for none yet. */
    @Nullable
    private static volatile int[] lightSurfaces;

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

    /**
     * {@link #apply(Context)} with the attributes and the custom colors the patch listed: the picked
     * color's styles, then Classic colors', each failing on its own.
     */
    static void apply(Context context, @Nullable String listed, @Nullable String customNames) {
        if (context == null) return;
        boolean invoked = false;
        try {
            AccentHue hue = AccentColor.picked();
            if (hue != null) {
                HookStatus.invoked(FamilyNames.ACCENT_COLOR);
                invoked = true;
                applyAccent(context, hue, listed, customNames);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "theme", failure);
        }
        try {
            if (Utils.settingsReady() && Settings.CLASSIC_COLORS.get()) {
                if (!invoked) HookStatus.invoked(FamilyNames.ACCENT_COLOR);
                applyClassic(context);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "classic colors", failure);
        }
    }

    /**
     * Lays Instagram's older look onto [context]'s theme with Classic colors on (#639): the classic
     * style for a dark theme or a light one, after the picked color's backgrounds so it wins over them,
     * unless the theme is a viewer's or a see-through one.
     */
    private static void applyClassic(Context context) {
        Resources resources = context.getResources();
        Resources.Theme theme = context.getTheme();
        String app = Utils.resourcePackage(context);
        int attr = id(resources, BACKGROUND_ATTRIBUTE, "attr", app);
        int icon = id(resources, ICON_ATTRIBUTE, "attr", app);
        boolean dark = icon != 0 && dark(context, theme, icon);
        int classic = attr == 0 || icon == 0 ? 0 : id(resources, classicStyle(dark), "style", app);
        if (classic == 0) {
            HookStatus.counted(FamilyNames.ACCENT_COLOR, CLASSIC_MISSING);
        } else if (!paintsItsOwnBackground(theme, attr)) {
            HookStatus.counted(FamilyNames.ACCENT_COLOR, BACKGROUNDS_KEPT);
        } else {
            theme.applyStyle(classic, true);
            HookStatus.counted(FamilyNames.ACCENT_COLOR, CLASSIC);
            rememberSurfaces(resources, theme, app, dark);
        }
    }

    /** Lays [hue]'s styles onto [context]'s theme, for the attributes and the custom colors the patch listed. */
    private static void applyAccent(Context context, AccentHue hue, @Nullable String listed, @Nullable String customNames) {
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
            boolean dark = attr != 0 && dark(context, theme, attr);
            int icons = attr == 0 ? 0 : id(resources, iconStyle(hue, dark), "style", app);
            if (icons == 0) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, ICONS_MISSING);
            } else {
                theme.applyStyle(icons, true);
                HookStatus.counted(FamilyNames.ACCENT_COLOR, ICONS);
                applyText(resources, theme, app, hue, dark);
            }
        }
        if (Settings.ACCENT_RINGS.get()) {
            int rings = id(resources, Settings.ACCENT_RINGS_SOLID.get() ? solidRingStyle(hue) : ringStyle(hue), "style", app);
            if (rings == 0) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, RINGS_MISSING);
            } else {
                theme.applyStyle(rings, true);
                HookStatus.counted(FamilyNames.ACCENT_COLOR, RINGS);
                // Most rings take their colors from Instagram's default ring, not the theme (#528).
                AccentRings.remember(resources, theme, app);
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
                if (dark) rememberSurfaces(resources, theme, app, true);
            }
        }
    }

    /**
     * Keeps the shades [theme] now holds for {@link #SURFACE_ATTRIBUTES} as a [dark] theme's or a light
     * one's, leaving the last ones if any can't be read.
     */
    private static void rememberSurfaces(Resources resources, Resources.Theme theme, String app, boolean dark) {
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
        if (dark) {
            darkSurfaces = surfaces;
        } else {
            lightSurfaces = surfaces;
        }
    }

    /** Sets what {@link #rememberSurfaces} keeps for a dark theme, for tests. */
    static void darkSurfacesForTests(@Nullable int[] surfaces) {
        darkSurfaces = surfaces;
    }

    /** Sets what {@link #rememberSurfaces} keeps for a light theme, for tests. */
    static void lightSurfacesForTests(@Nullable int[] surfaces) {
        lightSurfaces = surfaces;
    }

    /**
     * Injected at the end of the set-up of Instagram's Compose palettes, once for each gray a dark
     * screen paints itself with (#577) and each white or gray fill a light one does (#639). Compose
     * screens such as the message list take those from the code, not the theme, so {@link #apply}
     * alone leaves their rows and search field near-black under a colored page, or white beside an
     * off-white one. With Classic colors on, or Backgrounds in the accent color on and a dark theme
     * seen, answers a dark gray as the same shade the theme's own backgrounds hold, keeping the
     * alpha. With Classic colors on, does the same for the white page and the search field's gray,
     * whichever kind of screen shows (see {@link #styledSurfaces}). The white labels and icons
     * Compose draws over colored fills and media share that field, so they take the page shade too
     * (#fafafa against #ffffff, too slight to see). The light grays a dark screen writes its text
     * with aren't among either. Answers [value] itself for any other color. Never throws.
     */
    public static long composeSurface(long value) {
        return composeSurface(value, null);
    }

    /**
     * {@link #composeSurface(long)}, reading the shades from the styles through [context] (the app's when
     * null) when no theme has been styled yet. See {@link #styledSurfaces}.
     */
    static long composeSurface(long value, @Nullable Context context) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if ((value & 0xffffffffL) != 0) return value;
            int argb = (int) (value >>> 32);
            int step = surfaceStep(argb & 0xffffff);
            boolean dark = step >= 0;
            if (!dark) step = lightStep(argb & 0xffffff);
            if (step < 0) return value;
            int[] surfaces = dark ? paintedSurfaces() : paintedLightSurfaces();
            if (surfaces == null) surfaces = styledSurfaces(context, dark);
            if (surfaces == null) return value;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, SURFACES);
            return (long) (argb & 0xff000000 | surfaces[step] & 0xffffff) << 32;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Compose surface", failure);
            return value;
        }
    }

    /**
     * The shades a [dark] theme or a light one takes from Classic colors' style, or from the picked
     * color's backgrounds, read from the style alone through [context] (the app's when null) and kept as
     * {@link #rememberSurfaces} keeps a styled theme's. Instagram builds its Compose palettes on a thread
     * of its own as it starts, while the first screen's theme is still being styled, so the first grays
     * they asked for were answered as Instagram's own and the Messages rows stayed black all session
     * (#528). Classic colors' shades come for both kinds of screen, whichever shows then: Instagram
     * switches between dark and light without a restart (a dark mode schedule, the quick settings
     * tile), and palettes built on a light screen kept the dark one's Messages rows, Settings' header
     * and its loading page black until the next start (#683). A light screen writing its text in the
     * dark grays takes Classic's navy then, as it already did after a start in dark mode. The picked
     * color's backgrounds come only for the kind of screen the app will show, since their tints would
     * color a light screen's text, and not for Material You's or a typed color, whose shades load with
     * the first screen. Null when none of that holds or the style can't be read.
     */
    @Nullable
    static int[] styledSurfaces(@Nullable Context context, boolean dark) {
        if (!Utils.settingsReady()) return null;
        Context app = context != null ? context : Utils.getContext();
        if (app == null) return null;
        Resources resources = app.getResources();
        String style = null;
        if (Settings.CLASSIC_COLORS.get()) {
            style = classicStyle(dark);
        } else if (dark && Settings.ACCENT_BACKGROUNDS.get() && showsDark(app)) {
            AccentHue hue = AccentColor.picked();
            if (hue != null && hue != AccentHue.WALLPAPER && hue != AccentHue.CUSTOM) style = backgroundStyle(hue, true);
        }
        if (style == null) return null;
        String pkg = Utils.resourcePackage(app);
        int id = id(resources, style, "style", pkg);
        if (id == 0) return null;
        Resources.Theme theme = resources.newTheme();
        theme.applyStyle(id, true);
        rememberSurfaces(resources, theme, pkg, dark);
        return dark ? paintedSurfaces() : paintedLightSurfaces();
    }

    /**
     * Instagram's own Dark mode choice, kept in its default preferences under {@link #DARK_MODE_KEY}:
     * {@link #DARK_MODE_ON} or {@link #DARK_MODE_OFF}, else the system's. Each screen lays it on as it
     * opens, so the app's configuration still holds the system's while the palettes are built.
     */
    static boolean showsDark(Context app) {
        int mode = app.getSharedPreferences(app.getPackageName() + "_preferences", Context.MODE_PRIVATE).getInt(DARK_MODE_KEY, -1);
        if (mode == DARK_MODE_ON) return true;
        if (mode == DARK_MODE_OFF) return false;
        return (app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Injected at the start of the constructor of Meta's design system color, with the color it keeps
     * for a light screen. Meta's own menus in Instagram, the account switcher among them, build their
     * colors from tables in the code and not from Instagram's theme, so they kept Meta's blue (#187).
     * Answers Meta's blues as the picked color's fill, and with Classic colors on, Meta's white page
     * and gray wash as the shades Instagram's light theme holds. Keeps the alpha. Answers [color] for
     * any other color, with the switches off, HushGram paused or anything thrown.
     */
    public static int dspLight(int color) {
        return dspColor(color, false);
    }

    /**
     * The same as {@link #dspLight} for the color kept for a dark screen. With Classic colors or
     * Backgrounds in the accent color on, Meta's dark grays (the menu's #1f1f20 and the raised ones)
     * take the shades Instagram's dark theme holds, as {@link #composeSurface} gives Compose's.
     */
    public static int dspDark(int color) {
        return dspColor(color, true);
    }

    private static int dspColor(int color, boolean dark) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            int rgb = color & 0xffffff;
            if (rgb == META_BLUE_ON_LIGHT || rgb == META_BLUE_ON_DARK) {
                AccentHue hue = AccentColor.picked();
                if (hue == null) return color;
                HookStatus.counted(FamilyNames.ACCENT_COLOR, DSP);
                return color & 0xff000000 | AccentColor.swap(FILL, hue) & 0xffffff;
            }
            int step = dark ? dspDarkStep(rgb) : dspLightStep(rgb);
            int[] surfaces = dark ? paintedSurfaces() : paintedLightSurfaces();
            if (step < 0 || surfaces == null) return color;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, DSP);
            return color & 0xff000000 | surfaces[step] & 0xffffff;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Meta menu color", failure);
            return color;
        }
    }

    /** Meta's blue on a light screen and on a dark one. */
    private static final int META_BLUE_ON_LIGHT = 0x0064e0, META_BLUE_ON_DARK = 0x0082fb;

    /**
     * Injected where an opened chat hands its background to the view behind the messages, with the
     * layers Instagram built from the chat's theme (#528). The default theme paints the page a plain
     * black, or white on a light screen, and the strip behind the status bar the header's black, both
     * taken from the chat theme and not from Instagram's theme, so Classic colors and Backgrounds in the
     * accent color left the chat black under a colored page. With either painting, each plain layer of
     * Instagram's page black takes the dark page shade, and with Classic colors on, each white one takes
     * the light page shade, the way {@link #composeSurface} answers Compose's. The strip's shape is
     * copied, not changed, since Instagram keeps it for the next chat. A chat theme of its own (a
     * gradient, a picture or another color) keeps its look. Changes nothing with the switches off,
     * HushGram paused or anything thrown.
     */
    public static void chatBackground(@Nullable Drawable background) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if (!(background instanceof LayerDrawable)) return;
            LayerDrawable layers = (LayerDrawable) background;
            boolean painted = false;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                Drawable shaded = shaded(layers.getDrawable(i));
                if (shaded == null) continue;
                layers.setDrawable(i, shaded);
                painted = true;
            }
            if (painted) HookStatus.counted(FamilyNames.ACCENT_COLOR, CHAT_BACKGROUNDS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "chat background", failure);
        }
    }

    /** A copy of a plain color or a solid shape in [layer] holding the page shade {@link #chatShade} gives its color, or null for none. */
    @Nullable
    private static Drawable shaded(@Nullable Drawable layer) {
        if (layer instanceof ColorDrawable) {
            int color = ((ColorDrawable) layer).getColor();
            int shade = chatShade(color);
            return shade == color ? null : new ColorDrawable(shade);
        }
        if (!(layer instanceof ShapeDrawable)) return null;
        Paint paint = ((ShapeDrawable) layer).getPaint();
        int shade = chatShade(paint.getColor());
        Drawable.ConstantState state = layer.getConstantState();
        if (shade == paint.getColor() || paint.getShader() != null || state == null) return null;
        Drawable copy = state.newDrawable().mutate();
        if (!(copy instanceof ShapeDrawable) || copy == layer) return null;
        ((ShapeDrawable) copy).getPaint().setColor(shade);
        return copy;
    }

    /**
     * Injected right after an opened chat's header row takes the header color of the chat's theme, with
     * the header (#528). The default theme's header is a plain black, or white on a light screen, so
     * it stayed black over a colored page. Gives a plain background of Instagram's page black or white
     * the page shade, as {@link #chatBackground} does the strip above it. Changes nothing with the
     * switches off, HushGram paused or anything thrown.
     */
    public static void chatHeader(@Nullable View header) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if (header == null || !(header.getBackground() instanceof ColorDrawable)) return;
            int color = ((ColorDrawable) header.getBackground()).getColor();
            int shade = chatShade(color);
            if (shade == color) return;
            header.setBackgroundColor(shade);
            HookStatus.counted(FamilyNames.ACCENT_COLOR, CHAT_BACKGROUNDS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "chat header", failure);
        }
    }

    /**
     * The opaque page shade an opaque chat background of Instagram's page black or white takes while
     * the backgrounds are painted, or [color] itself.
     */
    static int chatShade(int color) {
        if (color >>> 24 != 0xff) return color;
        int rgb = color & 0xffffff;
        int[] surfaces = surfaceStep(rgb) == 0 ? paintedSurfaces() : lightStep(rgb) == 0 ? paintedLightSurfaces() : null;
        return surfaces == null ? color : 0xff000000 | surfaces[0] & 0xffffff;
    }

    /**
     * Which of {@link #SURFACE_ATTRIBUTES} a dark gray of Meta's stands for, or -1: its menus' surface
     * raised like Instagram's sheets, and the raised and wash grays over it. The light grays it writes
     * text with aren't among them.
     */
    private static int dspDarkStep(int rgb) {
        switch (rgb) {
            case 0x1f1f20:
                return 1;
            case 0x323435:
            case 0x28292b:
                return 2;
            default:
                return -1;
        }
    }

    /** Which of {@link #SURFACE_ATTRIBUTES} a light fill of Meta's stands for, or -1: the white page and the gray wash. */
    private static int dspLightStep(int rgb) {
        switch (rgb) {
            case 0xffffff:
                return 0;
            case 0xf3f4f5:
                return 2;
            default:
                return -1;
        }
    }

    /**
     * Injected where the Messages inbox's folder chip (Primary, Requests) paints its unread dot, with
     * the color Instagram's Compose palette holds for it, [value] (#671). Compose takes its colors from
     * palettes written into the code, so the badge style {@link #apply} lays on the theme never reaches
     * the dot. With Badges in the accent color on, answers the picked color's fill, the shade the badge
     * styles hold, keeping [value]'s alpha when it's an sRGB color. Answers [value] with the switch off,
     * HushGram paused or anything thrown.
     */
    public static long composeBadge(long value) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            AccentHue hue = AccentColor.picked();
            if (hue == null || !Settings.ACCENT_BADGES.get()) return value;
            // An sRGB Compose color holds its ARGB in the high 32 bits and nothing in the low ones.
            int alpha = (value & 0xffffffffL) == 0 ? (int) (value >>> 32) & 0xff000000 : 0xff000000;
            long badge = (long) (alpha | AccentColor.swap(FILL, hue) & 0xffffff) << 32;
            if (badge != value) HookStatus.counted(FamilyNames.ACCENT_COLOR, COMPOSE_BADGES);
            return badge;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Compose badge", failure);
            return value;
        }
    }

    /**
     * The shades {@link #rememberSurfaces} kept, while Classic colors or Backgrounds in the accent color
     * is painting with them, or null.
     */
    @Nullable
    private static int[] paintedSurfaces() {
        int[] surfaces = darkSurfaces;
        if (surfaces == null) return null;
        if (Utils.settingsReady() && Settings.CLASSIC_COLORS.get()) return surfaces;
        return AccentColor.picked() == null || !Settings.ACCENT_BACKGROUNDS.get() ? null : surfaces;
    }

    /** The shades {@link #rememberSurfaces} kept from a light theme, while Classic colors is painting with them, or null. */
    @Nullable
    private static int[] paintedLightSurfaces() {
        int[] surfaces = lightSurfaces;
        return surfaces != null && Utils.settingsReady() && Settings.CLASSIC_COLORS.get() ? surfaces : null;
    }

    /**
     * Whether Classic colors or Backgrounds in the accent color is painting a dark theme's surfaces. Pure black's fills ask
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
     * Which of {@link #SURFACE_ATTRIBUTES} a light fill of Instagram's stands for, or -1: the white page,
     * and the gray its search fields take from either palette (#639).
     */
    private static int lightStep(int rgb) {
        switch (rgb) {
            case 0xffffff:
                return 0;
            case 0xf3f5f7:
            case 0xf0f2f5:
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
     * Injected right after each load of the fill Instagram's button styles give its default secondary
     * button, [fill], a color resource's number, in light and in dark (#187). That fill is a color of Instagram's own rather than a theme
     * attribute, so Edit profile and Share profile kept Prism's gray over the backgrounds in the accent
     * color. Answers the color [context]'s theme points {@link #SECONDARY_BUTTON_ATTRIBUTE} at once
     * Backgrounds in the accent color or Classic colors have pointed it at one of the patch's shades, the
     * top step the other secondary buttons already take. Answers [fill] while the theme holds
     * Instagram's own color there, with no context or anything thrown.
     */
    public static int secondaryButtonFill(Context context, int fill) {
        try {
            if (context == null) return fill;
            Resources resources = context.getResources();
            int attr = id(resources, SECONDARY_BUTTON_ATTRIBUTE, "attr", Utils.resourcePackage(context));
            TypedValue value = new TypedValue();
            if (attr == 0 || !context.getTheme().resolveAttribute(attr, value, false)
                    || value.type != TypedValue.TYPE_REFERENCE || value.data == 0 || value.data == fill
                    || !resources.getResourceEntryName(value.data).startsWith(OWN_COLORS)) {
                return fill;
            }
            HookStatus.counted(FamilyNames.ACCENT_COLOR, SECONDARY_BUTTONS);
            return value.data;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "secondary buttons", failure);
            return fill;
        }
    }

    /**
     * Injected at the start of the method each of Instagram's tab buttons recolors its icon with, for
     * the color it's told to use, [color] (#663). Opening Reels tells every tab a white for the black
     * bar and leaving it tells them the theme's text color, and neither is the icon color the theme
     * holds, so with Icons in the accent color on the bar went white on Reels and stayed white after.
     * Answers the picked color's link shade for a dark screen when [color] reads light and its shade
     * for a light screen when it reads dark, the shades the icon styles hold, with [color]'s alpha.
     * Answers [color] with the switch off, HushGram paused or anything thrown.
     */
    public static int tabIcons(int color) {
        try {
            AccentHue hue = AccentColor.picked();
            if (hue == null || !Settings.ACCENT_ICONS.get()) return color;
            int shade = AccentColor.swap(readsLight(color) ? LINK_ON_DARK : LINK_ON_LIGHT, hue);
            int icons = (color & 0xff000000) | (shade & 0xffffff);
            if (icons != color) HookStatus.counted(FamilyNames.ACCENT_COLOR, TAB_ICONS);
            return icons;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "tab icons", failure);
            return color;
        }
    }

    /**
     * Injected at the end of the constructor of Litho's resource resolver, which keeps [context]'s theme
     * for every color its tree reads from a theme attribute (#724). The feed's post buttons read
     * {@link #GLYPH_ATTRIBUTE} there, and the context a tree is built on isn't always one the overrides
     * or the theme util reached, so with Icons in the accent color on they stayed black. Lays the picked
     * color's icon style for a dark or a light theme onto that theme when the glyph color it holds isn't
     * the style's yet. A viewer's or a see-through theme keeps its own, since its icons sit over media
     * (see {@link #paintsItsOwnBackground}). Each theme is looked at once. Leaves it alone with either
     * switch off, HushGram paused, the styles missing or anything thrown.
     */
    public static void lithoIcons(Context context) {
        try {
            if (context == null) return;
            AccentHue hue = AccentColor.picked();
            if (hue == null || !Settings.ACCENT_ICONS.get()) return;
            Resources.Theme theme = context.getTheme();
            if (theme == null || !firstLook(theme)) return;
            Resources resources = context.getResources();
            String app = Utils.resourcePackage(context);
            int icon = id(resources, ICON_ATTRIBUTE, "attr", app);
            int glyph = id(resources, GLYPH_ATTRIBUTE, "attr", app);
            boolean dark = icon != 0 && glyph != 0 && dark(context, theme, icon);
            int style = icon == 0 || glyph == 0 ? 0 : id(resources, iconStyle(hue, dark), "style", app);
            if (style == 0) {
                HookStatus.counted(FamilyNames.ACCENT_COLOR, ICONS_MISSING);
                return;
            }
            int background = id(resources, BACKGROUND_ATTRIBUTE, "attr", app);
            if (background != 0 && overMedia(theme, background)) return;
            if (themeColor(theme, glyph) == styleColor(theme, style, glyph)) return;
            theme.applyStyle(style, true);
            HookStatus.counted(FamilyNames.ACCENT_COLOR, LITHO_ICONS);
            applyText(resources, theme, app, hue, dark);
            Logger.printDebug(() -> "Accent color: icons on the Litho theme of " + context.getClass().getName());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Litho icons", failure);
        }
    }

    /** Whether {@link #lithoIcons} hasn't looked at [theme] lately, remembering it when it hasn't. */
    private static boolean firstLook(Resources.Theme theme) {
        synchronized (SETTLED) {
            for (Object slot : SETTLED) {
                if (slot != null && ((WeakReference<?>) slot).get() == theme) return false;
            }
            SETTLED[settledNext] = new WeakReference<>(theme);
            settledNext = (settledNext + 1) % SETTLED.length;
            return true;
        }
    }

    /** Whether [theme] holds a background [attribute] that isn't a solid color of its own, as a viewer's does. */
    private static boolean overMedia(Resources.Theme theme, int attribute) {
        TypedValue value = new TypedValue();
        return theme.resolveAttribute(attribute, value, false) && !paintsItsOwnBackground(theme, attribute);
    }

    /** The color [theme] holds for [attribute], 0 for none. */
    private static int themeColor(Resources.Theme theme, int attribute) {
        TypedArray values = theme.obtainStyledAttributes(new int[] {attribute});
        try {
            return values.getColor(0, 0);
        } finally {
            values.recycle();
        }
    }

    /** The color [style] gives [attribute], resolved in [theme], 0 for none. */
    private static int styleColor(Resources.Theme theme, int style, int attribute) {
        TypedArray values = theme.obtainStyledAttributes(style, new int[] {attribute});
        try {
            return values.getColor(0, 0);
        } finally {
            values.recycle();
        }
    }

    /**
     * Called as Instagram's Keyframes loader starts on the raw animation [resource]: notes the color
     * the animation takes when it's one of the repost button's, for {@link #repostAnimation(Drawable)}.
     * That's the picked icon color, and only where [context]'s theme already holds it for its icons,
     * so the button matches comment, share and save next to it, and Reels and other screens over
     * media keep Instagram's white.
     */
    public static void repostAnimation(Context context, int resource) {
        REPOST_PENDING.remove();
        try {
            if (context == null || resource == 0) return;
            AccentHue hue = AccentColor.picked();
            if (hue == null || !Settings.ACCENT_ICONS.get()) return;
            Resources resources = context.getResources();
            String app = Utils.resourcePackage(context);
            if (!repostRaw(resources, resource, app)) return;
            Resources.Theme theme = context.getTheme();
            int icon = id(resources, ICON_ATTRIBUTE, "attr", app);
            if (theme == null || icon == 0) return;
            int background = id(resources, BACKGROUND_ATTRIBUTE, "attr", app);
            if (background != 0 && overMedia(theme, background)) return;
            int style = id(resources, iconStyle(hue, dark(context, theme, icon)), "style", app);
            if (style == 0) return;
            int color = styleColor(theme, style, icon);
            if (color == 0 || themeColor(theme, icon) != color) return;
            REPOST_PENDING.set(color);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Repost animation", failure);
        }
    }

    /** Whether [resource] is one of the {@link #REPOST_ANIMATIONS}. */
    private static boolean repostRaw(Resources resources, int resource, String app) {
        for (String name : REPOST_ANIMATIONS) {
            if (id(resources, name, "raw", app) == resource) return true;
        }
        return false;
    }

    /**
     * Called with the [animation] Instagram's Keyframes loader hands back, null when the file didn't
     * load: gives it the color {@link #repostAnimation(Context, int)} noted for it, if any.
     */
    public static void repostAnimation(Drawable animation) {
        try {
            Integer color = REPOST_PENDING.get();
            REPOST_PENDING.remove();
            if (color == null || animation == null) return;
            Paint paint = new Paint();
            paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            synchronized (REPOST_PAINTS) {
                REPOST_PAINTS.put(animation, paint);
            }
            HookStatus.counted(FamilyNames.ACCENT_COLOR, REPOST_ICONS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Repost animation", failure);
        }
    }

    /**
     * Called as a Keyframes [animation] starts drawing on [canvas]: one given a color draws into a
     * layer that lays that color over everything it draws, keeping its shape and its motion. The
     * layer reaches a quarter of the icon past its bounds, for an animation that steps outside them.
     */
    public static void repostDraw(Drawable animation, Canvas canvas) {
        try {
            if (animation == null || canvas == null) return;
            Paint paint;
            synchronized (REPOST_PAINTS) {
                if (REPOST_PAINTS.isEmpty()) return;
                paint = REPOST_PAINTS.get(animation);
            }
            if (paint == null) return;
            Rect bounds = animation.getBounds();
            if (bounds.isEmpty()) return;
            float x = bounds.width() / 4f;
            float y = bounds.height() / 4f;
            int count = canvas.saveLayer(bounds.left - x, bounds.top - y, bounds.right + x, bounds.bottom + y, paint);
            REPOST_LAYER.set(new RepostLayer(animation, canvas, count));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Repost animation", failure);
        }
    }

    /** Called as a Keyframes [animation] is done drawing on [canvas]: closes the layer {@link #repostDraw} opened. */
    public static void repostDrawn(Drawable animation, Canvas canvas) {
        try {
            RepostLayer layer = REPOST_LAYER.get();
            if (layer == null || layer.animation != animation || layer.canvas != canvas) return;
            REPOST_LAYER.remove();
            canvas.restoreToCount(layer.count);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Repost animation", failure);
        }
    }

    /** A layer {@link #repostDraw} opened: the animation drawing, its canvas and the save count to go back to. */
    private static final class RepostLayer {
        final Drawable animation;
        final Canvas canvas;
        final int count;

        RepostLayer(Drawable animation, Canvas canvas, int count) {
            this.animation = animation;
            this.canvas = canvas;
            this.count = count;
        }
    }

    /**
     * Injected at the return of the method a reel's button column reads its color with, for the color
     * it read, [color] (#187): the white Instagram writes the counts in over the video, or the theme's
     * text color in the column's look under the video, which also tints its icons. With Reel buttons in
     * the accent color on, answers the picked color's link shade for a dark screen when [color] reads
     * light and its shade for a light screen when it reads dark, with [color]'s alpha, as
     * {@link #tabIcons(int)} does for the tab bar. Answers [color] with the switch off, HushGram paused
     * or anything thrown.
     */
    public static int reelButtons(int color) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            AccentHue hue = AccentColor.picked();
            if (hue == null || !Settings.ACCENT_REELS.get()) return color;
            int shade = AccentColor.swap(readsLight(color) ? LINK_ON_DARK : LINK_ON_LIGHT, hue);
            int buttons = (color & 0xff000000) | (shade & 0xffffff);
            if (buttons != color) HookStatus.counted(FamilyNames.ACCENT_COLOR, REEL_BUTTONS);
            return buttons;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "reel buttons", failure);
            return color;
        }
    }

    /**
     * Injected where a reel's button column builds each of its buttons, for the color filter the button
     * is built with, [filter] (#187). Over the video the column builds them with none, so their icons
     * keep the white they're drawn in. With Reel buttons in the accent color on, answers a filter that
     * multiplies what an icon draws by the picked color's link shade for a dark screen: white turns into
     * that shade, the dark shadow under an icon stays dark and a liked heart's red stays a red. A filter
     * Instagram built is kept, since it's made from {@link #reelButtons(int)}'s answer. Answers [filter]
     * with the switch off, HushGram paused or anything thrown.
     */
    @Nullable
    public static ColorFilter reelButtons(@Nullable ColorFilter filter) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if (filter != null) return filter;
            AccentHue hue = AccentColor.picked();
            if (hue == null || !Settings.ACCENT_REELS.get()) return null;
            int shade = AccentColor.swap(LINK_ON_DARK, hue) & 0xffffff;
            ReelFilter known = reelFilter;
            if (known == null || known.shade != shade) {
                known = new ReelFilter(shade, new LightingColorFilter(shade, 0));
                reelFilter = known;
            }
            HookStatus.counted(FamilyNames.ACCENT_COLOR, REEL_BUTTONS);
            return known.filter;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "reel button icons", failure);
            return filter;
        }
    }

    /** The filter {@link #reelButtons(ColorFilter)} last made, kept with its shade so each icon shares it. */
    @Nullable
    private static volatile ReelFilter reelFilter;

    /** A filter multiplying by [shade], an RGB with no alpha. */
    private static final class ReelFilter {
        final int shade;
        final ColorFilter filter;

        ReelFilter(int shade, ColorFilter filter) {
            this.shade = shade;
            this.filter = filter;
        }
    }

    /** {@link #tabIcons(int)} for the color a tab is told to use while it isn't selected, which comes boxed. Null stays null. */
    @Nullable
    public static Integer tabIcons(@Nullable Integer color) {
        if (color == null) return null;
        int icons = tabIcons(color.intValue());
        return icons == color ? color : Integer.valueOf(icons);
    }

    /**
     * Injected where a screen's top bar colors its back arrow and buttons, and where a profile's
     * Posts, Reels, Reposts and Tagged tabs are told their colors, for that color, [color] (#187). Both
     * read Instagram's text and icon color, or a gray for a tab you aren't on, rather than the icon
     * color the theme holds, so with Icons in the accent color on they stayed white or black. Answers
     * the picked color's link shade for a dark screen when [color] reads light and its shade for a light
     * screen when it reads dark, as {@link #tabIcons(int)} does. A gray in between keeps 60% of its
     * alpha, so the tabs you aren't on stay dimmer than the one you are. A color of its own, like a red
     * or Instagram's blue, and a see-through one are left as they are. Answers [color] with the switch
     * off, HushGram paused or anything thrown.
     */
    public static int barIcons(int color) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            AccentHue hue = AccentColor.picked();
            int alpha = color >>> 24;
            if (hue == null || alpha == 0 || !neutral(color) || !Settings.ACCENT_ICONS.get()) return color;
            int shade = AccentColor.swap(readsLight(color) ? LINK_ON_DARK : LINK_ON_LIGHT, hue);
            int brightness = brightness(color);
            if (brightness >= SECONDARY_LOW && brightness <= SECONDARY_HIGH) alpha = alpha * 3 / 5;
            int icons = (alpha << 24) | (shade & 0xffffff);
            if (icons != color) HookStatus.counted(FamilyNames.ACCENT_COLOR, BAR_ICONS);
            return icons;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "bar icons", failure);
            return color;
        }
    }

    /**
     * Injected in place of the top bar's own maker of the color filter it lays on its buttons, which
     * keeps one SRC_ATOP filter per color (#187). Answers the same kind of filter for
     * {@link #barIcons(int)}'s answer to [color], kept the same way, so with the switch off the bar gets
     * the filter it always did.
     */
    public static ColorFilter barIconFilter(int color) {
        int icons = barIcons(color);
        synchronized (BAR_FILTERS) {
            ColorFilter filter = BAR_FILTERS.get(icons);
            if (filter == null) {
                filter = new PorterDuffColorFilter(icons, PorterDuff.Mode.SRC_ATOP);
                BAR_FILTERS.put(icons, filter);
            }
            return filter;
        }
    }

    /**
     * Injected in place of the top bar's ImageView.setColorFilter(int) on its back arrow (#187): lays
     * {@link #barIcons(int)}'s answer to [color] on [view] the way that call does.
     */
    public static void setBarIconColor(ImageView view, int color) {
        view.setColorFilter(barIcons(color));
    }

    /**
     * Injected right after the Search tab's top bar makes its Saved bookmark, [view] (#187). The
     * bookmark's drawable carries Instagram's text and icon color, so with Icons in the accent color on
     * this lays the color [view]'s theme holds for {@link #ICON_ATTRIBUTE} over it. Leaves it alone with
     * the switch off, HushGram paused, no plain color there or anything thrown.
     */
    public static void searchSaved(ImageView view) {
        try {
            Integer color = view == null ? null : iconColor(view.getContext());
            if (color == null) return;
            view.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            HookStatus.counted(FamilyNames.ACCENT_COLOR, BAR_ICONS);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "search saved", failure);
        }
    }

    /**
     * Injected where one of Saved's filters, [tab], is about to be given [background], the drawable
     * Instagram just loaded for the id [id] (#187); the answer is given in its place. Saved's filters
     * along the top (All, Collections, Reels and the rest) are tabs whose background is
     * {@link #SAVED_TAB}: a selector filling the one you're on with Instagram's secondary button color
     * and outlining the others in its stroke color, which other borders share, so no style can reach
     * them alone. With Filters in the accent color on, the answer is the same two pills in the picked
     * color: the outlined ones bordered in its link shade for the screen, the selected one filled
     * with its fill. The tab's title follows, in the link shade, or white on the fill (black on a
     * light one). Any other background, the switch off, HushGram paused or anything thrown answers
     * [background] and leaves the tab as it is.
     */
    public static Drawable savedFilter(Drawable background, View tab, int id) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            AccentHue hue = AccentColor.picked();
            if (background == null || tab == null || id == 0 || hue == null || !Settings.ACCENT_PILLS.get()) return background;
            Context context = tab.getContext();
            Resources resources = context.getResources();
            String app = Utils.resourcePackage(context);
            if (id != id(resources, SAVED_TAB, "drawable", app)) return background;
            float density = resources.getDisplayMetrics().density;
            int round = id(resources, CHIP_RADIUS, "dimen", app);
            float radius = round != 0 ? resources.getDimension(round) : CHIP_RADIUS_DP * density;
            // Without the icon attribute, the screen's night mode decides.
            boolean dark = dark(context, context.getTheme(), id(resources, ICON_ATTRIBUTE, "attr", app));
            int link = 0xff000000 | AccentColor.swap(dark ? LINK_ON_DARK : LINK_ON_LIGHT, hue);
            int fill = 0xff000000 | AccentColor.swap(FILL, hue);
            GradientDrawable picked = new GradientDrawable();
            picked.setCornerRadius(radius);
            picked.setColor(fill);
            GradientDrawable others = new GradientDrawable();
            others.setCornerRadius(radius);
            others.setColor(0);
            others.setStroke(Math.max(1, Math.round(PILL_STROKE_DP * density)), link);
            StateListDrawable pills = new StateListDrawable();
            pills.addState(new int[] {android.R.attr.state_selected}, picked);
            pills.addState(new int[0], others);
            if (tab instanceof TextView) {
                TextView title = (TextView) tab;
                ColorStateList titles = new ColorStateList(
                        new int[][] {{android.R.attr.state_selected}, {}},
                        new int[] {readsLight(fill) ? 0xff000000 : 0xffffffff, link});
                title.setTextColor(titles);
                for (Drawable icon : title.getCompoundDrawablesRelative()) {
                    if (icon == null) continue;
                    title.setCompoundDrawableTintList(titles);
                    break;
                }
            }
            HookStatus.counted(FamilyNames.ACCENT_COLOR, PILLS);
            return pills;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "saved filter", failure);
            return background;
        }
    }

    /** Whether [rgb] is a gray, its channels no more than {@link #NEUTRAL_SPREAD} apart. */
    static boolean neutral(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= NEUTRAL_SPREAD;
    }

    /** [rgb]'s weighted brightness, 0 to 255, the one {@link #readsLight} reads. */
    private static int brightness(int rgb) {
        return (299 * ((rgb >> 16) & 0xff) + 587 * ((rgb >> 8) & 0xff) + 114 * (rgb & 0xff)) / 1000;
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

    /** The style pointing Instagram's primary text attributes at [hue]'s link shade for a dark theme or a light one (#187). */
    static String textStyle(AccentHue hue, boolean dark) {
        return "HushGramAccentText_" + hue.name() + (dark ? "_dark" : "_light");
    }

    /**
     * Lays [hue]'s text style for a [dark] theme or a light one onto [theme] right after its icon style
     * with Text in the accent color on (#187), counting it, or counting it missing on a build without
     * the text styles.
     */
    private static void applyText(Resources resources, Resources.Theme theme, String app, AccentHue hue, boolean dark) {
        if (!Settings.ACCENT_TEXT.get()) return;
        int text = id(resources, textStyle(hue, dark), "style", app);
        if (text == 0) {
            HookStatus.counted(FamilyNames.ACCENT_COLOR, TEXT_MISSING);
            return;
        }
        theme.applyStyle(text, true);
        HookStatus.counted(FamilyNames.ACCENT_COLOR, TEXT);
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

    /** The style pointing Instagram's background attributes at its older look for a dark theme or a light one (#639). */
    static String classicStyle(boolean dark) {
        return dark ? "HushGramClassicBackgrounds_dark" : "HushGramClassicBackgrounds_light";
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

    static int id(Resources resources, String name, String type, String app) {
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
