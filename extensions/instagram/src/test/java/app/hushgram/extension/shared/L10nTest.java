/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.os.LocaleList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How the catalog picks a language: the phone's list in order, a region falling back to its
 * language, both codes Indonesian goes by, English for everything else, and the pseudo-locales.
 * And that none of it touches the language Instagram itself runs in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class L10nTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String KEY = "Hide ads";

    @Test
    public void everyLanguageTheBundleCarriesHasATableWithTheKey() {
        assertEquals(Arrays.asList("de", "es", "in", "it", "ko", "pt-rbr", "tr", "zh-rtw"), Arrays.asList(L10nTranslations.LANGUAGES));
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            assertNotNull(language, table);
            assertTrue(language + " has no row for " + KEY, table.containsKey(KEY));
        }
        assertNull("a language with no table has a table", L10nTranslations.of("fr"));
    }

    @Test
    public void aLanguageAnswersInItsOwnTable() {
        for (String language : L10nTranslations.LANGUAGES) {
            Locale locale = locale(language);
            assertEquals(language, table(language).get(KEY), L10n.t(in(locale), KEY));
        }
    }

    @Test
    public void aRegionFallsBackToItsLanguageAndBrazilianPortugueseStaysBrazilian() {
        assertEquals(table("de").get(KEY), L10n.t(in(new Locale("de", "AT")), KEY));
        assertEquals(table("es").get(KEY), L10n.t(in(new Locale("es", "MX")), KEY));
        assertEquals(table("tr").get(KEY), L10n.t(in(new Locale("tr", "CY")), KEY));
        // Only Brazil has a Portuguese table. Portugal's Portuguese isn't Brazil's, so a phone set
        // to it reads English rather than a neighbour's spelling it never asked for.
        assertEquals(table("pt-rbr").get(KEY), L10n.t(in(new Locale("pt", "BR")), KEY));
        assertEquals(KEY, L10n.t(in(new Locale("pt", "PT")), KEY));
    }

    @Test
    public void indonesianAnswersToBothOfItsCodes() {
        // Android reports the old code "in", a desktop JVM the new "id".
        String indonesian = table("in").get(KEY);
        assertEquals(indonesian, L10nTranslations.of("id").get(KEY));
        assertEquals(indonesian, L10n.t(in(new Locale("in", "ID")), KEY));
        assertEquals(indonesian, L10n.t(in(Locale.forLanguageTag("id-ID")), KEY));
    }

    @Test
    public void traditionalChineseUsesTaiwansTableForItsScriptAndRegions() {
        String translated = table("zh-rtw").get(KEY);
        assertEquals("隱藏廣告", translated);
        for (String tag : Arrays.asList("zh-TW", "zh-Hant", "zh-Hant-TW", "zh-HK", "zh-MO", "zh-Hant-CN")) {
            Context chinese = in(Locale.forLanguageTag(tag));
            assertEquals(tag, translated, L10n.t(chinese, KEY));
            assertEquals(tag, Locale.TAIWAN, L10n.textLocale(chinese));
            assertEquals(tag, "other", L10n.pluralCategory(chinese, 1));
            assertEquals(tag, "已匯入 2 項設定。", L10n.f(chinese, "Imported %1$d settings.", 2));
            assertEquals(tag, "unknown setting", L10n.t(chinese, "unknown setting"));
        }
        assertEquals(translated, L10n.t(in(Locale.TRADITIONAL_CHINESE), KEY));
    }

    @Test
    public void simplifiedAndUnspecifiedChineseNeverTakeTheTraditionalTableByAccident() {
        for (String tag : Arrays.asList("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-CN", "zh-Hans-TW", "zh-Hans-HK")) {
            Context chinese = in(Locale.forLanguageTag(tag));
            assertEquals(tag, KEY, L10n.t(chinese, KEY));
            assertEquals(tag, Locale.ENGLISH, L10n.textLocale(chinese));
        }
        assertEquals(KEY, L10n.t(in(Locale.SIMPLIFIED_CHINESE), KEY));
        assertEquals(table("zh-rtw").get(KEY), L10n.t(in(Locale.FRANCE, Locale.TAIWAN), KEY));
        assertEquals(KEY, L10n.t(in(Locale.US, Locale.TAIWAN), KEY));
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.SIMPLIFIED_CHINESE, Locale.GERMANY), KEY));
        assertEquals(table("zh-rtw").get(KEY), L10n.t(in(Locale.SIMPLIFIED_CHINESE, Locale.TAIWAN), KEY));
    }

    @Test
    public void aSecondLanguageAnswersWhenTheFirstHasNoTable() {
        // Android reads string resources down the whole list, and so does the catalog.
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.FRANCE, Locale.GERMANY), KEY));
        assertEquals("the first language with a table wins", table("es").get(KEY),
                L10n.t(in(Locale.JAPAN, new Locale("es", "ES"), Locale.GERMANY), KEY));
        assertEquals(Arrays.asList("fr-rfr", "fr", "de-rde", "de"), L10n.tags(in(Locale.FRANCE, Locale.GERMANY)));
    }

    /**
     * English is the language the keys are written in, so it answers wherever the phone lists it.
     * An English-first phone with German second read every string in German, which Android's own
     * lookup never gives it.
     */
    @Test
    public void englishAnswersWhereThePhoneListsIt() {
        assertEquals(KEY, L10n.t(in(Locale.US, Locale.GERMANY), KEY));
        assertEquals(KEY, L10n.t(in(Locale.FRANCE, Locale.US, Locale.GERMANY), KEY));
        assertEquals("the English list word, not German's", "a, b, and c",
                L10n.join(in(Locale.US, Locale.GERMANY), Arrays.asList("a", "b", "c")));
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.FRANCE, Locale.GERMANY, Locale.US), KEY));
    }

    /** Portugal's Portuguese first and Brazil's second: the Brazilian table answers. */
    @Test
    public void aSecondRegionOfTheSameLanguageIsStillTried() {
        assertEquals(table("pt-rbr").get(KEY), L10n.t(in(new Locale("pt", "PT"), new Locale("pt", "BR")), KEY));
        assertEquals(Arrays.asList("pt-rpt", "pt", "pt-rbr"),
                L10n.tags(in(new Locale("pt", "PT"), new Locale("pt", "BR"))));
    }

    /**
     * With no table for the phone's first language, the second one's text comes out, and so do
     * its list word and its plural rule. Tested only where the two languages were the same, a
     * rule read from the phone's first language passed.
     */
    @Test
    public void aSecondLanguagesTextTakesItsOwnListWordAndPluralRule() {
        assertEquals("a, b und c", L10n.join(in(Locale.FRANCE, Locale.GERMANY), Arrays.asList("a", "b", "c")));
        Context indonesianSecond = in(Locale.FRANCE, new Locale("in", "ID"));
        // French calls 1 "one"; Indonesian has no such form.
        assertEquals("other", L10n.pluralCategory(indonesianSecond, 1));
        assertEquals("one", L10n.pluralCategory(in(Locale.FRANCE, Locale.US), 1));
    }

    @Test
    public void aSentenceStartIsRaisedPastAQuoteOrAnIsolateMark() {
        char quote = (char) 0x201E;
        assertEquals(quote + "Speichern", L10n.capitalize(in(Locale.GERMANY), quote + "speichern"));
        char isolate = (char) 0x2068;
        assertEquals(isolate + "Photo", L10n.capitalize(in(Locale.US), isolate + "photo"));
        assertEquals("3 Things", L10n.capitalize(in(Locale.US), "3 things"));
        assertEquals("...", L10n.capitalize(in(Locale.US), "..."));
    }

    /**
     * What goes in an isolate stays in it: its own isolate marks come out and its line breaks
     * become spaces, since either could end the isolate a report leaves out before the text does.
     */
    @Test
    public void anIsolatedValueCannotEndItsIsolateEarly() {
        String open = String.valueOf((char) 0x2068);
        String close = String.valueOf((char) 0x2069);
        assertEquals(open + "Movies/Clips" + close, L10n.isolate("Movies/Clips"));
        assertEquals(open + "Ana and Bo" + close, L10n.isolate("Ana⁩ and⁦ Bo⁧"));
        assertEquals(open + "a b c d e f" + close, L10n.isolate("a\nb\rc d e\u0085f"));
        assertEquals(open + close, L10n.isolate("⁨⁩"));
        assertEquals("", L10n.isolate(""));
    }

    /** A format's numbers come out the phone's way even when its sentence is English. */
    @Test
    public void aFormatWritesItsNumbersThePhonesWay() {
        assertEquals("4,2 MB", L10n.f(in(Locale.FRANCE), "%1$.1f MB", 4.2));
        assertEquals("4.2 MB", L10n.f(in(Locale.US), "%1$.1f MB", 4.2));
    }

    @Test
    public void englishAndEveryLanguageWithNoTableReadTheEnglish() {
        assertEquals(KEY, L10n.t(in(Locale.US), KEY));
        assertEquals(KEY, L10n.t(in(Locale.FRANCE), KEY));
        assertEquals(KEY, L10n.t(in(Locale.JAPAN, Locale.CHINA), KEY));
        // A string nobody translated is its own English, never an empty label.
        assertEquals("Not a key of any table", L10n.t(in(Locale.GERMANY), "Not a key of any table"));
        assertEquals("", L10n.t(in(Locale.GERMANY), ""));
        assertNull(L10n.t(in(Locale.GERMANY), (String) null));
    }

    @Test
    public void withNoContextTheDefaultLocaleAnswers() {
        SettingsContextRule.withoutContext(() -> assertEquals(KEY, L10n.t(KEY)));
    }

    @Test
    public void aFormatFillsTheTranslationAndFallsBackToTheEnglishWhenItCant() {
        String german = table("de").get("Full report saved to %1$s");
        assertNotNull(german);
        assertEquals(String.format(german, "Movies/Instagram"), L10n.f(in(Locale.GERMANY), "Full report saved to %1$s", "Movies/Instagram"));
        // Numbers are written the phone's way even in English: a French phone reads 4,2.
        assertEquals("4,2 MB of 9 MB", L10n.f(in(Locale.FRANCE), "%1$s of %2$s",
                String.format(L10n.locale(in(Locale.FRANCE)), "%.1f MB", 4.2), "9 MB"));
    }

    @Test
    public void aListIsJoinedInTheLanguageTheTextCameOutIn() {
        List<String> three = Arrays.asList("a", "b", "c");
        assertEquals("a", L10n.join(in(Locale.US), Arrays.asList("a")));
        assertEquals("a and b", L10n.join(in(Locale.US), Arrays.asList("a", "b")));
        assertEquals("a, b, and c", L10n.join(in(Locale.US), three));
        assertEquals("a, b und c", L10n.join(in(Locale.GERMANY), three));
        assertEquals("a, b y c", L10n.join(in(new Locale("es", "ES")), three));
        assertEquals("a, b ve c", L10n.join(in(new Locale("tr", "TR")), three));
        assertEquals("a, b e c", L10n.join(in(new Locale("pt", "BR")), three));
        // Android calls Indonesian "in", which ICU knows as "id".
        String indonesian = L10n.join(in(new Locale("in", "ID")), three);
        assertTrue(indonesian, indonesian.contains(" dan c"));
        // A French phone has no French table, so the sentence is English and so is its "and".
        assertEquals("a, b, and c", L10n.join(in(Locale.FRANCE), three));
    }

    @Test
    public void aSentenceStartIsRaisedTheWayTheLanguageDoesIt() {
        assertEquals("Indir", L10n.capitalize(in(Locale.US), "indir"));
        // Turkish raises i to a dotted capital.
        assertEquals((char) 0x0130 + "ndir", L10n.capitalize(in(new Locale("tr", "TR")), "indir"));
        assertEquals("", L10n.capitalize(in(Locale.US), ""));
    }

    @Test
    public void aCountTakesTheFormItsLanguageGivesIt() {
        String one = "%1$s. It was set when you patched, so Pause can't turn it off. To rule it out, patch "
                + "again and leave out that patch.";
        String other = "%1$s. They were set when you patched, so Pause can't turn them off. To rule one out, "
                + "patch again and leave out the patch in brackets after it.";
        assertEquals(String.format(one, "x"), L10n.quantity(in(Locale.US), 1, one, other, "x"));
        assertEquals(String.format(other, "x"), L10n.quantity(in(Locale.US), 2, one, other, "x"));
        // Indonesian has no singular form, so even one item takes the other row.
        Context indonesian = in(new Locale("in", "ID"));
        assertEquals("other", L10n.pluralCategory(indonesian, 1));
        assertEquals(String.format(table("in").get(other), "x"), L10n.quantity(indonesian, 1, one, other, "x"));
        // The form follows the language the text is in: French calls 0 "one", English doesn't, and
        // a French phone reads English here.
        assertEquals("other", L10n.pluralCategory(in(Locale.FRANCE), 0));
        assertEquals(String.format(other, "x"), L10n.quantity(in(Locale.FRANCE), 0, one, other, "x"));
        // A language with a form English lacks keeps it as a |category row of the other form.
        Map<String, String> polish = new java.util.HashMap<>();
        polish.put(other, "o");
        polish.put(other + "|few", "f");
        assertEquals("f", L10n.pluralRow("few", one, other, polish));
        assertEquals("o", L10n.pluralRow("many", one, other, polish));
        assertEquals(one, L10n.pluralRow("one", one, other, polish));
    }

    @Test
    public void enXaDrawsEveryStringAccentedLongerAndBracketed() {
        Context accented = in(new Locale("en", "XA"));
        String shown = L10n.t(accented, KEY);
        assertTrue(shown, shown.startsWith("[") && shown.endsWith("]"));
        assertTrue("en-XA isn't longer: " + shown, shown.length() > KEY.length() * 4 / 3);
        assertFalse("en-XA left the letters plain: " + shown, shown.contains("sponsored"));
        // Placeholders come through whole, so the values put in them read as they are.
        String formatted = L10n.f(accented, "Full report saved to %1$s", "Movies/Instagram");
        assertTrue(formatted, formatted.contains("Movies/Instagram"));
        assertEquals(L10n.pseudolocalize(L10n.ACCENTED, "%1$s of %2$s"), L10n.t(accented, "%1$s of %2$s"));
        assertTrue(L10n.t(accented, "%1$s of %2$s").startsWith("[%1$s "));
        // Second in the list, a pseudo-locale is never reached.
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.GERMANY, new Locale("en", "XA")), KEY));
    }

    @Test
    public void arXbDrawsEveryWordMirroredAndLeavesTheValuesAlone() {
        Context mirrored = in(new Locale("ar", "XB"));
        String shown = L10n.t(mirrored, KEY);
        char override = (char) 0x202E;
        char mark = (char) 0x200F;
        assertEquals("one override per word: " + shown, 2, count(shown, override));
        assertEquals("it has to open with a right-to-left mark, or the line runs left to right", mark, shown.charAt(0));
        String formatted = L10n.f(mirrored, "Full report saved to %1$s", "Movies/Instagram");
        assertTrue(formatted, formatted.contains("Movies/Instagram"));
        assertFalse(formatted, formatted.contains(override + "Movies"));
    }

    @Test
    public void anIsolatedValueIsWrappedInFirstStrongIsolates() {
        String isolated = L10n.isolate("580.0.0.51.74");
        assertEquals((char) 0x2068, isolated.charAt(0));
        assertEquals((char) 0x2069, isolated.charAt(isolated.length() - 1));
        assertEquals("580.0.0.51.74", isolated.substring(1, isolated.length() - 1));
        assertEquals("", L10n.isolate(""));
        assertEquals("", L10n.isolate(null));
    }

    /**
     * The catalog reads the phone's languages and never sets one: Instagram's own screens keep the
     * language Instagram chose, whatever HushGram's text comes out in.
     */
    @Test
    public void readingTheCatalogLeavesInstagramsLanguageAlone() {
        Context application = RuntimeEnvironment.getApplication();
        Locale defaultBefore = Locale.getDefault();
        LocaleList appBefore = application.getResources().getConfiguration().getLocales();

        for (Locale locale : new Locale[]{Locale.GERMANY, new Locale("tr", "TR"), new Locale("en", "XA"),
                new Locale("ar", "XB"), new Locale("in", "ID"), Locale.FRANCE, Locale.TAIWAN,
                Locale.forLanguageTag("zh-Hant"), Locale.SIMPLIFIED_CHINESE}) {
            Context phone = in(locale);
            L10n.t(phone, KEY);
            L10n.f(phone, "Full report saved to %1$s", "x");
            L10n.join(phone, Arrays.asList("a", "b", "c"));
            L10n.capitalize(phone, "indir");
            L10n.quantity(phone, 2, "%1$d", "%1$d");
            assertEquals(locale, phone.getResources().getConfiguration().getLocales().get(0));
        }
        assertEquals(defaultBefore, Locale.getDefault());
        assertEquals(appBefore, application.getResources().getConfiguration().getLocales());
    }

    /**
     * Instagram sets its own language on the application, and an activity or a dialog can carry
     * another. Every lookup answers in the application's, whichever of them asks, or a Back label
     * and the title beside it come out in two languages.
     */
    @Test
    @Config(qualifiers = "de")
    public void anActivitysOwnLanguageNeverReachesTheText() {
        Context englishActivity = configured(Locale.US);
        assertEquals(Locale.US, englishActivity.getResources().getConfiguration().getLocales().get(0));
        assertEquals(table("de").get(KEY), L10n.t(englishActivity, KEY));
        assertEquals(table("de").get("Cancel"), L10n.t(englishActivity, "Cancel"));
        assertEquals("a, b und c", L10n.join(englishActivity, Arrays.asList("a", "b", "c")));
        assertEquals(Locale.GERMANY.getLanguage(), L10n.locale(englishActivity).getLanguage());

        Context germanActivity = configured(Locale.GERMANY);
        RuntimeEnvironment.setQualifiers("en-rUS");
        assertEquals(KEY, L10n.t(germanActivity, KEY));
        assertEquals("Cancel", L10n.t(germanActivity, "Cancel"));
    }

    /** The same English is looked up again after the phone's languages change. */
    @Test
    public void aChangedLanguageListIsNeverAnsweredFromTheLastOne() {
        assertEquals(table("de").get(KEY), L10n.t(in(Locale.GERMANY), KEY));
        assertEquals(table("tr").get(KEY), L10n.t(in(new Locale("tr", "TR")), KEY));
        assertEquals(KEY, L10n.t(in(Locale.US), KEY));
        assertNotEquals(table("de").get(KEY), KEY);
    }

    private static Map<String, String> table(String language) {
        Map<String, String> table = L10nTranslations.of(language);
        assertNotNull(language, table);
        return table;
    }

    private static Locale locale(String tag) {
        switch (tag) {
            case "pt-rbr":
                return new Locale("pt", "BR");
            case "zh-rtw":
                return Locale.TAIWAN;
            case "in":
                return new Locale("in", "ID");
            default:
                return new Locale(tag);
        }
    }

    /**
     * A context whose phone is set to these languages, in this order. L10n reads the application's
     * configuration whichever context it is given, so this one stands in as its own application.
     */
    static Context in(Locale... locales) {
        Context configured = configured(locales);
        return new ContextWrapper(configured) {
            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
    }

    /** A context in these languages that belongs to the test's application, as an activity's does. */
    private static Context configured(Locale... locales) {
        Context base = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locales));
        return base.createConfigurationContext(configuration);
    }

    private static int count(String text, char wanted) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == wanted) count++;
        }
        return count;
    }
}
