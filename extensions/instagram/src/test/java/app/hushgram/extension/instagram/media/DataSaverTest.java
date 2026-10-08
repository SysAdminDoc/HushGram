/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Data saver: when it saves, what width a photo is asked for, and what it leaves alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class DataSaverTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final int SCREEN = 1080;
    private static final BooleanSupplier ON = () -> true;
    private static final BooleanSupplier OFF = () -> false;

    private boolean mobile;

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.DATA_SAVER.save(true);
        DataSaver.inBuildForTests = Boolean.TRUE;
        mobile = true;
        DataSaver.mobileDataForTests = () -> mobile;
        DataSaver.forgetForTests();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.DATA_SAVER.resetToDefault();
        Settings.DATA_SAVER_MOBILE_DATA_ONLY.resetToDefault();
        DataSaver.inBuildForTests = null;
        DataSaver.mobileDataForTests = null;
        DataSaver.forgetForTests();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void aPhotoAcrossTheScreenIsAskedForSmaller() {
        assertEquals(DataSaver.PHOTO_WIDTH, DataSaver.photoWidth(1080, SCREEN, ON));
        assertEquals(DataSaver.PHOTO_WIDTH, DataSaver.photoWidth(1440, 1440, ON));
        assertEquals(DataSaver.PHOTO_WIDTH, DataSaver.photoWidth(1000, SCREEN, ON));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(DataSaver.SMALLER_PHOTO + " 3"));
    }

    @Test
    public void thumbnailsSmallPhotosAndOffAreLeftAlone() {
        assertEquals("a grid thumbnail", 360, DataSaver.photoWidth(360, SCREEN, ON));
        assertEquals("a half-width photo", 700, DataSaver.photoWidth(700, 1440, ON));
        assertEquals("already that small", 600, DataSaver.photoWidth(600, 600, ON));
        assertEquals("no screen read", 1080, DataSaver.photoWidth(1080, 0, ON));
        assertEquals("not saving", 1080, DataSaver.photoWidth(1080, SCREEN, OFF));
        assertEquals("a failure", 1080, DataSaver.photoWidth(1080, SCREEN, () -> {
            throw new IllegalStateException("settings went away");
        }));
        assertFalse(HookStatus.missing(FamilyNames.DATA_SAVER).isEmpty());
    }

    @Test
    public void itStartsOffAndThenSavesOnlyOnMobileData() {
        Settings.DATA_SAVER.resetToDefault();
        assertFalse(Settings.DATA_SAVER.get());
        assertTrue(Settings.DATA_SAVER_MOBILE_DATA_ONLY.get());
        assertFalse(DataSaver.saving());

        Settings.DATA_SAVER.save(true);
        assertTrue(DataSaver.saving());
        mobile = false;
        assertFalse("Wi-Fi stays as it is", DataSaver.saving());

        Settings.DATA_SAVER_MOBILE_DATA_ONLY.save(false);
        assertTrue("every network once the second switch is off", DataSaver.saving());
    }

    @Test
    public void pausedUnreadyOrMissingSavesNothing() {
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(DataSaver.saving());
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        assertTrue(DataSaver.saving());

        SettingsContextRule.withoutContext(() -> assertFalse(DataSaver.saving()));

        DataSaver.inBuildForTests = Boolean.FALSE;
        assertFalse(DataSaver.saving());
    }

    @Test
    public void theNetworkIsReadFromItsTransport() {
        Context context = RuntimeEnvironment.getApplication();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        shadowOf(manager).setNetworkCapabilities(active, capabilities);
        assertFalse(DataSaver.readMobileData(context));

        capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        shadowOf(manager).setNetworkCapabilities(active, capabilities);
        assertTrue(DataSaver.readMobileData(context));

        assertFalse(DataSaver.readMobileData(null));
    }
}
