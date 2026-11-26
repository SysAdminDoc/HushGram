/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.res.Resources;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.stories.StoryRing;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Story rings in the accent color on the rings the theme doesn't reach (#528).
 *
 * <p>The ring style {@link AccentTheme} lays on each theme reaches the rings that read their colors
 * from it, such as a loading story's. Most story rings don't. Instagram's default ring holds its
 * yellow, orange, pink and lavender as colors it read once, from a context no accent theme is laid
 * on, so the rings on profiles, posts and chats kept Instagram's gradient with Story rings in the
 * accent color on. Its Compose screens build their rings from those same colors.
 *
 * <p>{@link AccentTheme} keeps the four shades the ring style gives those colors' attributes, light
 * to deep as the default ring runs. The patch hands every gradient the ring view makes to
 * {@link #colors}, and the default ring's colors on Compose screens to {@link #composeColors},
 * and they answer the kept shades for Instagram's own story gradient. A seen story's ring is drawn
 * in one gray the ring view is handed apart from its gradient, which goes to {@link #seen} (#187),
 * and the chat list's rows draw theirs in a Compose gray that goes to {@link #composeSeen}.
 * The chat list's rings make their brush from Compose's own yellow, orange, pink and purple, which go
 * to {@link #composeBrush} (#683).
 * A color picked under Story ring size's Ring color wins, so the two never fight over a ring.
 */
public final class AccentRings {
    /** What's counted each time a story ring outside the theme takes the accent shades. */
    static final String GIVEN = "story rings given the accent outside the theme";

    /**
     * The attributes Instagram's default ring reads its colors from, in the order the ring runs
     * through them on 454: yellow, orange, pink, then lavender. The patch's RING_ATTRIBUTES hold
     * the shade each takes.
     */
    static final String[] ATTRIBUTES = {
            "igds_color_gradient_yellow", "igds_color_gradient_orange",
            "igds_color_gradient_pink", "igds_color_gradient_lavender",
    };

    /** The shades the ring style last gave {@link #ATTRIBUTES}, or null for none yet. */
    @Nullable
    private static volatile int[] kept;

    private AccentRings() {
    }

    /**
     * Keeps the shades [theme] holds for {@link #ATTRIBUTES} once the ring style is on it. Leaves
     * the last ones if any can't be read as a color.
     */
    static void remember(Resources resources, Resources.Theme theme, String app) {
        int[] shades = new int[ATTRIBUTES.length];
        TypedValue value = new TypedValue();
        for (int step = 0; step < shades.length; step++) {
            int attr = AccentTheme.id(resources, ATTRIBUTES[step], "attr", app);
            if (attr == 0 || !theme.resolveAttribute(attr, value, true)
                    || value.type < TypedValue.TYPE_FIRST_COLOR_INT || value.type > TypedValue.TYPE_LAST_COLOR_INT) {
                return;
            }
            shades[step] = value.data;
        }
        kept = shades;
    }

    /** Sets what {@link #remember} keeps, for tests. */
    static void keptForTests(@Nullable int[] shades) {
        kept = shades;
    }

    /** What {@link #remember} kept, for tests. */
    @Nullable
    static int[] keptShades() {
        int[] shades = kept;
        return shades == null ? null : shades.clone();
    }

    /**
     * Injected first where the ring view builds a gradient, with its colors and stops. Answers the
     * kept accent shades for Instagram's own story gradient while Accent color and Story rings in
     * the accent color are on, and [colors] itself for every other ring (Close Friends, a Live's
     * pink, the rainbow), while a Ring color is picked, before a theme has been given the ring
     * style, and when anything goes wrong. Never changes the array it was given, and never throws.
     */
    public static int[] colors(int[] colors, float[] stops) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if (!StoryRing.instagramsOwn(colors, stops)) return colors;
            return accent(colors);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "story ring", failure);
            return colors;
        }
    }

    /**
     * Injected where Compose screens turn the default ring's colors into an array. The same as
     * {@link #colors} for that one ring, which has no stops at hand there: answers the kept shades
     * when Instagram gives as many colors as there are shades, and [colors] otherwise.
     */
    public static int[] composeColors(int[] colors) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if (colors == null || colors.length != ATTRIBUTES.length) return colors;
            return accent(colors);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "Compose story ring", failure);
            return colors;
        }
    }

    /** What's counted each time a ring on the chat list takes the accent shades. */
    static final String CHAT_LIST = "chat list story rings given the accent";

    /** The default ring's four stops, which the chat list's ring brush pairs its colors with. */
    private static final float[] STOPS = {0.1435f, 0.3779f, 0.6071f, 0.831f};

    /**
     * Injected first in the brush the chat list's story rings are made with (#683), with the list of
     * Compose colors it pairs with the default ring's four stops: Compose's own yellow, orange, pink and
     * purple, or the ones the server sends. Answers the kept shades in a list of their own, boxed as
     * Instagram's are, for four colors that aren't all one while Accent color and Story rings in the
     * accent color are on and no Ring color is picked, as {@link #colors} does for the ring view.
     * Answers [colors] itself for any other list, one it can't read included, and never changes it.
     * Never throws.
     */
    public static List<?> composeBrush(List<?> colors) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if (colors == null || colors.size() != ATTRIBUTES.length) return colors;
            int[] shades = kept;
            if (shades == null || shades.length != colors.size()) return colors;
            if (AccentColor.picked() == null || !Settings.ACCENT_RINGS.get() || StoryRing.picksColor()) return colors;
            int[] argb = BoxedColors.argb(colors);
            if (argb == null || !StoryRing.instagramsOwn(argb, STOPS)) return colors;
            List<Object> boxed = BoxedColors.boxed(colors.get(0), shades);
            if (boxed == null) return colors;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, CHAT_LIST);
            return boxed;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "chat list story ring", failure);
            return colors;
        }
    }

    /** What's counted each time a seen story's ring takes the accent. */
    static final String SEEN = "seen story rings given the accent";

    /**
     * The alpha a seen ring's fill takes, 30%. Over Instagram's dark page that's about as far from it
     * as its own seen gray (#2b3036 on #0c1014), and over a white one about as pale as #dbdfe4.
     */
    static final int SEEN_ALPHA = 0x4d;

    /** Prism's indigo, Instagram's fill, which {@link AccentColor#swap(int, AccentHue)} answers as the picked fill. */
    private static final int FILL = 0xff4a5df9;

    /**
     * Injected first in the ring view's setter for its inactive color, the one a seen story's ring is
     * drawn with (#187). Every ring view's constructor hands it the gray its theme holds for a seen
     * ring, and a few screens hand it a gray of their own. Answers an opaque gray (not black or
     * white) as the picked color's fill at {@link #SEEN_ALPHA}, which the view keeps in a shader,
     * while Accent color and Story rings in the accent color are on and no Ring color is picked.
     * Answers [color] for anything else, a see-through or colored ring included. Never throws.
     */
    public static int seen(int color) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            int shade = seenShade(color);
            if (shade != color) HookStatus.counted(FamilyNames.ACCENT_COLOR, SEEN);
            return shade;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "seen story ring", failure);
            return color;
        }
    }

    /** What's counted each time a seen story's ring on the chat list takes the accent. */
    static final String CHAT_LIST_SEEN = "seen chat list story rings given the accent";

    /** The low half of a Compose color, which holds its color space and is all zero for an sRGB one. */
    private static final long COLOR_SPACE = 0xffffffffL;

    /**
     * Injected right where the chat list's rows read the Compose color they draw a seen story's ring in
     * (#187), a gray from Compose's palette. Those rows draw their own ring, so the ring view's
     * {@link #seen} never sees it. Answers what {@link #seen} would for the gray, as a Compose color,
     * for an sRGB one, and [color] for anything else. Never throws.
     */
    public static long composeSeen(long color) {
        try {
            HookStatus.invoked(FamilyNames.ACCENT_COLOR);
            if ((color & COLOR_SPACE) != 0) return color;
            int argb = (int) (color >>> 32);
            int shade = seenShade(argb);
            if (shade == argb) return color;
            HookStatus.counted(FamilyNames.ACCENT_COLOR, CHAT_LIST_SEEN);
            return (long) shade << 32;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ACCENT_COLOR, "seen chat list story ring", failure);
            return color;
        }
    }

    /** The shade a seen ring's [color] takes: the picked fill at {@link #SEEN_ALPHA} for a seen gray, [color] otherwise. */
    private static int seenShade(int color) {
        if (!seenGray(color)) return color;
        AccentHue hue = AccentColor.picked();
        if (hue == null || !Settings.ACCENT_RINGS.get() || StoryRing.picksColor()) return color;
        int fill = AccentColor.swap(FILL, hue);
        if (fill == FILL) return color;
        return SEEN_ALPHA << 24 | fill & 0xffffff;
    }

    /** Whether [color] is an opaque gray between near-black and near-white, as Instagram's seen rings are. */
    private static boolean seenGray(int color) {
        if (color >>> 24 != 0xff) return false;
        int red = color >> 16 & 0xff;
        int green = color >> 8 & 0xff;
        int blue = color & 0xff;
        int most = Math.max(red, Math.max(green, blue));
        int least = Math.min(red, Math.min(green, blue));
        return most - least <= 0x10 && least >= 0x20 && most <= 0xf0;
    }

    private static int[] accent(int[] colors) {
        int[] shades = kept;
        if (shades == null || shades.length != colors.length) return colors;
        if (AccentColor.picked() == null || !Settings.ACCENT_RINGS.get() || StoryRing.picksColor()) return colors;
        HookStatus.counted(FamilyNames.ACCENT_COLOR, GIVEN);
        return shades.clone();
    }
}
