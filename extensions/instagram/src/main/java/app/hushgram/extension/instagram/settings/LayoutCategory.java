/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.accentHueRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.appIconRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.info;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.onFlip;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.instagram.misc.RefreshRate;
import app.hushgram.extension.shared.L10n;

/** Builds the Layout category of HushGram settings. */
final class LayoutCategory {
    /** The row a build without Pure black dark mode shows under Layout, saying where to get it. */
    static final String PURE_BLACK_HINT_ROW = "hushgram_pure_black_hint_row";

    private LayoutCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.BOTTOM_SPACE) || build.contains(PatchFamily.EMOJI_STYLE)
                || build.contains(PatchFamily.ACCENT_COLOR) || build.contains(PatchFamily.LAUNCH_SCREEN)
                || build.contains(PatchFamily.FONT) || build.contains(PatchFamily.APP_ICON)
                || build.contains(PatchFamily.SETTINGS_ROWS) || build.contains(PatchFamily.REFRESH_RATE)) {
            PreferenceCategory layout = category(screen, L10n.t("Layout"), SettingsIcons.LAYOUT,
                    L10n.t("Change how Instagram looks, from its colors to its emoji."));
            if (build.contains(PatchFamily.BOTTOM_SPACE)) {
                layout.addPreference(toggle(context, Settings.REMOVE_BOTTOM_SPACE, L10n.t("Remove the empty space at the bottom"),
                        L10n.t("Removes the empty gap under Instagram's tab bar that appears when your phone hides "
                                + "its navigation bar or Instagram is in a pop-up window. Restart Instagram to see "
                                + "the change.")));
            }
            if (build.contains(PatchFamily.FONT)) layout.addPreference(page.fontRow(context));
            if (build.contains(PatchFamily.EMOJI_STYLE)) {
                layout.addPreference(toggle(context, Settings.NOTO_EMOJI, L10n.t("Google's emoji everywhere"),
                        L10n.t("Shows every emoji in Google's style instead of your phone's own. Restart Instagram "
                                + "to see the change.")));
            }
            if (build.contains(PatchFamily.ACCENT_COLOR)) {
                layout.addPreference(toggle(context, Settings.ACCENT_COLOR, L10n.t("Accent color"),
                        L10n.t("Turns Instagram's blue, on Follow buttons, links and the like, into the color you "
                                + "pick below. Restart Instagram to see the change.")));
                layout.addPreference(accentHueRow(context));
                layout.addPreference(toggle(context, Settings.ACCENT_BADGES, L10n.t("Badges in the accent color"),
                        L10n.t("The red dots and counts for new messages and notifications take the color you "
                                + "picked. Restart Instagram to see the change.")));
                layout.addPreference(toggle(context, Settings.ACCENT_ICONS, L10n.t("Icons in the accent color"),
                        L10n.t("Instagram's icons on the top bar, the tab bar and under posts take the color you "
                                + "picked. Text stays as it is. Restart Instagram to see the change.")));
            }
            // Pure black is set when you patch and stays out of the default selection, so a build
            // without it says where to get it (#267, #359).
            if (!build.contains(PatchFamily.PURE_BLACK)) {
                android.preference.Preference hint = info(context, L10n.t("Pure black dark mode isn't in this build"),
                        L10n.t("To make dark mode pure black instead of dark gray, which can save battery on an "
                                + "OLED screen, pick Pure black dark mode in Morphe Manager the next time you patch "
                                + "Instagram."));
                hint.setKey(PURE_BLACK_HINT_ROW);
                layout.addPreference(mark(hint, SettingsIcons.MOON));
            }
            if (build.contains(PatchFamily.LAUNCH_SCREEN)) {
                layout.addPreference(toggle(context, Settings.LAUNCH_SCREEN, L10n.t("HushGram launch screen"),
                        L10n.t("Shows HushGram's logo instead of Instagram's on the screen you see while Instagram "
                                + "starts, on Android 13 and newer. It shows from the next start.")));
            }
            if (build.contains(PatchFamily.REFRESH_RATE)) {
                layout.addPreference(onFlip(toggle(context, Settings.HIGHEST_REFRESH_RATE, L10n.t("Use the highest refresh rate"),
                        L10n.t("Asks Android to run Instagram at your screen's fastest refresh rate, at the same "
                                + "resolution. Some phones keep their own limit for apps, and battery saver can "
                                + "still lower it.")), RefreshRate::switchChanged));
            }
            if (build.contains(PatchFamily.APP_ICON)) layout.addPreference(appIconRow(context));
            if (build.contains(PatchFamily.SETTINGS_ROWS)) layout.addPreference(page.settingsRowsRow(context));
        }
    }
}
