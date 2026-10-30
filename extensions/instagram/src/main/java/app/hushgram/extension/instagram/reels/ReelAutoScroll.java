/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicInteger;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Keep Reels auto scroll on" patch.
 *
 * <p>Instagram 449 asks one method whether auto scroll is on in Reels: the scroller that moves on
 * to the next reel reads it, and so does every auto scroll switch Instagram draws. Where the answer
 * comes from is a server choice: a value kept in memory, which every start forgets; a timer you set
 * when you turn it on; or a saved preference, which Instagram itself clears whenever you leave
 * Reels in one setup. The Reels viewer's state and its picture in picture read the value in memory
 * themselves. The patch passes every answer of that method, and every one of those reads, through
 * {@link #answer}, every answer of the saved preference's getter through {@link #saved}, and hands
 * each choice made with Instagram's own switches that turn it on or off to {@link #chosen}, and
 * again to {@link #stored} once Instagram has kept it in memory or saved it. A completed duration
 * choice passes its actual expiration timestamp to {@link #timerSet} after the native save, so a
 * future timer is remembered even if it expires before another check answers on.
 *
 * <p>HushGram keeps the last choice ({@link Settings#REEL_AUTO_SCROLL_ON}). The check answering on
 * is remembered as on, a choice Instagram keeps is remembered as it is, and turning auto scroll off
 * with one of Instagram's switches is remembered as off. While the switch is on, Instagram's "off"
 * is answered with what's remembered, so auto scroll stays on across restarts and leaving Reels
 * until you turn it off, and Instagram's own switches show that answer. The memory follows
 * Instagram while the switch is off too, so turning the switch on later goes by your latest choice.
 *
 * <p>An "on" read back from the saved preference is never remembered. In the memory and timer
 * setups Instagram doesn't go by it, and turning auto scroll off there doesn't clear it, so an old
 * "on" saved by the Reels tab's long press would otherwise turn auto scroll back on as soon as
 * anything read it. Where Instagram does go by the preference, its switches' handler saves the
 * choice there, which reaches {@link #stored}, and the check reads it through the getter, so its
 * "on" reaches {@link #answer} too.
 *
 * <p>Move on when a reel ends ({@link Settings#REEL_AUTO_ADVANCE}) is for an account Instagram
 * doesn't offer auto scroll to (#175). One server check decides both whether a reel's menu gets its
 * auto scroll switch and whether the Reels viewer builds its auto scroller at all, so on such an
 * account there's no switch to turn on and nothing listening for a reel's end. While that setting
 * is on, the viewer's own read of the check passes through {@link #install}, which answers yes, so
 * the viewer builds the scroller and hands it each reel's loop as it does on any other account, and
 * {@link #answer} answers on, so Instagram's own Auto scroll switch, where there is one, can't turn
 * it off. {@link #saved} doesn't: where Instagram's check reads the saved preference, an on from the
 * getter comes back to {@link #answer} as Instagram's own and would be remembered as a choice.
 * Whether a reel that ended may move on is still the
 * scroller's to decide, by its own checks of what's on screen, a finger on the reel, an open sheet
 * and the reel's kind, and the move is its own. The remembered choice isn't touched: it follows
 * Instagram's switches alone, so turning this off leaves auto scroll the way it was last set.
 *
 * <p>Paused, before the settings are read, or when anything goes wrong, Instagram's answer stands
 * and nothing is remembered. {@link #answer} runs every time the scroller checks, so it reads a
 * saved value and writes only when the choice changes. No hook throws.
 */
public final class ReelAutoScroll {
    /** Where the last choice is kept: {@link Settings#REEL_AUTO_SCROLL_ON} outside tests. */
    interface Memory {
        boolean on();

        void remember(boolean on);
    }

    static final Memory SAVED = new Memory() {
        @Override
        public boolean on() {
            return Settings.REEL_AUTO_SCROLL_ON.savedValue();
        }

        @Override
        public void remember(boolean on) {
            Settings.REEL_AUTO_SCROLL_ON.save(on);
        }
    };

    private ReelAutoScroll() {
    }

    /**
     * Injected at every return of Instagram's check of whether auto scroll is on in Reels, with
     * Instagram's answer as an int (non-zero is yes). Answers yes when Instagram does, remembering
     * it, while Move on when a reel ends is on, and while the switch is on, also when auto scroll
     * was last left on. Otherwise answers what Instagram did.
     */
    public static boolean answer(int instagram) {
        return answer(instagram, ReelAutoScroll::learning, ReelAutoScroll::switchedOn, ReelAutoScroll::advancing, SAVED);
    }

    static boolean answer(int instagram, BooleanSupplier learning, BooleanSupplier on, BooleanSupplier advancing, Memory memory) {
        boolean scrolling = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            if (reported.get() < 2) report(scrolling, learning, on, advancing, memory);
            if (!learning.getAsBoolean()) return scrolling;
            if (scrolling) {
                if (!memory.on()) {
                    memory.remember(true);
                    Logger.printInfo(() -> "Reel auto scroll: on, remembered");
                }
                return true;
            }
            return advancing.getAsBoolean() || (on.getAsBoolean() && memory.on());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll answer", failure);
            return scrolling;
        }
    }

    /**
     * Injected at every return of the saved auto scroll preference's getter, with the saved value as
     * an int (non-zero is on). Answers on when the preference is, and while the switch is on, also
     * when auto scroll was last left on, so the Reels tab's long press offers to turn off what's on.
     * Move on when a reel ends doesn't count here (see the class notes). Remembers nothing.
     */
    public static boolean saved(int instagram) {
        return saved(instagram, ReelAutoScroll::switchedOn, SAVED);
    }

    static boolean saved(int instagram, BooleanSupplier on, Memory memory) {
        boolean stored = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            return stored || (on.getAsBoolean() && memory.on());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll saved", failure);
            return stored;
        }
    }

    /**
     * Injected in the Reels viewer right after it asks whether Instagram offers auto scroll to this
     * account, which is what it builds its auto scroller on, with Instagram's answer as an int
     * (non-zero is yes). Answers yes when Instagram does, and while Move on when a reel ends is on,
     * so the viewer builds the scroller there too. Otherwise answers what Instagram did. The viewer
     * asks once as it opens.
     */
    public static boolean install(int instagram) {
        return install(instagram, ReelAutoScroll::advancing);
    }

    static boolean install(int instagram, BooleanSupplier advancing) {
        boolean offered = instagram != 0;
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            if (offered || !advancing.getAsBoolean()) return offered;
            Logger.printInfo(() -> "Reel auto scroll: Instagram doesn't offer it here, building its scroller for Move on when a reel ends");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll install", failure);
            return offered;
        }
    }

    /**
     * Injected where one of Instagram's switches turns auto scroll on or off, with the choice as
     * an int (non-zero is on). Off is remembered, so auto scroll stays off after a restart. On is
     * remembered once Instagram keeps it ({@link #stored}) or answers that it's on, since some
     * setups ask how long for first, and you can still back out there.
     */
    public static void chosen(int choice) {
        chosen(choice, ReelAutoScroll::learning, SAVED);
    }

    static void chosen(int choice, BooleanSupplier learning, Memory memory) {
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            if (choice != 0 || !learning.getAsBoolean()) return;
            if (memory.on()) {
                memory.remember(false);
                Logger.printInfo(() -> "Reel auto scroll: turned off, remembered");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll choice", failure);
        }
    }

    /**
     * Injected right after Instagram's handler for its switches keeps the choice, in memory or in the
     * saved preference, with the choice as an int (non-zero is on). Instagram goes by what it kept
     * there, so on and off are both remembered at once, before anything asks again.
     */
    public static void stored(int choice) {
        stored(choice, ReelAutoScroll::learning, SAVED);
    }

    static void stored(int choice, BooleanSupplier learning, Memory memory) {
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            if (!learning.getAsBoolean()) return;
            boolean on = choice != 0;
            if (memory.on() != on) {
                memory.remember(on);
                Logger.printInfo(() -> on ? "Reel auto scroll: on, remembered" : "Reel auto scroll: turned off, remembered");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll stored", failure);
        }
    }

    /** Called after a completed native duration save, with the expiration timestamp it saved. */
    public static void timerSet(long expiration) {
        timerSet(expiration, System::currentTimeMillis, ReelAutoScroll::learning, SAVED);
    }

    static void timerSet(long expiration, LongSupplier now, BooleanSupplier learning, Memory memory) {
        try {
            HookStatus.invoked(FamilyNames.REEL_AUTO_SCROLL);
            if (!learning.getAsBoolean() || expiration <= now.getAsLong()) return;
            if (!memory.on()) {
                memory.remember(true);
                Logger.printInfo(() -> "Reel auto scroll: duration saved, remembered on");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REEL_AUTO_SCROLL, "auto scroll timer", failure);
        }
    }

    /** What {@link #report} has logged in this process: 0 nothing, 1 an answer it couldn't learn from, 2 one it could. */
    private static final AtomicInteger reported = new AtomicInteger();

    /**
     * Logs what the first answer after a start saw, and the first one HushGram may learn from if
     * that came later, so a log shows why auto scroll did or didn't come back. The settings are read
     * only when they're ready.
     */
    private static void report(boolean scrolling, BooleanSupplier learning, BooleanSupplier on, BooleanSupplier advancing,
                               Memory memory) {
        boolean learns = learning.getAsBoolean();
        int next = learns ? 2 : 1;
        int previous;
        do {
            previous = reported.get();
            if (previous >= next) return;
        } while (!reported.compareAndSet(previous, next));
        boolean keeping = learns && on.getAsBoolean();
        boolean moving = learns && advancing.getAsBoolean();
        boolean left = learns && memory.on();
        Logger.printInfo(() -> "Reel auto scroll: answer since start, Instagram says " + (scrolling ? "on" : "off")
                + (learns ? ", keep switch " + (keeping ? "on" : "off") + ", move on switch " + (moving ? "on" : "off")
                        + ", last left " + (left ? "on" : "off")
                        : ", HushGram paused or settings not read yet"));
    }

    /** Whether the memory may follow Instagram: the settings are read and HushGram isn't paused. */
    static boolean learning() {
        return Utils.settingsReady() && !HushgramPause.isPaused();
    }

    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_REEL_AUTO_SCROLL.get();
    }

    /** Whether Move on when a reel ends is on. A pause answers off, as it does for every switch. */
    static boolean advancing() {
        return Utils.settingsReady() && Settings.REEL_AUTO_ADVANCE.get();
    }
}
