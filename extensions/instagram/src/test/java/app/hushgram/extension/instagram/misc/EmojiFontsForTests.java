/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** What a settings test needs of {@link EmojiFonts}, which keeps it to its own package. */
public final class EmojiFontsForTests {
    private EmojiFontsForTests() {
    }

    /** Takes back what the process read and the download a test put in. */
    public static void reset() {
        EmojiFonts.resetForTests();
    }

    /** Every pack downloads as [bytes], already checked. */
    public static void serve(byte[] bytes) {
        EmojiFonts.opener = choice -> new ByteArrayInputStream(bytes);
    }

    /** No pack can be reached. */
    public static void unreachable() {
        EmojiFonts.opener = choice -> {
            throw new IOException("No network in this test");
        };
    }

    /** Where the chosen emoji font is kept. */
    public static File kept() {
        return EmojiFonts.kept(RuntimeEnvironment.getApplication());
    }

    /** The start of a TrueType font listing [tags] as its tables, enough for the color check. */
    public static byte[] font(String... tags) {
        ByteBuffer out = ByteBuffer.allocate(12 + 16 * tags.length);
        out.putInt(0x00010000).putShort((short) tags.length).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String tag : tags) out.put(tag.getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(0).putInt(0);
        return out.array();
    }

    /** Removes the kept emoji font and anything an import left beside it. */
    public static void removeKept() {
        File kept = kept();
        if (kept.exists()) kept.delete();
        File[] files = kept.getParentFile().listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
    }
}
