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
import android.view.WindowManager;
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

/** Hide reels with few views' rows (#444): the switch, off to start, and the Minimum views row under it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class LowViewReelsSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.HIDE_LOW_VIEW_REELS.resetToDefault();
        Settings.MIN_REEL_VIEWS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.HIDE_LOW_VIEW_REELS.resetToDefault();
        Settings.MIN_REEL_VIEWS.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(boolean installed) throws Exception {
        PatchFamily.inBuildForTests = installed
                ? EnumSet.of(PatchFamily.LOW_VIEW_REELS) : EnumSet.noneOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoRows() throws Exception {
        open(false);
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_LOW_VIEW_REELS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.MIN_REEL_VIEWS.key));
        assertFalse(ConfigurationBackup.eligible().containsKey(Settings.MIN_REEL_VIEWS.key));
    }
    @Test public void theMinimumSitsUnderItsSwitchUnderReelsAndWaitsForIt() throws Exception {
        open(true);
        SwitchPreference hide = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.HIDE_LOW_VIEW_REELS.key);
        HushgramPreferenceFragment.MinViewsRow min =
                (HushgramPreferenceFragment.MinViewsRow) page.getPreferenceScreen().findPreference(Settings.MIN_REEL_VIEWS.key);
        assertNotNull(hide);
        assertNotNull(min);
        assertEquals("Hide reels with few views", hide.getTitle().toString());
        assertFalse("off to start", hide.isChecked());
        assertEquals("Minimum views", min.getTitle().toString());
        PreferenceCategory section = categoryHolding(page.getPreferenceScreen(), hide);
        assertNotNull(section);
        assertEquals("Reels", section.getTitle().toString());
        assertSame(section, categoryHolding(page.getPreferenceScreen(), min));
        assertEquals("right under its switch", indexIn(section, hide) + 1, indexIn(section, min));
        assertFalse("waits for the switch", min.isEnabled());
        hide.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(min.isEnabled());
        assertTrue(ConfigurationBackup.eligible().containsKey(Settings.MIN_REEL_VIEWS.key));
    }
    @Test public void theSummaryFormatsTheNumberAndTheValueStaysInRange() throws Exception {
        open(true);
        HushgramPreferenceFragment.MinViewsRow min =
                (HushgramPreferenceFragment.MinViewsRow) page.getPreferenceScreen().findPreference(Settings.MIN_REEL_VIEWS.key);
        assertEquals(String.format("Fewer than %s views", java.text.NumberFormat.getIntegerInstance().format(1000)),
                min.getSummary().toString());
        min.setText("25000");
        assertEquals(String.format("Fewer than %s views", java.text.NumberFormat.getIntegerInstance().format(25000)),
                min.getSummary().toString());
        assertEquals(1, HushgramPreferenceFragment.MinViewsRow.valueOf("0"));
        assertEquals(100_000_000, HushgramPreferenceFragment.MinViewsRow.valueOf("999999999"));
        assertEquals(1000, HushgramPreferenceFragment.MinViewsRow.valueOf("abc"));
        assertEquals(1000, HushgramPreferenceFragment.MinViewsRow.valueOf(null));
    }
    @Test public void theEditDialogResizesAboveTheKeyboard() throws Exception {
        open(true);
        HushgramPreferenceFragment.MinViewsRow min =
                (HushgramPreferenceFragment.MinViewsRow) page.getPreferenceScreen().findPreference(Settings.MIN_REEL_VIEWS.key);
        min.showDialog(null);
        ShadowLooper.idleMainLooper();
        try {
            int mode = min.getDialog().getWindow().getAttributes().softInputMode;
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    mode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        } finally {
            min.getDialog().dismiss();
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
