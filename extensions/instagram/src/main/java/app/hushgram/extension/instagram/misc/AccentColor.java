/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.os.Parcel;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Accent color" patch: Instagram's blue turns into the color picked in HushGram
 * settings.
 *
 * <p>Instagram reads most of its colors from its theme, through small methods that resolve a theme
 * attribute and answer the color (two plain ones and two that take a fallback color), and reads a
 * button's colors for each of its states as a color state list through AndroidX's loader. Its text
 * spans (mentions, hashtags, links) take the value the attribute resolves to straight from one more
 * read. The patch hands every color those reads answer to {@link #color}, the value read's answer to
 * {@link #value}, and every list the loader answers to {@link #list}. Its Compose screens, such as
 * the Activity tab and a profile's bio, take theirs from palettes of colors written into the code,
 * and each accent blue those store goes through {@link #composeColor}.
 *
 * <p>Only Instagram's exact accent blues change, each into the shade of the picked color that plays
 * the same part (see {@link #swap}), and each keeps its alpha. So a list keeps its states: a disabled
 * button's faded blue becomes the faded new color, and a gray or white state stays gray or white. No
 * other color is touched, so text on the buttons stays Instagram's own white. Colors already on
 * screen keep theirs, so a change shows after a restart.
 *
 * <p>Both hooks fail open: with the switch off, Instagram blue picked, HushGram paused, the settings
 * not read yet or anything thrown, Instagram's own color goes through.
 */
public final class AccentColor {
    /** What's counted when a color read from the theme is swapped. */
    static final String COLOR = "accent colors swapped";

    /** What's counted when a color state list from AndroidX's loader is swapped. */
    static final String LIST = "accent color lists swapped";

    /** What's counted when a color one of Instagram's Compose palettes holds is swapped. */
    static final String COMPOSE = "Compose palette colors swapped";

    /** What's counted when a list didn't read back as states and colors, so it was left alone. */
    static final String UNREAD = "color lists it couldn't read";

    /** What's counted when a profile's verified badge takes the picked color. */
    static final String BADGES = "verified badges colored";

    /** The id Instagram gives the verified badge by a profile's name in its top bar. */
    private static final String VERIFIED_BADGE = "action_bar_title_verified_badge";

    /** The blue drawn into the verified badge, Instagram's blue_5. */
    private static final int BADGE_BLUE = 0xff0095f6;

    /** The badge's id once looked up, -1 before. */
    private static volatile int verifiedBadgeId = -1;

    /** The badge's id for tests, in place of a lookup. */
    static volatile Integer verifiedBadgeIdForTests;

    /** The most states a list is read with. Instagram's have a handful. */
    private static final int MOST_STATES = 64;

    /** A color list's own hidden getters, null where they're kept from apps. */
    private static volatile Method statesGetter;
    private static volatile Method colorsGetter;

    /** Whether the getters were looked up yet. Written after them. */
    private static volatile boolean gettersLooked;

    /** Whether the log has said a list couldn't be read, which it says once a run. */
    private static volatile boolean toldUnread;

    /**
     * Each list swapped, by the list the loader answered, for the color it was swapped to. The
     * loader keeps its own lists, so the same one comes back, and the swap is worked out once. Weak
     * keys let a list go when Instagram drops it.
     */
    private static final Map<ColorStateList, Swapped> LISTS = new WeakHashMap<>();

    private AccentColor() {
    }

    /**
     * Injected before each theme color read returns. Answers the color to use for [color]: the
     * picked color's shade for an accent blue, [color] itself for anything else. Never throws.
     */
    public static int color(int color) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if (role(color) == Role.NONE) return color;
            AccentHue hue = picked();
            if (hue == null) return color;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, COLOR);
            return swap(color, hue);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "theme color", failure);
            return color;
        }
    }

    /**
     * Injected where one of Instagram's Compose palettes stores an accent blue, as the palette is set
     * up. A Compose color in the sRGB space holds its ARGB in the high 32 bits and nothing in the low
     * ones; answers [value] as the picked color's shade then, keeping the alpha, and [value] itself
     * for any other color. Never throws.
     */
    public static long composeColor(long value) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if ((value & 0xffffffffL) != 0) return value;
            int argb = (int) (value >>> 32);
            if (role(argb) == Role.NONE) return value;
            AccentHue hue = picked();
            if (hue == null) return value;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, COMPOSE);
            return (long) swap(argb, hue) << 32;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Compose color", failure);
            return value;
        }
    }

    /**
     * Injected before Instagram's theme value read returns: the one that answers what an attribute
     * resolves to as it stands, which is how mentions, hashtags and links in captions, comments
     * and search get their color (#8). Answers [data] swapped as {@link #color} does when [value]
     * holds a color, and [data] itself for a size, a number or anything else. Never throws.
     */
    public static int value(int data, TypedValue value) {
        try {
            if (value == null || value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) {
                return data;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "theme value", failure);
            return data;
        }
        return color(data);
    }

    /**
     * Injected before each return of AndroidX's color state list loader. Answers [list] with its
     * accent blues swapped, or [list] itself when it holds none. Never throws.
     */
    public static ColorStateList list(ColorStateList list) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            // A subclass may draw its own way, so only a plain list is rebuilt.
            if (list == null || list.getClass() != ColorStateList.class) return list;
            AccentHue hue = picked();
            if (hue == null) return list;
            Swapped known;
            synchronized (LISTS) {
                known = LISTS.get(list);
            }
            if (known == null || known.hue != hue) {
                known = new Swapped(hue, swap(list, hue));
                synchronized (LISTS) {
                    LISTS.put(list, known);
                }
            }
            if (known.list == null) return list;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, LIST);
            return known.list;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "color list", failure);
            return list;
        }
    }

    /**
     * Injected at the end of Instagram's text view constructors. A color a layout gives a text view
     * in its XML is read by the framework and never meets the reads above, which left the end of
     * Home's "Older posts" link blue (#187). Hands the colors it was built with to {@link #list}, and
     * gives it the answer when that's a new list. Never throws.
     */
    public static void text(TextView view) {
        try {
            if (view == null) return;
            ColorStateList before = view.getTextColors();
            ColorStateList after = list(before);
            if (after != before) view.setTextColor(after);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "text view", failure);
        }
    }

    /**
     * Injected at the end of Instagram's image view constructors. A tint a layout gives an image view
     * in its XML (android:tint) is read by the framework, as the verified badge on a profile's top bar
     * has it, so it stayed blue (#187). Hands the view's tint to {@link #list}, and gives it the answer
     * when that's a new list. Then hands the view to {@link AccentTheme#textIcons}, for a tint in
     * Instagram's text color. Never throws.
     */
    public static void image(ImageView view) {
        try {
            if (view == null) return;
            if (view.getId() != 0 && view.getId() == verifiedBadgeId(view.getContext())) {
                verifiedBadge(view);
                return;
            }
            ColorStateList before = view.getImageTintList();
            if (before == null) return;
            ColorStateList after = list(before);
            if (after != before) view.setImageTintList(after);
            AccentTheme.textIcons(view);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "image view", failure);
        }
    }

    /**
     * The verified badge by a profile's name in its top bar has no tint: its blue is drawn into the
     * badge itself, so {@link #image} found nothing to swap and it stayed blue (#187, #721). The check
     * is cut out of the badge, so the picked fill laid over what the badge draws keeps the check.
     */
    private static void verifiedBadge(ImageView view) {
        AccentHue hue = picked();
        if (hue == null) return;
        int fill = swap(BADGE_BLUE, hue);
        if (fill == BADGE_BLUE) return;
        view.setColorFilter(fill, PorterDuff.Mode.SRC_IN);
        HookStatus.counted(FamilyNames.ACCENT_COLOR, BADGES);
    }

    /** action_bar_title_verified_badge's id, looked up once; 0 when Instagram has none. */
    private static int verifiedBadgeId(Context context) {
        Integer forTests = verifiedBadgeIdForTests;
        if (forTests != null) return forTests;
        int id = verifiedBadgeId;
        if (id == -1) {
            id = context.getResources().getIdentifier(VERIFIED_BADGE, "id", Utils.resourcePackage(context));
            verifiedBadgeId = id;
        }
        return id;
    }

    /**
     * The color picked, or null for Instagram's own: switch off, blue picked, paused or not ready,
     * Material You with no wallpaper palette to read, this read being one of that palette's tones, or
     * Custom with no color typed.
     */
    static AccentHue picked() {
        if (!Utils.settingsReady() || !Settings.ACCENT_COLOR.get() || MaterialYou.reading()) return null;
        AccentHue hue = Settings.ACCENT_HUE.get();
        if (hue == AccentHue.WALLPAPER && MaterialYou.shades() == null) return null;
        if (hue == AccentHue.CUSTOM && CustomAccent.shades() == null) return null;
        return hue == null || hue == AccentHue.BLUE ? null : hue;
    }

    /** The part an accent blue plays, read from its red, green and blue alone. */
    enum Role {
        NONE, FILL, PRESSED, DIM, LINK_ON_LIGHT, LINK_ON_DARK, PALE
    }

    /**
     * Instagram's accent blues on 450. Its older blue (blue_5, #0095f6, with its see-through steps)
     * and its Prism indigo (#4a5df9) fill buttons and highlights. Prism's indigo_06 is the pressed
     * shade, indigo_08 the dim one, and the bright text links take #3143e3 or #3849da on a light
     * screen and #85a1ff or #8593ff on a dark one. The older theme, which accounts outside Prism
     * get, draws links (mentions, hashtags, bio links) in blue_8 (#00376b) on a light screen and in
     * blue_1 (#e0f1ff) on a dark one, and fills a selected pill or comment with that same pale blue.
     *
     * 451 adds shades of its own. indigo_0700 (#708dff) is the link on a dark screen, which code
     * picks rather than a theme attribute. indigo_0800 (#5c77ff), indigo_0900 (#5465fe) and
     * indigo_1100 (#4150f7) fill badges, carousel dots and strong buttons, and indigo_000, _0100,
     * _050 and _0300 are a selected pill's pale backgrounds, held down or not.
     *
     * <p>Prism's indigo_02 (#7595ff) is the link on a dark screen too, Home's "Older posts" over its
     * suggested posts (#187).
     */
    static Role role(int color) {
        switch (color & 0xffffff) {
            case 0x0095f6:
            case 0x4a5df9:
            case 0x5c77ff:
            case 0x5465fe:
            case 0x4150f7:
                return Role.FILL;
            case 0x3b52e7:
                return Role.PRESSED;
            case 0x232e67:
                return Role.DIM;
            case 0x3143e3:
            case 0x3849da:
            case 0x00376b:
                return Role.LINK_ON_LIGHT;
            case 0x85a1ff:
            case 0x8593ff:
            case 0x708dff:
            case 0x7595ff:
                return Role.LINK_ON_DARK;
            case 0xe0f1ff:
            case 0xeff1ff:
            case 0xebf1ff:
            case 0xdbdfff:
            case 0xccddff:
                return Role.PALE;
            default:
                return Role.NONE;
        }
    }

    /**
     * [color] as [hue]'s shade for the part it plays, with its own alpha, or [color] for no part, or
     * when Material You's palette can't be read or no custom color is typed.
     */
    static int swap(int color, AccentHue hue) {
        Role role = role(color);
        if (hue == AccentHue.WALLPAPER || hue == AccentHue.CUSTOM) {
            int[] shades = hue == AccentHue.WALLPAPER ? MaterialYou.shades() : CustomAccent.shades();
            if (role == Role.NONE || shades == null) return color;
            return (color & 0xff000000) | shades[role.ordinal() - 1];
        }
        int rgb;
        switch (role) {
            case FILL:
                rgb = hue.fill;
                break;
            case PRESSED:
                rgb = hue.pressed;
                break;
            case DIM:
                rgb = hue.dim;
                break;
            case LINK_ON_LIGHT:
                rgb = hue.linkOnLight;
                break;
            case LINK_ON_DARK:
                rgb = hue.linkOnDark;
                break;
            case PALE:
                rgb = hue.pale;
                break;
            default:
                return color;
        }
        return (color & 0xff000000) | rgb;
    }

    /**
     * A new list with [list]'s states and its colors swapped, or null when none of its colors is an
     * accent blue or it can't be read (see {@link #read(ColorStateList)}).
     */
    static ColorStateList swap(ColorStateList list, AccentHue hue) {
        Read read = read(list);
        if (read == null) {
            HookStatus.counted(FamilyNames.ACCENT_COLOR, UNREAD);
            if (!toldUnread) {
                toldUnread = true;
                Logger.printDebug(() -> "Accent color: couldn't read a color list, " + parcelSize(list) + " bytes");
            }
            return null;
        }
        int[] colors = read.colors;
        boolean changed = false;
        for (int i = 0; i < colors.length; i++) {
            int swapped = swap(colors[i], hue);
            changed |= swapped != colors[i];
            colors[i] = swapped;
        }
        return changed ? new ColorStateList(read.states, colors) : null;
    }

    /**
     * [list]'s states and a copy of its colors, or null when they can't be read. A list has no public
     * getter for them. Its own hidden ones answer first; where a build keeps those from apps, the
     * states and colors are read back from the start of the parcel it writes. A phone's own build can
     * write more after them (#727), and that's left unread.
     */
    @Nullable
    static Read read(ColorStateList list) {
        Read read = fromGetters(list);
        if (read != null) return read;
        Parcel parcel = Parcel.obtain();
        try {
            list.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return read(parcel);
        } catch (Throwable failure) {
            return null;
        } finally {
            parcel.recycle();
        }
    }

    /**
     * The states and colors a color list's parcel starts with: the states' count, each state as an int
     * array and then the colors as one. Null when it doesn't read that way.
     */
    @Nullable
    static Read read(Parcel parcel) {
        int count = parcel.readInt();
        if (count < 0 || count > MOST_STATES) return null;
        int[][] states = new int[count][];
        for (int i = 0; i < count; i++) {
            states[i] = parcel.createIntArray();
            if (states[i] == null) return null;
        }
        return checked(states, parcel.createIntArray());
    }

    @Nullable
    private static Read fromGetters(ColorStateList list) {
        if (!gettersLooked) {
            Method states = null;
            Method colors = null;
            try {
                states = ColorStateList.class.getMethod("getStates");
                colors = ColorStateList.class.getMethod("getColors");
            } catch (Throwable failure) {
                Logger.printDebug(() -> "Accent color: a color list's getters are kept from apps: " + failure);
            }
            statesGetter = states;
            colorsGetter = colors;
            gettersLooked = true;
        }
        Method states = statesGetter;
        Method colors = colorsGetter;
        if (states == null || colors == null) return null;
        try {
            return checked((int[][]) states.invoke(list), (int[]) colors.invoke(list));
        } catch (Throwable failure) {
            return null;
        }
    }

    /** [states] and a copy of [colors] when there's a color for each state, or null. */
    @Nullable
    private static Read checked(@Nullable int[][] states, @Nullable int[] colors) {
        if (states == null || colors == null || colors.length != states.length || states.length > MOST_STATES) return null;
        for (int[] state : states) {
            if (state == null) return null;
        }
        return new Read(states.clone(), colors.clone());
    }

    private static int parcelSize(ColorStateList list) {
        Parcel parcel = Parcel.obtain();
        try {
            list.writeToParcel(parcel, 0);
            return parcel.dataSize();
        } catch (Throwable failure) {
            return -1;
        } finally {
            parcel.recycle();
        }
    }

    /** A color list's states and colors, read. The colors are a copy, free to change. */
    static final class Read {
        final int[][] states;
        final int[] colors;

        Read(int[][] states, int[] colors) {
            this.states = states;
            this.colors = colors;
        }
    }

    /** A list's swap for one color, null when it had nothing to swap. */
    private static final class Swapped {
        final AccentHue hue;
        final ColorStateList list;

        Swapped(AccentHue hue, ColorStateList list) {
            this.hue = hue;
            this.list = list;
        }
    }
}
