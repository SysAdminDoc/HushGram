/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.pm.ApplicationInfo;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowAlertDialog;

import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/** Real framework/font boundaries. Instagram 453 declares minSdk 28 and targetSdk 37. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp-xhdpi")
@SuppressWarnings("deprecation")
public class SettingsDialogBoundaryTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private SettingsDialog dialog;

    @Before public void host() {
        ApplicationInfo info = RuntimeEnvironment.getApplication().getApplicationInfo();
        info.targetSdkVersion = 37;
        info.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        PatchFamily.inBuildForTests = EnumSet.noneOf(PatchFamily.class);
        RuntimeEnvironment.setFontScale(2f);
        BrokenPage.constructorFailures = 0;
        BrokenPage.viewFailures = 0;
    }

    @After public void close() throws Exception {
        if (dialog != null) dialog.dismissAllowingStateLoss();
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        PatchFamily.inBuildForTests = null;
        RuntimeEnvironment.setFontScale(1f);
        SettingsEntry.onClosedByUser();
    }

    @Test @Config(sdk = 28)
    public void sdk28DialogKeepsLegacyBarsOutsideLargeTextContent() throws Exception {
        assertEquals(28, Build.VERSION.SDK_INT);
        checkInsets(false);
    }

    @Test @Config(sdk = 37)
    public void sdk37DialogKeepsSystemBarsOutsideLargeTextContent() throws Exception {
        assertEquals(37, Build.VERSION.SDK_INT);
        checkInsets(false);
    }

    /**
     * Nothing resizes an edge-to-edge window for the keyboard, so the page and Restart now sat
     * behind it (S25, 2026-10-13). The keyboard's inset lifts them the way the buttons' does, and
     * closing it gives the room back.
     */
    @Test @Config(sdk = 37)
    public void theKeyboardLiftsThePageAndRestartNow() throws Exception {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "boundary_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        ViewGroup root = (ViewGroup) dialog.getView();
        assertNotNull(root);
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 30))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 500))
                .setVisible(WindowInsets.Type.ime(), true).build());
        assertEquals(24, root.getPaddingTop());
        assertEquals("the keyboard is taller than the buttons", 500, root.getPaddingBottom());
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 640, 1280);
        View container = root.findViewById(SettingsDialog.CONTAINER_ID);
        assertTrue("the page ends above the keyboard", container.getBottom() <= 1280 - 500);
        View restart = root.findViewById(SettingsDialog.RESTART_ID);
        assertNotNull(restart);
        restart.setVisibility(View.VISIBLE);
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 640, 1280);
        assertTrue("Restart now ends above the keyboard", restart.getBottom() <= 1280 - 500);
        assertTrue("and has its height", restart.getHeight() > 0);

        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 30)).build());
        assertEquals("the keyboard closed", 30, root.getPaddingBottom());
    }

    @Test @Config(sdk = 28, qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    public void sdk28DialogMirrorsItsLargeTextHeader() throws Exception {
        assertEquals(28, Build.VERSION.SDK_INT);
        checkInsets(true);
    }

    @Test @Config(sdk = 37, qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    public void sdk37DialogMirrorsItsLargeTextHeader() throws Exception {
        assertEquals(37, Build.VERSION.SDK_INT);
        checkInsets(true);
    }

    @Test @Config(sdk = {28, 29, 37})
    public void diagnosticChooserClosesWithItsSettingsPage() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "boundary_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        HushgramPreferenceFragment page = (HushgramPreferenceFragment)
                dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        android.preference.Preference export = page.findPreference("action_export_diagnostic_report");
        assertNotNull(export);
        assertTrue(export.getOnPreferenceClickListener().onPreferenceClick(export));
        AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(chooser);
        assertTrue(chooser.isShowing());
        dialog.dismissAllowingStateLoss();
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        assertNull(page.getView());
        assertFalse("report chooser outlived the settings page", chooser.isShowing());
    }

    @Test @Config(sdk = {28, 29, 37})
    public void aLateReportTapCannotOpenAChooserAfterPageTeardown() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "boundary_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        HushgramPreferenceFragment page = (HushgramPreferenceFragment)
                dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        android.preference.Preference export = page.findPreference("action_export_diagnostic_report");
        AlertDialog previous = ShadowAlertDialog.getLatestAlertDialog();
        dialog.dismissAllowingStateLoss();
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        assertNull(page.getView());
        assertTrue(export.getOnPreferenceClickListener().onPreferenceClick(export));
        assertSame("a detached row opened another report chooser", previous,
                ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test @Config(sdk = {28, 37}, shadows = BrokenPage.class)
    public void constructorFailureKeepsOneRetryAndBackThenShowsTheOrdinaryPage() {
        android.view.accessibility.AccessibilityManager accessibility =
                (android.view.accessibility.AccessibilityManager) RuntimeEnvironment.getApplication()
                        .getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        org.robolectric.Shadows.shadowOf(accessibility).setEnabled(true);
        org.robolectric.Shadows.shadowOf(accessibility).setTouchExplorationEnabled(true);
        BrokenPage.constructorFailures = 1;
        openRecovery();
        View retry = text(dialog.getView(), "Retry");
        assertNotNull("constructor failure left an empty shell", retry);
        assertNotNull(text(dialog.getView(), "Settings couldn't open"));
        android.view.accessibility.AccessibilityNodeInfo node = retry.createAccessibilityNodeInfo();
        assertEquals("android.widget.Button", node.getClassName());
        assertTrue(node.isClickable());
        assertTrue((node.getActions() & android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) != 0);
        retry.performClick();
        android.app.Fragment page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue(page instanceof HushgramPreferenceFragment);
        assertNotNull(page.getView());
        assertTrue("recovery must leave focus in the ordinary page",
                page.getView().findViewById(android.R.id.list).hasFocus());
        ShadowLooper.idleMainLooper();
        assertTrue("a screen reader must return to the page heading",
                text(dialog.getView(), "HushGram").isAccessibilityFocused());
        assertNull(text(dialog.getView(), "Retry"));
        retry.performClick();
        assertSame(page, dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID));
        assertEquals(1, dialog.getChildFragmentManager().getFragments().size());
    }

    @Test @Config(sdk = {28, 37}, shadows = BrokenPage.class)
    public void failureAfterNativeViewCreationRemovesThePartialChildBeforeRetry() {
        BrokenPage.viewFailures = 1;
        openRecovery();
        assertNotNull(text(dialog.getView(), "Settings couldn't open"));
        assertNull(dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID));
        View retry = text(dialog.getView(), "Retry");
        assertNotNull(retry);
        retry.performClick();
        assertNotNull(dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID).getView());
        assertEquals(1, dialog.getChildFragmentManager().getFragments().size());
    }

    @Test @Config(sdk = {28, 37}, shadows = BrokenPage.class)
    public void restoredChildViewFailureKeepsRecoveryInsideTheDialog() {
        openRecovery();
        BrokenPage.viewFailures = 1;
        controller.recreate();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("boundary_settings");
        assertNotNull(text(dialog.getView(), "Settings couldn't open"));
        View retry = text(dialog.getView(), "Retry");
        assertNotNull(retry);
        retry.performClick();
        assertNotNull(dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID).getView());
        assertEquals(1, dialog.getChildFragmentManager().getFragments().size());
    }

    @Test @Config(sdk = {28, 37}, shadows = BrokenPage.class)
    public void restoredChildConstructorFailureKeepsRecoveryInsideTheDialog() {
        openRecovery();
        BrokenPage.constructorFailures = 1;
        controller.recreate();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("boundary_settings");
        assertNotNull(text(dialog.getView(), "Settings couldn't open"));
        View retry = text(dialog.getView(), "Retry");
        assertNotNull(retry);
        retry.performClick();
        assertNotNull(dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID).getView());
        assertEquals(1, dialog.getChildFragmentManager().getFragments().size());
    }

    @Test @Config(sdk = {28, 37}, shadows = BrokenPage.class)
    public void rotationKeepsThePendingDocumentRequestAndItsFragmentIdentity() throws Exception {
        openRecovery();
        HushgramPreferenceFragment page = (HushgramPreferenceFragment)
                dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        Bundle identity = new Bundle();
        dialog.getChildFragmentManager().putFragment(identity, "page", page);
        android.preference.Preference export = page.findPreference("hushgram_export_configuration");
        export.getOnPreferenceClickListener().onPreferenceClick(export);
        org.robolectric.shadows.ShadowActivity.IntentForResult picked =
                org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivityForResult();
        assertNotNull(picked);
        controller.recreate();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("boundary_settings");
        HushgramPreferenceFragment restored = (HushgramPreferenceFragment)
                dialog.getChildFragmentManager().getFragment(identity, "page");
        assertNotSame(page, restored);
        assertFalse(restored.findPreference("hushgram_export_configuration").isEnabled());
        android.net.Uri document = android.net.Uri.parse("content://fixture/settings.json");
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        org.robolectric.Shadows.shadowOf(controller.get().getContentResolver()).registerOutputStream(document, output);
        restored.onActivityResult(picked.requestCode, Activity.RESULT_OK, new android.content.Intent().setData(document));
        Utils.awaitBackgroundTasksForTests();
        ShadowLooper.idleMainLooper();
        assertTrue(output.size() > 0);
        assertTrue(restored.findPreference("hushgram_export_configuration").isEnabled());
        assertEquals(1, dialog.getChildFragmentManager().getFragments().size());
    }

    @Test @Config(sdk = {28, 37}, shadows = BrokenPage.class)
    public void savedOrClosedOwnersCannotAcceptALateRetry() {
        BrokenPage.constructorFailures = 1;
        openRecovery();
        View retry = text(dialog.getView(), "Retry");
        assertNotNull(retry);
        controller.pause().saveInstanceState(new Bundle()).stop();
        retry.performClick();
        assertNull(dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID));
        controller.restart().start().resume().visible();
        retry.performClick();
        assertNotNull(dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID));
        android.app.FragmentManager manager = controller.get().getFragmentManager();
        dialog.dismissAllowingStateLoss();
        manager.executePendingTransactions();
        retry.performClick();
        assertNull(dialog.getView());
        dialog = new SettingsDialog();
        dialog.show(manager, "boundary_settings");
        manager.executePendingTransactions();
        ShadowLooper.idleMainLooper();
        assertNotNull(dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID).getView());
    }

    @Test @Config(sdk = {28, 37}, shadows = BrokenPage.class,
            qualifiers = "ar-rEG-ldrtl-w320dp-h640dp-xhdpi")
    public void rotationRetainsReadableFailureAndRejectsTheOldRetry() {
        BrokenPage.constructorFailures = 1;
        openRecovery();
        View oldRetry = text(dialog.getView(), "Retry");
        assertNotNull(oldRetry);
        controller.recreate();
        dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag("boundary_settings");
        assertNotNull(dialog);
        assertNotNull(text(dialog.getView(), "Settings couldn't open"));
        View retry = text(dialog.getView(), "Retry");
        assertNotSame(oldRetry, retry);
        oldRetry.performClick();
        assertNull(dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID));
        View root = dialog.getView();
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 640, 1280);
        TextView message = (TextView) text(root, "Settings couldn't open");
        assertEquals(0, message.getLayout().getEllipsisCount(0));
        assertEquals(message.getText().length(), message.getLayout().getLineEnd(message.getLineCount() - 1));
        assertTrue(retry.getHeight() >= 96);
        ViewGroup header = (ViewGroup) ((ViewGroup) root).getChildAt(0);
        header.getChildAt(0).performClick();
        controller.get().getFragmentManager().executePendingTransactions();
        assertNull(dialog.getView());
        retry.performClick();
    }

    private void openRecovery() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "boundary_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
    }

    private static View text(View view, String wanted) {
        if (view instanceof TextView && wanted.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = text(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Implements(value = HushgramPreferenceFragment.class, isInAndroidSdk = false)
    public static class BrokenPage {
        static int constructorFailures, viewFailures;
        @RealObject private HushgramPreferenceFragment page;

        @Implementation protected void __constructor__() {
            Shadow.invokeConstructor(HushgramPreferenceFragment.class, page);
            if (constructorFailures-- > 0) throw new IllegalStateException("fixture constructor failure");
        }

        @Implementation protected void onViewCreated(View view, Bundle state) {
            Shadow.directlyOn(page, HushgramPreferenceFragment.class, "onViewCreated",
                    ClassParameter.from(View.class, view), ClassParameter.from(Bundle.class, state));
            if (viewFailures-- > 0) throw new IllegalStateException("fixture partial view failure");
        }
    }

    private void checkInsets(boolean rtl) throws Exception {
        assertEquals(37, RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "boundary_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        // The standalone test host's window is not Instagram's RTL window. Supply that host
        // direction, as SettingsScreenRowLayoutTest does; the dialog's children must inherit it.
        assertEquals(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR,
                controller.get().getResources().getConfiguration().getLayoutDirection());
        dialog.getDialog().getWindow().getDecorView().setLayoutDirection(
                rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        ShadowLooper.idleMainLooper();
        ViewGroup root = (ViewGroup) dialog.getView();
        assertNotNull(root);
        WindowInsets bars = Build.VERSION.SDK_INT >= 30
                ? new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(11, 24, 13, 30)).build()
                : WindowInsets.class.getConstructor(Rect.class).newInstance(new Rect(11, 24, 13, 30));
        root.dispatchApplyWindowInsets(bars);
        root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 640, 1280);
        assertEquals(11, root.getPaddingLeft());
        assertEquals(24, root.getPaddingTop());
        assertEquals(13, root.getPaddingRight());
        assertEquals(30, root.getPaddingBottom());
        ViewGroup header = (ViewGroup) root.getChildAt(0);
        View back = header.getChildAt(0);
        TextView title = (TextView) header.getChildAt(1);
        assertEquals("HushGram", String.valueOf(title.getText()));
        assertEquals(0, title.getLayout().getEllipsisCount(0));
        assertEquals(title.getText().length(), title.getLayout().getLineEnd(title.getLineCount() - 1));
        assertTrue(title.getHeight() >= title.getLayout().getHeight());
        assertTrue(title.getBottom() <= header.getHeight() - header.getPaddingBottom());
        assertTrue(back.getWidth() >= 96);
        assertTrue(back.getHeight() >= 96);
        assertEquals(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR, header.getLayoutDirection());
        assertTrue(rtl ? title.getRight() <= back.getLeft() : back.getRight() <= title.getLeft());
        View container = root.findViewById(SettingsDialog.CONTAINER_ID);
        assertTrue(container.getBottom() <= root.getHeight() - root.getPaddingBottom());
    }
}
