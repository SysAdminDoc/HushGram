/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.content.pm.PackageInfo;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Change version code" patch.
 *
 * <p>The patch raises the version code in Instagram's manifest so Google Play stops offering Meta's
 * updates over the patched build. Instagram reads its own version code back: it compares it with
 * the code built into its own dex at start and in its job scheduler, and puts it in its logs and
 * crash reports. The patch sends each of those reads, the ones it can prove ask about Instagram's
 * own package, through here, so they see the code Meta built.
 *
 * <p>These reads start in the application's own start, before HushGram's settings can be read, so
 * nothing here reads a switch, and Pause doesn't change it: the manifest was changed when you
 * patched. A code other than the raised one, such as the Play Store build's under a Root Mount
 * install, passes through as it is.
 */
@SuppressWarnings("deprecation")
public final class VersionCode {
    private VersionCode() {
    }

    /**
     * In place of Instagram's own read of {@code info.versionCode}. Throws only where that read
     * would, on a null {@code info}.
     */
    public static int read(PackageInfo info) {
        int code = info.versionCode;
        try {
            HookStatus.invoked(FamilyNames.VERSION_CODE);
            return code == raised() ? real() : code;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VERSION_CODE, "version code read", failure);
            return code;
        }
    }

    /** In place of Instagram's own {@code info.getLongVersionCode()}, the same way as {@link #read}. */
    public static long readLong(PackageInfo info) {
        long code = info.getLongVersionCode();
        try {
            HookStatus.invoked(FamilyNames.VERSION_CODE);
            return code == raised() ? real() : code;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VERSION_CODE, "long version code read", failure);
            return code;
        }
    }

    /**
     * The code Meta built when {@code code} is the raised one, and {@code code} otherwise.
     * HushGram's own code uses it to name Instagram's build. Not a hook, so it counts nothing.
     */
    public static long unraised(long code) {
        int raised = raised();
        return raised != 0 && code == raised ? real() : code;
    }

    /**
     * The diagnostic report's line under Change version code. The report's app line shows the code
     * Android has installed, the raised one, so this names the build Meta made.
     */
    public static String reportLine() {
        return "built as version code " + real() + ", installed as " + raised();
    }

    /** The version code Meta built this Instagram with. The patch fills it in; 0 without it. */
    static int real() {
        return 0;
    }

    /** The version code the patched manifest carries. The patch fills it in; 0 without it. */
    static int raised() {
        return 0;
    }
}
