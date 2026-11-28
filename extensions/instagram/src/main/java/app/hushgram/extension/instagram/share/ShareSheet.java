/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide group buttons on the share sheet" patch.
 *
 * <p>The share sheet puts a New group button beside its search bar. Depending on a server value it's
 * a button of its own, built from a stub, or an action inside the search box, and either way one
 * method of the sheet reveals it once the sheet is set up. The patch asks {@link #hideGroupButton}
 * first thing in the method that builds the button, so the stub is never built and takes no room,
 * and {@link #hideGroupAction} right after each place the sheet shows the search box's own New
 * group action, which then turns that action off again. Once you pick people, the bar under the
 * sheet can also offer to send to them as a group, and the patch asks {@link #hideGroupSend}
 * before it shows that button. Apart from the group switch, it hands {@link #searchBox} the sheet's
 * search bar once the sheet has found it, for Hide the search bar, and {@link #people} the list of
 * people with the bar, for Hide people until you search.
 */
public final class ShareSheet {
    private static volatile boolean logged;

    private ShareSheet() {
    }

    /** Answers true while Hide group buttons is on, so the sheet doesn't build its New group button. */
    public static boolean hideGroupButton() {
        return hidden("group button");
    }

    /**
     * Answers true while Hide group buttons is on, right after the sheet shows the search box's New
     * group action, so the patch turns that action off again.
     */
    public static boolean hideGroupAction() {
        return hidden("group action");
    }

    /**
     * Answers true while Hide group buttons is on, so the bar under the sheet puts its send-as-group
     * button away instead of showing it for the people you picked. Send separately stays.
     */
    public static boolean hideGroupSend() {
        return hidden("group send");
    }

    /**
     * Takes the share sheet's search bar away while Hide the search bar is on (#545). The patch calls
     * this once the sheet has found the bar, but a few lines later the sheet shows the bar itself, so
     * the bar also goes again each time the sheet lays it out. Leaves the bar as it is before the
     * settings are ready, with the switch off, or when anything goes wrong. Never throws.
     */
    public static void searchBox(View box) {
        try {
            HookStatus.invoked(FamilyNames.SHARE_SHEET);
            if (box == null || !Utils.settingsReady() || !Settings.HIDE_SHARE_SHEET_SEARCH.get()) return;
            box.setVisibility(View.GONE);
            box.removeOnLayoutChangeListener(KEEP_HIDDEN);
            box.addOnLayoutChangeListener(KEEP_HIDDEN);
            Logger.printDebug(() -> "Share sheet: hid the search bar");
            if (box.getParent() instanceof ViewGroup) {
                ViewGroup row = (ViewGroup) box.getParent();
                row.removeOnLayoutChangeListener(KEEP_ROW_HIDDEN);
                row.addOnLayoutChangeListener(KEEP_ROW_HIDDEN);
                hideEmptyRow(row);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, "search bar", failure);
        }
    }

    /**
     * Hides the search bar again whenever the sheet lays it out while the switch is on. A gone view
     * is never laid out, so this only acts after something showed the bar.
     */
    private static final View.OnLayoutChangeListener KEEP_HIDDEN =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                try {
                    if (view.getVisibility() == View.GONE || !Settings.HIDE_SHARE_SHEET_SEARCH.get()) return;
                    view.setVisibility(View.GONE);
                    Logger.printDebug(() -> "Share sheet: hid the search bar the sheet showed again");
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.SHARE_SHEET, "search bar", failure);
                }
            };

    /** Takes the empty search row away again whenever the sheet lays it out (#685). */
    private static final View.OnLayoutChangeListener KEEP_ROW_HIDDEN =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                try {
                    if (view instanceof ViewGroup) hideEmptyRow((ViewGroup) view);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.SHARE_SHEET, "search row", failure);
                }
            };

    /**
     * The search bar sits in a row of its own with the New group button and its stub, and the row
     * keeps its height once the bar is gone, which left an empty band across the top of the sheet
     * (#685). With Hide group buttons on too, nothing else is ever built in that row, so the row goes
     * as well. With the group switch off the row stays for the button, which the sheet builds into it
     * later, and a row that still shows anything is left alone.
     */
    private static void hideEmptyRow(ViewGroup row) {
        if (row.getVisibility() == View.GONE || !Settings.HIDE_SHARE_SHEET_SEARCH.get()
                || !Settings.HIDE_SHARE_SHEET_GROUP.get()) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).getVisibility() != View.GONE) return;
        }
        row.setVisibility(View.GONE);
        Logger.printDebug(() -> "Share sheet: hid the empty search row");
    }

    /**
     * Keeps the share sheet's list of people hidden while its search bar is empty, for Hide people
     * until you search (#899), so a tap meant for Copy link under it can't land on someone. The patch
     * calls this once the sheet has found the bar, with the list it found before. Typing shows the
     * list, which is where the search's answers go, and clearing the bar hides it again. With Hide
     * the search bar on too, there's no way to type, so the list stays. Leaves the list as it is
     * before the settings are ready, with the switch off, without a text field in the bar, or when
     * anything goes wrong. Never throws.
     */
    public static void people(View list, View searchBar) {
        try {
            HookStatus.invoked(FamilyNames.SHARE_SHEET);
            if (list == null || !Utils.settingsReady() || !Settings.HIDE_SHARE_SHEET_PEOPLE.get()) return;
            EditText field = textField(searchBar);
            if (field == null) return;
            PeopleUntilSearch keeper = new PeopleUntilSearch(list, field);
            field.addTextChangedListener(keeper);
            list.addOnLayoutChangeListener(keeper);
            keeper.settle();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, "people", failure);
        }
    }

    /** The first text field in {@code view} or under it, depth first, or null when there's none. */
    private static EditText textField(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = textField(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Hides the list while the field is empty and shows it again once there's text. The sheet shows
     * the list itself as it loads, so the list also goes again each time it's laid out with the field
     * empty. A list the sheet hid on its own is never shown by this.
     */
    private static final class PeopleUntilSearch implements TextWatcher, View.OnLayoutChangeListener {
        private final View list;
        private final EditText field;
        private boolean hid;

        PeopleUntilSearch(View list, EditText field) {
            this.list = list;
            this.field = field;
        }

        void settle() {
            boolean hide = Settings.HIDE_SHARE_SHEET_PEOPLE.get() && !Settings.HIDE_SHARE_SHEET_SEARCH.get()
                    && TextUtils.getTrimmedLength(field.getText()) == 0;
            if (hide && list.getVisibility() == View.VISIBLE) {
                list.setVisibility(View.GONE);
                hid = true;
                Logger.printDebug(() -> "Share sheet: hid the people until a search");
            } else if (!hide && hid) {
                hid = false;
                if (list.getVisibility() == View.GONE) list.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable text) {
            try {
                settle();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.SHARE_SHEET, "people", failure);
            }
        }

        @Override
        public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            try {
                if (list.getVisibility() == View.VISIBLE) settle();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.SHARE_SHEET, "people", failure);
            }
        }
    }

    /**
     * True while Hide group buttons is on, and false before the settings are ready or when anything goes
     * wrong, so the button stays. Never throws, and never waits for the settings.
     */
    private static boolean hidden(String where) {
        try {
            HookStatus.invoked(FamilyNames.SHARE_SHEET);
            if (!Utils.settingsReady() || !Settings.HIDE_SHARE_SHEET_GROUP.get()) return false;
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Share sheet: left out the group buttons");
            }
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, where, failure);
            return false;
        }
    }
}
