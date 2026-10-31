/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.reels.HomeHeader;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Profile extras button: a small button first in the row on the right of a profile's top bar, where
 * the three dots are, that lists HushGram's profile actions (Save profile picture, View profile
 * picture, Copy username and Copy bio, whichever are switched on) in a small menu. The rows in the
 * three-dot menu stay as they are ({@link ProfilePicture}); this is a second way to reach them (#234).
 *
 * <p>The patch calls {@link #bound} first thing as the bar fills its rows, and {@link #screen} right
 * after each bind with the profile screen, which holds the account. Instagram empties both rows every time it
 * binds, so the button is put in again after each, posted behind the bind. With the switch off,
 * HushGram paused, no account yet or nothing switched on to list, no button is added and one left
 * from before is taken out. Everything fails open to Instagram's own layout.
 */
public final class ProfileExtras {
    private ProfileExtras() {
    }

    static final String ROUTE = FamilyNames.PROFILE_PICTURE;
    static final String PLACED = "profile extras button placed";
    static final String NOT_PLACED = "profile extras button not placed, no row to put it in";
    static final String EMPTY = "profile extras menu empty";

    /** What the bar is known by: its right row, held weakly since the row holds the bar, and the account it shows. */
    private static final class State {
        WeakReference<ViewGroup> row = new WeakReference<>(null);
        Object user;
    }

    private static final WeakHashMap<View, State> BARS = new WeakHashMap<>();

    /** Where the menu's rows come from: the same rows the three-dot menu gets. A test swaps it. */
    interface Source {
        List<ProfilePicture.Entry> entries(Object user, Context context);
    }

    static Source source = ProfilePicture::entries;

    private static State stateOf(View bar) {
        synchronized (BARS) {
            State state = BARS.get(bar);
            if (state == null) {
                state = new State();
                BARS.put(bar, state);
            }
            return state;
        }
    }

    /**
     * Injected first thing in the bar's bind, with the bar and its right row. Queues the placement
     * behind the bind, which empties the row and fills it again before the queued work runs. Never
     * throws, and does nothing to the bar itself.
     */
    public static void bound(View bar, View row) {
        try {
            HookStatus.invoked(ROUTE);
            if (bar == null || !(row instanceof ViewGroup)) return;
            stateOf(bar).row = new WeakReference<>((ViewGroup) row);
            bar.post(() -> place(bar));
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras bind", failure);
        }
    }

    /** Reads the account off the profile screen. A test swaps it. */
    interface Account {
        Object of(Object screen);
    }

    static Account account = ProfileExtras::accountOf;

    private static final String SCREEN_MODEL = "com.instagram.profile.fragment.UserDetailViewModel";
    private static final String USER_TYPE = "com.instagram.user.model.User";

    /**
     * Injected right after each call that binds the bar, with the bar and the profile screen that
     * holds it. The account the screen shows is read off the screen here, null while it has none,
     * and the tap lists actions for it. Never throws.
     */
    public static void screen(View bar, Object screen) {
        try {
            if (bar == null) return;
            Object shown = screen == null ? null : account.of(screen);
            user(bar, shown);
            ViewingHistory.profile(shown);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras account", failure);
        }
    }

    static void user(View bar, Object user) {
        stateOf(bar).user = user;
    }

    /** The screen's one view model field, then that model's one account field, by type; null when either is missing. */
    static Object accountOf(Object screen) {
        try {
            Object model = fieldOfType(screen, SCREEN_MODEL);
            if (model == null) return null;
            Object field = fieldOfType(model, USER_TYPE);
            if (field != null) return field;
            Object getter = getterOf(model, USER_TYPE);
            return getter != null ? getter : liveUser(model);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras account read", failure);
            return null;
        }
    }


    /** The account wrapped in the model's live data, by reflecting the value and its fields. */
    private static Object liveUser(Object model) throws Exception {
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(model);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) continue;
                for (Class<?> valueType = value.getClass(); valueType != null && valueType != Object.class; valueType = valueType.getSuperclass()) {
                    for (java.lang.reflect.Field held : valueType.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(held.getModifiers())) continue;
                        try {
                            held.setAccessible(true);
                            Object candidate = held.get(value);
                            if (candidate != null && candidate.getClass().getName().equals(USER_TYPE)) return candidate;
                        } catch (Throwable ignored) {
                            // The live value's other fields aren't the account.
                        }
                    }
                }
            }
        }
        return null;
    }
    /** The model's static getter taking itself and answering the account, by type; null when missing. */
    private static Object getterOf(Object holder, String typeName) throws Exception {
        Class<?> holderType = holder.getClass();
        for (java.lang.reflect.Method method : holderType.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.getReturnType().getName().equals(typeName) == false) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1 || parameters[0] != holderType) continue;
            method.setAccessible(true);
            return method.invoke(null, holder);
        }
        return null;
    }

    private static Object fieldOfType(Object holder, String typeName) throws IllegalAccessException {
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(typeName)) continue;
                field.setAccessible(true);
                return field.get(holder);
            }
        }
        return null;
    }

    static boolean wanted() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.PROFILE_EXTRAS_BUTTON.get()
                    && (ProfilePicture.on() || ProfilePicture.viewing() || ProfilePicture.copying());
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras switch", failure);
            return false;
        }
    }

    /**
     * Puts the button first in the bar's right row, leaves the one there where it is, or takes it
     * out, as the switch, Pause and what's switched on say.
     */
    static void place(View bar) {
        try {
            State state = stateOf(bar);
            ViewGroup row = state.row.get();
            if (row == null) {
                if (wanted()) HookStatus.counted(ROUTE, NOT_PLACED);
                return;
            }
            View existing = find(row);
            if (!wanted() || state.user == null) {
                if (existing != null) row.removeView(existing);
                return;
            }
            if (existing != null) {
                if (row.indexOfChild(existing) != 0) {
                    row.removeView(existing);
                    row.addView(existing, 0, params(row));
                }
                return;
            }
            View neighbor = row.getChildCount() > 0 ? row.getChildAt(0) : null;
            row.addView(create(row.getContext(), bar, neighbor), 0, params(row));
            HookStatus.counted(ROUTE, PLACED);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras button", failure);
            Logger.printException(() -> "Profile extras: could not put the button on the profile's top bar", failure);
        }
    }

    @Nullable
    static ImageView find(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof Info) return (ImageView) child;
        }
        return null;
    }

    private static ViewGroup.LayoutParams params(ViewGroup row) {
        if (row instanceof LinearLayout) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_VERTICAL;
            return params;
        }
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static ImageView create(Context context, View bar, @Nullable View neighbor) {
        ImageView button = new ImageView(context);
        button.setImageDrawable(new Info(context.getResources().getDisplayMetrics().density,
                FeedDownloadButton.iconColor(context)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        HomeHeader.padLike(button, neighbor);
        button.setClickable(true);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setContentDescription(L10n.t(context, "HushGram profile actions"));
        final WeakReference<View> owner = new WeakReference<>(bar);
        button.setOnClickListener(view -> press(view, owner.get()));
        return button;
    }

    /** The tap: lists the enabled actions for the account the bar shows. Never throws. */
    static void press(View button, @Nullable View bar) {
        try {
            if (!wanted() || bar == null) return;
            Object user = stateOf(bar).user;
            if (user == null) return;
            Context context = activityOf(button);
            List<ProfilePicture.Entry> entries = source.entries(user, context);
            if (entries.isEmpty()) {
                HookStatus.counted(ROUTE, EMPTY);
                Utils.showToastShort(L10n.t(context, "Nothing to do for this profile"));
                return;
            }
            PopupMenu menu = new PopupMenu(button.getContext(), button);
            for (int i = 0; i < entries.size(); i++) menu.getMenu().add(0, i, i, entries.get(i).label);
            menu.setOnMenuItemClickListener(item -> {
                try {
                    entries.get(item.getItemId()).click.onClick(button);
                } catch (Throwable failure) {
                    HookStatus.threw(ROUTE, "profile extras action", failure);
                }
                return true;
            });
            menu.show();
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras tap", failure);
            Logger.printException(() -> "Profile extras: the button's tap failed", failure);
        }
    }

    /**
     * The activity [view] sits in, found through its own context or its parents': a profile opened
     * from a link hands its rows a context with no activity under it, while the screen's content
     * view has one. [view]'s own context when none does.
     */
    static Context activityOf(View view) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            Context context = ((View) at).getContext();
            for (int depth = 0; depth < 16 && context instanceof ContextWrapper; depth++) {
                if (context instanceof Activity) return context;
                context = ((ContextWrapper) context).getBaseContext();
            }
        }
        return view.getContext();
    }

    /** A circled "i", drawn rather than taken from Instagram's resources, in Instagram's own icon color. */
    static final class Info extends Drawable {
        private final float unit;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Info(float density, int color) {
            unit = density;
            paint.setColor(color);
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        int color() {
            return paint.getColor();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(x, y, 9 * unit, paint);
            canvas.drawLine(x, y - unit, x, y + 4.5f * unit, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y - 4.2f * unit, 1.3f * unit, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return Math.round(24 * unit);
        }

        @Override
        public int getIntrinsicHeight() {
            return Math.round(24 * unit);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
