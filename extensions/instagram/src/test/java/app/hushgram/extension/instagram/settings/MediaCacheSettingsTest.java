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
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
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
        Settings.MEDIA_CACHE_LIMIT_MB.resetToDefault();
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
        assertEquals("When Instagram's saved photos and videos pass the limit below, deletes the photos as you leave "
                + "it and the videos the next time it starts. Your sign-in, drafts and settings stay.", row.getSummary().toString());
        PreferenceCategory storage = categoryHolding(page.getPreferenceScreen(), row);
        assertNotNull(storage);
        assertEquals("Storage", storage.getTitle().toString());
        assertEquals(4, storage.getPreferenceCount());
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
        assertEquals("Clear saved copies now", clear.getTitle().toString());

        assertTrue(clear.getOnPreferenceClickListener().onPreferenceClick(clear));
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();

        assertFalse(image.exists());
        assertTrue(clear.getSummary().toString(), clear.getSummary().toString().startsWith("Freed "));
    }

    /**
     * #713: a slider under the switch picks the size, 500 MB to start, and waits for the switch. Clear
     * now says how much the saved photos and videos take up, read once the page is built.
     */
    @Test public void aSliderPicksTheLimitAndClearNowSaysTheSize() throws Exception {
        File image = new File(RuntimeEnvironment.getApplication().getCacheDir(), "image_scoped/0/a.jpg");
        image.getParentFile().mkdirs();
        try (RandomAccessFile out = new RandomAccessFile(image, "rw")) {
            out.setLength(3 * 1024 * 1024);
        }
        open(true);
        ShadowLooper.idleMainLooper();
        SwitchPreference toggle = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.CLEAR_MEDIA_CACHE.key);
        Preference found = page.getPreferenceScreen().findPreference(Settings.MEDIA_CACHE_LIMIT_MB.key);
        assertTrue(found instanceof HushgramPreferenceFragment.SliderRow);
        HushgramPreferenceFragment.SliderRow slider = (HushgramPreferenceFragment.SliderRow) found;
        PreferenceCategory storage = categoryHolding(page.getPreferenceScreen(), toggle);
        assertSame("right under the switch", slider, storage.getPreference(1));
        assertEquals("Cache size limit", slider.getTitle().toString());
        assertEquals(100, slider.step);
        assertEquals(100, Settings.MEDIA_CACHE_LIMIT_MB.minimum());
        assertEquals(4000, Settings.MEDIA_CACHE_LIMIT_MB.maximum());
        assertEquals(500, (int) Settings.MEDIA_CACHE_LIMIT_MB.get());
        assertFalse("it waits for the switch", slider.isEnabled());
        assertEquals("Turn on Clear the media cache to use this slider.", slider.getSummary().toString());
        toggle.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(slider.isEnabled());
        assertEquals("500 MB (standard)", slider.getSummary().toString());
        slider.choose(1500);
        ShadowLooper.idleMainLooper();
        assertEquals(1500, (int) Settings.MEDIA_CACHE_LIMIT_MB.savedValue());
        assertEquals("1500 MB", slider.getSummary().toString());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.MEDIA_CACHE_LIMIT_MB.key));

        Preference clear = page.getPreferenceScreen().findPreference(HushgramPreferenceFragment.CLEAR_MEDIA_CACHE_NOW);
        String size = android.text.format.Formatter.formatShortFileSize(RuntimeEnvironment.getApplication(), 3 * 1024 * 1024);
        String[] lines = clear.getSummary().toString().split("\n");
        assertEquals(2, lines.length);
        assertEquals(HushgramPreferenceFragment.clearMediaCacheSummary(), lines[0]);
        assertEquals("Saved photos and videos take up " + size + " now.", lines[1].replaceAll("[\\u2066-\\u2069]", ""));
    }
    /**
     * #683: Space used, last under Storage, adds up Instagram's data off the main thread and says its
     * total, its biggest folders and HushGram's share, one line each.
     */
    @Test public void spaceUsedSaysWhatTakesUpInstagramsData() throws Exception {
        File big = new File(RuntimeEnvironment.getApplication().getDataDir(), "files/hushgram-test/big.bin");
        assertTrue(big.getParentFile().isDirectory() || big.getParentFile().mkdirs());
        try (RandomAccessFile out = new RandomAccessFile(big, "rw")) {
            out.setLength(8L * 1024 * 1024);
        }
        try {
            open(true);
            ShadowLooper.idleMainLooper();
            Preference row = page.getPreferenceScreen().findPreference(StorageCategory.SPACE_USED);
            assertNotNull(row);
            assertEquals("Space used", row.getTitle().toString());
            PreferenceCategory storage = categoryHolding(page.getPreferenceScreen(), row);
            assertSame("last under Storage", row, storage.getPreference(storage.getPreferenceCount() - 1));
            assertFalse(row.isSelectable());

            String[] lines = plain(row.getSummary().toString()).split("\n");
            assertEquals(row.getSummary().toString(), 3, lines.length);
            assertTrue(lines[0], lines[0].startsWith("Instagram's data takes up ") && lines[0].endsWith(", its cache aside."));
            assertTrue("the biggest folder comes first: " + lines[1], lines[1].startsWith("Biggest: files/hushgram-test "));
            assertTrue(lines[2], lines[2].startsWith("HushGram's own files take up "));
            assertEquals("Couldn't add up the space used.", StorageCategory.spaceUsedSummary(RuntimeEnvironment.getApplication(), null));
        } finally {
            assertTrue(big.delete());
        }
    }

    /** [text] without the direction marks L10n.isolate puts around sizes and names. */
    private static String plain(String text) {
        return text.replaceAll("[" + (char) 0x2066 + "-" + (char) 0x2069 + "]", "");
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
