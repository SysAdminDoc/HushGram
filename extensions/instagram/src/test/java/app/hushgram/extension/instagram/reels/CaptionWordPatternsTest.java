/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** Blocked words read as regular expressions (#883): what matches, what falls back to plain words, and what's never run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class CaptionWordPatternsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void enable() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.HIDE_CAPTION_WORD_REELS.save(true);
    }

    @After
    public void restore() {
        Settings.CAPTION_WORDS_AS_PATTERNS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_COMMENTS.resetToDefault();
        Settings.HIDE_CAPTION_WORD_REELS.resetToDefault();
        Settings.REELS_BLOCKED_WORDS.resetToDefault();
        // A hidden comment is counted in these.
        Settings.CAPTION_WORD_HIDDEN_TOTAL.resetToDefault();
        Settings.CAPTION_WORD_HIDDEN_HOURS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
    }

    /** Off, an entry is plain words; on, it's a pattern found anywhere, whatever the case. */
    @Test
    public void entriesArePatternsOnlyWithTheSwitch() {
        Settings.REELS_BLOCKED_WORDS.save("cr[iy]pto, \\bfree\\s+gift\\b");
        List<String> words = CaptionWordReels.wordsWhenOn();
        assertFalse("off to start", Settings.CAPTION_WORDS_AS_PATTERNS.get());
        assertFalse(CaptionWordReels.holdsAny("CRYPTO news", words));
        assertTrue("off, the entry is its own words", CaptionWordReels.holdsAny("about cr[iy]pto", words));

        Settings.CAPTION_WORDS_AS_PATTERNS.save(true);
        assertTrue(CaptionWordReels.holdsAny("CRYPTO news", words));
        assertTrue(CaptionWordReels.holdsAny("Crypto", words));
        assertTrue(CaptionWordReels.holdsAny("a Free   GIFT inside", words));
        assertFalse(CaptionWordReels.holdsAny("freegift", words));
        assertFalse(CaptionWordReels.holdsAny("crapto", words));
        assertFalse(CaptionWordReels.holdsAny(null, words));
        assertFalse("a list that isn't the stored one stays plain",
                CaptionWordReels.holdsAny("crypto", CaptionWordReels.words("cr[iy]pto")));
    }

    /** Paused, the switch reads off and every entry is plain words again. */
    @Test
    public void pausedEntriesArePlainWords() {
        Settings.REELS_BLOCKED_WORDS.save("cr[iy]pto");
        Settings.CAPTION_WORDS_AS_PATTERNS.save(true);
        assertTrue(CaptionWordReels.holdsAny("crypto", CaptionWordReels.wordsWhenOn()));
        try {
            BaseSettings.PAUSED.save(true);
            PauseForTests.pause(HushgramPause.Reason.SWITCH);
            List<String> words = CaptionWordReels.cached("cr[iy]pto");
            assertFalse(CaptionWordReels.holdsAny("crypto", words));
        } finally {
            BaseSettings.PAUSED.save(false);
            PauseForTests.resume();
        }
    }

    /**
     * An entry that isn't a valid pattern, or could stall the matcher, is matched as plain words, and
     * the list names it. An @name stays a name.
     */
    @Test
    public void anInvalidOrStallingEntryIsMatchedAsWordsAndNamed() {
        String stored = "(unclosed\n(a+)+b\nok\\d\n@CNN";
        Settings.REELS_BLOCKED_WORDS.save(stored);
        Settings.CAPTION_WORDS_AS_PATTERNS.save(true);
        List<String> words = CaptionWordReels.wordsWhenOn();
        assertTrue(CaptionWordReels.holdsAny("an (UNCLOSED one", words));
        assertTrue(CaptionWordReels.holdsAny("say OK7", words));
        assertFalse("the stalling pattern never runs", CaptionWordReels.holdsAny("aaaaaaaaaaaaaaaaaaaaaaaaaaaab", words));
        assertTrue("but its own words still match", CaptionWordReels.holdsAny("see (a+)+b here", words));
        assertTrue(CaptionWordReels.holdsAny("via @cnn", words));
        assertEquals(Arrays.asList("(unclosed", "(a+)+b"), CaptionWordReels.notPatterns(stored));
        assertEquals(Collections.emptyList(), CaptionWordReels.notPatterns("ok\\d, cr[iy]pto"));
    }

    /** With Also in comments on, a comment is matched against the patterns too. */
    @Test
    public void commentsAreMatchedAgainstThePatterns() {
        Settings.REELS_BLOCKED_WORDS.save("gr[ae]y");
        Settings.HIDE_CAPTION_WORD_COMMENTS.save(true);
        assertFalse(CaptionWordReels.dropsComment("so GREY", null));
        Settings.CAPTION_WORDS_AS_PATTERNS.save(true);
        assertTrue(CaptionWordReels.dropsComment("so GREY", null));
    }

    /** Only the first part of a long caption goes through a pattern. */
    @Test
    public void aPatternReadsOnlyTheStartOfALongCaption() {
        Settings.REELS_BLOCKED_WORDS.save("cr[iy]pto");
        Settings.CAPTION_WORDS_AS_PATTERNS.save(true);
        List<String> words = CaptionWordReels.wordsWhenOn();
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < CaptionWordPatterns.LONGEST_TEXT; i++) padding.append('x');
        assertTrue(CaptionWordReels.holdsAny("crypto " + padding, words));
        assertFalse(CaptionWordReels.holdsAny(padding + " crypto", words));
    }

    /** Commas split the list except inside brackets, braces and parentheses or after a backslash; a new line always does. */
    @Test
    public void theListSplitsAroundAPatternsOwnCommas() {
        assertEquals(Arrays.asList("a{2,3}", " [,;]x", " (b,c)", " d\\,e", ""),
                CaptionWordPatterns.entries("a{2,3}, [,;]x, (b,c), d\\,e,"));
        assertEquals(Arrays.asList("(open", "next"), CaptionWordPatterns.entries("(open\nnext"));
    }

    /** What Android's ICU refuses and desktop Java reads as itself is escaped; quantifiers and properties stay. */
    @Test
    public void aLoneBraceOrBracketIsEscapedForAndroid() {
        assertEquals("a\\}b\\]c\\{d", CaptionWordPatterns.icuSafe("a}b]c{d"));
        assertEquals("x{2,3}[a-z]\\p{L}+[\\]a]", CaptionWordPatterns.icuSafe("x{2,3}[a-z]\\p{L}+[]a]"));
        assertEquals("[^\\]a]", CaptionWordPatterns.icuSafe("[^]a]"));
        Pattern braces = CaptionWordPatterns.compile("smile :}");
        assertTrue(braces.matcher("SMILE :}").find());
    }

    /** A pattern that could keep the matcher busy is never compiled. */
    @Test
    public void aPatternThatCouldStallIsRefused() {
        for (String stalls : Arrays.asList("(a+)+", "(a|aa)*", "(\\w+\\s?)*", "(x*){2}", ".*a.*b.*c", "(a)\\1",
                "\\d?\\d?\\d?\\d?\\d?\\d?\\d?\\d?\\d?\\d?\\d?")) {
            assertTrue(stalls, CaptionWordPatterns.couldStall(stalls));
        }
        for (String fine : Arrays.asList("free\\s+gift", "(cat|dog)s?", "a{2,3}", "cr[iy]pto", "(?:buy|get)\\s+now",
                "\\p{L}+ing", "x+?y")) {
            assertFalse(fine, CaptionWordPatterns.couldStall(fine));
        }
        StringBuilder longest = new StringBuilder();
        for (int i = 0; i <= CaptionWordPatterns.LONGEST_PATTERN; i++) longest.append('a');
        assertNull(CaptionWordPatterns.compile(longest.toString()));
    }
}
