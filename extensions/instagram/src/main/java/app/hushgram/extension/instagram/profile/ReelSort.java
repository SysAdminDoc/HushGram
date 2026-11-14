/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Sort reels on profiles" patch (#679).
 *
 * <p>A profile's Reels tab gets the arrow next to its title, and the menu with Latest and Most
 * viewed, only when the server marks that profile as sortable. It decides that per profile and can
 * take it back, so the arrow comes and goes. The tab reads the answer each time it comes up or its
 * title is tapped, and the patch hands each read to {@link #sortable}, which says yes while the
 * switch is on. Picking a sort still sends Instagram's own request, and the reels that come back are
 * the server's answer.
 *
 * <p>With the switch off, HushGram paused, the settings not read yet or anything thrown, the answer
 * is Instagram's own.
 */
public final class ReelSort {
    /** The step a failure is reported under. */
    static final String READ = "read the Reels tab's sort";

    /** What's counted for each read the switch turned into a yes. */
    static final String OFFERED = "offered the sort Instagram held back";

    private ReelSort() {
    }

    /**
     * Injected after each read of whether a profile's reels can be sorted. Non-zero [stock] is
     * Instagram's yes. Answers true while the switch is on, or [stock] as it was. Never throws.
     */
    public static boolean sortable(int stock) {
        return sortable(stock, ReelSort::switchedOn);
    }

    static boolean sortable(int stock, BooleanSupplier on) {
        boolean instagram = stock != 0;
        try {
            HookStatus.invoked(FamilyNames.PROFILE_REEL_SORT);
            if (instagram || !on.getAsBoolean()) return instagram;
            HookStatus.counted(FamilyNames.PROFILE_REEL_SORT, OFFERED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_REEL_SORT, READ, failure);
            return instagram;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.PROFILE_REEL_SORT.get();
    }
}
