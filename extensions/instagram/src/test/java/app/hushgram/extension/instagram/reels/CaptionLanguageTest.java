/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.shared.SettingsContextRule;

/** Which captions count as being in another language (#495): the cleaning, the script layer and the classifier layer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CaptionLanguageTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ENGLISH = "What a beautiful evening at the lake with friends";

    /** [letters] Devanagari consonants, in words of four. */
    public static String devanagari(int letters) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < letters; i++) {
            if (i > 0 && i % 4 == 0) out.append(' ');
            out.appendCodePoint(0x0915 + (i % 20));
        }
        return out.toString();
    }

    private static CaptionLanguage.Rule rule(String kept, CaptionLanguage.Detector detector) {
        return new CaptionLanguage.Rule(CaptionLanguage.parse(kept), detector);
    }

    private static CaptionLanguage.Detector says(String language, float confidence) {
        return text -> new CaptionLanguage.Guess(language, confidence);
    }

    @Test public void aDevanagariCaptionGoesWhenOnlyEnglishIsKept() {
        assertTrue(rule("en", null).isOther(devanagari(30)));
        assertTrue("with a hashtag and an emoji around it", rule("en", null).isOther("#reels " + devanagari(30) + " @someone"));
    }

    @Test public void anEnglishCaptionStaysWhenEnglishAndHindiAreKept() {
        assertFalse(rule("en, hi", says("en", 0.95f)).isOther(ENGLISH));
        assertFalse(rule("en, hi", null).isOther(ENGLISH));
        assertFalse("a Devanagari caption is Hindi's script", rule("en, hi", null).isOther(devanagari(30)));
    }

    @Test public void aCaptionTooShortToTellStays() {
        CaptionLanguage.Rule rule = rule("en", says("es", 0.99f));
        assertFalse(rule.isOther("abc"));
        assertFalse(rule.isOther(devanagari(5)));
        assertFalse(rule.isOther("#viral #fyp #reels #love #india"));
        assertFalse(rule.isOther("12345 67890 !!! ??? https://example.com/page"));
        assertFalse(rule.isOther(null));
        assertFalse(rule.isOther(""));
    }

    @Test public void theClassifierDecidesWhenTheScriptsMatch() {
        assertTrue(rule("en", says("es", 0.9f)).isOther(ENGLISH));
        assertFalse("low confidence", rule("en", says("es", 0.4f)).isOther(ENGLISH));
        assertFalse("the kept language", rule("en", says("en", 0.9f)).isOther(ENGLISH));
        assertFalse("nothing to say", rule("en", text -> null).isOther(ENGLISH));
        assertFalse("a classifier that throws", rule("en", text -> {
            throw new IllegalStateException("no service");
        }).isOther(ENGLISH));
        assertFalse("no classifier", rule("en", null).isOther(ENGLISH));
    }

    @Test public void theClassifierSeesTheCleanedCaption() {
        String[] seen = new String[1];
        rule("en", text -> {
            seen[0] = text;
            return null;
        }).isOther("Sunset at the lake with friends #sunset @bob https://x.com/a 123 " + new String(Character.toChars(0x1F600)));
        assertEquals("Sunset at the lake with friends", seen[0]);
    }

    @Test public void answersAreRememberedByCaption() {
        AtomicInteger calls = new AtomicInteger();
        CaptionLanguage.Rule rule = rule("en", text -> {
            calls.incrementAndGet();
            return new CaptionLanguage.Guess("es", 0.9f);
        });
        assertTrue(rule.isOther(ENGLISH));
        assertTrue(rule.isOther(ENGLISH));
        assertEquals(1, calls.get());
        for (int i = 0; i < CaptionLanguage.CACHE_SIZE + 20; i++) rule.isOther(ENGLISH + " number " + letters(i));
        assertTrue(rule.isOther(ENGLISH));
        assertEquals("the oldest answers were let go", CaptionLanguage.CACHE_SIZE + 22, calls.get());
    }

    /** A number as letters, so cleaning doesn't drop what makes captions differ. */
    private static String letters(int number) {
        StringBuilder out = new StringBuilder();
        do {
            out.append((char) ('a' + number % 26));
            number /= 26;
        } while (number > 0);
        return out.toString();
    }

    @Test public void codesAndNamesInAnyCaseAreUnderstood() {
        Locale hindi = Locale.forLanguageTag("hi");
        String hindiName = hindi.getDisplayLanguage(hindi);
        String spanish = "Espa" + (char) 0xF1 + "ol";
        CaptionLanguage.Parsed parsed = CaptionLanguage.parse("English, " + hindiName + ", " + spanish);
        assertEquals(Arrays.asList("en", "hi", "es"), parsed.codes);
        assertTrue(parsed.unknown.isEmpty());
        assertFalse(parsed.fromPhone);

        assertEquals(Arrays.asList("en", "hi", "de"), CaptionLanguage.parse("EN\nhindi , de-DE,,en").codes);
        assertEquals(Arrays.asList("id", "he"), CaptionLanguage.parse("in, iw").codes);
    }

    @Test public void aPieceThatIsNoLanguageIsReported() {
        CaptionLanguage.Parsed parsed = CaptionLanguage.parse("English, Klingonese, zz9");
        assertEquals(Collections.singletonList("en"), parsed.codes);
        assertEquals(Arrays.asList("Klingonese", "zz9"), parsed.unknown);
        assertEquals("Keeps English. Didn't recognize: Klingonese, zz9", parsed.summary());
        assertEquals("Keeps English, Hindi", CaptionLanguage.summary("en, hi"));
        assertEquals("Didn't recognize: nope", CaptionLanguage.summary("nope"));
    }

    @Test public void aListWithNothingUnderstoodHidesNothing() {
        CaptionLanguage.Rule rule = rule("nope", says("es", 0.99f));
        assertFalse(rule.active());
        assertFalse(rule.isOther(ENGLISH));
    }

    @Test public void anEmptyListMeansThePhonesLanguages() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("fr-FR"));
            CaptionLanguage.Parsed parsed = CaptionLanguage.parse("");
            assertTrue(parsed.fromPhone);
            assertTrue(parsed.codes.toString(), parsed.codes.contains("en") || parsed.codes.contains("fr"));
            assertTrue(CaptionLanguage.summary(" ").startsWith("Keeps your phone's languages: "));
        } finally {
            Locale.setDefault(before);
        }
    }
}
