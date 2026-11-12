/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
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

import app.hushgram.extension.instagram.download.SavesForTests;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Recovery controls remain available in the new overview and dedicated section-page model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class PauseSectionTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private static final long MINUTE = 60_000L;
    private final SettingsLockTest.Phone phone = new SettingsLockTest.Phone();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.CATEGORY_PAGES.save(true);
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        PauseForTests.resume();
        SavesForTests.resetInterruption();
        SettingsLock.clocks = phone;
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        SettingsLock.clocks = SettingsLock.PHONE;
        Settings.SETTINGS_LOCK_HOURS.resetToDefault();
        Settings.SETTINGS_LOCK_ASKED.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        BaseSettings.PAUSED.resetToDefault();
        PauseForTests.resume();
        SavesForTests.resetInterruption();
        PatchFamily.inBuildForTests = null;
        SettingsEntry.onClosedByUser();
    }

    private void open() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private HushgramPreferenceFragment.Heading pause() {
        PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading
                    && "Pause and diagnostics".contentEquals(item.getTitle())) {
                return (HushgramPreferenceFragment.Heading) item;
            }
        }
        throw new AssertionError("no Pause and diagnostics section on the current page");
    }

    private boolean onScreen(String key) {
        return page.getPreferenceScreen().findPreference(key) != null;
    }

    private void assertRecoveryPage(String why) {
        HushgramPreferenceFragment.Heading section = pause();
        assertEquals(why, section.getKey(), page.currentPage());
        assertFalse(why + ": section headings do not expand inside a section page", section.collapsible);
        assertTrue(why + ": the recovery page has a section heading", section.pageHeader);
        assertTrue(why + ": Pause stays in reach", onScreen(BaseSettings.PAUSED.key));
        assertTrue(why + ": the lock stays in reach", onScreen(HushgramPreferenceFragment.LOCK_ROW_KEY));
    }

    private void tap(Preference row) {
        assertNotNull("the row has its original navigation action", row.getOnPreferenceClickListener());
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    @Test public void theSectionOpensAsASeparatePageAndBackReturnsToTheOverview() throws Exception {
        open();
        HushgramPreferenceFragment.Heading section = pause();
        assertTrue(section.collapsible);
        assertFalse(section.pageHeader);
        assertEquals(0, section.getPreferenceCount());
        assertFalse(onScreen(BaseSettings.DEBUG.key));
        assertNotNull("the hidden original row remains searchable through the fragment", page.findPreference(BaseSettings.DEBUG.key));

        tap(section);
        assertRecoveryPage("Pause and diagnostics opens");
        assertTrue(onScreen(BaseSettings.DEBUG.key));
        assertTrue("Back returns to the overview", page.closeCategory());
        assertEquals("", page.currentPage());
        assertTrue(pause().collapsible);
        assertFalse(onScreen(BaseSettings.DEBUG.key));
    }

    @Test public void itOpensByItselfWhilePausedFromTheNextStart() throws Exception {
        BaseSettings.PAUSED.save(true);
        open();
        assertRecoveryPage("paused from the next start");
    }

    @Test public void itOpensByItselfWhileThisStartIsPaused() throws Exception {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        open();
        assertRecoveryPage("paused now");
    }

    @Test public void itOpensWhileLockedAndStaysOnTheRecoveryPageAsTheWaitChanges() throws Exception {
        assertTrue(SettingsLock.lock(1));
        open();
        assertRecoveryPage("locked");
        assertTrue(SettingsLock.ask());
        page.showLock();
        assertRecoveryPage("waiting to open");

        phone.pass(60 * MINUTE);
        page.showLock();
        assertEquals(SettingsLock.State.OPEN, SettingsLock.state());
        assertRecoveryPage("unlocked for the open window");

        phone.pass(SettingsLock.OPEN_MS);
        page.showLock();
        assertEquals(SettingsLock.State.LOCKED, SettingsLock.state());
        assertRecoveryPage("locked again on its own clock");
    }

    @Test public void aLockSetDuringTheSessionOpensTheRecoveryPage() throws Exception {
        open();
        assertEquals("", page.currentPage());
        assertTrue(SettingsLock.lock(1));
        page.showLock();
        assertRecoveryPage("just locked");
    }

    @Test public void anInterruptedSaveOpensThePageWithItsWarning() throws Exception {
        SavesForTests.interrupt(RuntimeEnvironment.getApplication());
        open();
        assertRecoveryPage("a save was interrupted");
        assertTrue(onScreen("hushgram_interrupted_saves"));
    }

    @Test public void searchStillFindsRecoveryRows() throws Exception {
        open();
        page.searchSettings("debug");
        assertTrue(onScreen(BaseSettings.DEBUG.key));
        page.searchSettings("");
        assertEquals("", page.currentPage());
        assertFalse(onScreen(BaseSettings.DEBUG.key));
    }

    @Test public void recoverySectionStaysVisibleWhenSearchHasNoMatch() throws Exception {
        open();
        page.searchSettings("zzzz-no-match");
        assertNotNull("pause controls must remain reachable during search", page.getPreferenceScreen().findPreference(BaseSettings.PAUSED.key));
        assertNotNull("diagnostics must remain reachable during search", page.getPreferenceScreen().findPreference(BaseSettings.DEBUG.key));
        assertNotNull("the recovery category itself must stay attached", pause().getParent());
        assertNotNull("the empty search notice remains visible alongside recovery controls",
                page.getPreferenceScreen().findPreference("hushgram_settings_search_empty"));
    }
}
