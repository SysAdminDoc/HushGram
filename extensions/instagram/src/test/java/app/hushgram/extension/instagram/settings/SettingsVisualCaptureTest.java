/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;
import app.hushgram.extension.instagram.direct.PinLock;
import app.hushgram.extension.instagram.download.MediaSave;

/** Renders the production dialog and every installed category with Android's native graphics. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi", shadows = SettingsVisualCaptureTest.CaptureMetadata.class)
@SuppressWarnings("deprecation")
public class SettingsVisualCaptureTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;
    private HushgramPreferenceFragment page;
    private String scale = "normal";

    /** Deterministic development preview metadata. These fixtures never represent an installed build. */
    @Implements(value = Utils.class, isInAndroidSdk = false)
    public static class CaptureMetadata {
        @Implementation public static String getPatchesReleaseVersion() { return "0.0.18"; }
        @Implementation public static String getAppVersionName() { return "453.0.0.57.79"; }
        @Implementation public static long getAppVersionCode() { return 385914557L; }
        @Implementation public static String getSourceBuildIdentity() { return "Development preview"; }
    }

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        RuntimeEnvironment.setFontScale(1f);
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        Settings.CATEGORY_PAGES.save(true);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        PauseForTests.resume();
    }

    @After public void close() throws Exception {
        if (dialog != null) dialog.dismissAllowingStateLoss();
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Settings.DATE_SETTINGS_EXPORT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        RuntimeEnvironment.setFontScale(1f);
        SettingsEntry.onClosedByUser();
        SettingsDialogBoundaryTest.BrokenPage.constructorFailures = 0;
        SettingsDialogBoundaryTest.BrokenPage.viewFailures = 0;
    }

    @Test public void compactOverviewAndItsMainInteractionsRender() throws Exception {
        scale = "compact-normal";
        open();
        PreferenceGroup screen = page.getPreferenceScreen();
        assertEquals("hushgram_settings_search", screen.getPreference(0).getKey());
        assertEquals("hushgram_view_toggle", screen.getPreference(1).getKey());
        assertNull("the active banner must not occupy a row", screen.findPreference("hushgram_status"));
        capturePage("00-overview", false);
        ListView list = page.getView().findViewById(android.R.id.list);
        int visibleCategories = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            Object item = list.getItemAtPosition(list.getFirstVisiblePosition() + i);
            if (!(item instanceof Preference)) continue;
            String key = ((Preference) item).getKey();
            if (key != null && key.startsWith(HushgramPreferenceFragment.CATEGORY_ROW_KEY)
                    && row.getTop() >= 0 && row.getBottom() <= list.getHeight()) {
                visibleCategories++;
                assertTrue("compact rows remain tappable", row.getHeight() >= 48 * row.getResources().getDisplayMetrics().density);
            }
        }
        assertTrue("the overview should expose at least eight complete categories: " + visibleCategories, visibleCategories >= 8);
        Preference feed = titled(screen, "Feed");
        clickInList(feed);
        assertNotNull(page.findPreference(Settings.START_ON_FOLLOWING.key));
        capturePage("01-feed", false);
        list.setSelectionFromTop(0, 0);
        settle();
        EditText input = page.getView().findViewWithTag("hushgram-settings-search");
        input.setText("Hide ads");
        settle();
        Preference ads = page.findPreference(Settings.HIDE_ADS.key);
        assertNotNull("typing searches across categories", ads);
        boolean wasHidden = Settings.HIDE_ADS.savedValue();
        clickInList(ads);
        assertEquals(!wasHidden, Settings.HIDE_ADS.savedValue());
        capturePage("02-search", false);
        clickInList(ads);
        list.setSelectionFromTop(0, 0);
        settle();
        input = page.getView().findViewWithTag("hushgram-settings-search");
        input.setText("");
        settle();
        tap(feed);
        settle();
        clickInList(page.getPreferenceScreen().findPreference("hushgram_view_toggle"));
        assertFalse(Settings.CATEGORY_PAGES.savedValue());
        assertEquals("Show settings by category", page.getPreferenceScreen().getPreference(1).getTitle());
        clickInList(page.getPreferenceScreen().findPreference("hushgram_view_toggle"));
        assertTrue(Settings.CATEGORY_PAGES.savedValue());
        capturePage("03-restored-overview", false);
    }

    @Test public void compactOverviewAndCategoryRenderAtTwiceTheTextSize() throws Exception {
        scale = "compact-extra-large";
        RuntimeEnvironment.setFontScale(2f);
        open();
        capturePage("00-overview", false);
        openCategory("Feed");
        capturePage("01-feed", false);
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h891dp-xhdpi")
    public void compactOverviewAndCategoryRenderRightToLeft() throws Exception {
        scale = "compact-rtl";
        RuntimeEnvironment.setFontScale(1.3f);
        open();
        dialog.getView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        capturePage("00-overview", false);
        openCategory("Feed");
        capturePage("01-feed", false);
    }

    @Test public void everyCategoryAndItsScrolledContentRender() throws Exception {
        open();
        capturePage("00-overview", true);
        List<Preference> categories = categories();
        assertEquals("Every category, Pause and diagnostics included, can expand in place", 23, categories.size());
        int number = 1;
        for (Preference category : categories) {
            tap(category);
            ListView list = page.getView().findViewById(android.R.id.list);
            assertTrue(((HushgramPreferenceFragment.Heading) category).expanded);
            assertNotNull("Search remains on the same page", page.getPreferenceScreen().findPreference("hushgram_settings_search"));
            capturePage(String.format(Locale.ROOT, "%02d-%s", number++, slug(category.getTitle())), true);
            tap(category);
            settle();
        }
        Settings.CATEGORY_PAGES.save(false);
        page.searchSettings("");
        settle();
        capturePage("24-all-settings", false);
        page.searchSettings("Hide ads");
        settle();
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        capturePage("25-search-results", false);
        page.searchSettings("zzzz-no-settings-match");
        settle();
        assertNotNull(page.getPreferenceScreen().findPreference("hushgram_settings_search_empty"));
        capturePage("26-search-empty", false);
    }

    @Test public void everyPageKeepsItsTitlesAndControlsAtLargerText() throws Exception {
        captureLargePages(1.3f, "large");
    }

    @Test public void everyPageKeepsItsTitlesAndControlsAtTwiceTheTextSize() throws Exception {
        captureLargePages(2f, "extra-large");
    }

    private void captureLargePages(float fontScale, String output) throws Exception {
        scale = output;
        RuntimeEnvironment.setFontScale(fontScale);
        open();
        capturePage("00-overview", false);
        int number = 1;
        for (Preference category : categories()) {
            tap(category);
            capturePage(String.format(Locale.ROOT, "%02d-%s", number++, slug(category.getTitle())), false);
            tap(category);
            settle();
        }
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h891dp-xhdpi")
    public void mirroredLargerTextKeepsTheBackControlAndTitlesVisible() throws Exception {
        scale = "rtl-large";
        RuntimeEnvironment.setFontScale(1.3f);
        open();
        dialog.getView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        capturePage("00-overview", false);
        int number = 1;
        for (Preference category : categories()) {
            tap(category);
            capturePage(String.format(Locale.ROOT, "%02d-%s", number++, slug(category.getTitle())), false);
            tap(category);
            settle();
        }
    }

    @Test public void shortcodeNameEditorRendersAtTwiceTheTextSize() throws Exception {
        scale = "shortcode-extra-large";
        RuntimeEnvironment.setFontScale(2f);
        open();
        openCategory("Downloads");
        HushgramPreferenceFragment.FileNameRow row = (HushgramPreferenceFragment.FileNameRow)
                page.findPreference(Settings.FILENAME_TEMPLATE.key);
        row.setText("{shortcode}");
        clickInList(row);
        captureAlert("file-name-shortcode");
    }

    @Test public void datedBackupOptionRendersAtTwiceTheTextSize() throws Exception {
        scale = "backup-date-extra-large";
        RuntimeEnvironment.setFontScale(2f);
        open();
        openCategory("Settings backup");
        Preference option = page.findPreference(Settings.DATE_SETTINGS_EXPORT.key);
        assertNotNull(option);
        clickInList(option);
        assertTrue(Settings.DATE_SETTINGS_EXPORT.savedValue());
        capturePage("backup-date-on", true);
    }

    @Test public void realChoiceInputGhostPauseAndNoticeStatesRender() throws Exception {
        open();
        openCategory("Downloads");
        clickInList(page.findPreference(Settings.DOWNLOAD_QUALITY.key));
        captureAlert("27-download-quality");
        clickInList(page.findPreference(Settings.SAVE_FOLDER.key));
        captureAlert("28-save-folder");
        clickInList(page.findPreference(Settings.FILENAME_TEMPLATE.key));
        captureAlert("29-file-name");
        tap(titled(page.getPreferenceScreen(), "Downloads"));
        settle();
        openCategory("Ads and privacy");
        SwitchPreference ghost = (SwitchPreference) titled(page.getPreferenceScreen(), "Ghost mode");
        assertNotNull(ghost);
        assertNotNull(ghost.getOnPreferenceChangeListener());
        ghost.getOnPreferenceChangeListener().onPreferenceChange(ghost, true);
        settle();
        assertTrue("The real ghost control must reflect its constituent settings", ghost.isChecked());
        capturePage("30-ghost-on", false);
        ghost.getOnPreferenceChangeListener().onPreferenceChange(ghost, false);
        tap(titled(page.getPreferenceScreen(), "Ads and privacy"));
        settle();
        page.searchSettings("Pause HushGram");
        settle();
        capturePage("31-pause-and-diagnostics", true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        capturePage("32-paused", false);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        page.searchSettings("");
        settle();
        openCategory("About");
        capturePage("36-about", false);
        clickInList(titled(page.getPreferenceScreen(), "Licenses"));
        captureAlert("33-licenses");
    }

    @Test @Config(shadows = {CaptureMetadata.class, SettingsDialogBoundaryTest.BrokenPage.class})
    public void theProductionFailurePageAndRetryRender() throws Exception {
        SettingsDialogBoundaryTest.BrokenPage.constructorFailures = 1;
        openDialog();
        assertNotNull(titledView(dialog.getView(), "Settings couldn't open"));
        View retry = titledView(dialog.getView(), "Retry");
        assertNotNull(retry);
        assertTrue(retry.isEnabled());
        capture(dialog.getView(), "34-settings-recovery");
        assertTrue(retry.performClick());
        settle();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertNotNull("Retry restores the actual preference fragment", page);
        capturePage("35-settings-recovered", false);
    }

    @Test public void runtimeControlsRenderAtNormalAndLargerTextWithoutWeakeningPrivacy() throws Exception {
        captureRuntimeStates(false);
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h891dp-xhdpi")
    public void mirroredRuntimeControlsRenderAtNormalAndLargerTextWithoutWeakeningPrivacy() throws Exception {
        captureRuntimeStates(true);
    }

    @Test public void placeAndSharingDomainEditorsRenderDefaultsAndRejectedSaves() throws Exception {
        captureValidatedEditors(false, 1f, 2f);
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h891dp-xhdpi")
    public void mirroredPlaceAndSharingDomainEditorsRenderRejectedSavesAtTwiceTheTextSize() throws Exception {
        captureValidatedEditors(true, 2f);
    }

    private void captureValidatedEditors(boolean rtl, float... sizes) throws Exception {
        boolean oldSpoof = Settings.SPOOF_LOCATION.savedValue();
        boolean oldSanitize = Settings.SANITIZE_SHARING_LINKS.savedValue();
        String oldPlace = Settings.SPOOF_LOCATION_PLACE.savedValue();
        String oldDomain = Settings.SHARING_DOMAIN.savedValue();
        try {
            Settings.SPOOF_LOCATION.save(true);
            Settings.SANITIZE_SHARING_LINKS.save(true);
            Settings.SPOOF_LOCATION_PLACE.save("");
            Settings.SHARING_DOMAIN.save("");
            RuntimeEnvironment.setFontScale(sizes[0]);
            open();
            openCategory("Ads and privacy");
            HushgramPreferenceFragment.ValidatedEditRow place = (HushgramPreferenceFragment.ValidatedEditRow)
                    page.findPreference(Settings.SPOOF_LOCATION_PLACE.key);
            HushgramPreferenceFragment.ValidatedEditRow domain = (HushgramPreferenceFragment.ValidatedEditRow)
                    page.findPreference(Settings.SHARING_DOMAIN.key);
            assertNotNull(place);
            assertNotNull(domain);
            for (float size : sizes) {
                RuntimeEnvironment.setFontScale(size);
                scale = "validated-editors-" + (rtl ? "rtl-" : "ltr-") + Math.round(size * 100);
                captureValidatedEditor(place, "01-place", "91, 0", rtl);
                captureValidatedEditor(domain, "02-sharing-domain", "example.com/a-post", rtl);
                assertEquals("Rejected saves and Cancel leave the place blank", "", Settings.SPOOF_LOCATION_PLACE.savedValue());
                assertEquals("Rejected saves and Cancel leave the domain blank", "", Settings.SHARING_DOMAIN.savedValue());
            }
        } finally {
            Settings.SPOOF_LOCATION.save(oldSpoof);
            Settings.SANITIZE_SHARING_LINKS.save(oldSanitize);
            Settings.SPOOF_LOCATION_PLACE.save(oldPlace);
            Settings.SHARING_DOMAIN.save(oldDomain);
        }
    }

    private void captureValidatedEditor(HushgramPreferenceFragment.ValidatedEditRow row,
                                        String name, String invalid, boolean rtl) throws Exception {
        row.showDialog(null);
        AlertDialog shown = (AlertDialog) row.getDialog();
        try {
            EditText input = row.getEditText();
            assertEquals("", input.getText().toString());
            assertNull(input.getError());
            captureRuntimeDialog(shown, name + "-default", false, rtl);
            input.setText(invalid);
            shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertTrue("The actual rejected Save keeps the editor open", shown.isShowing());
            assertEquals(invalid, input.getText().toString());
            assertNotNull("The native field exposes its validation error", input.getError());
            captureRuntimeDialog(shown, name + "-invalid-save", false, rtl);
            // Android draws an EditText error bubble in a separate native window. Export that
            // actual window too when Robolectric exposes it, without substituting custom UI.
            android.widget.PopupWindow popup = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
                    .getLatestPopupWindow();
            if (popup != null && popup.isShowing()) {
                View popupRoot = popup.getContentView().getRootView();
                if (titledView(popupRoot, input.getError().toString()) != null) {
                    layRuntime(popupRoot, true, rtl);
                    capture(popupRoot, name + "-invalid-error-popup");
                }
            }
            shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertFalse("Cancel closes the actual editor", shown.isShowing());
            assertEquals("Cancel leaves the preference value blank", "", row.getText());
        } finally {
            shown.dismiss();
        }
    }

    /** A constrained layout proves scrolling, but doesn't claim an Android keyboard was opened. */
    @Test @Config(sdk = 37, qualifiers = "en-rUS-w891dp-h411dp-land-xhdpi")
    public void landscapePinFormsAtTwiceTheTextSizeKeepEveryInputAndActionReachable() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        scale = "runtime-landscape-200-constrained-300dp";
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity host = controller.get();
        String oldPin = Settings.LOCK_PIN.savedValue();
        long oldMisses = Settings.LOCK_PIN_MISSES.savedValue();
        long oldWait = Settings.LOCK_PIN_WAIT_UNTIL.savedValue();
        AlertDialog pin = null, setup = null, recovery = null;
        try {
            assertTrue("Only the isolated capture fixture gets a PIN", PinLock.set("4821"));
            invoke(PinLock.class, null, "ask", new Class<?>[]{Activity.class, Runnable.class, Runnable.class},
                    host, (Runnable) () -> fail("Capturing must never unlock"), (Runnable) () -> {});
            pin = ShadowAlertDialog.getLatestAlertDialog();
            captureConstrainedPin(pin, "01-pin-entry");
            for (int miss = 0; miss < 5; miss++) {
                ((EditText) pin.getWindow().getDecorView().findViewWithTag("hushgram_pin_field")).setText("0000");
                pin.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                ShadowLooper.idleMainLooper();
            }
            assertFalse("The real attempt throttle disables Unlock", pin.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            captureConstrainedPin(pin, "04-pin-lockout");
            pin.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            ShadowLooper.idleMainLooper();
            recovery = ShadowAlertDialog.getLatestAlertDialog();
            assertNotSame(pin, recovery);
            captureConstrainedPin(recovery, "05-pin-recovery");
            TextView explanation = recovery.findViewById(android.R.id.message);
            ScrollView recoveryScroll = parentScroll(explanation);
            assertNotNull("The full recovery explanation can scroll", recoveryScroll);
            recoveryScroll.setSmoothScrollingEnabled(false);
            recoveryScroll.fullScroll(View.FOCUS_DOWN);
            capture(recovery.getWindow().getDecorView(), "05-pin-recovery-bottom");
            recovery.dismiss();
            pin.dismiss();
            ShadowLooper.idleMainLooper();

            PinLock.promptNew(host, saved -> assertFalse("Capturing must never replace the fixture PIN", saved));
            setup = ShadowAlertDialog.getLatestAlertDialog();
            captureConstrainedPin(setup, "02-pin-setup");
            ((EditText) setup.getWindow().getDecorView().findViewWithTag("hushgram_pin_field")).setText("5555");
            ((EditText) setup.getWindow().getDecorView().findViewWithTag("hushgram_pin_confirm")).setText("5556");
            setup.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            TextView error = setup.getWindow().getDecorView().findViewWithTag("hushgram_pin_message");
            assertEquals("The PINs don't match.", error.getText().toString());
            assertTrue("The failed setup leaves the existing PIN", PinLock.matches("4821"));
            captureConstrainedPin(setup, "03-pin-mismatch");
        } finally {
            if (recovery != null) recovery.dismiss();
            if (pin != null) pin.dismiss();
            if (setup != null) setup.dismiss();
            ShadowLooper.idleMainLooper();
            Settings.LOCK_PIN.save(oldPin);
            Settings.LOCK_PIN_MISSES.save(oldMisses);
            Settings.LOCK_PIN_WAIT_UNTIL.save(oldWait);
        }
    }

    @Test @Config(sdk = 37, qualifiers = "en-rUS-w891dp-h411dp-land-xhdpi")
    public void landscapeProfileViewerUsesSafeInsetsOverLightAndDarkHosts() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        for (boolean light : new boolean[]{true, false}) {
            scale = "runtime-viewer-" + (light ? "light" : "dark") + "-landscape-200";
            controller = Robolectric.buildActivity(Activity.class);
            Activity host = controller.get();
            host.setTheme(light ? android.R.style.Theme_Material_Light : android.R.style.Theme_Material);
            controller.setup().visible();
            host.getWindow().getDecorView().setSystemUiVisibility(light
                    ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);
            List<MediaSave.Rendition> sizes = Collections.singletonList(
                    new MediaSave.Rendition("https://example.invalid/profile.jpg", 512, 512, 0));
            Class<?> saver = Class.forName("app.hushgram.extension.instagram.download.ProfilePicture$Save");
            Object noSave = Proxy.newProxyInstance(saver.getClassLoader(), new Class<?>[]{saver},
                    (proxy, method, arguments) -> { throw new AssertionError("Capturing must never save media"); });
            Object viewer = construct("app.hushgram.extension.instagram.download.ProfilePictureViewer$Screen",
                    new Class<?>[]{Activity.class, List.class, String.class, saver}, host, sizes, "sample.account", noSave);
            Dialog shown = (Dialog) field(viewer, "dialog");
            Bitmap sample = null;
            try {
                shown.show();
                ViewGroup decor = (ViewGroup) shown.getWindow().getDecorView();
                ViewGroup content = decor.findViewById(android.R.id.content);
                ViewGroup root = (ViewGroup) content.getChildAt(0);
                laySizedRuntime(decor, 891, 411, false);
                assertEquals("The black viewer has readable system icons over either host theme", 0,
                        decor.getSystemUiVisibility()
                                & (View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
                TextView save = (TextView) field(viewer, "save");
                TextView close = (TextView) field(viewer, "close");
                assertFalse(save.isEnabled());
                dispatchViewerInsets(root, false);
                laySizedRuntime(decor, 891, 411, false);
                assertViewerSafe(decor, root, save, close);
                capture(decor, "01-navigation-loading-save-disabled");

                sample = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                sample.eraseColor(0xFF312735);
                Canvas canvas = new Canvas(sample);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(0xFFAAA0AE);
                canvas.drawCircle(256, 190, 80, paint);
                canvas.drawRoundRect(100, 300, 412, 560, 120, 120, paint);
                Class<?> picture = Class.forName("app.hushgram.extension.instagram.download.ProfilePictureViewer$Picture");
                Object loaded = construct(picture.getName(), new Class<?>[]{Bitmap.class, int.class}, sample, 512);
                invoke(viewer.getClass(), viewer, "shown", new Class<?>[]{picture}, loaded);
                assertTrue(save.isEnabled());
                laySizedRuntime(decor, 891, 411, false);
                capture(decor, "02-navigation-loaded");
                boolean touchMode = save.isInTouchMode();
                Object viewRoot = invoke(View.class, decor, "getViewRootImpl", new Class<?>[0]);
                assertNotNull("Keyboard focus requires the attached viewer window", viewRoot);
                // The mocked window manager doesn't deliver touch-mode changes to attached dialogs.
                // Use the same local transition ViewRootImpl performs when a keyboard is used.
                invoke(viewRoot.getClass(), viewRoot, "ensureTouchMode", new Class<?>[]{boolean.class}, false);
                try {
                    assertFalse("The attached viewer must leave touch mode before keyboard navigation", save.isInTouchMode());
                    assertTrue("The enabled native Save button remains focusable", save.isFocusable());
                    assertTrue("The production Save action accepts keyboard focus", save.requestFocus());
                    assertTrue(save.isFocused());
                    save.refreshDrawableState();
                    capture(decor, "03-navigation-save-focused");
                } finally {
                    save.clearFocus();
                    invoke(viewRoot.getClass(), viewRoot, "ensureTouchMode", new Class<?>[]{boolean.class}, touchMode);
                }
                save.drawableHotspotChanged(save.getWidth() / 2f, save.getHeight() / 2f);
                save.setPressed(true);
                // Start the native ripple with a frame, then capture during its expansion.
                Bitmap pressedFrame = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                try { decor.draw(new Canvas(pressedFrame)); }
                finally { pressedFrame.recycle(); }
                ShadowLooper.idleMainLooper(80, TimeUnit.MILLISECONDS);
                capture(decor, "04-navigation-save-pressed");
                save.setPressed(false);

                dispatchViewerInsets(root, true);
                laySizedRuntime(decor, 891, 411, false);
                assertViewerSafe(decor, root, save, close);
                assertEquals("The dispatched IME inset reaches the real viewer root", dp(root, 160), root.getPaddingBottom());
                capture(decor, "05-dispatched-ime-inset");
                dispatchViewerInsets(root, false);
                laySizedRuntime(decor, 891, 411, false);
                assertEquals("Restoring navigation insets doesn't retain keyboard padding", dp(root, 24), root.getPaddingBottom());
                assertViewerSafe(decor, root, save, close);
                capture(decor, "06-navigation-restored");
            } finally {
                shown.dismiss();
                if (sample != null) sample.recycle();
                controller.close();
                controller = null;
            }
        }
    }

    private void captureConstrainedPin(AlertDialog shown, String name) throws Exception {
        assertNotNull(shown);
        assertTrue(shown.isShowing());
        assertTrue("Capturing never weakens the real PIN window", (shown.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                shown.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        ViewGroup root = (ViewGroup) shown.getWindow().getDecorView();
        laySizedRuntime(root, 560, 300, true);
        assertTrue("The constrained capture stays within its stated viewport", root.getHeight() <= dp(root, 300));
        assertTitlesAndControls(root);
        capture(root, name);
        List<View> controls = new ArrayList<>();
        for (String tag : new String[]{"hushgram_pin_field", "hushgram_pin_confirm"}) {
            View input = root.findViewWithTag(tag);
            if (input != null) controls.add(input);
        }
        TextView feedback = root.findViewWithTag("hushgram_pin_message");
        if (feedback != null && feedback.getVisibility() == View.VISIBLE && feedback.length() > 0) controls.add(feedback);
        collectButtons(root, controls);
        List<View> fixedActions = new ArrayList<>();
        for (View control : controls) {
            ScrollView scroll = parentScroll(control);
            if (scroll == null) fixedActions.add(control);
            else {
                scroll.setSmoothScrollingEnabled(false);
                control.requestRectangleOnScreen(new Rect(0, 0, control.getWidth(), control.getHeight()), true);
            }
            Rect visible = new Rect();
            assertTrue("The control is reachable: " + control.getTag(), control.getGlobalVisibleRect(visible));
            assertEquals("The complete control height is reachable", control.getHeight(), visible.height());
            assertEquals("The complete control width is reachable", control.getWidth(), visible.width());
            String label = control instanceof EditText || control == feedback ? String.valueOf(control.getTag())
                    : ((TextView) control).getText().toString();
            capture(root, name + "-" + slug(label));
        }
        assertActionBounds(root, fixedActions);
    }

    private static void dispatchViewerInsets(ViewGroup root, boolean keyboard) {
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, dp(root, 24), dp(root, 48), 0))
                .setInsets(WindowInsets.Type.systemGestures(), Insets.of(dp(root, 12), 0, dp(root, 12), dp(root, 24)))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard ? dp(root, 160) : 0))
                .setVisible(WindowInsets.Type.ime(), keyboard).build());
    }

    private void assertViewerSafe(ViewGroup decor, ViewGroup root, TextView save, TextView close) {
        assertTitlesAndControls(decor);
        assertActionBounds(decor);
        assertEquals(dp(root, 12), root.getPaddingLeft());
        assertEquals(dp(root, 24), root.getPaddingTop());
        assertEquals(dp(root, 48), root.getPaddingRight());
        View bar = (View) save.getParent();
        assertTrue("The toolbar ends above the dispatched bottom inset", bar.getBottom() <= root.getHeight() - root.getPaddingBottom());
        for (View action : new View[]{save, close}) {
            Rect visible = new Rect();
            assertTrue(action.getGlobalVisibleRect(visible));
            assertEquals("Viewer actions remain fully visible", action.getHeight(), visible.height());
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static void laySizedRuntime(View root, int widthDp, int heightDp, boolean floating) {
        ShadowLooper.idleMainLooper();
        root.measure(View.MeasureSpec.makeMeasureSpec(dp(root, widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(root, heightDp), floating ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private void captureRuntimeStates(boolean rtl) throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity host = controller.get();
        Settings.POST_DETAILS.save(true);
        Settings.DEFAULT_PLAYBACK_QUALITY.save(true);
        Settings.ASK_BEFORE_CALL.save(true);
        try {
            for (float size : new float[]{1f, 1.3f, 2f}) {
                RuntimeEnvironment.setFontScale(size);
                assertEquals(size, host.getResources().getConfiguration().fontScale, 0.01f);
                scale = (rtl ? "runtime-rtl-" : "runtime-") + Math.round(size * 100);
                Class<?> lock = Class.forName("app.hushgram.extension.instagram.direct.MessagesLock");
                View cover = (View) invoke(lock, null, "buildCover", new Class<?>[]{Activity.class, String.class}, host, "app");
                layRuntime(cover, false, rtl);
                assertTitlesAndControls(cover);
                capture(cover, "01-app-lock");

                invoke(PinLock.class, null, "ask", new Class<?>[]{Activity.class, Runnable.class, Runnable.class},
                        host, (Runnable) () -> fail("Capturing must never unlock"), (Runnable) () -> {});
                AlertDialog pin = ShadowAlertDialog.getLatestAlertDialog();
                captureRuntimeDialog(pin, "02-pin-unlock", true, rtl);
                assertTrue(pin.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
                AlertDialog forgot = ShadowAlertDialog.getLatestAlertDialog();
                assertNotSame(pin, forgot);
                captureRuntimeDialog(forgot, "03-pin-recovery", true, rtl);
                forgot.dismiss();
                pin.dismiss();
                PinLock.promptNew(host, saved -> assertFalse("Capturing must never save a PIN", saved));
                AlertDialog setup = ShadowAlertDialog.getLatestAlertDialog();
                captureRuntimeDialog(setup, "04-pin-setup", true, rtl);
                setup.dismiss();

                List<MediaSave.Rendition> sizes = Collections.singletonList(
                        new MediaSave.Rendition("https://example.invalid/profile.jpg", 512, 512, 0));
                Class<?> saver = Class.forName("app.hushgram.extension.instagram.download.ProfilePicture$Save");
                Object noSave = Proxy.newProxyInstance(saver.getClassLoader(), new Class<?>[]{saver},
                        (proxy, method, arguments) -> { throw new AssertionError("Capturing must never save media"); });
                Object viewer = construct("app.hushgram.extension.instagram.download.ProfilePictureViewer$Screen",
                        new Class<?>[]{Activity.class, List.class, String.class, saver}, host, sizes, "sample.account", noSave);
                Dialog pictureDialog = (Dialog) field(viewer, "dialog");
                pictureDialog.show();
                View pictureRoot = pictureDialog.getWindow().getDecorView();
                layRuntime(pictureRoot, false, rtl);
                ProgressBar spinner = (ProgressBar) field(viewer, "spinner");
                assertEquals("The real loading indicator is visible", View.VISIBLE, spinner.getVisibility());
                assertTrue("The loading indicator has native dimensions", spinner.getWidth() > 0 && spinner.getHeight() > 0);
                assertNotNull(spinner.getIndeterminateDrawable());
                Bitmap frame = Bitmap.createBitmap(pictureRoot.getWidth(), pictureRoot.getHeight(), Bitmap.Config.ARGB_8888);
                try { pictureRoot.draw(new Canvas(frame)); }
                finally { frame.recycle(); }
                ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
                layRuntime(pictureRoot, false, rtl);
                assertTitlesAndControls(pictureRoot);
                assertActionBounds((ViewGroup) pictureRoot);
                capture(pictureRoot, "05-profile-loading");
                Bitmap sample = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                sample.eraseColor(0xFF312735);
                Canvas canvas = new Canvas(sample);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(0xFFAAA0AE);
                canvas.drawCircle(256, 190, 80, paint);
                canvas.drawRoundRect(100, 300, 412, 560, 120, 120, paint);
                Class<?> pictureType = Class.forName("app.hushgram.extension.instagram.download.ProfilePictureViewer$Picture");
                Object loaded = construct(pictureType.getName(), new Class<?>[]{Bitmap.class, int.class}, sample, 512);
                invoke(viewer.getClass(), viewer, "shown", new Class<?>[]{pictureType}, loaded);
                layRuntime(pictureRoot, false, rtl);
                capture(pictureRoot, "06-profile-viewer");
                pictureDialog.dismiss();
                sample.recycle();

                Class<?> rendition = MediaSave.Rendition.class;
                Object facts = construct("app.hushgram.extension.instagram.download.PostInfo$Facts",
                        new Class<?>[]{Long.class, String.class, String.class, int.class, int.class,
                                rendition, int.class, int.class, String.class},
                        1_790_000_000L, "sample.account", "example_media", 1, 3,
                        new MediaSave.Rendition("https://example.invalid/photo.jpg", 1080, 1350, 0), 1080, 1350,
                        "A photo in the afternoon.");
                invoke(Class.forName("app.hushgram.extension.instagram.download.PostInfo"), null, "present",
                        new Class<?>[]{Activity.class, facts.getClass()}, host, facts);
                AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
                captureDetails(details, rtl);
                details.dismiss();

                Class<?> mentionType = Class.forName("app.hushgram.extension.instagram.stories.StoryMentions$Mention");
                Object first = construct(mentionType.getName(), new Class<?>[]{String.class, String.class, String.class},
                        "sample.account", "Example Person", null);
                Object second = construct(mentionType.getName(), new Class<?>[]{String.class, String.class, String.class},
                        "sample.second", "Another Example", null);
                AlertDialog mentions = (AlertDialog) invoke(Class.forName("app.hushgram.extension.instagram.stories.StoryMentions"),
                        null, "list", new Class<?>[]{Context.class, List.class}, host, java.util.Arrays.asList(first, second));
                captureRuntimeDialog(mentions, "08-story-mentions", false, rtl);
                mentions.dismiss();

                assertTrue(QualityPicker.show(host));
                AlertDialog quality = ShadowAlertDialog.getLatestAlertDialog();
                captureRuntimeDialog(quality, "09-playback-quality", false, rtl);
                if (size == 1f) {
                    ListView choices = quality.getListView();
                    assertEquals("Every playback quality is visible initially", 0, choices.getFirstVisiblePosition());
                    assertEquals(choices.getCount() - 1, choices.getLastVisiblePosition());
                    assertTrue("The final playback quality is fully visible",
                            choices.getChildAt(choices.getChildCount() - 1).getBottom()
                                    <= choices.getHeight() - choices.getPaddingBottom());
                }
                assertTrue("The selected quality remains selected", quality.getListView()
                        .isItemChecked(Settings.PLAYBACK_QUALITY.savedValue().ordinal()));
                quality.dismiss();

                AlertDialog confirm = (AlertDialog) invoke(Class.forName("app.hushgram.extension.instagram.feed.ConfirmDialog"),
                        null, "ask", new Class<?>[]{Context.class, String.class, String.class,
                                Runnable.class, Runnable.class, Consumer.class},
                        host, "Like this post?", "Like", (Runnable) () -> fail("Capturing must never like a post"),
                        (Runnable) () -> {}, (Consumer<AlertDialog>) dismissed -> {});
                captureRuntimeDialog(confirm, "10-feed-confirmation", false, rtl);
                confirm.dismiss();

                Class<?> call = Class.forName("app.hushgram.extension.instagram.direct.CallConfirm");
                Class<?> starterType = Class.forName(call.getName() + "$Starter");
                Field access = call.getDeclaredField("access");
                access.setAccessible(true);
                Object original = access.get(null);
                Object testStarter = Proxy.newProxyInstance(starterType.getClassLoader(), new Class<?>[]{starterType},
                        (proxy, method, arguments) -> {
                            if ("context".equals(method.getName())) return host;
                            throw new AssertionError("Capturing must never place a call");
                        });
                try {
                    access.set(null, testStarter);
                    assertEquals(true, invoke(call, null, "hold", new Class<?>[]{Object.class, Object.class,
                            Object.class, Object.class, int.class}, new Object(), null, null, null, 1));
                    AlertDialog question = ShadowAlertDialog.getLatestAlertDialog();
                    captureRuntimeDialog(question, "11-call-confirmation", false, rtl);
                    question.dismiss();
                } finally { access.set(null, original); }
            }
        } finally {
            Settings.POST_DETAILS.resetToDefault();
            Settings.DEFAULT_PLAYBACK_QUALITY.resetToDefault();
            Settings.ASK_BEFORE_CALL.resetToDefault();
        }
    }

    private void captureRuntimeDialog(Dialog shown, String name, boolean secure, boolean rtl) throws Exception {
        assertNotNull("The production dialog must open: " + name, shown);
        assertTrue(shown.isShowing());
        if (secure) assertTrue("PIN windows must stay protected from screenshots",
                (shown.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        View root = shown.getWindow().getDecorView();
        layRuntime(root, true, rtl);
        capture(root, name);
        assertTitlesAndControls(root);
        assertActionBounds((ViewGroup) root);
    }

    private static void assertActionBounds(ViewGroup root) {
        List<View> controls = new ArrayList<>();
        collectButtons(root, controls);
        assertActionBounds(root, controls);
    }

    private void captureDetails(AlertDialog details, boolean rtl) throws Exception {
        ViewGroup root = (ViewGroup) details.getWindow().getDecorView();
        layRuntime(root, true, rtl);
        TextView firstFact = details.findViewById(android.R.id.message);
        assertNotNull("The first real fact retains the framework message ID", firstFact);
        ScrollView scroll = parentScroll(firstFact);
        assertNotNull("Facts stay inside the native dialog scroll", scroll);
        ViewGroup rows = (ViewGroup) firstFact.getParent();
        int facts = 0;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child instanceof TextView && !(child instanceof Button)) {
                assertTrue("Each fact remains selectable", ((TextView) child).isTextSelectable());
                assertSame(scroll, parentScroll(child));
                assertContentInset(root, child);
                facts++;
            }
        }
        assertEquals("All known fixture facts have selectable rows", 5, facts);
        List<View> footer = java.util.Arrays.asList(details.getButton(AlertDialog.BUTTON_POSITIVE),
                details.getButton(AlertDialog.BUTTON_NEGATIVE));
        for (View action : footer) assertNotSame("Footer actions stay outside the scrolling facts", scroll, parentScroll(action));
        capture(root, "07-post-details");
        assertTitlesAndControls(root);
        assertActionBounds(root, footer);
        List<View> copies = new ArrayList<>();
        collectButtons(rows, copies);
        assertEquals("Known username and caption retain their copy actions", 2, copies.size());
        for (View action : copies) {
            assertSame("Copy rows share the facts' native scroll", scroll, parentScroll(action));
            assertContentInset(root, action);
            assertTrue("Copy rows retain their original click listeners", action.isEnabled() && action.hasOnClickListeners());
            action.requestRectangleOnScreen(new android.graphics.Rect(0, 0, action.getWidth(), action.getHeight()), true);
            ShadowLooper.idleMainLooper();
            android.graphics.Rect visible = new android.graphics.Rect();
            assertTrue("Each copy row is reachable by scrolling", action.getGlobalVisibleRect(visible));
            assertEquals("The complete copy row is reachable", action.getHeight(), visible.height());
            assertActionBounds(root, footer);
        }
        if (scroll.getScrollY() > 0) capture(root, "07-post-details-bottom");
    }

    private static void assertContentInset(ViewGroup root, View content) {
        android.graphics.Rect bounds = new android.graphics.Rect(0, 0, content.getWidth(), content.getHeight());
        root.offsetDescendantRectToMyCoords(content, bounds);
        assertTrue("Details text and copy icons stay inside the panel outline",
                bounds.left > 0 && bounds.right < root.getWidth());
    }

    private static ScrollView parentScroll(View view) {
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
        }
        return null;
    }

    private static void assertActionBounds(ViewGroup root, List<View> controls) {
        List<android.graphics.Rect> bounds = new ArrayList<>();
        for (View control : controls) {
            android.graphics.Rect area = new android.graphics.Rect(0, 0, control.getWidth(), control.getHeight());
            root.offsetDescendantRectToMyCoords(control, area);
            assertTrue("An action must stay inside the panel: " + ((TextView) control).getText() + " " + area,
                    area.left >= 0 && area.top >= 0 && area.right <= root.getWidth() && area.bottom <= root.getHeight());
            for (android.graphics.Rect previous : bounds) {
                assertFalse("Runtime actions must not overlap", android.graphics.Rect.intersects(previous, area));
            }
            bounds.add(area);
        }
    }

    private static void collectButtons(View view, List<View> controls) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || view.getHeight() == 0) return;
        if (view instanceof Button) controls.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectButtons(group.getChildAt(i), controls);
        }
    }

    private void layRuntime(View root, boolean floating, boolean rtl) {
        ShadowLooper.idleMainLooper();
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        float density = root.getResources().getDisplayMetrics().density;
        root.measure(View.MeasureSpec.makeMeasureSpec(Math.round((floating ? 379 : 411) * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round((floating ? 819 : 891) * density),
                        floating ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private static Object invoke(Class<?> type, Object owner, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(owner, arguments);
    }

    private static Object construct(String type, Class<?>[] parameters, Object... arguments) throws Exception {
        Constructor<?> constructor = Class.forName(type).getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void open() throws Exception {
        openDialog();
        page = (HushgramPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertNotNull("The production dialog must own its real preference fragment", page);
        assertSame(dialog, page.getParentFragment());
        assertTrue(dialog.getDialog().isShowing());
    }

    private void openDialog() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "visual_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        settle();
    }

    private void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        View root = dialog.getView();
        assertNotNull(root);
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(411 * density), height = Math.round(891 * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        ShadowLooper.idleMainLooper();
    }

    private List<Preference> categories() {
        List<Preference> result = new ArrayList<>();
        PreferenceGroup screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading
                    && ((HushgramPreferenceFragment.Heading) item).collapsible) result.add(item);
        }
        return result;
    }

    private void openCategory(String title) throws Exception {
        for (Preference category : categories()) {
            if (title.contentEquals(category.getTitle())) {
                tap(category);
                return;
            }
        }
        fail("No category " + title);
    }

    private void tap(Preference preference) throws Exception {
        assertTrue(preference.getOnPreferenceClickListener().onPreferenceClick(preference));
        settle();
    }

    private void clickInList(Preference preference) throws Exception {
        assertNotNull(preference);
        ListView list = page.getView().findViewById(android.R.id.list);
        ListAdapter adapter = list.getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i) == preference) {
                list.setSelectionFromTop(i, 0);
                settle();
                View row = adapter.getView(i, null, list);
                assertTrue(list.performItemClick(row, i, adapter.getItemId(i)));
                settle();
                return;
            }
        }
        fail("No live row for " + preference.getTitle());
    }

    private void captureAlert(String name) throws Exception {
        AlertDialog alert = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("Click must open the production Android dialog", alert);
        assertTrue(alert.isShowing());
        View root = alert.getWindow().getDecorView();
        float density = root.getResources().getDisplayMetrics().density;
        root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(379 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(819 * density), View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        capture(root, name);
        alert.dismiss();
        settle();
    }

    private void capturePage(String name, boolean allContent) throws Exception {
        ListView list = page.getView().findViewById(android.R.id.list);
        assertNotNull(list);
        int start = 0, end = list.getCount() - 1;
        Preference expanded = page.getPreferenceScreen().findPreference(page.currentPage());
        if (expanded instanceof HushgramPreferenceFragment.Heading
                && ((HushgramPreferenceFragment.Heading) expanded).collapsible) {
            for (int i = 0; i < list.getCount(); i++) {
                if (list.getItemAtPosition(i) == expanded) {
                    start = i;
                    end = i + ((PreferenceGroup) expanded).getPreferenceCount();
                    break;
                }
            }
        }
        list.setSelectionFromTop(start, 0);
        settle();
        int part = 1, previous = -1;
        do {
            capture(dialog.getView(), name + String.format(Locale.ROOT, "-p%02d", part++));
            assertTitlesAndControls(dialog.getView());
            if (!allContent || list.getLastVisiblePosition() >= end) break;
            int next = Math.max(list.getFirstVisiblePosition() + 1, list.getLastVisiblePosition());
            assertTrue("Scrolling must advance through every row", next > previous);
            previous = next;
            list.setSelectionFromTop(next, 0);
            settle();
        } while (part <= 100);
        assertTrue("A category must have a bounded number of screens", part <= 100);
    }

    private void assertTitlesAndControls(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || view.getHeight() == 0) return;
        if (view instanceof TextView && (view.getId() == android.R.id.title || view instanceof Button
                || view.isAccessibilityHeading())) {
            TextView text = (TextView) view;
            if (text.getText().length() > 0 && text.getLayout() != null) {
                for (int line = 0; line < text.getLineCount(); line++) {
                    assertEquals("Title/control is truncated: " + text.getText(), 0, text.getLayout().getEllipsisCount(line));
                }
                assertTrue("Title/control is cut vertically: " + text.getText(),
                        text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom()
                                >= text.getLayout().getHeight());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertTitlesAndControls(group.getChildAt(i));
        }
    }

    private void capture(View root, String name) throws Exception {
        Bitmap pixels = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(pixels));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, png));
            assertTrue("Native rendering must produce visible content", png.size() > 1000);
            String output = System.getenv("HUSHGRAM_UI_CAPTURES");
            if (output == null || output.isEmpty()) output = System.getProperty("HUSHGRAM_UI_CAPTURES");
            if (output != null && !output.isEmpty()) {
                File directory = new File(output, scale);
                assertTrue(directory.mkdirs() || directory.isDirectory());
                try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) { png.writeTo(stream); }
            }
        } finally { pixels.recycle(); }
    }

    private static Preference titled(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference item = group.getPreference(i);
            if (title.contentEquals(item.getTitle())) return item;
            if (item instanceof PreferenceGroup) {
                Preference child = titled((PreferenceGroup) item, title);
                if (child != null) return child;
            }
        }
        return null;
    }

    private static View titledView(View view, String title) {
        if (view instanceof TextView && title.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = titledView(group.getChildAt(i), title);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String slug(CharSequence title) {
        String value = title.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return value.isEmpty() ? "category" : value;
    }
}
