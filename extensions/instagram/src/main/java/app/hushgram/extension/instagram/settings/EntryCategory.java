/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.navigationRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.profileMenuRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.wayInSummary;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import java.util.Set;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;

/** Builds the Settings entry category of HushGram settings. */
final class EntryCategory {
    private EntryCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        PreferenceCategory entry = category(screen, L10n.t("Settings entry"), SettingsIcons.SETTINGS,
                L10n.t("How you open HushGram and lay out settings."));
        entry.addPreference(navigationRow(context));
        entry.addPreference(profileMenuRow(context));
        // Each way in has a switch, and the last one still on stays on (HushFacebook#146).
        SwitchPreference shortcut = toggle(context, Settings.SETTINGS_SHORTCUT,
                L10n.t("Settings shortcut on the app icon"), wayInSummary(Settings.SETTINGS_SHORTCUT));
        shortcut.setOnPreferenceChangeListener((preference, value) -> {
            // Posted, so the shortcut follows the value this change saves.
            Utils.runOnMainThread(() -> SettingsEntry.shortcutSwitchChanged(context));
            return true;
        });
        entry.addPreference(shortcut);
        entry.addPreference(toggle(context, Settings.SETTINGS_LOGO_PRESS, L10n.t("Open settings from the logo"),
                wayInSummary(Settings.SETTINGS_LOGO_PRESS)));
        entry.addPreference(toggle(context, Settings.HIDE_MENU_ROW, L10n.t("Hide the HushGram row in Instagram's menu"),
                wayInSummary(Settings.HIDE_MENU_ROW)));
        entry.addPreference(toggle(context, Settings.CATEGORY_PAGES, L10n.t("Expandable categories"),
                L10n.t("Tap a category to show or hide its settings on this page.")));
        SwitchPreference descriptions = toggle(context, Settings.DESCRIPTIONS_BEHIND_INFO,
                L10n.t("Descriptions behind an info button"),
                L10n.t("Shows each switch by its name alone. Tap the i next to a name to read what it does."));
        descriptions.setOnPreferenceChangeListener((preference, value) -> {
            // Posted, so the rows bind with the value this change saves.
            Utils.runOnMainThread(page::rebindRows);
            return true;
        });
        entry.addPreference(descriptions);
    }
}
