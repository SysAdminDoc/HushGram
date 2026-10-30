/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.preference.ReportProblem;

/**
 * HushGram's half of Report a problem: the repository, its bug form's field ids
 * (.github/ISSUE_TEMPLATE/bug_report.yml) and the names that go in the versions line.
 * {@link ReportProblem} builds the address.
 */
@SuppressWarnings("deprecation") // Framework preferences, as the settings screen uses.
final class ReportProblemForm {
    /** owner/name, read off the Source code link so the two can't drift apart. */
    static final String REPOSITORY = HushgramPreferenceFragment.SOURCE_URL
            .substring(HushgramPreferenceFragment.SOURCE_URL.indexOf("github.com/") + "github.com/".length());

    /** Where the reports are, for a phone with no app that can open the link. */
    static final String ISSUES_ADDRESS = HushgramPreferenceFragment.SOURCE_ADDRESS + "/issues";

    /** The bug form and the ids of the fields filled in. A renamed id in the form has to change here too. */
    static final ReportProblem.Form FORM = new ReportProblem.Form(REPOSITORY, "bug_report.yml",
            "versions", "device", "bug_description", "additional_context");

    private ReportProblemForm() {
    }

    /**
     * The titles of the switches that are on, in the screen's order. [rows] are every row the
     * screen has, on it or not: with categories folded, or during a search, most of them are
     * kept off the screen, and they still count.
     */
    static List<String> switchesOn(Iterable<? extends Preference> rows) {
        List<String> titles = new ArrayList<>();
        for (Preference row : rows) collect(row, titles);
        return titles;
    }

    private static void collect(Preference row, List<String> titles) {
        if (row instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) row;
            for (int i = 0; i < group.getPreferenceCount(); i++) collect(group.getPreference(i), titles);
        } else if (row instanceof TwoStatePreference && ((TwoStatePreference) row).isChecked()
                && row.hasKey() && Setting.getSettingFromPath(row.getKey()) != null && row.getTitle() != null) {
            titles.add(row.getTitle().toString());
        }
    }

    /** The new bug report's address, with this install's facts and the switches on among [rows]. */
    static String issueUrl(Iterable<? extends Preference> rows) {
        return ReportProblem.issueUrl(FORM, ReportProblem.facts("HushGram", "Instagram", switchesOn(rows)));
    }

    /** Opens the report in a browser, in a task of its own, or says no app here can. */
    static void open(Context context, Iterable<? extends Preference> rows) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(issueUrl(rows)))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException missing) {
            // No browser, or none switched on. Uncaught, Android's exception closed Instagram.
            Logger.printInfo(() -> "No app opened the bug report link");
            Utils.showToastLong(L10n.f("No app on this phone can open the link. The address is %1$s.",
                    L10n.isolate(ISSUES_ADDRESS)));
        }
    }
}
