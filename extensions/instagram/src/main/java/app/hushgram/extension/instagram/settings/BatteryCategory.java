/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.shared.L10n;

/** Builds the Battery category of HushGram settings. */
final class BatteryCategory {
    private BatteryCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.STOP_HEARTBEAT)) {
            PreferenceCategory battery = category(screen, L10n.t("Battery"), SettingsIcons.BATTERY,
                    L10n.t("Stop background work Instagram doesn't need."));
            battery.addPreference(toggle(context, Settings.STOP_HEARTBEAT, L10n.t("Stop the background heartbeat"),
                    L10n.t("Instagram wakes your phone every minute or two, screen off included, only to note that it "
                            + "is still running. This stops that. Messages, notifications and everything you see are "
                            + "unaffected.")));
            battery.addPreference(toggle(context, Settings.STOP_UPLOAD_ALARM, L10n.t("Stop the analytics upload alarm"),
                    L10n.t("Instagram sets an alarm to wake your phone and send its usage events five minutes later. "
                            + "This stops that alarm. The events go the next time you use the app.")));
        }
    }
}
