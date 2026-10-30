/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.info;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.shared.L10n;

/** Builds the Set when you patched category of HushGram settings. */
final class PatchTimeCategory {
    private PatchTimeCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.RESTORE_TRUST) || build.contains(PatchFamily.REMOVE_AD_ID)
                || build.contains(PatchFamily.PURE_BLACK) || build.contains(PatchFamily.VERSION_CODE)) {
            PreferenceCategory patched = category(screen, L10n.t("Set when you patched"), SettingsIcons.BUILD,
                    L10n.t("Chosen when you patched, so Pause leaves them on. Patch again to change them."));
            if (build.contains(PatchFamily.RESTORE_TRUST)) {
                patched.addPreference(mark(info(context, L10n.t("Instagram accepts this build"),
                        L10n.t("Instagram's own checks of who signed the app keep passing on this patched build.")), SettingsIcons.BUILD));
            }
            if (build.contains(PatchFamily.REMOVE_AD_ID)) {
                patched.addPreference(mark(info(context, L10n.t("Advertising ID removed"),
                        L10n.t("Instagram can't read your phone's advertising ID or tell Android's ad services "
                                + "which ads you saw or tapped. The permissions for them are gone from this build.")),
                        SettingsIcons.BLOCK));
            }
            if (build.contains(PatchFamily.PURE_BLACK)) {
                patched.addPreference(mark(info(context, L10n.t("Pure black dark mode"),
                        L10n.t("Instagram's dark mode uses pure black instead of its near-black gray, and so do the "
                                + "menus that slide up from the bottom. Buttons keep their own grays.")),
                        SettingsIcons.MOON));
            }
            if (build.contains(PatchFamily.VERSION_CODE)) {
                patched.addPreference(mark(info(context, L10n.t("No Play Store updates"),
                        L10n.t("Google Play won't offer Meta's updates over this build. To go back to the normal "
                                + "Instagram, uninstall this one first, which deletes Instagram's data on this "
                                + "phone. Later HushGram builds need Change version code too, or they won't install "
                                + "over this one.")), SettingsIcons.UPDATES));
            }
        }
    }
}
