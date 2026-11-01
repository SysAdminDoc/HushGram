/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import app.hushgram.extension.instagram.download.FeedDownloadButton;
import app.hushgram.extension.instagram.reels.HomeHeader;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A menu button at the end of Home's header (#437) that opens a short list: the viewing history,
 * Ghost mode (a check that shows and flips it) and HushGram settings. Each entry does what its own
 * place in HushGram does, so nothing here keeps a state of its own.
 *
 * <p>{@link GhostHeaderButton#drew} posts {@link #place} behind each header draw, like the buttons
 * beside it. The icon is three lines drawn in code in Instagram's icon color for the theme. With the
 * switch off or HushGram paused no button is added, and one left from before is taken out at the
 * next draw. Ghost mode is left off the list in a build with no ghost patch. Nothing here throws.
 */
public final class HomeMenuButton {
    private HomeMenuButton() { }

    /** The name the diagnostic report counts this under, the Reels tab line the header's other hooks use. */
    static final String ROUTE = FamilyNames.REELS_TAB;
    static final String PLACED = "Home header menu button placed";
    static final String NO_ROW = "Home header menu button not placed, no row to put it in";
    static final String OPENED = "Home header menu button opened its menu";
    static final String PICKED = "Home header menu button entry picked";

    /** Menu ids, so a pick is matched without the strings. */
    static final int HISTORY = 1;
    static final int GHOST = 2;
    static final int SETTINGS = 3;

    /** The button, its own type so the next draw finds it in the row. */
    static final class Dots extends ImageView {
        Dots(Context context) {
            super(context);
        }
    }

    /** Puts the button in, leaves the one there, or takes it out, as the switch and Pause say. Never throws. */
    public static void place(View header) {
        try {
            if (header == null) return;
            boolean wanted = Utils.settingsReady() && !HushgramPause.isPaused() && Settings.MENU_BUTTON_ON_HOME.get();
            LinearLayout end = HomeHeader.endRowOf(header);
            LinearLayout row = end != null ? end : HomeHeader.inboxRowOf(header);
            if (row == null) {
                if (wanted) HookStatus.counted(ROUTE, NO_ROW);
                return;
            }
            ImageView existing = in(row);
            if (!wanted) {
                if (existing != null) row.removeView(existing);
                return;
            }
            if (existing != null) return;
            Dots button = create(header.getContext(), row.getChildCount() > 0 ? row.getChildAt(row.getChildCount() - 1) : null);
            row.addView(button, params());
            HookStatus.counted(ROUTE, PLACED);
            Logger.printDebug(() -> "Home menu: the button is on Home's header");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "menu button", failure);
            Logger.printException(() -> "Home menu: could not put the button on Home's header", failure);
        }
    }

    /** The button HushGram put in [row], or null. */
    @Nullable
    static ImageView in(@Nullable LinearLayout row) {
        if (row == null) return null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof Dots) return (ImageView) child;
        }
        return null;
    }

    /** Opens the list under [view]. Does nothing while paused. Never throws. */
    static void press(View view) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            PopupMenu menu = new PopupMenu(view.getContext(), view);
            fill(menu.getMenu());
            Context context = view.getContext();
            menu.setOnMenuItemClickListener(item -> pick(item, context));
            menu.show();
            HookStatus.counted(ROUTE, OPENED);
            Logger.printDebug(() -> "Home menu: the header's menu button opened its menu");
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "menu button tap", failure);
            Logger.printException(() -> "Home menu: the header's menu button failed", failure);
        }
    }

    /** The entries: the history, Ghost mode with its check when this build has it, and settings. */
    static void fill(Menu menu) {
        menu.add(Menu.NONE, HISTORY, Menu.NONE, L10n.t("Viewing history"));
        List<BooleanSetting> switches = GhostMode.picked(GhostMode.switches(PatchFamily.inThisBuild()));
        if (!switches.isEmpty()) {
            MenuItem ghost = menu.add(Menu.NONE, GHOST, Menu.NONE, L10n.t("Ghost mode"));
            ghost.setCheckable(true);
            ghost.setChecked(GhostMode.on(switches));
        }
        menu.add(Menu.NONE, SETTINGS, Menu.NONE, L10n.t("HushGram settings"));
    }

    /** What a pick does. Never throws. */
    static boolean pick(MenuItem item, Context context) {
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return true;
            Activity activity = activityOf(context);
            switch (item.getItemId()) {
                case HISTORY:
                    if (activity == null) return true;
                    ViewingHistoryPage.open(activity, new HushgramPreferenceFragment.Row(activity));
                    break;
                case GHOST:
                    List<BooleanSetting> switches = GhostMode.picked(GhostMode.switches(PatchFamily.inThisBuild()));
                    if (!switches.isEmpty()) GhostMode.flip(switches);
                    break;
                case SETTINGS:
                    if (activity == null || !SettingsEntry.requestOpen(activity)) return true;
                    break;
                default:
                    return false;
            }
            HookStatus.counted(ROUTE, PICKED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "menu button pick", failure);
            Logger.printException(() -> "Home menu: an entry of the header's menu failed", failure);
            return true;
        }
    }

    /** The screen [context] sits on, or null. */
    @Nullable
    static Activity activityOf(@Nullable Context context) {
        Context at = context;
        for (int depth = 0; at != null && depth < 8; depth++) {
            if (at instanceof Activity) return (Activity) at;
            at = at instanceof ContextWrapper ? ((ContextWrapper) at).getBaseContext() : null;
        }
        return null;
    }

    private static LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    private static Dots create(Context context, @Nullable View neighbor) {
        Dots button = new Dots(context);
        button.setImageDrawable(new Lines(context.getResources().getDisplayMetrics().density,
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
        button.setContentDescription(L10n.t("HushGram menu"));
        button.setOnClickListener(HomeMenuButton::press);
        return button;
    }

    /** Three lines drawn in code, in the stroke and color of the buttons beside the menu button. */
    static final class Lines extends Drawable {
        private final float unit;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Lines(float density, int color) {
            unit = density;
            paint.setColor(color);
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
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            for (int line = -1; line <= 1; line++) {
                canvas.drawLine(x - 9 * unit, y + line * 5.5f * unit, x + 9 * unit, y + line * 5.5f * unit, paint);
            }
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
