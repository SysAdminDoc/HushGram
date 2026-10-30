"""Holds the bundle description to Morphe Manager's changelog parser, and the GitHub body to its layout.

Manager draws the notes as cards (New, Fixes, Improvements) from the headings, scopes and version
heading it reads. This ports the parts of its ChangelogParser that decide those (sections(),
sectionKindOf(), SCOPED_BODY_RE, VERSION_HEADING, the commit link and image strippers and the
scope matcher), builds the notes for the current Unreleased section as if it were dated (the newest
release's section while Unreleased is empty after a cut), and holds them to what Manager would do
with them.

    py -3.13 -I scripts/test-release-notes.py

Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
GPL-3.0-only.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import pathlib
import re
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("release_notes", ROOT / "scripts" / "release" / "release_notes.py")
notes = importlib.util.module_from_spec(_spec)
sys.modules["release_notes"] = notes
_spec.loader.exec_module(notes)

# --- Manager's parser, the parts that decide sections, kinds, scopes and the version heading ------

VERSION_HEADING = re.compile(
    r"^#{1,3}\s+(?:\S+\s+)?(?:\[([^\]]+)\]\([^)]*\)|([^\s\[(]+))\s+\((\d{4}-\d{2}-\d{2})\)", re.I)
COMMIT_LINK = re.compile(r"\s*\(\[([0-9a-f]{7,})\]\([^)]+/commit/[^)]+\)\)")
SECTION_HEADING = re.compile(r"^\s*#{1,6}\s+(.+?)\s*$")
HEADING_DECORATION = re.compile(r"^[^\w]+")
LIST_ITEM = re.compile(r"^\s*(?:[*+-]|\d+[.)])\s+(.+?)\s*$")
NOISE_LINE = re.compile(
    r"^\s*(?:[-=_*~]{3,}|[<=>]{7}.*|```.*|<[^>]+>|(?:signed-off-by|co-authored-by):.*)\s*$", re.I)
IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
SCOPED_BODY = re.compile(r"^\*\*(.+?):\*\*\s*(.*)$")
KINDS = ["FEATURES", "FIXES", "IMPROVEMENTS", "PERFORMANCE", "APP_SUPPORT", "OTHER"]


def kind_of(title):
    return {
        "features": "FEATURES", "new features": "FEATURES", "bug fixes": "FIXES",
        "improvements": "IMPROVEMENTS", "performance improvements": "PERFORMANCE",
        "updated app support": "APP_SUPPORT",
    }.get((title or "").lower(), "OTHER")


def compact(text):
    return "".join(c for c in text if c.isalnum()).lower()


def matches_scope(scope, names):
    """Manager's SubjectMatcher.matchesScope for an app with no package segments to match."""
    keys = {compact(scope), compact(scope.split(" - ")[0])}
    return any(compact(name) in keys for name in names)


def parse_version_heading(line):
    match = VERSION_HEADING.search(line)
    if not match:
        return None
    return (match.group(1) or match.group(2)).strip(), match.group(3)


def manager_sections(content):
    """ChangelogParser.sections(): (kind, title, items) in Manager's order, items as (scope, text, is_bullet)."""
    sections = []
    title = None
    items = []

    def flush():
        nonlocal items
        if items:
            sections.append((kind_of(title), title, items))
            items = []

    cleaned = IMAGE.sub("", COMMIT_LINK.sub("", content))
    for raw in cleaned.split("\n"):
        line = re.sub(r"^\s*>\s?", "", raw)
        if not line.strip() or NOISE_LINE.match(line):
            continue
        heading = SECTION_HEADING.match(line)
        if heading:
            flush()
            title = HEADING_DECORATION.sub("", heading.group(1)) or heading.group(1)
            continue
        item = LIST_ITEM.match(line)
        if not item:
            items.append((None, line.strip(), False))
            continue
        scoped = SCOPED_BODY.match(item.group(1))
        items.append((scoped.group(1), scoped.group(2), True) if scoped else (None, item.group(1), True))
    flush()
    return sorted(sections, key=lambda s: KINDS.index(s[0]))


def notes_for_unreleased():
    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    section, _, previous = notes.read_section(changelog, None)
    if not any(notes.read_bullets(section).values()):
        # Right after a release is cut, Unreleased is empty and the newest release is what ships.
        newest = re.search(r"^## (\d+\.\d+\.\d+) \(", changelog, re.M).group(1)
        section, _, previous = notes.read_section(changelog, newest)
    patches = notes.read_patches(ROOT / "patches-list.json")
    body, carried = notes.build_notes(section, "Validation: 1 runtime tests passed locally. All 2 patch tests passed too.",
                                      "9.9.9", "2026-01-02", previous, patches)
    bullets = [b for bs in notes.read_bullets(section).values() for b in bs]
    return body, carried, bullets, previous


class ManagerParserTests(unittest.TestCase):
    def test_parser_port_reads_morphes_own_style(self):
        text = ("## [1.46.0](https://github.com/MorpheApp/morphe-patches/compare/v1.45.0...v1.46.0) (2026-10-06)\n\n"
                "### \U0001F41B Bug Fixes\n\n* **Clone app:** Change update permission ([abc1234](https://x/commit/abc1234))\n")
        self.assertEqual(parse_version_heading(text.split("\n")[0]), ("1.46.0", "2026-10-06"))
        (kind, title, items), = manager_sections(text.split("\n", 1)[1])
        self.assertEqual((kind, title, items), ("FIXES", "Bug Fixes", [("Clone app", "Change update permission", True)]))

    def test_scope_matcher(self):
        self.assertTrue(matches_scope("Instagram", ["Instagram"]))
        self.assertTrue(matches_scope("Instagram - Hide Meta AI", ["Instagram"]))
        self.assertFalse(matches_scope("Hide Meta AI", ["Instagram"]))


class ReleaseNotesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.body, cls.carried, cls.bullets, cls.previous = notes_for_unreleased()
        lines = cls.body.split("\n")
        cls.heading = lines[0]
        cls.sections = manager_sections("\n".join(lines[1:]))

    def test_version_heading_is_what_manager_reads(self):
        self.assertEqual(parse_version_heading(self.heading), ("9.9.9", "2026-01-02"))
        self.assertIn(f"/compare/v{self.previous}...v9.9.9)", self.heading)

    def test_features_and_fixes_sections_add_up_to_the_changelog(self):
        by_title = {title: items for _, title, items in self.sections}
        self.assertEqual([title for _, title, _ in self.sections],
                         [t for t in ("Features", "Bug Fixes", "Improvements", "Validation") if t in by_title])
        kinds = {title: kind for kind, title, _ in self.sections}
        for title, kind in (("Features", "FEATURES"), ("Bug Fixes", "FIXES"), ("Improvements", "IMPROVEMENTS")):
            if title in by_title:
                self.assertEqual(kinds[title], kind)
                self.assertTrue(by_title[title])
        changes = sum(1 for title, items in by_title.items() if title != "Validation" for i in items if i[2])
        self.assertEqual(changes, len(self.bullets))
        self.assertEqual(changes, self.carried)
        # Manager reads every item of the three sections as a scoped bullet, not as a loose note
        for title in ("Features", "Bug Fixes", "Improvements"):
            for scope, text, is_bullet in by_title.get(title, []):
                self.assertTrue(is_bullet and scope and text, (title, scope, text[:60]))

    def test_validation_is_the_last_section_and_one_note(self):
        kind, title, items = self.sections[-1]
        self.assertEqual((kind, title), ("OTHER", "Validation"))
        self.assertEqual(len(items), 1)
        self.assertFalse(items[0][2])
        self.assertIn("runtime tests passed locally", items[0][1])

    def test_every_scope_is_instagram_or_tooling(self):
        for _, title, items in self.sections:
            for scope, text, is_bullet in items:
                if is_bullet:
                    self.assertTrue(matches_scope(scope, ["Instagram"]) or scope == "Tooling", (title, scope))

    def test_no_commit_link_or_image_is_left(self):
        self.assertIsNone(notes.COMMIT_LINK_RE.search(self.body))
        self.assertIsNone(COMMIT_LINK.search(self.body))
        self.assertIsNone(IMAGE.search(self.body))

    def test_no_intro_install_or_requirements(self):
        for stale in ("What's new", "Install or update", "Requirements", "## Instagram", "## Tooling"):
            self.assertNotIn(stale, self.body)

    def test_every_bullet_appears_exactly_once(self):
        for bullet in self.bullets:
            self.assertEqual(self.body.count(bullet + "\n"), 1, bullet[:80])


def words(count):
    """A sentence of exactly this many words."""
    return " ".join(["word"] * (count - 1) + ["end."])


def feature(text):
    return "\n### Features\n\n* **Instagram:** " + text + "\n"


class RefusalTests(unittest.TestCase):
    SECTION = "\n### Features\n\n* **Instagram:** A new row.\n\n### Bug Fixes\n\n* **Instagram:** `Hide Meta AI` has a fix.\n"
    # The shape sections from 0.0.17 and before were written in.
    OLD_SECTION = "\n### Features\n\n* **Instagram:** A new row.\n\n### Bug Fixes\n\n* **Instagram - Hide Meta AI:** A fix.\n"
    VALIDATION = "Validation: 1 runtime tests passed locally. All 1 patch tests passed too."

    def build(self, section, patches=None, version="1.0.0"):
        return notes.build_notes(section, self.VALIDATION, version, "2026-01-02", "0.9.0", patches)

    def test_clean_section_builds(self):
        body, carried = self.build(self.SECTION, {"Hide Meta AI"})
        self.assertEqual(carried, 2)
        self.assertTrue(body.endswith("### Validation\n\n" + self.VALIDATION + "\n"))

    def test_unreleased_version_wrapper_keeps_the_same_release_cards(self):
        wrapped = "\n### HushGram v1.0.0\n" + self.SECTION.replace("### ", "#### ")
        self.assertEqual(self.build(wrapped), self.build(self.SECTION))

    def test_updates_can_contain_only_fixes_or_improvements(self):
        for title, kind in (("Bug Fixes", "FIXES"), ("Improvements", "IMPROVEMENTS")):
            section = f"\n### {title}\n\n* **Instagram:** A clearer row.\n"
            body, carried = self.build(section)
            self.assertEqual(carried, 1)
            self.assertEqual(manager_sections(body.split("\n", 1)[1])[0][:2], (kind, title))

    def test_version_wrapper_must_be_first_and_use_nested_types(self):
        for section in (
            "### HushGram v1.0.0\n" + self.SECTION,
            self.SECTION + "\n### HushGram v1.0.0\n",
            "### HushGram v1.0.0\n### HushGram v1.0.0\n#### Features\n* **Instagram:** A row.\n",
            "### HushGram v1.0\n#### Features\n* **Instagram:** A row.\n",
            "#### Features\n* **Instagram:** A row.\n",
        ):
            with self.subTest(section=section), self.assertRaises(notes.NotesError):
                self.build(section)

    def test_refusals(self):
        cases = {
            "bullet outside a heading": ("* **Instagram:** A summary.\n* **Instagram:** A new row.\n", "outside a type sub-heading"),
            "tooling summary": ("* **Tooling:** A summary.\n" + self.SECTION,"only one **Instagram:** summary"),
            "summary inside a version wrapper": ("\n### HushGram v1.0.0\n* **Instagram:** A summary.\n#### Features\n* **Instagram:** A row.\n",
                                                 "outside a type sub-heading"),
            "unknown sub-heading": ("\n### Changes\n\n* **Instagram:** A row.\n", "unknown sub-heading"),
            "unscoped bullet": ("\n### Features\n\n* A new row.\n", "has no scope"),
            "unknown scope": ("\n### Features\n\n* **Docs:** A new row.\n", "unknown scope Docs"),
            "bare patch name as scope": ("\n### Features\n\n* **Hide Meta AI:** A row.\n", "unknown scope Hide Meta AI"),
            "no bullets": ("\n### Features\n\n", "no bullets"),
            "em dash": ("\n### Features\n\n* **Instagram:** A row — here.\n", "dash"),
            "spaced hyphen": ("\n### Features\n\n* **Instagram:** A row - here.\n", "dash"),
            "commit link": ("\n### Features\n\n* **Instagram:** A row ([abc1234](https://x/commit/abc1234)).\n", "commit link"),
            "image": ("\n### Features\n\n* **Instagram:** A row ![x](y.png).\n", "image"),
            "41 word bullet": (feature(words(41)), "runs 41 words"),
            "41 words before a credit": (feature(words(41) + " Asked for in #1."), "runs 41 words"),
            "credit that isn't last": (feature(words(39) + " Refs #89. More."), "runs 42 words"),
        }
        for name, (section, said) in cases.items():
            with self.subTest(name), self.assertRaises(notes.NotesError) as error:
                self.build(section)
            self.assertIn(said, str(error.exception))

    def test_forty_word_bullet_passes_with_or_without_a_credit(self):
        for credit in ("", " Asked for in #12.", " Asked about in #5 and #6.", " Reported in #3.", " Reported on #3.",
                       " Thanks to @name for #4.", " Fixes from @name.", " Refs #89.",
                       " Thanks to @name for #4. Asked for in #80 and #100."):
            with self.subTest(credit):
                body, carried = self.build(feature(words(40) + credit))
                self.assertEqual(carried, 1)
                self.assertIn(words(40) + credit + "\n", body)

    def test_unknown_patch_scope(self):
        with self.assertRaises(notes.NotesError) as error:
            self.build(self.OLD_SECTION, {"Something else"}, version="0.0.17")
        self.assertIn("isn't a patch", str(error.exception))

    def test_patch_scopes_end_after_0_0_17(self):
        for version in ("0.0.2", "0.0.16", "0.0.17"):
            with self.subTest(version):
                self.assertEqual(self.build(self.OLD_SECTION, {"Hide Meta AI"}, version)[1], 2)
        for version in ("0.0.18", "0.1.0", "1.0.0"):
            with self.subTest(version), self.assertRaises(notes.NotesError) as error:
                self.build(self.OLD_SECTION, {"Hide Meta AI"}, version)
            self.assertIn("name the patch in the text", str(error.exception))
        self.assertFalse(notes.takes_patch_scopes(None))

    def test_validation_is_one_line(self):
        with self.assertRaises(notes.NotesError):
            notes.build_notes(self.SECTION, "One.\nTwo.", "1.0.0", "2026-01-02", None)

    def test_first_release_heading_has_no_compare_link(self):
        body, _ = notes.build_notes(self.SECTION, self.VALIDATION, "1.0.0", "2026-01-02", None)
        self.assertTrue(body.startswith("## 1.0.0 (2026-01-02)\n"))

    def test_summary_stays_out_of_the_description(self):
        body, carried = self.build("* **Instagram:** The summary.\n" + self.SECTION, {"Hide Meta AI"})
        self.assertEqual(carried, 2)
        self.assertNotIn("The summary.", body)
        self.assertEqual(body, self.build(self.SECTION, {"Hide Meta AI"})[0])

    def test_long_summary_and_dash(self):
        for summary, said in ((words(121), "summary runs 121 words"), ("A summary - here.", "dash")):
            with self.subTest(said), self.assertRaises(notes.NotesError) as error:
                self.build("* **Instagram:** " + summary + "\n" + self.SECTION)
            self.assertIn(said, str(error.exception))
        self.build("* **Instagram:** " + words(120) + "\n" + self.SECTION)

    def test_unreleased_takes_no_patch_scope(self):
        found = notes.read_bullets(self.OLD_SECTION)
        notes.check_scopes(found, {"Hide Meta AI"})
        with self.assertRaises(notes.NotesError) as error:
            notes.check_scopes(found, {"Hide Meta AI"}, patch_scopes=False)
        self.assertIn("name the patch in the text", str(error.exception))


class CheckCommandTests(unittest.TestCase):
    """release_notes.py --check, which hands --version to the patch scope rule."""

    def check(self, *version):
        changelog = "# Changelog\n\n## Unreleased\n" + "".join(
            f"{RefusalTests.OLD_SECTION}\n## {heading}\n" for heading in ("0.0.18 (2026-11-01)", "0.0.17 (2026-10-28)"))
        changelog += RefusalTests.OLD_SECTION
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder)
            (path / "CHANGELOG.md").write_text(changelog, encoding="utf-8")
            (path / "patches-list.json").write_text(json.dumps({"patches": [{"name": "Hide Meta AI"}]}), encoding="utf-8")
            said = io.StringIO()
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(said):
                code = notes.main(["--changelog", str(path / "CHANGELOG.md"), "--check", *version])
        return code, said.getvalue()

    def test_version_picks_the_scope_rule(self):
        self.assertEqual(self.check("--version", "0.0.17"), (0, ""))
        for version in (("--version", "0.0.18"), ()):
            with self.subTest(version):
                code, said = self.check(*version)
                self.assertEqual(code, 1)
                self.assertIn("name the patch in the text", said)


class GitHubNotesTests(unittest.TestCase):
    """The GitHub release body, laid out like Hushfacebook's."""
    SECTION = ("* **Instagram:** The **summary** of it.\n\n### Improvements\n\n* **Tooling:** A script.\n"
               "* **Instagram:** A clearer row.\n\n### Features\n\n* **Instagram:** `Hide Meta AI` has a new row.\n\n"
               "### Bug Fixes\n\n* **Instagram - Hide Meta AI:** A fix.\n")
    VALIDATION = "Validation: 1 runtime tests passed locally. All 1 patch tests passed too."

    # SECTION's patch scope is the 0.0.17 shape, which plain() still has to write out.
    def build(self, section, summary=None, checked=None, version: str | None = "0.0.17"):
        return notes.build_github_notes(section, self.VALIDATION, "453.0.0.57.79", "2.0.0", {"Hide Meta AI"},
                                        summary, checked, version)

    def test_layout(self):
        body, carried = self.build(self.SECTION, checked="The walk passed.")
        self.assertEqual(carried, 4)
        self.assertEqual(body, "\n\n".join([
            "The `summary` of it.",
            "New in this release", "- `Hide Meta AI` has a new row.",
            "Fixes", "- `Hide Meta AI`: A fix.",
            "Other changes", "- A clearer row.",
            "Behind the scenes", "- A script.",
            f"The [changelog]({notes.REPO_URL}/blob/main/CHANGELOG.md) has every earlier release too.",
            "How to update",
            "1. Update Morphe Manager to 2.0.0 or newer, then refresh the HushGram source.\n"
            f"2. Patch Instagram `453.0.0.57.79`. [Supported Instagram builds]({notes.REPO_URL}#supported-instagram-builds) "
            "lists the builds that work.\n"
            "3. Install over your current patched Instagram with the manager's existing signing key. Keep that key so "
            "updates keep working.",
            f"The `.mpp` download is a patch bundle, not an Instagram APK. [Installation instructions]({notes.REPO_URL}#install).",
            "What was checked", "1 runtime tests passed locally. All 1 patch tests passed too.", "The walk passed.",
        ]) + "\n")
        self.assertNotIn("**", body)

    def test_summary_from_a_file_when_the_section_has_none(self):
        section = self.SECTION.split("\n", 1)[1]
        body, _ = self.build(section, summary="A summary\nover two lines.\n")
        self.assertTrue(body.startswith("A summary over two lines.\n\nNew in this release\n"))
        for summary, said in ((None, "no **Instagram:** summary"), ("Too - dashed.", "dash")):
            with self.subTest(said), self.assertRaises(notes.NotesError) as error:
                self.build(section, summary=summary)
            self.assertIn(said, str(error.exception))
        with self.assertRaises(notes.NotesError) as error:
            self.build(self.SECTION, summary="Another.")
        self.assertIn("pick one", str(error.exception))

    def test_newer_release_refuses_a_patch_scope(self):
        for version in ("0.0.18", None):
            with self.subTest(version), self.assertRaises(notes.NotesError) as error:
                self.build(self.SECTION, version=version)
            self.assertIn("name the patch in the text", str(error.exception))

    def test_newest_changelog_section(self):
        changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        newest = re.search(r"^## (\d+\.\d+\.\d+) \(", changelog, re.M).group(1)
        section, _, _ = notes.read_section(changelog, newest)
        summary = None if notes.read_summary(section) else "A summary."
        body, carried = notes.build_github_notes(
            section, "Validation: 1 runtime tests passed locally.", notes.read_target(ROOT / "patches-list.json"),
            notes.read_manager_floor(ROOT / "gradle" / "libs.versions.toml"), notes.read_patches(ROOT / "patches-list.json"),
            summary, version=newest)
        self.assertEqual(carried, sum(len(b) for b in notes.read_bullets(section).values()))
        self.assertNotIn("**Instagram", body)
        self.assertNotIn("**Tooling", body)
        self.assertFalse([line for line in body.split("\n") if line.startswith("- Instagram")])


if __name__ == "__main__":
    unittest.main(verbosity=1)
