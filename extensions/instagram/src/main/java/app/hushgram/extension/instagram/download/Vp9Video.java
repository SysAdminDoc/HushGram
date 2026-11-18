/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import java.util.function.BooleanSupplier;

/**
 * Helper for the "Ask for VP9 instead of AV1" patch (#546).
 *
 * <p>Instagram adds a device status to its feed, reels and story requests ({@code device_status},
 * built once a run by DeviceStatusApiUtil), and it says which video formats the phone decodes in
 * hardware: {@code hw_av1_dec}, {@code 10bit_hw_av1_dec}, {@code hw_vp9_dec} and the rest. The patch
 * hands both AV1 answers to {@link #hardwareAv1} as they go into the status, and while the switch is
 * on the answer is no. Instagram doesn't always act on it: a server flag decides whether the status
 * is sent at all, and a phone answering no was still sent the same AV1-only manifests (#546).
 *
 * <p>The status is built once, so a change takes a restart. The hook fails open: with the switch
 * off, HushGram paused, the settings not read yet or anything thrown, Instagram's own answer goes in.
 */
public final class Vp9Video {
    /** The step a failed switch read is reported under. */
    static final String SWITCH = "switch read";

    private static volatile boolean logged;

    private Vp9Video() {
    }

    /**
     * Asked with each AV1 answer [reported] as Instagram writes its device status. No in the same
     * type while the switch is on (a Boolean, a "true"/"false" string or a number), Instagram's own
     * answer otherwise or for a value of any other type. Never throws.
     */
    public static Object hardwareAv1(Object reported) {
        return hardwareAv1(reported, Vp9Video::switchedOn);
    }

    static Object hardwareAv1(Object reported, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.VP9_VIDEO);
            if (!on.getAsBoolean()) return reported;
            Object no = no(reported);
            if (!logged && no != reported) {
                logged = true;
                Logger.printDebug(() -> "VP9: told Instagram this phone has no AV1 decoder (it reported " + reported + ")");
            }
            return no;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VP9_VIDEO, SWITCH, t);
            return reported;
        }
    }

    /** No, written the way [reported] was. Anything else comes back as it was. */
    static Object no(Object reported) {
        if (reported instanceof Boolean) return Boolean.FALSE;
        if (reported instanceof String) {
            String text = (String) reported;
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) return "false";
            if ("1".equals(text) || "0".equals(text)) return "0";
            return reported;
        }
        if (reported instanceof Integer) return 0;
        if (reported instanceof Long) return 0L;
        return reported;
    }

    private static boolean switchedOn() {
        return Utils.settingsReady() && Settings.ASK_FOR_VP9.get();
    }
}
