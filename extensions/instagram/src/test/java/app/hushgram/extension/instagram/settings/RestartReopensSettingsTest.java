/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.TwoStatePreference;
import android.view.View;

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
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.shared.settings.preference.ReopenSettingsAfterRestart;

/**
 * Restart now expands the last section on the main settings screen once Instagram is up again,
 * and the card it was tapped on is the restart feedback: no toast covers the page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation") // Framework fragments, as the entry uses.
public class RestartReopensSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final AtomicInteger ended = new AtomicInteger();

    @Before
    public void prepare() {
        Application app = RuntimeEnvironment.getApplication();
        app.getApplicationInfo().targetSdkVersion = 37;
        SettingsEntry.onClosedByUser();
        Settings.CATEGORY_PAGES.save(true);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED);
        RestartNow.endProcessForTests = ended::incrementAndGet;
        ComponentName launcher = new ComponentName(app.getPackageName(), "com.instagram.mainactivity.LauncherActivity");
        IntentFilter main = new IntentFilter(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        ShadowPackageManager packages = shadowOf(app.getPackageManager());
        packages.addActivityIfNotPresent(launcher);
        packages.addIntentFilterForActivity(launcher, main);
        ShadowToast.reset();
    }

    @After
    public void restore() throws Exception {
        // A resumed screen queues a shortcut check on a background thread.
        Utils.awaitBackgroundTasksForTests();
        RestartNow.endProcessForTests = null;
        PatchFamily.inBuildForTests = null;
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        SettingsEntry.onClosedByUser();
    }

    @Test
    public void restartNowExpandsTheSameSectionOnTheMainPage() throws Exception {
        // Before the restart: the screen is open on Feed, with a change that waits on a restart.
        Activity before = Robolectric.buildActivity(Activity.class).setup().get();
        SettingsDialog first = SettingsDialog.onPage(null);
        first.show(before.getFragmentManager(), SettingsEntry.DIALOG_TAG);
        before.getFragmentManager().executePendingTransactions();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        HushgramPreferenceFragment page = pageOf(first);
        View bar = first.getView().findViewById(SettingsDialog.RESTART_ID);
        Preference feed = categoryRow(page, "Feed");
        assertTrue(feed.getOnPreferenceClickListener().onPreferenceClick(feed));
        ShadowLooper.idleMainLooper();
        assertEquals(feed.getKey(), page.currentPage());

        TwoStatePreference following = (TwoStatePreference) page.findPreference(Settings.START_ON_FOLLOWING.key);
        following.setChecked(!following.isChecked());
        ShadowLooper.idleMainLooper();
        assertNotEquals("the bar is the restart, so no toast says it", "Saved. Restart Instagram to apply this change.",
                ShadowToast.getTextOfLatestToast());
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(bar.performClick());
        assertEquals("Instagram didn't restart", 1, ended.get());
        first.dismissAllowingStateLoss();

        // The next start takes the marker, once, and the screen opens on Feed over the first screen.
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(new SettingsEntry.OpenWhenResumed());
        assertTrue(ReopenSettingsAfterRestart.reopenIfPending(SettingsEntry::reopenOn));
        assertFalse("the marker was taken twice", ReopenSettingsAfterRestart.reopenIfPending(ignored -> fail("opened " + ignored)));
        Activity after = Robolectric.buildActivity(Activity.class).setup().get();
        ShadowLooper.idleMainLooper();
        after.getFragmentManager().executePendingTransactions();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        Fragment shown = after.getFragmentManager().findFragmentByTag(SettingsEntry.DIALOG_TAG);
        assertTrue("the screen didn't open after the restart", shown instanceof SettingsDialog);
        assertEquals(feed.getKey(), ((SettingsDialog) shown).startPage());
        assertEquals("not back on the page Restart now was tapped on", feed.getKey(), pageOf((SettingsDialog) shown).currentPage());
        HushgramPreferenceFragment restored = pageOf((SettingsDialog) shown);
        Preference restoredFeed = categoryRow(restored, "Feed");
        assertSame(restoredFeed, restored.findPreference(Settings.START_ON_FOLLOWING.key).getParent());
        assertNotNull(restored.getPreferenceScreen().findPreference("hushgram_view_toggle"));
        assertNotNull(categoryRow(restored, "Ads and privacy"));
    }

    private static HushgramPreferenceFragment pageOf(SettingsDialog dialog) {
        Fragment page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue("no preference page", page instanceof HushgramPreferenceFragment);
        return (HushgramPreferenceFragment) page;
    }

    private static Preference categoryRow(HushgramPreferenceFragment page, String title) {
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item.getKey() != null && item.getKey().startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)
                    && title.contentEquals(item.getTitle())) return item;
        }
        throw new AssertionError("no row for " + title);
    }
}
