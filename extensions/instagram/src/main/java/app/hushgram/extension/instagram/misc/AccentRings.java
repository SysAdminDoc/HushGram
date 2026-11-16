/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.res.Resources;
import android.util.TypedValue;

import androidx.annotation.Nullable;

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
 * and they answer the kept shades for Instagram's own story gradient. A color picked under Story
 * ring size's Ring color wins, so the two never fight over a ring.
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

    private static int[] accent(int[] colors) {
        int[] shades = kept;
        if (shades == null || shades.length != colors.length) return colors;
        if (AccentColor.picked() == null || !Settings.ACCENT_RINGS.get() || StoryRing.picksColor()) return colors;
        HookStatus.counted(FamilyNames.ACCENT_COLOR, GIVEN);
        return shades.clone();
    }
}
