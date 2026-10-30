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

/**
 * Pause and diagnostics folds like every other category, and opens by itself, without a way to
 * fold it, while HushGram is paused, the settings are locked or a save was interrupted.
 */
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
        throw new AssertionError("no Pause and diagnostics on the overview");
    }

    private boolean onScreen(String key) {
        return page.getPreferenceScreen().findPreference(key) != null;
    }

    private void assertHeldOpen(String why) {
        HushgramPreferenceFragment.Heading pause = pause();
        assertFalse(why + ": the section can't fold", pause.collapsible);
        assertTrue(why, pause.expanded);
        assertFalse(why + ": a tap on the heading does nothing", pause.isEnabled());
        assertTrue(why + ": Pause in reach", onScreen(BaseSettings.PAUSED.key));
        assertTrue(why + ": the lock in reach", onScreen(HushgramPreferenceFragment.LOCK_ROW_KEY));
    }

    private void tap(Preference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
    }

    @Test public void itFoldsDuringNormalUseAndATapOpensIt() throws Exception {
        open();
        HushgramPreferenceFragment.Heading pause = pause();
        assertTrue(pause.collapsible);
        assertFalse(pause.expanded);
        assertEquals(0, pause.getPreferenceCount());
        assertFalse(onScreen(BaseSettings.DEBUG.key));
        assertNotNull("folded rows stay the page's own", page.findPreference(BaseSettings.DEBUG.key));

        tap(pause);
        assertTrue(pause.expanded);
        assertTrue(onScreen(BaseSettings.PAUSED.key));
        assertTrue(onScreen(HushgramPreferenceFragment.LOCK_ROW_KEY));
        assertTrue(onScreen(BaseSettings.DEBUG.key));
        tap(pause);
        assertFalse(pause.expanded);
        assertFalse(onScreen(BaseSettings.PAUSED.key));
    }

    @Test public void itOpensByItselfWhilePausedFromTheNextStart() throws Exception {
        BaseSettings.PAUSED.save(true);
        open();
        assertHeldOpen("paused from the next start");
    }

    @Test public void itOpensByItselfWhileThisStartIsPaused() throws Exception {
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        open();
        assertHeldOpen("paused now");
    }

    @Test public void itOpensWhileLockedAndStaysOpenOnceTheWaitEnds() throws Exception {
        assertTrue(SettingsLock.lock(1));
        open();
        assertHeldOpen("locked");
        assertTrue(SettingsLock.ask());
        page.showLock();
        assertHeldOpen("waiting to open");

        phone.pass(60 * MINUTE);
        page.showLock();
        assertEquals(SettingsLock.State.OPEN, SettingsLock.state());
        HushgramPreferenceFragment.Heading pause = pause();
        assertTrue("open settings fold again", pause.collapsible);
        assertTrue("but the row just used doesn't fold away", pause.expanded);
        assertTrue(onScreen(HushgramPreferenceFragment.LOCK_ROW_KEY));

        phone.pass(SettingsLock.OPEN_MS);
        page.showLock();
        assertEquals(SettingsLock.State.LOCKED, SettingsLock.state());
        assertHeldOpen("locked again on its own clock");
    }

    @Test public void aLockSetFromTheFoldedSectionOpensIt() throws Exception {
        open();
        assertTrue(pause().collapsible);
        assertTrue(SettingsLock.lock(1));
        page.showLock();
        assertHeldOpen("just locked");
    }

    @Test public void anInterruptedSaveOpensItWithTheWarning() throws Exception {
        SavesForTests.interrupt(RuntimeEnvironment.getApplication());
        open();
        assertHeldOpen("a save was interrupted");
        assertTrue(onScreen("hushgram_interrupted_saves"));
    }

    @Test public void searchStillFindsItsRows() throws Exception {
        open();
        page.searchSettings("debug");
        assertTrue(onScreen(BaseSettings.DEBUG.key));
        page.searchSettings("");
        assertTrue(pause().collapsible);
        assertFalse(onScreen(BaseSettings.DEBUG.key));
    }
}
