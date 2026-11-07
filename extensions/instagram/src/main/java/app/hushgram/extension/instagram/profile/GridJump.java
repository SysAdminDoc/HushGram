/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.app.DatePickerDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.download.FeedDownloadButton;
import app.hushgram.extension.instagram.download.ProfileExtras;
import app.hushgram.extension.instagram.reels.HomeHeader;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Jump a profile's posts to a date" patch (#498).
 *
 * <p>A media id holds the time it was made in its top bits, and Instagram's feed of a user's posts
 * pages by asking for what's older than an id ({@code max_id}), so an id made up for the end of a day
 * asks for that day's posts on down. A calendar button on the profile's top bar picks the day. The
 * grid is then reloaded through Instagram's own call, the one its tab switch makes.
 *
 * <p>The query the grid normally pages through ignores {@code max_id}, so {@link #restMaxId} sends the
 * jump and the pages after it down the route the profile keeps for tagged posts, whose answer the grid
 * already reads, and {@link #restPath} points that request at the user's own feed. The reload runs on
 * the tapping thread right down to the request, so the id is held in a {@link ThreadLocal} for just
 * that long: it's one request for each jump, and no other thread's request, prefetch or search sees
 * it. Instagram clears the grid when the answer comes. The grid's next pages stay on that route with
 * the cursor Instagram kept from the answer. Pull to refresh goes back to the newest posts through
 * Instagram's own route.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet, a member
 * missing or anything thrown, the request and the grid stay as Instagram has them.
 */
public final class GridJump {
    /** Where media ids start counting from, and how far up their time sits. */
    static final long MEDIA_EPOCH_MS = 1314220021721L;
    private static final int TIME_SHIFT = 23;

    /** Counted when a jump's request went out, when Instagram refused it, and when the button went on a bar. */
    static final String JUMPED = "jumped to a date";
    static final String STILL_LOADING = "grid still loading";
    static final String PLACED = "calendar placed";

    /** The feed of a user's posts, which honors max_id. Instagram fills in the user's id. */
    static final String USER_FEED_PATH = "feed/user/%s/";

    /** The id waiting for the request of the reload now running on this thread. */
    private static final ThreadLocal<Long> PENDING = new ThreadLocal<>();

    /** Set while this thread is building a request that goes to {@link #USER_FEED_PATH}. */
    private static final ThreadLocal<Boolean> REST_PATH = new ThreadLocal<>();

    /** The grids' paging states showing a jump's answer, whose next pages stay on that route. */
    private static final Set<Object> JUMPED_LOADERS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** The posts tab showing now, told by {@link #resumed}. */
    static volatile WeakReference<Object> postsTab = new WeakReference<>(null);

    /** Reloads the tab's posts. A test swaps it. */
    interface Reloader {
        void reload(Object tab);
    }

    static Reloader reloader = tab -> reload(tab, null, null, null);

    private GridJump() {
    }

    /** The id of something made at [millis]: older than whatever was made after it. */
    static long mediaId(long millis) {
        return Math.max(0, millis - MEDIA_EPOCH_MS) << TIME_SHIFT;
    }

    /** The last moment of the given local day, or [now] when that's sooner. */
    static long endOfDay(int year, int month, int day, long now) {
        Calendar end = Calendar.getInstance();
        end.clear();
        end.set(year, month, day, 23, 59, 59);
        end.set(Calendar.MILLISECOND, 999);
        return Math.min(end.getTimeInMillis(), now);
    }

    /**
     * Injected first in the profile's load, with the tab's paging state, the user's id, whether this is
     * the first page, the state's next cursor, and whether the state is free to load. Answers the
     * max_id to fetch with down the jump's route, or null for Instagram's own route. A jump waits while
     * the grid is loading, which {@link #chosen} tells the user. Never throws.
     */
    public static String restMaxId(Object loader, String user, int first, String next, int free) {
        try {
            Long media = PENDING.get();
            if (media != null) {
                if (free == 0 || user == null || user.isEmpty()) return null;
                PENDING.remove();
                JUMPED_LOADERS.add(loader);
                String id = media + "_" + user;
                Logger.printDebug(() -> "Jump to a date: asking for posts older than " + id);
                REST_PATH.set(Boolean.TRUE);
                return id;
            }
            if (first != 0) {
                JUMPED_LOADERS.remove(loader);
                return null;
            }
            if (next == null || !JUMPED_LOADERS.contains(loader)) return null;
            REST_PATH.set(Boolean.TRUE);
            return next;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_GRID_JUMP, "grid load", failure);
            return null;
        }
    }

    /**
     * Injected in the tagged posts' request where it loads its path, with that path. Answers the user's
     * feed when {@link #restMaxId} just sent this thread here, Instagram's path otherwise. Never throws.
     */
    public static String restPath(String path) {
        try {
            if (REST_PATH.get() == null) return path;
            REST_PATH.remove();
            return USER_FEED_PATH;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_GRID_JUMP, "grid request", failure);
            return path;
        }
    }

    /** Injected in the posts tab's onResume, right after its super call. Never throws. */
    public static void resumed(Object tab) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_GRID_JUMP);
            if (tab == null || !on()) return;
            Object arguments = tab.getClass().getMethod("getArguments").invoke(tab);
            if (arguments instanceof Bundle && PostsList.POSTS_TAB.equals(((Bundle) arguments).getString(PostsList.TAB_KEY))) {
                postsTab = new WeakReference<>(tab);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_GRID_JUMP, "posts tab", failure);
        }
    }

    /**
     * Injected right after the profile bar's bind fills its rows, with the right row. Queued because
     * Profile extras queues its own buttons the same way, which keeps them next to each other. Never
     * throws.
     */
    public static void bound(View row) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_GRID_JUMP);
            if (!(row instanceof ViewGroup)) return;
            row.post(() -> place((ViewGroup) row));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_GRID_JUMP, "profile bar", failure);
        }
    }

    /** Puts the button first in the row, leaves the one there where it is, or takes it out. */
    static void place(ViewGroup row) {
        try {
            JumpButton existing = find(row);
            if (!on()) {
                if (existing != null) row.removeView(existing);
                return;
            }
            if (existing != null) return;
            View neighbor = row.getChildCount() > 0 ? row.getChildAt(0) : null;
            row.addView(create(row.getContext(), neighbor), 0, params(row));
            HookStatus.counted(FamilyNames.PROFILE_GRID_JUMP, PLACED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_GRID_JUMP, "calendar button", failure);
            Logger.printException(() -> "Jump to a date: could not put the button on the profile's top bar", failure);
        }
    }

    /** The button: a calendar of its own kind, so Profile extras doesn't take it for one of its own. */
    static final class JumpButton extends ImageView {
        JumpButton(Context context) {
            super(context);
        }
    }

    @Nullable
    static JumpButton find(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof JumpButton) return (JumpButton) row.getChildAt(i);
        }
        return null;
    }

    private static JumpButton create(Context context, @Nullable View neighbor) {
        JumpButton button = new JumpButton(context);
        button.setImageDrawable(new CalendarIcon(context.getResources().getDisplayMetrics().density,
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
        button.setContentDescription(L10n.t(context, "Jump to a date"));
        button.setOnClickListener(GridJump::press);
        return button;
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

    /** The tap: asks for a day, as far back as ids count. Older posts follow by scrolling from there. Never throws. */
    static void press(View button) {
        try {
            if (!on()) return;
            Context context = ProfileExtras.activityOf(button);
            if (!showing(postsTab.get())) {
                Utils.showToastShort(L10n.t(context, "Open the profile's posts first"));
                return;
            }
            Calendar now = Calendar.getInstance();
            DatePickerDialog dialog = new DatePickerDialog(context, (picker, year, month, day) -> chosen(context, year, month, day),
                    now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));
            dialog.getDatePicker().setMinDate(MEDIA_EPOCH_MS);
            dialog.getDatePicker().setMaxDate(now.getTimeInMillis());
            dialog.show();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_GRID_JUMP, "calendar tap", failure);
            Logger.printException(() -> "Jump to a date: the button's tap failed", failure);
        }
    }

    /**
     * The day picked: reloads the tab with the day's id waiting for the load. An id still waiting
     * afterwards means the load turned it down, which it does while the grid is loading. Never throws.
     */
    static void chosen(Context context, int year, int month, int day) {
        try {
            Object tab = postsTab.get();
            if (!on() || !showing(tab)) return;
            long media = mediaId(endOfDay(year, month, day, System.currentTimeMillis()));
            boolean asked;
            PENDING.set(media);
            try {
                reloader.reload(tab);
            } finally {
                asked = PENDING.get() == null;
                PENDING.remove();
            }
            if (!asked) {
                HookStatus.counted(FamilyNames.PROFILE_GRID_JUMP, STILL_LOADING);
                Utils.showToastShort(L10n.t(context, "Still loading, try again"));
                return;
            }
            HookStatus.counted(FamilyNames.PROFILE_GRID_JUMP, JUMPED);
            Utils.showToastShort(L10n.t(context, "Going to that date"));
            toTop(tab);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_GRID_JUMP, "jump", failure);
            Logger.printException(() -> "Jump to a date: the jump failed", failure);
        }
    }

    /** Whether [tab] is up on the screen now, the one the button's bar belongs to. */
    private static boolean showing(@Nullable Object tab) throws ReflectiveOperationException {
        return tab != null && Boolean.TRUE.equals(tab.getClass().getMethod("isResumed").invoke(tab));
    }

    /**
     * Takes the grid to its top so the answer shows from its first post rather than wherever the
     * grid was scrolled to. RecyclerView's own scroll is renamed in Instagram's builds, its layout
     * manager's isn't. The jump works without this, so a missing member is only reported.
     */
    private static void toTop(Object tab) {
        try {
            Object grid = tab.getClass().getField(PostsList.GRID_FIELD).get(tab);
            if (grid == null) return;
            Object manager = grid.getClass().getMethod("getLayoutManager").invoke(grid);
            if (manager != null) manager.getClass().getMethod("scrollToPosition", int.class).invoke(manager, 0);
        } catch (ReflectiveOperationException missing) {
            HookStatus.missingMember(FamilyNames.PROFILE_GRID_JUMP, "method", tab.getClass().getName(), "grid scroll to top");
        }
    }

    /**
     * Filled in by the patch: asks the profile screen for the first page of [tab]'s posts, the call
     * Instagram's tab switch makes. The other arguments are spare registers for the patch's code.
     */
    public static void reload(Object tab, Object spare1, Object spare2, Object spare3) {
    }

    private static boolean on() {
        return Utils.settingsReady() && Settings.PROFILE_GRID_JUMP.get();
    }

    /** A calendar page, drawn rather than taken from Instagram's resources, in Instagram's own icon color. */
    static final class CalendarIcon extends Drawable {
        private final float unit;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CalendarIcon(float density, int color) {
            unit = density;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            RectF page = new RectF(x - 8.5f * unit, y - 7 * unit, x + 8.5f * unit, y + 8.5f * unit);
            canvas.drawRoundRect(page, 2.5f * unit, 2.5f * unit, paint);
            canvas.drawLine(page.left, y - 2.5f * unit, page.right, y - 2.5f * unit, paint);
            canvas.drawLine(x - 4 * unit, y - 9.5f * unit, x - 4 * unit, y - 5 * unit, paint);
            canvas.drawLine(x + 4 * unit, y - 9.5f * unit, x + 4 * unit, y - 5 * unit, paint);
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
