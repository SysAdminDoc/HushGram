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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;

/** Which captions count as being in another language (#495): the cleaning, the script layer and the classifier layer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CaptionLanguageTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String ENGLISH = "What a beautiful evening at the lake with friends";

    /** These tests run on the main thread, so the classifier is asked on its own; a long wait keeps a busy machine from timing them out. */
    @Before public void waitLong() {
        CaptionLanguage.mainWaitMillis = 10_000;
    }

    @After public void waitAsShipped() throws Exception {
        CaptionLanguage.mainWaitMillis = CaptionLanguage.MAIN_WAIT_MILLIS;
        CaptionLanguage.awaitClassifierForTests();
        CaptionLanguage.forget();
    }

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

    /**
     * On the main thread a classifier that hangs costs one wait, not one a caption: the reels stay
     * this page, and its answers count once it catches up.
     */
    @Test public void aSlowClassifierOnTheMainThreadCostsOneWait() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CaptionLanguage.mainWaitMillis = 300;
        CaptionLanguage.Rule rule = rule("en", text -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return new CaptionLanguage.Guess("es", 0.9f);
        });
        try {
            long start = System.nanoTime();
            for (int i = 0; i < 10; i++) assertFalse(rule.isOther(ENGLISH + " number " + letters(i)));
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("one wait, not ten: " + took + " ms", took < 1_500);
        } finally {
            release.countDown();
        }
        CaptionLanguage.awaitClassifierForTests();
        assertTrue("the late answer counts", rule.isOther(ENGLISH + " number " + letters(0)));
        assertTrue(rule.isOther(ENGLISH + " number " + letters(9)));

        // Caught up, it's waited for again.
        assertTrue(rule.isOther(ENGLISH + " and one more"));
    }

    @Test public void offTheMainThreadTheClassifierIsAskedDirectly() throws Exception {
        AtomicReference<Thread> askedOn = new AtomicReference<>();
        CaptionLanguage.Rule rule = rule("en", text -> {
            askedOn.set(Thread.currentThread());
            return new CaptionLanguage.Guess("es", 0.9f);
        });
        AtomicReference<Boolean> answer = new AtomicReference<>();
        Thread page = new Thread(() -> answer.set(rule.isOther(ENGLISH)));
        page.start();
        page.join(10_000);
        assertEquals(Boolean.TRUE, answer.get());
        assertEquals(page, askedOn.get());
    }

    /** Three-letter codes: the ones with a two-letter code turn into it, and the ones without one count as they are. */
    @Test public void threeLetterCodesAreUnderstood() {
        CaptionLanguage.Parsed parsed = CaptionLanguage.parse("fil, Filipino, hin, tl, Tagalog, yue, xyz");
        assertEquals(Arrays.asList("fil", "hi", "yue"), parsed.codes);
        assertEquals(Collections.singletonList("xyz"), parsed.unknown);

        // The classifier saying tl for a phone kept in Filipino is the same language.
        assertFalse(rule("fil", says("tl", 0.95f)).isOther("Magandang gabi sa lawa kasama ang mga kaibigan"));
    }

    /**
     * A three-letter code the classifier spells shorter keeps that language: Mandarin's cmn is the
     * zh the classifier answers, and so are Norwegian's nob and German's library code ger.
     */
    @Test public void threeLetterCodesTheClassifierSpellsShorterAreKept() {
        assertEquals(Arrays.asList("zh", "no", "de", "fr"), CaptionLanguage.parse("cmn, nob, nno, ger, fre, chi").codes);
        assertTrue(CaptionLanguage.parse("cmn, nob, ger").unknown.isEmpty());

        // A Chinese caption stays with Mandarin kept.
        assertFalse(rule("cmn", says("zh", 0.95f)).isOther(han(30)));
        assertFalse(rule("nob", says("no", 0.95f)).isOther("Det var en fin kveld ved vannet med venner"));
    }

    /** [letters] Han characters, a caption with no spaces. */
    private static String han(int letters) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < letters; i++) out.appendCodePoint(0x4E00 + i * 37);
        return out.toString();
    }

    /** With Languages to keep empty, a change of the phone's languages changes the rule. */
    @Test public void theRuleFollowsTheSystemLanguages() {
        Locale before = Locale.getDefault();
        try {
            Settings.HIDE_OTHER_LANGUAGE_REELS.save(true);
            Settings.REELS_KEPT_LANGUAGES.save("");
            Locale.setDefault(Locale.forLanguageTag("fr-FR"));
            CaptionLanguage.forget();
            CaptionLanguage.Rule french = CaptionLanguage.ruleWhenOn();
            assertTrue(french.kept.codes.toString(), french.kept.codes.contains("fr"));

            Locale.setDefault(Locale.forLanguageTag("de-DE"));
            CaptionLanguage.Rule german = CaptionLanguage.ruleWhenOn();
            assertTrue(german.kept.codes.toString(), german.kept.codes.contains("de"));
            assertTrue("the same languages keep the same rule", german == CaptionLanguage.ruleWhenOn());
        } finally {
            Locale.setDefault(before);
            Settings.HIDE_OTHER_LANGUAGE_REELS.resetToDefault();
            Settings.REELS_KEPT_LANGUAGES.resetToDefault();
        }
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
