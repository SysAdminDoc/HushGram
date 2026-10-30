/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Switch;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.robolectric.shadows.ShadowAlertDialog;

import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

import static org.robolectric.Shadows.shadowOf;

/** Service-facing nodes and actions on real, attached framework preference-list rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsRowAccessibilityTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private HushgramPreferenceFragment page;
    private FrameLayout host;
    private final List<AccessibilityEvent> events = new ArrayList<>();

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.HIDE_ADS.save(false);
        Settings.START_ON_FOLLOWING.save(false);
        Settings.ONLY_FOLLOWING.save(true);
        PauseForTests.resume();
        shadowOf((AccessibilityManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.ACCESSIBILITY_SERVICE)).setEnabled(true);
        shadowOf((AccessibilityManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.ACCESSIBILITY_SERVICE)).setTouchExplorationEnabled(true);
    }

    @After public void close() throws Exception {
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        PauseForTests.resume();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Settings.CATEGORY_PAGES.resetToDefault();
        Settings.HIDE_ADS.resetToDefault();
        Settings.START_ON_FOLLOWING.resetToDefault();
        Settings.ONLY_FOLLOWING.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        BaseSettings.PAUSED.resetToDefault();
        RuntimeEnvironment.setFontScale(1f);
        for (AccessibilityEvent event : events) event.recycle();
    }

    private void open(PatchFamily... families) throws Exception {
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        for (PatchFamily family : families) PatchFamily.inBuildForTests.add(family);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        host = new FrameLayout(controller.get());
        host.setId(View.generateViewId());
        host.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public boolean onRequestSendAccessibilityEvent(ViewGroup parent,
                    View child, AccessibilityEvent event) {
                events.add(AccessibilityEvent.obtain(event));
                return super.onRequestSendAccessibilityEvent(parent, child, event);
            }
        });
        controller.get().setContentView(host);
        page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(host.getId(), page, "accessibility-page").commitNow();
        Utils.awaitBackgroundTasksForTests();
        layout();
    }

    private void layout() {
        ShadowLooper.idleMainLooper();
        int width = Math.round(320 * host.getResources().getDisplayMetrics().density);
        int height = Math.round(640 * host.getResources().getDisplayMetrics().density);
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, width, height);
        ShadowLooper.idleMainLooper();
    }

    private View row(Preference preference) {
        ListView list = page.getView().findViewById(android.R.id.list);
        layout();
        int position = -1;
        for (int i = 0; i < list.getCount(); i++) {
            if (list.getItemAtPosition(i) == preference) position = i;
        }
        assertTrue("preference is missing from the actual list", position >= 0);
        View attached = list.getChildAt(position - list.getFirstVisiblePosition());
        if (attached != null) return attached;
        // Selection is keyboard focus, not a touch scroll. The search input can retain focus
        // on API 28 and leave an off-screen selection unlaid out, especially at 200% text.
        for (int step = 0; step < list.getCount() * 2
                && (position < list.getFirstVisiblePosition() || position > list.getLastVisiblePosition()); step++) {
            int direction = position < list.getFirstVisiblePosition() ? -1 : 1;
            list.scrollListBy(direction * Math.max(1, list.getHeight() / 2));
            layout();
        }
        View view = list.getChildAt(position - list.getFirstVisiblePosition());
        assertNotNull("the selected row isn't attached: wanted " + position + ", first "
                + list.getFirstVisiblePosition() + ", children " + list.getChildCount(), view);
        assertSame(preference, list.getItemAtPosition(list.getPositionForView(view)));
        return view;
    }

    private View row(String key) { return row(page.findPreference(key)); }

    private static boolean clickable(AccessibilityNodeInfo node) {
        return node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    private static String readable(View view) {
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        StringBuilder text = new StringBuilder();
        if (node.getText() != null) text.append(node.getText()).append('\n');
        if (node.getContentDescription() != null) text.append(node.getContentDescription()).append('\n');
        node.recycle();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && child.getImportantForAccessibility()
                        != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) text.append(readable(child));
            }
        }
        return text.toString();
    }

    /** TalkBack's Switch role reads this node, rather than collecting its children's text. */
    private static void assertRoleLabel(View view, Preference preference) {
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        CharSequence label = node.getContentDescription();
        assertNotNull("The role node has no label for " + preference.getTitle(), label);
        assertTrue(label.toString().contains(preference.getTitle()));
        assertTrue(label.toString().contains(preference.getSummary()));
        node.recycle();
    }

    @Test public void switchRoleCarriesItsNameAndExplanationInBothStates() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        assertRoleLabel(view, preference);
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        layout();
        assertTrue(row(preference).createAccessibilityNodeInfo().isChecked());
        assertRoleLabel(row(preference), preference);
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h640dp-xhdpi")
    public void disabledRoleCarriesItsLocalizedNameAndReason() throws Exception {
        open(PatchFamily.FOLLOWING_FEED);
        Preference preference = page.findPreference(Settings.ONLY_FOLLOWING.key);
        View view = row(preference);
        assertFalse(preference.isEnabled());
        assertNotEquals("Only accounts you follow", preference.getTitle().toString());
        assertRoleLabel(view, preference);
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
    }

    @Test public void everyCustomButtonRoleCarriesItsNameAndCurrentSummary() throws Exception {
        open(PatchFamily.values());
        for (String key : new String[]{"hushgram_export_configuration", "action_export_diagnostic_report",
                "action_clear_diagnostic_data", Settings.SAVE_FOLDER.key, Settings.FILENAME_TEMPLATE.key,
                Settings.DOWNLOAD_QUALITY.key, Settings.PLAYBACK_QUALITY.key, Settings.STORY_RING_SCALE.key,
                Settings.STORY_RING_COLOR.key, Settings.NAVIGATION_SETTINGS_TARGET.key}) {
            Preference preference = page.findPreference(key);
            assertNotNull(key, preference);
            View view = row(preference);
            assertEquals(key, Button.class.getName(), view.createAccessibilityNodeInfo().getClassName());
            assertRoleLabel(view, preference);
        }
    }

    @Test public void roleLabelsReadCurrentTextBeforeRebindingAndHandleAbsentParts() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        preference.setTitle("Changed action");
        preference.setSummary("Changed explanation");
        assertRoleLabel(view, preference);
        preference.setSummary(null);
        assertEquals("Changed action", view.createAccessibilityNodeInfo().getContentDescription());
        preference.setTitle(null);
        preference.setSummary("Explanation without a title");
        assertEquals("Explanation without a title", view.createAccessibilityNodeInfo().getContentDescription());
    }

    @Test public void offSwitchHasAReadableRoleStateAndOneWorkingClick() throws Exception {
        open(PatchFamily.HIDE_ADS);
        SwitchPreference preference = (SwitchPreference) page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertEquals(Switch.class.getName(), node.getClassName());
        assertTrue(node.isEnabled());
        assertTrue(node.isCheckable());
        assertFalse(node.isChecked());
        assertTrue(node.isClickable());
        assertTrue(clickable(node));
        assertTrue(readable(view).contains(preference.getTitle()));
        assertTrue(readable(view).contains(preference.getSummary()));
        node.recycle();
        AtomicInteger changes = new AtomicInteger();
        Preference.OnPreferenceChangeListener original = preference.getOnPreferenceChangeListener();
        preference.setOnPreferenceChangeListener((p, value) -> {
            changes.incrementAndGet();
            return original == null || original.onPreferenceChange(p, value);
        });
        events.clear();
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, new Bundle()));
        assertEquals(1, changes.get());
        assertTrue(preference.isChecked());
        assertTrue(Settings.HIDE_ADS.savedValue());
        List<AccessibilityEvent> clicks = new ArrayList<>();
        for (AccessibilityEvent event : events) {
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) clicks.add(event);
        }
        assertEquals("one accessible click should announce one completed transition", 1, clicks.size());
        assertEquals(Switch.class.getName(), clicks.get(0).getClassName());
        assertTrue(clicks.get(0).isChecked());
        assertTrue(clicks.get(0).getText().toString().contains(preference.getTitle()));
    }

    @Test public void disabledSwitchOffersNoClickAndCannotChangeItsSavedValue() throws Exception {
        open(PatchFamily.FOLLOWING_FEED);
        Preference preference = page.findPreference(Settings.ONLY_FOLLOWING.key);
        assertFalse(preference.isEnabled());
        View view = row(preference);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertEquals(Switch.class.getName(), node.getClassName());
        assertFalse(node.isEnabled());
        assertTrue(node.isCheckable());
        assertTrue(node.isChecked());
        assertFalse(node.isClickable());
        assertFalse(clickable(node));
        assertTrue(readable(view).contains(preference.getSummary()));
        node.recycle();
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertTrue(Settings.ONLY_FOLLOWING.savedValue());
    }

    @Test public void anActionRunsOnceAndRetainedRowsCannotRunAfterPageTeardown() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference("action_export_diagnostic_report");
        View view = row(preference);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertEquals(Button.class.getName(), node.getClassName());
        assertFalse(node.isCheckable());
        assertTrue(clickable(node));
        assertTrue(readable(view).contains(preference.getTitle()));
        node.recycle();
        AtomicInteger taps = new AtomicInteger();
        Preference.OnPreferenceClickListener original = preference.getOnPreferenceClickListener();
        preference.setOnPreferenceClickListener(p -> {
            taps.incrementAndGet();
            return original.onPreferenceClick(p);
        });
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(1, taps.get());
        AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(chooser.isShowing());
        controller.get().getFragmentManager().beginTransaction().remove(page).commitNow();
        assertNull(page.getView());
        assertFalse(chooser.isShowing());
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(1, taps.get());
    }

    @Test public void disablingAnAlreadyBoundSwitchImmediatelyRemovesItsAction() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        preference.setEnabled(false);
        // A service may already hold this row before the pending list rebind runs.
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertFalse(node.isEnabled());
        assertFalse(node.isClickable());
        assertFalse(clickable(node));
        node.recycle();
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(Settings.HIDE_ADS.savedValue());
    }

    @Test public void hiddenAndNonselectableSwitchesNeverChangeSettings() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        view.setVisibility(View.GONE);
        AccessibilityNodeInfo hidden = view.createAccessibilityNodeInfo();
        assertFalse(hidden.isVisibleToUser());
        assertFalse(clickable(hidden));
        hidden.recycle();
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(Settings.HIDE_ADS.savedValue());
        view.setVisibility(View.VISIBLE);
        preference.setSelectable(false);
        assertFalse(clickable(view.createAccessibilityNodeInfo()));
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(Settings.HIDE_ADS.savedValue());
    }

    @Test public void aFilteredRowCannotClickTheNewItemAtItsOldPosition() throws Exception {
        open(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED);
        View view = row(Settings.HIDE_ADS.key);
        page.searchSettings("following");
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        // Dispatch the delayed service action before the requested list layout runs.
        assertFalse(clickable(view.createAccessibilityNodeInfo()));
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertFalse(Settings.START_ON_FOLLOWING.savedValue());
        assertTrue(Settings.ONLY_FOLLOWING.savedValue());
        page.searchSettings("");
        layout();
        View rebound = row(Settings.HIDE_ADS.key);
        assertTrue(clickable(rebound.createAccessibilityNodeInfo()));
        assertTrue(rebound.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test public void aRetainedSwitchCannotMutateAfterItsListLeavesTheWindow() throws Exception {
        open(PatchFamily.HIDE_ADS);
        View view = row(Settings.HIDE_ADS.key);
        controller.get().getFragmentManager().beginTransaction().remove(page).commitNow();
        assertNull(page.getView());
        assertFalse(view.isAttachedToWindow());
        boolean clicked = view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
        assertFalse("a detached click changed Hide ads to " + Settings.HIDE_ADS.savedValue(), clicked);
        assertFalse(Settings.HIDE_ADS.savedValue());
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertFalse(clickable(node));
        node.recycle();
    }

    @Test public void pausedSwitchesDescribeSavedChoicesAndStillOfferDeliberateChanges() throws Exception {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        Settings.HIDE_ADS.save(true);
        open(PatchFamily.HIDE_ADS);
        View view = row(Settings.HIDE_ADS.key);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertTrue(node.isChecked());
        assertTrue(node.isEnabled());
        assertTrue(clickable(node));
        node.recycle();
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertTrue(HushgramPause.isPaused());
        assertTrue(BaseSettings.PAUSED.savedValue());
        AccessibilityNodeInfo pause = row(BaseSettings.PAUSED.key).createAccessibilityNodeInfo();
        assertEquals(Switch.class.getName(), pause.getClassName());
        assertTrue(pause.isChecked());
        assertTrue(clickable(pause));
        pause.recycle();
    }

    @Test @Config(qualifiers = "es-rES-w320dp-h640dp-xhdpi")
    public void translatedLabelsAndDisabledExplanationsAreExposedByTheNodes() throws Exception {
        open(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED);
        Preference preference = page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        assertNotEquals("Hide ads", preference.getTitle().toString());
        assertTrue(readable(view).contains(preference.getTitle()));
        assertTrue(readable(view).contains(preference.getSummary()));
        Preference unavailable = page.findPreference(Settings.ONLY_FOLLOWING.key);
        assertTrue(readable(row(unavailable)).contains(unavailable.getTitle()));
        assertTrue(readable(row(unavailable)).contains(unavailable.getSummary()));
        assertFalse(clickable(row(unavailable).createAccessibilityNodeInfo()));
    }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void rtlLargeTextRetainsTheSwitchRoleAndReadableLabels() throws Exception {
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        RuntimeEnvironment.setFontScale(2f);
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertEquals(Switch.class.getName(), node.getClassName());
        assertTrue(clickable(node));
        assertTrue(readable(view).contains(preference.getTitle()));
        assertTrue(readable(view).contains(preference.getSummary()));
        assertEquals(View.LAYOUT_DIRECTION_RTL, view.getLayoutDirection());
        node.recycle();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void focusedRowsKeepTheirIdentityAndCurrentStateDuringRebinding() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        // TalkBack brings a row it focuses fully on screen. On API 28 a row cut off at the
        // bottom can come back as a new view once its summary shortens.
        ListView list = page.getView().findViewById(android.R.id.list);
        if (view.getBottom() > list.getHeight()) {
            list.scrollListBy(view.getBottom() - list.getHeight());
            view = row(preference);
        }
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        assertTrue(view.createAccessibilityNodeInfo().isAccessibilityFocused());
        preference.setSummary("Updated visible explanation");
        assertTrue("a summary notification cleared focus before layout",
                view.createAccessibilityNodeInfo().isAccessibilityFocused());
        layout();
        View rebound = row(preference);
        assertSame("rebinding must retain the focused row", view, rebound);
        assertTrue(rebound.createAccessibilityNodeInfo().isAccessibilityFocused());
        assertTrue(readable(rebound).contains("Updated visible explanation"));
        assertTrue(rebound.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        layout();
        assertTrue(row(preference).createAccessibilityNodeInfo().isChecked());
    }

    @Test public void aDisabledActionCannotOpenItsDialog() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference("action_export_diagnostic_report");
        View view = row(preference);
        preference.setEnabled(false);
        assertFalse(clickable(view.createAccessibilityNodeInfo()));
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void aRefusedSwitchChangeRunsItsListenerOnceAndKeepsTheOffState() throws Exception {
        open(PatchFamily.HIDE_ADS);
        SwitchPreference preference = (SwitchPreference) page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        AtomicInteger changes = new AtomicInteger();
        preference.setOnPreferenceChangeListener((p, value) -> { changes.incrementAndGet(); return false; });
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(1, changes.get());
        assertFalse(preference.isChecked());
        assertFalse(Settings.HIDE_ADS.savedValue());
        assertFalse(view.createAccessibilityNodeInfo().isChecked());
    }

    @Test public void anAccessibleChoiceOpensItsNativeDialogAndUpdatesTheReadableValue() throws Exception {
        open(PatchFamily.REEL_DOWNLOAD);
        HushgramPreferenceFragment.QualityRow preference = (HushgramPreferenceFragment.QualityRow)
                page.findPreference(Settings.DOWNLOAD_QUALITY.key);
        View view = row(preference);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertEquals(Button.class.getName(), node.getClassName());
        assertFalse(node.isCheckable());
        assertTrue(clickable(node));
        node.recycle();
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        AlertDialog dialog = (AlertDialog) preference.getDialog();
        assertTrue(dialog.isShowing());
        ShadowLooper.idleMainLooper();
        ListView choices = dialog.getListView();
        int smallest = preference.findIndexOfValue(DownloadQuality.SMALLEST.name());
        assertTrue("the native choice listener wasn't wired", choices.performItemClick(
                choices.getAdapter().getView(smallest, null, choices),
                smallest, choices.getItemIdAtPosition(smallest)));
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals(DownloadQuality.SMALLEST.name(), preference.getValue());
        assertEquals(DownloadQuality.SMALLEST, Settings.DOWNLOAD_QUALITY.savedValue());
        assertTrue(readable(row(preference)).contains(preference.getSummary()));
    }

    @Test public void anAccessibleNavigationChoiceSavesAndAnnouncesItsSelectedTab() throws Exception {
        open();
        HushgramPreferenceFragment.NavigationRow preference = (HushgramPreferenceFragment.NavigationRow)
                page.findPreference(Settings.NAVIGATION_SETTINGS_TARGET.key);
        View view = row(preference);
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        AlertDialog dialog = (AlertDialog) preference.getDialog();
        assertTrue(dialog.isShowing());
        ShadowLooper.idleMainLooper();
        ListView choices = dialog.getListView();
        int selected = preference.findIndexOfValue(NavigationTarget.PROFILE.name());
        assertTrue(choices.performItemClick(choices.getAdapter().getView(selected, null, choices),
                selected, choices.getItemIdAtPosition(selected)));
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals(NavigationTarget.PROFILE, Settings.NAVIGATION_SETTINGS_TARGET.savedValue());
        assertTrue(preference.getSummary().toString().contains("Profile"));
        assertRoleLabel(row(preference), preference);
    }

    @Test public void missingFamiliesAndInformationalRowsOfferNoFeatureAction() throws Exception {
        open();
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.ONLY_FOLLOWING.key));
        assertNull(page.getPreferenceScreen().findPreference("hushgram_status"));
        Preference status = page.about.getPreference(0);
        assertEquals("Version", status.getTitle());
        assertFalse(status.isSelectable());
        View view = row(status);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        assertNotEquals(Button.class.getName(), node.getClassName());
        assertFalse(node.isCheckable());
        assertFalse(clickable(node));
        node.recycle();
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void accessibilitySearchAndClearKeepInputFocusAndRestoreTheSameControls() throws Exception {
        open(PatchFamily.HIDE_ADS, PatchFamily.FOLLOWING_FEED);
        Preference ads = page.findPreference(Settings.HIDE_ADS.key);
        Preference search = page.findPreference("hushgram_settings_search");
        View searchView = row(search);
        EditText field = searchView.findViewWithTag("hushgram-settings-search");
        AccessibilityNodeInfo input = field.createAccessibilityNodeInfo();
        assertEquals(EditText.class.getName(), input.getClassName());
        assertTrue(input.isEditable());
        assertTrue(input.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT));
        input.recycle();
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "ads");
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text));
        layout();
        EditText rebound = row(search).findViewWithTag("hushgram-settings-search");
        assertSame(field, rebound);
        assertTrue("focus moved to search row: " + row(search).createAccessibilityNodeInfo().isAccessibilityFocused(),
                rebound.createAccessibilityNodeInfo().isAccessibilityFocused());
        assertSame(ads, page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        assertNull(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        Button clear = (Button) ((ViewGroup) rebound.getParent()).getChildAt(1);
        AccessibilityNodeInfo clearNode = clear.createAccessibilityNodeInfo();
        assertTrue(clickable(clearNode));
        assertEquals("Clear search", clearNode.getContentDescription());
        clearNode.recycle();
        assertTrue(clear.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        layout();
        assertEquals("", field.getText().toString());
        assertSame(ads, page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        assertNotNull(page.getPreferenceScreen().findPreference(Settings.START_ON_FOLLOWING.key));
        assertTrue(field.createAccessibilityNodeInfo().isAccessibilityFocused());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void searchFocusHoldsBalanceAcrossRepeatedActionsAndPageTeardown() throws Exception {
        open(PatchFamily.HIDE_ADS);
        View search = row(page.findPreference("hushgram_settings_search"));
        EditText field = search.findViewWithTag("hushgram-settings-search");
        assertFalse(search.hasTransientState());
        for (int i = 0; i < 3; i++) {
            assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
            assertTrue(search.hasTransientState());
            assertFalse(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
            assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
            assertFalse("duplicate focus added an unmatched hold", search.hasTransientState());
        }
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        assertTrue(search.hasTransientState());
        controller.get().getFragmentManager().beginTransaction().remove(page).commitNow();
        assertFalse(search.isAttachedToWindow());
        assertFalse("closing the page retained its focused search row", search.hasTransientState());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void disablingAccessibilityThenClearingFocusReleasesSearchHold() throws Exception {
        open(PatchFamily.HIDE_ADS);
        View search = row(page.findPreference("hushgram_settings_search"));
        EditText field = search.findViewWithTag("hushgram-settings-search");
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        assertTrue(search.hasTransientState());
        AccessibilityManager manager = (AccessibilityManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        shadowOf(manager).setEnabled(false);
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
        assertFalse(field.createAccessibilityNodeInfo().isAccessibilityFocused());
        assertFalse("focus cleared without an accessibility event, but the search hold stayed", search.hasTransientState());
        shadowOf(manager).setEnabled(true);
        search.setHasTransientState(true);
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        shadowOf(manager).setEnabled(false);
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
        assertTrue("clearing focus released another owner's hold", search.hasTransientState());
        search.setHasTransientState(false);
        assertFalse("search kept its own hold after accessibility stopped", search.hasTransientState());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void retainedClosedSearchClearCannotAdvertiseOrPerformClick() throws Exception {
        open(PatchFamily.HIDE_ADS);
        View search = row(page.findPreference("hushgram_settings_search"));
        EditText field = search.findViewWithTag("hushgram-settings-search");
        field.setText("ads");
        layout();
        Button clear = (Button) ((ViewGroup) field.getParent()).getChildAt(1);
        assertTrue(clear.isAttachedToWindow());
        controller.get().getFragmentManager().beginTransaction().remove(page).commitNow();
        assertNull(page.getView());
        AccessibilityNodeInfo node = clear.createAccessibilityNodeInfo();
        boolean advertised = clickable(node);
        boolean performed = clear.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
        assertFalse("the closed search Clear still advertises click", advertised);
        assertFalse("the closed search Clear still performs click", performed);
        assertEquals("ads", field.getText().toString());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aDisabledSettingsListCannotToggleItsChildSwitch() throws Exception {
        open(PatchFamily.HIDE_ADS);
        View view = row(Settings.HIDE_ADS.key);
        ListView list = page.getView().findViewById(android.R.id.list);
        list.setEnabled(false);
        boolean advertised = clickable(view.createAccessibilityNodeInfo());
        boolean performed = view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
        assertFalse("the disabled list still exposes a working row action", advertised);
        assertFalse(performed);
        assertFalse(Settings.HIDE_ADS.savedValue());
        list.setEnabled(true);
        host.setEnabled(false);
        assertFalse(view.createAccessibilityNodeInfo().isEnabled());
        assertFalse(clickable(view.createAccessibilityNodeInfo()));
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(Settings.HIDE_ADS.savedValue());
        host.setEnabled(true);
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertTrue(Settings.HIDE_ADS.savedValue());
    }

    @Test public void aRemovedSectionCannotStillToggleItsChildBeforeRebinding() throws Exception {
        open(PatchFamily.HIDE_ADS);
        Preference preference = page.findPreference(Settings.HIDE_ADS.key);
        View view = row(preference);
        assertTrue(page.getPreferenceScreen().removePreference(preference.getParent()));
        assertNull(page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        boolean advertised = clickable(view.createAccessibilityNodeInfo());
        boolean performed = view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
        assertFalse("a removed category's child still exposes a working action", advertised);
        assertFalse(performed);
        assertFalse(Settings.HIDE_ADS.savedValue());
    }

    @Test public void categoryAccessibilityActionsExpandCollapseAndRejectDetachedChildren() throws Exception {
        Settings.CATEGORY_PAGES.save(true);
        open(PatchFamily.HIDE_ADS);
        Preference header = null;
        for (int i = 0; i < page.getPreferenceScreen().getPreferenceCount(); i++) {
            Preference candidate = page.getPreferenceScreen().getPreference(i);
            if ("Ads and privacy".contentEquals(candidate.getTitle())) header = candidate;
        }
        assertNotNull(header);
        View button = row(header);
        AccessibilityNodeInfo node = button.createAccessibilityNodeInfo();
        assertTrue(node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND));
        assertFalse(node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE));
        if (android.os.Build.VERSION.SDK_INT >= 30) assertEquals("Collapsed", node.getStateDescription());
        else assertTrue(String.valueOf(node.getContentDescription()).endsWith("Collapsed"));
        node.recycle();
        assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null));
        layout();
        Preference ads = page.findPreference(Settings.HIDE_ADS.key);
        assertSame(header, ads.getParent());
        assertTrue(ads.isEnabled());
        View child = row(ads);
        assertTrue(child.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertTrue(Settings.HIDE_ADS.savedValue());
        button = row(header);
        node = button.createAccessibilityNodeInfo();
        assertTrue(node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE));
        assertFalse(node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND));
        if (android.os.Build.VERSION.SDK_INT >= 30) assertEquals("Expanded", node.getStateDescription());
        else assertTrue(String.valueOf(node.getContentDescription()).endsWith("Expanded"));
        node.recycle();
        assertTrue(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_COLLAPSE, null));
        // The adapter still holds the old child view until its queued hierarchy refresh runs.
        assertNull(ads.getParent());
        assertFalse(clickable(child.createAccessibilityNodeInfo()));
        assertFalse(child.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertTrue(Settings.HIDE_ADS.savedValue());
        layout();
        assertTrue(row(header).performAccessibilityAction(AccessibilityNodeInfo.ACTION_EXPAND, null));
        layout();
        assertSame(ads, page.getPreferenceScreen().findPreference(Settings.HIDE_ADS.key));
        assertTrue(((android.preference.SwitchPreference) ads).isChecked());
    }

    @Test public void aReplacedScreenCannotAcceptItsPreviousRowBeforeRebinding() throws Exception {
        open(PatchFamily.HIDE_ADS);
        View view = row(Settings.HIDE_ADS.key);
        page.setPreferenceScreen(page.getPreferenceManager().createPreferenceScreen(page.getActivity()));
        assertFalse(clickable(view.createAccessibilityNodeInfo()));
        assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(Settings.HIDE_ADS.savedValue());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void searchControlsRespectDisabledAncestorsAndRejectClosedPageEdits() throws Exception {
        open(PatchFamily.HIDE_ADS);
        View search = row(page.findPreference("hushgram_settings_search"));
        EditText field = search.findViewWithTag("hushgram-settings-search");
        field.setText("ads");
        layout();
        Button clear = (Button) ((ViewGroup) field.getParent()).getChildAt(1);
        ListView list = page.getView().findViewById(android.R.id.list);
        Bundle changed = new Bundle();
        changed.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "following");
        list.setEnabled(false);
        assertFalse(clear.createAccessibilityNodeInfo().isEnabled());
        assertFalse(clickable(clear.createAccessibilityNodeInfo()));
        assertFalse(clear.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, changed));
        assertEquals("ads", field.getText().toString());
        list.setEnabled(true);
        assertTrue(clear.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals("", field.getText().toString());
        field.setText("ads");
        layout();
        controller.get().getFragmentManager().beginTransaction().remove(page).commitNow();
        assertFalse(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, changed));
        assertEquals("ads", field.getText().toString());
        assertFalse(clear.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals("ads", field.getText().toString());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void searchFocusNeverReleasesAnotherOwnersTransientState() throws Exception {
        open(PatchFamily.HIDE_ADS);
        View search = row(page.findPreference("hushgram_settings_search"));
        EditText field = search.findViewWithTag("hushgram-settings-search");
        search.setHasTransientState(true);
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        assertTrue(field.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null));
        assertTrue("search cleared an unrelated reference", search.hasTransientState());
        search.setHasTransientState(false);
        assertFalse(search.hasTransientState());
    }
}
