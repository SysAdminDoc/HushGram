/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "View live anonymously" patch.
 *
 * <p>While you watch someone's live, Instagram's heartbeat manager tells the server every few
 * seconds that you're still there, and that's what puts you on the host's viewer list. The patch
 * asks {@link #hold} right before a viewer's or guest's heartbeat goes out, with the live's
 * broadcast id, and the tick ends as if it had. The host's own heartbeat, which counts the viewers
 * of your lives, isn't touched.
 *
 * <p>A live marked with the Mark as seen button ({@link LiveSeenButton}) is let through: its
 * heartbeats go out as usual from the next tick on, so you join its viewer list, and the rest stay
 * held. A mark lasts until Instagram is closed.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram does what it always does.
 */
public final class LiveSeen {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static final Object LOCK = new Object();

    /** The live of the last heartbeat held, which a tap on the button marks. */
    @Nullable
    private static String watching;

    /** The live marked with the button, whose heartbeats go out. */
    @Nullable
    private static String marked;

    /** A tap that came before any heartbeat of the live on screen: the next one marks it. */
    private static boolean pending;

    private static volatile boolean logged;

    private LiveSeen() {
    }

    /**
     * Asked before a viewer's or guest's heartbeat for the live [broadcast] is sent. True skips it.
     * False while the switch is off, HushGram is paused or the settings aren't ready, and for a live
     * marked with the button. Never throws.
     */
    public static boolean hold(@Nullable String broadcast) {
        return hold(broadcast, LiveSeen::switchedOn);
    }

    static boolean hold(@Nullable String broadcast, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.LIVE_SEEN);
            if (!on.getAsBoolean()) return false;
            synchronized (LOCK) {
                if (broadcast != null && pending) {
                    pending = false;
                    marked = broadcast;
                }
                watching = broadcast;
                if (broadcast != null && broadcast.equals(marked)) return false;
            }
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Live: held a viewer heartbeat");
            }
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.LIVE_SEEN, SWITCH, t);
            return false;
        }
    }

    /** A tap on the button: lets the live on screen through from its next heartbeat. */
    static void mark() {
        synchronized (LOCK) {
            if (watching != null) {
                marked = watching;
            } else {
                pending = true;
            }
        }
    }

    /** Whether the live on screen is marked, or will be by its first heartbeat. */
    static boolean markedNow() {
        synchronized (LOCK) {
            return pending || (watching != null && watching.equals(marked));
        }
    }

    /** A live viewer opened: forgets which live was on screen and any tap still waiting for one. */
    static void opened() {
        synchronized (LOCK) {
            watching = null;
            pending = false;
        }
    }

    /** Forgets everything, the mark included. */
    static void resetForTests() {
        synchronized (LOCK) {
            watching = null;
            marked = null;
            pending = false;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.VIEW_LIVE_ANONYMOUSLY.get();
    }
}
