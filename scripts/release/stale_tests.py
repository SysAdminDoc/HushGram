"""Find tests a code-only commit left stale, before the release gate's Gradle runs find them one at a time.

Two checks, both reading sources only:

1. Every method verifyAndroidBoundaries requires still exists as a test method in its class.
2. Every text the settings tests expect (assertEquals' expected value and contains()'s argument)
   still occurs in the extension sources or the translation tables. A source format string counts
   for any text its fixed parts match, so "Stop after 20 reels" is found in "Stop after %1$d reels".

Exit 0 when both pass, 1 with one line per stale name or text otherwise.

Copyright 2026 HushGram contributors. GPL-3.0-only.
https://github.com/SysAdminDoc/HushGram
"""
import argparse
import re
import sys
from pathlib import Path

if sys.version_info < (3, 10):
    sys.exit("stale_tests.py needs Python 3.10 or newer")

ROOT = Path(__file__).resolve().parents[2]
EXTENSION = "extensions/instagram"
GRADLE = f"{EXTENSION}/build.gradle.kts"
TESTS = f"{EXTENSION}/src/test/java"
SETTINGS_TESTS = f"{TESTS}/app/hushgram/extension/instagram/settings"
SOURCES = (f"{EXTENSION}/src/main/java", "extensions/shared/library/src/main/java")
TABLES = "extensions/shared/library/src/main/l10n"

STRING = r'"(?:[^"\\\n]|\\.)*"'
FORMAT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
# A method JUnit runs: @Test (or @ParameterizedTest), any other annotations and modifiers, then a
# Java void method or a Kotlin fun, generic or with a backticked name.
TEST_METHOD = re.compile(
    r"@(?:Test|ParameterizedTest)\b(?:\s*\([^)]*\))?"
    r"(?:\s*@[\w.]+(?:\s*\([^)]*\))?)*"
    r"(?:\s*(?:public|protected|private|internal|static|final|open|override|suspend))*"
    r"\s*(?:void|fun(?:\s*<[^>]*>)?)\s+`?(\w+)`?\s*\(")
# A format string needs this many characters of its own text to stand for a test's text, so a
# bare "%s" can't match everything.
MIN_FIXED = 4
SENTENCE_WORDS = 3
# Punctuation a test keeps around a piece it checks, like ": on (".
EDGES = " \t\n:;,.()[]-"
ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "0": "\0", "'": "'", '"': '"', "\\": "\\", "s": " "}


def unescape(body: str) -> str:
    """Java string literal body to its text."""
    out, i = [], 0
    while i < len(body):
        c = body[i]
        if c != "\\" or i + 1 == len(body):
            out.append(c)
            i += 1
            continue
        nxt = body[i + 1]
        if nxt == "u":
            j = i + 1
            while j < len(body) and body[j] == "u":
                j += 1
            out.append(chr(int(body[j:j + 4], 16)))
            i = j + 4
        else:
            out.append(ESCAPES.get(nxt, nxt))
            i += 2
    return "".join(out)


def strip_comments(text: str) -> str:
    """Java or Kotlin text with comments blanked, string and char literals kept."""
    return re.sub(r'//[^\n]*|/\*.*?\*/|("(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\\n]|\\.)+\')',
                  # A block comment keeps its line breaks, so the lines reported stay the file's own.
                  lambda m: m.group(1) or ("\n" * m.group(0).count("\n") or " " if m.group(0).startswith("/*") else ""),
                  text, flags=re.S)


def required_boundaries(root: Path) -> dict[str, list[str]]:
    """verifyAndroidBoundaries' map of test class to method, the Android level suffix dropped."""
    text = (root / GRADLE).read_text(encoding="utf-8")
    task = re.search(r'tasks\.register\("verifyAndroidBoundaries"\)(.*?)\n\s*\)\n\s*val factory', text, re.S)
    if not task:
        raise SystemExit(f"{GRADLE}: verifyAndroidBoundaries' required map wasn't found")
    required: dict[str, list[str]] = {}
    for suite, names in re.findall(r'"([\w.]+Test)"\s+to\s+listOf\((.*?)\)', task.group(1), re.S):
        methods = [re.sub(r"\[\d+]$", "", name) for name in re.findall(r'"([^"]+)"', names)]
        required.setdefault(suite, [])
        required[suite] += [m for m in methods if m not in required[suite]]
    if not required:
        raise SystemExit(f"{GRADLE}: verifyAndroidBoundaries names no test")
    return required


def stale_boundaries(root: Path) -> list[str]:
    stale = []
    for suite, methods in required_boundaries(root).items():
        base = root / TESTS / suite.replace(".", "/")
        source = next((p for p in (base.with_suffix(".java"), base.with_suffix(".kt")) if p.is_file()), None)
        if source is None:
            stale.append(f"boundary class {suite} has no source under {TESTS}")
            continue
        declared = set(TEST_METHOD.findall(strip_comments(source.read_text(encoding="utf-8"))))
        stale += [f"boundary {suite.rsplit('.', 1)[1]}.{m} names no test method" for m in methods if m not in declared]
    return stale


def call_arguments(text: str, start: int) -> list[str] | None:
    """The top-level arguments of the call whose '(' is at start, or None when it never closes."""
    depth, args, current, i = 0, [], start + 1, start
    while i < len(text):
        c = text[i]
        if c == '"':
            i = re.match(STRING, text[i:]).end() + i if re.match(STRING, text[i:]) else i + 1
            continue
        if c == "'":
            close = re.match(r"'(?:[^'\\]|\\.)*'", text[i:])
            i += close.end() if close else 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                args.append(text[current:i])
                return [a.strip() for a in args]
        elif c == "," and depth == 1:
            args.append(text[current:i])
            current = i + 1
        i += 1
    return None


def literal_text(argument: str) -> str | None:
    """The text of an argument made only of string literals joined by '+', else None."""
    parts = re.fullmatch(rf"\s*{STRING}(?:\s*\+\s*{STRING})*\s*", argument)
    if not parts:
        return None
    return "".join(unescape(m[1:-1]) for m in re.findall(STRING, argument))


def sentence_like(value: str) -> bool:
    """A message a source writes, not a key, a class or enum name, a file size or a count: three
    plain words in a row, one of them a real word of four letters or more."""
    return (re.search(rf"[^\W_]+(?: [^\W_]+){{{SENTENCE_WORDS - 1},}}", value) is not None
            and re.search(r"[^\W\d_]{4,}", value) is not None)


def expected_texts(path: Path) -> list[tuple[int, str]]:
    """(line, text) for each message a test expects: assertEquals' expected value, contains()'s argument.

    A text the test also writes somewhere other than an expected value is data the test hands in
    itself, so it's left out.
    """
    text = strip_comments(path.read_text(encoding="utf-8"))
    expected = []
    for call in re.finditer(r"\b(assertEquals|contains)\s*\(", text):
        args = call_arguments(text, call.end() - 1)
        if not args:
            continue
        if call.group(1) == "contains":
            argument = args[0]
        else:
            argument = args[1] if len(args) >= 3 else args[0]
        value = literal_text(argument)
        if value and sentence_like(value):
            at = text.index(argument, call.end())
            expected.append((text.count("\n", 0, call.start()) + 1, value, at, at + len(argument)))
    data = set()
    for run in re.finditer(rf"{STRING}(?:\s*\+\s*{STRING})*", text):
        if not any(begin <= run.start() < end for _, _, begin, end in expected):
            data.add(literal_text(run.group(0)))
    return [(line, value) for line, value, _, _ in expected if value not in data]


def corpus(root: Path) -> tuple[str, str, list[re.Pattern]]:
    """Every source string (adjacent '+' literals joined) and table text, the same with numbers
    as '#', and two patterns per format string: its whole output and any start of it."""
    texts = []
    for folder in SOURCES:
        for path in sorted((root / folder).rglob("*.java")):
            code = strip_comments(path.read_text(encoding="utf-8"))
            for run in re.finditer(rf"{STRING}(?:\s*\+\s*{STRING})*", code):
                texts.append(literal_text(run.group(0)) or "")
    for table in sorted((root / TABLES).glob("*.tsv")):
        for line in table.read_text(encoding="utf-8").splitlines():
            texts += [cell.replace("\n", "\n").replace("\t", "\t") for cell in line.split("\t")]
    patterns = []
    for value in texts:
        if FORMAT.search(value.replace("%%", "")) and len(FORMAT.sub("", value).strip()) >= MIN_FIXED:
            patterns += format_patterns(value)
    joined = "\x00".join(texts)
    numbers = re.sub(r"%(?:\d+\$)?d", "#", joined)
    return joined, numbered(numbers), patterns


def numbered(text: str) -> str:
    return re.sub(r"\d+(?:[.,]\d+)*", "#", text)


def format_patterns(value: str) -> list[re.Pattern]:
    """Patterns for whatever the format string prints, whole and cut short past its first
    placeholder: a number for %d, any text for the others."""
    fixed, gaps, last = [], [], 0
    for spec in FORMAT.finditer(value):
        fixed.append(value[last:spec.start()])
        kind = spec.group(0)[-1]
        gaps.append("(%)" if kind == "%" else r"(-?[\d,.]+)" if kind in "df" else "(.+?)")
        last = spec.end()
    fixed.append(value[last:])
    whole = re.escape(fixed[0]) + "".join(gap + re.escape(text) for gap, text in zip(gaps, fixed[1:]))

    def start_of(text: str) -> str:
        pattern = ""
        for char in reversed(text):
            pattern = "(?:" + re.escape(char) + pattern + ")?"
        return pattern

    tail = start_of(fixed[-1])
    for index in range(len(fixed) - 2, 0, -1):
        tail = "(?:" + re.escape(fixed[index]) + gaps[index] + tail + "|" + start_of(fixed[index]) + ")"
    cut = re.escape(fixed[0]) + gaps[0] + tail
    return [re.compile(whole, re.S), re.compile(cut, re.S)]


def printed(pattern: re.Pattern, text: str) -> bool:
    """The format prints text, and its own words are at least half of it, so a placeholder can't
    stand in for most of a message."""
    match = pattern.fullmatch(text)
    if not match:
        return False
    filled = sum(len(gap) for gap in match.groups() if gap)
    return len(text) - filled >= max(MIN_FIXED, len(text) / 2)


def found_in(value: str, joined: str, numbers: str, patterns: list[re.Pattern]) -> bool:
    """In a source text or table (numbers aside), or printed by a format string, whole or cut
    short, sentence by sentence and line by line when it's built from several."""
    text = value.strip(EDGES)
    if not text or text in joined or numbered(text) in numbers or any(printed(p, text) for p in patterns):
        return True
    parts = [part for part in re.split(r"\n|(?<=[.!?])\s+", text) if part.strip(EDGES)]
    return len(parts) > 1 and all(not sentence_like(part) or found_in(part, joined, numbers, patterns) for part in parts)


def stale_texts(root: Path) -> list[str]:
    joined, numbers, patterns = corpus(root)
    stale = []
    for path in sorted((root / SETTINGS_TESTS).glob("*Test.java")):
        for line, value in expected_texts(path):
            if not found_in(value, joined, numbers, patterns):
                shown = value if len(value) <= 80 else value[:77] + "..."
                stale.append(f"{path.name}:{line} expects text no source or table has: {shown!r}")
    return stale


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    root = parser.parse_args(argv).root.resolve()
    stale = stale_boundaries(root) + stale_texts(root)
    for line in stale:
        print(line)
    if stale:
        print(f"{len(stale)} stale test name(s) or text(s)")
        return 1
    print("every boundary test and settings text is still in the sources")
    return 0


if __name__ == "__main__":
    sys.exit(main())
