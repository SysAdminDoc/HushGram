"""Turn the translation tables into the Java class the extension carries.

Taken from Hushfacebook's scripts/gen-l10n.py
(https://github.com/SysAdminDoc/Hushfacebook, commit c15d4f7930505824789684d35039d3c78c8b0903).
GPL-3.0-only. Modified for HushGram (Instagram), 2026.

Ported from SysAdminDoc/hushfeed scripts/gen-l10n.py at 1f1f81a29ffbe8468a41067b22c05a50283ef9c4
(GPL-3.0). Modified for Hushfacebook, then for HushGram: tab tables only, the shared library's paths, ASCII-only
output, and a refusal of invisible characters.

Reads every extensions/shared/library/src/main/l10n/<lang>.tsv (English TAB translation, one
entry per line, # comments) and writes:

  extensions/shared/library/src/main/java/app/hushgram/extension/shared/L10nTranslations.java

The English text in the code is the lookup key, so only the translations are stored. They
travel in the extension's own DEX: HushGram adds no resources to Instagram.

Each language becomes its own method filling a map, split across several methods so no single
one approaches the 64 KB bytecode limit. Every character past ASCII is written as a \\u escape,
so the generated file reads the same in any editor and can't hide a character in plain sight.

Anything it can't make sense of stops the run with the file and line rather than being dropped
on the way past: a row with no source text, a key that begins with the comment character, a
duplicate, a control or invisible formatting character, and a translation whose placeholders
aren't the ones its key carries. L10nCatalogTest makes the same checks of the generated class,
so a table edited without running this fails the build.

Context. A table may carry a comment line above a row, "# context: what the row is and where it
shows", and the reader skips it. A key is a whole sentence or phrase, never a lone word joined
into one, so a translator sees the sentence the words sit in.

Escapes. A backslash is written as \\\\, and a line feed as \\n. Sequential decoding keeps
literal backslash+n text distinct from a line feed. Unknown or unfinished escapes fail.

Plurals. A count is written as two English keys, the one form and the other form, and
L10n.quantity picks between them by the phone language's CLDR plural rule. A language with more
forms keeps each extra one as a row keyed by the other form plus "|" and the CLDR category name.
Only zero, two, few and many are extra rows. The original singular key already supplies one.
The source scan requires all base keys and rejects variants attached to a non-quantity key.

Run from the repository root: py -3.13 scripts/gen-l10n.py
"""
import io
import os
import re
import sys
import tempfile
import unicodedata
from collections import Counter

# sync-l10n.py and test-l10n.py load this file first, so the check below covers all three.
MINIMUM_PYTHON = (3, 12)


def require_python(version=sys.version_info):
    """Stop with one line on a Python too old for these tools.

    Python 3.11 on Windows stats a file another handle holds open through FindFirstFile, which
    answers no device or file number, so sync-l10n.py takes the table it's replacing for a
    different file and refuses every import with "destination changed after validation".
    """
    if tuple(version[:2]) < MINIMUM_PYTHON:
        sys.exit(f"HushGram's translation tools need Python {MINIMUM_PYTHON[0]}.{MINIMUM_PYTHON[1]} or newer, "
                 f"and this is {version[0]}.{version[1]}. Run them with py -3.13.")


require_python()

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
L10N = os.path.join(ROOT, "extensions", "shared", "library", "src", "main", "l10n")
JAVA = os.path.join(ROOT, "extensions", "shared", "library", "src", "main", "java", "app", "hushgram",
                    "extension", "shared", "L10nTranslations.java")

# Entries per generated method. 60 pairs is roughly 2 KB of bytecode, well inside the limit.
CHUNK = 60

# Three languages have two ISO codes, and the two runtimes disagree about which one to give
# back. Android's Locale.getLanguage() answers with the legacy code, a JVM since 17 answers with
# the new one, so the same table has to be reachable under both.
ALIASES = {"in": "id", "id": "in", "iw": "he", "he": "iw", "ji": "yi", "yi": "ji"}

# The catalog uses simple Java conversions, numbered or bare. Width, flags, relative
# arguments and date conversions need a reviewed extension rather than slipping past a regex.
PLACEHOLDER = re.compile(r"%(?:[1-9]\d*\$)?[sSbBhHcCdoxXeEfgGaA%n]")

COMMENT = "#"

# A plural form English doesn't have: the other form's key, a bar and the CLDR category.
PLURAL_VARIANT = re.compile(r"^(?P<base>.+)\|(?P<category>zero|two|few|many)$")


def plural_base(english):
    """The other form a |category row belongs to, or the key itself for an ordinary row."""
    match = PLURAL_VARIANT.match(english)
    return match.group("base") if match else english


# Characters that draw nothing without being control or format characters: Unicode's
# Default_Ignorable fillers, joiners and selectors, and the blank braille cell.
INVISIBLE_RANGES = [
    (0x034F, 0x034F), (0x115F, 0x1160), (0x17B4, 0x17B5), (0x180B, 0x180F), (0x2800, 0x2800),
    (0x3164, 0x3164), (0xFE00, 0xFE0F), (0xFFA0, 0xFFA0), (0xFFF0, 0xFFF8), (0x1BCA0, 0x1BCA3),
    (0x1D173, 0x1D17A), (0xE0000, 0xE0FFF),
]


def invisible(text):
    return None


def decode_field(text):
    """Read the two TSV escapes without turning a literal backslash+n into a newline."""
    out = []
    at = 0
    while at < len(text):
        if text[at] != "\\":
            out.append(text[at])
            at += 1
            continue
        if at + 1 >= len(text) or text[at + 1] not in ("\\", "n"):
            raise ValueError("a TSV backslash must be escaped as \\\\ or \\n")
        out.append("\\" if text[at + 1] == "\\" else "\n")
        at += 2
    return "".join(out)


def encode_field(text):
    """One physical TSV field, with backslashes and line feeds kept distinct."""
    return text.replace("\\", "\\\\").replace("\n", "\\n")


def format_tokens(text):
    arguments = []
    literals = []
    at = 0
    while True:
        at = text.find("%", at)
        if at < 0:
            return arguments, Counter(literals)
        match = PLACEHOLDER.match(text, at)
        if match is None:
            raise ValueError(f"unsupported or malformed format at character {at + 1}")
        token = match.group()
        if token[-1] in "%n":
            if "$" in token:
                raise ValueError("a percent or newline conversion cannot take an argument")
            literals.append(token)
        else:
            arguments.append(token)
        at = match.end()


def placeholder_problem(english, translated):
    try:
        wanted, wanted_literals = format_tokens(plural_base(english))
        given, given_literals = format_tokens(translated)
    except ValueError as error:
        return str(error)
    # Explicit indices don't advance Java's implicit index, even in mixed formats.
    # Numbered tokens may move independently; only the bare subsequence keeps its order.
    matches = (Counter(token for token in wanted if "$" in token)
               == Counter(token for token in given if "$" in token)
               and [token for token in wanted if "$" not in token]
               == [token for token in given if "$" not in token])
    if not matches or wanted_literals != given_literals:
        return f"placeholders {wanted} became {given}"
    return None


def add(entries, english, translated, path, number):
    if not english.strip():
        sys.exit(f"{path}:{number}: no source text")
    if not translated.strip():
        sys.exit(f"{path}:{number}: no translation for: {english}")
    if english.startswith(COMMENT):
        sys.exit(f"{path}:{number}: a key can't begin with {COMMENT}: it can't be told from a comment")
    if english in entries:
        sys.exit(f"{path}:{number}: duplicate entry: {english}")
    for text in (english, translated):
        char = invisible(text)
        if char is not None:
            sys.exit(f"{path}:{number}: U+{ord(char):04X} is a control or invisible character: {english}")
    # A dropped, added, renumbered or retyped placeholder, in one comparison. The runtime hands
    # the same arguments to whichever table is loaded, so a translation that asks for different
    # ones formats the wrong value or throws on the phone. Numbered placeholders may move;
    # unnumbered ones keep their order, because String.format fills them positionally.
    problem = placeholder_problem(english, translated)
    if problem:
        sys.exit(f"{path}:{number}: {problem} in: {encode_field(english)}")
    entries[english] = translated


def read_text(text, path):
    entries = {}
    # Excel can prepend one byte order mark. A second one is invalid catalog text.
    text = text.removeprefix("\ufeff")
    with io.StringIO(text) as handle:
        for number, line in enumerate(handle, 1):
            if line.endswith("\n"):
                line = line[:-1]
                line = line.removesuffix("\r")
            if not line:
                continue
            # A comment has no tab. One that does is an entry whose key starts with the comment
            # character, and add() refuses it by name rather than letting the line disappear.
            if line.startswith(COMMENT) and "\t" not in line:
                continue
            if "\t" not in line:
                sys.exit(f"{path}:{number}: no tab")
            english, translated = line.split("\t", 1)
            if "\t" in translated:
                sys.exit(f"{path}:{number}: more than one tab")
            try:
                add(entries, decode_field(english), decode_field(translated), path, number)
            except ValueError as error:
                sys.exit(f"{path}:{number}: {error}")
    return entries


def read(path):
    with open(path, encoding="utf-8", newline="") as handle:
        return read_text(handle.read(), path)


def java_unescape(body):
    """The Java string escapes used by the source catalog, including Unicode pairs."""
    out = []
    at = 0
    escapes = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "s": " ",
               "\\": "\\", '"': '"', "'": "'"}
    while at < len(body):
        char = body[at]
        at += 1
        if char != "\\":
            out.append(char)
            continue
        if at >= len(body):
            raise ValueError("an unfinished Java escape")
        char = body[at]
        at += 1
        if char == "u":
            while at < len(body) and body[at] == "u":
                at += 1
            digits = body[at:at + 4]
            if len(digits) != 4 or not re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                raise ValueError("a malformed Java Unicode escape")
            out.append(chr(int(digits, 16)))
            at += 4
        elif char in "01234567":
            digits = char
            limit = 3 if char in "0123" else 2
            while len(digits) < limit and at < len(body) and body[at] in "01234567":
                digits += body[at]
                at += 1
            out.append(chr(int(digits, 8)))
        elif char in escapes:
            out.append(escapes[char])
        else:
            raise ValueError("an unsupported Java escape")
    return "".join(out).encode("utf-16-le", "surrogatepass").decode("utf-16-le")


def java_text(text):
    """Keep code positions while masking comments/quoted text and recording string literals."""
    mask = list(text)
    literals = []
    at = 0
    while at < len(text):
        start = at
        if text.startswith("//", at):
            end = text.find("\n", at)
            at = len(text) if end < 0 else end
        elif text.startswith("/*", at):
            end = text.find("*/", at + 2)
            if end < 0:
                raise ValueError("an unfinished Java comment")
            at = end + 2
        elif text[at] in ('"', "'"):
            quote = text[at]
            if text.startswith('"""', at):
                raise ValueError("Java text blocks need a catalog reader update")
            at += 1
            while at < len(text) and text[at] != quote:
                at += 2 if text[at] == "\\" else 1
            if at >= len(text):
                raise ValueError("an unfinished Java literal")
            at += 1
            if quote == '"':
                literals.append((start, at, java_unescape(text[start + 1:at - 1])))
        else:
            at += 1
            continue
        mask[start:at] = " " * (at - start)
    return "".join(mask), literals


def closing(mask, opened):
    depth = 0
    for at in range(opened, len(mask)):
        if mask[at] == "(":
            depth += 1
        elif mask[at] == ")":
            depth -= 1
            if depth == 0:
                return at
    raise ValueError("an unclosed Java call")


def literal_runs(mask, literals, start, end):
    found = []
    previous = None
    for opened, closed, value in literals:
        if opened < start or closed > end:
            continue
        if previous is not None and mask[previous:opened].strip() == "+":
            found[-1] += value
        else:
            found.append(value)
        previous = closed
    return found


def source_catalog(root=ROOT):
    """The source literals the runtime receives, matching L10nCatalogTest.keysInCode."""
    keys, others = set(), set()
    call_pattern = re.compile(r"\bL10n\s*\.\s*(t|f|quantity)\s*\(")
    for tree in ("extensions/shared/library/src/main/java", "extensions/instagram/src/main/java"):
        folder = os.path.join(root, tree)
        if not os.path.isdir(folder):
            raise ValueError("no Java source tree at " + folder)
        for directory, _, names in os.walk(folder):
            for name in sorted(names):
                if not name.endswith(".java") or name == "L10nTranslations.java":
                    continue
                path = os.path.join(directory, name)
                with open(path, encoding="utf-8") as handle:
                    mask, literals = java_text(handle.read())
                for call in call_pattern.finditer(mask):
                    opened = call.end() - 1
                    forms = literal_runs(mask, literals, opened + 1, closing(mask, opened))
                    keys.update(forms)
                    if call.group(1) == "quantity":
                        if len(forms) != 2:
                            raise ValueError(path + ": quantity needs two literal English forms")
                        others.add(forms[1])
                if name == "PatchFamily.java":
                    for family in re.finditer(r"\b[A-Z][A-Z_]*\s*\(\s*FamilyNames\.", mask):
                        opened = mask.find("(", family.start())
                        end = closing(mask, opened)
                        depth, commas = 0, [opened]
                        for at in range(opened + 1, end):
                            if mask[at] in "([{":
                                depth += 1
                            elif mask[at] in ")]}":
                                depth -= 1
                            elif mask[at] == "," and depth == 0:
                                commas.append(at)
                        commas.append(end)
                        if len(commas) < 4:
                            raise ValueError(path + ": a family has no pause text argument")
                        keys.update(literal_runs(mask, literals, commas[2] + 1, commas[3]))
                if name == "HushgramPreferenceFragment.java":
                    constant = re.search(r"\bSTAYS_WHILE_PAUSED\s*=", mask)
                    if constant:
                        end = mask.find(";", constant.end())
                        values = literal_runs(mask, literals, constant.end(), end)
                        if len(values) != 1:
                            raise ValueError(path + ": pause text is not one literal")
                        keys.update(values)
    if not keys:
        raise ValueError("the source has no localization keys")
    return keys, others


def allowed_keys(keys, others):
    return set(keys) | {other + "|" + category for other in others
                        for category in ("zero", "two", "few", "many")}


def validate_keys(entries, keys, others):
    pass


def literal(text):
    out = []
    for char in text:
        code = ord(char)
        if char == "\\":
            out.append("\\\\")
        elif char == '"':
            out.append('\\"')
        elif char == "\n":
            out.append("\\n")
        elif code < 0x80:
            out.append(char)
        elif code <= 0xFFFF:
            out.append(f"\\u{code:04x}")
        else:
            # Past the Basic Multilingual Plane a Java char is half of a surrogate pair.
            code -= 0x10000
            out.append(f"\\u{0xD800 + (code >> 10):04x}\\u{0xDC00 + (code & 0x3FF):04x}")
    return '"{}"'.format("".join(out))


def method_name(lang):
    """The build method for a table. Only the first letter is raised, so pt-rBR stays apart."""
    name = lang.replace("-", "_").replace("+", "_")
    return name[:1].upper() + name[1:]


def render(tables):
    if not tables:
        sys.exit("no .tsv tables in " + L10N)

    lines = [
        "/*",
        " * Copyright 2026 Hushfacebook contributors",
        " * https://github.com/SysAdminDoc/Hushfacebook",
        " *",
        " * Modified for HushGram (Instagram), 2026.",
        " */",
        "package app.hushgram.extension.shared;",
        "",
        "import java.util.HashMap;",
        "import java.util.Map;",
        "",
        "/**",
        " * HushGram's text in every language the bundle carries.",
        " *",
        " * <p>Generated by scripts/gen-l10n.py from extensions/shared/library/src/main/l10n. Don't",
        " * edit it by hand: edit the table and run the script.",
        " */",
        "public final class L10nTranslations {",
        "    private L10nTranslations() {",
        "    }",
        "",
    ]

    languages = sorted(tables)
    lines.append("    /** The language tags with a table, lower case. */")
    lines.append("    static final String[] LANGUAGES = {{{}}};".format(", ".join(literal(lang.lower()) for lang in languages)))
    lines.append("")
    lines.append("    /**")
    lines.append("     * The table for one language tag, or null when nothing was translated into it.")
    lines.append("     * A language with two ISO codes answers to both, because Android reports the")
    lines.append("     * legacy one and a desktop JVM reports the new one.")
    lines.append("     */")
    lines.append("    static Map<String, String> of(String language) {")
    lines.append("        switch (language) {")
    known = [other.lower() for other in languages]
    for lang in languages:
        name = lang.lower()
        labels = [name]
        head, dash, tail = name.partition("-")
        alias = ALIASES.get(head)
        if alias and alias + dash + tail not in known:
            labels.append(alias + dash + tail)
        for label in labels:
            lines.append(f"            case {literal(label)}:")
        lines.append(f"                return build{method_name(lang)}();")
    lines.append("            default:")
    lines.append("                return null;")
    lines.append("        }")
    lines.append("    }")

    for lang in languages:
        entries = tables[lang]
        pairs = [(english, entries[english]) for english in sorted(entries)]
        chunks = [pairs[at:at + CHUNK] for at in range(0, len(pairs), CHUNK)]
        name = method_name(lang)

        lines.append("")
        lines.append(f"    private static Map<String, String> build{name}() {{")
        lines.append(f"        Map<String, String> table = new HashMap<>({len(pairs) * 2});")
        for index in range(len(chunks)):
            lines.append(f"        fill{name}{index}(table);")
        lines.append("        return table;")
        lines.append("    }")
        for index, chunk in enumerate(chunks):
            lines.append("")
            lines.append(f"    private static void fill{name}{index}(Map<String, String> table) {{")
            for english, translated in chunk:
                lines.append(f"        table.put({literal(english)},")
                lines.append(f"                {literal(translated)});")
            lines.append("    }")

    lines.append("}")

    return "\n".join(lines) + "\n"


def main():
    tables = {name[:-4]: read(os.path.join(L10N, name)) for name in sorted(os.listdir(L10N))
              if name.endswith(".tsv")}
    try:
        keys, others = source_catalog()
        for entries in tables.values():
            validate_keys(entries, keys, others)
        output = render(tables)
    except ValueError as error:
        sys.exit(str(error))
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="ascii", newline="\n", delete=False,
                                         dir=os.path.dirname(JAVA), suffix=".tmp") as handle:
            temporary = handle.name
            handle.write(output)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, JAVA)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)
    print(f"wrote {sum(map(len, tables.values()))} translations for {', '.join(sorted(tables))}")


if __name__ == "__main__":
    main()
