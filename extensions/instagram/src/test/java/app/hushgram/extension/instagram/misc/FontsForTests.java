/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.graphics.Typeface;

import org.robolectric.RuntimeEnvironment;

import java.io.File;

/** What a settings test needs of {@link Fonts}, which keeps it to its own package. */
public final class FontsForTests {
    private FontsForTests() {
    }

    /** Takes back what the process read and the loader a test put in. */
    public static void reset() {
        Fonts.resetForTests();
    }

    /** Every file loads as [typeface], so a test needs no real font file. */
    public static void loadEveryFile(Typeface typeface) {
        Fonts.loader = file -> typeface;
    }

    /** Where a picked font is kept. */
    public static File kept() {
        return Fonts.kept(RuntimeEnvironment.getApplication());
    }
}
