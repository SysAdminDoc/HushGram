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
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
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
 * the end, a bookmark. Instagram's tab layout takes every view in its row to be one of its own tabs, so the
 * bookmark isn't put in the row. Each of Instagram's tabs is given a quarter of the row's width instead, the
 * bookmark is drawn in the quarter left, on the layout's overlay, and the layout's touch listener answers for
 * that quarter.
 *
 * <p>Tapping it does what you'd do by hand: opens the menu at the top right and taps Saved in it.
 * Instagram's own code opens Saved, so nothing in its bytecode is touched. The menu is a Jetpack Compose
 * screen, which describes its rows as accessibility nodes whether or not a screen reader is on, so the row
 * is found by the label it carries, Saved, and a build that moves it still works. Every view is found by the
 * name Instagram gives it, and the profile counts as yours when it has the Create New button at the top
 * left, which other people's profiles don't.
 *
 * <p>While the menu opens and the row is tapped the screen is held invisible, so the menu doesn't flash by.
 * It's shown again whether or not the row was found, and if it wasn't, the menu is left open for you to tap
 * Saved yourself.
 */
public final class ProfileSaved {
    static final String TAB_LAYOUT = "profile_tab_layout";
    static final String CREATE_BUTTON = "profile_header_create_button";
    static final String ACTION_BUTTONS = "right_action_bar_buttons";

    /** How often the layout is looked at for a profile, at most. */
    private static final long LOOK_EVERY_MS = 250;
    /** How long the menu is given to fill in, and how long the screen is held at most. */
    private static final long WAIT_FOR_MENU_MS = 8000;
    private static final long HOLD_MAX_MS = 9000;
    private static final long POLL_MS = 50;
    /** How many accessibility nodes are asked for before giving up on a screen. */
    private static final int NODE_LIMIT = 600;

    private static final boolean DIAGNOSE = false;
    private static volatile boolean registered;
    private static final WeakHashMap<Activity, Boolean> watched = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Row> rows = new WeakHashMap<>();

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
            synchronized (watched) {
                if (watched.containsKey(activity)) return;
                watched.put(activity, Boolean.TRUE);
            }
            View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                private long last;
                private boolean pending;

                @Override public void onGlobalLayout() {
                    long now = SystemClock.uptimeMillis();
                    if (now - last < LOOK_EVERY_MS) {
                        // The last layout of a burst is the one that matters, so look once more after it.
                        if (!pending) {
                            pending = true;
                            decor.postDelayed(() -> {
                                pending = false;
                                last = SystemClock.uptimeMillis();
                                ensure(activity);
                            }, LOOK_EVERY_MS);
                        }
                        return;
                    }
                    last = now;
                    ensure(activity);
                }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "watch", failure);
        }
    }

    /** Makes room for the bookmark on your profile's tab row, if it's showing, and keeps it that way. */
    static void ensure(Activity activity) {
        try {
            if (!Utils.settingsReady() || !Settings.SAVED_ON_PROFILE.get()) return;
            View decor = activity.getWindow().getDecorView();
            View found = GlassTabBar.find(decor, TAB_LAYOUT);
            if (!(found instanceof ViewGroup) || !found.isShown() || found.getWidth() <= 0) return;
            View create = GlassTabBar.find(decor, CREATE_BUTTON);
            if (create == null || !create.isShown()) return;
            ViewGroup tabs = (ViewGroup) found;
            Row row;
            synchronized (rows) {
                row = rows.get(tabs);
                if (row == null) {
                    row = new Row(activity, tabs);
                    rows.put(tabs, row);
                    HookStatus.invoked(FamilyNames.SAVED_ON_PROFILE);
                }
            }
            row.keep();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "tab", failure);
            why("threw " + failure);
        }
    }

    static void why(String what) {
        if (DIAGNOSE) Log.w("HushSaved", what);
    }

    /** One profile's tab row: the room made for the bookmark, the bookmark, and the touch on it. */
    private static final class Row implements View.OnTouchListener {
        private final Activity activity;
        private final ViewGroup tabs;
        private final Icon icon;
        private boolean down;

        Row(Activity activity, ViewGroup tabs) {
            this.activity = activity;
            this.tabs = tabs;
            this.icon = new Icon(activity);
            tabs.getOverlay().add(icon);
            tabs.setOnTouchListener(this);
        }

        private int quarter() {
            return tabs.getWidth() / 4;
        }

        /** Puts each of Instagram's tabs back to a quarter of the row after Instagram lays it out again. */
        void keep() {
            int quarter = quarter();
            View strip = tabs.getChildCount() > 0 ? tabs.getChildAt(0) : null;
            if (strip instanceof LinearLayout) {
                LinearLayout group = (LinearLayout) strip;
                boolean changed = false;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View tab = group.getChildAt(i);
                    ViewGroup.LayoutParams params = tab.getLayoutParams();
                    if (params == null || params.width == quarter) continue;
                    if (params instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) params).weight = 0f;
                    params.width = quarter;
                    tab.setLayoutParams(params);
                    changed = true;
                }
                if (changed) group.requestLayout();
                // Instagram centres the tabs in the layout; start them at the left edge so the bookmark has the end.
                int gravity = (group.getGravity() & Gravity.VERTICAL_GRAVITY_MASK) | Gravity.START;
                if (group.getGravity() != gravity) group.setGravity(gravity);
            }
            icon.setBounds(tabs.getWidth() - quarter, 0, tabs.getWidth(), tabs.getHeight());
        }

        private boolean inBookmark(MotionEvent event) {
            return event.getX() >= tabs.getWidth() - quarter() && event.getX() <= tabs.getWidth();
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        down = inBookmark(event);
                        return down;
                    case MotionEvent.ACTION_UP:
                        if (down && inBookmark(event)) {
                            down = false;
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            open(activity);
                            return true;
                        }
                        down = false;
                        return false;
                    case MotionEvent.ACTION_CANCEL:
                        boolean was = down;
                        down = false;
                        return was;
                    default:
                        return down;
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.SAVED_ON_PROFILE, "touch", failure);
                return false;
            }
        }
    }

    /** Opens the menu, then taps Saved in it as soon as the menu has filled in. */
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
                        if (tapSaved(decor)) {
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

    /** Taps the menu's Saved row if it's up. Returns whether something was tapped. */
    static boolean tapSaved(View root) {
        if (root.getVisibility() != View.VISIBLE) return false;
        if (root.getClass().getName().startsWith("androidx.compose.") && tapNode(root)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (tapSaved(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /**
     * Walks a Compose view's accessibility tree from its top, finding each node's children by the ids the node
     * holds, and clicks the node that says Saved, or the closest parent that does something when clicked.
     */
    static boolean tapNode(View compose) {
        try {
            AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
            if (provider == null) return false;
            Method childId = AccessibilityNodeInfo.class.getDeclaredMethod("getChildId", int.class);
            childId.setAccessible(true);
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            queue.add(new int[]{AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityNodeProvider.HOST_VIEW_ID});
            HashMap<Integer, Integer> parents = new HashMap<>();
            int visited = 0;
            while (!queue.isEmpty() && visited++ < NODE_LIMIT) {
                int[] at = queue.poll();
                AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(at[0]);
                if (node == null) continue;
                parents.put(at[0], at[1]);
                CharSequence label = node.getText() != null ? node.getText() : node.getContentDescription();
                if (label != null && isSavedLabel(label.toString())) {
                    for (int id = at[0]; ; ) {
                        if (provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null)) return true;
                        Integer up = parents.get(id);
                        if (up == null || up == id || up == AccessibilityNodeProvider.HOST_VIEW_ID) break;
                        id = up;
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    long child = (Long) childId.invoke(node, i);
                    queue.add(new int[]{(int) (child >>> 32), at[0]});
                }
            }
        } catch (Throwable failure) {
            why("compose walk failed: " + failure);
        }
        return false;
    }

    /** Whether a label is the menu's Saved row, in Instagram's English or the phone's language. */
    static boolean isSavedLabel(@Nullable String label) {
        if (label == null) return false;
        String trimmed = label.trim();
        return trimmed.equalsIgnoreCase("Saved") || trimmed.equalsIgnoreCase(L10n.t("Saved"));
    }

    /** The bookmark: an outline drawn here, so the app gets no resource, grey like Instagram's idle tabs. */
    private static final class Icon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float density;

        Icon(Context context) {
            density = context.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            paint.setColor(night == Configuration.UI_MODE_NIGHT_YES ? 0xb3ffffff : 0x99000000);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            float width = 18 * density;
            float height = 22 * density;
            Rect bounds = getBounds();
            float left = bounds.left + (bounds.width() - width) / 2f;
            float top = bounds.top + (bounds.height() - height) / 2f;
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

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
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
