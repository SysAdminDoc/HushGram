/*
 * Forked from Hushfacebook's LicenseNoticeTest.java at 8804ea83.
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The Licenses row shows the NOTICE the repository ships, word for word. Morphe's section 7b asks
 * that its notice reach the person using the app ("All derivative works (source code and binaries)
 * must include this NOTICE text and provide it to the software user"), and a hand-kept copy would
 * drift from NOTICE the first time either changed.
 */
public class LicenseNoticeTest {
    private static File repositoryFile(String name) {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "settings.gradle.kts").isFile()) dir = dir.getParentFile();
        if (dir == null) throw new IllegalStateException("No repository root above " + new File("").getAbsolutePath());
        return new File(dir, name);
    }

    @Test
    public void theLicensesRowShowsNoticeExactly() throws Exception {
        String notice = new String(Files.readAllBytes(repositoryFile("NOTICE").toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertEquals(notice, LicenseNotice.TEXT);
    }

    @Test
    public void theNoticeCarriesMorpheAndPikoSectionSevenAndEveryUpstream() {
        assertTrue(LicenseNotice.TEXT.contains("GPLv3 Section 7b: Preservation of User Notices"));
        assertTrue(LicenseNotice.TEXT.contains("must include this\nNOTICE text and provide it to the software user."));
        assertTrue(LicenseNotice.TEXT.contains("Project Name Restriction"));
        assertTrue(LicenseNotice.TEXT.contains("This NOTICE file must be preserved and retained in all distributions"));
        for (String upstream : new String[] {
                "https://github.com/SysAdminDoc/Hushfacebook",
                "https://github.com/SysAdminDoc/HushPinterest",
                "https://github.com/SysAdminDoc/hushfeed",
                "https://github.com/andrewliang25/morphe-patches",
                "https://github.com/icysymmetra/tiktok-patches-for-morphe",
                "https://github.com/MorpheApp/morphe-patches",
                "https://github.com/MorpheApp/morphe-patches-library",
                "https://github.com/crimera/piko",
                "https://gitlab.com/ReVanced/revanced-patches"}) {
            assertTrue("NOTICE does not name " + upstream, LicenseNotice.TEXT.contains(upstream));
        }
    }
}
