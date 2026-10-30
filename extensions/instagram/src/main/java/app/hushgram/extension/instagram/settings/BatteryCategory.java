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
import android.preference.SwitchPreference;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.misc.BackgroundWork;
import app.hushgram.extension.shared.L10n;

/** Builds the Battery category of HushGram settings. */
final class BatteryCategory {
    private BatteryCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.STOP_HEARTBEAT) || build.contains(PatchFamily.BACKGROUND_WORK)) {
            PreferenceCategory battery = category(screen, L10n.t("Battery"), SettingsIcons.BATTERY,
                    L10n.t("Stop background work Instagram doesn't need."));
            if (build.contains(PatchFamily.STOP_HEARTBEAT)) {
                battery.addPreference(toggle(context, Settings.STOP_HEARTBEAT, L10n.t("Stop the background heartbeat"),
                        L10n.t("Instagram wakes your phone every minute or two, screen off included, only to note that it "
                                + "is still running. This stops that. Messages, notifications and everything you see are "
                                + "unaffected.")));
                battery.addPreference(toggle(context, Settings.STOP_UPLOAD_ALARM, L10n.t("Stop the analytics upload alarm"),
                        L10n.t("Instagram sets an alarm to wake your phone and send its usage events five minutes later. "
                                + "This stops that alarm. The events go the next time you use the app.")));
            }
            if (build.contains(PatchFamily.BACKGROUND_WORK)) {
                for (SwitchPreference row : backgroundWorkRows(context)) battery.addPreference(row);
            }
        }
    }

    /**
     * Stop background jobs' three switches. Each change turns Instagram's services and receivers on
     * or off at once, once the change is saved, so no restart.
     */
    static List<SwitchPreference> backgroundWorkRows(Context context) {
        List<SwitchPreference> rows = Arrays.asList(
                toggle(context, Settings.STOP_PUSH_WHEN_SILENT, L10n.t("Turn off push while notifications are off"),
                        L10n.t("With Instagram's notifications turned off in your phone's settings, Meta's push service "
                                + "still runs and wakes Instagram all day. This turns it off until you allow "
                                + "notifications again. Messages still arrive while Instagram is open.")),
                toggle(context, Settings.STOP_BACKGROUND_REFRESH, L10n.t("Stop background feed refresh"),
                        L10n.t("Instagram loads your feed in the background so it's ready when you open the app. This "
                                + "stops that, so the feed loads when you open Instagram instead.")),
                toggle(context, Settings.STOP_BACKGROUND_TRACKING, L10n.t("Stop background tracking"),
                        L10n.t("Stops the periodic log of which other Meta apps you used and when, and Instagram's "
                                + "service that tracks how it was started and closed.")));
        for (SwitchPreference row : rows) {
            row.setOnPreferenceChangeListener((preference, value) -> {
                BackgroundWork.switchChanged(preference.getContext());
                return true;
            });
        }
        return rows;
    }
}
