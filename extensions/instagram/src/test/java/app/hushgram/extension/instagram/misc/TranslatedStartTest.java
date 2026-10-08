/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Build;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

import app.hushgram.extension.shared.SettingsContextRule;

/**
 * Which devices skip Instagram's code protection step: an x86 device running Instagram's arm64
 * code translated, as an x86 emulator does (ABIs x86_64,arm64-v8a), and no other. Instagram's own
 * x86 builds on that emulator run their code as they are, so they make the call (#95).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TranslatedStartTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final String[] abis = Build.SUPPORTED_ABIS;

    @Before
    public void start() {
        TranslatedStart.forget();
        ShadowLog.reset();
    }

    @After
    public void restoreAbis() {
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_ABIS", abis);
        TranslatedStart.forget();
    }

    /** With no installed ABI to go by, the device's ABIs decide. */
    @Test
    public void onlyAnX86DeviceWithAnArmTranslatorCountsAsTranslated() {
        assertTrue(TranslatedStart.translated(null, new String[] {"x86_64", "arm64-v8a"}));
        assertTrue(TranslatedStart.translated(null, new String[] {"x86_64", "x86", "arm64-v8a", "armeabi-v7a", "armeabi"}));
        assertTrue(TranslatedStart.translated(null, new String[] {"x86", "armeabi-v7a"}));
        assertFalse("an x86 device with no translator", TranslatedStart.translated(null, new String[] {"x86_64", "x86"}));
        assertFalse("an arm phone", TranslatedStart.translated(null, new String[] {"arm64-v8a", "armeabi-v7a", "armeabi"}));
        assertFalse(TranslatedStart.translated(null, new String[0]));
        assertFalse(TranslatedStart.translated(null, null));
    }

    /** On an x86 device, the ABI Instagram was installed for decides, when it names one. */
    @Test
    public void theAbiInstagramWasInstalledForDecides() {
        String[] emulator = {"x86_64", "arm64-v8a"};
        assertTrue("arm64 code on an x86 emulator", TranslatedStart.translated("arm64", emulator));
        assertTrue("arm code on an x86 emulator", TranslatedStart.translated("arm", emulator));
        assertFalse("Instagram's x86_64 build", TranslatedStart.translated("x86_64", emulator));
        assertFalse("Instagram's x86 build", TranslatedStart.translated("x86", new String[] {"x86_64", "x86", "arm64-v8a"}));
        assertFalse("an arm phone", TranslatedStart.translated("arm64", new String[] {"arm64-v8a", "armeabi-v7a"}));
        assertTrue("a folder naming no ABI leaves it to the device", TranslatedStart.translated("lib", emulator));
        assertTrue("an empty name leaves it to the device", TranslatedStart.translated("", emulator));
    }

    @Test
    public void theInstalledAbiIsTheNativeFolderName() {
        RuntimeEnvironment.getApplication().getApplicationInfo().nativeLibraryDir = "/data/app/~~x/com.instagram.android-y/lib/x86_64";
        assertEquals("x86_64", TranslatedStart.appAbi());

        String[] before = new String[1];
        SettingsContextRule.withoutContext(() -> before[0] = TranslatedStart.appAbi());
        assertNull("nothing to read before the context is set", before[0]);
    }

    /** 450's x86_64 build (385611440) on an x86_64 emulator with an arm translator runs its own code (#95). */
    @Test
    public void anX86BuildOnAnX86EmulatorMakesTheCall() {
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_ABIS", new String[] {"x86_64", "arm64-v8a"});
        RuntimeEnvironment.getApplication().getApplicationInfo().nativeLibraryDir = "/data/app/~~x/com.instagram.android-y/lib/x86_64";

        assertEquals(1, TranslatedStart.protectCode());
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            assertFalse(item.msg, item.msg.contains("skipping the code protection step"));
        }
    }

    @Test
    public void anArmBuildOnAnX86EmulatorSkipsTheStepOnceTheContextIsSet() {
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_ABIS", new String[] {"x86_64", "arm64-v8a"});
        RuntimeEnvironment.getApplication().getApplicationInfo().nativeLibraryDir = "/data/app/~~x/com.instagram.android-y/lib/arm64";

        assertEquals(0, TranslatedStart.protectCode());
    }

    /** A translated start skips the step, and says so once, even before HushGram's settings are ready. */
    @Test
    public void aTranslatedStartSkipsTheStep() {
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_ABIS", new String[] {"x86_64", "arm64-v8a"});

        int[] answers = new int[2];
        SettingsContextRule.withoutContext(() -> {
            answers[0] = TranslatedStart.protectCode();
            answers[1] = TranslatedStart.protectCode();
        });

        assertEquals(0, answers[0]);
        assertEquals(0, answers[1]);
        int said = 0;
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.msg.contains("skipping the code protection step")) said++;
        }
        assertEquals("the skip is logged once", 1, said);
    }

    @Test
    public void anArmPhoneMakesTheCall() {
        ReflectionHelpers.setStaticField(Build.class, "SUPPORTED_ABIS", new String[] {"arm64-v8a", "armeabi-v7a"});

        assertEquals(1, TranslatedStart.protectCode());
    }
}
