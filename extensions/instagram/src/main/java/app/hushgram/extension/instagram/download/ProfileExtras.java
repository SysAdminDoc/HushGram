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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.profile.GridJump;
import app.hushgram.extension.instagram.reels.HomeHeader;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Profile extras button: a small button first in the row on the right of a profile's top bar, where
 * the three dots are, that lists HushGram's profile actions (Save profile picture, View profile
 * picture, Save profile, Copy username and Copy bio, whichever are switched on) in a small menu. The rows in the
 * three-dot menu stay as they are ({@link ProfilePicture}); this is a second way to reach them (#234).
 *
 * <p>The patch calls {@link #bound} first thing as the bar fills its rows, and {@link #screen} right
 * after each bind with the profile screen, which holds the account. Instagram empties both rows every time it
 * binds, so the button is put in again after each, posted behind the bind. With the switch off,
 * HushGram paused, no account yet or nothing switched on to list, no button is added and one left
 * from before is taken out. Everything fails open to Instagram's own layout.
 *
 * <p>HushGram's buttons take their room from the username beside them (#850: with the calendar, this
 * button and About this account on your own profile, the name read "i..."). Once a layout leaves the
 * username narrower than its text with two or more of them in the bar, that bar folds: the calendar
 * and About this account leave it and become rows at the end of this button's menu, and this button
 * shows for them even when nothing else is switched on for it. A folded bar stays folded.
 */
public final class ProfileExtras {
    private ProfileExtras() {
    }

    static final String ROUTE = FamilyNames.PROFILE_PICTURE;
    static final String PLACED = "profile extras button placed";
    static final String NOT_PLACED = "profile extras button not placed, no row to put it in";
    static final String EMPTY = "profile extras menu empty";
    static final String FOLDED = "profile bar buttons folded into the menu";

    /** The profile bar's username, by name. */
    static final String TITLE = "action_bar_title";

    private static final ResourceIdCache IDS = new ResourceIdCache();

    /** What the bar is known by: its right row, held weakly since the row holds the bar, and the account it shows. */
    private static final class State {
        WeakReference<ViewGroup> row = new WeakReference<>(null);
        Object user;
        /** The signed-in session the bar was bound with, which Instagram's About this account is opened through. */
        Object session;
        /** Set once a layout left the username cut short: the calendar and About this account live in the menu (#850). */
        boolean folded;
        /** The row the layout watch is on, so it's added once per row. */
        WeakReference<ViewGroup> watched = new WeakReference<>(null);
    }

    static final String ABOUT_PLACED = "about this account button placed";
    static final String ABOUT_OPENED = "about this account opened";

    /** Filled in by the patch: whether this build has the call that opens About this account. */
    public static boolean aboutAvailable() {
        return false;
    }

    /** Filled in by the patch: an account's id, or null. */
    @Nullable
    public static String userId(Object user) {
        return null;
    }

    /** Filled in by the patch: opens Instagram's About this account for [id] from [context], through [session], saying it came from [referer]. */
    public static void openAbout(Context context, Object session, String id, @Nullable String referer) {
    }

    /**
     * #507: what Instagram's own profile menu tells the screen it came from. Left out, the call says
     * FeedAds, its entry from an ad, and the screen fails on a Page.
     */
    static final String ABOUT_REFERER = "ProfileMore";

    /** What opens the screen. A test swaps it. */
    interface Opener {
        void open(Context context, Object session, String id, String referer);
    }

    static java.util.function.Function<Object, String> ids = ProfileExtras::userId;

    static java.util.function.BooleanSupplier available = ProfileExtras::aboutAvailable;

    static Opener opener = ProfileExtras::openAbout;

    /** Whether the About this account button is switched on and usable. */
    static boolean aboutWanted() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.ABOUT_ACCOUNT_BUTTON.get()
                    && available.getAsBoolean();
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "about this account switch", failure);
            return false;
        }
    }

    /** The button: a circled i of its own kind, so it's told from the profile extras one. */
    static final class AboutButton extends ImageView {
        AboutButton(Context context) {
            super(context);
        }
    }

    @Nullable
    static AboutButton findAbout(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof AboutButton) return (AboutButton) row.getChildAt(i);
        }
        return null;
    }

    /** The tap: opens About this account for the account the bar shows. Never throws. */
    static void pressAbout(View button, @Nullable View bar) {
        try {
            if (!aboutWanted() || bar == null) return;
            State state = stateOf(bar);
            if (state.user == null || state.session == null) return;
            String id = ids.apply(state.user);
            if (id == null || id.isEmpty()) return;
            HookStatus.counted(ROUTE, ABOUT_OPENED);
            opener.open(activityOf(button), state.session, id, ABOUT_REFERER);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "about this account tap", failure);
            Logger.printException(() -> "Profile extras: About this account failed to open", failure);
        }
    }

    private static AboutButton createAbout(Context context, View bar, @Nullable View neighbor) {
        AboutButton button = new AboutButton(context);
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
        button.setContentDescription(L10n.t(context, "About this account"));
        final WeakReference<View> owner = new WeakReference<>(bar);
        button.setOnClickListener(view -> pressAbout(view, owner.get()));
        return button;
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
    public static void bound(View bar, View row, Object session) {
        try {
            HookStatus.invoked(ROUTE);
            if (bar == null || !(row instanceof ViewGroup)) return;
            State state = stateOf(bar);
            state.row = new WeakReference<>((ViewGroup) row);
            state.session = session;
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
            ProfileHistory.seen(shown);
            SavedProfiles.seen(shown);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras account", failure);
        }
    }

    static void user(View bar, Object user) {
        stateOf(bar).user = user;
    }

    /**
     * The account of the profile [view] is on, for a long press on its picture ({@link ProfilePictureHold}):
     * going up from [view], the first parent with a profile bar on screen under it, and that bar's
     * account. Null when no bar is found, or when the bars there show different accounts or none yet.
     */
    @Nullable
    static Object accountAround(View view) {
        List<java.util.Map.Entry<View, State>> bars;
        synchronized (BARS) {
            bars = new java.util.ArrayList<>(BARS.entrySet());
        }
        for (android.view.ViewParent at = view.getParent(); at instanceof ViewGroup; at = at.getParent()) {
            boolean found = false;
            Object account = null;
            for (java.util.Map.Entry<View, State> entry : bars) {
                View bar = entry.getKey();
                if (bar == null || !bar.isShown() || !holds((ViewGroup) at, bar)) continue;
                Object shown = entry.getValue().user;
                if (found && shown != account) return null;
                found = true;
                account = shown;
            }
            if (found) return account;
        }
        return null;
    }

    private static boolean holds(ViewGroup group, View view) {
        for (android.view.ViewParent at = view.getParent(); at != null; at = at.getParent()) {
            if (at == group) return true;
        }
        return false;
    }

    /** The screen's one view model field, then the account that model holds; null when either is missing. */
    static Object accountOf(Object screen) {
        try {
            Object model = fieldOfType(screen, SCREEN_MODEL);
            return model == null ? null : modelUser(model);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras account read", failure);
            return null;
        }
    }

    /**
     * Filled in by the patch: the account the profile screen's [model] holds, or null. 453 keeps it in
     * the model's live data, wrapped in a holder, so it's read the way Show if a profile follows you
     * reads it rather than by walking fields (#234).
     */
    @Nullable
    public static Object modelUser(Object model) {
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
                    && (ProfilePicture.on() || ProfilePicture.viewing() || ProfilePicture.copying() || SavedProfiles.on());
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
            boolean folding = state.folded && (GridJump.wanted() || aboutWanted());
            if (state.folded) {
                View calendar = GridJump.calendarIn(row);
                if (calendar != null) row.removeView(calendar);
            }
            View existing = find(row);
            if (!(wanted() || folding) || state.user == null) {
                if (existing != null) row.removeView(existing);
                existing = null;
            } else if (existing != null) {
                if (row.indexOfChild(existing) != 0) {
                    row.removeView(existing);
                    row.addView(existing, 0, params(row));
                }
            } else {
                View neighbor = row.getChildCount() > 0 ? row.getChildAt(0) : null;
                existing = create(row.getContext(), bar, neighbor);
                row.addView(existing, 0, params(row));
                HookStatus.counted(ROUTE, PLACED);
            }
            placeAbout(row, bar, state, existing != null ? 1 : 0);
            watch(bar, row, state);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile extras button", failure);
            Logger.printException(() -> "Profile extras: could not put the button on the profile's top bar", failure);
        }
    }

    /**
     * #850: whether the profile bar holding [row] folded its calendar and About this account into this
     * button's menu. Never throws.
     */
    public static boolean folded(ViewGroup row) {
        try {
            synchronized (BARS) {
                for (State state : BARS.values()) {
                    if (state.row.get() == row) return state.folded;
                }
            }
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile bar fold", failure);
        }
        return false;
    }

    /** Looks at [bar] after each layout of its right [row], once per row, until the bar folds. */
    private static void watch(View bar, ViewGroup row, State state) {
        if (state.watched.get() == row) return;
        state.watched = new WeakReference<>(row);
        final WeakReference<View> owner = new WeakReference<>(bar);
        row.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            View at = owner.get();
            // After the whole bar's pass, when the username has its width for this layout.
            if (at != null && !stateOf(at).folded) view.post(() -> fold(at));
        });
    }

    /**
     * Folds [bar] when its username is narrower than its text and two or more of HushGram's buttons
     * share the row: the calendar and About this account go into this button's menu. Never throws.
     */
    static void fold(View bar) {
        try {
            State state = stateOf(bar);
            ViewGroup row = state.row.get();
            if (state.folded || row == null || ours(row) < 2 || !cut(bar)) return;
            state.folded = true;
            HookStatus.counted(ROUTE, FOLDED);
            place(bar);
        } catch (Throwable failure) {
            HookStatus.threw(ROUTE, "profile bar fold", failure);
        }
    }

    /** How many of HushGram's buttons show in [row]: the calendar, this one and About this account. */
    private static int ours(ViewGroup row) {
        int count = 0;
        for (View button : new View[] {GridJump.calendarIn(row), find(row), findAbout(row)}) {
            if (button != null && button.getVisibility() == View.VISIBLE) count++;
        }
        return count;
    }

    /** Whether [bar]'s username is laid out narrower than its text, so it's cut short. */
    static boolean cut(View bar) {
        int id = IDS.resolve(bar.getResources(), Utils.resourcePackage(bar.getContext()), TITLE, false);
        View found = id == 0 ? null : bar.findViewById(id);
        if (!(found instanceof TextView) || !found.isLaidOut() || found.getVisibility() != View.VISIBLE) return false;
        TextView name = (TextView) found;
        CharSequence text = name.getText();
        if (text == null || text.length() == 0) return false;
        float wide = name.getPaint().measureText(text.toString()) + name.getTotalPaddingLeft() + name.getTotalPaddingRight();
        return name.getWidth() + 1 < wide;
    }

    static void putIdForTests(Context context, String name, int id) {
        IDS.putForTests(Utils.resourcePackage(context), name, id);
    }

    static void resetForTests() {
        IDS.clear();
        synchronized (BARS) {
            BARS.clear();
        }
    }

    /** Puts the About this account button at [index] of the row, leaves it there, or takes it out. */
    private static void placeAbout(ViewGroup row, View bar, State state, int index) {
        AboutButton existing = findAbout(row);
        if (!aboutWanted() || state.user == null || state.session == null || state.folded) {
            if (existing != null) row.removeView(existing);
            return;
        }
        if (existing != null) {
            if (row.indexOfChild(existing) != index) {
                row.removeView(existing);
                row.addView(existing, Math.min(index, row.getChildCount()), params(row));
            }
            return;
        }
        View neighbor = row.getChildCount() > index ? row.getChildAt(index) : null;
        row.addView(createAbout(row.getContext(), bar, neighbor), Math.min(index, row.getChildCount()), params(row));
        HookStatus.counted(ROUTE, ABOUT_PLACED);
    }

    /**
     * HushGram's profile actions button in a profile's top bar [row], or null when it isn't there. Show it as an
     * icon at the top puts its follow mark on it when the bar is too crowded for an icon of its own (#842).
     */
    @Nullable
    public static ImageView actionsButton(ViewGroup row) {
        return find(row);
    }

    @Nullable
    static ImageView find(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof AboutButton) continue;
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
        button.setImageDrawable(new Actions(context.getResources().getDisplayMetrics().density,
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

    /**
     * The tap: lists the enabled actions for the account the bar shows, then in a folded bar Jump to a
     * date and About this account (#850). Never throws.
     */
    static void press(View button, @Nullable View bar) {
        try {
            if (bar == null) return;
            State state = stateOf(bar);
            boolean listing = wanted();
            if (!listing && !state.folded) return;
            Object user = state.user;
            if (user == null) return;
            Context context = activityOf(button);
            List<ProfilePicture.Entry> entries = new ArrayList<>();
            if (listing) entries.addAll(source.entries(user, context));
            if (state.folded) {
                if (GridJump.wanted()) entries.add(new ProfilePicture.Entry(L10n.t(context, "Jump to a date"), view -> GridJump.press(button)));
                if (aboutWanted()) entries.add(new ProfilePicture.Entry(L10n.t(context, "About this account"), view -> pressAbout(button, bar)));
            }
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
    public static Context activityOf(View view) {
        for (Object at = view; at instanceof View; at = ((View) at).getParent()) {
            Context context = ((View) at).getContext();
            for (int depth = 0; depth < 16 && context instanceof ContextWrapper; depth++) {
                if (context instanceof Activity) return context;
                context = ((ContextWrapper) context).getBaseContext();
            }
        }
        return view.getContext();
    }

    /** The extras button's mark, a circled arrow pointing down at its menu, so it isn't taken for About's "i" beside it. */
    static final class Actions extends Info {
        Actions(float density, int color) {
            super(density, color);
        }

        @Override
        void mark(Canvas canvas, float x, float y) {
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(x - 4 * unit, y - 1.5f * unit, x, y + 2.5f * unit, paint);
            canvas.drawLine(x, y + 2.5f * unit, x + 4 * unit, y - 1.5f * unit, paint);
        }
    }

    /** A circled "i", drawn rather than taken from Instagram's resources, in Instagram's own icon color. */
    static class Info extends Drawable {
        final float unit;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
            mark(canvas, x, y);
        }

        /** What sits inside the circle. */
        void mark(Canvas canvas, float x, float y) {
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
