/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import app.hushgram.extension.instagram.reels.TabOrder;
import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Contrast, real choice callbacks and measured large-text dialog controls after styling. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 29, qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@SuppressWarnings("deprecation")
public class ScreenColorsAccessibilityTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<Activity> controller;
    private final List<AlertDialog> opened = new ArrayList<>();

    @Before public void prepare() {
        RuntimeEnvironment.getApplication().getApplicationInfo().targetSdkVersion = 37;
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        RuntimeEnvironment.setFontScale(1f);
        Settings.SIGN_IN_NOTICE_HIDDEN.save(true);
        Settings.TAB_ORDER.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        BaseSettings.SAFE_MODE.save(false);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        TabOrder.forgetForTests();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
    }

    @After public void close() throws Exception {
        for (AlertDialog alert : opened) alert.dismiss();
        if (controller != null) controller.close();
        Utils.awaitBackgroundTasksForTests();
        Settings.SIGN_IN_NOTICE_HIDDEN.resetToDefault();
        Settings.TAB_ORDER.resetToDefault();
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        RuntimeEnvironment.setFontScale(1f);
        TabOrder.forgetForTests();
    }

    @Test public void enabledTextAndSwitchPartsRemainDistinctOnEverySurface() {
        readable(ScreenColors.DEFAULT);
    }

    @Test public void pureBlackTurnsCardsAndDialogsBlackAndKeepsTheirOutlines() {
        ScreenColors black = new ScreenColors(true);
        assertEquals(Color.BLACK, black.card);
        assertEquals(Color.BLACK, black.dialog);
        assertEquals(ScreenColors.DEFAULT.outline, black.outline);
        contrast("card edge", black.outline, Color.BLACK, 1.3);
        readable(black);
        ScreenColors charcoal = new ScreenColors(false);
        assertNotEquals(Color.BLACK, charcoal.card);
        assertNotEquals(Color.BLACK, charcoal.dialog);
    }

    private static void readable(ScreenColors colors) {
        for (int surface : new int[]{colors.background, colors.card, colors.dialog}) {
            contrast("title", colors.title, surface, 4.5);
            contrast("summary", colors.summary, surface, 4.5);
            contrast("heading and secondary action", colors.heading, surface, 4.5);
            contrast("validation error", colors.error, surface, 4.5);
            contrast("enabled on track", colors.accent, surface, 3.0);
            contrast("enabled off track", colors.switchOff, surface, 3.0);
        }
        contrast("icon", colors.heading, colors.iconSurface, 3.0);
        contrast("filled action text", colors.onAccent, colors.accent, 4.5);
        contrast("thumb on checked track", colors.onAccent, colors.accent, 3.0);
        contrast("thumb on unchecked track", colors.offThumb(), colors.switchOff, 3.0);
    }

    @Test public void nativeFieldsAndActionsDrawVisibleFocusWithoutChangingTheirRoles() {
        ScreenColors colors = ScreenColors.DEFAULT;
        Activity activity = controller.get();
        EditText field = new EditText(activity);
        ScreenColors.styleField(field);
        Button primary = new Button(activity);
        primary.setText("Save");
        ScreenColors.styleAction(primary, true);
        Button secondary = new Button(activity);
        secondary.setText("Cancel");
        ScreenColors.styleAction(secondary, false);
        Button compact = new Button(activity);
        compact.setContentDescription("Clear search");
        ScreenColors.styleIconAction(compact);
        for (View control : new View[]{field, primary, secondary, compact}) {
            int ordinary = border(control, android.R.attr.state_enabled);
            int focused = border(control, android.R.attr.state_enabled, android.R.attr.state_focused);
            assertNotEquals("Focus must visibly outline " + control.getClass().getSimpleName(), ordinary, focused);
            int surface = control == primary ? colors.accent : colors.dialog;
            contrast("focused control boundary", focused, surface, 3.0);
            GradientDrawable shape = control.getBackground() instanceof RippleDrawable
                    ? (GradientDrawable) ((RippleDrawable) control.getBackground()).getDrawable(0)
                    : (GradientDrawable) control.getBackground();
            assertTrue("Shared controls use the compact radius scale", shape.getCornerRadius() <= dp(12));
        }
        contrast("unfocused input boundary", border(field, android.R.attr.state_enabled), colors.dialog, 3.0);
        assertTrue(primary instanceof Button);
        assertTrue(secondary.getBackground() instanceof RippleDrawable);
        assertTrue(compact.getBackground() instanceof RippleDrawable);
        assertNotNull("Fields keep native editing", field.getKeyListener());
    }

    @Test @Config(sdk = {28, 37})
    public void freshActionsPaintEnabledFillAndOutlineBeforeAnyStateToggle() {
        ScreenColors colors = ScreenColors.DEFAULT;
        for (int theme : new int[]{android.R.style.Theme_Material_Light_NoActionBar,
                android.R.style.Theme_Material_NoActionBar}) {
            for (boolean primary : new boolean[]{true, false}) {
                Button action = new Button(new ContextThemeWrapper(controller.get(), theme));
                action.setText(primary ? "Save" : "Cancel");
                ScreenColors.styleAction(action, primary);
                assertTrue(action.isEnabled());
                int fill = primary ? colors.accent : colors.card;
                int edge = primary ? colors.accent : colors.outline;
                assertEquals("A fresh enabled action must start opaque", fill, nativePixel(action, 6));
                assertEquals("Its default outline must start opaque", edge, nativePixel(action, 1));
                ScreenColors.styleAction(action, primary);
                assertEquals("Restyling an enabled action preserves the current fill", fill, nativePixel(action, 6));
                action.setEnabled(false);
                assertEquals("Only a disabled action uses the dimmed fill", 0x61, Color.alpha(nativePixel(action, 6)));
                action.setEnabled(true);
                assertEquals("Re-enabling restores the opaque fill", fill, nativePixel(action, 6));
                assertEquals(edge, nativePixel(action, 1));
            }
        }
    }

    @Test @Config(sdk = {28, 37})
    public void freshChoicesKeepTheirNativeEnabledAndCheckedStateThroughTheTintPass() {
        ScreenColors colors = ScreenColors.DEFAULT;
        Activity activity = controller.get();
        ChoiceCards.Card card = new ChoiceCards.Card(activity, colors);
        card.name.setText("Original");
        card.detail.setText("Keep the original file.");
        card.setBackgroundTintList(null);
        assertEquals(colors.card, nativePixel(card, 10));
        assertEquals("A fresh card's enabled outline must not become translucent", colors.outline, nativePixel(card, 4));

        TextView plain = new TextView(activity);
        plain.setText("Original");
        plain.setBackgroundTintList(null);
        new DialogChoices(singleRow(plain), colors).getView(0, null, new FrameLayout(activity));
        assertEquals(colors.card, nativePixel(plain, 10));
        assertEquals(colors.outline, nativePixel(plain, 4));

        CheckedTextView checked = new CheckedTextView(activity);
        checked.setText("Original");
        checked.setChecked(true);
        checked.setBackgroundTintList(null);
        new DialogChoices(singleRow(checked), colors).getView(0, null, new FrameLayout(activity));
        assertTrue(checked.isChecked());
        assertEquals("An already checked choice keeps its native selection fill", 0x22,
                Color.alpha(nativePixel(checked, 6)));
        assertEquals(colors.heading, nativePixel(checked, 0));
    }

    private static BaseAdapter singleRow(View row) {
        return new BaseAdapter() {
            @Override public int getCount() { return 1; }
            @Override public Object getItem(int position) { return row; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) { return row; }
        };
    }

    /** Render the actual view's state. Do not push a synthetic state into its background. */
    private int nativePixel(View view, int yDp) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(160), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(bitmap));
            return bitmap.getPixel(bitmap.getWidth() / 2, dp(yDp));
        } finally { bitmap.recycle(); }
    }

    @Test public void keyboardSelectedChoiceAndPreferenceRowsRetainFocusAndTouchFeedback() {
        Activity activity = controller.get();
        Preference preference = new Preference(activity);
        preference.setTitle("Downloads");
        View row = preference.getView(null, new FrameLayout(activity));
        ScreenColors.row(row, preference);
        assertTrue(row.getBackground() instanceof RippleDrawable);
        Drawable inset = ((RippleDrawable) row.getBackground()).getDrawable(0);
        assertTrue(inset instanceof InsetDrawable);
        Drawable surface = ((InsetDrawable) inset).getDrawable();
        int ordinary = border(surface, android.R.attr.state_enabled);
        int selected = border(surface, android.R.attr.state_enabled, android.R.attr.state_selected);
        assertNotEquals("Native list keyboard selection must stay visible", ordinary, selected);
        contrast("selected row", selected, ScreenColors.DEFAULT.card, 3.0);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setSingleChoiceItems(new String[]{"Original", "Smaller file"}, 0, (d, index) -> { }).show();
        opened.add(dialog);
        ScreenColors.dialog(dialog);
        View choice = dialog.getListView().getAdapter().getView(0, null, dialog.getListView());
        assertTrue("Restyling a native choice must preserve pressed feedback",
                choice.getBackground() instanceof RippleDrawable);
        assertNotEquals(border(choice, android.R.attr.state_enabled),
                border(choice, android.R.attr.state_enabled, android.R.attr.state_selected));
    }

    private int border(View view, int... state) {
        return border(view.getBackground(), state);
    }

    private int border(Drawable drawable, int... state) {
        int width = dp(160);
        int height = dp(48);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(ScreenColors.DEFAULT.dialog);
        drawable.setState(state);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        int pixel = bitmap.getPixel(width / 2, dp(1) / 2);
        bitmap.recycle();
        return pixel;
    }

    private int dp(int value) {
        return Math.round(value * controller.get().getResources().getDisplayMetrics().density);
    }

    @Test public void realDisabledRowsKeepTheirStateAndVisiblyDifferentControls() {
        Activity activity = controller.get();
        SwitchPreference preference = new SwitchPreference(activity);
        preference.setTitle("Hide ads");
        preference.setSummary("Hides sponsored posts, reels and stories.");
        preference.setChecked(true);
        View enabled = preference.getView(null, new FrameLayout(activity));
        ScreenColors.row(enabled, preference);
        TextView enabledTitle = enabled.findViewById(android.R.id.title);
        TextView enabledSummary = enabled.findViewById(android.R.id.summary);
        Switch enabledSwitch = enabled.findViewById(android.R.id.switch_widget);
        assertNotNull(enabledSwitch);
        assertTrue(enabledSwitch.getThumbDrawable() instanceof GradientDrawable);
        assertTrue(enabledSwitch.getTrackDrawable() instanceof GradientDrawable);
        GradientDrawable thumb = (GradientDrawable) enabledSwitch.getThumbDrawable();
        GradientDrawable track = (GradientDrawable) enabledSwitch.getTrackDrawable();
        assertEquals("The native switch thumb stays rectangular", GradientDrawable.RECTANGLE, thumb.getShape());
        assertEquals("The switch track must not become a capsule", GradientDrawable.RECTANGLE, track.getShape());
        assertEquals(dp(4), thumb.getCornerRadius(), 0.01f);
        assertEquals(dp(6), track.getCornerRadius(), 0.01f);
        assertEquals(dp(20), thumb.getIntrinsicWidth());
        assertEquals(dp(20), thumb.getIntrinsicHeight());
        assertEquals(dp(40), track.getIntrinsicWidth());
        assertEquals(dp(24), track.getIntrinsicHeight());
        contrast("actual enabled title", color(enabledTitle.getTextColors(), true, false), ScreenColors.DEFAULT.card, 4.5);
        contrast("actual enabled summary", color(enabledSummary.getTextColors(), true, false), ScreenColors.DEFAULT.card, 4.5);
        int activeTrack = color(enabledSwitch.getTrackTintList(), true, true);
        preference.setEnabled(false);
        View disabled = preference.getView(null, new FrameLayout(activity));
        ScreenColors.row(disabled, preference);
        Switch disabledSwitch = disabled.findViewById(android.R.id.switch_widget);
        TextView disabledTitle = disabled.findViewById(android.R.id.title);
        assertFalse(disabled.isEnabled());
        assertFalse(disabledSwitch.isEnabled());
        assertTrue("Disabling a control must preserve its saved choice", disabledSwitch.isChecked());
        assertNotEquals("An unavailable control must look different", activeTrack,
                color(disabledSwitch.getTrackTintList(), false, true));
        assertTrue("Unavailable text must keep the disabled visual state",
                Color.alpha(color(disabledTitle.getTextColors(), false, false))
                        < Color.alpha(color(enabledTitle.getTextColors(), true, false)));
    }

    @Test public void stylingPreservesEachCheckedAndUncheckedItemAndItsNativeCallback() {
        List<String> changes = new ArrayList<>();
        AlertDialog alert = new AlertDialog.Builder(controller.get())
                .setTitle("Locked chats")
                .setMultiChoiceItems(new String[]{"First test chat", "Second test chat", "Third test chat", "Fourth test chat"},
                        new boolean[]{true, false, true, false},
                        (dialog, index, checked) -> changes.add(index + ":" + checked))
                .setPositiveButton("Done", null).show();
        opened.add(alert);
        ListView list = alert.getListView();
        layAlert(alert);
        assertTrue("The native initial selection must be bound before styling", list.isItemChecked(0));
        assertTrue(list.isItemChecked(2));
        ScreenColors.dialog(alert);
        ScreenColors.dialog(alert);
        layAlert(alert);
        assertTrue(list.isItemChecked(0));
        assertFalse(list.isItemChecked(1));
        assertTrue(list.isItemChecked(2));
        assertFalse(list.isItemChecked(3));
        assertEquals(2, list.getCheckedItemCount());
        tap(list, 1);
        assertEquals(Arrays.asList("1:true"), changes);
        assertTrue(list.isItemChecked(1));
        assertTrue(list.isItemChecked(2));
        tap(list, 0);
        assertEquals(Arrays.asList("1:true", "0:false"), changes);
        assertFalse(list.isItemChecked(0));
        assertTrue("A multi-choice dialog must stay open", alert.isShowing());
    }

    @Test public void singleChoiceKeepsOnlyTheSavedSelectionAndSelectsThroughTheOriginalListener() {
        List<Integer> changes = new ArrayList<>();
        AlertDialog alert = new AlertDialog.Builder(controller.get())
                .setTitle("Download quality")
                .setSingleChoiceItems(new String[]{"Best", "720p", "Smallest"}, 1,
                        (dialog, index) -> changes.add(index))
                .setNegativeButton("Cancel", null).show();
        opened.add(alert);
        ScreenColors.dialog(alert);
        layAlert(alert);
        ListView list = alert.getListView();
        assertFalse(list.isItemChecked(0));
        assertTrue(list.isItemChecked(1));
        assertFalse(list.isItemChecked(2));
        tap(list, 2);
        assertEquals(Arrays.asList(2), changes);
        assertFalse(list.isItemChecked(1));
        assertTrue(list.isItemChecked(2));
        assertEquals(1, list.getCheckedItemCount());
    }

    /** The tab picker's rows keep the page's colours once the dialog is styled, and Reset keeps it open (#207). */
    @Test public void theTabPickerKeepsItsColoursAndResetKeepsItOpen() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REELS_TAB);
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        Preference row = page.findPreference(Settings.TAB_ORDER.key);
        assertNotNull(row);
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
        ShadowLooper.idleMainLooper();
        AlertDialog alert = ShadowAlertDialog.getLatestAlertDialog();
        opened.add(alert);
        ScreenColors.dialog(alert);
        android.widget.LinearLayout list = (android.widget.LinearLayout)
                alert.getWindow().getDecorView().findViewWithTag(TabPicker.HOME).getParent();
        ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
        TabPicker.moveUp(list, 3);
        assertEquals(TabPicker.MESSAGES, list.getChildAt(2).getTag());
        alert.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(alert.isShowing());
        assertEquals(TabPicker.REELS, list.getChildAt(2).getTag());
        for (int at = 0; at < list.getChildCount(); at++) {
            android.widget.CheckBox box = TabPicker.box(list.getChildAt(at));
            assertEquals(box.isEnabled() ? colors.title : colors.summary, box.getCurrentTextColor());
        }
        assertEquals("", Settings.TAB_ORDER.get());
    }

    @Test public void theLastQualityStaysReachableAndEveryVisibleRadioLabelFits() throws Exception {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.REEL_DOWNLOAD);
        HushgramPreferenceFragment page = new HushgramPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        Utils.awaitBackgroundTasksForTests();
        HushgramPreferenceFragment.QualityRow row = (HushgramPreferenceFragment.QualityRow)
                page.findPreference(Settings.DOWNLOAD_QUALITY.key);
        assertNotNull(row);
        for (float size : new float[]{1f, 1.3f, 2f}) {
            RuntimeEnvironment.setFontScale(size);
            row.showDialog(null);
            AlertDialog alert = (AlertDialog) row.getDialog();
            opened.add(alert);
            layAlert(alert);
            ListView choices = alert.getListView();
            assertRadioLabelsFit(choices);
            int smallest = row.findIndexOfValue(DownloadQuality.SMALLEST.name());
            assertEquals("Every download quality is offered", DownloadQuality.values().length, choices.getCount());
            assertEquals("Smallest is the last quality", choices.getCount() - 1, smallest);
            if (size == 1f) {
                assertEquals("Normal text shows the first quality without scrolling", 0, choices.getFirstVisiblePosition());
                assertEquals("Normal text shows all six qualities without scrolling", smallest, choices.getLastVisiblePosition());
                View last = choices.getChildAt(choices.getChildCount() - 1);
                assertTrue("Smallest is fully inside the initial list viewport",
                        last.getBottom() <= choices.getHeight() - choices.getPaddingBottom());
            } else {
                choices.setSelectionFromTop(smallest, 0);
                layAlert(alert);
            }
            assertTrue("The last quality must remain reachable", choices.getLastVisiblePosition() >= smallest);
            assertRadioLabelsFit(choices);
            capture(alert.getWindow().getDecorView(), "download-quality-" + Math.round(size * 100));
            tap(choices, smallest);
            assertFalse(alert.isShowing());
            assertEquals(DownloadQuality.SMALLEST, Settings.DOWNLOAD_QUALITY.savedValue());
        }
    }

    private static void assertRadioLabelsFit(ListView choices) {
        for (int i = 0; i < choices.getChildCount(); i++) {
            View child = choices.getChildAt(i);
            assertTrue("A native radio label must remain a TextView", child instanceof TextView);
            TextView label = (TextView) child;
            assertNotNull(label.getLayout());
            assertTrue("Radio label is clipped: " + label.getText(),
                    label.getHeight() - label.getCompoundPaddingTop() - label.getCompoundPaddingBottom()
                            >= label.getLayout().getHeight());
            for (int line = 0; line < label.getLineCount(); line++) {
                assertEquals("A quality label must not lose text", 0, label.getLayout().getEllipsisCount(line));
            }
        }
    }

    @Test public void threeDialogActionsFitAtLargerText() throws Exception { checkActions(1.3f, false); }
    @Test public void threeDialogActionsFitAtTwiceTheTextSize() throws Exception { checkActions(2f, false); }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h891dp-xhdpi")
    public void mirroredThreeDialogActionsFitAtLargerText() throws Exception { checkActions(1.3f, true); }

    @Test @Config(qualifiers = "ar-rEG-ldrtl-w411dp-h891dp-xhdpi")
    public void mirroredThreeDialogActionsFitAtTwiceTheTextSize() throws Exception { checkActions(2f, true); }

    private void checkActions(float fontScale, boolean rtl) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        AlertDialog alert = new AlertDialog.Builder(controller.get())
                .setTitle(rtl ? "اختيار إعدادات التنزيل" : "Choose download settings")
                .setMessage(rtl ? "ستُطبّق اختياراتك على التنزيلات القادمة." : "Your choices apply to the next download.")
                .setPositiveButton(rtl ? "حفظ التغييرات" : "Save changes", null)
                .setNegativeButton(rtl ? "إلغاء" : "Cancel", null)
                .setNeutralButton(rtl ? "استعادة الإعدادات" : "Restore defaults", null).show();
        opened.add(alert);
        ScreenColors.dialog(alert);
        ShadowLooper.idleMainLooper();
        ViewGroup root = (ViewGroup) alert.getWindow().getDecorView();
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        float density = root.getResources().getDisplayMetrics().density;
        root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(379 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(819 * density), View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        capture(root, (rtl ? "rtl-" : "ltr-") + Math.round(fontScale * 100));
        List<Rect> actionBounds = new ArrayList<>();
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = alert.getButton(which);
            assertNotNull(button);
            assertEquals(View.VISIBLE, button.getVisibility());
            assertTrue("Actions retain their touch height", button.getHeight() >= Math.round(48 * density));
            assertTrue("Action text is fully visible: " + button.getText(),
                    button.getHeight() - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom()
                            >= button.getLayout().getHeight());
            assertEquals("The action label ends inside its layout", button.getText().length(),
                    button.getLayout().getLineEnd(button.getLineCount() - 1));
            for (int line = 0; line < button.getLineCount(); line++) {
                assertEquals("The action label is not ellipsized", 0, button.getLayout().getEllipsisCount(line));
            }
            Rect bounds = new Rect(0, 0, button.getWidth(), button.getHeight());
            root.offsetDescendantRectToMyCoords(button, bounds);
            assertTrue("An action is outside the panel: " + button.getText() + " " + bounds,
                    bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.getWidth() && bounds.bottom <= root.getHeight());
            for (Rect previous : actionBounds) assertFalse("Dialog actions overlap", Rect.intersects(previous, bounds));
            actionBounds.add(bounds);
        }
    }

    private static void tap(ListView list, int position) {
        ListAdapter adapter = list.getAdapter();
        assertTrue(list.performItemClick(adapter.getView(position, null, list), position, adapter.getItemId(position)));
        ShadowLooper.idleMainLooper();
    }

    private static void layAlert(AlertDialog alert) {
        ShadowLooper.idleMainLooper();
        View root = alert.getWindow().getDecorView();
        float density = root.getResources().getDisplayMetrics().density;
        root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(379 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(819 * density), View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        ShadowLooper.idleMainLooper();
    }

    private static int color(ColorStateList colors, boolean enabled, boolean checked) {
        return colors.getColorForState(new int[]{enabled ? android.R.attr.state_enabled : -android.R.attr.state_enabled,
                checked ? android.R.attr.state_checked : -android.R.attr.state_checked}, Color.TRANSPARENT);
    }

    private static void contrast(String label, int foreground, int background, double minimum) {
        double a = luminance(foreground), b = luminance(background);
        double ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
        assertTrue(String.format(Locale.ROOT, "%s contrast %.2f:1 is below %.1f:1", label, ratio, minimum), ratio >= minimum);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color));
    }

    private static double linear(int component) {
        double channel = component / 255.0;
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static void capture(View root, String name) throws Exception {
        String output = System.getenv("HUSHGRAM_UI_CAPTURES");
        if (output == null || output.isEmpty()) return;
        File folder = new File(output, "dialog-accessibility");
        assertTrue(folder.mkdirs() || folder.isDirectory());
        Bitmap pixels = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(pixels));
            try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
        } finally { pixels.recycle(); }
    }
}
