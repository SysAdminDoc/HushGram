/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Sharing domain row: right under Sanitize sharing links, blank to start, waiting for it, and taking only a domain. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SharingDomainSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SANITIZE_SHARING_LINKS.resetToDefault();
        Settings.SHARING_DOMAIN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.SANITIZE_SHARING_LINKS.resetToDefault();
        Settings.SHARING_DOMAIN.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed
                ? EnumSet.of(PatchFamily.SANITIZE_SHARING_LINKS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRow() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.SHARING_DOMAIN.key));
    }
    @Test public void theRowSitsUnderSanitizeSharingLinksAndWaitsForIt() throws Exception {
        open(true);
        SwitchPreference sanitize = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.SANITIZE_SHARING_LINKS.key);
        EditTextPreference domain = (EditTextPreference) page.getPreferenceScreen().findPreference(Settings.SHARING_DOMAIN.key);
        assertNotNull(sanitize);
        assertNotNull(domain);
        assertEquals("Sharing domain", domain.getTitle().toString());
        assertEquals("Links keep instagram.com.", domain.getSummary().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), sanitize);
        assertNotNull(section);
        assertSame(section, categoryHolding(page.getPreferenceScreen(), domain));
        assertEquals("right under its switch", indexIn(section, sanitize) + 1, indexIn(section, domain));
        assertEquals("", Settings.SHARING_DOMAIN.get());
        assertTrue(sanitize.isChecked());
        assertTrue(domain.isEnabled());
        sanitize.setChecked(false);
        ShadowLooper.idleMainLooper();
        assertFalse("waits for the switch", domain.isEnabled());
        sanitize.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(domain.isEnabled());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.SHARING_DOMAIN.key));
    }
    @Test public void theRowTakesOnlyADomainAndSavesItBare() throws Exception {
        open(true);
        EditTextPreference domain = (EditTextPreference) page.getPreferenceScreen().findPreference(Settings.SHARING_DOMAIN.key);
        Preference.OnPreferenceChangeListener listener = domain.getOnPreferenceChangeListener();
        assertFalse(listener.onPreferenceChange(domain, "not a domain"));
        assertFalse(listener.onPreferenceChange(domain, "example.com/p"));
        assertEquals("", Settings.SHARING_DOMAIN.get());
        assertTrue(listener.onPreferenceChange(domain, ""));
        assertTrue(listener.onPreferenceChange(domain, "example.com"));

        assertFalse("saved bare in place of what was typed", listener.onPreferenceChange(domain, " https://Example.com/ "));
        ShadowLooper.idleMainLooper();
        assertEquals("example.com", domain.getText());
        assertEquals("example.com", Settings.SHARING_DOMAIN.get());
        assertEquals("Links to instagram.com go out on example.com.", domain.getSummary().toString().replaceAll("[⁦-⁩]", ""));
    }

    @Test public void invalidSaveStaysOpenAndDoneSavesTheCorrectedDomainOnce() throws Exception {
        Settings.SHARING_DOMAIN.save("old.example.com");
        open(true);
        HushgramPreferenceFragment.SharingDomainRow row = (HushgramPreferenceFragment.SharingDomainRow)
                page.findPreference(Settings.SHARING_DOMAIN.key);
        AtomicInteger changes = new AtomicInteger();
        Preference.OnPreferenceChangeListener original = row.getOnPreferenceChangeListener();
        row.setOnPreferenceChangeListener((preference, value) -> {
            changes.incrementAndGet();
            return original.onPreferenceChange(preference, value);
        });
        row.showDialog(null);
        AlertDialog dialog = (AlertDialog) row.getDialog();
        EditText field = row.getEditText();
        field.setText("example.com/a-post");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue("a rejected value stays ready to correct", dialog.isShowing());
        assertEquals("example.com/a-post", field.getText().toString());
        assertNotNull(field.getError());
        assertEquals("old.example.com", Settings.SHARING_DOMAIN.savedValue());
        assertEquals(0, changes.get());

        field.setText(" https://Example.com/ ");
        assertNull("typing clears the old error", field.getError());
        field.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertFalse(dialog.isShowing());
        assertEquals("example.com", row.getText());
        assertEquals("example.com", Settings.SHARING_DOMAIN.savedValue());
        assertEquals("normalization commits through the preference once", 1, changes.get());
        field.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertEquals("a stale keyboard action cannot save again", 1, changes.get());
    }

    @Test public void cancelAfterAnInlineErrorKeepsTheSavedDomain() throws Exception {
        Settings.SHARING_DOMAIN.save("old.example.com");
        open(true);
        HushgramPreferenceFragment.SharingDomainRow row = (HushgramPreferenceFragment.SharingDomainRow)
                page.findPreference(Settings.SHARING_DOMAIN.key);
        row.showDialog(null);
        AlertDialog dialog = (AlertDialog) row.getDialog();
        row.getEditText().setText("not a domain");
        row.getEditText().onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertTrue(dialog.isShowing());
        assertNotNull(row.getEditText().getError());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals("old.example.com", Settings.SHARING_DOMAIN.savedValue());
        row.showDialog(null);
        assertEquals("old.example.com", row.getEditText().getText().toString());
        assertNull(row.getEditText().getError());
        row.getDialog().dismiss();
    }

    @Test public void theCompactEditorsKeepTheirInputAndHelpInTheSameScrollBody() throws Exception {
        open(true);
        HushgramPreferenceFragment.SharingDomainRow domain = (HushgramPreferenceFragment.SharingDomainRow)
                page.findPreference(Settings.SHARING_DOMAIN.key);
        HushgramPreferenceFragment.FolderRow folder = HushgramPreferenceFragment.folderRow(controller.get());
        HushgramPreferenceFragment.PlaceRow place = HushgramPreferenceFragment.placeRow(controller.get());
        page.getPreferenceScreen().addPreference(folder);
        page.getPreferenceScreen().addPreference(place);
        for (EditTextPreference row : new EditTextPreference[]{domain, folder, place}) {
            if (row instanceof HushgramPreferenceFragment.ValidatedEditRow) {
                ((HushgramPreferenceFragment.ValidatedEditRow) row).showDialog(null);
            } else {
                ((HushgramPreferenceFragment.FolderRow) row).showDialog(null);
            }
            ShadowLooper.idleMainLooper();
            AlertDialog dialog = (AlertDialog) row.getDialog();
            assertTrue(row.getEditText().getParent() instanceof LinearLayout);
            LinearLayout body = (LinearLayout) row.getEditText().getParent();
            assertTrue("native binding must keep the input inside the scroll body", body.getParent() instanceof ScrollView);
            assertSame(row.getEditText(), body.getChildAt(0));
            TextView help = body.findViewById(android.R.id.message);
            assertNotNull(help);
            assertEquals(row.getDialogMessage(), help.getText());
            assertEquals(View.VISIBLE, help.getVisibility());
            assertTrue("help must be allowed to wrap", help.getMaxLines() > 1);
            assertEquals(14f * help.getResources().getDisplayMetrics().scaledDensity, help.getTextSize(), 0.01f);
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown());
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isShown());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse(dialog.isShowing());
        }
    }
    private static int indexIn(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (group.getPreference(i) == row) return i;
        }
        return -1;
    }
    private static PreferenceCategory categoryHolding(PreferenceGroup group, Preference row) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) child;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    if (category.getPreference(j) == row) return category;
                }
            }
            if (child instanceof PreferenceGroup) {
                PreferenceCategory found = categoryHolding((PreferenceGroup) child, row);
                if (found != null) return found;
            }
        }
        return null;
    }
}
