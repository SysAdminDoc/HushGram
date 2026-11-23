/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import android.app.Activity;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * More people in the row a long press on a post's Share button opens (discussion #811). Instagram
 * puts four faces there: it takes four people off the top of the share sheet's list, draws no more
 * than four faces from whichever list it has, and makes the popup four faces wide. The patch asks
 * {@link #people(int)} for each of those fours instead, and with the switch on the answer is as many
 * faces as fit across the window, so the row fills it.
 */
public final class QuickSend {
    /** The popup's sizes on 456.1, in dp: each face, the gap between two, and the padding at each end. */
    static final float FACE_DP = 44f;
    static final float GAP_DP = 16f;
    static final float PADDING_DP = 12f;
    /** Room left clear at each side of the window, so the row never runs off it. */
    static final float EDGE_DP = 8f;
    /** The most faces the row takes, however wide the window. */
    static final int MOST = 10;

    private static boolean logged;

    private QuickSend() { }

    /**
     * How many people the row shows where Instagram shows [stock]: [stock] while the switch is off,
     * before the settings are ready or when anything goes wrong, and otherwise as many as fit across
     * the window, never fewer than [stock]. Never throws, and never waits for the settings.
     */
    public static int people(int stock) {
        try {
            HookStatus.invoked(FamilyNames.SHARE_SHEET);
            if (!Utils.settingsReady() || !Settings.MORE_QUICK_SEND_PEOPLE.get()) return stock;
            int answer = fit(stock, windowWidthDp());
            if (!logged && answer != stock) {
                logged = true;
                Logger.printDebug(() -> "Quick send: room for " + answer + " people");
            }
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, "quick send", failure);
            return stock;
        }
    }

    /** How many faces fit across [widthDp], between [stock] and {@link #MOST}. */
    static int fit(int stock, float widthDp) {
        float room = widthDp - 2 * PADDING_DP - 2 * EDGE_DP + GAP_DP;
        int fits = room <= 0 ? 0 : (int) (room / (FACE_DP + GAP_DP));
        return Math.max(stock, Math.min(MOST, fits));
    }

    /** The width of Instagram's window, which split screen narrows, or the screen's when there's no window yet. */
    private static float windowWidthDp() {
        Activity activity = Utils.getActivity();
        if (activity != null) {
            int width = activity.getResources().getConfiguration().screenWidthDp;
            if (width > 0) return width;
        }
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        return metrics.widthPixels / metrics.density;
    }
}
