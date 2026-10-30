/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.STAYS_WHILE_PAUSED;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.info;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;

import java.util.Set;

import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.ClearRow;
import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.ExportRow;
import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.Row;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.preference.ClearLogBufferPreference;
import app.hushgram.extension.shared.settings.preference.ExportDiagnosticReportPreference;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/** Builds the Pause and diagnostics category of HushGram settings. */
final class PauseCategory {
    private PauseCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        // Named for its rows: the screen's own title already says HushGram. It folds like the others,
        // and opens by itself while paused, locked or showing an interrupted save.
        PreferenceCategory hushgram = category(screen, L10n.t("Pause and diagnostics"), SettingsIcons.PAUSE,
                L10n.t("Pause HushGram, lock these settings, or make a report when something breaks."));
        page.recovery = hushgram;
        hushgram.addPreference(mark(toggle(context, BaseSettings.PAUSED, L10n.t("Pause HushGram"),
                L10n.t("From the next start, every switch but Debug logging acts as if it were off. "
                        + "Changes made when you patched stay in, and your choices stay saved.")), SettingsIcons.PATCHED));
        // Lock these settings (#301), under Pause, which it locks too. Its words follow the lock's state.
        Row lock = page.settingsLock = new Row(context);
        lock.setKey(HushgramPreferenceFragment.LOCK_ROW_KEY);
        lock.setPersistent(false);
        HushgramPreferenceFragment.showLockRow(lock, SettingsLock.state());
        lock.setOnPreferenceClickListener(p -> {
            page.tapLock();
            return true;
        });
        hushgram.addPreference(mark(lock, SettingsIcons.LOCK));
        // Debug logging also fills the exported report and turns on error toasts (Logger).
        hushgram.addPreference(mark(toggle(context, BaseSettings.DEBUG, L10n.t("Debug logging"),
                L10n.t("Record patch activity and show errors for a bug report. Leave off during normal use.")), SettingsIcons.BUG));
        ExportDiagnosticReportPreference export = page.exportDiagnostics = page.new ExportRow(context);
        export.setTitle(L10n.t("Export diagnostic report"));
        export.setSummary(L10n.f("Copy a quick report or save the full one to %1$s. Links, IDs, cookies and sign-in "
                + "details are left out. Check it for other private text before you share it.",
                L10n.isolate(LogBufferManager.reportFolder(context))));
        hushgram.addPreference(mark(export, SettingsIcons.LICENSE));
        ClearLogBufferPreference clear = new ClearRow(context);
        clear.setTitle(L10n.t("Clear diagnostic data"));
        clear.setClearAndUndoSummaries(L10n.t("Empties the activity log and any kept crash that a diagnostic report would include."),
                L10n.t("Diagnostic data cleared. Tap again to put it back."));
        hushgram.addPreference(mark(clear, SettingsIcons.DELETE));
        String stays = PatchFamily.staysWhilePausedSummary(build);
        if (stays != null) hushgram.addPreference(info(context, L10n.t(STAYS_WHILE_PAUSED), stays));
    }
}
