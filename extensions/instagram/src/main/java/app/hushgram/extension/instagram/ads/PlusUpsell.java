/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.ads;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide Instagram Plus prompts" patch (#559).
 *
 * <p>Every Instagram Plus offer in Instagram 454 (the card and "See preview" row on a held chat,
 * the story menu's row, the diamond in a story's viewer list and the rest) first asks one check
 * whether that spot should offer Plus right now. The patch hands each answer to {@link #offer(int)},
 * which says no while the switch is on, so the spot shows what it shows anyone Instagram isn't
 * selling Plus to. Accounts on Meta's newer offer path get their card from a cached offer instead,
 * and the patch asks {@link #hide()} before that offer is built, so no offer comes back. Nothing
 * Plus gives is turned on: what a subscriber has comes from a separate check the patch leaves alone.
 */
public final class PlusUpsell {
    private static final String HOOK = "Instagram Plus offer check";
    private static final String EXPERIENCE_HOOK = "Instagram Plus offer builder";

    private PlusUpsell() {
    }

    /** No offer while the switch is on; otherwise Instagram's answer (non-zero means offer it). Never throws. */
    public static boolean offer(int offer) {
        try {
            HookStatus.invoked(FamilyNames.HIDE_PLUS_UPSELL);
            return offer != 0 && !on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_PLUS_UPSELL, HOOK, failure);
            return offer != 0;
        }
    }

    /**
     * Whether Meta's newer Plus offer (the one it caches per spot and builds into a card) should be
     * left out: true while the switch is on. The patch asks this first where that offer is built and
     * hands back no offer on a yes, which every spot already handles as "nothing to show". Never throws.
     */
    public static boolean hide() {
        try {
            HookStatus.invoked(FamilyNames.HIDE_PLUS_UPSELL);
            return on();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_PLUS_UPSELL, EXPERIENCE_HOOK, failure);
            return false;
        }
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_PLUS_UPSELL.get();
    }
}
