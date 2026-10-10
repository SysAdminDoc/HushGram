/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Hide your active status" patch.
 *
 * <p>Instagram 450 tells its presence service how you are with a write request on the presence
 * stream: Active while the app is in front, Idle once it goes to the background, Offline or
 * Disabled as the stream closes. The people you chat with see Active now only while the last one
 * said Active. The patch asks {@link #status} first in the write request's constructor with the
 * status it was given, and while the switch is on an Active becomes Idle, the status Instagram
 * itself sends from the background. Everything else in the request, the stream, and the presence
 * of other people that comes back on it, stays as it is.
 *
 * <p>The hook fails open: with the switch off, HushGram paused, the settings not read yet, a
 * status this doesn't know, or anything thrown, the request keeps the status Instagram gave it.
 */
public final class ActiveStatus {
    /** The step a failed switch read or lookup is reported under. */
    static final String SWITCH = "switch read";
    /** Counted each time an Active was sent as Idle. */
    static final String SENT_IDLE = "sent idle";

    /** The names Instagram's presence status enum gives the two statuses. */
    static final String ACTIVE = "ACTIVE";
    static final String IDLE = "IDLE";

    /** The Idle constant, once found, with the enum it came from. */
    private static volatile Enum<?> idle;
    private static volatile boolean logged;

    private ActiveStatus() {
    }

    /**
     * Asked first in the presence write request's constructor with its status. Answers Idle in
     * place of Active while the switch is on, and otherwise the status it was given, the same
     * object. Never throws, and never answers anything but the status given or a constant of its
     * own enum, so the cast after the call always holds.
     */
    public static Object status(Object status) {
        return status(status, ActiveStatus::switchedOn);
    }

    static Object status(Object status, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.ACTIVE_STATUS);
            if (!(status instanceof Enum) || !ACTIVE.equals(((Enum<?>) status).name()) || !on.getAsBoolean()) {
                return status;
            }
            Class<?> type = ((Enum<?>) status).getDeclaringClass();
            Enum<?> replacement = idleOf(type);
            if (replacement == null) {
                HookStatus.missingMember(FamilyNames.ACTIVE_STATUS, "constant", type.getName(), IDLE);
                return status;
            }
            HookStatus.counted(FamilyNames.ACTIVE_STATUS, SENT_IDLE);
            if (!logged) {
                logged = true;
                Logger.printDebug(() -> "Messages: sent the presence service Idle in place of Active");
            }
            return replacement;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.ACTIVE_STATUS, SWITCH, t);
            return status;
        }
    }

    /** The Idle constant of {@code type}, read from its static fields so it works without values(). */
    private static Enum<?> idleOf(Class<?> type) throws IllegalAccessException {
        Enum<?> found = idle;
        if (found != null && found.getDeclaringClass() == type) return found;
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != type || !Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Enum && IDLE.equals(((Enum<?>) value).name())) {
                found = (Enum<?>) value;
                idle = found;
                return found;
            }
        }
        return null;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.HIDE_ACTIVE_STATUS.get();
    }
}
