/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceScreen;
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
import org.robolectric.shadows.ShadowToast;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Support HushGram: the settings home's last row, which opens the Ko-fi page in a browser. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SupportRowSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.CATEGORY_PAGES.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED);
        ShadowToast.reset();
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false);
        SettingsEntry.onClosedByUser();
    }

    private void open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private Preference last() {
        PreferenceScreen screen = page.getPreferenceScreen();
        return screen.getPreference(screen.getPreferenceCount() - 1);
    }

    private void assertLastIsSupport() {
        Preference row = last();
        assertEquals("action_support_hushgram", row.getKey());
        assertEquals("Support HushGram", String.valueOf(row.getTitle()));
        assertEquals("Buy me a coffee on Ko-fi", String.valueOf(row.getSummary()));
    }

    @Test public void itEndsTheHomeAsOneList() throws Exception {
        open();
        assertLastIsSupport();
    }

    @Test public void itEndsTheHomeWhenCategoriesArePages() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        assertLastIsSupport();
    }

    @Test public void aTapOpensKofiInABrowserTask() throws Exception {
        open();
        Preference row = last();
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        Intent started = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        if (started == null) started = shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals(Uri.parse("https://ko-fi.com/X8K126YVER"), started.getData());
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test public void noBrowserShowsATipAndKeepsRunning() throws Exception {
        open();
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        Preference row = last();
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
        String tip = String.valueOf(ShadowToast.getTextOfLatestToast());
        assertTrue(tip, tip.startsWith("No app on this phone can open the link. The address is "));
        assertTrue(tip, tip.contains("ko-fi.com/X8K126YVER"));
    }

    @Test public void aCategoryPageAndAMissSearchLeaveItOff() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open();
        page.searchSettings("zzzz nothing");
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            assertNotEquals("action_support_hushgram", screen.getPreference(i).getKey());
        }
        page.searchSettings("coffee");
        assertLastIsSupport();
        page.searchSettings("");
        assertLastIsSupport();
    }
}
