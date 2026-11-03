/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.app.Activity;
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
import app.hushgram.extension.instagram.misc.PhoneLayout;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The Phone layout on big screens switch (#478): on the Layout page, off to start, and a restart switch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class PhoneLayoutSettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.PHONE_LAYOUT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        BaseSettings.SAFE_MODE.save(false);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
    }
    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.PHONE_LAYOUT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
    }
    private void open(EnumSet<PatchFamily> build) throws Exception {
        PatchFamily.inBuildForTests = build;
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
    }
    @Test public void missingPatchHasNoSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE));
        assertNull(page.getPreferenceScreen().findPreference(Settings.PHONE_LAYOUT.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.REMOVE_BOTTOM_SPACE.key));
    }
    /** Without the other Layout patches, Layout still opens for this switch alone, and it starts off. */
    @Test public void aloneItStillGetsLayoutAndStartsOff() throws Exception {
        open(EnumSet.of(PatchFamily.PHONE_LAYOUT));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PHONE_LAYOUT.key);
        assertNotNull(row);
        assertEquals("Layout", row.getParent().getTitle().toString());
        assertEquals("Phone layout on big screens", row.getTitle().toString());
        assertTrue(row.getSummary().toString(), row.getSummary().toString().contains("tab bar at the bottom"));
        assertFalse(row.isChecked());
        assertTrue(Settings.PHONE_LAYOUT.rebootApp);
        assertEquals(673, PhoneLayout.width(673));
    }
    /** It sits right after Remove the empty space at the bottom, the other switch about the tab bar's place. */
    @Test public void itFollowsTheBottomSpaceSwitch() throws Exception {
        open(EnumSet.of(PatchFamily.BOTTOM_SPACE, PatchFamily.PHONE_LAYOUT));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PHONE_LAYOUT.key);
        android.preference.PreferenceGroup layout = row.getParent();
        assertEquals(Settings.REMOVE_BOTTOM_SPACE.key, layout.getPreference(0).getKey());
        assertEquals(Settings.PHONE_LAYOUT.key, layout.getPreference(1).getKey());
    }
    /** Flipping the switch reaches the hooks on their next read, which Instagram makes as it starts again. */
    @Test public void flippingItReachesTheNextRead() throws Exception {
        open(EnumSet.of(PatchFamily.PHONE_LAYOUT));
        SwitchPreference row = (SwitchPreference) page.getPreferenceScreen().findPreference(Settings.PHONE_LAYOUT.key);
        row.setChecked(true);
        assertTrue(Settings.PHONE_LAYOUT.get());
        assertEquals(599, PhoneLayout.width(673));
        assertFalse(PhoneLayout.foldable(1));
        row.setChecked(false);
        assertEquals(673, PhoneLayout.width(673));
        assertTrue(PhoneLayout.foldable(1));
    }
}
