/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.view.View;

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
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the controller gets Instagram's list.
 *
 * <p>The same hook hands the header to {@link GlassTopBar} for the See-through top bar switch (#418).
 */
public final class HomeTopBar {
    static final String HOOK = "header list";
    static final String COUNT_KEPT = "top bar kept";

    private HomeTopBar() {
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
            return Collections.emptyList();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_TOP_BAR, HOOK, failure);
            return views;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_HOME_TOP_BAR.get();
    }
}
