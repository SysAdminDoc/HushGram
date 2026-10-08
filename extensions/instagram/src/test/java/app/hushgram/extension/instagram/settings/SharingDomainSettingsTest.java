/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
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
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
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
