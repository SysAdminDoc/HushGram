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
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.util.Collections;
import java.util.EnumSet;
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

/** App-only proxy's rows: under Ads and privacy, off to start, the server waiting for the switch and taking only a host and a port. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class AppProxySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.APP_PROXY.resetToDefault();
        Settings.APP_PROXY_SERVER.resetToDefault();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.APP_PROXY.resetToDefault();
        Settings.APP_PROXY_SERVER.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.APP_PROXY) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    private EditTextPreference server() {
        return (EditTextPreference) page.getPreferenceScreen().findPreference(Settings.APP_PROXY_SERVER.key);
    }

    @Test public void missingPatchHasNoRows() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.APP_PROXY.key));
        assertNull(server());
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.APP_PROXY_SERVER.key));
    }

    @Test public void bothRowsSitUnderPrivacyAndTheServerWaits() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.APP_PROXY.key);
        EditTextPreference server = server();
        assertNotNull(row);
        assertNotNull(server);
        assertEquals("App-only proxy", row.getTitle().toString());
        assertEquals("Proxy server", server.getTitle().toString());
        assertTrue(server.getSummary().toString(), server.getSummary().toString().contains("No server set"));
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(section);
        assertEquals("Ads and privacy", section.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), server));
        assertFalse(row.isChecked());
        assertFalse(Settings.APP_PROXY.get());
        assertFalse("waits for the switch", server.isEnabled());
        row.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(server.isEnabled());
        assertEquals(Collections.singletonList(Settings.APP_PROXY), PatchFamily.APP_PROXY.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.APP_PROXY.key));
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.APP_PROXY_SERVER.key));
    }

    /** Instagram reads its proxy as it starts, so both rows ask for a restart. */
    @Test public void bothRowsAskForARestart() {
        assertTrue(Settings.APP_PROXY.rebootApp);
        assertTrue(Settings.APP_PROXY_SERVER.rebootApp);
    }

    @Test public void theServerTakesOnlyAServerAndKeepsItPlain() throws Exception {
        open(true);
        EditTextPreference server = server();
        Preference.OnPreferenceChangeListener check = server.getOnPreferenceChangeListener();
        assertFalse(check.onPreferenceChange(server, "proxy.example.com"));
        assertFalse(check.onPreferenceChange(server, "socks5://proxy.example.com:1080"));
        assertTrue(check.onPreferenceChange(server, ""));
        assertTrue(check.onPreferenceChange(server, "proxy.example.com:8080"));
        assertFalse("saved as host:port instead", check.onPreferenceChange(server, "HTTP://Proxy.Example.com:8080/"));
        assertEquals("proxy.example.com:8080", server.getText());
        assertTrue(server.getSummary().toString(), server.getSummary().toString().contains("proxy.example.com:8080"));
        server.setText("");
        assertTrue(server.getSummary().toString(), server.getSummary().toString().contains("No server set"));
    }

    @Test public void invalidDoneKeepsTheServerAndSaveTakesTheCorrection() throws Exception {
        Settings.APP_PROXY.save(true);
        Settings.APP_PROXY_SERVER.save("10.0.0.2:8080");
        open(true);
        HushgramPreferenceFragment.ProxyServerRow row = (HushgramPreferenceFragment.ProxyServerRow) server();
        row.showDialog(null);
        AlertDialog dialog = (AlertDialog) row.getDialog();
        EditText field = row.getEditText();
        assertEquals("10.0.0.2:8080", field.getText().toString());
        field.setText("10.0.0.2");
        field.onEditorAction(EditorInfo.IME_ACTION_DONE);
        assertTrue(dialog.isShowing());
        assertNotNull(field.getError());
        assertEquals("10.0.0.2:8080", Settings.APP_PROXY_SERVER.savedValue());
        field.setText(" http://Proxy.Lan:3128/ ");
        assertNull(field.getError());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertEquals("proxy.lan:3128", row.getText());
        assertEquals("proxy.lan:3128", Settings.APP_PROXY_SERVER.savedValue());
    }

    /** A settings file can hold the server only as the row saves it, or blank. */
    @Test public void aBackupHoldsTheServerAsTheRowSavesIt() {
        assertTrue(ConfigurationBackup.textAllowed(Settings.APP_PROXY_SERVER, ""));
        assertTrue(ConfigurationBackup.textAllowed(Settings.APP_PROXY_SERVER, "proxy.lan:3128"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.APP_PROXY_SERVER, "http://proxy.lan:3128"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.APP_PROXY_SERVER, "Proxy.Lan:3128"));
        assertFalse(ConfigurationBackup.textAllowed(Settings.APP_PROXY_SERVER, "proxy.lan"));
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
