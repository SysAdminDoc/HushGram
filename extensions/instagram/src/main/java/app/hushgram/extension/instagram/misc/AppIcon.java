/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsEntry;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Pick the app icon" patch (#284): the icon on the home screen and in the app drawer.
 *
 * <p>Android shows an icon for each enabled launcher activity. Instagram's is the alias
 * {@link #INSTAGRAM_ALIAS}, and the patch adds a copy of it per {@link AppIconChoice}, off, with its
 * own icon, named {@link #HUSHGRAM_ALIAS} and the choice's key. {@link #apply} turns the picked one on
 * and Instagram's off when Instagram goes to the background, the way Instagram changes its own
 * alternate icons, since a launcher activity switched off under a running screen can close it. It
 * changes only what differs, and puts the new icon on before the old one comes off, so there's never
 * a moment without one.
 *
 * <p>Instagram turns its alias back on at start whenever none of its own alternate icons is on, and
 * its component manager turns on any component the manifest doesn't say to leave alone. The patch
 * sends every call Instagram makes to turn a component on or off here instead, and
 * {@link #keepsOut} drops the ones that would touch HushGram's aliases or bring Instagram's back
 * while one of HushGram's is on. It goes by what's on, not by the setting, so it holds before the
 * settings are read. HushGram's aliases carry the meta-data that tells Instagram's component manager
 * to leave them as they are.
 *
 * <p>A build without the patch has no HushGram aliases, and Android forgets the state of a component
 * that's gone, so Instagram's own start turns its alias back on there.
 */
public final class AppIcon {
    /** Instagram's launcher alias, which the patch's aliases copy. */
    static final String INSTAGRAM_ALIAS = "com.instagram.android.activity.MainTabActivity";

    /** The start of each HushGram alias's name, which ends in its choice's key. */
    static final String HUSHGRAM_ALIAS = INSTAGRAM_ALIAS + ".hushgram_";

    /** What's counted each time one of Instagram's calls is dropped, and each time the icon changes. */
    static final String KEPT_OUT = "Instagram's change to the launcher icon kept out";
    static final String CHANGED = "home screen icon changed";

    private static final int ON = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    private static final int OFF = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    private static final int AS_SHIPPED = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;

    private static final Object LOCK = new Object();
    private static volatile boolean registered;
    /** Instagram's screens started and not yet stopped, on the main thread. */
    private static int started;
    /** The icon changed, so the HushGram shortcut is checked again when Instagram is back in front. */
    private static volatile boolean shortcutStale;

    private AppIcon() {
    }

    /** Called as the application starts. Watches Instagram's screens in a build with the patch. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.appIcon()
                    || !Utils.isMainProcess()) return;
            registered = true;
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.APP_ICON, "start", failure);
        }
    }

    // Instagram's own calls that turn a component on or off come here instead: the patch sends each
    // PackageManager call of these names to the method of the same name here, the manager first.

    public static void setComponentEnabledSetting(PackageManager manager, ComponentName component, int state, int flags) {
        if (keepsOut(manager, component, state)) return;
        manager.setComponentEnabledSetting(component, state, flags);
    }

    /** The same for several at once, which Android 13 added. The ones kept out are left out of the call. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static void setComponentEnabledSettings(PackageManager manager,
                                                   List<PackageManager.ComponentEnabledSetting> settings) {
        List<PackageManager.ComponentEnabledSetting> kept = null;
        for (int at = 0; at < settings.size(); at++) {
            PackageManager.ComponentEnabledSetting setting = settings.get(at);
            boolean out = setting != null && keepsOut(manager, setting.getComponentName(), setting.getEnabledState());
            if (out && kept == null) kept = new ArrayList<>(settings.subList(0, at));
            else if (!out && kept != null) kept.add(setting);
        }
        if (kept == null) manager.setComponentEnabledSettings(settings);
        else if (!kept.isEmpty()) manager.setComponentEnabledSettings(kept);
    }

    /**
     * Whether Instagram's call setting [component] to [state] is dropped: any call on a HushGram alias,
     * and a call that would turn Instagram's alias on while a HushGram alias is on. Turning Instagram's
     * off always goes through. Anything thrown lets the call go through.
     */
    static boolean keepsOut(PackageManager manager, @Nullable ComponentName component, int state) {
        try {
            if (component == null) return false;
            String name = component.getClassName();
            boolean ours = name.startsWith(HUSHGRAM_ALIAS);
            if (!ours && (!INSTAGRAM_ALIAS.equals(name) || state == OFF)) return false;
            HookStatus.invoked(FamilyNames.APP_ICON);
            if (!ours && turnedOn(manager, component.getPackageName()) == null) return false;
            HookStatus.counted(FamilyNames.APP_ICON, KEPT_OUT);
            return true;
        } catch (RuntimeException failure) {
            HookStatus.threw(FamilyNames.APP_ICON, "Instagram's icon change", failure);
            return false;
        }
    }

    /** The HushGram icon whose alias is on in [owner], the package, or null when none is. */
    @Nullable
    static AppIconChoice turnedOn(PackageManager manager, String owner) {
        for (AppIconChoice choice : AppIconChoice.values()) {
            if (choice.key != null && manager.getComponentEnabledSetting(alias(owner, choice)) == ON) return choice;
        }
        return null;
    }

    /**
     * The icon [choice] gives the home screen, as its alias declares it (adaptive layers and all), for
     * the picker to draw (#321, #327). Null when this build has no such alias or it can't be loaded.
     */
    @Nullable
    public static android.graphics.drawable.Drawable preview(Context context, AppIconChoice choice) {
        try {
            PackageManager manager = context.getPackageManager();
            android.content.pm.ActivityInfo info = manager.getActivityInfo(
                    alias(context.getPackageName(), choice), PackageManager.MATCH_DISABLED_COMPONENTS);
            return info.loadIcon(manager);
        } catch (Throwable absent) {
            return null;
        }
    }

    static ComponentName alias(String owner, AppIconChoice choice) {
        return new ComponentName(owner, choice.key == null ? INSTAGRAM_ALIAS : HUSHGRAM_ALIAS + choice.key);
    }

    /**
     * Turns the picked icon's alias on and the others off, on the thread it's called on. Instagram's
     * alias comes back on only when it's a HushGram alias going off, so one of Instagram's own
     * alternate icons stays as Instagram left it. Answers whether anything changed. Does nothing
     * before the settings are read. Never throws.
     */
    static boolean apply(Context context) {
        try {
            if (!Utils.settingsReady()) return false;
            HookStatus.invoked(FamilyNames.APP_ICON);
            AppIconChoice wanted = Settings.APP_ICON.get();
            PackageManager manager = context.getPackageManager();
            String owner = context.getPackageName();
            boolean changed = false;
            synchronized (LOCK) {
                if (wanted.key != null) {
                    changed = set(manager, alias(owner, wanted), ON);
                } else if (turnedOn(manager, owner) != null) {
                    changed = set(manager, alias(owner, AppIconChoice.INSTAGRAM), AS_SHIPPED);
                }
                for (AppIconChoice choice : AppIconChoice.values()) {
                    if (choice.key != null && choice != wanted
                            && manager.getComponentEnabledSetting(alias(owner, choice)) == ON) {
                        changed |= set(manager, alias(owner, choice), AS_SHIPPED);
                    }
                }
                if (wanted.key != null) changed |= set(manager, alias(owner, AppIconChoice.INSTAGRAM), OFF);
            }
            if (changed) {
                shortcutStale = true;
                HookStatus.counted(FamilyNames.APP_ICON, CHANGED);
            }
            return changed;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.APP_ICON, "icon change", failure);
            return false;
        }
    }

    /** Sets [component] to [state] when it isn't already. Answers whether it changed. */
    private static boolean set(PackageManager manager, ComponentName component, int state) {
        if (manager.getComponentEnabledSetting(component) == state) return false;
        manager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
        return true;
    }

    /** One of Instagram's screens started: the shortcut is checked again after an icon change. */
    static void started(Activity activity) {
        started++;
        if (!shortcutStale) return;
        shortcutStale = false;
        SettingsEntry.shortcutSwitchChanged(activity);
    }

    /**
     * One of Instagram's screens stopped. Once none is left, the icon changes on a background thread.
     * A screen turning sideways stops and starts again at once, which isn't leaving.
     */
    static void stopped(Activity activity) {
        if (started > 0) started--;
        if (started > 0 || activity.isChangingConfigurations()) return;
        Context app = activity.getApplicationContext() != null ? activity.getApplicationContext() : activity;
        Utils.runOnBackgroundThread(() -> apply(app));
    }

    /** Forgets what this process knows, as a new process would. */
    static void forgetForTests() {
        started = 0;
        shortcutStale = false;
    }

    static boolean shortcutStaleForTests() {
        return shortcutStale;
    }

    static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityStarted(@NonNull Activity activity) {
            started(activity);
        }

        @Override public void onActivityStopped(@NonNull Activity activity) {
            stopped(activity);
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityResumed(@NonNull Activity activity) { }
        @Override public void onActivityPaused(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
        @Override public void onActivityDestroyed(@NonNull Activity activity) { }
    }
}
