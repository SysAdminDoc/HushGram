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
 * Helper for the "Keep your place in Home" patch (#354).
 *
 * <p>When Instagram comes back from the background with Home showing, its warm-start check works out
 * how long you were away and asks whether that's under the server's minimum (30 seconds unless the
 * server says otherwise). Away for less, Home stays where it was. Away for longer, Home reloads the
 * feed and scrolls back to the top, so the post you were on is gone.
 *
 * <p>The time isn't the only way in. Instagram's instant feed, its server timers and a return that
 * names a tab can each pick a reload on their own, and the check ends in one answer: the reload it
 * settled on, or its "nothing to do" value. The patch hands the time to {@link #timeAway} right
 * before the minimum test, and that answer to {@link #reload} once it's settled. While the switch is
 * on they answer 0 and "nothing to do", so every return counts as a quick one.
 *
 * <p>Instagram also sets an alarm when it goes to the background, timed for that same minimum. When
 * it goes off and Instagram is still in the background, it reloads Home there and then, so the
 * return finds a new feed at the top whatever the check decides. The patch asks
 * {@link #skipBackgroundReload} first thing when the alarm goes off, and while the switch is on the
 * alarm does nothing.
 *
 * <p>The reset that actually showed on a device is a third one, Instagram's app reset. Leaving the
 * app starts a timer (about a minute, set by the server), and when it runs out Instagram closes
 * anything opened on top and, on the next return, takes you back to the top of Home, whichever tab
 * you were on. With some server settings it also picks a reset as it comes back. The patch asks
 * {@link #skipAppReset} first thing when the timer runs out and hands the reset picked on return to
 * {@link #appResetPlan}, and while the switch is on neither happens, so you come back to the screen
 * you left.
 *
 * <p>Pulling down to refresh is a different path and still reloads. A return after Android closed
 * Instagram in the background is a fresh start, which none of this sees.
 *
 * <p>The hooks fail open: with the switch off, HushGram paused, the settings not read yet or
 * anything thrown, Instagram's own time and answer go through.
 */
public final class HomePlace {
    /** The step a failure is reported under. */
    static final String RETURN = "return to home";

    /** The step a failure in the background alarm is reported under. */
    static final String BACKGROUND = "background reload";

    /** What's counted each time a return would have reloaded Home and didn't. */
    static final String KEPT = "home kept its place on a return";

    /** What's counted each time the background alarm would have reloaded Home and didn't. */
    static final String HELD = "home skipped a background reload";

    /** The step a failure in Instagram's app reset is reported under. */
    static final String RESET = "app reset";

    /** What's counted each time Instagram would have reset to the top of Home and didn't. */
    static final String RESET_SKIPPED = "skipped an app reset";

    private HomePlace() {
    }

    /**
     * Injected in Home's warm-start check, in front of the minimum time away test. Answers 0 to
     * keep Home where it was, or {@code away} to go on as Instagram does. Never throws.
     */
    public static long timeAway(long away) {
        return timeAway(away, HomePlace::switchedOn);
    }

    static long timeAway(long away, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.HOME_PLACE);
            return on.getAsBoolean() ? 0 : away;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_PLACE, RETURN, failure);
            return away;
        }
    }

    /**
     * Injected right after Home's warm-start check settles what to do. Answers {@code none},
     * Instagram's own "nothing to do" value, to keep Home where it was, or {@code reload} to go on as
     * Instagram does. Never throws.
     */
    public static Object reload(Object reload, Object none) {
        return reload(reload, none, HomePlace::switchedOn);
    }

    static Object reload(Object reload, Object none, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.HOME_PLACE);
            if (reload == none || !on.getAsBoolean()) return reload;
            HookStatus.counted(FamilyNames.HOME_PLACE, KEPT);
            Logger.printDebug(() -> "Keep your place in Home: skipped a Home reload (" + reload + ")");
            return none;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_PLACE, RETURN, failure);
            return reload;
        }
    }

    /**
     * Injected first thing in the alarm that reloads Home while Instagram is in the background.
     * Answers true to skip that reload, false to go on as Instagram does. Never throws.
     */
    public static boolean skipBackgroundReload() {
        return skipBackgroundReload(HomePlace::switchedOn);
    }

    static boolean skipBackgroundReload(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.HOME_PLACE);
            if (!on.getAsBoolean()) return false;
            HookStatus.counted(FamilyNames.HOME_PLACE, HELD);
            Logger.printDebug(() -> "Keep your place in Home: skipped a reload in the background");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_PLACE, BACKGROUND, failure);
            return false;
        }
    }

    /**
     * Injected first thing when Instagram's app reset timer goes off in the background. Answers true
     * to skip the reset, false to go on as Instagram does. Never throws.
     */
    public static boolean skipAppReset() {
        return skipAppReset(HomePlace::switchedOn);
    }

    static boolean skipAppReset(BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.HOME_PLACE);
            if (!on.getAsBoolean()) return false;
            HookStatus.counted(FamilyNames.HOME_PLACE, RESET_SKIPPED);
            Logger.printDebug(() -> "Keep your place in Home: skipped an app reset in the background");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_PLACE, RESET, failure);
            return false;
        }
    }

    /**
     * Injected where Instagram picks an app reset as it comes to the foreground. Answers null, which
     * Instagram reads as no reset, or {@code plan} to go on as Instagram does. Never throws.
     */
    public static Object appResetPlan(Object plan) {
        return appResetPlan(plan, HomePlace::switchedOn);
    }

    static Object appResetPlan(Object plan, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.HOME_PLACE);
            if (plan == null || !on.getAsBoolean()) return plan;
            HookStatus.counted(FamilyNames.HOME_PLACE, RESET_SKIPPED);
            Logger.printDebug(() -> "Keep your place in Home: skipped an app reset on return");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HOME_PLACE, RESET, failure);
            return plan;
        }
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.KEEP_HOME_PLACE.get();
    }
}
