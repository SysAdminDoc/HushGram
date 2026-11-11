/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import java.util.HashSet;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Keeps where the photos and videos in your gallery were taken away from Instagram (#628).
 *
 * <p>Instagram gets at that place three ways. Its image loader asks MediaStore for a gallery
 * file's original bytes, the location in them included, whenever Instagram holds the media
 * location permission; {@link #original} answers that ask. A gallery item works out its own place
 * from a video's metadata or a photo's EXIF, which is {@link #galleryItem}. And the camera, the
 * location sticker and the post's EXIF data read the place from a file's EXIF tags, which is
 * {@link #exifTags}. With the switch on, MediaStore hands the loader the file with its location
 * taken out, and the other two find no place, the way they do for a photo that never had one.
 *
 * <p>Fails open: anything unexpected hands Instagram its own answer back.
 */
public final class PhotoLocations {
    /** Counted each time a gallery file is opened without the original's location. */
    static final String OPENED_WITHOUT = "gallery files opened without their location";

    /** Counted each time a place is kept from a gallery item or a file's EXIF. */
    static final String KEPT_BACK = "places kept from Instagram";

    private static final String SOURCE = "PhotoLocations";

    /** What was logged this process, so each route's outcome shows in the log once. */
    private static final Set<String> LOGGED = new HashSet<>();

    private PhotoLocations() {
    }

    /**
     * The image loader, about to open a gallery file. [granted] is non-zero when Instagram holds the
     * media location permission and would ask for the original bytes. The answer says whether it
     * still asks, and replaces Instagram's own boolean, so it stays a boolean.
     */
    public static boolean original(int granted) {
        try {
            HookStatus.invoked(FamilyNames.PHOTO_LOCATIONS);
            if (granted != 0 && kept()) {
                HookStatus.counted(FamilyNames.PHOTO_LOCATIONS, OPENED_WITHOUT);
                log("gallery file", "opened without its location");
                return false;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PHOTO_LOCATIONS, "gallery file", failure);
        }
        return granted != 0;
    }

    /** A gallery item, about to work out where it was taken. Yes means it answers no place. */
    public static boolean galleryItem() {
        return keepBack("gallery item");
    }

    /** A file's EXIF tags, about to be read for where it was taken. Yes means they answer no place. */
    public static boolean exifTags() {
        return keepBack("photo EXIF");
    }

    private static boolean keepBack(String route) {
        try {
            HookStatus.invoked(FamilyNames.PHOTO_LOCATIONS);
            if (kept()) {
                HookStatus.counted(FamilyNames.PHOTO_LOCATIONS, KEPT_BACK);
                log(route, "place kept from Instagram");
                return true;
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PHOTO_LOCATIONS, route, failure);
        }
        return false;
    }

    private static boolean kept() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.KEEP_PHOTO_LOCATIONS.get();
    }

    /** Logs that [route] went [what], once per process. */
    private static void log(String route, String what) {
        try {
            String line = route + " " + what;
            synchronized (LOGGED) {
                if (!LOGGED.add(line)) return;
            }
            Logger.diagnosticDebug(DiagnosticCategory.OTHER, SOURCE, () -> FamilyNames.PHOTO_LOCATIONS + ": " + line);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PHOTO_LOCATIONS, "log", failure);
        }
    }
}
