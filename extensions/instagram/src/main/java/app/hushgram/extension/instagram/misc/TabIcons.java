/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.ResourceIdCache;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ViewWatcher;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Draws the tab bar's icons at the size picked under Tab icon size (#541).
 *
 * <p>No part of Instagram's code changes. Before each frame the bar is found by its name, and in each
 * tab the icon (or, on Profile, the picture) is drawn bigger from its own center, with its parents up
 * to the tab no longer cutting it off at its old size. The tab's touch area
 * and the badges beside the icon stay as Instagram made them. Instagram animates an icon's size when
 * it's pressed, so an icon caught at a size that is neither Instagram's nor the one given here is left
 * to finish, and gets its size again once it's back. Instagram's size, a pause, or anything thrown puts
 * every icon back as it was.
 *
 * <p>An icon that shows a picture of its own is drawn bigger through its picture, not the whole view
 * (#826): most of Instagram's icons are vector drawings, which Android draws into a bitmap at the size
 * the view lays them out, so growing the view stretched that bitmap and left the icon soft. Drawn
 * through the image's own matrix, the drawing is made at the bigger size and stays sharp. The view
 * itself, which Instagram's press animation scales, keeps its size. The Reels icon is a small bitmap
 * instead, which goes soft drawn any bigger, so while it's drawn at another size {@link SharpReelsIcon}
 * draws the same shape as a path. The Profile picture, a photo, is still drawn bigger as a whole.
 *
 * <p>Full-size Reels icon draws the Reels tab's icon {@link #REELS_BOOST} times bigger on top of that
 * (#793): its glyph is narrower than the ones beside it, so at the same size it looks smaller. The tab
 * is told by its own name, wherever Tab order put it.
 */
public final class TabIcons {
    /** Instagram's tab bar, whose children are the tabs. */
    static final String TAB_BAR = "tab_bar";
    /** A tab's icon. */
    static final String ICON = "tab_icon";
    /** The Profile tab's picture, which it shows in place of an icon. */
    static final String AVATAR = "tab_avatar";
    /** The Reels tab. */
    static final String REELS_TAB = "clips_tab";
    /**
     * How much bigger Full-size Reels icon draws the Reels icon: its glyph is about 58 pixels wide where
     * Home's and Messages' are about 68 on a phone at 480 dpi.
     */
    static final float REELS_BOOST = 1.15f;
    static final String HOOK = "tab icon size";
    static final String COUNT_SIZED = "tab icons resized";
    /** The most tabs looked at on one bar. */
    private static final int MAX_TABS = 10;

    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** The most parents between an icon and its tab that are looked at. */
    private static final int MAX_DEPTH = 8;

    /** The icons given a size here, and the size, so only those are put back. */
    private static final WeakHashMap<View, Float> sized = new WeakHashMap<>();
    /** The icons whose picture is drawn bigger here, and how each picture was placed before. */
    private static final WeakHashMap<ImageView, Placed> pictured = new WeakHashMap<>();
    /** The icons given a {@link SharpReelsIcon} here in place of Instagram's picture. */
    private static final WeakHashMap<ImageView, SharpReelsIcon> sharpened = new WeakHashMap<>();
    /** The parents told not to clip here, and whether each clipped its children and its padding before. */
    private static final WeakHashMap<ViewGroup, boolean[]> unclipped = new WeakHashMap<>();
    private static volatile boolean registered;

    private TabIcons() {
    }

    /** Called as the application starts. Does nothing in a build without the tab bar's patches. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.reelsTab()) return;
            registered = true;
            ViewWatcher.onResumeAndPause((Application) context, TabIcons::watch, TabIcons::unwatch);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, HOOK + " start", failure);
        }
    }

    /** The scale the icons are drawn at now: 1 while the settings aren't read, while paused, or for Instagram's size. */
    static float wantedScale() {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return 1f;
        TabIconSize size = Settings.TAB_ICON_SIZE.get();
        return size == null ? 1f : size.scale;
    }

    /** How much bigger the Reels icon is drawn than the others: 1 while the settings aren't read, while paused, or with the switch off. */
    static float reelsScale() {
        if (!Utils.settingsReady() || HushgramPause.isPaused()) return 1f;
        return Settings.FULL_SIZE_REELS_ICON.get() ? REELS_BOOST : 1f;
    }

    static void watch(Activity activity) {
        try {
            if (!Utils.settingsReady()) return;
            Session session;
            synchronized (sessions) {
                session = sessions.get(activity);
                if (session == null) {
                    session = new Session();
                    sessions.put(activity, session);
                }
            }
            if (ViewWatcher.joined(activity, session)) return;
            ViewWatcher.join(activity, session);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, HOOK, failure);
        }
    }

    static void unwatch(Activity activity) {
        try {
            Session session;
            synchronized (sessions) {
                session = sessions.remove(activity);
            }
            if (session != null) ViewWatcher.leave(activity, session);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, HOOK, failure);
        }
    }

    /** Gives each tab's icon on [bar] the [wanted] scale. Answers whether one changed. */
    static boolean size(ViewGroup bar, int iconId, int avatarId, float wanted) {
        return size(bar, iconId, avatarId, 0, wanted, 1f);
    }

    /**
     * Gives each tab's icon on [bar] the [wanted] scale, and the tab whose id is [reelsTabId] [reels]
     * times that. Answers whether one changed.
     */
    static boolean size(ViewGroup bar, int iconId, int avatarId, int reelsTabId, float wanted, float reels) {
        boolean changed = false;
        int count = Math.min(bar.getChildCount(), MAX_TABS);
        for (int i = 0; i < count; i++) {
            View tab = bar.getChildAt(i);
            View icon = iconOf(tab, iconId, avatarId);
            if (icon == null) continue;
            float scale = reelsTabId != 0 && tab.getId() == reelsTabId ? wanted * reels : wanted;
            if (iconId != 0 && icon.getId() == iconId && icon instanceof ImageView && hasPicture((ImageView) icon)) {
                // Any size given to the whole view before goes first, so the two never stack.
                changed |= scale(icon, 1f);
                changed |= sharpen((ImageView) icon, scale != 1f);
                changed |= picture((ImageView) icon, scale);
            } else {
                if (icon instanceof ImageView) changed |= picture((ImageView) icon, 1f);
                changed |= scale(icon, scale);
            }
            if (scale != 1f) changed |= unclip(icon, tab);
        }
        return changed;
    }

    /**
     * Lets [icon] draw past its parents' edges, up to and including [tab], so a bigger icon isn't cut
     * off where its old size ended (the Profile picture came out square). The bar still holds it in.
     * Answers whether a parent changed.
     */
    private static boolean unclip(View icon, View tab) {
        boolean changed = false;
        ViewParent parent = icon.getParent();
        for (int depth = 0; parent instanceof ViewGroup && depth < MAX_DEPTH; depth++) {
            ViewGroup group = (ViewGroup) parent;
            if (!unclipped.containsKey(group)) {
                unclipped.put(group, new boolean[]{group.getClipChildren(), group.getClipToPadding()});
            }
            if (group.getClipChildren()) {
                group.setClipChildren(false);
                changed = true;
            }
            if (group.getClipToPadding()) {
                group.setClipToPadding(false);
                changed = true;
            }
            if (group == tab) break;
            parent = group.getParent();
        }
        return changed;
    }

    /** The icon in [tab], its picture when it has no icon, or null. */
    @Nullable
    static View iconOf(View tab, int iconId, int avatarId) {
        View icon = iconId == 0 ? null : tab.findViewById(iconId);
        if (icon == null && avatarId != 0) icon = tab.findViewById(avatarId);
        return icon == tab ? null : icon;
    }

    private static boolean scale(View icon, float wanted) {
        Float given = sized.get(icon);
        float now = icon.getScaleX();
        if (wanted == 1f) {
            if (given == null) return false;
            sized.remove(icon);
            if (now != given || icon.getScaleY() != given) return false;
            icon.setScaleX(1f);
            icon.setScaleY(1f);
            return true;
        }
        if (now == wanted && icon.getScaleY() == wanted) {
            if (given == null) sized.put(icon, wanted);
            return false;
        }
        // Pressed: Instagram's own animation has the icon. It's sized again once that ends.
        boolean ours = given != null && now == given && icon.getScaleY() == given;
        if (!ours && (now != 1f || icon.getScaleY() != 1f)) return false;
        sized.put(icon, wanted);
        icon.setScaleX(wanted);
        icon.setScaleY(wanted);
        HookStatus.counted(FamilyNames.REELS_TAB, COUNT_SIZED);
        return true;
    }

    /** Whether [icon] shows a picture with a size of its own, laid out, so it can be drawn bigger through it. */
    static boolean hasPicture(ImageView icon) {
        Drawable picture = icon.getDrawable();
        return picture != null && picture.getIntrinsicWidth() > 0 && picture.getIntrinsicHeight() > 0
                && contentWidth(icon) > 0 && contentHeight(icon) > 0;
    }

    /**
     * While [bigger], has [icon] draw Instagram's Reels picture as a path, which stays sharp at any size;
     * otherwise gives it back Instagram's picture. Any other picture is left as it is. Answers whether
     * it changed.
     */
    private static boolean sharpen(ImageView icon, boolean bigger) {
        Drawable shown = icon.getDrawable();
        if (shown instanceof SharpReelsIcon) {
            if (bigger) return false;
            sharpened.remove(icon);
            ((SharpReelsIcon) shown).giveBack(icon);
            return true;
        }
        if (!bigger || shown == null) return false;
        String owner = Utils.resourcePackage(icon.getContext());
        if (SharpReelsIcon.kind(shown, icon.getResources(), owner) == SharpReelsIcon.NONE) return false;
        SharpReelsIcon sharp = new SharpReelsIcon(shown, icon.getResources(), owner);
        icon.setImageDrawable(sharp);
        // Taking the new picture, the view let go of Instagram's, which the stand-in draws now.
        shown.setCallback(sharp);
        sharpened.put(icon, sharp);
        return true;
    }

    /**
     * Draws [icon]'s picture [wanted] times bigger from the view's center through the image's own
     * matrix, or with [wanted] at 1 puts it back as Instagram placed it. Answers whether it changed.
     */
    private static boolean picture(ImageView icon, float wanted) {
        Placed placed = pictured.get(icon);
        if (wanted == 1f) {
            if (placed == null) return false;
            pictured.remove(icon);
            return placed.putBack(icon);
        }
        if (placed != null && !placed.stillOurs(icon)) placed = null;
        if (placed == null) placed = new Placed(icon);
        Matrix bigger = placement(icon, placed.type, placed.matrix);
        bigger.postScale(wanted, wanted, icon.getWidth() / 2f - icon.getPaddingLeft(),
                icon.getHeight() / 2f - icon.getPaddingTop());
        pictured.put(icon, placed);
        if (placed.given != null && placed.given.equals(bigger) && placed.stillOurs(icon)) return false;
        placed.given = bigger;
        icon.setScaleType(ImageView.ScaleType.MATRIX);
        icon.setImageMatrix(bigger);
        HookStatus.counted(FamilyNames.REELS_TAB, COUNT_SIZED);
        return true;
    }

    /**
     * Where [icon] would draw its picture with [type] (and [matrix] for {@link ImageView.ScaleType#MATRIX}),
     * as the matrix from the picture's own size to the view: the same placement ImageView works out.
     */
    static Matrix placement(ImageView icon, ImageView.ScaleType type, @Nullable Matrix matrix) {
        Drawable picture = icon.getDrawable();
        float pw = picture.getIntrinsicWidth();
        float ph = picture.getIntrinsicHeight();
        float vw = contentWidth(icon);
        float vh = contentHeight(icon);
        Matrix placed = new Matrix();
        switch (type) {
            case MATRIX:
                if (matrix != null) placed.set(matrix);
                break;
            case CENTER:
                placed.setTranslate(Math.round((vw - pw) * 0.5f), Math.round((vh - ph) * 0.5f));
                break;
            case CENTER_CROP: {
                float scale;
                float dx = 0;
                float dy = 0;
                if (pw * vh > vw * ph) {
                    scale = vh / ph;
                    dx = (vw - pw * scale) * 0.5f;
                } else {
                    scale = vw / pw;
                    dy = (vh - ph * scale) * 0.5f;
                }
                placed.setScale(scale, scale);
                placed.postTranslate(Math.round(dx), Math.round(dy));
                break;
            }
            case CENTER_INSIDE: {
                float scale = pw <= vw && ph <= vh ? 1f : Math.min(vw / pw, vh / ph);
                placed.setScale(scale, scale);
                placed.postTranslate(Math.round((vw - pw * scale) * 0.5f), Math.round((vh - ph * scale) * 0.5f));
                break;
            }
            default:
                placed.setRectToRect(new RectF(0, 0, pw, ph), new RectF(0, 0, vw, vh), fit(type));
                break;
        }
        return placed;
    }

    private static Matrix.ScaleToFit fit(ImageView.ScaleType type) {
        switch (type) {
            case FIT_XY:
                return Matrix.ScaleToFit.FILL;
            case FIT_START:
                return Matrix.ScaleToFit.START;
            case FIT_END:
                return Matrix.ScaleToFit.END;
            default:
                return Matrix.ScaleToFit.CENTER;
        }
    }

    private static int contentWidth(View view) {
        return view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
    }

    private static int contentHeight(View view) {
        return view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
    }

    /** How an icon's picture was placed before it was drawn bigger here, and the matrix given it here. */
    static final class Placed {
        final ImageView.ScaleType type;
        /** Instagram's own matrix, when its scale type was {@link ImageView.ScaleType#MATRIX}. */
        @Nullable
        final Matrix matrix;
        @Nullable
        Matrix given;

        Placed(ImageView icon) {
            type = icon.getScaleType();
            matrix = type == ImageView.ScaleType.MATRIX ? new Matrix(icon.getImageMatrix()) : null;
        }

        /** Whether [icon] still draws with the matrix given here, so nothing else has placed it since. */
        boolean stillOurs(ImageView icon) {
            return given != null && icon.getScaleType() == ImageView.ScaleType.MATRIX && icon.getImageMatrix().equals(given);
        }

        /** Places [icon]'s picture as before, unless something else placed it since. Answers whether it changed. */
        boolean putBack(ImageView icon) {
            if (!stillOurs(icon)) return false;
            icon.setScaleType(type);
            // A scale type alone waits for the next layout to place the picture again. A new matrix
            // places it now, and other scale types don't read it.
            icon.setImageMatrix(type == ImageView.ScaleType.MATRIX ? matrix : null);
            return true;
        }
    }

    /** Puts every icon sized here back at Instagram's size. Answers whether one changed. */
    static boolean putBack() {
        boolean changed = false;
        for (Map.Entry<ImageView, SharpReelsIcon> each : new ArrayList<>(sharpened.entrySet())) {
            ImageView icon = each.getKey();
            if (icon == null || icon.getDrawable() != each.getValue()) continue;
            each.getValue().giveBack(icon);
            changed = true;
        }
        sharpened.clear();
        for (Map.Entry<ImageView, Placed> each : new ArrayList<>(pictured.entrySet())) {
            if (each.getKey() != null) changed |= each.getValue().putBack(each.getKey());
        }
        pictured.clear();
        for (Map.Entry<View, Float> each : new ArrayList<>(sized.entrySet())) {
            View icon = each.getKey();
            if (icon == null) continue;
            if (icon.getScaleX() == each.getValue() && icon.getScaleY() == each.getValue()) {
                icon.setScaleX(1f);
                icon.setScaleY(1f);
                changed = true;
            }
        }
        sized.clear();
        for (Map.Entry<ViewGroup, boolean[]> each : new ArrayList<>(unclipped.entrySet())) {
            ViewGroup group = each.getKey();
            if (group == null) continue;
            boolean[] was = each.getValue();
            if (was[0] && !group.getClipChildren()) {
                group.setClipChildren(true);
                changed = true;
            }
            if (was[1] && !group.getClipToPadding()) {
                group.setClipToPadding(true);
                changed = true;
            }
        }
        unclipped.clear();
        return changed;
    }

    /**
     * Sizes the icons on [owner]'s bar. The bar is looked for after a layout, or when the one found
     * before has left the window, so a frame without either costs a look at the tabs alone.
     */
    private static boolean apply(Activity owner, Session session, boolean laidOut) {
        float wanted = wantedScale();
        float reels = reelsScale();
        if (wanted == 1f && reels == 1f) {
            return (!sized.isEmpty() || !unclipped.isEmpty() || !pictured.isEmpty() || !sharpened.isEmpty()) && putBack();
        }
        View bar = session.bar == null ? null : session.bar.get();
        if (laidOut || bar == null || !bar.isAttachedToWindow()) {
            int barId = id(owner, TAB_BAR);
            if (barId == 0) {
                HookStatus.missingViewId(FamilyNames.REELS_TAB, TAB_BAR);
                return false;
            }
            bar = owner.getWindow().getDecorView().findViewById(barId);
            session.bar = bar == null ? null : new WeakReference<>(bar);
        }
        if (!(bar instanceof ViewGroup) || !bar.isShown()) return false;
        int reelsTab = reels == 1f ? 0 : id(owner, REELS_TAB);
        if (reels != 1f && reelsTab == 0) HookStatus.missingViewId(FamilyNames.REELS_TAB, REELS_TAB);
        return size((ViewGroup) bar, id(owner, ICON), id(owner, AVATAR), reelsTab, wanted, reels);
    }

    private static int id(Activity activity, String name) {
        return ids.resolve(activity.getResources(), Utils.resourcePackage(activity), name, false);
    }

    /** Tests give a view an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, String name, int id) {
        ids.putForTests(Utils.resourcePackage(context), name, id);
    }

    static boolean listeningForTests(Activity activity) {
        synchronized (sessions) {
            Session session = sessions.get(activity);
            return session != null && ViewWatcher.listening(activity, session);
        }
    }

    static void resetForTests() {
        ids.clear();
        putBack();
        SharpReelsIcon.resetForTests();
        synchronized (sessions) {
            for (Map.Entry<Activity, Session> each : new ArrayList<>(sessions.entrySet())) {
                if (each.getKey() != null) ViewWatcher.leave(each.getKey(), each.getValue());
            }
            sessions.clear();
        }
    }

    /** One activity's place in the shared frame watcher. */
    private static final class Session implements ViewWatcher.Member {
        /** The bar found last, looked for again after a layout. */
        @Nullable
        WeakReference<View> bar;

        @Override
        public boolean wanted() {
            return Utils.settingsReady();
        }

        @Override
        public boolean preDraw(Activity owner, boolean laidOut) {
            try {
                return apply(owner, this, laidOut);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REELS_TAB, HOOK, failure);
                putBack();
                return false;
            }
        }
    }
}
