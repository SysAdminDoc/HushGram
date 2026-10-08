/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import android.content.Context;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Hide the Threads button" patch: takes the Threads button, the one that opens the
 * account's Threads profile, off the top bar of profiles.
 *
 * <p>A profile's top bar builds its buttons from a list, one item per button, each holding the
 * resource id of its icon. The patch hands that list to {@link #buttons} right after the bar gets
 * it. With the switch on, the answer leaves out each item whose icon is one of Instagram's Threads
 * icons, told by the icon's resource name, which Instagram's build keeps. The bar then never adds
 * the button, so no gap is left where it was.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet, an icon
 * that can't be named or anything thrown, the list goes through as it came.
 */
public final class ThreadsButton {
    /** The start of the resource name of each of Instagram's Threads icons. */
    static final String THREADS_ICON = "instagram_app_threads";

    /** What's counted for each Threads button left out. */
    static final String LEFT_OUT = "Threads button left out";

    /** Icon resource names already looked up, "" for an id with no resource. */
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    private ThreadsButton() {
    }

    /**
     * Injected right after a profile's top bar gets its list of buttons. Answers the list to build
     * the bar from: [buttons] itself, or a copy without the Threads button. Never throws.
     */
    public static List<?> buttons(List<?> buttons) {
        try {
            HookStatus.invoked(FamilyNames.THREADS_BUTTON);
            if (buttons == null || !Utils.settingsReady() || !Settings.HIDE_THREADS_BUTTON.get()) return buttons;
            return without(buttons, ThreadsButton::icon, ThreadsButton::iconName);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.THREADS_BUTTON, "profile buttons", failure);
            return buttons;
        }
    }

    /** [buttons] without the ones whose icon, read by [icons] and named by [names], is a Threads icon. */
    static List<?> without(List<?> buttons, ToIntFunction<Object> icons, IntFunction<String> names) {
        List<Object> kept = new ArrayList<>(buttons.size());
        for (Object button : buttons) {
            if (isThreads(icons.applyAsInt(button), names)) {
                HookStatus.counted(FamilyNames.THREADS_BUTTON, LEFT_OUT);
            } else {
                kept.add(button);
            }
        }
        return kept.size() == buttons.size() ? buttons : kept;
    }

    static boolean isThreads(int icon, IntFunction<String> names) {
        if (icon == 0) return false;
        String name = names.apply(icon);
        return name != null && name.startsWith(THREADS_ICON);
    }

    /** The resource name of [icon], "" when there's no such resource, or null with no context yet. */
    static String iconName(int icon) {
        String known = NAMES.get(icon);
        if (known != null) return known;
        Context context = Utils.getContext();
        if (context == null) return null;
        String name;
        try {
            name = context.getResources().getResourceEntryName(icon);
        } catch (Resources.NotFoundException missing) {
            name = "";
        }
        NAMES.put(icon, name);
        return name;
    }

    /**
     * The icon resource id of one of the bar's buttons. A stub: the patch writes its body, which
     * reads the id out of [button]. Answers 0 unpatched, which is never a Threads icon.
     */
    public static int icon(Object button) {
        return 0;
    }
}
