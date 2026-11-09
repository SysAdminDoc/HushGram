"""Write the README's patch table and patch counts from patches-list.json.

Copyright 2026 HushGram contributors. GPL-3.0-only.
https://github.com/SysAdminDoc/HushGram

Run it from the repository root after :patches:generatePatchesList:

    py -3.13 -I -X utf8 scripts/gen-readme-patches.py

Only the table rows under "| Patch | What it does | Simple mode |" and the two numbers and the
Instagram version in the sentence above it change. Everything else in the README stays as written.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"
CATALOG = ROOT / "patches-list.json"
PACKAGE = "com.instagram.android"
HEADER = "| Patch | What it does | Simple mode |"
RULE = "|---|---|---|"
COUNTS = re.compile(
    r"There are \d+ patches for `" + re.escape(PACKAGE) + r"`, checked against Instagram [0-9.]+\."
    r" Manager's simple mode includes \d+ of them"
)


def row(patch):
    """One table row. A pipe in a description is escaped so it stays in its column."""
    description = " ".join(patch["description"].split()).replace("|", "\\|")
    mode = "Included" if patch["use"] else "Opt-in"
    return f"| `{patch['name']}` | {description} | {mode} |"


def rewrite(text, patches):
    """The README [text] with its table and counts taken from [patches]."""
    versions = {version for patch in patches for version in patch["compatiblePackages"][PACKAGE]}
    if len(versions) != 1:
        raise SystemExit(f"expected one Instagram version in the catalog, found {sorted(versions)}")
    ordered = sorted(patches, key=lambda patch: patch["name"].casefold())
    included = sum(1 for patch in patches if patch["use"])
    sentence = (
        f"There are {len(patches)} patches for `{PACKAGE}`, checked against Instagram {versions.pop()}."
        f" Manager's simple mode includes {included} of them"
    )
    text, found = COUNTS.subn(lambda _: sentence, text)
    if found != 1:
        raise SystemExit(f"expected one patch count sentence in the README, found {found}")

    lines = text.split("\n")
    if lines.count(HEADER) != 1:
        raise SystemExit("expected one patch table header in the README")
    start = lines.index(HEADER)
    if lines[start + 1] != RULE:
        raise SystemExit("the patch table header isn't followed by its rule")
    end = start + 2
    while end < len(lines) and lines[end].startswith("|"):
        end += 1
    return "\n".join(lines[:start + 2] + [row(patch) for patch in ordered] + lines[end:])


def main():
    patches = json.loads(CATALOG.read_text(encoding="utf-8"))["patches"]
    before = README.read_bytes().decode("utf-8")
    if "\r\n" in before:
        raise SystemExit("README.md has CRLF line endings, convert it to LF first")
    after = rewrite(before, patches)
    if after != before:
        README.write_bytes(after.encode("utf-8"))
    print(f"{len(patches)} patches, README {'updated' if after != before else 'already current'}")


if __name__ == "__main__":
    sys.exit(main())
