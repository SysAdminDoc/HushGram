/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import app.hushgram.extension.shared.Logger;

/**
 * The Blocked words list read as regular expressions, for Blocked words as regular expressions (#883).
 *
 * <p>Each entry is matched as a case-insensitive Java regular expression, found anywhere in the
 * text. The list is split at commas and new lines as the plain list is, except that a comma inside
 * brackets, braces or parentheses or after a backslash stays in its entry, so {@code a{2,3}} stays
 * one. A new line always ends an entry. An entry that
 * names an account with an @ stays a plain word, since the list matches those against a post's
 * author too.
 *
 * <p>Android's matcher has no time limit, so a pattern is bounded before it runs: an entry longer
 * than {@link #LONGEST_PATTERN}, one with a repeated group that itself repeats or branches (the
 * shape of {@code (a+)+}), a back reference, more than {@link #MOST_QUANTIFIERS} quantifiers or more
 * than {@link #MOST_UNBOUNDED} that can repeat without end is never compiled. Neither is one Android
 * refuses. Each of those is matched as plain words instead, and {@link #notPatterns} names it, so the
 * list's row can say which. Only the first {@link #LONGEST_TEXT} characters of a caption go through a
 * pattern, and a matcher that runs out of stack counts as no match.
 */
final class CaptionWordPatterns {
    /** The longest entry read as a pattern. */
    static final int LONGEST_PATTERN = 200;

    /** The most of a caption or comment a pattern reads. */
    static final int LONGEST_TEXT = 1000;

    /** The most quantifiers one pattern may hold. */
    static final int MOST_QUANTIFIERS = 10;

    /** The most quantifiers one pattern may hold that repeat without end ({@code *}, {@code +}, {@code {n,}}). */
    static final int MOST_UNBOUNDED = 2;

    private static volatile boolean loggedStack;

    /** The entries read as patterns. */
    final List<Pattern> patterns;

    /** The entries matched as plain words, lower case: @names and those not read as patterns. */
    final List<String> plain;

    /** The entries not read as patterns, as typed. */
    final List<String> notPatterns;

    private CaptionWordPatterns(List<Pattern> patterns, List<String> plain, List<String> notPatterns) {
        this.patterns = Collections.unmodifiableList(patterns);
        this.plain = Collections.unmodifiableList(plain);
        this.notPatterns = Collections.unmodifiableList(notPatterns);
    }

    /** [stored], the list as saved, read as patterns. */
    static CaptionWordPatterns of(String stored) {
        List<Pattern> patterns = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        List<String> notPatterns = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String piece : entries(stored)) {
            String entry = piece.trim();
            if (entry.isEmpty() || seen.contains(entry)) continue;
            seen.add(entry);
            if (entry.charAt(0) == '@') {
                plain.add(entry.toLowerCase(Locale.ROOT));
                continue;
            }
            Pattern pattern = compile(entry);
            if (pattern != null) {
                patterns.add(pattern);
            } else {
                notPatterns.add(entry);
                plain.add(entry.toLowerCase(Locale.ROOT));
            }
        }
        return new CaptionWordPatterns(patterns, plain, notPatterns);
    }

    /** Whether [text] holds a plain word or a pattern's match. No text holds nothing. */
    boolean holdsAny(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : plain) {
            if (lower.contains(word)) return true;
        }
        String read = text.length() > LONGEST_TEXT ? text.substring(0, LONGEST_TEXT) : text;
        for (Pattern pattern : patterns) {
            try {
                if (pattern.matcher(read).find()) return true;
            } catch (StackOverflowError deep) {
                if (!loggedStack) {
                    loggedStack = true;
                    Logger.printDebug(() -> "Caption words: a pattern ran out of stack and counted as no match");
                }
            }
        }
        return false;
    }

    /**
     * The entries of [stored]: split at new lines, and at commas outside brackets, braces and
     * parentheses and not right after a backslash.
     */
    static List<String> entries(String stored) {
        List<String> entries = new ArrayList<>();
        if (stored == null) return entries;
        StringBuilder entry = new StringBuilder();
        int depth = 0;
        boolean inClass = false;
        for (int i = 0; i < stored.length(); i++) {
            char c = stored.charAt(i);
            boolean newLine = c == '\n' || c == '\r';
            if (c == '\\' && i + 1 < stored.length() && !newLine(stored.charAt(i + 1))) {
                entry.append(c).append(stored.charAt(++i));
                continue;
            }
            if (newLine || c == ',' && depth == 0 && !inClass) {
                // A new line always ends an entry, so one left open can't take the rest of the list.
                entries.add(entry.toString());
                entry.setLength(0);
                depth = 0;
                inClass = false;
                continue;
            }
            if (inClass) {
                if (c == ']') inClass = false;
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(' || c == '{') {
                depth++;
            } else if ((c == ')' || c == '}') && depth > 0) {
                depth--;
            }
            entry.append(c);
        }
        entries.add(entry.toString());
        return entries;
    }

    private static boolean newLine(char c) {
        return c == '\n' || c == '\r';
    }

    /** [entry] compiled case-insensitively, or null when it's too long, could stall, or isn't a pattern Android takes. */
    static Pattern compile(String entry) {
        if (entry.length() > LONGEST_PATTERN || couldStall(entry)) return null;
        try {
            return Pattern.compile(icuSafe(entry), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (RuntimeException | StackOverflowError refused) {
            return null;
        }
    }

    /**
     * Whether [pattern] could keep Android's matcher busy: a back reference, a repeated group that
     * repeats or branches inside, more than {@link #MOST_QUANTIFIERS} quantifiers, or more than
     * {@link #MOST_UNBOUNDED} that repeat without end.
     */
    static boolean couldStall(String pattern) {
        int quantifiers = 0;
        int unbounded = 0;
        // One entry per open group: whether its body repeats or branches.
        Deque<boolean[]> groups = new ArrayDeque<>();
        boolean riskyGroup = false;
        boolean afterQuantifier = false;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                if (i + 1 < pattern.length()) {
                    char next = pattern.charAt(i + 1);
                    if (next >= '1' && next <= '9' || next == 'k') return true;
                    i = skipEscape(pattern, i);
                }
                riskyGroup = false;
                afterQuantifier = false;
                continue;
            }
            if (inClass) {
                if (c == ']') inClass = false;
                continue;
            }
            int end = c == '{' ? quantifierEnd(pattern, i) : -1;
            boolean repeats = c == '*' || c == '+' || end > 0;
            if ((c == '?' || c == '+') && afterQuantifier) {
                // A lazy or possessive mark on the quantifier before it.
                afterQuantifier = false;
                continue;
            }
            if (repeats || c == '?') {
                quantifiers++;
                boolean endless = c != '?' && (end < 0 || pattern.charAt(end - 1) == ',');
                if (endless) unbounded++;
                if (repeats && riskyGroup) return true;
                if (!groups.isEmpty() && repeats) groups.peek()[0] = true;
                if (end > 0) i = end;
                riskyGroup = false;
                afterQuantifier = true;
                continue;
            }
            afterQuantifier = false;
            riskyGroup = false;
            if (c == '[') {
                inClass = true;
            } else if (c == '(') {
                groups.push(new boolean[1]);
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') i++;
            } else if (c == ')') {
                boolean[] group = groups.poll();
                riskyGroup = group != null && group[0];
                if (riskyGroup && !groups.isEmpty()) groups.peek()[0] = true;
            } else if (c == '|' && !groups.isEmpty()) {
                groups.peek()[0] = true;
            }
        }
        return quantifiers > MOST_QUANTIFIERS || unbounded > MOST_UNBOUNDED;
    }

    /**
     * [pattern] with each brace that isn't a quantifier's and each closing bracket outside a class
     * escaped: desktop Java reads a lone {@code }} or {@code ]} as itself, and Android's ICU refuses it.
     */
    static String icuSafe(String pattern) {
        StringBuilder safe = new StringBuilder(pattern.length() + 8);
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                int end = skipEscape(pattern, i);
                safe.append(pattern, i, end + 1);
                i = end;
                continue;
            }
            if (inClass) {
                if (c == ']') inClass = false;
                safe.append(c);
                continue;
            }
            if (c == '[') {
                inClass = true;
                safe.append(c);
                // A bracket right after the opening one, or after its ^, is part of the class.
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '^') safe.append(pattern.charAt(++i));
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == ']') safe.append('\\').append(pattern.charAt(++i));
                continue;
            }
            if (c == '{') {
                int end = quantifierEnd(pattern, i);
                if (end > 0) {
                    safe.append(pattern, i, end + 1);
                    i = end;
                    continue;
                }
            }
            if (c == '{' || c == '}' || c == ']') safe.append('\\');
            safe.append(c);
        }
        return safe.toString();
    }

    /** Where the escape at [at] ends: past a braced argument for \p, \P, \x and \N, else its one character. */
    private static int skipEscape(String pattern, int at) {
        int next = at + 1;
        char kind = pattern.charAt(next);
        if ((kind == 'p' || kind == 'P' || kind == 'x' || kind == 'N')
                && next + 1 < pattern.length() && pattern.charAt(next + 1) == '{') {
            int close = pattern.indexOf('}', next + 2);
            if (close > 0) return close;
        }
        return next;
    }

    /** Where the quantifier opened by the brace at [at] closes, {@code {n}}, {@code {n,}} or {@code {n,m}}, or -1. */
    private static int quantifierEnd(String pattern, int at) {
        int i = at + 1;
        int digits = 0;
        while (i < pattern.length() && Character.isDigit(pattern.charAt(i))) {
            i++;
            digits++;
        }
        if (digits == 0 || i >= pattern.length()) return -1;
        if (pattern.charAt(i) == ',') {
            i++;
            while (i < pattern.length() && Character.isDigit(pattern.charAt(i))) i++;
        }
        return i < pattern.length() && pattern.charAt(i) == '}' ? i : -1;
    }
}
