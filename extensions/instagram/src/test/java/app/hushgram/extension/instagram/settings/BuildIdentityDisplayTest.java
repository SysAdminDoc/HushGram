/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {28, 37}, shadows = BuildIdentityDisplayTest.Metadata.class)
@SuppressWarnings("deprecation")
public class BuildIdentityDisplayTest {
    private static final String ID = "hg1:cd44116f6ee1923f60ea7b97e6f131740dd97f13e5e65330ba3242d39f94039b";
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;

    @Implements(value = Utils.class, isInAndroidSdk = false)
    public static class Metadata {
        @Implementation public static String getSourceBuildIdentity() { return ID; }
    }

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.DM_MEDIA_SEEN);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private HushgramPreferenceFragment open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        return page;
    }

    private Preference version(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if ("Version".contentEquals(row.getTitle())) return row;
            if (row instanceof PreferenceGroup) {
                Preference found = version((PreferenceGroup) row);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void aboutShowsTheCompleteIdentityBesideTheVersionWithoutChangingInstalledFamilies() throws Exception {
        HushgramPreferenceFragment page = open();
        Preference row = version(page.getPreferenceScreen());
        assertNotNull(row);
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains(ID));
        assertEquals(EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.DM_MEDIA_SEEN), PatchFamily.inThisBuild());
    }

    @Test public void localDiagnosticsKeepTheIdentityEvenWhenEventSectionsAreFiltered() throws Exception {
        open();
        BaseSettings.DEBUG.save(false);
        BaseSettings.DEBUG_LOG_FILTERS.save("downloads");
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("source_build: " + ID + "\n"));
        assertFalse(report.contains("[SELECTED EVENTS]"));
        assertFalse(report.contains("account_id"));
        assertFalse(report.contains("access_token"));
        assertFalse(report.contains("https://"));
    }

    @Config(sdk = 28)
    @Test public void theExportedLocalFileCarriesTheSameIdentityAsAbout() throws Exception {
        HushgramPreferenceFragment page = open();
        String report = LogBufferManager.buildExportText();
        String saved = ReflectionHelpers.callStaticMethod(LogBufferManager.class, "writeToFile",
                ReflectionHelpers.ClassParameter.from(android.content.Context.class, RuntimeEnvironment.getApplication()),
                ReflectionHelpers.ClassParameter.from(String.class, report));
        assertNotNull(saved);
        File file = new File(RuntimeEnvironment.getApplication().getExternalFilesDir(null),
                "Download/Morphe/" + saved.substring(saved.lastIndexOf('/') + 1));
        try {
            String bytes = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(bytes, bytes.contains("source_build: " + ID));
            assertTrue(version(page.getPreferenceScreen()).getSummary().toString().contains(ID));
        } finally { Files.delete(file.toPath()); }
    }
}
