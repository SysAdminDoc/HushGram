/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import android.content.Context;
import android.icu.lang.UScript;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.LocaleList;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;

/**
 * The language rule of "Hide reels in other languages" (#495): whether a caption is written in a
 * language the person didn't list under Languages to keep.
 *
 * <p>A caption is cleaned first: hashtags, mentions, links, emoji, digits and punctuation go. With
 * fewer than {@link #MIN_LETTERS} letters left it can't be told, and the reel stays. Then two
 * layers decide, and either can only say "other" when it is sure:
 * <ul>
 * <li>The script. Each kept language has a script (Hindi is Devanagari, English is Latin), found
 * with ICU on every Android this runs on. A caption mostly in a script no kept language uses is in
 * another language, whatever the classifier thinks.
 * <li>The language. When the scripts fit, Android's own text classifier (API 29 and up) names the
 * likeliest language. It counts only at {@link #MIN_CONFIDENCE} or more, and a missing classifier,
 * an error or a low score all mean "can't tell", so the reel stays. Romanized Hindi often reads as
 * English or scores low; both stay when both are kept.
 * </ul>
 *
 * <p>Thread: Instagram's Reels page callback hands pages over on whichever thread its network layer
 * delivers them, which is normally the main thread. The 12 letter floor and the script layer cost
 * nothing, and an answer is kept by caption in a small bounded cache. The classifier is a call into
 * the system that can be slow, so on the main thread it's asked on a thread of its own and waited
 * for at most {@link #MAIN_WAIT_MILLIS}; a caption it hasn't answered by then stays, and its answer
 * counts from the next page. Off the main thread it's asked directly. Nothing here throws.
 */
public final class CaptionLanguage {
    /** Fewer letters than this after cleaning and the caption can't be told. */
    static final int MIN_LETTERS = 12;

    /** The least score the classifier's top language needs to count. */
    static final float MIN_CONFIDENCE = 0.6f;

    /** Captions remembered by the rule that read them. */
    static final int CACHE_SIZE = 256;

    /** The most text handed to the classifier; a caption's start tells its language. */
    private static final int MAX_TEXT = 600;

    /** How long the main thread waits for the classifier before the reel stays this time. */
    static final long MAIN_WAIT_MILLIS = 40;

    /** Captions waiting for the classifier at most; past it, more stay unasked until it catches up. */
    static final int MAX_PENDING = 32;

    /** The main thread's wait. Tests that time the classifier set their own. */
    static volatile long mainWaitMillis = MAIN_WAIT_MILLIS;

    /** The classifier's thread for main-thread callers, so a slow system call can't hold up a page. */
    private static final ExecutorService CLASSIFIER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "HushGram caption language");
        thread.setDaemon(true);
        return thread;
    });

    /** The language the classifier thinks a text is in, and how sure it is. */
    static final class Guess {
        final String language;
        final float confidence;

        Guess(String language, float confidence) {
            this.language = language;
            this.confidence = confidence;
        }
    }

    /** Names a text's likeliest language, or null when it can't. May throw; the caller catches. */
    interface Detector {
        Guess detect(String text);
    }

    private static final Pattern LINKS = Pattern.compile("(?i)(?:https?://|www\\.)\\S+");
    private static final Pattern TAGS = Pattern.compile("[#\\x{FF03}@\\x{FF20}][\\p{L}\\p{N}\\p{M}_.]+");

    /** The languages typed under Languages to keep, as codes, with the pieces that were not understood. */
    static final class Parsed {
        /** Language codes, in the order typed, repeats left out. */
        final List<String> codes;
        /** Pieces that are no language this knows, as typed. */
        final List<String> unknown;
        /** True when the setting was empty and the phone's own languages were used. */
        final boolean fromPhone;

        Parsed(List<String> codes, List<String> unknown, boolean fromPhone) {
            this.codes = Collections.unmodifiableList(codes);
            this.unknown = Collections.unmodifiableList(unknown);
            this.fromPhone = fromPhone;
        }

        /** What the row's summary says under its title. */
        String summary() {
            String names = join(displayNames(codes));
            if (!unknown.isEmpty()) {
                String bad = join(unknown);
                return codes.isEmpty() ? L10n.f("Didn't recognize: %1$s", bad)
                        : L10n.f("Keeps %1$s. Didn't recognize: %2$s", names, bad);
            }
            return fromPhone ? L10n.f("Keeps your phone's languages: %1$s", names) : L10n.f("Keeps %1$s", names);
        }
    }

    /**
     * One reading of the setting: the kept languages and their scripts, the detector, and the
     * answers already given. Rebuilt when the stored text changes.
     */
    public static final class Rule {
        final Parsed kept;
        private final Set<Integer> scripts;
        private final Detector detector;
        private final LinkedHashMap<String, Boolean> answers = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > CACHE_SIZE;
            }
        };

        /** Captions handed to the classifier's thread and not answered yet. Guarded by [answers]. */
        private final Set<String> pending = new HashSet<>();
        /** Set when a main-thread wait ran out: no more waits until the classifier has caught up. */
        private volatile boolean behind;

        Rule(Parsed kept, Detector detector) {
            this.kept = kept;
            this.detector = detector;
            this.scripts = scriptsOf(kept.codes);
        }

        /** Whether the rule has any kept language to compare with. Without one it hides nothing. */
        public boolean active() {
            return !kept.codes.isEmpty();
        }

        /** Whether [caption] is in a language that isn't kept. Undetermined, empty and failures answer false. */
        public boolean isOther(String caption) {
            if (caption == null || caption.isEmpty() || !active()) return false;
            try {
                synchronized (answers) {
                    Boolean known = answers.get(caption);
                    if (known != null) return known;
                }
                String text = clean(caption);
                if (letters(text) < MIN_LETTERS) return false;
                if (otherScript(text, scripts)) return remember(caption, true);
                if (detector == null) return remember(caption, false);
                String sample = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
                if (!Utils.isCurrentlyOnMainThread()) return remember(caption, otherLanguage(sample));
                return Boolean.TRUE.equals(askOnTheSide(caption, sample));
            } catch (Throwable failure) {
                return false;
            }
        }

        private boolean remember(String caption, boolean other) {
            synchronized (answers) {
                answers.put(caption, other);
            }
            return other;
        }

        /**
         * The classifier's answer for [caption] from its own thread, or null when it isn't in within
         * the main thread's wait. A late answer goes into the cache for the next page. After one wait
         * runs out the main thread stops waiting until the thread has caught up, so a slow classifier
         * costs one wait, not one a caption.
         */
        private Boolean askOnTheSide(String caption, String sample) {
            synchronized (answers) {
                if (pending.contains(caption) || pending.size() >= MAX_PENDING) return null;
                pending.add(caption);
            }
            Future<Boolean> answer;
            try {
                answer = CLASSIFIER.submit(() -> {
                    boolean other = false;
                    try {
                        other = otherLanguage(sample);
                    } finally {
                        synchronized (answers) {
                            pending.remove(caption);
                            answers.put(caption, other);
                            if (pending.isEmpty()) behind = false;
                        }
                    }
                    return other;
                });
            } catch (Throwable refused) {
                synchronized (answers) {
                    pending.remove(caption);
                }
                return null;
            }
            if (behind) return null;
            try {
                return answer.get(mainWaitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException late) {
                synchronized (answers) {
                    // It may have come in between the wait and here.
                    if (!pending.contains(caption)) return answers.get(caption);
                    behind = true;
                }
                return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Throwable failure) {
                return null;
            }
        }

        private boolean otherLanguage(String sample) {
            Guess guess;
            try {
                guess = detector.detect(sample);
            } catch (Throwable failure) {
                return false;
            }
            if (guess == null || guess.language == null || guess.confidence < MIN_CONFIDENCE) return false;
            String language = normalize(guess.language);
            return !language.isEmpty() && !kept.codes.contains(language);
        }
    }

    /** One reading of the setting: the text, the phone's languages then, and the rule they make. */
    private static final class Reading {
        final String stored;
        final String phone;
        final Rule rule;

        Reading(String stored, String phone, Rule rule) {
            this.stored = stored;
            this.phone = phone;
            this.rule = rule;
        }
    }

    /** The last reading, so a page doesn't parse the setting again. */
    private static volatile Reading current;
    private static volatile Detector systemDetector;

    private CaptionLanguage() {
    }

    // The setting

    /** The rule while Hide reels in other languages is on and the settings are read; otherwise null. */
    public static Rule ruleWhenOn() {
        if (!Utils.settingsReady() || !Settings.HIDE_OTHER_LANGUAGE_REELS.get()) return null;
        return ruleFor(Settings.REELS_KEPT_LANGUAGES.get());
    }

    /** The rule for Home's posts: also needs Also on Home on. */
    public static Rule homeRuleWhenOn() {
        if (!Utils.settingsReady() || !Settings.HIDE_OTHER_LANGUAGE_REELS.get()
                || !Settings.HIDE_OTHER_LANGUAGE_HOME.get()) return null;
        return ruleFor(Settings.REELS_KEPT_LANGUAGES.get());
    }

    private static Rule ruleFor(String stored) {
        // An empty setting means the phone's languages, so a change of those is a new rule too.
        String phone = phoneTags();
        Reading known = current;
        if (known != null && Objects.equals(known.stored, stored) && Objects.equals(known.phone, phone)) {
            return known.rule.active() ? known.rule : null;
        }
        Rule rule = new Rule(parse(stored), systemDetector());
        current = new Reading(stored, phone, rule);
        return rule.active() ? rule : null;
    }

    private static String phoneTags() {
        try {
            return LocaleList.getDefault().toLanguageTags();
        } catch (Throwable failure) {
            return Locale.getDefault().toLanguageTag();
        }
    }

    /** Forgets the rule and its answers; the next page reads the setting again. */
    public static void forget() {
        current = null;
    }

    /** Waits for the classifier's thread to finish what it was handed. For tests. */
    static void awaitClassifierForTests() throws Exception {
        CLASSIFIER.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    /** What the settings row says under its title for [stored]. */
    public static String summary(String stored) {
        return parse(stored).summary();
    }

    // Parsing

    private static volatile Map<String, String> names;

    /**
     * The languages in [stored]: pieces split at commas and new lines, each an ISO 639 code, an
     * English name or the language's own name, whatever the case. An empty text means the phone's
     * languages. A piece that is none of those is listed as unknown.
     */
    static Parsed parse(String stored) {
        List<String> codes = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        boolean any = false;
        if (stored != null) {
            for (String piece : stored.split("[,\\n\\r]")) {
                String trimmed = piece.trim();
                if (trimmed.isEmpty()) continue;
                any = true;
                String code = codeOf(trimmed);
                if (code == null) {
                    if (!unknown.contains(trimmed)) unknown.add(trimmed);
                } else if (!codes.contains(code)) {
                    codes.add(code);
                }
            }
        }
        if (!any) return new Parsed(phoneLanguages(), unknown, true);
        return new Parsed(codes, unknown, false);
    }

    private static List<String> phoneLanguages() {
        List<String> codes = new ArrayList<>();
        try {
            LocaleList list = LocaleList.getDefault();
            for (int i = 0; i < list.size(); i++) {
                String code = normalize(list.get(i).getLanguage());
                if (!code.isEmpty() && !codes.contains(code)) codes.add(code);
            }
        } catch (Throwable failure) {
            // Fall through to the single default below.
        }
        if (codes.isEmpty()) {
            String code = normalize(Locale.getDefault().getLanguage());
            if (!code.isEmpty()) codes.add(code);
        }
        return codes;
    }

    /** The language code for a typed piece, or null when it isn't one. */
    static String codeOf(String piece) {
        String lower = piece.toLowerCase(Locale.ROOT);
        String base = lower.split("[-_]", 2)[0];
        if (base.matches("[a-z]{2,3}")) {
            String code = normalize(base);
            if (isoCodes().contains(code)) return code;
            // Three letters: hin is hi, and fil, yue or haw have no two-letter code at all.
            String shorter = threeLetterCodes().get(code);
            if (shorter == null) shorter = SPELLED_SHORTER.get(code);
            if (shorter != null) return shorter;
            if (code.length() == 3 && namedByIcu(code)) return code;
        }
        return nameTable().get(lower);
    }

    /**
     * Three-letter codes getISO3Language never gives, to the code the classifier answers for them:
     * a language inside a macrolanguage (Mandarin's cmn is zh) and ISO 639-2's library spellings
     * (ger for German). Kept as typed, every caption in that language would count as another one.
     */
    private static final Map<String, String> SPELLED_SHORTER = new HashMap<>();

    static {
        String[] pairs = {
                "cmn", "zh", "arb", "ar", "nob", "no", "nno", "no", "swh", "sw", "zsm", "ms", "pes", "fa",
                "alb", "sq", "arm", "hy", "baq", "eu", "bur", "my", "chi", "zh", "cze", "cs", "dut", "nl",
                "fre", "fr", "geo", "ka", "ger", "de", "gre", "el", "ice", "is", "mac", "mk", "mao", "mi",
                "may", "ms", "per", "fa", "rum", "ro", "slo", "sk", "tib", "bo", "wel", "cy"};
        for (int i = 0; i < pairs.length; i += 2) SPELLED_SHORTER.put(pairs[i], pairs[i + 1]);
    }

    private static volatile Map<String, String> iso3;

    /** Each two-letter language's three-letter code to its two-letter one. */
    private static Map<String, String> threeLetterCodes() {
        Map<String, String> known = iso3;
        if (known == null) {
            known = new HashMap<>();
            for (String raw : Locale.getISOLanguages()) {
                try {
                    String three = Locale.forLanguageTag(raw).getISO3Language();
                    if (three != null && three.length() == 3) known.put(three, normalize(raw));
                } catch (Throwable missing) {
                    // A language with no three-letter code adds nothing.
                }
            }
            iso3 = known;
        }
        return known;
    }

    /** Whether ICU has a name for [code], so fil and yue count and xyz doesn't. */
    private static boolean namedByIcu(String code) {
        try {
            String name = new ULocale(code).getDisplayLanguage(ULocale.ENGLISH);
            return name != null && !name.isEmpty() && !name.equalsIgnoreCase(code);
        } catch (Throwable failure) {
            return false;
        }
    }

    private static volatile Set<String> iso;

    private static Set<String> isoCodes() {
        Set<String> known = iso;
        if (known == null) {
            known = new HashSet<>();
            for (String code : Locale.getISOLanguages()) known.add(normalize(code));
            iso = known;
        }
        return known;
    }

    /** Every language's English name and its own name, lower case, to its code. */
    private static Map<String, String> nameTable() {
        Map<String, String> table = names;
        if (table != null) return table;
        table = new HashMap<>();
        for (String raw : Locale.getISOLanguages()) {
            String code = normalize(raw);
            Locale locale = Locale.forLanguageTag(raw);
            put(table, locale.getDisplayLanguage(Locale.ENGLISH), code);
            put(table, locale.getDisplayLanguage(locale), code);
        }
        // The languages ICU has data for that only have three-letter codes: Filipino, Cantonese.
        try {
            for (ULocale available : ULocale.getAvailableLocales()) {
                String code = normalize(available.getLanguage());
                if (code.length() != 3) continue;
                ULocale language = new ULocale(code);
                put(table, language.getDisplayLanguage(ULocale.ENGLISH), code);
                put(table, language.getDisplayLanguage(language), code);
            }
        } catch (Throwable failure) {
            // The two-letter names above still count.
        }
        names = table;
        return table;
    }

    private static void put(Map<String, String> table, String name, String code) {
        if (name == null || name.isEmpty()) return;
        String key = name.toLowerCase(Locale.ROOT);
        if (!table.containsKey(key)) table.put(key, code);
    }

    /** A code Android, ICU and the classifier may spell two ways, in one form, lower case. */
    static String normalize(String code) {
        if (code == null) return "";
        String lower = code.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            // A phone in Filipino says fil, and the classifier may say tl for the same captions.
            case "tl": return "fil";
            case "nb":
            case "nn": return "no";
            default: return lower;
        }
    }

    private static List<String> displayNames(List<String> codes) {
        List<String> shown = new ArrayList<>();
        for (String code : codes) {
            String name = Locale.forLanguageTag(code).getDisplayLanguage();
            shown.add(name == null || name.isEmpty() ? code : name);
        }
        return shown;
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(", ");
            out.append(part);
        }
        return out.toString();
    }

    // Cleaning and scripts

    /** [caption] with hashtags, mentions, links, emoji, digits and punctuation turned into spaces. */
    static String clean(String caption) {
        String text = LINKS.matcher(caption).replaceAll(" ");
        text = TAGS.matcher(text).replaceAll(" ");
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (Character.isLetter(point) || isWordMark(point)) out.appendCodePoint(point);
            else out.append(' ');
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** A combining mark that belongs to a word (Devanagari vowel signs), not an emoji selector or joiner. */
    private static boolean isWordMark(int point) {
        int type = Character.getType(point);
        if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK) return false;
        return !(point >= 0xFE00 && point <= 0xFE0F) && !(point >= 0xE0100 && point <= 0xE01EF);
    }

    private static int letters(String text) {
        int count = 0;
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (Character.isLetter(point)) count++;
        }
        return count;
    }

    /** Whether most of [text]'s letters are in a script none of the kept languages use. */
    static boolean otherScript(String text, Set<Integer> kept) {
        if (kept.isEmpty()) return false;
        int total = 0;
        int outside = 0;
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (!Character.isLetter(point)) continue;
            int script = UScript.getScript(point);
            if (script == UScript.COMMON || script == UScript.INHERITED || script == UScript.UNKNOWN) continue;
            total++;
            if (!kept.contains(script)) outside++;
        }
        return total > 0 && outside * 2 > total;
    }

    /** The scripts the languages in [codes] are written in. */
    static Set<Integer> scriptsOf(List<String> codes) {
        Set<Integer> found = new HashSet<>();
        for (String code : codes) {
            try {
                String script = ULocale.addLikelySubtags(new ULocale(code)).getScript();
                if (script == null || script.isEmpty()) continue;
                int[] ids = UScript.getCode(script);
                if (ids != null) for (int id : ids) found.add(id);
                // ICU names a language's mix of scripts as one code.
                switch (script) {
                    case "Jpan":
                        found.add(UScript.HAN);
                        found.add(UScript.HIRAGANA);
                        found.add(UScript.KATAKANA);
                        break;
                    case "Kore":
                        found.add(UScript.HANGUL);
                        found.add(UScript.HAN);
                        break;
                    case "Hans":
                    case "Hant":
                        found.add(UScript.HAN);
                        found.add(UScript.BOPOMOFO);
                        break;
                    default:
                        break;
                }
            } catch (Throwable failure) {
                // A code ICU can't place adds no script; the language layer still applies.
            }
        }
        return found;
    }

    // The classifier

    private static Detector systemDetector() {
        Detector known = systemDetector;
        if (known != null) return known;
        known = Build.VERSION.SDK_INT < 29 ? null : CaptionLanguage::classify;
        systemDetector = known;
        return known;
    }

    /** Android's text classifier (API 29 and up) naming the top language of [text]. */
    private static Guess classify(String text) {
        Context context = Utils.getContext();
        if (context == null) return null;
        TextClassificationManager manager = context.getSystemService(TextClassificationManager.class);
        if (manager == null) return null;
        TextClassifier classifier = manager.getTextClassifier();
        TextLanguage result = classifier.detectLanguage(new TextLanguage.Request.Builder(text).build());
        String best = null;
        float top = 0f;
        for (int i = 0; i < result.getLocaleHypothesisCount(); i++) {
            ULocale locale = result.getLocale(i);
            float score = result.getConfidenceScore(locale);
            if (score > top) {
                top = score;
                best = locale.getLanguage();
            }
        }
        return best == null ? null : new Guess(best, top);
    }
}
