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
 * <p>Instagram 451 shows Threads as a chip under the bio on most profiles instead. The method adding
 * that chip asks {@link #hideBanner} first and skips the chip when it says so.
 *
 * <p>The same list holds the Create button, the plus at the top of your own profile (#254). With
 * Hide the Create button on, the answer leaves out the item with Instagram's add icon too, and the
 * other buttons close up the same way.
 *
 * <p>Instagram 451 builds the plus on your own profile in the profile screen's action bar instead,
 * not from that list. The action bar asks {@link #hideCreateInBar} before it adds the button and
 * skips it when it says so.
 *
 * <p>The hooks fail open: with the switches off, HushGram paused, the settings not read yet, an icon
 * that can't be named or anything thrown, the list goes through as it came and the chip and the plus
 * are added.
 */
public final class ThreadsButton {
    /** The start of the resource name of each of Instagram's Threads icons. */
    static final String THREADS_ICON = "instagram_app_threads";

    /** The start of the resource name of the Create button's icon, the plus on your own profile. */
    static final String CREATE_ICON = "instagram_add_outline";

    /** What's counted for each Threads button left out. */
    static final String LEFT_OUT = "Threads button left out";

    /** What's counted for each Create button left out. */
    static final String CREATE_LEFT_OUT = "Create button left out";

    /** What's counted for each Threads chip left off a profile's header. */
    static final String CHIP_LEFT_OUT = "Threads chip left out";

    /** Icon resource names already looked up, "" for an id with no resource. */
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    private ThreadsButton() {
    }

    /**
     * Injected right after a profile's top bar gets its list of buttons. Answers the list to build
     * the bar from: [buttons] itself, or a copy without the Threads button, the Create button or
     * both, as their switches say. Never throws.
     */
    public static List<?> buttons(List<?> buttons) {
        try {
            HookStatus.invoked(FamilyNames.THREADS_BUTTON);
            if (buttons == null || !Utils.settingsReady()) return buttons;
            boolean threads = Settings.HIDE_THREADS_BUTTON.get();
            boolean create = Settings.HIDE_CREATE_BUTTON.get();
            if (!threads && !create) return buttons;
            return without(buttons, ThreadsButton::icon, ThreadsButton::iconName, threads, create);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.THREADS_BUTTON, "profile buttons", failure);
            return buttons;
        }
    }

    /**
     * Injected first thing in the method that adds the Threads chip to the row of chips under a
     * profile's bio, where Instagram 451 shows the Threads link. Answers true to leave the chip out,
     * so the other chips close up. Never throws, and answers false whenever it can't tell.
     */
    public static boolean hideBanner() {
        try {
            HookStatus.invoked(FamilyNames.THREADS_BUTTON);
            if (!Utils.settingsReady() || !Settings.HIDE_THREADS_BUTTON.get()) return false;
            HookStatus.counted(FamilyNames.THREADS_BUTTON, CHIP_LEFT_OUT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.THREADS_BUTTON, "profile Threads chip", failure);
            return false;
        }
    }

    /**
     * Injected where Instagram 451's profile action bar decides to add the Create button, the plus
     * on your own profile. Answers true to leave it out. Never throws, and answers false whenever it
     * can't tell.
     */
    public static boolean hideCreateInBar() {
        try {
            HookStatus.invoked(FamilyNames.THREADS_BUTTON);
            if (!Utils.settingsReady() || !Settings.HIDE_CREATE_BUTTON.get()) return false;
            HookStatus.counted(FamilyNames.THREADS_BUTTON, CREATE_LEFT_OUT);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.THREADS_BUTTON, "profile Create button", failure);
            return false;
        }
    }

    /**
     * [buttons] without the ones whose icon, read by [icons] and named by [names], is a Threads icon
     * when [threads] is set, or the Create button's when [create] is.
     */
    static List<?> without(List<?> buttons, ToIntFunction<Object> icons, IntFunction<String> names,
                           boolean threads, boolean create) {
        List<Object> kept = new ArrayList<>(buttons.size());
        for (Object button : buttons) {
            int icon = icons.applyAsInt(button);
            if (threads && isThreads(icon, names)) {
                HookStatus.counted(FamilyNames.THREADS_BUTTON, LEFT_OUT);
            } else if (create && isCreate(icon, names)) {
                HookStatus.counted(FamilyNames.THREADS_BUTTON, CREATE_LEFT_OUT);
            } else {
                kept.add(button);
            }
        }
        return kept.size() == buttons.size() ? buttons : kept;
    }

    static boolean isThreads(int icon, IntFunction<String> names) {
        return named(icon, names, THREADS_ICON);
    }

    static boolean isCreate(int icon, IntFunction<String> names) {
        return named(icon, names, CREATE_ICON);
    }

    private static boolean named(int icon, IntFunction<String> names, String start) {
        if (icon == 0) return false;
        String name = names.apply(icon);
        return name != null && name.startsWith(start);
    }

    /**
     * The resource name of [icon], "" when there's no such resource, or null with no context yet.
     * Home's header reads its buttons' icons through this too, so both share one cache.
     */
    public static String iconName(int icon) {
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
