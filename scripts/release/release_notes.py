"""Release notes in the shape Morphe Manager draws, from one CHANGELOG section.

Manager shows the bundle description for the current version and the GitHub release body for
history, and it reads both as a changelog: a version heading, then Features and Bug Fixes (and
Improvements) lists of short scoped bullets, each list its own card. So the notes are that and
nothing else: the version heading with its compare link, the section's sub-headings with every
bullet under them exactly once, and a last Validation section with the one sentence the release
gate holds to the test results. The README has the intro, the install steps and the requirements.

The CHANGELOG section sorts its bullets under ### Features, ### Bug Fixes and ### Improvements,
and each bullet opens with a scope, **Instagram:** or **Instagram - Patch name:** (Manager keeps a
scoped bullet for an app when the scope, or the part before " - ", is the app's name), or
**Tooling:**. The run stops on a bullet outside a type heading, an unknown heading, an unscoped
bullet, an unknown scope or patch, a missing section, a bullet count that doesn't add up and dashes
the project's writing rules keep out of public text.

    py -3.13 -I scripts/release/release_notes.py --version 0.0.9 --validation validation.md
        --out notes.md --description-out description.json

(one command, split here to fit). --out is the GitHub release body. --description-out is the same
text as a JSON string, ready to be the description in patches-bundle.json. Either can be left out.

--check reads the section (Unreleased when no --version is given) and checks it the same way
without writing anything, which scripts/release/preflight.ps1 runs before the gate.

Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
GPL-3.0-only.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

REPO_URL = "https://github.com/SysAdminDoc/HushGram"
SCOPES = ("Instagram", "Tooling")
# Manager lists its cards in this order whatever order the changelog wrote them in.
SECTIONS = ("Features", "Bug Fixes", "Improvements")
# Em and en dashes, and a spaced hyphen standing in for one.
DASHES = (chr(0x2014), chr(0x2013), " - ")
# A patch scope's label may hold " - ": it's how Manager splits the app from the patch.
PATCH_SCOPE_RE = re.compile(r"\*\*Instagram - [^*:]+:\*\*")
BULLET_RE = re.compile(r"\* \*\*([^*:]+):\*\* (.+)")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
# Manager strips these from a bullet, so none belongs in the notes.
COMMIT_LINK_RE = re.compile(r"\(\[[0-9a-f]{7,}\]\([^)]+/commit/[^)]+\)\)")
IMAGE_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)")


class NotesError(Exception):
    """A section or a piece of the notes the rules refuse."""


def read_section(changelog: str, version: str | None) -> tuple[str, str | None, str | None]:
    """One CHANGELOG section: its body, its date and the dated release under it (None if no more).

    A dated release, or Unreleased when version is None (which has no date).
    """
    text = changelog.replace("\r\n", "\n")
    heading = r"Unreleased" if version is None else rf"{re.escape(version)} \((\d{{4}}-\d{{2}}-\d{{2}})\)"
    match = re.search(rf"^## {heading}[ \t]*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not match:
        what = "Unreleased section" if version is None else f"dated section for {version}"
        raise NotesError(f"the CHANGELOG has no {what}")
    date = match.group(1) if version is not None else None
    previous = re.search(r"^## (\d+\.\d+\.\d+) \(\d{4}-\d{2}-\d{2}\)", text[match.end():], re.M)
    return match.group(match.lastindex), date, previous.group(1) if previous else None


def read_bullets(section: str) -> dict[str, list[str]]:
    """The section's bullets by sub-heading, in CHANGELOG order, a carried-on line joined to its bullet."""
    found: dict[str, list[str]] = {}
    current: str | None = None
    last: list[str] | None = None
    for line in section.split("\n"):
        heading = re.match(r"(#{1,6}) (.+?)\s*$", line)
        if heading:
            if heading.group(1) != "###" or heading.group(2) not in SECTIONS:
                raise NotesError(f"an unknown sub-heading, the section wants {', '.join(SECTIONS)}: {line[:80]}")
            current, last = heading.group(2), None
            found.setdefault(current, [])
        elif line.startswith("* "):
            if current is None:
                raise NotesError(f"a bullet sits outside a type sub-heading: {line[:80]}")
            found[current].append(line)
            last = found[current]
        elif line.strip() and last is not None:
            last[-1] += " " + line.strip()
        elif line.strip():
            raise NotesError(f"a line that belongs to no bullet: {line[:80]}")
    return found


def check_scopes(found: dict[str, list[str]], patches: set[str] | None) -> None:
    """Every bullet is scoped to Instagram (or one of its patches) or Tooling."""
    for bullets in found.values():
        for bullet in bullets:
            scoped = BULLET_RE.match(bullet)
            if not scoped:
                raise NotesError(f"a bullet has no scope: {bullet[:80]}")
            scope = scoped.group(1)
            base, _, patch = scope.partition(" - ")
            if base not in SCOPES or (patch and base != "Instagram"):
                raise NotesError(f"a bullet has the unknown scope {scope}: {bullet[:80]}")
            if patch and patches is not None and patch not in patches:
                raise NotesError(f"a bullet is scoped to {patch}, which isn't a patch in patches-list.json: {bullet[:80]}")


def check_dashes(text: str, where: str) -> None:
    text = PATCH_SCOPE_RE.sub("", text)
    for dash in DASHES:
        if dash in text:
            line = next(l for l in text.split("\n") if dash in l)
            raise NotesError(f"there's a dash ({dash!r}) in {where}: {line[:80]}")


def check_clean(text: str, where: str) -> None:
    for pattern, what in ((COMMIT_LINK_RE, "a commit link"), (IMAGE_RE, "an image")):
        if pattern.search(text):
            raise NotesError(f"there's {what} in {where}, which Manager drops")


def read_validation(validation: str) -> str:
    """The one validation sentence, on one line."""
    lines = [line.strip() for line in validation.replace("\r\n", "\n").split("\n") if line.strip()]
    if len(lines) != 1:
        raise NotesError(f"the validation file holds {len(lines)} lines, it wants the one sentence")
    return lines[0]


def read_patches(path: pathlib.Path) -> set[str]:
    """The patch names of patches-list.json. A missing file stops the run: without it a bullet scoped
    to a patch that doesn't exist would pass unseen."""
    if not path.is_file():
        raise NotesError(f"{path.name} isn't beside the CHANGELOG, so the patch scopes can't be checked")
    document = json.loads(path.read_text(encoding="utf-8"))
    return {patch["name"] for patch in document["patches"]}


def version_heading(version: str, date: str, previous: str | None) -> str:
    if not DATE_RE.match(date):
        raise NotesError(f"the date {date!r} isn't YYYY-MM-DD")
    if previous is None:
        return f"## {version} ({date})"
    return f"## [{version}]({REPO_URL}/compare/v{previous}...v{version}) ({date})"


def build_notes(section: str, validation: str, version: str, date: str, previous: str | None,
                patches: set[str] | None = None) -> tuple[str, int]:
    """The notes, and how many CHANGELOG bullets they carry."""
    found = read_bullets(section)
    total = sum(len(bullets) for bullets in found.values())
    if not total:
        raise NotesError("the section has no bullets")
    check_scopes(found, patches)
    parts = [version_heading(version, date, previous)]
    carried = 0
    for name in SECTIONS:
        if found.get(name):
            parts.append(f"### {name}\n\n" + "\n".join(found[name]))
            carried += len(found[name])
    parts.append("### Validation\n\n" + read_validation(validation))
    if carried != total:
        raise NotesError(f"the notes carry {carried} of the section's {total} bullets")
    body = "\n\n".join(parts) + "\n"
    check_dashes(body, "the notes")
    check_clean(body, "the notes")
    return body, carried


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    root = pathlib.Path(__file__).resolve().parents[2]
    parser.add_argument("--changelog", default=str(root / "CHANGELOG.md"))
    parser.add_argument("--patches", default=None, help="patches-list.json, next to the changelog by default")
    parser.add_argument("--version", help="a dated release section; Unreleased when left out with --check")
    parser.add_argument("--check", action="store_true", help="check the section and write nothing")
    parser.add_argument("--validation", help="file with the one validation sentence")
    parser.add_argument("--out", help="where the notes go, for the GitHub release")
    parser.add_argument("--description-out", help="where the notes go as a JSON string, for patches-bundle.json")
    args = parser.parse_args(argv)

    changelog = pathlib.Path(args.changelog)
    try:
        section, date, previous = read_section(changelog.read_text(encoding="utf-8"), args.version)
        patches = read_patches(pathlib.Path(args.patches) if args.patches else changelog.with_name("patches-list.json"))
        if args.check:
            found = read_bullets(section)
            total = sum(len(bullets) for bullets in found.values())
            if not total:
                raise NotesError("the section has no bullets")
            check_scopes(found, patches)
            everything = "\n".join(b for bullets in found.values() for b in bullets)
            check_dashes(everything, "the section")
            check_clean(everything, "the section")
            counts = ", ".join(f"{len(found[n])} {n}" for n in SECTIONS if found.get(n))
            print(f"[notes] {args.version or 'Unreleased'}: {total} bullets ({counts}), all typed and scoped, no dashes")
            return 0
        if not args.version or date is None:
            raise NotesError("--version names the dated section the notes are for")
        if not args.validation or not (args.out or args.description_out):
            raise NotesError("needs --validation and at least one of --out and --description-out")
        validation = pathlib.Path(args.validation).read_text(encoding="utf-8")
        body, carried = build_notes(section, validation, args.version, date, previous, patches)
    except (NotesError, OSError, KeyError, json.JSONDecodeError) as error:
        print(f"[notes] refused: {error}", file=sys.stderr)
        return 1
    if args.out:
        pathlib.Path(args.out).write_text(body, encoding="utf-8", newline="\n")
    if args.description_out:
        pathlib.Path(args.description_out).write_text(json.dumps(body.rstrip("\n")), encoding="utf-8", newline="\n")
    print(f"[notes] {args.version}: {carried} CHANGELOG bullets, {len(body)} characters")
    return 0


if __name__ == "__main__":
    sys.exit(main())
