/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import java.util.function.BooleanSupplier;

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
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, the move goes where Instagram sent it.
 */
public final class SwipeToCreate {
    /** The reason Instagram gives every move a finger makes. */
    static final String DRAG = "swipe";

    private SwipeToCreate() {
    }

    /** Before any added native getter or reason read, decide whether this hook can act. */
    public static int enabled() {
        try {
            return enabled(SwipeToCreate::switchedOn);
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
        return hold(target, current, reason, SwipeToCreate::switchedOn);
    }

    static int hold(float target, float current, String reason, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.SWIPE_TO_CREATE);
            // NaN fails both comparisons, so a broken position is left alone.
            if (!DRAG.equals(reason) || !(target < 0f) || !(current >= 0f)) return 0;
            if (!on.getAsBoolean()) return 0;
            Logger.printDebug(() -> "Swipe to create: held a swipe toward the camera at Home");
            return 1;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SWIPE_TO_CREATE, DRAG, failure);
            return 0;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.STOP_SWIPE_TO_CREATE.get();
    }
}
