/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Stop swipe to create" patch.
 *
 * <p>Home sits in a container that slides sideways: the camera lies past its start edge, at -1,
 * Home at 0, and whatever Instagram puts past the other edge at 1. Every move of the container goes
 * through one method, whether a finger drags it, lets go of it or a button sends it, and each move
 * says why it happened. A finger's moves say {@link #DRAG}. The patch hands each move to
 * {@link #hold} just before the container slides.
 *
 * <p>While the switch is on, a drag from Home or the far panel toward the camera is held at Home,
 * so the camera never opens from a swipe. The + button and the other ways into the camera send
 * their own reasons, and a swipe that starts in the camera (back to Home) starts below 0, so both go on as
 * before.
 *
 * <p>{@code Swipe right on Home opens} ({@link HomeSwipe}, #589) picks a tab for that swipe. The same
 * moves are held, the panels never leave Home, and the tab's button is clicked once the finger has gone
 * {@link #OPEN_AT} of the screen's width or flung that way. Held at Home, each step of a drag comes in as
 * how far that step went, so the steps add up to the swipe. A tab that isn't on the bar leaves the swipe
 * to the switch, as if none were picked.
 *
 * <p>The hook fails open: with the switch off, no tab picked, HushGram paused, the settings not read yet
 * or anything thrown, the move goes where Instagram sent it.
 */
public final class SwipeToCreate {
    /** The reason Instagram gives every move a finger makes. */
    static final String DRAG = "swipe";

    /** How much of the screen's width a swipe covers before it opens the picked tab. */
    static final float OPEN_AT = 0.25f;

    /** Where a fling's move ends: the camera's panel. No drag step gets that far. */
    private static final float FLUNG = -1f;

    /** A step this long after the last one belongs to a new swipe. */
    static final long NEW_SWIPE_AFTER_MS = 300;

    static final String TAB_BAR = "tab_bar";

    /** What a swipe that opened its tab is counted under. */
    static final String COUNT_OPENED = "Swipe right on Home opened the picked tab";

    /** How far toward the camera this swipe has gone, as a share of the screen's width. */
    private static float swiped;
    /** When this swipe's last step came, by the uptime clock. */
    private static long lastStep = Long.MIN_VALUE / 2;
    /** Whether this swipe has opened its tab. */
    private static boolean opened;
    /** The picked tab's button for this swipe, cleared when it's not on the bar. */
    private static WeakReference<View> button = new WeakReference<>(null);

    private SwipeToCreate() {
    }

    /** Before any added native getter or reason read, decide whether this hook can act. */
    public static int enabled() {
        try {
            return enabled(() -> switchedOn() || picked() != HomeSwipe.CAMERA);
        } catch (Throwable failure) {
            // Even constructing the stateless supplier must leave the native setter alone.
            HookStatus.threw(FamilyNames.SWIPE_TO_CREATE, "swipe gate", failure);
            return 0;
        }
    }

    static int enabled(BooleanSupplier on) {
        try {
            return on.getAsBoolean() ? 1 : 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SWIPE_TO_CREATE, "swipe gate", failure);
            return 0;
        }
    }

    /**
     * Injected in the container's move, after the target is clamped and before the panels slide.
     * Answers 1, hold at Home, for a finger's move toward the camera that starts at Home or past it
     * while the switch is on, and 0 otherwise, or when anything goes wrong. Never throws.
     *
     * @param target  where the move ends: below 0 is toward the camera
     * @param current where the panels are now
     * @param reason  why the move happens
     */
    public static int hold(float target, float current, String reason) {
        return hold(target, current, reason, SwipeToCreate::switchedOn, SwipeToCreate::picked, SwipeToCreate::buttonFor);
    }

    static int hold(float target, float current, String reason, BooleanSupplier on) {
        return hold(target, current, reason, on, () -> HomeSwipe.CAMERA, tab -> null);
    }

    static int hold(float target, float current, String reason, BooleanSupplier on, Supplier<HomeSwipe> tab,
            Function<HomeSwipe, View> buttons) {
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_CREATE);
            // NaN fails both comparisons, so a broken position is left alone.
            if (!DRAG.equals(reason) || !(target < 0f) || !(current >= 0f)) return 0;
            if (openTab(target, current, tab.get(), buttons)) return 1;
            if (!on.getAsBoolean()) return 0;
            Logger.printDebug(() -> "Swipe to create: held a swipe toward the camera at Home");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SWIPE_TO_CREATE, DRAG, failure);
            return 0;
        }
    }

    /**
     * Adds this step to the swipe and clicks [tab]'s button when the swipe has gone far enough or was
     * flung, once a swipe. Says whether the swipe is the tab's: false for the camera, or a tab with no
     * button on the bar.
     */
    private static boolean openTab(float target, float current, HomeSwipe tab, Function<HomeSwipe, View> buttons) {
        if (tab == HomeSwipe.CAMERA) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastStep > NEW_SWIPE_AFTER_MS) {
            swiped = 0f;
            opened = false;
            button = new WeakReference<>(buttons.apply(tab));
        }
        lastStep = now;
        View found = button.get();
        if (found == null) return false;
        swiped += current - target;
        if (!opened && (target <= FLUNG || swiped >= OPEN_AT)) {
            opened = true;
            if (found.performClick()) {
                HookStatus.counted(FamilyNames.SWIPE_TO_CREATE, COUNT_OPENED);
                Logger.printDebug(() -> "Swipe to create: a swipe on Home opened " + tab);
            }
        }
        return true;
    }

    /** [tab]'s button on the shown activity's tab bar, or null when it isn't there. */
    @Nullable
    private static View buttonFor(HomeSwipe tab) {
        Activity activity = Utils.getActivity();
        if (activity == null || tab.button == null) return null;
        View root = activity.getWindow().getDecorView();
        return buttonIn(root, id(activity, TAB_BAR), id(activity, tab.button));
    }

    /** The view with [tabId] inside the one with [barId] under [root], or null when either is missing. */
    @Nullable
    static View buttonIn(View root, int barId, int tabId) {
        View bar = barId == 0 ? null : root.findViewById(barId);
        return bar == null || tabId == 0 ? null : bar.findViewById(tabId);
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", Utils.resourcePackage(activity));
    }

    /** Starts the next step on a new swipe. Tests only. */
    static void forgetSwipeForTests() {
        swiped = 0f;
        opened = false;
        lastStep = Long.MIN_VALUE / 2;
        button = new WeakReference<>(null);
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.STOP_SWIPE_TO_CREATE.get();
    }

    private static HomeSwipe picked() {
        return Utils.settingsReady() ? Settings.HOME_SWIPE_OPENS.get() : HomeSwipe.CAMERA;
    }
}
