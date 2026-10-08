/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;

/**
 * The HushGram screen: a full screen, black dialog with a title bar and the preference list. It is
 * a framework dialog fragment, so it needs no activity of its own and Back closes it.
 */
@SuppressWarnings("deprecation") // Framework fragments are what the shared preference code builds on.
public final class SettingsDialog extends DialogFragment {
    /**
     * The preference list's container. Fixed, because the child manager saves the page with this
     * id and puts it back into a view with the same id after rotation or process recreation.
     * Outside both the generated-id range and aapt's 0x7f ids, and nothing else in this dialog
     * carries an id.
     */
    static final int CONTAINER_ID = 0x48474301;
    private static final String PAGE_FAILED = "hushgram.page_failed";
    private boolean failed;
    private boolean loading;
    private Button retry;
    private TextView heading;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        failed = savedInstanceState != null && savedInstanceState.getBoolean(PAGE_FAILED);
        try {
            super.onCreate(savedInstanceState);
        } catch (Exception failure) {
            // Fragment.onCreate restores child constructors before our view exists.
            // Finish the dialog's own initialization without retrying the failed saved child.
            super.onCreate(null);
            failed = true;
            Logger.printException(() -> "Could not restore the preference list", failure);
        }
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Material_NoActionBar);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = new Dialog(getActivity(), getTheme()) {
            // Back on a category's page goes to the list of categories first. Android 13 and up
            // route the gesture here too, through the dialog's own back callback.
            @Override
            @SuppressWarnings("deprecation")
            public void onBackPressed() {
                if (closeCategory()) return;
                super.onBackPressed();
            }
        };
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // With three-button navigation Android lays a grey scrim under the buttons; the
            // screen is black edge to edge, so the scrim only shows as a grey band. Android 9 has
            // no scrim to turn off.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return dialog;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        ScreenColors palette = ScreenColors.DEFAULT;
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Start and end, not left and right: in a right-to-left language the bar is mirrored.
        bar.setPaddingRelative(dp(4), dp(12), dp(16), dp(12));

        ImageButton back = new ImageButton(getContext());
        android.graphics.drawable.Drawable arrow = SettingsIcons.icon(getContext(), SettingsIcons.BACK, palette.title);
        arrow.setBounds(0, 0, dp(24), dp(24));
        arrow.setLayoutDirection(rightToLeft() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        back.setImageDrawable(arrow);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setMinimumWidth(dp(48));
        back.setMinimumHeight(dp(48));
        back.setContentDescription(L10n.t(getContext(), "Back"));
        // A screen reader calls it a button, as it would the back arrow of any other screen.
        back.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        back.setOnClickListener(v -> {
            if (closeCategory()) return;
            SettingsEntry.onClosedByUser();
            dismissAllowingStateLoss();
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(getContext());
        heading = title;
        title.setText("HushGram");
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setPaddingRelative(dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        FrameLayout container = new FrameLayout(getContext());
        container.setId(CONTAINER_ID);
        root.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Instagram targets a recent API, so its windows are edge to edge: keep the bars off the
        // content.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                // Android 9 and 10 report the bars through the calls Android 11 replaced.
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        // A newly opened screen must not steal focus and raise the keyboard.
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        return root;
    }

    /** Shows [title] in the bar while a category's page is open, and HushGram again for null. */
    void showTitle(@androidx.annotation.Nullable CharSequence title) {
        TextView bar = heading;
        if (bar != null) bar.setText(title == null ? "HushGram" : title);
    }

    /** Closes the settings page's open category, if one is open. Answers whether one was. */
    private boolean closeCategory() {
        Fragment page = getChildFragmentManager().findFragmentById(CONTAINER_ID);
        return page instanceof HushgramPreferenceFragment && ((HushgramPreferenceFragment) page).closeCategory();
    }

    @Override
    public void onCancel(android.content.DialogInterface dialog) {
        // Back key or a tap outside: the person closed it.
        SettingsEntry.onClosedByUser();
        super.onCancel(dialog);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Keep the restored child's identity and pending activity result, but defer its view
        // until openPage can catch construction failures. Framework restoration would otherwise
        // build that view after this callback and before onResume, outside the recovery boundary.
        FragmentManager manager = getChildFragmentManager();
        Fragment restored = manager.findFragmentById(CONTAINER_ID);
        if (restored != null && !restored.isDetached()) manager.beginTransaction().detach(restored).commitNow();
        if (failed) showFailure(view);
    }

    @Override
    public void onResume() {
        super.onResume();
        // At onViewCreated the child manager is only CREATED. Its view construction would
        // happen later, outside our catch. At resume commitNow also constructs the child view.
        if (failed) {
            if (retry != null) retry.setEnabled(canOpenPage(getView()));
        } else {
            openPage(getView());
        }
    }

    @Override
    public void onPause() {
        if (retry != null) retry.setEnabled(false);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(PAGE_FAILED, failed);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        retry = null;
        heading = null;
        super.onDestroyView();
    }

    private boolean canOpenPage(View owner) {
        return owner != null && owner == getView() && isAdded() && isResumed()
                && !isRemoving() && !isDetached() && getActivity() != null
                && !getActivity().isFinishing() && !getActivity().isDestroyed()
                && getDialog() != null && getDialog().isShowing()
                && !getFragmentManager().isStateSaved()
                && !getChildFragmentManager().isStateSaved()
                && !getChildFragmentManager().isDestroyed();
    }

    private void openPage(View owner) {
        if (loading || !canOpenPage(owner)) return;
        loading = true;
        boolean recovering = failed;
        FrameLayout container = owner.findViewById(CONTAINER_ID);
        FragmentManager manager = getChildFragmentManager();
        try {
            Fragment child = manager.findFragmentById(CONTAINER_ID);
            if (failed && child != null) {
                manager.beginTransaction().remove(child).commitNow();
                child = null;
            }
            if (child == null) {
                container.removeAllViews();
                manager.beginTransaction()
                        .replace(CONTAINER_ID, new HushgramPreferenceFragment())
                        .commitNow();
            } else if (child.isDetached()) {
                manager.beginTransaction().attach(child).commitNow();
            }
            failed = false;
            retry = null;
            if (recovering) {
                View list = container.findViewById(android.R.id.list);
                if (list != null) list.requestFocus();
                TextView pageHeading = heading;
                owner.post(() -> {
                    if (canOpenPage(owner) && !failed && heading == pageHeading) {
                        pageHeading.performAccessibilityAction(
                                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
                    }
                });
            }
        } catch (Exception ex) {
            failed = true;
            Logger.printException(() -> "Could not show the preference list", ex);
            try {
                Fragment partial = manager.findFragmentById(CONTAINER_ID);
                if (partial != null) manager.beginTransaction().remove(partial).commitNow();
            } catch (Exception cleanup) {
                Logger.printException(() -> "Could not remove the failed preference list", cleanup);
            }
            // A throw from onViewCreated can leave a view attached before the fragment has
            // reached ACTIVITY_CREATED, so removal alone doesn't necessarily remove that view.
            container.removeAllViews();
            showFailure(owner);
        } finally {
            loading = false;
        }
    }

    private void showFailure(View owner) {
        ScreenColors palette = ScreenColors.DEFAULT;
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPaddingRelative(dp(24), dp(24), dp(24), dp(24));
        TextView title = new TextView(getContext());
        title.setText(L10n.t(getContext(), "Settings couldn't open"));
        title.setTextColor(palette.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setAccessibilityHeading(true);
        content.addView(title);
        TextView explanation = new TextView(getContext());
        explanation.setText(L10n.t(getContext(), "Try again, or go back to Instagram."));
        explanation.setTextColor(palette.summary);
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        explanation.setPaddingRelative(0, dp(16), 0, dp(16));
        content.addView(explanation);
        Button button = new Button(getContext());
        button.setText(L10n.t(getContext(), "Retry"));
        button.setAllCaps(false);
        button.setTextColor(palette.onAccent);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        button.setMinHeight(dp(48));
        button.setEnabled(canOpenPage(owner));
        button.setOnClickListener(v -> {
            if (retry == button && failed) openPage(owner);
        });
        retry = button;
        content.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.addView(content);
        scroll.setAccessibilityPaneTitle(title.getText());
        ((FrameLayout) owner.findViewById(CONTAINER_ID)).addView(scroll,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        button.requestFocus();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** Whether the phone's language reads right to left, so the way back points right. */
    private boolean rightToLeft() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }
}
