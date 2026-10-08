/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Spoof location: a fix from the phone answers the set place, and everything else answers its own. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SpoofLocationTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final double[] TIMES_SQUARE = {40.758, -73.9855};
    private static final Supplier<double[]> SET = () -> TIMES_SQUARE;
    private static final Supplier<double[]> OFF = () -> null;

    private static Location at(String provider, double latitude, double longitude) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        return location;
    }

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SPOOF_LOCATION.save(true);
        Settings.SPOOF_LOCATION_PLACE.save("40.758, -73.9855");
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.SPOOF_LOCATION.resetToDefault();
        Settings.SPOOF_LOCATION_PLACE.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void aFixFromThePhoneAnswersTheSetPlace() {
        for (String provider : SpoofLocation.PHONE_PROVIDERS) {
            Location fix = at(provider, 51.5007, -0.1246);
            assertEquals(provider, 40.758, SpoofLocation.read(fix, true, SET), 0);
            assertEquals(provider, -73.9855, SpoofLocation.read(fix, false, SET), 0);
        }
        Location fix = at("fused", 51.5007, -0.1246);
        assertEquals(40.758, SpoofLocation.latitude(fix), 0);
        assertEquals(-73.9855, SpoofLocation.longitude(fix), 0);
        assertEquals("the fix itself is left as it was", 51.5007, fix.getLatitude(), 0);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(SpoofLocation.SPOOFED + " " + (SpoofLocation.PHONE_PROVIDERS.length * 2 + 2)));
    }

    @Test
    public void aPlaceInstagramMadeKeepsItsOwn() {
        for (String provider : new String[]{"", "exif", "venue", "GPS"}) {
            Location place = at(provider, 48.8584, 2.2945);
            assertEquals(provider, 48.8584, SpoofLocation.read(place, true, SET), 0);
            assertEquals(provider, 2.2945, SpoofLocation.read(place, false, SET), 0);
        }
        assertFalse(SpoofLocation.fromPhone(null));
    }

    @Test
    public void offPausedUnreadyOrThrowingAnswerTheRealPlace() {
        Location fix = at("gps", 51.5007, -0.1246);
        assertEquals(51.5007, SpoofLocation.read(fix, true, OFF), 0);

        Settings.SPOOF_LOCATION.save(false);
        assertEquals(51.5007, SpoofLocation.latitude(fix), 0);
        Settings.SPOOF_LOCATION.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(-0.1246, SpoofLocation.longitude(fix), 0);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals(51.5007, SpoofLocation.latitude(fix), 0));

        assertEquals(51.5007, SpoofLocation.read(fix, true, () -> {
            throw new IllegalStateException("settings went away");
        }), 0);
        assertFalse(HookStatus.missing(FamilyNames.SPOOF_LOCATION).isEmpty());
    }

    /** On with no place, or one that can't be read, a fix answers 0, 0 rather than where the phone is. */
    @Test
    public void noPlaceAnswersZeroZero() {
        Location fix = at("network", 51.5007, -0.1246);
        for (String text : new String[]{"", "Times Square", "91, 0"}) {
            Settings.SPOOF_LOCATION_PLACE.save(text);
            assertEquals(text, 0, SpoofLocation.latitude(fix), 0);
            assertEquals(text, 0, SpoofLocation.longitude(fix), 0);
        }
        Settings.SPOOF_LOCATION_PLACE.save("-33.8568,151.2153");
        assertEquals(-33.8568, SpoofLocation.latitude(fix), 0);
    }

    @Test
    public void aPlaceIsTwoNumbersInRange() {
        assertArrayEquals(new double[]{40.758, -73.9855}, SpoofLocation.parse("40.758, -73.9855"), 0);
        assertArrayEquals(new double[]{-33.8568, 151.2153}, SpoofLocation.parse("  -33.8568 ,151.2153 "), 0);
        assertArrayEquals(new double[]{90, -180}, SpoofLocation.parse("90,-180"), 0);
        for (String text : new String[]{null, "", "40.758", "1,2,3", "a,b", "90.1,0", "0,180.5", "NaN,0", "40,758 -73,9855"}) {
            assertNull(text, SpoofLocation.parse(text));
        }
        assertEquals("40.758, -73.9855", SpoofLocation.describe(new double[]{40.758, -73.9855}));
        assertEquals("0, 151.2", SpoofLocation.describe(new double[]{-0.0000001, 151.2}));
    }

    /** A distance from a spoofed fix is measured from the set place, and one between two other places as before. */
    @Test
    public void aDistanceIsMeasuredFromTheSetPlace() {
        Location fix = at("gps", 51.5007, -0.1246);
        Location sameCorner = at("", 40.758, -73.9855);
        assertTrue(SpoofLocation.distanceTo(fix, sameCorner, SET) < 1);
        assertTrue("off, London is far from Times Square", SpoofLocation.distanceTo(fix, sameCorner, OFF) > 5_000_000);

        Location eiffel = at("", 48.8584, 2.2945);
        assertEquals(sameCorner.distanceTo(eiffel), SpoofLocation.distanceTo(sameCorner, eiffel, SET), 0);
    }
}
