/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Fragment;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListAdapter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.download.SaveControl;
import app.hushgram.extension.instagram.download.SavesForTests;
import app.hushgram.extension.instagram.media.ResumePlayback;
import app.hushgram.extension.instagram.media.ResumePlaybackForTests;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;

/** Search preserves installed controls, preference synchronization and the live Cancel action. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsSearchTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;

    @Before public void host() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 36;
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.ONLY_FOLLOWING.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        RuntimeEnvironment.setFontScale(1f);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        SavesForTests.endAll();
        SavesForTests.resetCarouselOutcome();
        Utils.awaitBackgroundTasksForTests();
        RuntimeEnvironment.setFontScale(1f);
        PatchFamily.inBuildForTests = null;
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.ONLY_FOLLOWING.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
    }

    private void open(PatchFamily... families) throws Exception {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        for (PatchFamily family : families) PatchFamily.inBuildForTests.add(family);
        controller = Robolectric.buildActivity(Activity.class).setup();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, page, "search-page").commitNow();
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
    }

    private Preference visible(String key) { return page.getPreferenceScreen().findPreference(key); }

    private List<Preference> rows(PreferenceGroup group) {
        List<Preference> result = new ArrayList<>();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            result.add(row);
            if (row instanceof PreferenceGroup) result.addAll(rows((PreferenceGroup) row));
        }
        return result;
    }

    private View render(Preference wanted) {
        ShadowLooper.idleMainLooper();
        ListAdapter adapter = page.getPreferenceScreen().getRootAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) == wanted) return adapter.getView(i, null, new FrameLayout(controller.get()));
        }
        throw new AssertionError("Preference isn't in the visible adapter: " + wanted.getKey());
    }

    @Test public void clearActionStaysReachableByItsOriginalWordsThroughUndoAndExpiry() throws Exception {
        ResumePlaybackForTests.install();
        try {
            open(PatchFamily.RESUME_LONG_VIDEOS, PatchFamily.HIDE_ADS);
            String query = L10n.t("Clear remembered positions");
            page.searchSettings(query);
            Preference row = visible("hushgram_clear_resume_points");
            assertNotNull(row);
            Preference.OnPreferenceClickListener clear = row.getOnPreferenceClickListener();
            clear.onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertSame(row, visible("hushgram_clear_resume_points"));
            assertEquals(L10n.t("Undo cleared positions"), row.getTitle());
            assertNull(visible(Settings.HIDE_ADS.key));
            long token = ResumePlayback.undoHistoryToken();
            clear.onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            assertEquals(token, ResumePlayback.undoHistoryToken());
            page.searchSettings("");
            page.searchSettings(query);
            assertSame(row, visible("hushgram_clear_resume_points"));
            controller.pause().stop().start().resume();
            assertSame(row, visible("hushgram_clear_resume_points"));
            SystemClock.sleep(ResumePlayback.undoHistoryDeadline(token) - SystemClock.elapsedRealtime());
            ShadowLooper.idleMainLooper();
            assertSame(row, visible("hushgram_clear_resume_points"));
            assertEquals(L10n.t("Clear remembered positions"), row.getTitle());
        } finally { Utils.awaitBackgroundTasksForTests(); ResumePlaybackForTests.forget(); }
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h640dp-xhdpi")
    public void reopeningAnAvailableUndoStillFindsItsLocalizedClearAction() throws Exception {
        ResumePlaybackForTests.install();
        try {
            open(PatchFamily.RESUME_LONG_VIDEOS, PatchFamily.HIDE_ADS);
            Preference row = visible("hushgram_clear_resume_points");
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            controller.close();
            controller = null;
            open(PatchFamily.RESUME_LONG_VIDEOS, PatchFamily.HIDE_ADS);
            page.searchSettings(L10n.t("Clear remembered positions").toUpperCase(Locale.ROOT));
            row = visible("hushgram_clear_resume_points");
            assertNotNull(row);
            assertEquals(L10n.t("Undo cleared positions"), row.getTitle());
            assertNull(visible(Settings.HIDE_ADS.key));
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            Utils.awaitBackgroundTasksForTests();
            ShadowLooper.idleMainLooper();
            assertSame(row, visible("hushgram_clear_resume_points"));
            assertEquals(L10n.t("Clear remembered positions"), row.getTitle());
        } finally { Utils.awaitBackgroundTasksForTests(); ResumePlaybackForTests.forget(); }
    }

    @Test public void exportActionKeepsItsOriginalWordsDuringStatusTitleChanges() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference row = visible("hushgram_export_configuration");
        String query = row.getTitle().toString();
        for (String status : new String[]{"Export running", "Export finished", "Export failed"}) {
            row.setTitle(status);
            page.searchSettings(query);
            assertSame(row, visible("hushgram_export_configuration"));
            assertNull(visible("hushgram_import_configuration"));
            assertNull(visible(Settings.HIDE_ADS.key));
            page.searchSettings("");
        }
    }

    @Test public void verifiedAliasesFindOnlyTheirInstalledControls() throws Exception {
        open(PatchFamily.DISABLE_ANALYTICS, PatchFamily.FOLLOWING_FEED, PatchFamily.REEL_DOWNLOAD);
        for (String query : new String[]{"contacts", "LOCATION SETUP", "analytics"}) {
            page.searchSettings(query);
            assertNotNull(visible(Settings.DISABLE_ANALYTICS.key));
            assertNull(visible(Settings.START_ON_FOLLOWING.key));
        }
        page.searchSettings("following");
        assertNotNull(visible(Settings.START_ON_FOLLOWING.key));
        assertNotNull(visible(Settings.ONLY_FOLLOWING.key));
        page.searchSettings("reels");
        assertNotNull(visible(Settings.DOWNLOAD_REELS.key));
        assertNull(visible(Settings.DOWNLOAD_STORIES.key));
        assertNull(page.findPreference(Settings.DOWNLOAD_STORIES.key));
        page.searchSettings("Hide ads");
        assertNull(visible(Settings.HIDE_ADS.key));
        assertNotNull(visible("hushgram_settings_search_empty"));
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h640dp-xhdpi")
    public void localizedTitlesAndSummariesMatchWithoutCaseOrAccentRestrictions() throws Exception {
        open(PatchFamily.DISABLE_ANALYTICS, PatchFamily.FOLLOWING_FEED);
        Preference analytics = visible(Settings.DISABLE_ANALYTICS.key);
        assertNotEquals("Disable analytics", analytics.getTitle().toString());
        page.searchSettings(analytics.getTitle().toString().toUpperCase(Locale.ROOT));
        assertSame(analytics, visible(Settings.DISABLE_ANALYTICS.key));
        assertNull(visible(Settings.START_ON_FOLLOWING.key));
        page.searchSettings(analytics.getSummary().toString().toUpperCase(Locale.ROOT));
        assertSame(analytics, visible(Settings.DISABLE_ANALYTICS.key));
        page.searchSettings("configuracion de ubicacion");
        assertSame(analytics, visible(Settings.DISABLE_ANALYTICS.key));
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h640dp-xhdpi")
    public void englishPatchNamesFindTheirLocalizedSwitchAndDownloadChildren() throws Exception {
        open(PatchFamily.REEL_DOWNLOAD, PatchFamily.STORY_AUTO_ADVANCE);
        page.searchSettings("DOWNLOAD ANY REEL");
        assertNotNull(visible(Settings.DOWNLOAD_REELS.key));
        assertNotNull(visible(Settings.DOWNLOAD_QUALITY.key));
        assertNotNull(visible(Settings.DOWNLOAD_COMPATIBLE.key));
        assertNotNull(visible(Settings.SAVE_FOLDER.key));
        assertNotNull(visible(Settings.FILENAME_TEMPLATE.key));
        assertNull(visible(Settings.BLOCK_STORY_AUTO_ADVANCE.key));
    }

    @Test @Config(qualifiers = "tr-rTR-w320dp-h640dp-xhdpi")
    public void turkishCaseVariantsAlsoKeepEnglishPatchNameLookup() throws Exception {
        open(PatchFamily.FOLLOWING_FEED, PatchFamily.DISABLE_ANALYTICS);
        Preference following = visible(Settings.START_ON_FOLLOWING.key);
        page.searchSettings(following.getTitle().toString().toUpperCase(Locale.ROOT));
        assertSame(following, visible(Settings.START_ON_FOLLOWING.key));
        page.searchSettings("KISILER");
        assertNotNull(visible(Settings.DISABLE_ANALYTICS.key));
        page.searchSettings("DISABLE ANALYTICS");
        assertNotNull(visible(Settings.DISABLE_ANALYTICS.key));
    }

    @Test public void clearingRestoresTheOriginalObjectsOrderAndChoices() throws Exception {
        open(PatchFamily.FOLLOWING_FEED, PatchFamily.REEL_DOWNLOAD, PatchFamily.DISABLE_ANALYTICS);
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.SMALLEST);
        ShadowLooper.idleMainLooper();
        List<Preference> before = rows(page.getPreferenceScreen());
        ListPreference quality = (ListPreference) visible(Settings.DOWNLOAD_QUALITY.key);
        for (int i = 0; i < 4; i++) {
            page.searchSettings("contacts");
            assertNull(visible(Settings.DOWNLOAD_QUALITY.key));
            page.searchSettings("");
            assertEquals(before, rows(page.getPreferenceScreen()));
            assertSame(quality, visible(Settings.DOWNLOAD_QUALITY.key));
            assertEquals("SMALLEST", quality.getValue());
        }
        assertEquals(DownloadQuality.SMALLEST, Settings.DOWNLOAD_QUALITY.savedValue());
    }

    @Test public void hiddenRowsStillSynchronizeStoredChangesAndParentAvailability() throws Exception {
        Settings.START_ON_FOLLOWING.save(true);
        open(PatchFamily.FOLLOWING_FEED, PatchFamily.DISABLE_ANALYTICS);
        Settings.ONLY_FOLLOWING.save(true);
        ShadowLooper.idleMainLooper();
        SwitchPreference parent = (SwitchPreference) visible(Settings.START_ON_FOLLOWING.key);
        SwitchPreference child = (SwitchPreference) visible(Settings.ONLY_FOLLOWING.key);
        page.searchSettings("analytics");
        Settings.START_ON_FOLLOWING.save(false);
        ShadowLooper.idleMainLooper();
        assertSame(parent, page.findPreference(new StringBuilder(Settings.START_ON_FOLLOWING.key)));
        assertNull(visible(Settings.START_ON_FOLLOWING.key));
        page.searchSettings("");
        assertSame(parent, visible(Settings.START_ON_FOLLOWING.key));
        assertFalse(parent.isChecked());
        assertSame(child, visible(Settings.ONLY_FOLLOWING.key));
        assertFalse(child.isEnabled());
        assertTrue(child.isChecked());
        assertTrue(Settings.ONLY_FOLLOWING.savedValue());
    }

    @Test public void noMatchIsLocalizedWhilePauseAndRecoveryStayReachable() throws Exception {
        open(PatchFamily.DISABLE_ANALYTICS);
        page.searchSettings("zzzz-no-match");
        assertNotNull(visible(BaseSettings.PAUSED.key));
        assertNotNull(visible(BaseSettings.DEBUG.key));
        assertTrue(rows(page.getPreferenceScreen()).stream()
                .anyMatch(row -> L10n.t("Export diagnostic report").equals(row.getTitle())));
        assertEquals(L10n.t("No matching settings"), visible("hushgram_settings_search_empty").getTitle());
        page.searchSettings("null");
        assertNotNull(visible("hushgram_settings_search_empty"));
        page.searchSettings(" \t ");
        assertNull(visible("hushgram_settings_search_empty"));
        assertNotNull(visible(Settings.DISABLE_ANALYTICS.key));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void typingAndClearUseTheInlineAccessibleControls() throws Exception {
        open(PatchFamily.DISABLE_ANALYTICS, PatchFamily.FOLLOWING_FEED);
        controller.visible();
        View root = page.getView();
        float density = root.getResources().getDisplayMetrics().density;
        root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(320 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(640 * density), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        EditText input = root.findViewWithTag("hushgram-settings-search");
        assertNotNull(input);
        View row = (View) input.getParent();
        assertTrue("typing must use the current, attached native row", row.isAttachedToWindow());
        assertEquals(L10n.t("Search settings"), input.getContentDescription());
        input.setText("contacts");
        assertNotNull(visible(Settings.DISABLE_ANALYTICS.key));
        assertNull(visible(Settings.START_ON_FOLLOWING.key));
        Button clear = (Button) ((android.view.ViewGroup) row).getChildAt(1);
        assertEquals(L10n.t("Clear search"), clear.getContentDescription());
        int touch = Math.round(48 * row.getResources().getDisplayMetrics().density);
        assertTrue(clear.getMinimumWidth() >= touch);
        assertTrue(clear.getMinimumHeight() >= touch);
        assertTrue(clear.performClick());
        assertEquals("", input.getText().toString());
        assertFalse(clear.isEnabled());
        assertNotNull(visible(Settings.START_ON_FOLLOWING.key));
    }

    @Test public void frameworkStateDoesNotRetainTheSearchQuery() throws Exception {
        open(PatchFamily.DISABLE_ANALYTICS, PatchFamily.FOLLOWING_FEED);
        page.searchSettings("location setup");
        android.os.Bundle state = new android.os.Bundle();
        page.onSaveInstanceState(state);
        assertFalse(state.containsKey("hushgram_settings_query"));
        Fragment.SavedState saved = controller.get().getFragmentManager().saveFragmentInstanceState(page);
        controller.get().getFragmentManager().beginTransaction().remove(page).commitNow();
        page = new HushgramPreferenceFragment();
        page.setInitialSavedState(saved);
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        assertNotNull(visible(Settings.DISABLE_ANALYTICS.key));
        assertNotNull(visible(Settings.START_ON_FOLLOWING.key));
        EditText input = render(visible("hushgram_settings_search")).findViewWithTag("hushgram-settings-search");
        assertEquals("", input.getText().toString());
        assertFalse("Android must not serialize the typing field", input.isSaveEnabled());
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theLiveInlineControlsFitAndMirrorAtTwiceTheTextSize() throws Exception {
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        RuntimeEnvironment.setFontScale(2f);
        open(PatchFamily.DISABLE_ANALYTICS, PatchFamily.FOLLOWING_FEED);
        View root = page.getView();
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density);
        int height = Math.round(640 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        EditText input = root.findViewWithTag("hushgram-settings-search");
        assertNotNull(input);
        new android.app.Instrumentation().setInTouchMode(true);
        assertTrue(input.requestFocus());
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertTrue("typing focus was lost before filtering: " + root.findFocus(), input.hasFocus());
        input.setText("contacts");
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertSame("filtering replaced the focused input", input,
                root.findViewWithTag("hushgram-settings-search"));
        assertTrue("filtering lost typing focus", input.hasFocus());
        Button clear = (Button) ((android.view.ViewGroup) input.getParent()).getChildAt(1);
        assertTrue(input.getWidth() >= Math.round(120 * density));
        assertTrue(clear.getWidth() >= Math.round(48 * density));
        assertTrue(clear.getHeight() >= Math.round(48 * density));
        assertTrue(clear.getLeft() < input.getLeft());
        assertNotNull(visible(Settings.DISABLE_ANALYTICS.key));
        assertNull(visible(Settings.START_ON_FOLLOWING.key));
        capture(root, "filtered");
        assertTrue(clear.performClick());
        ShadowLooper.idleMainLooper();
        assertNotNull(visible(Settings.START_ON_FOLLOWING.key));
        assertEquals("", input.getText().toString());
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        capture(root, "cleared");
        input.setText("zzzz-no-match");
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertNotNull(visible("hushgram_settings_search_empty"));
        assertNotNull(visible(BaseSettings.PAUSED.key));
        capture(root, "empty");
    }

    private void capture(View root, String state) throws Exception {
        Bitmap pixels = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(pixels));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, png));
        assertTrue("the framework rendering produced an empty image", png.size() > 1000);
        String folder = System.getenv("HUSHGRAM_UI_DIR");
        if (folder != null) {
            File directory = new File(folder);
            assertTrue(directory.mkdirs() || directory.isDirectory());
            try (FileOutputStream out = new FileOutputStream(new File(directory,
                    "search-api" + android.os.Build.VERSION.SDK_INT + "-" + state + ".png"))) {
                png.writeTo(out);
            }
        }
    }

    @Test public void aCarouselKeepsItsCancelIdentityAcrossFilteredPageChanges() throws Exception {
        open(PatchFamily.VIDEO_DOWNLOAD);
        page.searchSettings("zzzz-no-match");
        int id = SavesForTests.beginCarousel(RuntimeEnvironment.getApplication(), 3);
        try {
            ShadowLooper.idleMainLooper();
            Preference running = visible("running_save_" + id);
            assertEquals("Saving a carousel", running.getTitle());
            assertTrue(running.getSummary().toString().contains("Page 1 of 3"));
            View row = render(running);
            Button cancel = (Button) ((android.view.ViewGroup) row.findViewById(android.R.id.widget_frame)).getChildAt(0);
            assertEquals("Cancel saving this carousel", cancel.getContentDescription());
            SavesForTests.page(id, 2, true);
            SavesForTests.joining(id);
            ShadowLooper.idleMainLooper();
            assertSame(running, visible("running_save_" + id));
            assertTrue(running.getSummary().toString().contains("Page 2 of 3"));
            assertSame(cancel, ((android.view.ViewGroup) row.findViewById(android.R.id.widget_frame)).getChildAt(0));
            assertTrue(cancel.performClick());
            assertTrue(SavesForTests.cancelled(id));
        } finally {
            SavesForTests.end(id);
        }
    }

    @Test public void aCompleteCarouselOutcomeSurvivesFilteringAndReopeningSettings() throws Exception {
        open(PatchFamily.VIDEO_DOWNLOAD);
        page.searchSettings("zzzz-no-match");
        int id = SavesForTests.beginCarousel(RuntimeEnvironment.getApplication(), 3);
        SavesForTests.finishCarousel(id, 1, 1, 1, 0, false);
        ShadowLooper.idleMainLooper();
        Preference result = visible("hushgram_last_carousel_save");
        assertNotNull(result);
        assertEquals("Last carousel save", result.getTitle());
        assertEquals("Saved 1. Failed 1. Skipped 1.", result.getSummary());
        page.searchSettings("");
        assertSame(result, visible("hushgram_last_carousel_save"));
        controller.get().getFragmentManager().beginTransaction().remove(page).commitNow();
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        assertEquals("Saved 1. Failed 1. Skipped 1.", visible("hushgram_last_carousel_save").getSummary());
        int next = SavesForTests.beginCarousel(RuntimeEnvironment.getApplication(), 2);
        try {
            ShadowLooper.idleMainLooper();
            assertNull(visible("hushgram_last_carousel_save"));
        } finally {
            SavesForTests.end(next);
        }
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aCancelledCarouselShowsEveryCountAndQualityWarningAtLargeText() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        open(PatchFamily.VIDEO_DOWNLOAD);
        int id = SavesForTests.beginCarousel(RuntimeEnvironment.getApplication(), 32);
        SavesForTests.finishCarousel(id, 3, 2, 27, 2, true);
        ShadowLooper.idleMainLooper();
        Preference result = visible("hushgram_last_carousel_save");
        assertNotNull(result);
        assertEquals(SaveControl.batchOutcome(), result.getSummary());
        View row = render(result);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int width = Math.round(320 * row.getResources().getDisplayMetrics().density);
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());
        assertEquals(View.LAYOUT_DIRECTION_RTL, row.getLayoutDirection());
        android.widget.TextView summary = row.findViewById(android.R.id.summary);
        assertEquals(result.getSummary(), summary.getText());
        assertTrue(summary.getLineCount() > 2);
        capture(row, "carousel-result");
        int room = summary.getWidth() - summary.getTotalPaddingLeft() - summary.getTotalPaddingRight();
        for (int line = 0; line < summary.getLineCount(); line++) {
            assertEquals(0, summary.getLayout().getEllipsisCount(line));
            // Android 9 Layout.getLineWidth includes the trailing space consumed by wrapping.
            // getLineMax measures visible text, so invisible whitespace isn't mistaken for clipping.
            assertTrue("line " + line + " visible width " + summary.getLayout().getLineMax(line) + " in " + room,
                    summary.getLayout().getLineMax(line) <= room + 0.5f);
        }
        assertEquals(summary.getText().length(), summary.getLayout().getLineEnd(summary.getLineCount() - 1));
        assertTrue("the full outcome is clipped", summary.getLayout().getHeight() <= summary.getHeight());
    }

    @Test public void aSaveStartedDuringFilteringKeepsOneWorkingCancelRow() throws Exception {
        open(PatchFamily.REEL_DOWNLOAD);
        page.searchSettings("zzzz-no-match");
        int id = SavesForTests.begin(RuntimeEnvironment.getApplication(), true);
        try {
            ShadowLooper.idleMainLooper();
            Preference running = visible("running_save_" + id);
            assertNotNull(running);
            for (int i = 0; i < 4; i++) {
                page.searchSettings("zzzz-no-match-" + i);
                assertSame(running, visible("running_save_" + id));
                assertEquals(1, rows(page.getPreferenceScreen()).stream()
                        .filter(row -> ("running_save_" + id).equals(row.getKey())).count());
            }
            View row = render(running);
            android.view.ViewGroup buttons = row.findViewById(android.R.id.widget_frame);
            assertTrue(((Button) buttons.getChildAt(0)).performClick());
            assertTrue(SavesForTests.cancelled(id));
        } finally {
            SavesForTests.end(id);
            ShadowLooper.idleMainLooper();
        }
        assertTrue(SaveControl.running().isEmpty());
        assertTrue(rows(page.getPreferenceScreen()).stream()
                .noneMatch(row -> row.getKey() != null && row.getKey().startsWith("running_save_")));
    }
}
