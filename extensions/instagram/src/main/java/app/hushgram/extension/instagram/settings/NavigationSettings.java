/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/** The owned navigation listener is wired only by the structurally proved native tab factory. */
public final class NavigationSettings {
    private NavigationSettings() { }
    // Neither side retains a View or Activity. The live View owns its real listener, as before.
    private static final WeakHashMap<View, Binding> nativeBindings = new WeakHashMap<>();

    private static final class Binding {
        final String tab;
        final WeakReference<View.OnLongClickListener> original;
        final boolean hadOriginal;
        Binding(String tab, View.OnLongClickListener original) {
            this.tab = tab;
            this.original = new WeakReference<>(original);
            this.hadOriginal = original != null;
        }
    }

    public static View.OnLongClickListener remember(View view, Object tab, View.OnLongClickListener original) {
        return remember(view, tab instanceof Enum<?> ? ((Enum<?>) tab).name() : null, original);
    }

    /**
     * Stands in for each setOnLongClickListener call of Instagram's main activity (#82). Besides the
     * tab's own setter, 450's activity puts the account switcher straight on the Profile button,
     * sometimes after the setter has, and the listener kept for Profile was gone by the time a
     * choice reached it, so Profile went on opening the switcher. A tab button takes the listener
     * the way its setter would: the chosen tab keeps opening HushGram, and the others keep the
     * newest listener Instagram gave them. Any other view gets it as it came.
     */
    public static void setOnLongClickListener(View view, View.OnLongClickListener listener) {
        View.OnLongClickListener installed = listener;
        try {
            String tab = null;
            if (view != null) {
                synchronized (nativeBindings) {
                    Binding binding = nativeBindings.get(view);
                    if (binding != null) tab = binding.tab;
                }
            }
            if (tab != null) installed = remember(view, tab, listener);
        } catch (Throwable t) {
            Logger.printException(() -> "Navigation settings: could not take a tab's long press", t);
        }
        view.setOnLongClickListener(installed);
    }

    private static View.OnLongClickListener remember(View view, String tab, View.OnLongClickListener original) {
        if (original instanceof Press) original = ((Press) original).original;
        if (view == null) return original;
        Binding binding = null;
        synchronized (nativeBindings) {
            if (original == null || tab == null) nativeBindings.remove(view);
            else {
                binding = new Binding(tab, original);
                nativeBindings.put(view, binding);
            }
        }
        // A native null setter is teardown. Only the factory may add an entry to a tab without one.
        return binding != null && selected(tab) ? new Press(binding, original) : original;
    }

    public static void bind(View view, Object tab) {
        if (view == null) return;
        View.OnLongClickListener original = null;
        Binding next;
        synchronized (nativeBindings) {
            Binding binding = nativeBindings.get(view);
            if (!(tab instanceof Enum<?>) || (binding != null && !binding.tab.equals(((Enum<?>) tab).name()))) {
                nativeBindings.remove(view);
                return;
            }
            if (!selected(tab)) {
                // Kept so a tab chosen later in settings gets the long press without a restart
                // (#82). The view is left alone, so a tab nobody chose gains no long press.
                if (binding == null) nativeBindings.put(view, new Binding(((Enum<?>) tab).name(), null));
                return;
            }
            if (binding != null) {
                original = binding.original.get();
                if (original == null && binding.hadOriginal) {
                    nativeBindings.remove(view);
                    return;
                }
            }
            next = new Binding(((Enum<?>) tab).name(), original);
            nativeBindings.put(view, next);
        }
        view.setOnLongClickListener(new Press(next, original));
    }

    /**
     * Puts the saved choice on the tabs Instagram has already built, so a change in settings
     * applies at once (#82). It used to wait for the next start, and until then the chosen tab's
     * long press did what it always had, which read as the choice not saving. The chosen tab's
     * long press opens HushGram, and every other tab gets its own listener back, or none when it
     * had none. A tab whose own listener is gone is left as it is. Main thread only. Never throws.
     */
    public static void applyChoice() {
        try {
            List<Map.Entry<View, Binding>> bound;
            synchronized (nativeBindings) {
                bound = new ArrayList<>(nativeBindings.entrySet());
            }
            for (Map.Entry<View, Binding> entry : bound) {
                View view = entry.getKey();
                Binding binding = entry.getValue();
                if (view == null || binding == null) continue;
                View.OnLongClickListener original = binding.original.get();
                if (original == null && binding.hadOriginal) continue;
                NavigationTarget chosen = Utils.settingsReady() ? Settings.NAVIGATION_SETTINGS_TARGET.get() : NavigationTarget.OFF;
                if (chosen != NavigationTarget.OFF && chosen.name().equals(binding.tab)) {
                    view.setOnLongClickListener(new Press(binding, original));
                } else {
                    view.setOnLongClickListener(original);
                    if (original == null) view.setLongClickable(false);
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Navigation settings: could not apply the tab choice", t);
        }
    }

    private static boolean selected(Object tab) {
        return Utils.settingsReady() && Settings.NAVIGATION_SETTINGS_TARGET.get().matches(tab);
    }

    private static final class Press implements View.OnLongClickListener {
        private final Binding binding;
        private final View.OnLongClickListener original;
        Press(Binding binding, View.OnLongClickListener original) { this.binding = binding; this.original = original; }

        @Override public boolean onLongClick(View view) {
            // Retained or programmatic actions on a removed/disabled button must do nothing.
            synchronized (nativeBindings) {
                if (nativeBindings.get(view) != binding) return false;
            }
            Activity owner = ownerOf(view);
            if (owner == null) return false;
            if (Utils.settingsReady() && Settings.NAVIGATION_SETTINGS_TARGET.get() != NavigationTarget.OFF
                    && Settings.NAVIGATION_SETTINGS_TARGET.get().name().equals(binding.tab)
                    && SettingsEntry.requestOpen(owner)) return true;
            return original != null && original.onLongClick(view);
        }

        @Override public boolean onLongClickUseDefaultHapticFeedback(View view) {
            synchronized (nativeBindings) {
                if (nativeBindings.get(view) != binding) return false;
            }
            if (ownerOf(view) == null) return false;
            // Before API 34 Android never calls this method. Native handlers keep their choice.
            return (Utils.settingsReady() && Settings.NAVIGATION_SETTINGS_TARGET.get().name().equals(binding.tab))
                    || original == null || Build.VERSION.SDK_INT < 34
                    || original.onLongClickUseDefaultHapticFeedback(view);
        }
    }

    private static Activity ownerOf(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()) return null;
        Context context = view.getContext();
        for (int depth = 0; depth < 16 && context instanceof ContextWrapper && !(context instanceof Activity); depth++) {
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        if (!(context instanceof Activity)) return null;
        Activity activity = (Activity) context;
        if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return null;
        View decor = activity.getWindow().getDecorView();
        View current = view;
        for (int depth = 0; depth < 64; depth++) {
            if (!current.isEnabled() || current.getVisibility() != View.VISIBLE) return null;
            if (current == decor) return activity;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return null;
            current = (View) parent;
        }
        return null;
    }
}
