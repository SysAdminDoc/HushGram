/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Hide the Messages tab (#298): Messages' button folds off the tab bar, and Messages stays one of
 * Instagram's tabs.
 *
 * <p>On 452 the tabs swipe, and a tab taken out of Instagram's tab list has no page left to open, so
 * the Messages button on Home's header, a notification or a shared chat would have nowhere to land.
 * So the list keeps Messages and only its button goes: the bar's child with the tab's own id,
 * {@link #DIRECT_TAB}. Whatever opens Messages still finds the tab. The bar is looked at each time an
 * activity resumes and each time it lays out, so the switch shows on the way back from HushGram
 * settings with no restart, and a bar Instagram builds again stays without the button. Main thread.
 */
public final class MessagesTab {
    /** The id name Instagram gives Messages' button on the tab bar, the tab's own id. */
    static final String DIRECT_TAB = "direct_tab";

    /** The id name of the tab bar the buttons are children of. */
    static final String TAB_BAR = "tab_bar";

    /** What the report counts each time the button folds. */
    static final String COUNT_HIDDEN = "Messages tab button hidden";

    /** Each bar the layout listener is on. Main thread only. */
    private static final Map<View, Boolean> WATCHED = new WeakHashMap<>();

    /** Each window whose bar wasn't built yet, with a listener waiting for it. Main thread only. */
    private static final Map<View, Boolean> WAITING = new WeakHashMap<>();

    /** Each button folded, with the visibility it had. Main thread only. */
    private static final Map<View, Integer> HIDDEN = new WeakHashMap<>();

    private MessagesTab() {
    }

    /** Called as each activity resumes. Folds or unfolds the bar's Messages button. Never throws. */
    static void apply(@Nullable Activity activity) {
        try {
            if (activity == null || activity.getWindow() == null) return;
            int bar = id(activity, TAB_BAR);
            int button = id(activity, DIRECT_TAB);
            if (bar == 0 || button == 0) return;
            View decor = activity.getWindow().getDecorView();
            if (find(decor, bar, button)) return;
            if (WAITING.containsKey(decor)) return;
            // A screen whose bar isn't built yet when it resumes: look again as it lays out, until it's there.
            WAITING.put(decor, Boolean.TRUE);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        if (!find(decor, bar, button)) return;
                        WAITING.remove(decor);
                        decor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    } catch (Throwable failure) {
                        HookStatus.threw(FamilyNames.REELS_TAB, "messages tab", failure);
                    }
                }
            });
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REELS_TAB, "messages tab", failure);
        }
    }

    /** Applies to the bar under [decor] with id [bar], and answers whether there's one. */
    private static boolean find(View decor, int bar, int button) {
        View found = decor.findViewById(bar);
        if (!(found instanceof ViewGroup)) return false;
        apply((ViewGroup) found, button);
        return true;
    }

    /** Folds or unfolds [bar]'s child with id [button], and keeps watching [bar]'s layouts. */
    static void apply(ViewGroup bar, int button) {
        View tab = childWithId(bar, button);
        if (tab != null) fold(tab, hiding());
        if (WATCHED.containsKey(bar)) return;
        WATCHED.put(bar, Boolean.TRUE);
        bar.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            try {
                View now = childWithId(bar, button);
                if (now != null) fold(now, hiding());
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.REELS_TAB, "messages tab", failure);
            }
        });
    }

    /** Folds [tab] while [hide] is set, and puts back the visibility of a button this folded when it isn't. */
    static void fold(View tab, boolean hide) {
        if (hide) {
            if (tab.getVisibility() == View.GONE) return;
            HIDDEN.put(tab, tab.getVisibility());
            tab.setVisibility(View.GONE);
            HookStatus.counted(FamilyNames.REELS_TAB, COUNT_HIDDEN);
            Logger.printDebug(() -> "Reels tab: Messages' button is off the tab bar");
            return;
        }
        Integer was = HIDDEN.remove(tab);
        if (was != null && tab.getVisibility() == View.GONE) tab.setVisibility(was);
    }

    /** Whether the button folds: the switch on, HushGram not paused. */
    static boolean hiding() {
        return Utils.settingsReady() && Settings.HIDE_MESSAGES_TAB.get();
    }

    @Nullable
    private static View childWithId(ViewGroup bar, int id) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child.getId() == id) return child;
        }
        return null;
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }
}
