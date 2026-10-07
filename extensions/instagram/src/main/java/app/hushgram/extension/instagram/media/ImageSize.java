/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Improve image viewing" patch.
 *
 * <p>Instagram's server sends each photo as a list of copies at different sizes, and the app picks
 * the one closest to the size it wants on screen. {@link #target} raises that wanted size, so the
 * pick is the largest copy the server sent. The server decides which sizes to send from the screen
 * size in the User-Agent header every request carries, which {@link #screen} raises when its own
 * switch is on, so there are bigger copies to pick from.
 *
 * <p>Both answer with the size Instagram gave while the switch is off, HushGram is paused, the
 * settings aren't ready, or when anything here fails.
 */
public final class ImageSize {
    /** The size, in pixels, both hooks ask for: bigger than any copy Instagram stores. */
    static final int LARGEST = 4096;

    private ImageSize() {
    }

    /** Handed the size the picker wants a photo at. */
    public static int target(int wanted) {
        return target(wanted, () -> Settings.IMPROVE_IMAGE_VIEWING.get());
    }

    static int target(int wanted, BooleanSupplier on) {
        try {
            HookStatus.invoked(FamilyNames.IMAGE_VIEWING);
            return Utils.settingsReady() && on.getAsBoolean() ? Math.max(wanted, LARGEST) : wanted;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.IMAGE_VIEWING, "picker", failure);
            return wanted;
        }
    }

    /** Handed the width or the height of the screen as Instagram writes it into its User-Agent header. */
    public static Integer screen(Integer size) {
        return screen(size, () -> Settings.IMPROVE_IMAGE_REQUEST.get());
    }

    static Integer screen(Integer size, BooleanSupplier on) {
        try {
            if (size == null) return null;
            HookStatus.invoked(FamilyNames.IMAGE_VIEWING);
            return Utils.settingsReady() && on.getAsBoolean() ? Integer.valueOf(Math.max(size, LARGEST)) : size;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.IMAGE_VIEWING, "request header", failure);
            return size;
        }
    }
}
