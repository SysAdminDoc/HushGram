"""Release notes from one CHANGELOG section: the GitHub release body and the bundle description.

Two texts come out of the section. The GitHub release body reads like Hushfacebook's: the
section's summary as the opening paragraph, then New in this release, Fixes, Other changes and
Behind the scenes (the Tooling bullets), every bullet once with bold turned into backticks, then a
link to the changelog, How to update and What was checked.

The bundle description is what Morphe Manager draws for the current version, so it's a changelog
in Manager's shape: a version heading with its compare link, then Features, Bug Fixes and
Improvements lists of short bullets, each list its own card, and a last Validation section with
the one sentence the release gate holds to the test results. It leaves the summary out.

The CHANGELOG section can open with one summary bullet before its first heading, which the GitHub
body needs (or --summary). The rest sorts under ### Features, ### Bug Fixes and ### Improvements,
or under #### type headings after a ### HushGram v<version> heading in Unreleased. A bullet with no
label is a change to Instagram, and so is one that opens with its topic in bold (**Glass tab
bar:**). **Tooling:** marks a change behind the scenes. HushGram only patches Instagram, so a
bullet doesn't say so. Until 2026-11-04 every one opened with **Instagram:** or **Instagram -
<topic>:**, and a bullet that still does is read as an Instagram change with the label taken off
(its topic kept) in both texts. --drop-labels takes it off in the CHANGELOG when a release is cut.
Morphe Manager 2.2.0 and newer show a single-app source's whole changelog whatever its labels say,
and the README asks for 2.3.0. The run stops on a bullet outside a type heading, an unknown
heading, a summary with a label, a missing section, a bullet count that doesn't add up, dashes
the project's writing rules keep out of public text and a bullet that runs over 40 words (its
closing credit, like Asked for in #12 or Thanks to @name, doesn't count; the summary may run to
120).

    py -3.13 -I scripts/release/release_notes.py --version 0.0.16 --validation validation.md
        --checked walk.md --out notes.md --description-out description.json

(one command, split here to fit). --out is the GitHub release body, and --checked is an optional
paragraph for What was checked after the validation sentence (the walk, say). --description-out
is the Manager text as a JSON string, ready to be the description in patches-bundle.json. Either
output can be left out.

--check reads the section (Unreleased when no --version is given) and checks it the same way
without writing anything, which scripts/release/preflight.ps1 runs before the gate. --drop-labels
rewrites that section of the CHANGELOG with the Instagram label off its bullets and nothing else
changed, which is the last step of moving Unreleased under the release's dated heading.

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
TOOLING = "Tooling"
# Manager lists its cards in this order whatever order the changelog wrote them in.
SECTIONS = ("Features", "Bug Fixes", "Improvements")
# Em and en dashes, and a spaced hyphen standing in for one.
DASHES = (chr(0x2014), chr(0x2013), " - ")
# A bullet and the bold label it may open with: Tooling, or the topic of an Instagram change.
BULLET_RE = re.compile(r"\* (?:\*\*([^*:]+):\*\* )?(.+)")
# The label bullets opened with until 2026-11-04, "**Instagram:** ", or the "Instagram - " in front
# of a topic ("**Instagram - Glass tab bar:**"). It's taken off wherever a bullet is read.
APP_LABEL_RE = re.compile(r"\* \*\*Instagram(?::\*\* |( - )(?=[^*:]+:\*\* ))")
# A bullet's text after its label runs at most this many words, so Manager's card stays short.
MAX_WORDS = 40
SUMMARY_MAX_WORDS = 120
# The GitHub body's group for each CHANGELOG type, as Hushfacebook's notes name them.
GITHUB_GROUPS = {"Features": "New in this release", "Bug Fixes": "Fixes", "Improvements": "Other changes"}
TOOLING_GROUP = "Behind the scenes"
TARGET_PACKAGE = "com.instagram.android"
# A closing sentence that credits or points at an issue doesn't count toward the limit.
CREDIT_RE = re.compile(r"(Asked for|Asked about|Asked in|Reported in|Reported on|Thanks|Fixes from|Refs)\b")
SENTENCE_BREAK_RE = re.compile(r"(?<=[.!?]) (?=[A-Z\[])")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
# Manager strips these from a bullet, so none belongs in the notes.
COMMIT_LINK_RE = re.compile(r"\(\[[0-9a-f]{7,}\]\([^)]+/commit/[^)]+\)\)")
IMAGE_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)")


class NotesError(Exception):
    """A section or a piece of the notes the rules refuse."""


def find_section(text: str, version: str | None) -> re.Match[str]:
    """Where a dated release's section sits in the CHANGELOG, or Unreleased's when version is None.

    The body is the match's last group, after the date when there is one."""
    heading = r"Unreleased" if version is None else rf"{re.escape(version)} \((\d{{4}}-\d{{2}}-\d{{2}})\)"
    match = re.search(rf"^## {heading}[ \t]*\n(.*?)(?=^## |\Z)", text, re.S | re.M)
    if not match:
        what = "Unreleased section" if version is None else f"dated section for {version}"
        raise NotesError(f"the CHANGELOG has no {what}")
    return match


def read_section(changelog: str, version: str | None) -> tuple[str, str | None, str | None]:
    """One CHANGELOG section: its body, its date and the dated release under it (None if no more).

    A dated release, or Unreleased when version is None (which has no date).
    """
    text = changelog.replace("\r\n", "\n")
    match = find_section(text, version)
    date = match.group(1) if version is not None else None
    previous = re.search(r"^## (\d+\.\d+\.\d+) \(\d{4}-\d{2}-\d{2}\)", text[match.end():], re.M)
    return match.group(match.lastindex), date, previous.group(1) if previous else None


def drop_label(bullet: str) -> str:
    """The bullet without the Instagram label it may still open with, a topic after it kept."""
    match = APP_LABEL_RE.match(bullet)
    if not match:
        return bullet
    return ("* **" if match.group(1) else "* ") + bullet[match.end():]


def drop_labels(changelog: str, version: str | None) -> tuple[str, int]:
    """The CHANGELOG with the Instagram label off one section's bullets, and how many had it."""
    text = changelog.replace("\r\n", "\n")
    match = find_section(text, version)
    lines = match.group(match.lastindex).split("\n")
    kept = [drop_label(line) for line in lines]
    dropped = sum(1 for line, new in zip(lines, kept) if line != new)
    start, end = match.span(match.lastindex)
    return text[:start] + "\n".join(kept) + text[end:], dropped


def read_summary(section: str) -> str | None:
    """The one bullet before the section's first heading, a carried-on line joined to it, or None."""
    summary: str | None = None
    for line in section.split("\n"):
        if line.startswith("#"):
            break
        if line.startswith("* "):
            if summary is not None:
                break
            summary = drop_label(line)
        elif line.strip() and summary is not None:
            summary += " " + line.strip()
    return summary


def read_bullets(section: str) -> dict[str, list[str]]:
    """The section's bullets by sub-heading, in CHANGELOG order, a carried-on line joined to its bullet.

    One bullet before the first heading is the summary (read_summary) and isn't among them. A
    bullet still opening with the Instagram label comes back without it (drop_label).
    """
    found: dict[str, list[str]] = {}
    current: str | None = None
    last: list[str] | None = None
    summary: list[str] = []
    type_level = "###"
    for line in section.split("\n"):
        heading = re.match(r"(#{1,6}) (.+?)\s*$", line)
        if heading:
            if (type_level == "###" and not found and heading.group(1) == "###"
                    and re.fullmatch(r"HushGram v\d+\.\d+\.\d+", heading.group(2))):
                type_level = "####"
                continue
            if heading.group(1) != type_level or heading.group(2) not in SECTIONS:
                raise NotesError(f"an unknown sub-heading, the section wants {', '.join(SECTIONS)}: {line[:80]}")
            current, last = heading.group(2), None
            found.setdefault(current, [])
        elif line.startswith("* "):
            if current is None:
                if summary or found or type_level != "###":
                    raise NotesError(f"a bullet sits outside a type sub-heading: {line[:80]}")
                summary.append(line)
                last = summary
                continue
            found[current].append(drop_label(line))
            last = found[current]
        elif line.strip() and last is not None:
            last[-1] += " " + line.strip()
        elif line.strip():
            raise NotesError(f"a line that belongs to no bullet: {line[:80]}")
    return found


def check_summary(summary: str | None) -> None:
    """The bullet before the first heading, when there is one: no label, one paragraph, no dashes."""
    if summary is None:
        return
    labeled = BULLET_RE.match(summary)
    if not labeled or labeled.group(1) is not None:
        raise NotesError(f"a bullet sits outside a type sub-heading, and only one summary with no label may: {summary[:80]}")
    words = len(labeled.group(2).split())
    if words > SUMMARY_MAX_WORDS:
        raise NotesError(f"the summary runs {words} words, the limit is {SUMMARY_MAX_WORDS}: {summary[:80]}")
    check_dashes(summary, "the summary")
    check_clean(summary, "the summary")


def check_dashes(text: str, where: str) -> None:
    for dash in DASHES:
        if dash in text:
            line = next(l for l in text.split("\n") if dash in l)
            raise NotesError(f"there's a dash ({dash!r}) in {where}: {line[:80]}")


def bullet_words(bullet: str) -> int:
    """Words in a bullet's text after its label, if any, not counting closing credit sentences."""
    labeled = BULLET_RE.match(bullet)
    sentences = SENTENCE_BREAK_RE.split(labeled.group(2) if labeled else bullet)
    while len(sentences) > 1 and CREDIT_RE.match(sentences[-1]):
        sentences.pop()
    return len(" ".join(sentences).split())


def check_length(found: dict[str, list[str]]) -> None:
    """Every bullet is short: the long explanations live in the README."""
    for bullets in found.values():
        for bullet in bullets:
            words = bullet_words(bullet)
            if words > MAX_WORDS:
                raise NotesError(f"a bullet runs {words} words, the limit is {MAX_WORDS}: {bullet[:80]}")


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


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", version))


def read_target(path: pathlib.Path) -> str:
    """The newest Instagram version patches-list.json declares."""
    document = json.loads(path.read_text(encoding="utf-8"))
    versions = {version for patch in document["patches"]
                for version in (patch.get("compatiblePackages") or {}).get(TARGET_PACKAGE) or []}
    if not versions:
        raise NotesError(f"{path.name} declares no {TARGET_PACKAGE} version for How to update")
    return max(versions, key=version_key)


def read_manager_floor(path: pathlib.Path) -> str:
    """The manager-floor pin in the version catalog."""
    match = re.search(r'(?m)^\s*manager-floor\s*=\s*"([^"]+)"', path.read_text(encoding="utf-8"))
    if not match:
        raise NotesError(f"{path.name} doesn't pin manager-floor, which How to update names")
    return match.group(1)


def version_heading(version: str, date: str, previous: str | None) -> str:
    if not DATE_RE.match(date):
        raise NotesError(f"the date {date!r} isn't YYYY-MM-DD")
    if previous is None:
        return f"## {version} ({date})"
    return f"## [{version}]({REPO_URL}/compare/v{previous}...v{version}) ({date})"


def build_notes(section: str, validation: str, version: str, date: str, previous: str | None) -> tuple[str, int]:
    """The notes, and how many CHANGELOG bullets they carry."""
    found = read_bullets(section)
    total = sum(len(bullets) for bullets in found.values())
    if not total:
        raise NotesError("the section has no bullets")
    check_length(found)
    check_summary(read_summary(section))
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


def plain(bullet: str) -> str:
    """A CHANGELOG bullet as the GitHub body writes it: bold names in backticks.

    A topic label stays in front, "`Glass tab bar`: text". Tooling's goes, since the bullet sits
    under Behind the scenes."""
    label, text = BULLET_RE.match(bullet).groups()
    text = re.sub(r"\*\*([^*]+)\*\*", r"`\1`", text)
    return f"`{label}`: {text}" if label and label != TOOLING else text


def update_steps(target: str, floor: str) -> str:
    return "\n".join([
        f"1. Update Morphe Manager to {floor} or newer, then refresh the HushGram source.",
        f"2. Patch Instagram `{target}`. [Supported Instagram builds]({REPO_URL}#supported-instagram-builds) "
        "lists the builds that work.",
        "3. Install over your current patched Instagram with the manager's existing signing key. Keep that key so "
        "updates keep working.",
    ])


def build_github_notes(section: str, validation: str, target: str, floor: str,
                       summary: str | None = None, checked: str | None = None) -> tuple[str, int]:
    """The GitHub release body in Hushfacebook's layout, and how many CHANGELOG bullets it carries.

    summary is the opening paragraph when the section has no summary bullet of its own, and
    checked an extra paragraph for What was checked."""
    found = read_bullets(section)
    total = sum(len(bullets) for bullets in found.values())
    if not total:
        raise NotesError("the section has no bullets")
    check_length(found)
    own = read_summary(section)
    check_summary(own)
    if own and summary:
        raise NotesError("the section has a summary bullet and --summary gave another, pick one")
    if own:
        intro = plain(own)
    elif summary and summary.strip():
        intro = " ".join(summary.split())
        check_summary("* " + intro)
    else:
        raise NotesError("the section opens with no summary bullet before its first heading, "
                         "and the GitHub notes open with one (or pass --summary)")
    blocks: dict[str, list[str]] = {name: [] for name in list(GITHUB_GROUPS.values()) + [TOOLING_GROUP]}
    for name in SECTIONS:
        for bullet in found.get(name, []):
            tooling = BULLET_RE.match(bullet).group(1) == TOOLING
            blocks[TOOLING_GROUP if tooling else GITHUB_GROUPS[name]].append(plain(bullet))
    parts = [intro]
    for name, bullets in blocks.items():
        if bullets:
            parts.append(name)
            parts.extend("- " + bullet for bullet in bullets)
    parts.append(f"The [changelog]({REPO_URL}/blob/main/CHANGELOG.md) has every earlier release too.")
    parts.append("How to update")
    parts.append(update_steps(target, floor))
    parts.append(f"The `.mpp` download is a patch bundle, not an Instagram APK. "
                 f"[Installation instructions]({REPO_URL}#install).")
    parts.append("What was checked")
    parts.append(re.sub(r"^Validation:\s*", "", read_validation(validation)))
    if checked and checked.strip():
        parts.append(" ".join(checked.split()))
    body = "\n\n".join(parts) + "\n"
    kept = sum(1 for line in body.split("\n") if line.startswith("- "))
    if kept != total:
        raise NotesError(f"the notes carry {kept} of the section's {total} bullets")
    check_dashes(body, "the notes")
    check_clean(body, "the notes")
    return body, kept


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    root = pathlib.Path(__file__).resolve().parents[2]
    parser.add_argument("--changelog", default=str(root / "CHANGELOG.md"))
    parser.add_argument("--patches", default=None, help="patches-list.json, next to the changelog by default")
    parser.add_argument("--version", help="a dated release section; Unreleased when left out with --check or --drop-labels")
    parser.add_argument("--check", action="store_true", help="check the section and write nothing")
    parser.add_argument("--drop-labels", action="store_true",
                        help="take the Instagram label off the section's bullets in the CHANGELOG, for the release cut")
    parser.add_argument("--validation", help="file with the one validation sentence")
    parser.add_argument("--summary", help="file with the opening paragraph, when the section has no summary bullet")
    parser.add_argument("--checked", help="file with a paragraph for What was checked, after the validation sentence")
    parser.add_argument("--catalog", default=str(root / "gradle" / "libs.versions.toml"),
                        help="the version catalog that pins manager-floor")
    parser.add_argument("--out", help="where the GitHub release body goes")
    parser.add_argument("--description-out", help="where Manager's notes go as a JSON string, for patches-bundle.json")
    args = parser.parse_args(argv)

    changelog = pathlib.Path(args.changelog)
    try:
        text = changelog.read_text(encoding="utf-8")
        section, date, previous = read_section(text, args.version)
        relabeled, labeled = drop_labels(text, args.version)
        if args.drop_labels:
            if labeled:
                changelog.write_text(relabeled, encoding="utf-8", newline="\n")
            print(f"[notes] {args.version or 'Unreleased'}: took the Instagram label off {labeled} bullets")
            return 0
        patches_path = pathlib.Path(args.patches) if args.patches else changelog.with_name("patches-list.json")
        if args.check:
            found = read_bullets(section)
            total = sum(len(bullets) for bullets in found.values())
            if not total:
                raise NotesError("the section has no bullets")
            check_length(found)
            check_summary(read_summary(section))
            everything = "\n".join(b for bullets in found.values() for b in bullets)
            check_dashes(everything, "the section")
            check_clean(everything, "the section")
            counts = ", ".join(f"{len(found[n])} {n}" for n in SECTIONS if found.get(n))
            left = f", {labeled} still open with the Instagram label (--drop-labels takes it off)" if labeled else ""
            print(f"[notes] {args.version or 'Unreleased'}: {total} bullets ({counts}), all typed, short, no dashes{left}")
            return 0
        if not args.version or date is None:
            raise NotesError("--version names the dated section the notes are for")
        if not args.validation or not (args.out or args.description_out):
            raise NotesError("needs --validation and at least one of --out and --description-out")
        validation = pathlib.Path(args.validation).read_text(encoding="utf-8")
        description, carried = build_notes(section, validation, args.version, date, previous)
        github = None
        if args.out:
            summary = pathlib.Path(args.summary).read_text(encoding="utf-8") if args.summary else None
            checked = pathlib.Path(args.checked).read_text(encoding="utf-8") if args.checked else None
            github, _ = build_github_notes(section, validation, read_target(patches_path),
                                           read_manager_floor(pathlib.Path(args.catalog)), summary, checked)
    except (NotesError, OSError, KeyError, json.JSONDecodeError) as error:
        print(f"[notes] refused: {error}", file=sys.stderr)
        return 1
    if github is not None:
        pathlib.Path(args.out).write_text(github, encoding="utf-8", newline="\n")
    if args.description_out:
        pathlib.Path(args.description_out).write_text(json.dumps(description.rstrip("\n")), encoding="utf-8", newline="\n")
    print(f"[notes] {args.version}: {carried} CHANGELOG bullets, {len(github or description)} characters")
    return 0


if __name__ == "__main__":
    sys.exit(main())
