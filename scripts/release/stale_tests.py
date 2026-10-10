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
    """Java or Kotlin text with comments blanked, string literals kept."""
    return re.sub(r'//[^\n]*|/\*.*?\*/|("(?:[^"\\\n]|\\.)*")',
                  lambda m: m.group(1) if m.group(1) else (" " if m.group(0).startswith("/*") else ""),
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
        declared = set(re.findall(r"(?:\bvoid|\bfun)\s+(\w+)\s*\(", strip_comments(source.read_text(encoding="utf-8"))))
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


def expected_texts(path: Path) -> list[tuple[int, str]]:
    """(line, text) for each literal a test expects: assertEquals' expected value, contains()'s argument."""
    text = strip_comments(path.read_text(encoding="utf-8"))
    found = []
    for call in re.finditer(r"\b(assertEquals|contains)\s*\(", text):
        args = call_arguments(text, call.end() - 1)
        if not args:
            continue
        if call.group(1) == "contains":
            candidates = args[:1]
        elif len(args) == 3:
            candidates = args[1:2]
        else:
            candidates = args[:1]
        for argument in candidates:
            value = literal_text(argument)
            if value and value.strip():
                found.append((text.count("\n", 0, call.start()) + 1, value))
    return found


def corpus(root: Path) -> tuple[str, list[re.Pattern]]:
    """Every source string (adjacent '+' literals joined) and table text, plus a pattern per format string."""
    texts = []
    for folder in SOURCES:
        for path in sorted((root / folder).rglob("*.java")):
            code = strip_comments(path.read_text(encoding="utf-8"))
            for run in re.finditer(rf"{STRING}(?:\s*\+\s*{STRING})*", code):
                texts.append(literal_text(run.group(0)) or "")
    for table in sorted((root / TABLES).glob("*.tsv")):
        for line in table.read_text(encoding="utf-8").splitlines():
            texts += [cell.replace("\\n", "\n").replace("\\t", "\t") for cell in line.split("\t")]
    patterns = []
    for value in texts:
        if FORMAT.search(value.replace("%%", "")):
            patterns.append(format_pattern(value))
    return "\x00".join(texts), patterns


def format_pattern(value: str) -> re.Pattern:
    """A pattern for whatever the format string can print: a number for %d, any text for %s."""
    out, last = [], 0
    for spec in FORMAT.finditer(value):
        out.append(re.escape(value[last:spec.start()]))
        kind = spec.group(0)[-1]
        out.append("%" if kind == "%" else r"-?[\d,.]+" if kind in "dfx" else ".*?")
        last = spec.end()
    out.append(re.escape(value[last:]))
    return re.compile("".join(out), re.S)


def found_in(value: str, joined: str, patterns: list[re.Pattern]) -> bool:
    """In a source text or table, or the whole text a format string prints."""
    return value in joined or any(p.fullmatch(value) for p in patterns)


def stale_texts(root: Path) -> list[str]:
    joined, patterns = corpus(root)
    stale = []
    for path in sorted((root / SETTINGS_TESTS).glob("*Test.java")):
        for line, value in expected_texts(path):
            if not found_in(value, joined, patterns):
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
