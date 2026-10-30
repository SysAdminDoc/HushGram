/*
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.hushgram.extension.shared.diagnostics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes request addresses, credentials and device identifiers from exported text. */
public final class DiagnosticRedactor {
    /** Instagram's hosts, CDN and short links, and the Facebook and Messenger ones Instagram loads from. */
    private static final String HOST_SUFFIXES =
            "(?:instagram\\.com|cdninstagram\\.com|instagr\\.am|ig\\.me|threads\\.net|threads\\.com"
                    + "|facebook\\.com|facebook\\.net|fbcdn\\.net|fbsbx\\.com|fb\\.com|fb\\.me|fb\\.watch"
                    + "|fb\\.gg|messenger\\.com|meta\\.com|meta\\.ai)";
    /**
     * A word edge, between an ASCII letter, digit or underscore and anything else, written out for
     * the rules to use in place of \b. Android runs them on ICU, whose \b takes a letter such as é,
     * я or 猫 for part of a word, so étoken=secret kept its value on a phone while the JDK the
     * tests run on dropped it. Spelled in ASCII classes, the edge falls in the same place on both.
     * Case is switched off inside it, or ICU would fold ſ and the Kelvin sign into s and k.
     */
    private static final String EDGE =
            "(?-i:(?:(?<![A-Za-z0-9_])(?=[A-Za-z0-9_])|(?<=[A-Za-z0-9_])(?![A-Za-z0-9_])))";
    /**
     * The spaces the JDK's \s means, spelled out for use inside a class in its place. ICU's \s
     * also takes a no-break space and the other Unicode spaces, so on Android a value holding one
     * lost only its first part. Spelled in ASCII, a value runs on past them on both engines, which
     * leaves more out rather than less.
     */
    private static final String SPACE = " \\t\\n\\x0B\\f\\r";
    /**
     * Every letter ICU folds into plain ASCII when it ignores case and the JDK doesn't: the sharp
     * s, its capital, the long s, the Kelvin sign and the Latin ligatures. ICU read paßword as
     * password and toKen as token where the JDK didn't, and neither found ſecret. A letter like
     * these can also stand where any letter outside ASCII does, before a name (ſaid=), which only
     * the JDK's reading caught. So the rules run first with each one as an edge, then, if any is
     * left, again with it written out as {@link #PLAIN_LETTERS}. Both engines read the text the
     * same way in each pass, and whatever either reading finds goes. ß left in a report comes out
     * as ss.
     */
    private static final String FOLDING = new String(new char[]{
            0x00DF, 0x1E9E, 0x017F, 0x212A, 0xFB00, 0xFB01, 0xFB02, 0xFB03, 0xFB04, 0xFB05, 0xFB06});
    /** What each letter in {@link #FOLDING} folds to. */
    private static final String[] PLAIN_LETTERS = {"ss", "SS", "s", "K", "ff", "fi", "fl", "ffi", "ffl", "st", "st"};
    /**
     * The first of the noncharacters that stand in for the letters in {@link #FOLDING} during the
     * first pass. Unicode keeps them for a program's own use, so text never carries them.
     */
    private static final char STAND_IN = 0xFDD0;
    /**
     * English words holding sid, uid, iid, guid or auth, which a name may be as a whole and keep its
     * value, alone or as a yes-or-no flag ({@code isAuthor}). Not authentic or authenticate: those
     * name credentials.
     */
    private static final String ORDINARY_WORDS =
            "(?:is|has)?(?:inside|insides|outside|beside|insider|residual|residuals|residue|considered|considers"
                    + "|considering|consider|reconsider|president|residence|subsidy|upside|downside|aside|sidebar"
                    + "|guided|guides|guiding|guidance|guide|misguided|fluidity|fluid|liquid|squid|druid|authors"
                    + "|authored|authoring|author|authorize|authorized|authorizes|authorizing|unauthorized"
                    + "|authorities|authority)";
    /**
     * Credential and device names. sessionid, ds_user_id and ig_did make up an Instagram session,
     * and X-IG-WWW-Claim, X-MID and the IG-U- headers go with its requests. c_user, xs and datr are
     * the cookies that make up a Facebook
     * session, and fr and sb go with them; fb_dtsg is its request token; family_device_id,
     * X-FB-Device-ID and advertiser_id identify the phone across Meta's apps. xs, fr, sb and pwd
     * are matched whole below, being too short to look for inside a word.
     *
     * <p>sid, uid, iid and auth (and guid, which holds uid) count anywhere in a name, run into
     * other words or not: authkey, basicauth, SAPISID and FBUID are all credentials. They also sit
     * inside ordinary words, and the value after one of those is often what a report is read for,
     * so a name that is wholly one of {@link #ORDINARY_WORDS} keeps its value. The list is short on
     * purpose: a name nobody thought of stays hidden.
     */
    private static final String CREDENTIAL_NAMES =
            "[a-z0-9_-]*(?:token|session|sessionid|secret|password|passwd|passphrase|passcode|signature"
                    + "|cookie|credential|api_?key|access_?key|private_?key|device[_-]?id|install[_-]?id"
                    + "|openudid|c_user|datr|fb_dtsg|ds_user_id|ig_did|ig-u-ds-user-id|ig-u-rur|x-ig-www-claim|x-mid"
                    + "|machine[_-]?id|advertiser[_-]?id|advertising[_-]?id"
                    + "|adid)[a-z0-9_-]*"
                    + "|(?!" + ORDINARY_WORDS + "(?![a-z0-9_]|-(?!>)))[a-z0-9_-]*(?:sid|uid|iid|auth)[a-z0-9_-]*"
                    + "|xs|fr|sb|pwd";
    /** Names whose unquoted value can hold spaces and semicolons, so it runs to the end of its line. */
    private static final String PASSWORD_NAMES = "[a-z0-9_-]*(?:password|passwd|passphrase|passcode)[a-z0-9_-]*|pwd";
    /**
     * Names carrying the id of one account, post, story, comment or message. Each of these
     * resolves to something somebody can open, so a shared report would otherwise carry a slice of
     * what was read, or who read it. The short ones, aid and cid, are matched whole or after an
     * underscore or hyphen, so an ordinary setting such as {@code hide_paid_partnership} keeps its
     * value. The longer ones may follow any prefix, camel case included ({@code topLevelPostId}),
     * and take a hyphen as well as an underscore. An account's own id is {@link #USER_ID_NAMES}.
     */
    private static final String CONTENT_ID_NAMES =
            "(?:[a-z0-9]+[_-])*(?:aid|cid)|[a-z0-9_-]*(?:fbid|story[_-]?id|post[_-]?id|feedback[_-]?id"
                    + "|video[_-]?id|item[_-]?id|group[_-]?id|page[_-]?id|profile[_-]?id|actor[_-]?id"
                    + "|thread[_-]?id|comment[_-]?id|msg[_-]?id|message[_-]?id)";
    /**
     * Names carrying the id of an account, or a list of them: user_id, userid, userId, user-id and
     * their plurals, after any prefix ({@code X-User-Id}).
     */
    private static final String USER_ID_NAMES = "[a-z0-9_-]*user[_-]?ids?";
    /**
     * A quote written as a JSON unicode escape, backslash u 0022. Built in two parts so no tool
     * that reads this file turns the escape into the quote itself.
     */
    private static final String ESCAPED_QUOTE = "\\\\" + "u0022";
    /**
     * A name's closing quote: plain, escaped once or more when the JSON is itself inside a string,
     * as a unicode escape, as HTML or percent-encoded.
     */
    private static final String QUOTE_MARK = "(?:\\\\*[\"']|" + ESCAPED_QUOTE + "|&quot;|%22)";
    /**
     * What comes between a name and its value: spaces, the name's closing quote, then =, :, =>
     * or ->, written plain or percent-encoded. It never crosses a line break, so a name at the end
     * of a line can't take the stack frame printed under it.
     */
    private static final String SEPARATOR = "[ \\t]*" + QUOTE_MARK + "?[ \\t]*(?:=>|->|[=:]|%3[ad])[ \\t]*";
    /**
     * A quoted value, whole. A double-quoted one ends at a quote escaped exactly as its opening
     * one was, so an escaped quote inside a JSON string, or JSON inside a string, doesn't end it
     * early. Values quoted by unicode escape, HTML or percent-encoding end at the same mark. An
     * unclosed one runs to the end of its line.
     */
    private static final String QUOTED =
            "(?:(?<q>\\\\*)\"(?:[^\\\\\"\\r\\n]|\\\\++(?!\")|(?!\\k<q>\")\\\\+\")*(?:\\k<q>\")?"
                    + "|'(?:[^\\\\'\\r\\n]|\\\\.)*'?"
                    + "|" + ESCAPED_QUOTE + "(?:(?!" + ESCAPED_QUOTE + ")[^\\r\\n])*(?:" + ESCAPED_QUOTE + ")?"
                    + "|&quot;(?:(?!&quot;)[^\\r\\n])*(?:&quot;)?"
                    + "|%22(?:(?!%22)[^" + SPACE + "&])*(?:%22)?)";
    /** The schemes an Authorization value names before its credential. */
    private static final String SCHEME = "(?:bearer|basic|digest|oauth|negotiate)";
    /** A credential's characters: the token68 of RFC 9110, and percent signs. */
    private static final String TOKEN = "[a-z0-9._~+/=%-]+";
    /** An indented line Java prints for a trace, which a header's continuation must never take. */
    private static final String NOT_TRACE =
            "(?!at[" + SPACE + "]|caused by:|suppressed:|\\.\\.\\.[" + SPACE + "])";
    /**
     * An Authorization or cookie header, whose whole value is private: every cookie in it, and the
     * credential after Bearer, Basic or OAuth, not only the scheme's name. A value may start on
     * the next line when that line starts with a scheme, and a credential may carry on alone on
     * the line after it. An unquoted value without a scheme runs to the end of its line and takes
     * indented continuation lines, but never a Java trace line.
     */
    private static final String HEADER =
            "(?i)" + EDGE + "((?:proxy-)?authorization|set-cookie|cookie)" + SEPARATOR + "(?:" + QUOTED
                    + "|(?:\\r?\\n[ \\t]*(?=" + SCHEME + "[ \\t]))?"
                    + "(?:" + SCHEME + "(?:[ \\t]+|[ \\t]*\\r?\\n[ \\t]*" + NOT_TRACE + ")" + TOKEN
                    + "(?:\\r?\\n[ \\t]*" + TOKEN + "(?=[ \\t]*(?:\\r?\\n|$)))?[^\\r\\n]*"
                    + "|[^\\r\\n]*(?:\\r?\\n[ \\t]++" + NOT_TRACE + "[^\\r\\n]*)*))";
    /**
     * A name and value pair as HAR files and header dumps print them, {"name": ..., "value": ...},
     * where the name is a header, cookie or id the rules know. Only the value goes.
     */
    private static final String NAME_VALUE_PAIR =
            "(?i)(\\\\*\"name\\\\*\"[ \\t]*:[ \\t]*\\\\*\"(?:(?:proxy-)?authorization|set-cookie|" + CREDENTIAL_NAMES
                    + "|" + USER_ID_NAMES + "|" + CONTENT_ID_NAMES + ")\\\\*\"[ \\t]*,[ \\t]*\\\\*\"value\\\\*\"[ \\t]*:[ \\t]*)" + QUOTED;
    /**
     * A Bearer, OAuth or Basic credential written with no header name in front of it. A word
     * counts as one only with a digit, +, / or = in it, so "OAuth callback" and "Basic settings"
     * stay as written.
     */
    private static final String BARE_SCHEME =
            "(?i)" + EDGE + "(bearer|oauth|basic)(?:[ \\t]+|%20)(?=[a-z._~-]*[0-9+/=%])[a-z0-9._~+/=%-]{8,}";
    /** Where a credential's or an id's object or list starts: its name, the separator, then { or [. */
    private static final Pattern BLOCK_START = Pattern.compile("(?i)" + EDGE + "(" + CREDENTIAL_NAMES + "|"
            + USER_ID_NAMES + "|" + CONTENT_ID_NAMES + ")" + SEPARATOR + "(?=[\\[{])");
    /** How far an object or list is followed before it's cut at the end of its first line. */
    private static final int BLOCK_MAX_CHARS = 8_192;
    /**
     * Ids are printed in comma separated lists, and the value pattern the credential rule uses
     * stops at the first comma, so everything after the first id stayed in the report.
     */
    private static final String CONTENT_ID_VALUE = "\"?[^" + SPACE + ";&\"'<>]+";
    /**
     * An account id's unquoted value. A list of them may be written with spaces as well as commas,
     * so it runs on over spaces and stops only at the next name with a separator after it
     * ({@code action=hide}), a semicolon, ampersand, quote, closing brace or parenthesis, or the
     * end of its line. Angle brackets are part of it, as in {@code user_id=<id>}, and so are square
     * ones, so a list the block rule already cut to {@code [omitted]} is taken whole.
     */
    private static final String USER_ID_VALUE = "[^ \\t\\r\\n;&\"')}]*(?:[ \\t]++(?![a-z][a-z0-9_-]*" + SEPARATOR
            + ")[^ \\t\\r\\n;&\"')}]*)*";
    /**
     * A bare id, for the places that print a list of them with no name in front. Facebook's
     * account ids are fifteen digits (the newer ones seventeen) and its post and story ids run to
     * nineteen; nothing else these reports carry is a number that long. A millisecond timestamp
     * is thirteen digits, so it stays readable.
     *
     * <p>Bounded by digits rather than by word edges. A CDN file name joins its ids with
     * underscores ({@code 475148478_1134540631592283_1316146539584337463_n.jpg}), an underscore is
     * a word character, and a word-bounded rule found no edge there.
     *
     * <p>A digit is any script's decimal digit on both engines, which is what ICU's \d means and
     * the JDK's isn't: an id printed in Arabic-Indic digits, as some languages print numbers, went
     * on a phone and stayed in the tests.
     */
    private static final String BARE_CONTENT_ID = "(?<!\\p{Nd})\\p{Nd}{15,21}(?!\\p{Nd})";
    /**
     * A creator's name, as the bundle writes it into a toast or a banner: between Unicode's
     * first-strong isolate U+2068 and its pop U+2069. Every toast is written to the buffer as it
     * is shown, in whatever language the phone speaks, so the name cannot be found by the words
     * around it. The isolate pair marks it in every language. A run with no pop is cut to the
     * end of the line, so a name that lost its closing mark is still not printed.
     */
    private static final String ISOLATED_NAME = "⁨[^⁩\\n]*⁩?";
    /**
     * A name right after @ that the credential, user id or content id rules would otherwise
     * redact: {@code @token}, {@code @access_token}, {@code @c_user}, {@code @user_id}. Left to
     * {@link #HANDLE}, taken first, it took the name out of {@code @token=secret} and left the
     * value behind, the same way a name glued to a non-ASCII letter did. {@link #NAMED_HANDLE}
     * waits for those rules instead.
     */
    private static final String KNOWN_HANDLE_NAME =
            "(?:" + CREDENTIAL_NAMES + "|" + USER_ID_NAMES + "|" + CONTENT_ID_NAMES + ")";
    /**
     * An account handle, which starts with @ and stands on its own. One glued to something in
     * front of it is not a handle: an email address, or the identity hash Java prints after a
     * class name. Only ASCII counts as glued, as with {@link #EDGE}, so a handle straight after
     * text with no spaces, such as Japanese, still goes on both engines. That one waits for
     * {@link #GLUED_HANDLE}, since the name it holds may be a credential's ({@code é@token=}).
     * One naming a credential or an id the redactor knows waits for {@link #NAMED_HANDLE}
     * instead, so the value behind {@code @token=secret} is not left holding the name's = sign.
     */
    private static final String HANDLE = "(?<![A-Za-z0-9_.@/:])(?<![^\\x00-\\x7F])@(?!(?i:"
            + KNOWN_HANDLE_NAME + "))[A-Za-z0-9_.]{2,}";
    /**
     * A handle straight after a character outside ASCII, taken last. Taken first, it took the
     * name out of {@code é@token=secret} or {@code josé@facebook.com/dana.q.1987} and left the
     * secret or the path behind, so the credential, id and host rules see it before it goes.
     */
    private static final String GLUED_HANDLE = "(?<=[^\\x00-\\x7F])@[A-Za-z0-9_.]{2,}";
    /**
     * A handle naming a credential, a user id or a content id, taken last for the same reason as
     * {@link #GLUED_HANDLE}: taken first, it left {@code @token=secret}, {@code @c_user=100012}
     * and {@code @user_id=100012} holding their values, since the name behind the @ was gone by
     * the time the credential, user id and content id rules ran.
     */
    private static final String NAMED_HANDLE = "(?<![A-Za-z0-9_.@/:])(?<![^\\x00-\\x7F])@(?=(?i:"
            + KNOWN_HANDLE_NAME + "))[A-Za-z0-9_.]{2,}";
    /**
     * One of Facebook's hosts without a scheme, with any port or path after it. The subdomain is
     * optional: the rule asked for one, so {@code facebook.com/dana.q.1987} passed while
     * {@code www.facebook.com/dana.q.1987} didn't. A host has to end at a word edge, so a package
     * name ({@code com.facebook.katana}) and a longer name ({@code facebook.community}) stay.
     */
    private static final String HOST =
            "(?i)" + EDGE + "(?:[a-z0-9-]+\\.)*" + HOST_SUFFIXES + EDGE + "(?:[:/][^" + SPACE + "\"'<>]*)?";
    /**
     * A file in shared storage, where the reader's own photos, downloads and documents live. It
     * runs to the end of the line, a quote, or the ": " an exception puts after a path, since a
     * file name can hold spaces. App and system paths stay: they name code, not the reader's files.
     */
    private static final String STORAGE_PATH = "(?<![A-Za-z0-9_.~/-])/(?:storage|sdcard|data/media"
            + "|mnt/(?:media_rw|user|pass_through|runtime|sdcard|installer|androidwritable))/"
            + "[^\\r\\n\"'<>]*?(?=: |[\"'<>\\r\\n]|$)";

    /*
     * The rules, compiled once. Logger redacts every line it writes to the system log, Debug
     * logging's lines on the feed's hot paths included, and compiling these on each call cost more
     * than running them.
     */
    private static final Pattern ISOLATED_NAME_RULE = Pattern.compile(ISOLATED_NAME);
    private static final Pattern HANDLE_RULE = Pattern.compile(HANDLE);
    private static final Pattern URL_RULE = Pattern.compile("(?i)" + EDGE + "[a-z][a-z0-9+.-]*://[^" + SPACE + "\"'<>]+");
    private static final Pattern STORAGE_PATH_RULE = Pattern.compile(STORAGE_PATH);
    private static final Pattern HOST_RULE = Pattern.compile(HOST);
    private static final Pattern NAME_VALUE_PAIR_RULE = Pattern.compile(NAME_VALUE_PAIR);
    private static final Pattern HEADER_RULE = Pattern.compile(HEADER);
    private static final Pattern BARE_SCHEME_RULE = Pattern.compile(BARE_SCHEME);
    private static final Pattern PASSWORD_RULE = Pattern.compile(
            "(?i)" + EDGE + "(" + PASSWORD_NAMES + ")" + SEPARATOR + "(?:" + QUOTED + "|[^\\r\\n]*)");
    private static final Pattern CREDENTIAL_RULE = Pattern.compile("(?i)" + EDGE + "(" + CREDENTIAL_NAMES + ")" + SEPARATOR
            + "(?:" + QUOTED + "|[^" + SPACE + ",&\"'<>]+)");
    private static final Pattern USER_ID_RULE = Pattern.compile("(?i)" + EDGE + "(" + USER_ID_NAMES + ")" + SEPARATOR
            + "(?:" + QUOTED + "|" + USER_ID_VALUE + ")");
    private static final Pattern CONTENT_ID_RULE = Pattern.compile("(?i)" + EDGE + "(" + CONTENT_ID_NAMES + ")" + SEPARATOR
            + "(?:" + QUOTED + "|" + CONTENT_ID_VALUE + ")");
    private static final Pattern BARE_CONTENT_ID_RULE = Pattern.compile(BARE_CONTENT_ID);
    private static final Pattern NAMED_HANDLE_RULE = Pattern.compile(NAMED_HANDLE);
    private static final Pattern GLUED_HANDLE_RULE = Pattern.compile(GLUED_HANDLE);

    private DiagnosticRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return "";
        String once = withoutPrivateValues(withStandIns(text));
        String plain = withPlainLetters(once);
        return plain == once ? once : withoutPrivateValues(plain);
    }

    /** Every rule, in order. */
    private static String withoutPrivateValues(String text) {
        String passed = text;
        passed = ISOLATED_NAME_RULE.matcher(passed).replaceAll("[name omitted]");
        passed = HANDLE_RULE.matcher(passed).replaceAll("[handle omitted]");
        passed = URL_RULE.matcher(passed).replaceAll("[url omitted]");
        passed = STORAGE_PATH_RULE.matcher(passed).replaceAll("[path omitted]");
        passed = HOST_RULE.matcher(passed).replaceAll("[host omitted]");
        passed = NAME_VALUE_PAIR_RULE.matcher(passed).replaceAll("$1[omitted]");
        passed = HEADER_RULE.matcher(passed).replaceAll("$1=[omitted]");
        passed = BARE_SCHEME_RULE.matcher(passed).replaceAll("$1 [omitted]");
        passed = PASSWORD_RULE.matcher(passed).replaceAll("$1=[omitted]");
        passed = withoutCredentialBlocks(passed);
        passed = CREDENTIAL_RULE.matcher(passed).replaceAll("$1=[omitted]");
        passed = USER_ID_RULE.matcher(passed).replaceAll("$1=[omitted]");
        passed = CONTENT_ID_RULE.matcher(passed).replaceAll("$1=[omitted]");
        passed = BARE_CONTENT_ID_RULE.matcher(passed).replaceAll("[id omitted]");
        passed = NAMED_HANDLE_RULE.matcher(passed).replaceAll("[handle omitted]");
        return GLUED_HANDLE_RULE.matcher(passed).replaceAll("[handle omitted]");
    }

    /**
     * The text with each letter in {@link #FOLDING} swapped for its stand-in, and any stand-in it
     * already held (they never belong in text) for U+FFFD. Neither engine folds a stand-in into
     * anything, and it counts as an edge, like any letter outside ASCII.
     */
    private static String withStandIns(String text) {
        StringBuilder out = null;
        for (int at = 0; at < text.length(); at++) {
            char letter = text.charAt(at);
            int folding = FOLDING.indexOf(letter);
            char put;
            if (folding >= 0) {
                put = (char) (STAND_IN + folding);
            } else if (letter >= STAND_IN && letter < STAND_IN + FOLDING.length()) {
                put = (char) 0xFFFD;
            } else {
                if (out != null) out.append(letter);
                continue;
            }
            if (out == null) out = new StringBuilder(text.length()).append(text, 0, at);
            out.append(put);
        }
        return out == null ? text : out.toString();
    }

    /** The text with every stand-in left in it written out as the plain letters its letter folds to. */
    private static String withPlainLetters(String text) {
        StringBuilder out = null;
        for (int at = 0; at < text.length(); at++) {
            char letter = text.charAt(at);
            if (letter < STAND_IN || letter >= STAND_IN + FOLDING.length()) {
                if (out != null) out.append(letter);
                continue;
            }
            if (out == null) out = new StringBuilder(text.length() + 16).append(text, 0, at);
            out.append(PLAIN_LETTERS[letter - STAND_IN]);
        }
        return out == null ? text : out.toString();
    }

    /**
     * Every object or list a credential's or an id's name holds, whole, however deep it nests. A pattern can
     * only follow nesting to a depth written into it, and it can't tell a brace inside a quoted
     * string from one that closes the object, so this counts brackets outside quotes instead.
     */
    private static String withoutCredentialBlocks(String text) {
        Matcher start = BLOCK_START.matcher(text);
        StringBuilder out = null;
        int copied = 0;
        int from = 0;
        while (from < text.length() && start.find(from)) {
            int end = blockEnd(text, start.end());
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, copied, start.start()).append(start.group(1)).append("=[omitted]");
            copied = end;
            from = end;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /**
     * Just past the bracket that closes the one at [open], or the end of its line when none does
     * within {@link #BLOCK_MAX_CHARS}. A text that ends first, such as a log line cut to a length,
     * ends inside the block, so all of what's left is the block. A quote opens or closes a string
     * when it's escaped the way the object's first quote is, so JSON inside a JSON string is read
     * at its own level.
     */
    private static int blockEnd(String text, int open) {
        int limit = Math.min(text.length(), open + BLOCK_MAX_CHARS);
        int level = -1;
        boolean quoted = false;
        int depth = 0;
        for (int at = open; at < limit; at++) {
            char c = text.charAt(at);
            if (c == '"') {
                int run = 0;
                while (at - run - 1 >= open && text.charAt(at - run - 1) == '\\') run++;
                if (level < 0) level = run;
                if (level == 0 ? run % 2 == 0 : run == level) quoted = !quoted;
            } else if (!quoted) {
                if (c == '{' || c == '[') {
                    depth++;
                } else if ((c == '}' || c == ']') && --depth == 0) {
                    return at + 1;
                }
            }
        }
        if (limit == text.length() && depth > 0) return limit;
        int line = open;
        while (line < text.length() && text.charAt(line) != '\n' && text.charAt(line) != '\r') line++;
        return line;
    }
}
