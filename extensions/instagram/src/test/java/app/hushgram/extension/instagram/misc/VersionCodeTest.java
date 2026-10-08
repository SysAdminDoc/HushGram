/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Change version code (#68): Instagram's own reads of its version code see the code Meta built while
 * the manifest carries the raised one. Any other code passes through as it was read, a throw in the
 * hook gives back what Instagram read, and a null PackageInfo throws where Instagram's read would.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37}, shadows = VersionCodeTest.Patched.class)
@SuppressWarnings("deprecation")
public class VersionCodeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int REAL = 385611438;
    private static final int RAISED = Integer.MAX_VALUE;

    @Before
    public void setUp() {
        Patched.real = REAL;
        Patched.raised = RAISED;
        Patched.failure = false;
        HookStatus.clear();
    }

    @After
    public void tearDown() {
        Patched.failure = false;
        HookStatus.clear();
    }

    @Test
    public void theRaisedCodeReadsAsTheOneMetaBuilt() {
        assertEquals(REAL, VersionCode.read(info(RAISED)));
        assertEquals(REAL, VersionCode.readLong(info(RAISED)));
        assertEquals(List.of(FamilyNames.VERSION_CODE + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** The Play Store build under a Root Mount install, another version, and a code with a major half. */
    @Test
    public void anyOtherCodePassesThroughAsRead() {
        assertEquals(REAL, VersionCode.read(info(REAL)));
        assertEquals(REAL, VersionCode.readLong(info(REAL)));
        assertEquals(12, VersionCode.read(info(12)));
        PackageInfo major = info(RAISED);
        major.setLongVersionCode((1L << 32) | RAISED);
        assertEquals((1L << 32) | RAISED, VersionCode.readLong(major));
    }

    @Test
    public void aThrowGivesBackWhatInstagramReadAndIsReported() {
        Patched.failure = true;
        assertEquals(RAISED, VersionCode.read(info(RAISED)));
        assertEquals(RAISED, VersionCode.readLong(info(RAISED)));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("'version code read' hook (it threw java.lang.IllegalStateException)"));
    }

    @Test
    public void aMissingPackageInfoThrowsWhereInstagramsReadWould() {
        assertThrows(NullPointerException.class, () -> VersionCode.read(null));
        assertThrows(NullPointerException.class, () -> VersionCode.readLong(null));
        assertTrue(HookStatus.report().isEmpty());
    }

    /** HushGram's own naming of the build. It counts nothing, and unpatched it changes nothing. */
    @Test
    public void unraisedNamesTheBuildMetaMade() {
        assertEquals(REAL, VersionCode.unraised(RAISED));
        assertEquals(REAL, VersionCode.unraised(REAL));
        assertEquals((1L << 32) | RAISED, VersionCode.unraised((1L << 32) | RAISED));
        Patched.raised = 0;
        Patched.real = 0;
        assertEquals(0, VersionCode.unraised(0));
        assertEquals(RAISED, VersionCode.unraised(RAISED));
        assertTrue(HookStatus.report().isEmpty());
    }

    /** The report's app line shows the raised code Android installed, so the patch's line names Meta's. */
    @Test
    @SuppressWarnings("unchecked")
    public void theReportNamesTheBuildMetaMade() throws Exception {
        String line = "built as version code " + REAL + ", installed as " + RAISED;
        assertEquals(line, VersionCode.reportLine());
        Method report = PatchFamily.class.getDeclaredMethod("reportLines", Set.class, boolean.class);
        report.setAccessible(true);
        List<String> lines = (List<String>) report.invoke(null, EnumSet.of(PatchFamily.VERSION_CODE), false);
        assertEquals("  " + line, lines.get(1));
        assertTrue(HookStatus.report().isEmpty());
    }

    private static PackageInfo info(int code) {
        PackageInfo info = new PackageInfo();
        info.packageName = "com.instagram.android";
        info.setLongVersionCode(code);
        return info;
    }

    /** The two codes as the patch fills them in, and a stub that throws. */
    @Implements(value = VersionCode.class, isInAndroidSdk = false)
    public static class Patched {
        static int real, raised;
        static boolean failure;

        @Implementation protected static int real() {
            if (failure) throw new IllegalStateException("stub failed");
            return real;
        }

        @Implementation protected static int raised() {
            return raised;
        }
    }
}
