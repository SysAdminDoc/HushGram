"""GitHub release notes from one CHANGELOG section, with every bullet in it.

The notes open with an intro and a short What's new list the maintainer writes (#86, #106), then
carry the section's bullets whole, grouped by scope (Instagram, then Tooling), then the install steps
and the validation paragraph. Every bullet of the section lands in the notes exactly once, and the
run stops on a bullet with no known scope, on a section that isn't there, on dashes the project's
writing rules keep out of public text and on a What's new list that isn't 5 to 8 short bullet lines.

    py -3.13 -I scripts/release/release_notes.py --version 0.0.8 --intro intro.md
        --highlights highlights.md --install install.md --validation validation.md --out notes.md

(one command, split here to fit).

--check reads the section (Unreleased when no --version is given) and checks its bullets the same
way without writing anything, which scripts/release/preflight.ps1 runs before the gate.

Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
GPL-3.0-only.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

SCOPES = ("Instagram", "Tooling")
# Em and en dashes, and a spaced hyphen standing in for one.
DASHES = (chr(0x2014), chr(0x2013), " - ")
# The What's new list is for scanning: a few one-line bullets, the full section under it.
HIGHLIGHT_LINES = (5, 8)
HIGHLIGHT_CHARS = 160


class NotesError(Exception):
    """A section or a piece of the notes the rules refuse."""


def read_section(changelog: str, version: str | None) -> str:
    """The body of one CHANGELOG section: a dated release, or Unreleased when version is None."""
    text = changelog.replace("\r\n", "\n")
    heading = r"Unreleased" if version is None else rf"{re.escape(version)} \(\d{{4}}-\d{{2}}-\d{{2}}\)"
    match = re.search(rf"^## {heading}[ \t]*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not match:
        what = "Unreleased section" if version is None else f"dated section for {version}"
        raise NotesError(f"the CHANGELOG has no {what}")
    return match.group(1)


def read_bullets(section: str) -> list[str]:
    """The section's bullets, one string each. A line that carries on a bullet is joined to it."""
    bullets: list[str] = []
    for line in section.split("\n"):
        if line.startswith("* "):
            bullets.append(line)
        elif line.strip() and bullets and not line.startswith("#"):
            bullets[-1] += " " + line.strip()
    return bullets


def group_bullets(bullets: list[str]) -> dict[str, list[str]]:
    """Bullets by scope, with the scope label taken off, in CHANGELOG order within each scope."""
    groups: dict[str, list[str]] = {}
    for bullet in bullets:
        scoped = re.match(r"\* \*\*(\w+):\*\* (.+)", bullet)
        if not scoped:
            raise NotesError(f"a bullet has no scope: {bullet[:80]}")
        if scoped.group(1) not in SCOPES:
            raise NotesError(f"a bullet has the unknown scope {scoped.group(1)}: {bullet[:80]}")
        groups.setdefault(scoped.group(1), []).append("* " + scoped.group(2))
    return groups


def check_dashes(text: str, where: str) -> None:
    for dash in DASHES:
        if dash in text:
            line = next(l for l in text.split("\n") if dash in l)
            raise NotesError(f"there's a dash ({dash!r}) in {where}: {line[:80]}")


def read_highlights(highlights: str) -> str:
    """The What's new list: 5 to 8 bullets of one short line each, blank lines dropped."""
    lines = [line.rstrip() for line in highlights.replace("\r\n", "\n").split("\n") if line.strip()]
    plain = next((line for line in lines if not line.startswith("* ")), None)
    if plain is not None:
        raise NotesError(f"a What's new line isn't a bullet of its own: {plain[:80]}")
    fewest, most = HIGHLIGHT_LINES
    if not fewest <= len(lines) <= most:
        raise NotesError(f"the What's new list has {len(lines)} lines, it wants {fewest} to {most}")
    long = next((line for line in lines if len(line) > HIGHLIGHT_CHARS), None)
    if long is not None:
        raise NotesError(f"a What's new line runs over {HIGHLIGHT_CHARS} characters: {long[:80]}")
    return "\n".join(lines)


def build_notes(section: str, intro: str, highlights: str, install: str, validation: str) -> tuple[str, int]:
    """The notes, and how many CHANGELOG bullets they carry."""
    bullets = read_bullets(section)
    if not bullets:
        raise NotesError("the section has no bullets")
    groups = group_bullets(bullets)
    parts = [intro.strip(), "## What's new\n\n" + read_highlights(highlights)]
    carried = 0
    for scope in SCOPES:
        if scope in groups:
            parts.append(f"## {scope}\n\n" + "\n".join(groups[scope]))
            carried += len(groups[scope])
    parts += ["## Install or update\n\n" + install.strip(), "## Validation\n\n" + validation.strip()]
    if carried != len(bullets):
        raise NotesError(f"the notes carry {carried} of the section's {len(bullets)} bullets")
    body = "\n\n".join(parts) + "\n"
    check_dashes(body, "the notes")
    return body, carried


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("--changelog", default=str(pathlib.Path(__file__).resolve().parents[2] / "CHANGELOG.md"))
    parser.add_argument("--version", help="a dated release section; Unreleased when left out with --check")
    parser.add_argument("--check", action="store_true", help="check the section's bullets and write nothing")
    parser.add_argument("--intro", help="file with the intro paragraph")
    parser.add_argument("--highlights", help="file with the What's new list, 5 to 8 short bullet lines")
    parser.add_argument("--install", help="file with the install steps")
    parser.add_argument("--validation", help="file with the validation paragraph")
    parser.add_argument("--out", help="where the notes go")
    args = parser.parse_args(argv)

    try:
        section = read_section(pathlib.Path(args.changelog).read_text(encoding="utf-8"), args.version)
        if args.check:
            bullets = read_bullets(section)
            if not bullets:
                raise NotesError("the section has no bullets")
            groups = group_bullets(bullets)
            check_dashes("\n".join(bullets), "the section")
            counts = ", ".join(f"{len(groups[s])} {s}" for s in SCOPES if s in groups)
            print(f"[notes] {args.version or 'Unreleased'}: {len(bullets)} bullets ({counts}), all scoped, no dashes")
            return 0
        if not args.version:
            raise NotesError("--version names the dated section the notes are for")
        missing = [name for name in ("intro", "highlights", "install", "validation", "out") if not getattr(args, name)]
        if missing:
            raise NotesError("missing " + ", ".join("--" + name for name in missing))
        read = lambda path: pathlib.Path(path).read_text(encoding="utf-8")
        body, carried = build_notes(section, read(args.intro), read(args.highlights), read(args.install), read(args.validation))
    except (NotesError, OSError) as error:
        print(f"[notes] refused: {error}", file=sys.stderr)
        return 1
    pathlib.Path(args.out).write_text(body, encoding="utf-8", newline="\n")
    print(f"[notes] {args.version}: {carried} CHANGELOG bullets, {len(body)} characters, written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
