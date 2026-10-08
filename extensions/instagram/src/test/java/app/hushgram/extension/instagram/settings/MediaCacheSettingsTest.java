/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import java.io.File;
import java.io.RandomAccessFile;
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

/** Clear the media cache's rows: under Storage, the switch off to start, and Clear now showing what it freed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class MediaCacheSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.CLEAR_MEDIA_CACHE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CLEAR_MEDIA_CACHE.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed ? EnumSet.of(PatchFamily.MEDIA_CACHE) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoStorageRows() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.CLEAR_MEDIA_CACHE.key));
        assertNull(page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.CLEAR_MEDIA_CACHE_NOW));
    }
    @Test public void theSwitchSitsUnderStorageAndStartsOff() throws Exception {
        open(true);
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.CLEAR_MEDIA_CACHE.key);
        assertNotNull(row);
        assertEquals("Clear the media cache", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("500 MB"));
        PreferenceCategory storage = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(storage);
        assertEquals("Storage", storage.getTitle().toString());
        assertEquals(2, storage.getPreferenceCount());
        assertFalse(row.isChecked());
        assertFalse(Settings.CLEAR_MEDIA_CACHE.get());
        assertEquals(java.util.Collections.singletonList(Settings.CLEAR_MEDIA_CACHE), PatchFamily.MEDIA_CACHE.switches);
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.CLEAR_MEDIA_CACHE.key));
    }
    @Test public void clearNowDeletesTheCacheAndShowsWhatItFreed() throws Exception {
        File image = new File(RuntimeEnvironment.getApplication().getCacheDir(), "images/a.jpg");
        image.getParentFile().mkdirs();
        try (RandomAccessFile out = new RandomAccessFile(image, "rw")) {
            out.setLength(2048);
        }
        assertTrue(image.setLastModified(System.currentTimeMillis() - 5 * 60_000));
        open(true);
        Preference clear = page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.CLEAR_MEDIA_CACHE_NOW);
        assertNotNull(clear);
        assertEquals("Clear the cache now", clear.getTitle().toString());

        assertTrue(clear.getOnPreferenceClickListener().onPreferenceClick(clear));
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();

        assertFalse(image.exists());
        assertTrue(clear.getSummary().toString(), clear.getSummary().toString().startsWith("Freed "));
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
