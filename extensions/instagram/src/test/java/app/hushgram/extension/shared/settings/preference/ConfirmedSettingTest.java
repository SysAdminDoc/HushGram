/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.Setting;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** A switch whose setting asks first keeps, everywhere, whatever the answer to that question was. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ConfirmedSettingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * Made once, after the rule has given Setting a context, since a second Setting under one key
     * is an error. Kept out of import and export, and back at its default after each test.
     */
    private static BooleanSetting asked;

    @Before public void make() {
        if (asked == null) {
            asked = new BooleanSetting("hushgram_test_asks_first", false, true, false,
                    "Turning this on needs a restart.", null);
        }
        asked.resetToDefault();
    }

    @After public void restore() {
        asked.resetToDefault();
        AbstractPreferenceFragment.restartPending.clear();
    }

    @Test public void cancellingPutsTheValueBackInTheStoreToo() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SwitchPreference row = flipOn(controller);
            assertTrue("the flip is what writes the store", store().getBoolean(asked.key, false));

            press(ShadowDialog.getLatestDialog(), "Cancel");

            assertFalse("the setting keeps the value it had", asked.savedValue());
            assertFalse("the row shows it", row.isChecked());
            assertFalse("and the store holds it, as an absent key for the default", store().contains(asked.key));
            assertFalse("with no restart owed", AbstractPreferenceFragment.restartPending.contains(asked.key));
        }
    }

    @Test public void confirmingKeepsTheNewValueAndOwesARestart() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SwitchPreference row = flipOn(controller);

            press(ShadowDialog.getLatestDialog(), "OK");

            assertTrue(asked.savedValue());
            assertTrue(row.isChecked());
            assertTrue(store().getBoolean(asked.key, false));
            assertTrue(AbstractPreferenceFragment.restartPending.contains(asked.key));
        }
    }

    /** Opens the page and turns its switch on, which asks before it applies. */
    private static SwitchPreference flipOn(ActivityController<Activity> controller) {
        OneSwitchPage page = new OneSwitchPage();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        SwitchPreference row = (SwitchPreference) page.findPreference(asked.key);
        assertNotNull(row);
        assertFalse(row.isChecked());
        row.setChecked(true);
        ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue("the switch asks first", dialog != null && dialog.isShowing());
        return row;
    }

    private static void press(Dialog dialog, String label) {
        Button button = buttonIn(dialog.getWindow().getDecorView(), label);
        assertNotNull("no " + label + " button", button);
        button.performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
    }

    private static Button buttonIn(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = buttonIn(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static SharedPreferences store() {
        return Setting.preferences.preferences;
    }

    /** A page holding the one switch. */
    public static final class OneSwitchPage extends AbstractPreferenceFragment {
        @Override protected void initialize() {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(getActivity());
            setPreferenceScreen(screen);
            SwitchPreference row = new SwitchPreference(getActivity());
            row.setKey(asked.key);
            row.setTitle("Asks first");
            screen.addPreference(row);
        }
    }
}
