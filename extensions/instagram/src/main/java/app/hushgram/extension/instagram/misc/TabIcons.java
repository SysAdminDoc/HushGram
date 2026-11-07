/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

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
 * tab the icon (or, on Profile, the picture) is drawn bigger from its own center. The tab's touch area
 * and the badges beside the icon stay as Instagram made them. Instagram animates an icon's size when
 * it's pressed, so an icon caught at a size that is neither Instagram's nor the one given here is left
 * to finish, and gets its size again once it's back. Instagram's size, a pause, or anything thrown puts
 * every icon back as it was.
 */
public final class TabIcons {
    /** Instagram's tab bar, whose children are the tabs. */
    static final String TAB_BAR = "tab_bar";
    /** A tab's icon. */
    static final String ICON = "tab_icon";
    /** The Profile tab's picture, which it shows in place of an icon. */
    static final String AVATAR = "tab_avatar";
    static final String HOOK = "tab icon size";
    static final String COUNT_SIZED = "tab icons resized";
    /** The most tabs looked at on one bar. */
    private static final int MAX_TABS = 10;

    private static final ResourceIdCache ids = new ResourceIdCache();
    private static final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    /** The icons given a size here, and the size, so only those are put back. */
    private static final WeakHashMap<View, Float> sized = new WeakHashMap<>();
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
        boolean changed = false;
        int count = Math.min(bar.getChildCount(), MAX_TABS);
        for (int i = 0; i < count; i++) {
            View icon = iconOf(bar.getChildAt(i), iconId, avatarId);
            if (icon != null) changed |= scale(icon, wanted);
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

    /** Puts every icon sized here back at Instagram's size. Answers whether one changed. */
    static boolean putBack() {
        boolean changed = false;
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
        return changed;
    }

    /**
     * Sizes the icons on [owner]'s bar. The bar is looked for after a layout, or when the one found
     * before has left the window, so a frame without either costs a look at the tabs alone.
     */
    private static boolean apply(Activity owner, Session session, boolean laidOut) {
        float wanted = wantedScale();
        if (wanted == 1f) return !sized.isEmpty() && putBack();
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
        return size((ViewGroup) bar, id(owner, ICON), id(owner, AVATAR), wanted);
    }

    private static int id(Activity activity, String name) {
        return ids.resolve(activity.getResources(), activity.getPackageName(), name, false);
    }

    /** Tests give a view an id of their own, since a test app has none of Instagram's. */
    static void idForTests(Context context, String name, int id) {
        ids.putForTests(context.getPackageName(), name, id);
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
