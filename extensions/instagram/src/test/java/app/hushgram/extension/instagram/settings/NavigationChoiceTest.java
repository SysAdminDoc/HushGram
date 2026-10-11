/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.preference.Preference;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.EnumSet;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class NavigationChoiceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Before public void setUp() {
        PauseForTests.resume();
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
    }
    @After public void tearDown() throws Exception {
        PauseForTests.resume();
        PatchFamily.inBuildForTests = null;
        Settings.NAVIGATION_SETTINGS_TARGET.resetToDefault();
        Utils.awaitBackgroundTasksForTests();
    }

    @Test public void choiceStartsOffOffersEveryNativeNameAndPersistsOneSelection() {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            Preference preference = page.findPreference(Settings.NAVIGATION_SETTINGS_TARGET.key);
            assertTrue(preference instanceof HushgramPreferenceFragment.NavigationRow);
            HushgramPreferenceFragment.NavigationRow row = (HushgramPreferenceFragment.NavigationRow) preference;
            assertEquals("OFF", row.getValue());
            assertEquals(NavigationTarget.OFF, Settings.NAVIGATION_SETTINGS_TARGET.get());
            assertTrue(row.getSummary().toString().contains("Instagram's own action"));
            assertEquals(NavigationTarget.values().length, row.getEntries().length);
            for (int i = 0; i < NavigationTarget.values().length; i++) {
                assertEquals(NavigationTarget.values()[i].name(), row.getEntryValues()[i]);
            }
            row.setValue("PROFILE");
            ShadowLooper.idleMainLooper();
            assertEquals(NavigationTarget.PROFILE, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
            assertTrue(row.getSummary().toString().contains("Long press Profile"));
            // The tabs take a change at once (#82), so the row no longer asks for a restart.
            assertFalse(row.getSummary().toString().contains("Restart Instagram"));
            assertFalse(Settings.NAVIGATION_SETTINGS_TARGET.rebootApp);
        }
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            assertEquals("PROFILE", ((HushgramPreferenceFragment.NavigationRow)
                    page.findPreference(Settings.NAVIGATION_SETTINGS_TARGET.key)).getValue());
        }
    }

    @Test public void pauseShowsTheSavedChoiceWhileTheHookAnswersOff() {
        Settings.NAVIGATION_SETTINGS_TARGET.save(NavigationTarget.CLIPS);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            HushgramPreferenceFragment.NavigationRow row = (HushgramPreferenceFragment.NavigationRow)
                    page.findPreference(Settings.NAVIGATION_SETTINGS_TARGET.key);
            assertEquals("CLIPS", row.getValue());
            assertEquals(NavigationTarget.OFF, Settings.NAVIGATION_SETTINGS_TARGET.get());
            assertTrue(row.getSummary().toString().contains("Long press Reels"));
        }
    }

    @Test @Config(qualifiers = "es") public void theChooserAndSelectedSummaryUseTranslatedLabels() {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment.NavigationRow row = HushgramPreferenceFragment.navigationRow(activity.get());
            assertEquals("Abrir ajustes al mantener pulsada una pestaña", row.getTitle());
            assertEquals("Elegir una pestaña", row.getDialogTitle());
            assertEquals("Inicio", row.getEntries()[NavigationTarget.FEED.ordinal()]);
            row.setValue("FEED");
            assertTrue(row.getSummary().toString().contains("Mantén pulsado Inicio"));
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "w360dp-h760dp-mdpi") public void defaultAndSelectedSettingsRowsRenderWithoutTruncatingTheirText() throws Exception {
        try (ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup()) {
            HushgramPreferenceFragment page = DownloadSettingsTest.pageIn(activity);
            HushgramPreferenceFragment.NavigationRow row = (HushgramPreferenceFragment.NavigationRow)
                    page.findPreference(Settings.NAVIGATION_SETTINGS_TARGET.key);
            capture(page.getView(), "off");
            row.setValue("FEED");
            ShadowLooper.idleMainLooper();
            View actual = row.getView(null, page.getView().findViewById(android.R.id.list));
            actual.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            actual.layout(0, 0, actual.getMeasuredWidth(), actual.getMeasuredHeight());
            android.widget.TextView summary = actual.findViewById(android.R.id.summary);
            assertEquals(row.getSummary(), summary.getText());
            assertTrue(summary.getLineCount() > 2);
            for (int line = 0; line < summary.getLineCount(); line++) assertEquals(0, summary.getLayout().getEllipsisCount(line));
            assertEquals(summary.getText().length(), summary.getLayout().getLineEnd(summary.getLineCount() - 1));
            capture(actual, "selected");
        }
    }

    private static void capture(View view, String state) throws Exception {
        if (view.getWidth() == 0) {
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(760, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 360, 760);
        }
        Bitmap pixels = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(pixels));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, png));
        assertTrue(png.size() > 1000);
        String folder = System.getenv("HUSHGRAM_UI_DIR");
        if (folder != null) {
            File directory = new File(folder);
            assertTrue(directory.mkdirs() || directory.isDirectory());
            try (FileOutputStream out = new FileOutputStream(new File(directory,
                    "navigation-api" + Build.VERSION.SDK_INT + "-" + state + ".png"))) { png.writeTo(out); }
        }
    }
}
