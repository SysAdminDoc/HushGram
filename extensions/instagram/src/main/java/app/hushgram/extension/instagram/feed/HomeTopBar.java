/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Keep Home's top bar patch (#223, #367, #408): keeps Home's header, the Instagram
 * logo with the heart and the other buttons, in place while the feed scrolls.
 *
 * <p>Home hands its header, in a list of one, to a controller that slides each view in the list up
 * as the feed scrolls down and back down as it scrolls up. The patch passes that list through
 * {@link #slidingViews} on the way. While the switch is on the controller gets an empty list, the
 * state Instagram itself leaves it in when Home lets go of it, so it slides nothing and tells the
 * feed nothing moved. A header already slid away is put back first.
 *
 * <p>An empty list stops the slide but not the fade: the controller also fades the header's logo and
 * buttons by how far it thinks the header slid, and a warm start back into Home (after Android
 * trimmed the app's memory in the background) sets that to the header's full height, leaving an
 * empty bar at the top (#513). So the list handed over is {@link #KEPT}, and the patch runs every
 * slide step through {@link #slideBy}, which holds the controller at the top while that list is
 * the one it slides.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the controller gets Instagram's list.
 *
 * <p>The same hook hands the header to {@link GlassTopBar} for the See-through top bar switch (#418).
 */
public final class HomeTopBar {
    static final String HOOK = "header list";
    static final String COUNT_KEPT = "top bar kept";

    /** The empty list the controller gets while the switch is on, its own instance so slideBy knows it. */
    static final List<Object> KEPT = Collections.unmodifiableList(new ArrayList<>());

    private HomeTopBar() {
    }

    /**
     * Injected at the top of the controller's slide step, with the list it slides and how far to
     * move. While that list is {@link #KEPT} the answer moves it back past the top, which the step
     * clamps to zero, so nothing slides or fades. Any other list moves as Instagram asked. Never
     * throws.
     */
    public static float slideBy(List<?> sliding, float delta) {
        return sliding == KEPT ? -Float.MAX_VALUE : delta;
    }

    /** Injected right before Home hands its header to the scroll controller. Never throws. */
    public static List<?> slidingViews(List<?> views) {
        return slidingViews(views, HomeTopBar::switchedOn);
    }

    static List<?> slidingViews(List<?> views, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.HOME_TOP_BAR);
            if (views == null) return null;
            // The see-through top bar is its own switch: the header gets the glass whether it slides or not (#418).
            GlassTopBar.glaze(views);
            if (!on.getAsBoolean()) return views;
            for (Object view : views) {
                if (view instanceof View) ((View) view).setTranslationY(0f);
            }
            HookStatus.counted(FamilyNames.HOME_TOP_BAR, COUNT_KEPT);
            return KEPT;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_TOP_BAR, HOOK, failure);
            return views;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_HOME_TOP_BAR.get();
    }
}
