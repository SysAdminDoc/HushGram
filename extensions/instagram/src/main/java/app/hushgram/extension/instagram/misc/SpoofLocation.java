/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.location.Location;

import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.Supplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "Spoof location" patch.
 *
 * <p>Every place in Instagram's code that reads a {@link Location}'s latitude or longitude, or
 * measures a distance from one, calls the matching method here instead, on the same location. A fix
 * that came from the phone, by Android's location providers or Google Play services' fused one,
 * answers the place set in HushGram's settings while the switch is on. Every other location, like
 * a photo's place or a venue's, answers what it holds, so maps of other places stay right.
 *
 * <p>With the switch on and no place set, or one that can't be read, a fix answers 0, 0 rather
 * than where the phone is. With the switch off, HushGram paused, the settings not read yet or
 * anything thrown, every read answers what the location holds.
 */
public final class SpoofLocation {
    /** What's counted for each read of a fix that answered the set place. */
    static final String SPOOFED = "location read spoofed";

    /** The step a failure is reported under. */
    static final String READ = "location read";

    /** The place a fix answers while the switch is on and no place is set. */
    static final double[] NO_PLACE = {0, 0};

    /** The providers a fix from the phone comes from: Android's three and Google Play services' fused one. */
    static final String[] PHONE_PROVIDERS = {"gps", "network", "passive", "fused"};

    /** The last place text read and what it parsed to, so a feed of fixes doesn't parse it each time. */
    @Nullable private static volatile String lastText;
    @Nullable private static volatile double[] lastPlace;

    private SpoofLocation() {
    }

    /** Stands in for {@link Location#getLatitude()}. Throws as it would for a null location. */
    public static double latitude(Location location) {
        return read(location, true, SpoofLocation::place);
    }

    /** Stands in for {@link Location#getLongitude()}. Throws as it would for a null location. */
    public static double longitude(Location location) {
        return read(location, false, SpoofLocation::place);
    }

    /**
     * Stands in for {@link Location#distanceTo(Location)}: the distance between the two as their
     * reads above answer them, so a spoofed fix is as far from a place as the set place is.
     */
    public static float distanceTo(Location from, Location to) {
        return distanceTo(from, to, SpoofLocation::place);
    }

    static float distanceTo(Location from, Location to, Supplier<double[]> place) {
        if (!fromPhone(from) && !fromPhone(to)) return from.distanceTo(to);
        float[] meters = new float[1];
        Location.distanceBetween(read(from, true, place), read(from, false, place),
                read(to, true, place), read(to, false, place), meters);
        return meters[0];
    }

    static double read(Location location, boolean latitude, Supplier<double[]> place) {
        double held = latitude ? location.getLatitude() : location.getLongitude();
        if (!fromPhone(location)) return held;
        try {
            HookStatus.invoked(FamilyNames.SPOOF_LOCATION);
            double[] set = place.get();
            if (set == null) return held;
            HookStatus.counted(FamilyNames.SPOOF_LOCATION, SPOOFED);
            return latitude ? set[0] : set[1];
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPOOF_LOCATION, READ, failure);
            return held;
        }
    }

    /** Whether [location] is a fix from the phone rather than a place Instagram made one for. */
    static boolean fromPhone(@Nullable Location location) {
        String provider = location == null ? null : location.getProvider();
        if (provider == null) return false;
        for (String phone : PHONE_PROVIDERS) {
            if (phone.equals(provider)) return true;
        }
        return false;
    }

    /** The place a fix answers: null while the switch is off, {@link #NO_PLACE} when none can be read. */
    @Nullable
    private static double[] place() {
        if (!Utils.settingsReady() || !Settings.SPOOF_LOCATION.get()) return null;
        String text = Settings.SPOOF_LOCATION_PLACE.get();
        double[] known = lastPlace;
        if (known != null && text.equals(lastText)) return known;
        double[] parsed = parse(text);
        double[] place = parsed == null ? NO_PLACE : parsed;
        lastPlace = place;
        lastText = text;
        return place;
    }

    /**
     * The latitude and longitude in [text], two decimal numbers in degrees separated by a comma, or
     * null when it isn't one or either is out of range. Spaces around each are fine.
     */
    @Nullable
    public static double[] parse(@Nullable String text) {
        if (text == null) return null;
        String[] parts = text.split(",");
        if (parts.length != 2) return null;
        try {
            double latitude = Double.parseDouble(parts[0].trim());
            double longitude = Double.parseDouble(parts[1].trim());
            if (Double.isNaN(latitude) || Double.isNaN(longitude)) return null;
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
            return new double[]{latitude, longitude};
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** How the settings row shows a place: up to six decimals, with a dot whatever the language. */
    public static String describe(double[] place) {
        return plain(place[0]) + ", " + plain(place[1]);
    }

    private static String plain(double degrees) {
        String text = new BigDecimal(String.format(Locale.US, "%.6f", degrees)).stripTrailingZeros().toPlainString();
        return text.equals("-0") ? "0" : text;
    }
}
