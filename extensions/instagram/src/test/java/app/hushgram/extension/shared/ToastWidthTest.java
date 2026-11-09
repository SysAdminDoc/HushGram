/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every toast fits the two lines Android 12 and later show, in English and in every table. The
 * S22 fits about 34 characters a line and word wrap breaks before that, so this wraps at 28. A
 * longer toast loses its end with nothing on screen to say so. A message that needs more goes out
 * as two toasts, the way safe mode says it paused HushGram.
 */
public class ToastWidthTest {
    private static final int WIDTH = 28;

    private static final Pattern TOAST = Pattern.compile("\\b(?:showToast|showToastShort|showToastLong)\\s*\\(");
    private static final Pattern L10N_CALL = Pattern.compile("\\bL10n\\s*\\.\\s*(?:t|f)\\s*\\(");
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:[1-9]\\d*\\$)?[sd]");

    /** A placeholder's text is unknown, so it counts as a long-ish name. */
    private static final String FILLED = "XXXXXXXX";

    @Test
    public void everyToastFitsTwoLinesInEveryLanguage() throws IOException {
        Set<String> found = longToasts(toastKeys());
        report(found);
        assertTrue("Toasts past two lines of " + WIDTH + " characters. Shorten them:\n"
                + String.join("\n", found), found.isEmpty());
    }

    @Test
    public void theScanFindsToastsAndCountsLines() throws IOException {
        // Positive controls: the scan has to find this change's toasts, and the wrap has to be
        // able to say three.
        Map<String, String> keys = toastKeys();
        assertTrue("found only " + keys.size() + " toasts", keys.size() > 50);
        assertEquals("EmojiFonts.java", keys.get("Couldn't load your emoji, so the phone's show."));
        assertEquals("HushgramPreferenceFragment.java", keys.get("Emoji saved. Restart Instagram to see them."));
        assertEquals(1, lines("Saved"));
        assertEquals(1, lines("Twenty-eight characters fit."));
        assertEquals(2, lines("Twenty-nine-characters-don't."));
        assertEquals(2, lines("one two\nthree"));
        assertEquals(2, lines("Twenty-eight characters fit. And this goes on."));
        assertEquals(3, lines("Twenty-eight characters fit. And this goes on to a third line."));
        assertEquals(2, lines("%1$s and %2$s were saved."));
        StringBuilder marked = new StringBuilder();
        for (int letter = 0; letter < WIDTH; letter++) marked.append("กิ");
        assertEquals(1, lines(marked.toString()));
        assertEquals(2, lines(marked.append("ก").toString()));
        Map<String, String> one = new TreeMap<>();
        one.put("Twenty-eight characters fit. And this goes on to a third line.", "Test.java");
        assertEquals(1, longToasts(one).size());
    }

    /** "language TAB English" for each toast past two lines, English as "en". */
    static Set<String> longToasts(Map<String, String> keys) throws IOException {
        List<Map<String, String>> tables = new ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) tables.add(L10nCatalogTest.readTable(language));
        Set<String> found = new TreeSet<>();
        for (String english : keys.keySet()) {
            if (lines(english) > 2) found.add("en\t" + encode(english));
            for (int index = 0; index < tables.size(); index++) {
                String translated = tables.get(index).get(english);
                if (translated != null && lines(translated) > 2) {
                    found.add(L10nTranslations.LANGUAGES[index] + "\t" + encode(english));
                }
            }
        }
        return found;
    }

    /** The English of each L10n call inside a toast call, with the file it's in. */
    static Map<String, String> toastKeys() throws IOException {
        Map<String, String> keys = new TreeMap<>();
        for (Path file : L10nCatalogTest.sources()) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            byte[] kind = L10nCatalogTest.classify(text);
            Matcher toast = TOAST.matcher(text);
            while (toast.find()) {
                if (kind[toast.start()] != L10nCatalogTest.CODE) continue;
                int close = L10nCatalogTest.closing(text, kind, toast.end() - 1);
                if (close < 0) continue;
                Matcher call = L10N_CALL.matcher(text).region(toast.end(), close);
                while (call.find()) {
                    if (kind[call.start()] != L10nCatalogTest.CODE) continue;
                    int open = call.end() - 1;
                    List<String> literals = L10nCatalogTest.literalsIn(text, kind, open + 1,
                            L10nCatalogTest.closing(text, kind, open), null);
                    if (!literals.isEmpty()) keys.putIfAbsent(literals.get(0), file.getFileName().toString());
                }
            }
        }
        return keys;
    }

    /** The lines [text] takes when wrapped at word breaks into lines of WIDTH characters. */
    static int lines(String text) {
        String plain = PLACEHOLDER.matcher(text).replaceAll(FILLED);
        int lines = 0;
        for (String paragraph : plain.split("\n", -1)) {
            lines++;
            int used = 0;
            for (String word : paragraph.split(" ", -1)) {
                int length = width(word);
                if (used > 0 && used + 1 + length <= WIDTH) {
                    used += 1 + length;
                    continue;
                }
                if (used > 0) lines++;
                // A word longer than a line breaks where the line ends.
                while (length > WIDTH) {
                    length -= WIDTH;
                    lines++;
                }
                used = length;
            }
        }
        return lines;
    }

    /** The characters of [word] that take room on a line. A mark sitting on a letter, as Thai vowels and tones do, takes none. */
    private static int width(String word) {
        int width = 0;
        for (int at = 0; at < word.length(); ) {
            int point = word.codePointAt(at);
            at += Character.charCount(point);
            if (Character.getType(point) != Character.NON_SPACING_MARK) width++;
        }
        return width;
    }

    /** What the scan found, so a failing run's list can be read whole. */
    private static void report(Set<String> found) throws IOException {
        File out = new File("build/reports/toast-width.txt");
        if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs()) return;
        Files.write(out.toPath(), (String.join("\n", found) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String english) {
        return english.replace("\\", "\\\\").replace("\n", "\\n");
    }
}
