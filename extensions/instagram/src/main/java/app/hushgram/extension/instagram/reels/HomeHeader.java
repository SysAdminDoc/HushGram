/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

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
 * Helper for the Hide the Reels tab patch's Tab bar switches that take buttons off Home's header:
 * the Create (plus) button and the notifications heart.
 *
 * <p>Home's header draws its buttons from a list in its state, one item per button. The patch hands
 * that list to {@link #buttons} as the state is made. With a switch on, the answer leaves out the
 * heart (told by its item type, which the patch tells {@link #heart} by) or the Create button (told
 * by its icon's resource name, which Instagram's build keeps). The header then never adds the
 * button, so no gap is left where it was. The header's other buttons, the Messages button
 * included, are untouched, and so are the stories row and the feed.
 *
 * <p>The hook fails open: a switch off, HushGram paused, the settings not read yet, an icon that
 * can't be named or anything thrown, and the list goes through as it came.
 */
public final class HomeHeader {
    /** The start of the resource name of Instagram's plus icon, which the Create button on Home's header wears. */
    static final String CREATE_ICON = "instagram_add_";

    /** What's counted for each button left out. */
    static final String CREATE_LEFT_OUT = "Home header Create button left out";
    static final String NOTIFICATIONS_LEFT_OUT = "Home header notifications button left out";

    /** Icon resource names already looked up, "" for an id with no resource. */
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();

    private HomeHeader() {
    }

    /**
     * Injected as Home's header state takes its list of buttons. Answers the list to draw the header
     * from: [buttons] itself, or a copy without the buttons whose switch is on. Never throws.
     */
    public static List<?> buttons(List<?> buttons) {
        try {
            HookStatus.invoked(FamilyNames.REELS_TAB);
            if (buttons == null || buttons.isEmpty() || !Utils.settingsReady()) return buttons;
            boolean create = Settings.HIDE_HOME_CREATE_BUTTON.get();
            boolean notifications = Settings.HIDE_HOME_NOTIFICATIONS_BUTTON.get();
            if (!create && !notifications) return buttons;
            return without(buttons, create, notifications, HomeHeader::icon, HomeHeader::heart, HomeHeader::iconName);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "home header buttons", failure);
            return buttons;
        }
    }

    /**
     * [buttons] without the Create button when [create] is set and without the heart when
     * [notifications] is set, in the same order, or [buttons] itself when nothing is taken out.
     * [icons] reads a button's icon, [hearts] says whether a button is the heart and [names] names
     * an icon.
     */
    static List<?> without(List<?> buttons, boolean create, boolean notifications, ToIntFunction<Object> icons,
                           ToIntFunction<Object> hearts, IntFunction<String> names) {
        List<Object> kept = new ArrayList<>(buttons.size());
        for (Object button : buttons) {
            if (notifications && hearts.applyAsInt(button) != 0) {
                HookStatus.counted(FamilyNames.REELS_TAB, NOTIFICATIONS_LEFT_OUT);
            } else if (create && isCreate(icons.applyAsInt(button), names)) {
                HookStatus.counted(FamilyNames.REELS_TAB, CREATE_LEFT_OUT);
            } else {
                kept.add(button);
            }
        }
        return kept.size() == buttons.size() ? buttons : kept;
    }

    static boolean isCreate(int icon, IntFunction<String> names) {
        if (icon == 0) return false;
        String name = names.apply(icon);
        return name != null && name.startsWith(CREATE_ICON);
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
     * The icon resource id of one of the header's buttons. A stub: the patch writes its body, which
     * reads the id out of [button]. Answers 0 unpatched, which is never the Create icon.
     */
    public static int icon(Object button) {
        return 0;
    }

    /**
     * 1 when [button] is the notifications heart. A stub: the patch writes its body, which asks the
     * button's type. Answers 0 unpatched.
     */
    public static int heart(Object button) {
        return 0;
    }
}
