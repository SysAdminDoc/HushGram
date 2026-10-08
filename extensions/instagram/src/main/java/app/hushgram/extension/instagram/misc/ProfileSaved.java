/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Saved on your profile" patch.
 *
 * <p>Your own profile shows a row of tabs under the buttons: posts, reels, tagged. This adds one more at
 * the end, a bookmark. Tapping it does what you'd do by hand: opens the menu at the top right and taps
 * Saved in it. Instagram's own code opens Saved, so nothing in its bytecode is touched, and a build that
 * moves the menu's rows still works as long as a row's accessibility label says Saved. Every view is
 * found by the name Instagram gives it, and the profile counts as yours when it has the Create New button
 * at the top left, which other people's profiles don't.
 *
 * <p>While the menu opens and the row is tapped the screen is held invisible for a moment, so the menu
 * doesn't flash by. It's shown again whether or not the row was found, and if it wasn't, the menu is
 * left open for you to tap Saved yourself.
 */
public final class ProfileSaved {
    static final String TAB_LAYOUT = "profile_tab_layout";
    static final String CREATE_BUTTON = "profile_header_create_button";
    static final String ACTION_BUTTONS = "right_action_bar_buttons";

    /** Marks the view added here, so it isn't added twice. */
    static final String TAG = "hushgram_saved_tab";

    /** How often the layout is looked at for a profile, at most. */
    private static final long LOOK_EVERY_MS = 250;
    /** How long the menu is given to appear, and how long the screen is held at most. */
    private static final long WAIT_FOR_MENU_MS = 2500;
    private static final long HOLD_MAX_MS = 3500;
    private static final long POLL_MS = 50;

    /** What Instagram's own English and the phone's language call the row. */
    static final String[] SAVED_LABELS = {"Saved"};

    private static volatile boolean registered;
    private static final WeakHashMap<Activity, Boolean> watched = new WeakHashMap<>();

    private ProfileSaved() {
    }

    /** Called as the application starts. Does nothing in a build that doesn't carry the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.savedOnProfile()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "start", failure);
        }
    }

    static void watch(Activity activity) {
        try {
            if (!Utils.settingsReady() || !Settings.SAVED_ON_PROFILE.get()) return;
            synchronized (watched) {
                if (watched.containsKey(activity)) return;
                watched.put(activity, Boolean.TRUE);
            }
            View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                private long last;

                @Override public void onGlobalLayout() {
                    long now = SystemClock.uptimeMillis();
                    if (now - last < LOOK_EVERY_MS) return;
                    last = now;
                    ensure(activity);
                }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "watch", failure);
        }
    }

    /** Adds the bookmark to your profile's tabs if it's showing and doesn't have one yet. */
    static void ensure(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            View layout = GlassTabBar.find(decor, TAB_LAYOUT);
            if (!(layout instanceof ViewGroup) || !layout.isShown()) return;
            View create = GlassTabBar.find(decor, CREATE_BUTTON);
            if (create == null || !create.isShown()) return;
            ViewGroup tabs = (ViewGroup) layout;
            if (tabs.getChildCount() == 0 || !(tabs.getChildAt(0) instanceof LinearLayout)) return;
            LinearLayout strip = (LinearLayout) tabs.getChildAt(0);
            if (strip.getOrientation() != LinearLayout.HORIZONTAL) return;
            for (int i = 0; i < strip.getChildCount(); i++) {
                if (TAG.equals(strip.getChildAt(i).getTag())) return;
            }
            SavedTab saved = new SavedTab(activity);
            saved.setOnClickListener(view -> open(activity));
            strip.addView(saved, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            HookStatus.invoked(FamilyNames.SAVED_ON_PROFILE);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "tab", failure);
        }
    }

    /** Opens the menu, then taps Saved in it. */
    static void open(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        try {
            View menu = menuButton(decor);
            if (menu == null) return;
            Handler handler = new Handler(Looper.getMainLooper());
            long started = SystemClock.uptimeMillis();
            decor.setAlpha(0f);
            handler.postDelayed(() -> decor.setAlpha(1f), HOLD_MAX_MS);
            menu.performClick();
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        View row = findRow(decor);
                        if (row != null) {
                            row.performClick();
                            handler.postDelayed(() -> decor.setAlpha(1f), 400);
                            return;
                        }
                        if (SystemClock.uptimeMillis() - started < WAIT_FOR_MENU_MS) {
                            handler.postDelayed(this, POLL_MS);
                            return;
                        }
                    } catch (Throwable failure) {
                        HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "open", failure);
                    }
                    decor.setAlpha(1f);
                }
            }, POLL_MS);
        } catch (Throwable failure) {
            decor.setAlpha(1f);
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "menu", failure);
        }
    }

    /** The last clickable button in the top right of the profile, the menu. */
    @Nullable
    static View menuButton(View decor) {
        View buttons = GlassTabBar.find(decor, ACTION_BUTTONS);
        if (!(buttons instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) buttons;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isClickable()) return child;
        }
        return null;
    }

    /** The menu's Saved row: a clickable view whose label says Saved, or null while the menu isn't up yet. */
    @Nullable
    static View findRow(View root) {
        List<View> rows = new ArrayList<>();
        collect(root, rows);
        for (View row : rows) {
            if (isSavedLabel(String.valueOf(row.getContentDescription()))) return row;
        }
        return null;
    }

    static boolean isSavedLabel(@Nullable String label) {
        if (label == null) return false;
        String trimmed = label.trim();
        for (String saved : SAVED_LABELS) {
            if (saved.equalsIgnoreCase(trimmed)) return true;
        }
        return trimmed.equalsIgnoreCase(L10n.t("Saved"));
    }

    /** Every shown, clickable view with a label, top to bottom. */
    private static void collect(View view, List<View> into) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view.isClickable() && view.getContentDescription() != null && view.getContentDescription().length() > 0) {
            into.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }

    /** The bookmark: an outline drawn here, so the app gets no resource, grey like Instagram's idle tabs. */
    private static final class SavedTab extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float density;

        SavedTab(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setTag(TAG);
            setClickable(true);
            setContentDescription(L10n.t("Saved"));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            paint.setColor(night == Configuration.UI_MODE_NIGHT_YES ? 0xb3ffffff : 0x99000000);
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            float width = 18 * density;
            float height = 22 * density;
            float left = (getWidth() - width) / 2f;
            float top = (getHeight() - height) / 2f;
            paint.setStrokeWidth(2f * density);
            path.reset();
            path.moveTo(left, top);
            path.lineTo(left + width, top);
            path.lineTo(left + width, top + height);
            path.lineTo(left + width / 2f, top + height - 6 * density);
            path.lineTo(left, top + height);
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(@NonNull Activity activity) {
            watch(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
