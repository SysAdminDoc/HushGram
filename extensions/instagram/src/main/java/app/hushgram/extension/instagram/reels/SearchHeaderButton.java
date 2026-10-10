/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A Search button on Home's header, for the Tab bar switch Search button on Home's header.
 *
 * <p>The header's draw hook ({@code GhostHeaderButton.drew}) posts {@link #place} behind each draw.
 * The button goes first in the header's end row of buttons, the row Instagram always puts the
 * notifications heart in, which the patch reads for {@link HomeHeader#endRow}. Without that row it
 * goes right before Messages. The magnifier is drawn in code, in the color and size of the ghost
 * button beside it.
 *
 * <p>A tap selects Instagram's own Search tab through the tab host's public switch, the one
 * InstagramMainActivity calls for a tab picked from code, so Search opens as it does from the bar.
 * The patch writes {@link #select} to make that call. The tab list hook's redirect of a hidden
 * Search to Home lets this one switch through, so with Hide the Search tab on, Search still opens,
 * by its fragment, the way Instagram opens Messages off the bar on a wide screen. Where the tabs
 * swipe sideways the pager is built from the bar's tabs and has no page for a Search that's off
 * the bar, so the tap leaves Search shut there, says why in a toast and is counted. The patch writes
 * {@link #paging} to tell.
 *
 * <p>With the switch off or HushGram paused, no button is added and one left from before is taken
 * out at the next draw. A tap while paused does nothing. Nothing in here throws.
 */
public final class SearchHeaderButton {
    private SearchHeaderButton() { }

    /** The name the diagnostic report counts this under, the Reels tab line the header's other hooks use. */
    static final String ROUTE = FamilyNames.REELS_TAB;
    static final String PLACED = "Home header Search button placed";
    static final String NO_ROW = "Home header Search button not placed, no row to put it in";
    static final String OPENED = "Home header Search button opened Search";
    static final String OPENED_OFF_BAR = "Home header Search button opened Search off the tab bar";
    static final String SWIPING = "Home header Search button left Search shut, it's off the swiping tabs";
    static final String NO_HOST = "Home header Search button found no tab host";

    /** Stand in for the patched stubs in tests, or null to ask the stubs. */
    @Nullable
    static volatile ToIntBiFunction<Object, Object> selectForTests;
    @Nullable
    static volatile ToIntFunction<Object> pagingForTests;

    /** Puts the button in, leaves the one there, or takes it out, as the switch and Pause say. Never throws. */
    public static void place(View header) {
        try {
            if (header == null) return;
            boolean wanted = Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SEARCH_BUTTON_ON_HOME.get();
            LinearLayout end = HomeHeader.endRowOf(header);
            View inbox = HomeHeader.inboxOf(header);
            LinearLayout row = end != null ? end : HomeHeader.inboxRowOf(header);
            if (row == null) {
                if (wanted) HookStatus.counted(ROUTE, NO_ROW);
                return;
            }
            ImageView existing = find(row);
            if (!wanted) {
                if (existing != null) row.removeView(existing);
                return;
            }
            if (existing != null) return;
            ImageView button = create(header.getContext(), inbox != null ? inbox : row.getChildCount() > 0 ? row.getChildAt(0) : null);
            row.addView(button, row == end ? 0 : row.indexOfChild(inbox), params());
            HookStatus.counted(ROUTE, PLACED);
            Logger.printDebug(() -> "Reels tab: the Search button is on Home's header");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "search button", failure);
            Logger.printException(() -> "Reels tab: could not put the Search button on Home's header", failure);
        }
    }

    /** The tap: opens Search, or says why it can't. Does nothing while paused. Never throws. */
    static void press(View view) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            Activity activity = activityOf(view.getContext());
            Object search = TabStart.tabNamed(ReelsTab.SEARCH);
            if (activity == null || search == null) {
                HookStatus.counted(ROUTE, NO_HOST);
                return;
            }
            boolean onTheBar = TabStart.isOnTheBar(ReelsTab.SEARCH);
            if (!onTheBar && tabsSwipe(activity)) {
                HookStatus.counted(ROUTE, SWIPING);
                Utils.showToastLong(L10n.t("Search isn't on your tab bar, so this button can't open it here. "
                        + "If Hide the Search tab is on, turn it off and restart Instagram."));
                return;
            }
            int opened;
            // A Search the switch hides is let through for this one switch, and only this one.
            ReelsTab.letThrough(ReelsTab.SEARCH);
            try {
                opened = switchTo(activity, search);
            } finally {
                ReelsTab.letThrough(null);
            }
            if (opened == 0) {
                HookStatus.counted(ROUTE, NO_HOST);
                return;
            }
            HookStatus.counted(ROUTE, onTheBar ? OPENED : OPENED_OFF_BAR);
            Logger.printDebug(() -> "Reels tab: the header's Search button opened Search" + (onTheBar ? "" : " off the bar"));
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "search button tap", failure);
            Logger.printException(() -> "Reels tab: the header's Search button failed", failure);
        }
    }

    private static int switchTo(Activity activity, Object tab) {
        ToIntBiFunction<Object, Object> stand = selectForTests;
        return stand != null ? stand.applyAsInt(activity, tab) : select(activity, tab);
    }

    private static boolean tabsSwipe(Activity activity) {
        ToIntFunction<Object> stand = pagingForTests;
        return (stand != null ? stand.applyAsInt(activity) : paging(activity)) != 0;
    }

    /**
     * Switches the tab host of [activity], when it's Instagram's main activity, to [tab] the way
     * Instagram switches to a tab picked from code. 1 when it asked the host, 0 otherwise. A stub:
     * the patch writes its body. Answers 0 unpatched.
     */
    public static int select(Object activity, Object tab) {
        return 0;
    }

    /**
     * 1 when the tab host of [activity], Instagram's main activity, has a pager, so its tabs swipe
     * sideways, else 0. A stub: the patch writes its body. Answers 0 unpatched.
     */
    public static int paging(Object activity) {
        return 0;
    }

    /** The activity [context] is, or wraps, or null. */
    @Nullable
    static Activity activityOf(@Nullable Context context) {
        Context at = context;
        for (int depth = 0; at != null && depth < 16; depth++) {
            if (at instanceof Activity) return (Activity) at;
            if (!(at instanceof ContextWrapper)) return null;
            at = ((ContextWrapper) at).getBaseContext();
        }
        return null;
    }

    @Nullable
    private static ImageView find(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof Magnifier) return (ImageView) child;
        }
        return null;
    }

    private static LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private static ImageView create(Context context, @Nullable View neighbor) {
        ImageView button = new ImageView(context);
        button.setImageDrawable(new Magnifier(context.getResources().getDisplayMetrics().density, night(context)));
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
        button.setContentDescription(L10n.t("Search"));
        button.setOnClickListener(SearchHeaderButton::press);
        return button;
    }

    private static boolean night(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * A magnifier, drawn rather than taken from Instagram's resources: a ring and a handle to the
     * lower right, in the stroke and color of the ghost button, which follow the app's light or dark
     * mode like Instagram's own header icons.
     */
    static final class Magnifier extends Drawable {
        private final float unit;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Magnifier(float density, boolean night) {
            unit = density;
            paint.setColor(night ? Color.WHITE : 0xFF262626);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        int color() {
            return paint.getColor();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.exactCenterX() - 1.5f * unit;
            float y = bounds.exactCenterY() - 1.5f * unit;
            float radius = 7 * unit;
            canvas.drawCircle(x, y, radius, paint);
            // The handle leaves the ring at the lower right, at 45 degrees.
            float edge = radius * 0.7071f;
            canvas.drawLine(x + edge, y + edge, x + edge + 5.5f * unit, y + edge + 5.5f * unit, paint);
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
