/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import android.content.Context;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.File;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Keeps Instagram's start from crashing on an x86 device that runs its arm64 code through Android's
 * translator, such as an x86 Chromebook or an emulator.
 *
 * <p>Instagram's idle task "mprotect" calls {@code RuntimeInternals.mprotectExecCode()}, which
 * changes the protection of code pages from native code. Facebook runs the same step at start, and
 * under the translator the change comes back without the execute bit on the device's own compiled
 * framework code, so the next call into it dies with SIGSEGV "trying to execute non-executable
 * memory", or hangs the start. The patch asks here before each call, and on a translated device the
 * call is skipped. Anywhere else it runs as before, Instagram's own x86 builds on an x86 device too.
 *
 * <p>This can run before HushGram's settings can be read, so it reads no switch, logs through the
 * logger's settings-free path, and Pause and safe mode don't change it.
 */
public final class TranslatedStart {
    private static final String SOURCE = "TranslatedStart";

    private static volatile boolean logged;

    private TranslatedStart() {
    }

    /**
     * Injected in front of each call to Instagram's code protection step. Answers 0 to skip it when
     * this process runs Instagram's arm64 code translated, 1 to make it. Never throws: anything
     * unexpected makes the call, as Instagram would.
     */
    public static int protectCode() {
        HookStatus.invoked(FamilyNames.TRANSLATED_START);
        try {
            if (!translated(appAbi(), Build.SUPPORTED_ABIS)) return 1;
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.OTHER, SOURCE,
                        () -> "Start on x86 devices: arm code on an x86 device, skipping the code protection step");
            }
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.TRANSLATED_START, "ABI check", failure);
            return 1;
        }
    }

    /**
     * The ABI Android installed Instagram for, the name of its native library folder ("arm64",
     * "x86_64" and so on), or null before HushGram has a context or when there's no folder to read.
     * Read only once the context is set, so nothing logs a missing one.
     */
    @Nullable
    static String appAbi() {
        if (!Utils.settingsReady()) return null;
        Context context = Utils.getContext();
        String folder = context == null ? null : context.getApplicationInfo().nativeLibraryDir;
        return folder == null ? null : new File(folder).getName();
    }

    /**
     * Whether Instagram runs translated on a device whose ABIs are [abis], best first. Only an x86
     * device can. There [app], the ABI Instagram was installed for, decides: arm code runs through
     * the translator, while Instagram's own x86 and x86_64 builds (450's 385611439 and 385611440,
     * #95) run as they are. With no [app] to go by, or one that names neither, an x86 device that
     * also lists an arm ABI, which only a translator gives it, counts as translated, since the arm64
     * builds are what most installs carry. Any arm device answers false.
     */
    static boolean translated(@Nullable String app, @Nullable String[] abis) {
        if (abis == null || abis.length == 0 || abis[0] == null || !abis[0].startsWith("x86")) return false;
        if (app != null && app.startsWith("arm")) return true;
        if (app != null && app.startsWith("x86")) return false;
        for (String abi : abis) {
            if (abi != null && abi.startsWith("arm")) return true;
        }
        return false;
    }

    /** Forgets that the skip was logged. For tests. */
    static void forget() {
        logged = false;
    }
}
